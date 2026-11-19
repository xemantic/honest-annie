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

import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/*
 * The edits of an intent, applied by the harness before its script, see [Phenomenon.Intent.edits].
 * Kept out of the API pasted into the constitutions, see `generateConstitution`:
 * models seeing a function changing files called it from their scripts,
 * escaping the code they changed for a Kotlin string, instead of passing it as it is by the edits of the call.
 * [Edit] itself stays in the cognition, as the record of the edits an intent carried, read when researching a cogitation.
 */

/**
 * Applies the [edits] in their order, each one to the file as the edits before it left it, see [Edit],
 * telling where, e.g. `Edited /app/f.py at line 12, as:`, followed by the edited lines, numbered as by `cat -n`,
 * with [EDIT_CONTEXT] lines around them, as this edit left the file,
 * so that the change is seen, and edited further, without reading the file again.
 * Fails, leaving every file unchanged, when any edit fails, naming it and why:
 * the old text not occurring, naming the line closest to its first one, or occurring more than once, naming their lines,
 * also showing the lines where the old text occurs with other whitespace, e.g. indented otherwise, to copy them exactly,
 * or the file around the occurrences of an old text occurring more than once, to extend it to the one meant.
 */
fun applyEdits(edits: List<Edit>): String {
    val texts = linkedMapOf<java.nio.file.Path, String>()
    val reports = edits.mapIndexed { index, edit ->
        fun fail(reason: String): Nothing = throw IllegalArgumentException("Edit ${index + 1} of ${edits.size} failed: $reason")
        val file = Path(edit.path)
        val old = edit.old
        if (old == null) {
            texts[file] = edit.new
            "Wrote ${edit.path}, ${edit.new.lineCount} lines"
        } else {
            if (old.isEmpty()) fail("the old text is empty: give it, or omit it to write the whole file")
            val text = texts[file] ?: if (file.exists()) file.readText() else {
                fail("no file at ${edit.path} to edit: write it whole, without the old text")
            }
            val edited = text.edited(edit.path, old, edit.new, edit.all)
            edited.failure?.let { fail(if (edit.all) it else it.replaceFirst("occur once", "occur once, or replace all")) }
            texts[file] = edited.text
            "Edited ${edit.path} at line${if (edited.lines.size == 1) "" else "s"} ${edited.lines.joinToString()}, as:\n" +
                edited.text.numbered(edited.spans)
        }
    }
    texts.forEach { (file, text) ->
        file.parent?.createDirectories()
        file.writeText(text)
    }
    return reports.joinToString("\n")
}

/** How many lines around the edited ones the report of an edit shows, see [applyEdits]. */
private const val EDIT_CONTEXT = 3

/** Around how many occurrences of an old text occurring more than once its failure shows the file, see [applyEdits]. */
private const val AMBIGUITY_SHOWN = 5

/** At most how many lines of a single edited span the report of an edit shows, the middle ones omitted. */
private const val EDIT_SPAN_LINES = 30

/**
 * This text with the [old] text replaced by the [new] one, the lines where the replacements start,
 * and the spans of lines of the new text, from the first to the last, or why it could not be.
 */
private class Edited(
    val text: String,
    val lines: List<Int> = emptyList(),
    val spans: List<IntRange> = emptyList(),
    val failure: String? = null
)

private fun String.edited(path: String, old: String, new: String, all: Boolean): Edited {
    fun line(index: Int) = substring(0, index).count { it == '\n' } + 1
    val occurrences = generateSequence(indexOf(old).takeIf { it >= 0 }) { previous ->
        indexOf(old, previous + if (all) old.length else 1).takeIf { it >= 0 }
    }.toList()
    if (occurrences.isEmpty()) return Edited(this, failure = absence(path, old))
    if (occurrences.size > 1 && !all) {
        val shown = occurrences.take(AMBIGUITY_SHOWN)
        val spans = shown.map { at -> line(at)..line(at + old.removeSuffix("\n").length) }
        return Edited(this, failure =
            "the old text occurs ${occurrences.size} times in $path, at lines ${occurrences.joinToString { "${line(it)}" }}: extend it to occur once" +
                ", the file around ${if (shown.size < occurrences.size) "the first ${shown.size} of them" else "them"} being:\n" +
                numbered(spans)
        )
    }
    val text = buildString {
        var from = 0
        occurrences.forEach { at ->
            append(this@edited, from, at).append(new)
            from = at + old.length
        }
        append(this@edited, from, this@edited.length)
    }
    val spans = occurrences.mapIndexed { index, at ->
        val start = at + index * (new.length - old.length)
        val end = start + new.removeSuffix("\n").length
        text.substring(0, start).count { it == '\n' } + 1..text.substring(0, end).count { it == '\n' } + 1
    }
    return Edited(text, occurrences.map(::line), spans)
}

