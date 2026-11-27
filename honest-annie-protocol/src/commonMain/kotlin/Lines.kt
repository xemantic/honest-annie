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

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * The [lines] of the file at the [path], numbered, see [numbered], the whole file when none is given:
 * what [Agency.read] reads on the computer of the executor.
 */
fun readNumbered(path: String, lines: List<IntRange>): String {
    val text = SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
    return text.numbered(lines.ifEmpty { listOf(1..Int.MAX_VALUE) }, context = 0, spanLines = Int.MAX_VALUE)
}

/**
 * The lines of this text in the [spans], numbered as by `cat -n`, with [context] lines around them,
 * spans overlapping or adjacent after adding it shown as one, and the middle of one longer than [spanLines] omitted.
 */
internal fun String.numbered(spans: List<IntRange>, context: Int, spanLines: Int): String {
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
