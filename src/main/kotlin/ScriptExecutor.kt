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
     * collecting the phenomena enunciated and invoked through it, e.g.:
     *
     * ```
     * executor.execute(intent.script, agency) { collecting ->
     *     "cogitation" provides cogitation
     *     "agency" provides collecting
     * }
     * ```
     *
     * Only the phenomena of the collecting agency form the result, not those of the [agency] itself.
     *
     * @return the phenomena the script enunciated and invoked through the collecting agency, in their order,
     * followed by the [Phenomenon.Impediment] describing its failure, if it failed.
     * The value of the script is ignored.
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
        return collecting.phenomena + listOfNotNull(
            (result as? ScriptResult.Error)?.let { Phenomenon.Impediment(it.message) }
        )
    }
}

/** The [agency] collecting the [phenomena] enunciated and invoked through it. */
private class CollectingAgency(private val agency: Agency) : Agency by agency {

    val phenomena = CopyOnWriteArrayList<Phenomenon>()

    override fun invoke(
        prompt: String,
        harness: String,
        constitution: String?
    ): Phenomenon.Invocation = agency.invoke(prompt, harness, constitution).also { phenomena += it }

    override suspend fun enunciate(text: String, contentType: String) {
        agency.enunciate(text, contentType)
        phenomena += Phenomenon.Text(text, contentType)
    }

    override suspend fun enunciate(bytes: ByteArray, contentType: String?) {
        agency.enunciate(bytes, contentType)
        phenomena += Phenomenon.Data(bytes, contentType)
    }

    override suspend fun impede(reason: String) {
        agency.impede(reason)
        phenomena += Phenomenon.Impediment(reason)
    }

}
