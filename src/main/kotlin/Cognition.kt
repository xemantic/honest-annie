/*
 * honest-annie - A recursive harness for cognizers acting through Kotlin scripts
 * Copyright (C) 2026  Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.ai.annie

import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

sealed interface Phenomenon {
    /** Phenomenon awaiting a response, an [Enunciation] whose [Enunciation.respondsTo] is its [id]. */
    sealed interface Request : Phenomenon {
        val id: String
    }
    /** Request to execute the [script], responded by its result, unless it produced no phenomena, see [Cogitation]. */
    data class Intent(
        val script: String,
        override val id: String = randomId()
    ) : Request
    data class Text(val text: String, val contentType: String = MARKDOWN) : Phenomenon {
        companion object {
            const val MARKDOWN = "text/markdown"
        }
    }
    class Data(val bytes: ByteArray, val contentType: String? = null) : Phenomenon {
        override fun equals(other: Any?): Boolean = other is Data
                && contentType == other.contentType && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = 31 * bytes.contentHashCode() + contentType.hashCode()
        override fun toString(): String = "Data(contentType=$contentType, size=${bytes.size})"
    }
    /**
     * Request to unfold a new cogitation, see [Agency.invoke], also used as the id of that cogitation.
     * Its outcome is perceived later, as an [Enunciation] responding to this [id], see [Cogitation].
     */
    data class Invocation(
        override val id: String,
        val prompt: String,
        val harness: String,
        val constitution: String?
    ) : Request {
        override fun toString(): String = "Invocation(id=$id)"
    }
    /** What impeded the cogitation, or an intent, as the [reason] for it, see [Agency.impede]. */
    data class Impediment(val reason: String) : Phenomenon
    data class Monologue(
        val summary: List<String> = emptyList(),
        val role: Role? = null,
        val continuation: Continuation? = null
    ) : Phenomenon {
        enum class Role { REASONING, PROGRESS_UPDATE }
        sealed interface Continuation {
            /** Model that produced it; needed to decide whether a target model can read it. */
            val producedBy: String
            data class ClaudeSignature(
                override val producedBy: String,
                val signature: String
            ) : Continuation
            data class ClaudeRedacted(
                override val producedBy: String,
                val data: String
            ) : Continuation
            data class OpenAIReasoning(
                override val producedBy: String,
                val itemId: String,
                val encryptedContent: String?,   // null when stored server-side
            ) : Continuation
        }
        enum class Effort { NONE, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX }
        enum class Visibility { HIDDEN, UPDATES, SUMMARY }
    }
}

/** Lossless escape hatch: keep the original JSON and re-emit it byte-for-byte. */
// val raw: JsonObject  ← I'd attach this to Reasoning too, for round-trip safety

data class Actant(
    val type: Type,
    val name: String,
    val spec: String? = null
) {
    enum class Type { SELF, OTHER_AI, HUMAN, COMPUTER, UNIDENTIFIED }
    companion object {
        /** The actant of every enunciation of the cognizer, never revealing its model. */
        val SELF = Actant(type = Type.SELF, name = "self")
    }
}

/**
 * @property initiated the moment the expression of this enunciation started, e.g. the inference request.
 * @property finalized the moment the expression was completed; equal to [initiated] for instantaneous ones.
 * @property respondsTo the id of the [Phenomenon.Request] this enunciation responds to.
 * @property id unique within its cogitation, the [responseId] of the request it responds to,
 * so that enunciating the same response again is recognized as such.
 */
data class Enunciation(
    val actant: Actant,
    val initiated: Instant = Clock.System.now(),
    val finalized: Instant = initiated,
    val respondsTo: String? = null,
    val id: String = respondsTo?.let(::responseId) ?: randomId(),
    val phenomena: List<Phenomenon>
) {
    init {
        require(finalized >= initiated) {
            "Enunciation finalized ($finalized) before initiated ($initiated)"
        }
    }
    val duration: Duration get() = finalized - initiated
}

/**
 * A cogitation is unfolded by its [harness], a Kotlin script whose body is evaluated as a suspending block
 * with a `CoroutineScope` receiver, in the coroutine of the host, and the value of its last expression
 * must be the unfolded [Cogitation]. These properties are provided:
 * - `cogitation`: [Cogitation] to unfold, its [harness] being the source of this very script,
 * - `cognizer`: `Cognizer` of the package `com.xemantic.ai.annie.cognizer`, whose
 *   `suspend fun cognize(cogitation: Cogitation): List<Phenomenon>` makes a single inference,
 *   to be enunciated by [Actant.SELF]; only inferences made this way are recorded,
 * - `executor`: `ScriptExecutor`, whose
 *   `suspend fun execute(script: String, agency: Agency, dependencies: ScriptDependencies.(collecting: Agency) -> Unit): List<Phenomenon>`
 *   executes an intent, providing it with the `dependencies`, e.g. `"cogitation" provides cogitation` and
 *   `"agency" provides collecting`, the agency collecting the phenomena enunciated and invoked through it,
 *   and returns these phenomena, in their order, ignoring the value of the script;
 *   when the intent fails, the [Phenomenon.Impediment] describing the failure follows them,
 * - `agency`: [Agency] within this cogitation, to be passed on to intents.
 *
 * The harness unfolds the cogitation by a single step, e.g. one inference, and is executed
 * again by the host for the next one, until [Actant.SELF] enunciates last, e.g. an intent producing no phenomena:
 * then the cogitation rests, until another interaction progresses it.
 * A cogitation awaiting [unansweredInvocations] is not unfolded further
 * until all of them are answered, the responding [Enunciation]s appended to it.
 * The output of a cogitation, everything enunciated through its agency, is perceived by its invoker
 * once it rests, as the enunciation of [Actant.SELF] responding to the invocation.
 *
 * A harness which fails to compile, throws, evaluates to anything else than a [Cogitation],
 * or evaluates to the given cogitation, not unfolded, has its cogitation rested by the host,
 * with the reason enunciated as a [Phenomenon.Impediment].
 *
 * @property harness the source of the harness script unfolding this cogitation.
 */
data class Cogitation(
    val constitution: List<String>,
    val harness: String,
    val enunciations: List<Enunciation>
) {
    operator fun plus(enunciation: Enunciation): Cogitation = copy(
        enunciations = enunciations + enunciation
    )
    /**
     * Requests enunciated in this cogitation without a response to them, e.g. an intent producing no phenomena,
     * which is not responded.
     */
    val unansweredRequests: List<Phenomenon.Request> get() {
        val responded = enunciations.mapNotNullTo(mutableSetOf()) { it.respondsTo }
        return enunciations.flatMap { it.phenomena }
            .filterIsInstance<Phenomenon.Request>()
            .filterNot { it.id in responded }
    }
    val unansweredInvocations: List<Phenomenon.Invocation> get() =
        unansweredRequests.filterIsInstance<Phenomenon.Invocation>()
}

/** Id of the enunciation responding to the request with [requestId], the same however many times it is enunciated. */
fun responseId(requestId: String): String = "response-to-$requestId"

fun randomId(): String = UUID.randomUUID().toString()
