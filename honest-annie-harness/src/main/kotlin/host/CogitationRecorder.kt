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

package com.xemantic.annie.harness.host

import com.xemantic.annie.Cogitation
import com.xemantic.annie.Contents
import com.xemantic.annie.Edit
import com.xemantic.annie.Enunciation
import com.xemantic.annie.Inference
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.harness.cost
import com.xemantic.annie.readBytes
import com.xemantic.ai.money.Money
import com.xemantic.ai.money.ZERO
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Records a cogitation as a folder within [parentDir], named after the moment of its first
 * enunciation, e.g. `2026-10-14T15-02-02.543893Z`. Each phenomenon becomes a file named after
 * its enunciation number, its position within the enunciation, actant type and kind,
 * e.g. `001-1-human-text.md`, `002-1-self-monologue.md`, `002-2-self-intent.kts`, the script of an intent, `.py` or `.sh` of a Python or bash one,
 * followed by `002-2-self-edits.json`, the edits of files it carries, if any,
 * so that the files are listed in the order of their enunciation, while `cogitation.md` indexes the enunciations, and `constitution.md` holds the constitution,
 * which embeds the source of the harness unfolding the cogitation.
 *
 * Inferences discarded by the harness, not enunciated in the cogitation, are recorded too, for inspection,
 * numbered after the enunciation following them, and by their order, e.g. `002-discarded-1-1-self-intent.kts`,
 * see [record].
 *
 * Invoked cogitations are recorded in subfolders prefixed with the number and position of
 * the intent invoking them, e.g. `002-2-2026-10-14T15-02-08.074487Z`, listed next to `002-2-self-intent.kts`,
 * see [invoked].
 *
 * The content of the texts and data is read from the [contents] keeping it, see [Contents].
 */