/**
 * Why the [old] text, not occurring in this text of the file at the [path], could not be replaced:
 * showing where it occurs with other whitespace, e.g. indented with spaces instead of tabs, as retyped by a model,
 * or else naming the line closest to its first one.
 */
private fun String.absence(path: String, old: String): String {
    val lines = lines()
    fun String.squeezed() = filterNot(Char::isWhitespace)
    val wanted = old.removeSuffix("\n").lines().map { it.squeezed() }
    if (wanted.any { it.isNotEmpty() }) {
        val squeezed = lines.map { it.squeezed() }
        val found = (0..lines.size - wanted.size).filter { at ->
            wanted.indices.all { i ->
                val line = squeezed[at + i]
                when {
                    wanted.size == 1 -> wanted[i] in line
                    i == 0 -> line.endsWith(wanted[i])
                    i == wanted.lastIndex -> line.startsWith(wanted[i])
                    else -> line == wanted[i]
                }
            }
        }
        when (found.size) {
            0 -> {}
            1 -> return "the old text does not occur in $path, but does with other whitespace, at line ${found.single() + 1}, as:\n" +
                numbered(listOf(found.single() + 1..found.single() + wanted.size), context = 0)
            else -> return "the old text does not occur in $path, but does with other whitespace, " +
                "at lines ${found.joinToString { "${it + 1}" }}"
        }
    }
    val first = old.lines().firstOrNull { it.isNotBlank() } ?: old
    val closest = lines.indices.maxBy { similarity(first, lines[it]) }
    return "the old text does not occur in $path, its closest line being ${closest + 1}: \"${lines[closest]}\""
}

/**
 * The lines of this text in the [spans], numbered as by `cat -n`, with [context] lines around them,
 * spans overlapping or adjacent after adding it shown as one, and the middle of one longer than [spanLines] omitted.
 */
internal fun String.numbered(spans: List<IntRange>, context: Int = EDIT_CONTEXT, spanLines: Int = EDIT_SPAN_LINES): String {
    val lines = lines().let { if (endsWith('\n')) it.dropLast(1) else it }
    val merged = mutableListOf<IntRange>()
    spans.map { maxOf(1, it.first - context)..minOf(lines.size, it.last + context) }.filterNot { it.isEmpty() }.sortedBy { it.first }.forEach { span ->
        val last = merged.lastOrNull()
        if (last != null && span.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, span.last)
        else merged += span
    }
    fun line(number: Int) = "${number.toString().padStart(6)}\t${lines[number - 1]}"
    return merged.joinToString("\n     …\n") { span ->
        if (span.count() <= spanLines) span.joinToString("\n", transform = ::line)
        else (span.first until span.first + spanLines / 2).joinToString("\n", transform = ::line) +
            "\n     …\t${span.count() - spanLines} lines omitted\n" +
            (span.last - spanLines / 2 + 1..span.last).joinToString("\n", transform = ::line)
    }
}

/** The number of lines of this text, the last one counted also when not ended by a line break. */
private val String.lineCount: Int get() = count { it == '\n' } + if (isEmpty() || endsWith('\n')) 0 else 1

/** How many of the words and symbols of two lines they share, from 0 to 1, whatever their whitespace. */
private fun similarity(a: String, b: String): Double {
    val tokens = Regex("""\w+|[^\s\w]""")
    val left = tokens.findAll(a).map { it.value }.groupingBy { it }.eachCount()
    val right = tokens.findAll(b).map { it.value }.groupingBy { it }.eachCount()
    val shared = left.entries.sumOf { (token, count) -> minOf(count, right[token] ?: 0) }
    return shared.toDouble() / maxOf(left.values.sum(), right.values.sum(), 1)
}
