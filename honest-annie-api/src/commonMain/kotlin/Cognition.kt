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

package com.xemantic.annie

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A change of the file at the [path], carried by an intent, see [Phenomenon.Intent.edits]:
 * passed along the call of the tool, rather than written in its script, so that the code
 * of any language is written as it is, never escaped for a Kotlin string,
 * the only occurrence of the [old] text replaced by the [new] one, or each of its occurrences when [all],
 * or, without [old] text, the [new] text written as the whole file, created with its directories when absent.
 * It is the record of an edit, read in a cogitation, e.g. of the parent one, to learn what its intents changed:
 * an edit created in a script changes no file.
 */
@Serializable
data class Edit(
    val path: String,
    val new: String,
    val old: String? = null,
    val all: Boolean = false
)

@Serializable
sealed interface Phenomenon {
    /** Phenomenon awaiting a response, an [Enunciation] whose [Enunciation.respondsTo] is its [id], e.g. an [Intent]. */
    sealed interface Request : Phenomenon {
        val id: String
    }
    /**
     * Request to apply the [edits] of files, and then to execute the [script] of the [language], unless blank,
     * in the [repl] it names, on the computer it is open on, by default the REPL of its cogitation, see [Cogitation.repl],
     * a REPL no cogitation of the tree has yet being opened on the computer of its cogitation by the first intent naming it,
     * e.g. one started for an invoked cogitation, see [Agency.invoke],
     * its properties in the order of the execution, like the arguments of the tool expressing it,
     * responded by where the edits were applied, followed by the result of the script, unless it produced no phenomena, see [Cogitation].
     * When an edit fails, no file is changed, nor the script executed.
     *
     * The [language] is [Language.KOTLIN], the script executed in the session of the intents, coded against the API of the constitution,
     * [Language.PYTHON], executed by the Python of the intents, see [Agency.python], or [Language.BASH], executed as a process.
     * The script is cancelled once its [timeout] elapses, by default never, but for bash after a minute:
     * a bash process is destroyed, while a Kotlin or Python script blocking its thread, rather than suspending, runs to its end,
     * and the next intents wait for it.
     * Its result is kept whole in the cogitation, while the cognizer perceives at most 30,000 characters of each text of it,
     * the cut telling where in `agency.cogitation()` the whole text is.
     * A [Text] the cognizer expresses along it, in the same enunciation, is its commentary, not an output.
     * The cognizer expresses it by calling the only tool it is offered, whose result is the response,
     * the call being replayed with the [id] of the intent, rather than the one the cognizer gave it.
     */
    @Serializable
    data class Intent(
        val repl: String? = null,
        val edits: List<Edit> = emptyList(),
        val language: Language = Language.KOTLIN,
        val script: String,
        val timeout: Duration? = null,
        override val id: String = randomId()
    ) : Request {
        /** The language of the [script] of an [Intent]. */
        enum class Language {
            KOTLIN,
            PYTHON,
            BASH
        }
    }
    /**
     * Text, e.g. the prompt of a task, the value of an intent, or what the cognizer says:
     * an inference expressing text, and no [Intent], answers with it, which is the output of its cogitation.
     */
    @Serializable
    data class Text(val text: String, val contentType: String = "text/markdown") : Phenomenon
    @Serializable
    class Data(val bytes: ByteArray, val contentType: String? = null) : Phenomenon {
        override fun equals(other: Any?): Boolean = other is Data
                && contentType == other.contentType && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = 31 * bytes.contentHashCode() + contentType.hashCode()
        override fun toString(): String = "Data(contentType=$contentType, size=${bytes.size})"
    }
    /** What impeded the cogitation, or an intent, as the [reason] for it, see [Agency.impede]. */
    @Serializable
    data class Impediment(val reason: String) : Phenomenon
    /**
     * What the cognizer thought before expressing anything else in the same inference, as far as its provider reveals it:
     * the [text] is empty when nothing of it is revealed.
     * Never an output of its cogitation, and replayed only to the model which thought it.
     *
     * @property id unique within its cogitation, like the id of a [Request]:
     * whatever the provider needs to replay the monologue exactly is kept by the cognizer under this id, not here.
     */
    @Serializable
    data class Monologue(
        val text: String,
        val id: String = randomId()
    ) : Phenomenon
}

@Serializable
data class Actant(
    val type: Type,
    val name: String,
    val spec: String? = null
) {
    enum class Type { SELF, INVOKER, OTHER_AI, HUMAN, COMPUTER, UNIDENTIFIED }
    companion object {
        /** The actant of every enunciation of the cognizer, never revealing its model. */
        val SELF = Actant(type = Type.SELF, name = "self")
        /**
         * The actant of the prompt of an invoked cogitation, its first enunciation: the cogitation which invoked it, see [Agency.invoke].
         * The id of the prompt is the one of the invoked cogitation, which its outcome responds to in the invoking one, see [Enunciation.respondsTo].
         */
        val INVOKER = Actant(type = Type.INVOKER, name = "invoker")
    }
}

