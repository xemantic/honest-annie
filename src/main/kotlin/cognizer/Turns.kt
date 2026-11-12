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

package com.xemantic.ai.annie.cognizer

import com.xemantic.ai.annie.Actant
import com.xemantic.ai.annie.Cogitation
import com.xemantic.ai.annie.Enunciation
import com.xemantic.ai.annie.Phenomenon
import com.xemantic.ai.annie.Phenomenon.Monologue
import com.xemantic.ai.file.magic.MediaType
import com.xemantic.ai.file.magic.detectMediaType
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/** The constitution as the system prompt, `null` when it is blank, since some APIs reject an empty one. */
val Cogitation.systemPrompt: String? get() = constitution.joinToString(separator = "\n\n").ifBlank { null }

/** A turn of the conversation with a model, the assistant [P]arts being specific to the API. */
sealed interface Turn<out P> {
    /** Text perceived by the model, consecutive text merged into a single part, and the attachments in between. */
    class User(val parts: List<Perceived>) : Turn<Nothing>
    class Assistant<P>(val parts: List<P>) : Turn<P>
}

/** What the model perceives in a [Turn.User]. */
sealed interface Perceived {
    data class Text(val text: String) : Perceived
    /**
     * The [data] passed to the model as is, of the [mediaType] detected from its bytes, not the declared one:
     * the cogitation is replayed at every inference, so data which an API rejects would impede all of them.
     */
    sealed interface Attachment : Perceived {
        val data: Phenomenon.Data
        val mediaType: String
        val base64: String get() = Base64.encode(data.bytes)
        val dataUrl: String get() = "data:$mediaType;base64,$base64"
    }
    data class Image(override val data: Phenomenon.Data, override val mediaType: String) : Attachment
    /** A PDF document, which a model not accepting one, e.g. served by vLLM, rejects. */
    data class Document(override val data: Phenomenon.Data) : Attachment {
        override val mediaType: String get() = MediaType.PDF.mime
    }
    /**
     * The result of the tool call expressing the [intent], see [IntentTool]: what its execution enunciated,
     * always starting the user turn which follows it.
     */
    data class Result(val intent: String, val parts: List<Perceived>) : Perceived
}

/** The result of the tool call of an intent never executed, e.g. interrupted, as an API rejects a tool call without one. */
const val NOT_EXECUTED: String = "<annie:impediment/>\nThe intent was not executed."

/** The maximal size of an image accepted by every API, the one of Claude, a larger one is perceived only as markup. */
const val MAX_IMAGE_SIZE: Int = 5 * 1024 * 1024

/** The maximal size of a PDF document fitting Claude's 32 MB request once encoded in Base64, a larger one is perceived only as markup. */
const val MAX_DOCUMENT_SIZE: Int = 24 * 1024 * 1024

/** The media types of images accepted by every API. */
private val imageMediaTypes = setOf(MediaType.JPEG, MediaType.PNG, MediaType.GIF, MediaType.WEBP)

/**
 * What the cognizer expressed in an inference, see [isExpressed], becomes assistant turns: its intents, monologues and texts.
 * Everything else is perceived in user turns,
 * headed by the actant of the enunciation, the first one by the depth of the cogitation too.
 * The role follows the expression, not the actant:
 * the output an intent enunciates, and the output of an invoked cogitation, are enunciated by [Actant.Type.SELF], yet perceived.
 * The execution of an intent, expressed as a tool call, is perceived as its [Perceived.Result]:
 * the output the intent enunciated, and the response of the computer to it, see [isExecutionOf],
 * while what follows, e.g. the outcomes of the cogitations it invoked, is perceived after it.
 *
 * @param intent the assistant part of the intent.
 * @param monologue the assistant part of the monologue, `null` when it cannot be replayed to the model.
 * @param text the assistant part of the text the cognizer expressed, `null` when there is nothing to replay, e.g. a blank one.
 */
fun <P> Cogitation.toTurns(
    intent: (Phenomenon.Intent) -> P,
    monologue: (Monologue) -> P?,
    text: (Phenomenon.Text) -> P?
): List<Turn<P>> {
    // intent ids are never rendered, so the model would not know what a response to an intent refers to
    val invocations = enunciations.flatMap { it.phenomena }
        .filterIsInstance<Phenomenon.Invocation>()
        .mapTo(mutableSetOf()) { it.id }
    val turns = mutableListOf<Turn<P>>()
    // the intent whose tool call awaits its result, and the result perceived so far
    var called: Phenomenon.Intent? = null
    var result: MutableList<Perceived>? = null
    fun conclude() {
        val intent = called ?: return
        turns += Turn.User(listOf(Perceived.Result(intent.id, result ?: listOf(Perceived.Text(NOT_EXECUTED)))))
        called = null
        result = null
    }
    enunciations.forEach { enunciation ->
        val calling = called
        val expressed = enunciation.isExpressed
        if (calling != null && !expressed) {
            if (enunciation.isExecutionOf(calling)) {
                if (result == null) result = mutableListOf()
            } else {
                conclude()
            }
        } else {
            conclude()
        }
        var headed = false
        enunciation.phenomena.forEach { phenomenon ->
            when (phenomenon) {
                is Intent -> {
                    turns.appendAssistant(intent(phenomenon))
                    called = phenomenon
                }
                is Monologue -> monologue(phenomenon)?.let { turns.appendAssistant(it) }
                is Phenomenon.Text if expressed -> text(phenomenon)?.let { turns.appendAssistant(it) }
                else -> {
                    val text = phenomenon.toText()
                    val perceived = result ?: turns.userParts()
                    perceived.appendText(if (headed) text else "${enunciation.actantTag(invocations)}\n$text")
                    headed = true
                    if (phenomenon is Phenomenon.Data) phenomenon.toAttachment()?.let { perceived += it }
                    if (result == null) turns.replaceUserParts(perceived)
                }
            }
        }
    }
    // an intent awaiting its execution is the last enunciation, followed by nothing to perceive yet
    if (result != null) conclude()
    val first = turns.indexOfFirst { it is Turn.User }
    if (first >= 0) {
        // the first user turn always starts with the text heading the enunciation of the task
        val parts = (turns[first] as Turn.User).parts
        val heading = parts.first()
        if (heading is Perceived.Text) {
            turns[first] = Turn.User(listOf(Perceived.Text("<annie:cogitation depth=\"$depth\"/>\n${heading.text}")) + parts.drop(1))
        }
    }
    return turns
}

