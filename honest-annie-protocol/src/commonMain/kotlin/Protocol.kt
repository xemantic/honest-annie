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

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/*
 * The protocol between the harness and an executor of another process, see [ScriptExecutor]:
 * the harness requests the intents of its cogitations to be executed, each cogitation in a session of its own,
 * and the executor responds with the result of each intent, together with what the intent did within its cogitation,
 * so that the harness enunciates it, as if the intent acted through the agency of the harness itself.
 * While executing an intent, the executor recalls its cogitation from the harness, only when the intent asks for it,
 * see [ExecutorResponse.Recall], as the cogitations stay with the harness, e.g. apart from an isolated computer,
 * and so does the content of their texts and data, loaded only when the intent reads it, see [ExecutorResponse.Load],
 * while the content of the texts and data the intent creates is sent along its result, see [ExecutorResponse.Executed.contents].
 *
 * Each message is a line of JSON, see [encodeMessage], so that a single channel carries the requests of many sessions,
 * e.g. the standard input and output of the executor, the responses following in any order, matched by their id.
 */

/** A request of the harness to the executor. */
@Serializable
sealed interface ExecutorRequest {

    /**
     * Executes the [intent] in the [session] of its cogitation, created by its first intent, as [ScriptExecutor.execute] does,
     * responded by [ExecutorResponse.Executed], or by [ExecutorResponse.Failed] when the executor fails to execute it,
     * not when the intent fails, which is its result,
     * preceded by an [ExecutorResponse.Recall] whenever the intent asks for its cogitation, see [IntentContext].
     */
    @Serializable
    data class Execute(
        val id: String,
        val session: String,
        val intent: Phenomenon.Intent
    ) : ExecutorRequest

    /**
     * The [cogitations] the [ExecutorResponse.Recall] of the [recall] id asks for:
     * the single cogitation of the intent, or its ancestor ones, see [IntentContext.ancestorCogitations].
     */
    @Serializable
    data class Recalled(
        val recall: String,
        val cogitations: List<Cogitation>
    ) : ExecutorRequest

    /** The [content] the [ExecutorResponse.Load] of the [load] id asks for, `null` when the harness keeps none of its ref. */
    @Serializable
    data class Loaded(
        val load: String,
        @Serializable(with = Base64Serializer::class)
        val content: ByteArray?
    ) : ExecutorRequest {
        override fun equals(other: Any?): Boolean = other is Loaded && load == other.load
                && (content?.contentEquals(other.content) ?: (other.content == null))
        override fun hashCode(): Int = 31 * load.hashCode() + content.contentHashCode()
    }

    /** Cancels the execution of the [Execute] request of this [id], e.g. as the cogitation is impeded, responded by nothing. */
    @Serializable
    data class Cancel(val id: String) : ExecutorRequest

    /** Closes the [session], e.g. as its cogitation rests, cancelling what its intents still execute, responded by nothing. */
    @Serializable
    data class Close(val session: String) : ExecutorRequest

}

/**
 * What the agency of the harness tells of the cogitation of an intent, see [Agency.cogitation] and [Agency.ancestorCogitations],
 * and the content of its texts and data, see [Contents],
 * recalled by an executor of another process only when the intent asks for it, see [ExecutorResponse.Recall] and [ExecutorResponse.Load].
 */
interface IntentContext {
    suspend fun cogitation(): Cogitation
    suspend fun ancestorCogitations(): List<Cogitation>
    /** The content of the [ref], see [Phenomenon.Text.ref], `null` when the harness keeps none. */
    suspend fun content(ref: String): ByteArray?
}

/** The [IntentContext] of cogitations at hand, and of the [contents] of their phenomena, e.g. of a REPL in the process of the harness itself. */
fun IntentContext(cogitation: Cogitation, ancestorCogitations: List<Cogitation>, contents: Contents): IntentContext = object : IntentContext {
    override suspend fun cogitation(): Cogitation = cogitation
    override suspend fun ancestorCogitations(): List<Cogitation> = ancestorCogitations
    override suspend fun content(ref: String): ByteArray? = contents.content(ref)
}

/** A response of the executor to the [ExecutorRequest.Execute] of the same [id]. */
@Serializable
sealed interface ExecutorResponse {

    val id: String

