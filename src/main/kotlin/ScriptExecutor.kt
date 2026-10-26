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
     * What the script enunciates is not part of its result, but the output of the cogitation,
     * see [Agency.enunciated].
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
        val result = executor.execute(script.unfenced()) {
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
 * The script without the Markdown code fence enclosing all of it, e.g. ` ```kotlin `, which some cognizers
 * keep writing around their intents, failing to compile otherwise.
 */
private fun String.unfenced(): String {
    val lines = trim().lines()
    return if (lines.size >= 2 && lines.first().matches(Regex("```\\w*")) && lines.last() == "```") {
        lines.subList(1, lines.size - 1).joinToString("\n")
    } else this
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
