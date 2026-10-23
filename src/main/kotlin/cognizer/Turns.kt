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
import com.xemantic.ai.annie.Enunciation
import com.xemantic.ai.annie.Phenomenon
import com.xemantic.ai.annie.Phenomenon.Monologue
import kotlin.time.Instant

/** A turn of the conversation with a model, the assistant [P]arts being specific to the API. */
sealed interface Turn<out P> {
    class User(val text: String) : Turn<Nothing>
    class Assistant<P>(val parts: List<P>) : Turn<P>
}

/**
 * Intents and monologues become assistant turns, everything else is perceived in user turns,
 * headed by the actant of the enunciation. The role follows the phenomenon, not the actant:
 * the output of an invoked cogitation is enunciated by [Actant.Type.SELF], yet perceived.
 *
 * @param intent the assistant part of the intent.
 * @param monologue the assistant part of the monologue, `null` when it cannot be replayed to the model.
 */
fun <P> List<Enunciation>.toTurns(
    intent: (Phenomenon.Intent) -> P,
    monologue: (Monologue) -> P?
): List<Turn<P>> {
    // intent ids are never rendered, so the model would not know what a response to an intent refers to
    val invocations = flatMap { it.phenomena }
        .filterIsInstance<Phenomenon.Invocation>()
        .mapTo(mutableSetOf()) { it.id }
    return fold(mutableListOf()) { turns, enunciation ->
        var headed = false
        enunciation.phenomena.forEach { phenomenon ->
            when (phenomenon) {
                is Intent -> turns.appendAssistant(intent(phenomenon))
                is Monologue -> monologue(phenomenon)?.let { turns.appendAssistant(it) }
                else -> {
                    val text = phenomenon.toText()
                    turns.appendUser(if (headed) text else "${enunciation.actantTag(invocations)}\n$text")
                    headed = true
                }
            }
        }
        turns
    }
}

private fun <P> MutableList<Turn<P>>.appendUser(text: String) {
    val last = lastOrNull()
    if (last is Turn.User) {
        this[lastIndex] = Turn.User("${last.text}\n\n$text")
    } else {
        this += Turn.User(text)
    }
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
    append("<golem:actant")
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
        "<golem:text content-type=\"${contentType.xmlEscaped()}\"/>\n$text"
    }
    // TODO pass data to the model as attachments, e.g. images and documents
    is Data -> buildString {
        append("<golem:data")
        contentType?.let { append(" content-type=\"").append(it.xmlEscaped()).append('"') }
        append(" size=\"").append(bytes.size).append("\"/>")
    }
    is Invocation -> buildString {
        append("<golem:invocation id=\"").append(id.xmlEscaped()).append('"')
        append("/>")
    }
    is Impediment -> "<golem:impediment/>\n$reason"
    is Monologue -> summary.joinToString(separator = "\n\n")
}