/**
 * @property initiated the moment the expression of this enunciation started, e.g. the inference request.
 * @property finalized the moment the expression was completed; equal to [initiated] for instantaneous ones.
 * @property respondsTo the id of the [Phenomenon.Request] this enunciation responds to,
 * or of the cogitation invoked, see [Agency.invoke], when this enunciation is its outcome.
 * @property id unique within its cogitation, the [responseId] of what it responds to,
 * so that enunciating the same response again is recognized as such.
 * @property inference the inference expressing this enunciation, `null` when it was not inferred.
 * Never perceived by the cognizer, so that the rendering of past turns never changes.
 */
@Serializable
data class Enunciation(
    val actant: Actant,
    val initiated: Instant = Clock.System.now(),
    val finalized: Instant = initiated,
    val respondsTo: String? = null,
    val id: String = respondsTo?.let(::responseId) ?: randomId(),
    val phenomena: List<Phenomenon>,
    val inference: Inference? = null
) {
    init {
        require(finalized >= initiated) {
            "Enunciation finalized ($finalized) before initiated ($initiated)"
        }
    }
    val duration: Duration get() = finalized - initiated
    /**
     * Whether this is the output of its cogitation, addressed to its invoker, see [Cogitation.output]:
     * enunciated by [Actant.SELF], not responding to anything, e.g. to a request, or as the outcome of an invoked cogitation, and expressing no intent, but something else than a monologue,
     * e.g. what an intent enunciates through its agency, or the text of an inference answering without an intent,
     * unless the inference also expressed a tool call which could not be executed, as an impediment, see `IntentTool`.
     */
    val isOutput: Boolean get() = actant == Actant.SELF && respondsTo == null
            && phenomena.none { it is Phenomenon.Intent } && phenomena.any { it !is Phenomenon.Monologue }
            && (inference == null || phenomena.none { it is Phenomenon.Impediment })
}

/**
 * A single inference of a model, as reported by the provider serving it, the same for every provider.
 *
 * @property model the canonical id of the model which inferred, the same at every endpoint serving it.
 * @property provider who served the inference, e.g. `anthropic`, `bedrock`, `openai`, or the host of another OpenAI-compatible endpoint.
 * @property tokens the numbers of tokens by their category, a category without tokens being absent.
 * The categories are disjoint, so that each token is counted once, and they sum up to the [total].
 * @property stop why the inference stopped, `null` when not reported.
 */
@Serializable
data class Inference(
    val model: String,
    val provider: String,
    val tokens: Map<Tokens, Long> = emptyMap(),
    val stop: Stop? = null
) {
    enum class Tokens {
        /** Input processed anew, neither read from a cache, nor written to it. */
        INPUT,
        /** Input read from a cache, usually billed at a fraction of the [INPUT]. */
        CACHE_READ,
        /** Input written to a cache whose lifetime is not reported. */
        CACHE_WRITE,
        /** Input written to a cache living 5 minutes. */
        CACHE_WRITE_5M,
        /** Input written to a cache living 1 hour. */
        CACHE_WRITE_1H,
        /** Output of the monologue, when reported apart from the rest of the [OUTPUT]. */
        REASONING,
        /** Output, except the [REASONING]. */
        OUTPUT
    }
    val total: Long get() = tokens.values.sum()

    /**
     * Why the inference stopped, the same for every provider.
     *
     * @property reason as reported by the provider, e.g. `end_turn` of Claude, or `length` of Chat Completions.
     * @property category of a refusal, as reported by the provider, e.g. `cyber` of Claude, `null` when not reported.
     */
    @Serializable
    data class Stop(val kind: Kind, val reason: String, val category: String? = null) {
        enum class Kind {
            /** The model completed what it expressed. */
            COMPLETED,
            /** Cut short by a limit of tokens, of the output or of the context window. */
            TRUNCATED,
            /** Cut short by the model refusing to continue, or by a filter of the provider. */
            REFUSED,
            /** Stopped for any other reason, e.g. one introduced by a newer API. */
            OTHER
        }
    }
}

/**
 * A computer the intents of cogitations execute on, see [Cogitation.computer], identified by its [id],
 * of a [type] the host offers, e.g. one with a GPU, a new one being started for an invoked cogitation, see [Agency.invoke].
 * Each computer runs REPLs, sessions keeping the declarations of the intents executed in them, see [Cogitation.repl],
 * each name of a REPL naming a single one in the tree of cogitations, see [Phenomenon.Intent.repl].
 */
