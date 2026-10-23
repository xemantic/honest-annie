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

package com.xemantic.ai.annie.host

import com.xemantic.ai.annie.Cogitation
import com.xemantic.ai.annie.Enunciation
import com.xemantic.ai.annie.Phenomenon
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Records a cogitation as a folder within [parentDir], named after the moment of its first
 * enunciation, e.g. `2026-10-14T15-02-02.543893Z`. Each phenomenon becomes a file named after
 * its enunciation number, actant type and kind, e.g. `001-human-text.md`, `002-self-intent.kts`,
 * while `cogitation.md` indexes the enunciations, `constitution.md` holds the constitution,
 * and `harness.kts` the harness unfolding the cogitation.
 *
 * Invoked cogitations are recorded in subfolders prefixed with the number of the enunciation
 * holding their invocation, e.g. `003-2026-10-14T15-02-08.074487Z`, see [invoked].
 */
class CogitationRecorder(
    private val parentDir: Path,
    private val prefix: String = ""
) {

    private var dir: Path? = null

    private var recorded = 0

    private val index = StringBuilder()

    /** Numbers of enunciations holding requests, by request id. */
    private val requests = mutableMapOf<String, Int>()

    /** Records the enunciations of the [cogitation] not recorded yet. */
    suspend fun record(
        cogitation: Cogitation
    ): Unit = withContext(Dispatchers.IO) {
        val dir = dir ?: createDir(cogitation).also { dir = it }
        cogitation.enunciations.drop(recorded).forEach { enunciation ->
            recorded++
            record(dir, recorded, enunciation)
        }
        dir.resolve("cogitation.md").writeText(index)
    }

    /** Recorder of the cogitation unfolding the [invocation] enunciated in the recorded one. */
    fun invoked(invocation: Phenomenon.Invocation): CogitationRecorder = CogitationRecorder(
        parentDir = checkNotNull(dir) { "No enunciation recorded yet to invoke a cogitation" },
        prefix = "${number(checkNotNull(requests[invocation.id]) { "Invocation ${invocation.id} not recorded" })}-"
    )

    private fun createDir(cogitation: Cogitation): Path {
        parentDir.createDirectories()
        val started = cogitation.enunciations.firstOrNull()?.initiated
        val name = prefix + (started?.toFileName() ?: "empty")
        // cogitations invoked in parallel might start at the very same moment
        val dir = generateSequence(1) { it + 1 }.firstNotNullOf { attempt ->
            try {
                Files.createDirectory(
                    parentDir.resolve(if (attempt == 1) name else "$name-$attempt")
                )
            } catch (_: FileAlreadyExistsException) {
                null
            }
        }
        dir.resolve("constitution.md").writeText(
            cogitation.constitution.joinToString(separator = "\n\n")
        )
        index.append("# Cogitation started at ${started ?: "-"}\n\n")
        dir.resolve("harness.kts").writeText(cogitation.harness)
        index.append("[constitution](constitution.md) · [harness](harness.kts)\n\n")
        index.append("| # | actant | initiated | duration | phenomena |\n")
        index.append("|---|--------|-----------|----------|-----------|\n")
        return dir
    }

    private fun record(dir: Path, number: Int, enunciation: Enunciation) {
        val actant = enunciation.actant
        val kinds = mutableMapOf<String, Int>()
        val files = enunciation.phenomena.map { phenomenon ->
            if (phenomenon is Phenomenon.Request) requests[phenomenon.id] = number
            val (kind, extension, content) = phenomenon.toFile()
            val count = kinds.merge(kind, 1, Int::plus)!!
            val suffix = if (count == 1) "" else "-$count"
            val file = "${number(number)}-${actant.type.name.lowercase()}-$kind$suffix.$extension"
            dir.resolve(file).writeBytes(content)
            "[$kind$suffix]($file)"
        }
        val responding = enunciation.respondsTo?.let { id ->
            requests[id]?.let { " ↩ ${number(it)}" } ?: " ↩ `$id`"
        } ?: ""
        index.append(
            "| ${number(number)} | ${actant.type.name.lowercase()} `${actant.name}`$responding " +
                    "| ${enunciation.initiated} | ${enunciation.duration} | ${files.joinToString(" ")} |\n"
        )
    }

}

private fun number(n: Int) = n.toString().padStart(3, '0')

private fun Instant.toFileName() = toString().replace(':', '-')

/** The kind of this phenomenon, e.g. `text` or `intent`. */
internal val Phenomenon.kind: String get() = when (this) {
    is Text -> "text"
    is Data -> "data"
    is Intent -> "intent"
    is Invocation -> "invocation"
    is Impediment -> "impediment"
    is Monologue -> "monologue"
}

private fun Phenomenon.toFile(): Triple<String, String, ByteArray> = when (this) {
    is Text -> Triple(kind, contentType.extension ?: "txt", text.encodeToByteArray())
    is Data -> Triple(kind, contentType?.extension ?: "bin", bytes)
    is Intent -> Triple(kind, "kts", script.encodeToByteArray())
    is Invocation -> Triple(kind, "md", buildString {
        append("id: `$id`  \n")
        append("\n").append(prompt)
    }.encodeToByteArray())
    is Impediment -> Triple(kind, "md", reason.encodeToByteArray())
    is Monologue -> Triple(
        kind,
        "md",
        buildString {
            role?.let { append("_${it.name.lowercase()}_\n\n") }
            append(summary.joinToString(separator = "\n\n"))
        }.encodeToByteArray()
    )
}

private val String.extension: String? get() = when (substringBefore(';').trim().lowercase()) {
    "text/markdown" -> "md"
    "text/plain" -> "txt"
    "text/html" -> "html"
    "text/css" -> "css"
    "text/csv" -> "csv"
    "application/json" -> "json"
    "application/xml", "text/xml" -> "xml"
    "application/pdf" -> "pdf"
    "image/png" -> "png"
    "image/jpeg" -> "jpg"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "image/svg+xml" -> "svg"
    else -> null
}
