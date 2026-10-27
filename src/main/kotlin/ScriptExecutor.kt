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
     * unless it is `Unit`, `null`, an invocation, or a collection of nothing else.
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
        val result = executor.execute(script.unfenced().withoutParameterClosing()) {
            dependencies(collecting)
        }
        return collecting.collected + listOfNotNull(
            when (result) {
                is ScriptResult.Error -> Phenomenon.Impediment(result.message)
                is ScriptResult.Value -> when (val value = result.value) {
                    null, Unit, is Phenomenon.Invocation -> null
                    // already in the result, e.g. `prompts.map { agency.invoke(it) }`
                    is Iterable<*> if value.any() && value.all { it is Phenomenon.Invocation } -> null
                    else -> Phenomenon.Text(value.toString(), Phenomenon.Text.PLAIN)
                }
            }
        )
    }
}

/**
 * The script within the first Markdown code fence, e.g. ` ```kotlin `, which some cognizers keep writing
 * around their intents, often with text before and after it, which is discarded, failing to compile otherwise.
 * Fences nested within it, e.g. in a multiline string of Markdown, are counted, so that only the fence
 * closing the first one ends the script: a fence with an info string opens, and a bare one closes.
 * A script without a fence is kept as is, and one whose fence is never closed extends to its end.
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
    return lines.subList(opening + 1, closing).joinToString("\n")
}

private val FENCE = Regex("```[\\w+-]*")

/**
 * The script without the closing `</parameter>` tag of a tool call ending it, which some cognizers write
 * after a script expressed as the parameter of a tool call, the rest of the call being taken apart by the endpoint,
 * failing to compile otherwise.
 */
private fun String.withoutParameterClosing(): String {
    val trimmed = trimEnd()
    return if (trimmed.endsWith(PARAMETER_CLOSING)) trimmed.removeSuffix(PARAMETER_CLOSING) else this
}

private const val PARAMETER_CLOSING = "\n</parameter>"

/** The [agency] collecting the invocations made through it. */
private class CollectingAgency(private val agency: Agency) : Agency by agency {

    val collected = CopyOnWriteArrayList<Phenomenon.Invocation>()

    override fun invoke(
        prompt: String,
        constitution: String,
        harness: String
    ): Phenomenon.Invocation = agency.invoke(prompt, constitution, harness).also { collected += it }

}