@Serializable
data class Computer(
    val id: String = randomId(),
    val type: String = "default"
)

/**
 * A cogitation is unfolded by the harness, the `unfold` function of `Harness.kt`, compiled into honest-annie,
 * whose source is part of the [constitution], and called by the host at each step with:
 * - `agency`: [Agency] within this cogitation, its [Agency.cogitation] being the one to unfold,
 *   local to the host, unlike for the intents, whose agency fetches it from the host,
 * - `cognizer`: `Cognizer` of the package `com.xemantic.annie.harness.cognizer`, whose
 *   `suspend fun cognize(cogitation: Cogitation): Cognized` makes a single inference, returning
 *   `data class Cognized(val phenomena: List<Phenomenon>, val inference: Inference? = null)`:
 *   the phenomena to be enunciated by [Actant.SELF], e.g. a [Phenomenon.Monologue], a [Phenomenon.Text] and a [Phenomenon.Intent],
 *   and the [Inference] expressing them, to be carried by that enunciation,
 * - `executor`: `ScriptExecutor`, whose
 *   `suspend fun execute(intent: Phenomenon.Intent, agency: Agency): ExecutedIntent`
 *   executes an intent expressed in the [Agency.cogitation] of the `agency`: it applies the edits of the `intent` first, all of them or none,
 *   returning where each one was applied as a [Phenomenon.Text], or only the [Phenomenon.Impediment] of the edit failing,
 *   no file being changed, and the script not executed; then it executes the script, unless blank,
 *   in the [Phenomenon.Intent.language] of the intent, a Python or bash one acting through no agency, while a Kotlin one is executed
 *   in the session of the intents of this cogitation, providing it with the `agency`, and returns
 *   `data class ExecutedIntent(val result: List<Phenomenon>, val output: List<Phenomenon>, val invoked: List<Invocation>)`:
 *   the `result` of the intent, the value of the script as a [Phenomenon.Text] in plain text, unless it is `Unit` or `null`,
 *   or, when the script fails, the [Phenomenon.Impediment] describing the failure;
 *   its `output`, what the intent enunciated through the agency, and the cogitations it `invoked`, see [Agency.invoke],
 *   neither of them part of its result.
 *
 * The harness unfolds the cogitation by a single step, one inference and the intent it expresses, and is called
 * again by the host for the next one, until [Actant.SELF] enunciates last, e.g. the output of an intent, which is not responded,
 * or the text of an inference expressing no intent:
 * then the cogitation rests, until another interaction progresses it.
 * The host unfolds each cogitation an intent invoked from its prompt, see [Actant.INVOKER],
 * the invoking cogitation awaiting all of them before it is unfolded further.
 * Once all of them rest, their outcomes follow the response to the intent, in the order they were invoked:
 * the [output] of each one, as the enunciation of [Actant.SELF] responding to the id of the invoked cogitation, the one of its prompt,
 * or the [Phenomenon.Impediment] of the host, when it could not be invoked.
 *
 * A step which throws, e.g. an inference failing, has its cogitation rested by the host,
 * with the reason enunciated as a [Phenomenon.Impediment].
 *
 * @property depth how deep this cogitation is in its tree: 0 for the root one, 1 for one it invokes, and so on.
 * @property computer the computer its intents execute on, unless an intent names a REPL open on another one, see [Phenomenon.Intent.repl].
 * @property repl the REPL of the [computer] its intents execute in, keeping their declarations,
 * shared with the cogitations it invokes, unless they run in another one, see [Agency.invoke].
 */
@Serializable
data class Cogitation(
    val constitution: String,
    val enunciations: List<Enunciation>,
    val depth: Int = 0,
    val computer: Computer = Computer(),
    val repl: String = randomId()
) {
    operator fun plus(enunciation: Enunciation): Cogitation = copy(
        enunciations = enunciations + enunciation
    )
    /**
     * Requests enunciated in this cogitation without a response to them, e.g. an intent enunciating output,
     * which is not responded.
     */
    val unansweredRequests: List<Phenomenon.Request> get() {
        val responded = enunciations.mapNotNullTo(mutableSetOf()) { it.respondsTo }
        return enunciations.flatMap { it.phenomena }
            .filterIsInstance<Phenomenon.Request>()
            .filterNot { it.id in responded }
    }
    /** The phenomena of every [Enunciation.isOutput] of this cogitation, in their order, without the monologues. */
    val output: List<Phenomenon> get() = enunciations.filter { it.isOutput }.flatMap { it.phenomena }
        .filter { it !is Phenomenon.Monologue }
}

/** Id of the enunciation responding to the request with [requestId], the same however many times it is enunciated. */
fun responseId(requestId: String): String = "response-to-$requestId"

fun randomId(): String = Uuid.random().toString()