class CogitationRecorder(
    private val parentDir: Path,
    private val contents: Contents,
    private val prefix: String = "",
    private val invoker: CogitationRecorder? = null
) {

    /** Guards the state written to the files, updated also by the recorders of invoked cogitations. */
    private val mutex = Mutex()

    private var dir: Path? = null

    private var recorded = 0

    private val index = StringBuilder()

    /** Positions of requests, the numbers of their enunciations followed by their positions within, by request id. */
    private val requests = mutableMapOf<String, String>()

    /** The inferences of the recorded enunciations, discarded ones included, by their labels, e.g. `002`. */
    private val inferences = mutableListOf<Pair<String, Enunciation>>()

    /** The totals of the invoked cogitations, including the ones they invoke, by the names of their directories. */
    private val invoked = linkedMapOf<String, Totals>()

    /**
     * Records the enunciations of the [cogitation] not recorded yet,
     * preceded by the [discarded] inferences, expressed as enunciations never enunciated in the cogitation.
     */
    suspend fun record(
        cogitation: Cogitation,
        discarded: List<Enunciation> = emptyList()
    ): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dir = dir ?: createDir(cogitation).also { dir = it }
            discarded.forEachIndexed { index, enunciation ->
                val label = "${number(recorded + 1)}-discarded-${index + 1}"
                record(dir, label, enunciation, discarded = true)
                if (enunciation.inference != null) inferences += label to enunciation
            }
            cogitation.enunciations.drop(recorded).forEach { enunciation ->
                recorded++
                val label = number(recorded)
                record(dir, label, enunciation, discarded = false)
                if (enunciation.inference != null) inferences += label to enunciation
            }
            write(dir)
        }
    }

    /** Updates the [totals] of the cogitation recorded in the [dir] invoked by this one. */
    private suspend fun invoked(dir: Path, totals: Totals): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            invoked[dir.fileName.toString()] = totals
            write(checkNotNull(this@CogitationRecorder.dir) { "No enunciation recorded yet to invoke a cogitation" })
        }
    }

    /**
     * Writes the index and the inferences, and reports the totals to the [invoker],
     * still holding the lock, so that its updates arrive in order.
     */
    private suspend fun write(dir: Path) {
        val own = inferences.fold(Totals()) { totals, (_, enunciation) -> totals + enunciation.inference!! }
        val total = invoked.values.fold(own, Totals::plus)
        dir.resolve("cogitation.md").writeText(
            if (total.inferences == 0) index else "$index\n${total.describe(invoked = total.inferences - own.inferences)}\n"
        )
        dir.resolve("inferences.json").writeText(inferencesJson(total))
        invoker?.invoked(dir, total)
    }

    private fun inferencesJson(total: Totals) = buildString {
        append("{\n  \"inferences\": [")
        append(inferences.joinToString(separator = ",", postfix = if (inferences.isEmpty()) "" else "\n  ") { (label, enunciation) ->
            val inference = enunciation.inference!!
            """
            |
            |    {
            |      "enunciation": "$label",
            |      "initiated": "${enunciation.initiated}",
            |      "finalized": "${enunciation.finalized}",
            |      "model": ${inference.model.json()},
            |      "provider": ${inference.provider.json()},
            |      "tokens": ${inference.tokens.json()},
            |      "total": ${inference.total},${inference.cost?.let { "\n            |      \"cost\": \"$it\"," } ?: ""}
            |      "stop": ${inference.stop?.let { "{\"kind\": \"${it.kind.name.lowercase()}\", \"reason\": ${it.reason.json()}${it.category?.let { category -> ", \"category\": ${category.json()}" } ?: ""}}" } ?: "null"}
            |    }
            """.trimMargin().trimEnd()
        })
        append("],\n  \"invoked\": {")
        append(invoked.entries.joinToString(separator = ",", postfix = if (invoked.isEmpty()) "" else "\n  ") { (name, totals) ->
            "\n    ${name.json()}: ${totals.json()}"
        })
        append("},\n  \"total\": ${total.json()}\n}\n")
    }

    /**
     * Recorder of the cogitation of the [id] invoked by the [intent] of the id given, enunciated in the recorded one,
     * its outcome indexed as responding to that intent.
     */
    fun invoked(id: String, intent: String): CogitationRecorder {
        val position = checkNotNull(requests[intent]) { "Intent $intent not recorded" }
        requests[id] = position
        return CogitationRecorder(
            parentDir = checkNotNull(dir) { "No enunciation recorded yet to invoke a cogitation" },
            contents = contents,
            prefix = "$position-",
            invoker = this
        )
    }

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
        dir.resolve("constitution.md").writeText(cogitation.constitution)
        index.append("# Cogitation started at ${started ?: "-"}\n\n")
        index.append("[constitution](constitution.md)\n\n")
        index.append("| # | actant | initiated | duration | phenomena | inference |\n")
        index.append("|---|--------|-----------|----------|-----------|-----------|\n")
        return dir
    }

    /** Records the [enunciation] labelled as [label], a [discarded] one being no request to respond to. */
    private fun record(dir: Path, label: String, enunciation: Enunciation, discarded: Boolean) {
        val actant = enunciation.actant
        val kinds = mutableMapOf<String, Int>()
        // positions padded, so that 10 sorts after 9
        val width = enunciation.phenomena.size.toString().length
        val files = enunciation.phenomena.mapIndexed { index, phenomenon ->
            val position = "$label-${(index + 1).toString().padStart(width, '0')}"
            if (phenomenon is Phenomenon.Intent && !discarded) requests[phenomenon.id] = position
            val (kind, extension) = phenomenon.toFile()
            val content = contents.readBytes(phenomenon)
            val count = kinds.merge(kind, 1, Int::plus)!!
            val suffix = if (count == 1) "" else "-$count"
            val file = "$position-${actant.type.name.lowercase()}-$kind.$extension"
            dir.resolve(file).writeBytes(content)
            val edits = (phenomenon as? Phenomenon.Intent)?.edits?.takeIf { it.isNotEmpty() }?.let { edits ->
                val editsFile = "$position-${actant.type.name.lowercase()}-edits.json"
                dir.resolve(editsFile).writeText(edits.json())
                " [edits]($editsFile)"
            } ?: ""
            "[$kind$suffix]($file)$edits"
        }
        val responding = enunciation.respondsTo?.let { id ->
            requests[id]?.let { " ↩ $it" } ?: " ↩ `$id`"
        } ?: ""
        index.append(
            "| $label | ${actant.type.name.lowercase()} `${actant.name}`$responding " +
                    "| ${enunciation.initiated} | ${enunciation.duration} | ${files.joinToString(" ")} " +
                    "| ${enunciation.inference?.let { "`${it.model}` by ${it.provider}: ${it.tokensDescription()}${it.stopDescription()}" } ?: ""} |\n"
        )
    }

}

private fun number(n: Int) = n.toString().padStart(3, '0')

private fun Instant.toFileName() = toString().replace(':', '-')

/** The tokens of this inference by category, e.g. `3 input, 1000 cache write 1h, 40 output`. */
internal fun Inference.tokensDescription(): String = tokens.description().ifEmpty { "no tokens reported" }