    /**
     * Recalls the cogitation of the intent executing, or its ancestor ones when [ancestors], see [IntentContext],
     * answered by [ExecutorRequest.Recalled] of the same [recall] id, the intent still executing.
     */
    @Serializable
    data class Recall(
        override val id: String,
        val recall: String,
        val ancestors: Boolean = false
    ) : ExecutorResponse

    /**
     * Loads the content of the [ref], see [IntentContext.content],
     * answered by [ExecutorRequest.Loaded] of the same [load] id, the intent still executing.
     */
    @Serializable
    data class Load(
        override val id: String,
        val load: String,
        val ref: String
    ) : ExecutorResponse

    /**
     * The [result] of the intent, see [ScriptExecutor.execute], its [output], the cogitations it [invoked],
     * and the [contents] of the texts and data of the [result] and the [output], see [ExecutedIntent].
     */
    @Serializable
    data class Executed(
        override val id: String,
        val result: List<Phenomenon>,
        val output: List<Phenomenon> = emptyList(),
        val invoked: List<Invocation> = emptyList(),
        val contents: Map<String, @Serializable(with = Base64Serializer::class) ByteArray> = emptyMap()
    ) : ExecutorResponse {
        override fun equals(other: Any?): Boolean = other is Executed && id == other.id && result == other.result
                && output == other.output && invoked == other.invoked && contents.keys == other.contents.keys
                && contents.all { (ref, content) -> content.contentEquals(other.contents[ref]) }
        override fun hashCode(): Int = listOf(id, result, output, invoked, contents.keys).hashCode()
    }

    /** The executor failed to execute the intent, for the [reason], e.g. its session failing to start. */
    @Serializable
    data class Failed(
        override val id: String,
        val reason: String
    ) : ExecutorResponse

}

/**
 * Starts each message, so that a message is told from anything else written to the same channel,
 * e.g. what a native library prints to the standard output of the executor,
 * as it never occurs unescaped in JSON.
 */
const val MESSAGE_SEPARATOR: Char = '\u001E'

private val protocolJson = Json { ignoreUnknownKeys = true }

/** Content as Base64, rather than as an array of numbers, about 3 times longer. */
private object Base64Serializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.xemantic.annie.Base64", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) = encoder.encodeString(Base64.encode(value))
    override fun deserialize(decoder: Decoder): ByteArray = Base64.decode(decoder.decodeString())
}

/** The line of this request, ending with a line feed, see [MESSAGE_SEPARATOR]. */
fun ExecutorRequest.encodeMessage(): String =
    "$MESSAGE_SEPARATOR${protocolJson.encodeToString(ExecutorRequest.serializer(), this)}\n"

/** The line of this response, ending with a line feed, see [MESSAGE_SEPARATOR]. */
fun ExecutorResponse.encodeMessage(): String =
    "$MESSAGE_SEPARATOR${protocolJson.encodeToString(ExecutorResponse.serializer(), this)}\n"

/** The request of this [line], or `null` when it carries no message, see [MESSAGE_SEPARATOR]. */
fun decodeRequest(line: String): ExecutorRequest? = line.message()?.let {
    protocolJson.decodeFromString(ExecutorRequest.serializer(), it)
}

/** The response of this [line], or `null` when it carries no message, see [MESSAGE_SEPARATOR]. */
fun decodeResponse(line: String): ExecutorResponse? = line.message()?.let {
    protocolJson.decodeFromString(ExecutorResponse.serializer(), it)
}

/** The message following the last separator of this line, as something else might precede it on the same line. */
private fun String.message(): String? =
    if (MESSAGE_SEPARATOR in this) substringAfterLast(MESSAGE_SEPARATOR).trimEnd('\r') else null

/**
 * The invocation of a cogitation, see [Agency.invoke], made by the agency of the executor:
 * on a new [Computer] of the [computer] type, its first REPL being the [repl] given, or a new one,
 * or on the computer of the invoker, in the [repl] given, or else in a new one when [independent], or in the one of the invoker.
 */
fun invocation(
    prompt: String,
    constitution: String?,
    computer: String?,
    repl: String?,
    independent: Boolean
): Invocation = Invocation(
    id = randomId(),
    prompt = prompt,
    constitution = constitution,
    computer = computer?.let { Computer(type = it) },
    repl = repl ?: if (computer != null || independent) randomId() else null,
    independent = independent
)