/**
 * Whether the cognizer expressed this enunciation in an inference, rather than a script enunciating it as output:
 * it carries its inference, or an intent or a monologue, which only an inference expresses.
 */
private val Enunciation.isExpressed: Boolean get() = actant == Actant.SELF && respondsTo == null
        && (inference != null || phenomena.any { it is Phenomenon.Intent || it is Monologue })

/**
 * Whether this enunciation is part of executing the [intent]: the response of the computer to it,
 * or the output it enunciated, by self, responding to nothing, and not inferred.
 */
private fun Enunciation.isExecutionOf(intent: Phenomenon.Intent): Boolean =
    respondsTo == intent.id || (actant == Actant.SELF && respondsTo == null && inference == null)

private fun Phenomenon.Data.toAttachment(): Perceived.Attachment? {
    val mediaType = bytes.detectMediaType() ?: return null
    return when (mediaType) {
        in imageMediaTypes if bytes.size <= MAX_IMAGE_SIZE -> Perceived.Image(this, mediaType.mime)
        MediaType.PDF if bytes.size <= MAX_DOCUMENT_SIZE -> Perceived.Document(this)
        else -> null
    }
}

/** The parts of the last user turn, to perceive more, empty when the last turn is not a user one. */
private fun <P> List<Turn<P>>.userParts(): MutableList<Perceived> =
    (lastOrNull() as? Turn.User)?.parts?.toMutableList() ?: mutableListOf()

/** The [parts] as the last user turn, replacing it, or following the last assistant turn. */
private fun <P> MutableList<Turn<P>>.replaceUserParts(parts: List<Perceived>) {
    if (lastOrNull() is Turn.User) this[lastIndex] = Turn.User(parts) else this += Turn.User(parts)
}

/** Consecutive text is merged into a single part, an attachment always following its markup. */
private fun MutableList<Perceived>.appendText(text: String) {
    val lastPart = lastOrNull()
    if (lastPart is Perceived.Text) this[lastIndex] = Perceived.Text("${lastPart.text}\n\n$text") else this += Perceived.Text(text)
}

private fun <P> MutableList<Turn<P>>.appendAssistant(part: P) {
    val last = lastOrNull()
    if (last is Turn.Assistant) {
        this[lastIndex] = Turn.Assistant(last.parts + part)
    } else {
        this += Turn.Assistant(listOf(part))
    }
}

/**
 * Renders the actant as in-band markup, the only metadata channel every provider shows to the model.
 * Only stored values are used, so the rendering of past turns never changes and stays cached.
 */
private fun Enunciation.actantTag(invocations: Set<String>): String = buildString {
    append("<annie:actant")
    append(" type=\"").append(actant.type.name.lowercase()).append('"')
    append(" name=\"").append(actant.name.xmlEscaped()).append('"')
    append(" initiated=\"").append(initiated.toMillisecondsString()).append('"')
    if (finalized != initiated) {
        append(" finalized=\"").append(finalized.toMillisecondsString()).append('"')
    }
    respondsTo?.takeIf { it in invocations }?.let { append(" responds-to=\"").append(it.xmlEscaped()).append('"') }
    append("/>")
}

/** Sub-millisecond precision only costs tokens. */
private fun Instant.toMillisecondsString(): String =
    Instant.fromEpochMilliseconds(toEpochMilliseconds()).toString()

private fun String.xmlEscaped(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

private fun Phenomenon.toText(): String = when (this) {
    is Intent -> script
    is Text -> if (contentType == Phenomenon.Text.MARKDOWN) text else {
        "<annie:text content-type=\"${contentType.xmlEscaped()}\"/>\n$text"
    }
    // an image or a PDF document is perceived after this markup, see `toAttachment`
    is Data -> buildString {
        append("<annie:data")
        contentType?.let { append(" content-type=\"").append(it.xmlEscaped()).append('"') }
        append(" size=\"").append(bytes.size).append("\"/>")
    }
    is Invocation -> buildString {
        append("<annie:invocation id=\"").append(id.xmlEscaped()).append('"')
        append("/>")
    }
    is Impediment -> "<annie:impediment/>\n$reason"
    is Monologue -> text
}
