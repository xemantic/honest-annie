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
 * The session is released when the executor is closed, e.g. once its cogitation rests.
 *
 * What an intent does on a computer, e.g. executing a process, it does on the computer of the executor,
 * through the agency the executor provides it with, so that the executor can be a process of its own,
 * while what it does within its cogitation, e.g. invoking another one, it does through the given agency.
 */
interface ScriptExecutor : AutoCloseable {
    /**
     * Executes the [script] of an intent expressed in the [cogitation], given the [agency] of the current step
     * of the harness, provided to the script as `agency`, with this [cogitation] as its [Agency.cogitation],
     * and collecting the invocations made through it, e.g. `executor.execute(intent.script, inferred, agency)`.
     *
     * Only the invocations made through the script form the result, not those of the [agency] itself.
     * What the script enunciates is not part of its result, but its output, see [ExecutedIntent.output].
     *
     * @return the [ExecutedIntent.result]: the invocations the script made, in their order,
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
    ): ExecutedIntent

    /**
     * Executes the [intent] expressed in the [cogitation], e.g. `executor.execute(intent, inferred, agency)`:
     * its edits applied first, all of them or none, see [Phenomenon.Intent.edits],
     * then its script, as by the [execute] of a script, unless an edit failed, or the script is blank.
     *
     * @return the [ExecutedIntent.result]: the report of the edits, if any, as a [Phenomenon.Text] telling where each one was applied,
     * followed by the result of the script,
     * or only the [Phenomenon.Impediment] of the edit failing, no file being changed, and the script not executed;
     * and the [ExecutedIntent.output] of the script.
     */
    suspend fun execute(
        intent: Phenomenon.Intent,
        cogitation: Cogitation,
        agency: Agency
    ): ExecutedIntent {
        val edited: Phenomenon? = if (intent.edits.isEmpty()) null else try {
            Phenomenon.Text(applyEdits(intent.edits), "text/plain")
        } catch (e: Exception) {
            return ExecutedIntent(listOf(Phenomenon.Impediment("${e.message}\nNo file was changed, and the script was not executed.")))
        }
        val executed = if (intent.script.isBlank()) ExecutedIntent(emptyList()) else execute(intent.script, cogitation, agency)
        return executed.copy(result = listOfNotNull(edited) + executed.result)
    }

    /** Releases the session of the intents, closing it again having no effect. */
    override fun close() {}
}

/**
 * An intent executed by a [ScriptExecutor]: its [result], the response of the computer to the intent,
 * and its [output], what its script enunciated through the agency, e.g. by [Agency.enunciate],
 * to be enunciated by the harness as the output of the cogitation, see [Enunciation.isOutput].
 */
data class ExecutedIntent(
    val result: List<Phenomenon>,
    val output: List<Phenomenon> = emptyList()
)