/** Why this inference stopped, its kind emphasized unless completed. */
internal fun Inference.stopDescription(): String = stop?.let {
    (if (it.kind == Inference.Stop.Kind.COMPLETED) " · `${it.reason}`" else " · **${it.kind.name.lowercase()}** `${it.reason}`") +
            (it.category?.let { category -> " ($category)" } ?: "")
} ?: ""

/** The categories of tokens the cognizers report, in the order they are listed, any other one following them by name. */
private val knownTokens = listOf("input", "cacheRead", "cacheWrite", "cacheWrite5m", "cacheWrite1h", "reasoning", "output")

private fun Map<String, Long>.sortedEntries() = entries.sortedWith(
    compareBy({ knownTokens.indexOf(it.key).takeIf { index -> index >= 0 } ?: knownTokens.size }, { it.key })
)

/** The words of a lowerCamelCase category, e.g. `cache write 1h` of `cacheWrite1h`. */
private fun String.words() = replace(Regex("(?<=[a-z])(?=[A-Z0-9])"), " ").lowercase()

private fun Map<String, Long>.description() = sortedEntries()
    .joinToString { (category, count) -> "$count ${category.words()}" }

/** The number of [inferences], and their [tokens] summed up by category. */
/**
 * The totals of inferences, their [cost] in US dollars at list prices being `null`
 * when the cost of any of them is unknown, see [Inference.cost].
 */
private data class Totals(
    val inferences: Int = 0,
    val tokens: Map<String, Long> = emptyMap(),
    val cost: Money? = Money.ZERO
) {

    val total: Long get() = tokens.values.sum()

    operator fun plus(other: Totals) = Totals(
        inferences = inferences + other.inferences,
        tokens = (tokens.keys + other.tokens.keys).associateWith { (tokens[it] ?: 0) + (other.tokens[it] ?: 0) },
        cost = other.cost?.let { cost?.plus(it) }
    )

    operator fun plus(inference: Inference) = this + Totals(inferences = 1, tokens = inference.tokens, cost = inference.cost)

    private val knownCost: Money? get() = cost.takeIf { inferences > 0 }

    fun describe(invoked: Int) = "**Total** of $inferences inference${if (inferences == 1) "" else "s"}" +
            (if (invoked == 0) "" else ", $invoked in invoked cogitations") +
            ": ${tokens.description().ifEmpty { "no tokens reported" }}, $total tokens" +
            (knownCost?.let { ", costing $it USD at list prices" } ?: "")

    fun json() = "{\"inferences\": $inferences, \"tokens\": ${tokens.json()}, \"total\": $total" +
            (knownCost?.let { ", \"cost\": \"$it\"" } ?: "") + "}"

}

private fun Map<String, Long>.json() = sortedEntries()
    .joinToString(prefix = "{", postfix = "}") { (category, count) -> "${category.json()}: $count" }

/** These edits as a JSON array, an edit on each line, with only the properties given, see [Edit]. */
private fun List<Edit>.json() = joinToString(separator = ",\n", prefix = "[\n", postfix = "\n]\n") { edit ->
    "  {\"path\": ${edit.path.json()}" +
            (edit.old?.let { ", \"old\": ${it.json()}" } ?: "") +
            ", \"new\": ${edit.new.json()}" +
            (if (edit.all) ", \"all\": true" else "") + "}"
}

private fun String.json() = buildString {
    append('"')
    this@json.forEach { char ->
        when {
            char == '"' || char == '\\' -> append('\\').append(char)
            char == '\n' -> append("\\n")
            char == '\r' -> append("\\r")
            char == '\t' -> append("\\t")
            char < ' ' -> append("\\u%04x".format(char.code))
            else -> append(char)
        }
    }
    append('"')
}

/** The kind of this phenomenon, e.g. `text` or `intent`. */
internal val Phenomenon.kind: String get() = when (this) {
    is Text -> "text"
    is Data -> "data"
    is Intent -> "intent"
    is Impediment -> "impediment"
    is Monologue -> "monologue"
}

/** The kind of this phenomenon, and the extension of the file of its content. */
private fun Phenomenon.toFile(): Pair<String, String> = when (this) {
    is Text -> kind to (contentType.extension ?: "txt")
    is Data -> kind to (contentType.extension ?: "bin")
    is Intent -> kind to language.scriptExtension
    is Impediment, is Monologue -> kind to "md"
}

/** The extension of a file of a script of this language. */
private val Phenomenon.Intent.Language.scriptExtension: String get() = when (this) {
    Phenomenon.Intent.Language.KOTLIN -> "kts"
    Phenomenon.Intent.Language.PYTHON -> "py"
    Phenomenon.Intent.Language.BASH -> "sh"
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
