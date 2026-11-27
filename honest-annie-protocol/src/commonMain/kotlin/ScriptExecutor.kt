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
     * Executes the Kotlin [script] of an intent expressed in the [cogitation], as an intent of nothing else, see the [execute] of an intent.
     */
    suspend fun execute(
        script: String,
        cogitation: Cogitation,
        agency: Agency
    ): ExecutedIntent = execute(Phenomenon.Intent(script = script), cogitation, agency)

    /**
     * Executes the [intent] expressed in the [cogitation], e.g. `executor.execute(intent, inferred, agency)`:
     * its edits applied first, all of them or none, see [Phenomenon.Intent.edits],
     * then its script, unless an edit failed, or the script is blank, in the [Phenomenon.Intent.language] of the intent.
     *
     * A Kotlin script is executed given the [agency] of the current step of the harness,
     * provided to the script as `agency`, with this [cogitation] as its [Agency.cogitation],
     * collecting the invocations made through it.
     * Only the invocations made through the script form the result, not those of the [agency] itself.
     * What the script enunciates is not part of its result, but its output, see [ExecutedIntent.output].
     * A Python or bash script acts through no agency, so that its result is all of it.
     *
     * @return the [ExecutedIntent.result]: the report of the edits, if any, as a [Phenomenon.Text] telling where each one was applied,
     * followed by the result of the script:
     * of a Kotlin script, the invocations it made, in their order,
     * followed by what the Python of the intents printed, see [Agency.python], if anything, as a [Phenomenon.Text],
     * by the value of the script as a [Phenomenon.Text] of its `toString()` in plain text,
     * unless it is `Unit`, `null`, an invocation, or a collection of nothing else,
     * or [Phenomenon.Data] as is, e.g. an image the cognizer sees,
     * and by the versions resolved for the dependencies the script declared without a version, if any;
     * of a Python script, what it printed, followed by the `repr` of the value of its last statement, unless `None`, or not an expression;
     * of a bash script, what its process wrote to its standard output and error, followed by its exit code.
     * If the script failed, or timed out, the [Phenomenon.Impediment] describing it follows the invocations instead.
     * If an edit failed, its [Phenomenon.Impediment] is the only phenomenon, no file being changed, and the script not executed.
     * The [ExecutedIntent.output] is what the script enunciated.
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
        val executed = if (intent.script.isBlank()) ExecutedIntent(emptyList()) else executeScript(intent, cogitation, agency)
        return executed.copy(result = listOfNotNull(edited) + executed.result)
    }

    /**
     * Executes the script of the [intent] in its [Phenomenon.Intent.language],
     * its edits already applied, see the [execute] of an intent, returning the result of the script and its output.
     */
    suspend fun executeScript(
        intent: Phenomenon.Intent,
        cogitation: Cogitation,
        agency: Agency
    ): ExecutedIntent

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
