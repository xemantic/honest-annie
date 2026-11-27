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

/**
 * The content of the texts, data and monologues of cogitations, see [Phenomenon.Text], [Phenomenon.Data] and [Phenomenon.Monologue],
 * addressed by its ref, the SHA-256 of the content, see [refOf], so that the same content is kept once,
 * and never changes once kept: the phenomena carry only the ref, the cogitations being recalled without their content,
 * which is read on demand, see [Agency.readText].
 *
 * The host keeps the content of every cogitation it unfolds, while an executor sends what its intents create
 * along their result, see [ExecutedIntent.contents], and reads the rest from the host, see [IntentContext.content].
 */
interface Contents {

    /** Keeps the [content], returning its ref, see [refOf]. */
    fun keep(content: ByteArray): String

    /** The content of the [ref], `null` when none is kept. */
    fun content(ref: String): ByteArray?

}

/** The ref of the [content], `sha256:` followed by the hexadecimal SHA-256 of it. */
fun refOf(content: ByteArray): String = "sha256:${sha256(content).toHexString()}"

internal expect fun sha256(content: ByteArray): ByteArray

/** The [Phenomenon.Text] of the [text], whose content is kept. */
fun Contents.text(text: String, contentType: String = "text/markdown"): Phenomenon.Text =
    textOf(text, contentType).also { (_, content) -> keep(content) }.first

/** The [Phenomenon.Text] of the [text] together with its content, to be kept, e.g. sent along the result of an intent. */
fun textOf(text: String, contentType: String = "text/markdown"): Pair<Phenomenon.Text, ByteArray> {
    val content = text.encodeToByteArray()
    return Phenomenon.Text(ref = refOf(content), length = text.length, lines = text.lineCount, contentType = contentType) to content
}

/** The [Phenomenon.Monologue] of the [text], of the [id], whose content is kept. */
fun Contents.monologue(text: String, id: String = randomId()): Phenomenon.Monologue {
    val content = text.encodeToByteArray()
    return Phenomenon.Monologue(ref = keep(content), length = text.length, lines = text.lineCount, id = id)
}

/** The [Phenomenon.Data] of the [bytes], whose content is kept. */
fun Contents.data(bytes: ByteArray, contentType: String? = null): Phenomenon.Data =
    Phenomenon.Data(ref = keep(bytes), size = bytes.size.toLong(), contentType = contentType)

/** Keeps each of the [contents], e.g. of an executed intent, see [ExecutedIntent.contents]. */
fun Contents.keepAll(contents: Map<String, ByteArray>) {
    contents.forEach { (ref, content) ->
        val kept = keep(content)
        check(kept == ref) { "The content of $ref is not the one of its ref, but of $kept" }
    }
}

/**
 * The contents of the texts and data of the [phenomena] kept by these contents, by their refs,
 * e.g. to send what an intent created along its result, rather than what it returned of its cogitation, which the harness keeps.
 */
fun Contents.of(phenomena: List<Phenomenon>): Map<String, ByteArray> =
    phenomena.mapNotNull { it.ref }.distinct().mapNotNull { ref -> content(ref)?.let { ref to it } }.toMap()

/** The content of this phenomenon as text, see [Agency.readText], its content, if any, being kept by these contents. */
fun Contents.readText(phenomenon: Phenomenon, lines: List<IntRange> = emptyList()): String =
    phenomenon.textOf(phenomenon.ref?.let(::required), lines)

/** The content of this phenomenon as bytes, see [Agency.readBytes], its content, if any, being kept by these contents. */
fun Contents.readBytes(phenomenon: Phenomenon): ByteArray =
    phenomenon.bytesOf(phenomenon.ref?.let(::required))

private fun Contents.required(ref: String): ByteArray =
    content(ref) ?: throw NoSuchElementException("No content of $ref is kept")

/** The ref of the content of this phenomenon, see [Contents], `null` when it carries its content itself, e.g. an intent. */
val Phenomenon.ref: String? get() = when (this) {
    is Phenomenon.Text -> ref
    is Phenomenon.Data -> ref
    is Phenomenon.Monologue -> ref
    else -> null
}

/**
 * The content of this phenomenon as text, see [Agency.readText], given the [content] of its [ref], if it has one,
 * in the [lines], each range of line numbers, in their order, the whole text when none is given.
 */
fun Phenomenon.textOf(content: ByteArray?, lines: List<IntRange> = emptyList()): String {
    val text = when (this) {
        is Phenomenon.Text -> checkNotNull(content) { "No content of $ref" }.decodeToString()
        is Phenomenon.Monologue -> checkNotNull(content) { "No content of $ref" }.decodeToString()
        is Phenomenon.Data -> try {
            checkNotNull(content) { "No content of $ref" }.decodeToString(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException(
                "The data${contentType?.let { " of $it" } ?: ""}, $size bytes, is no UTF-8 text, read its bytes with agency.readBytes", e
            )
        }
        is Phenomenon.Intent -> script
        is Phenomenon.Impediment -> reason
    }
    return if (lines.isEmpty()) text else text.selectLines(lines)
}

/** The content of this phenomenon as bytes, see [Agency.readBytes], given the [content] of its [ref], if it has one. */
fun Phenomenon.bytesOf(content: ByteArray?): ByteArray = when (this) {
    is Phenomenon.Text, is Phenomenon.Data, is Phenomenon.Monologue -> checkNotNull(content) { "No content of $ref" }
    else -> textOf(null).encodeToByteArray()
}

/** How many lines this text has, the last one not ending with a line break, see [selectLines]. */
internal val String.lineCount: Int get() = if (isEmpty()) 0 else lineList.size

/** The lines of this text in the [spans], each range of line numbers, in their order, joined by line breaks. */
internal fun String.selectLines(spans: List<IntRange>): String {
    val lines = lineList
    return spans.flatMap { span ->
        (maxOf(1, span.first)..minOf(lines.size, span.last)).map { lines[it - 1] }
    }.joinToString("\n")
}

/** The lines of this text, a line break ending it not starting another one. */
private val String.lineList: List<String> get() = lines().let { if (endsWith('\n')) it.dropLast(1) else it }
