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

import com.xemantic.kotlin.script.ScriptDependencies
import com.xemantic.kotlin.script.ScriptResult
import com.xemantic.kotlin.script.execute
import java.util.concurrent.CopyOnWriteArrayList
import com.xemantic.kotlin.script.ScriptExecutor as KotlinScriptExecutor

/** Executes intents, provided to a harness as `executor`, see [Cogitation]. */
interface ScriptExecutor {
    /**
     * Executes the [script] of an intent, providing it with the [dependencies], given the [agency]
     * collecting the invocations made through it, e.g.:
     *
     * ```
     * executor.execute(intent.script, agency) { collecting ->
     *     "cogitation" provides cogitation
     *     "agency" provides collecting
     * }
     * ```
     *
     * Only the invocations of the collecting agency form the result, not those of the [agency] itself.
     * What the script enunciates is not part of its result, but its output, see [Agency.enunciated].
     *
     * @return the invocations the script made through the collecting agency, in their order,
     * followed by the value of the script as a [Phenomenon.Text] of its `toString()` in plain text,
     * unless it is `Unit`, `null`, an invocation, or a collection of nothing else,
     * or [Phenomenon.Data] as is, e.g. an image the cognizer sees.
     * If the script failed, the [Phenomenon.Impediment] describing the failure follows the invocations instead.
     */
    suspend fun execute(
        script: String,
        agency: Agency,
        dependencies: ScriptDependencies.(collecting: Agency) -> Unit
    ): List<Phenomenon>
}

/** The [ScriptExecutor] executing intents with the [executor] of Kotlin scripts. */
fun ScriptExecutor(executor: KotlinScriptExecutor): ScriptExecutor = object : ScriptExecutor {
    override suspend fun execute(
        script: String,
        agency: Agency,
        dependencies: ScriptDependencies.(collecting: Agency) -> Unit
    ): List<Phenomenon> {
        val collecting = CollectingAgency(agency)
        val result = executor.execute(script) {
            dependencies(collecting)
        }
        return collecting.collected + listOfNotNull(
            when (result) {
                is ScriptResult.Error -> Phenomenon.Impediment(result.message.digest())
                is ScriptResult.Value -> when (val value = result.value) {
                    null, Unit, is Phenomenon.Invocation -> null
                    is Phenomenon.Data -> value
                    // already in the result, e.g. `prompts.map { agency.invoke(it) }`
                    is Iterable<*> if value.any() && value.all { it is Phenomenon.Invocation } -> null
                    else -> Phenomenon.Text(value.toString(), Phenomenon.Text.PLAIN)
                }
            }
        )
    }
}

/**
 * The first errors of this message of a failed script, at most [MAX_ERRORS_LENGTH] characters of them,
 * telling how many more are omitted, so that, e.g. a script repeated over and over,
 * or code of another language outside a string, does not flood the cogitation with errors following from the first ones.
 */
private fun String.digest(): String {
    if (length <= MAX_ERRORS_LENGTH) return this
    val errors = split("\n\n")
    var length = 0
    val first = errors.takeWhile { error -> (length + error.length + 2 <= MAX_ERRORS_LENGTH).also { length += error.length + 2 } }
        .ifEmpty { listOf(errors.first().take(MAX_ERRORS_LENGTH)) }
    return first.joinToString("\n\n") + "\n\n${errors.size - first.size} more errors omitted"
}

private const val MAX_ERRORS_LENGTH = 4000

/**
 * The script of an intent expressed as this text, see [Phenomenon.Intent.script]:
 * what precedes the made up markup, within the first fence, without the closing tags ending it.
 */
internal fun String.executable(): String = beforeMarkup().unfenced().withoutClosingTags().trim()

/**
 * The text before its first line of markup, which no line of Kotlin starts with, except within a multiline string:
 * either perceived markup, e.g. an `annie:actant` tag heading a response of the computer,
 * which some cognizers, e.g. DeepSeek, write after their script, going on as if they perceived its result,
 * often with further scripts, all built on made up results,
 * or a line of closing tags, e.g. `</script>`, after which some cognizers keep repeating their script.
 * Only the latter is kept within a multiline string, e.g. of HTML, while perceived markup is cut even there,
 * as the quotes of a script built on made up results may not be balanced, executing them otherwise.
 */
private fun String.beforeMarkup(): String {
    val lines = lines()
    var quotes = 0
    val markup = lines.indexOfFirst { line ->
        val trimmed = line.trim()
        (trimmed.startsWith("<annie:") || quotes % 2 == 0 && trimmed.matches(CLOSING_TAGS)).also {
            quotes += RAW_QUOTES.findAll(line).count()
        }
    }
    return if (markup < 0) this else lines.take(markup).joinToString("\n")
}

private val CLOSING_TAGS = Regex("(</[^<>]+>)+")

private val RAW_QUOTES = Regex("\"\"\"")

/**
 * The script within the first Markdown code fence, e.g. ` ```kotlin `, which some cognizers keep writing
 * around their intents, often with text before and after it, which is discarded, failing to compile otherwise.
 * Fences nested within it, e.g. in a multiline string of Markdown, are counted, so that only the fence
 * closing the first one ends the script: a fence with an info string opens, and a bare one closes.
 * A script without a fence is kept as is, and so is one with nothing within its first fence,
 * while one whose fence is never closed extends to its end.
 */
private fun String.unfenced(): String {
    val lines = lines()
    val opening = lines.indexOfFirst { it.trim().matches(FENCE) }
    if (opening < 0) return this
    var depth = 0
    val closing = (opening + 1 until lines.size).firstOrNull { index ->
        val line = lines[index].trim()
        when {
            line == "```" -> depth-- == 0
            line.matches(FENCE) -> { depth++; false }
            else -> false
        }
    } ?: lines.size
    val script = lines.subList(opening + 1, closing).joinToString("\n")
    // a lone fence ending the text, e.g. of prose, closes rather than opens, executing the text as is
    return script.ifBlank { this }
}

private val FENCE = Regex("```[\\w+-]*")

/**
 * The script without the closing tags ending it, e.g. `</parameter>` or DeepSeek's `</｜DSML｜ invoke>`,
 * which some cognizers write after a script expressed as the parameter of a tool call,
 * the opening tags being taken apart by the endpoint, failing to compile otherwise.
 * No Kotlin script ends with a closing tag, a string literal containing one ending with its quote.
 */
private fun String.withoutClosingTags(): String {
    var script = this
    while (true) {
        val trimmed = script.trimEnd()
        val opening = trimmed.lastIndexOf("</")
        if (!trimmed.endsWith(">") || opening < 0) return script
        val name = trimmed.substring(opening + 2, trimmed.length - 1)
        if (name.isEmpty() || name.any { it == '<' || it == '>' || it == '\n' }) return script
        script = trimmed.substring(0, opening)
    }
}

/** The [agency] collecting the invocations made through it. */
private class CollectingAgency(private val agency: Agency) : Agency by agency {

    val collected = CopyOnWriteArrayList<Phenomenon.Invocation>()

    override fun invoke(
        prompt: String,
        constitution: String,
        harness: String
    ): Phenomenon.Invocation = agency.invoke(prompt, constitution, harness).also { collected += it }

}
