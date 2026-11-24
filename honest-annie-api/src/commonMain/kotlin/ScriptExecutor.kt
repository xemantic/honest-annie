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
 * Executes the intents of a cogitation, provided to the harness as `executor`, see [Cogitation].
 *
 * The intents are executed one after another in a session of their own, which is stateful, like a Kotlin notebook:
 * what an intent declares, e.g. a `val`, a function or a class, stays visible to the intents executed after it.
 */
interface ScriptExecutor {
    /**
     * Executes the [script] of an intent expressed in the [cogitation], given the [agency] of the current step
     * of the harness, provided to the script as `agency`, with this [cogitation] as its [Agency.cogitation],
     * and collecting the invocations made through it, e.g. `executor.execute(intent.script, inferred, agency)`.
     *
     * Only the invocations made through the script form the result, not those of the [agency] itself.
     * What the script enunciates is not part of its result, but its output, see [Agency.enunciated].
     *
     * @return the invocations the script made, in their order,
     * followed by what the Python of the intents printed, see [Agency.python], if anything, as a [Phenomenon.Text],
     * by the value of the script as a [Phenomenon.Text] of its `toString()` in plain text,
     * unless it is `Unit`, `null`, an invocation, or a collection of nothing else,
     * or [Phenomenon.Data] as is, e.g. an image the cognizer sees,
     * and by the versions resolved for the dependencies the script declared without a version, if any.
     * If the script failed, the [Phenomenon.Impediment] describing the failure follows the invocations instead.
     */
    suspend fun execute(
        script: String,
        cogitation: Cogitation,
        agency: Agency
    ): List<Phenomenon>

    /**
     * Executes the [intent] expressed in the [cogitation], e.g. `executor.execute(intent, inferred, agency)`:
     * its edits applied first, all of them or none, see [Phenomenon.Intent.edits],
     * then its script, as by the [execute] of a script, unless an edit failed, or the script is blank.
     *
     * @return the report of the edits, if any, as a [Phenomenon.Text] telling where each one was applied,
     * followed by the result of the script,
     * or only the [Phenomenon.Impediment] of the edit failing, no file being changed, and the script not executed.
     */
    suspend fun execute(
        intent: Phenomenon.Intent,
        cogitation: Cogitation,
        agency: Agency
    ): List<Phenomenon> {
        val edited: Phenomenon? = if (intent.edits.isEmpty()) null else try {
            Phenomenon.Text(applyEdits(intent.edits), "text/plain")
        } catch (e: Exception) {
            return listOf(Phenomenon.Impediment("${e.message}\nNo file was changed, and the script was not executed."))
        }
        return listOfNotNull(edited) + if (intent.script.isBlank()) emptyList() else execute(intent.script, cogitation, agency)
    }
}
