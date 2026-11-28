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

import kotlinx.serialization.Serializable

/**
 * Executes the intents of a cogitation, provided to the harness as `executor`, see [Cogitation].
 *
 * The intents are executed one after another in a session of their own, which is stateful, like a Kotlin notebook:
 * what an intent declares, e.g. a `val`, a function or a class, stays visible to the intents executed after it.
 * The session is released when the executor is closed, e.g. once its cogitation rests.
 *
 * What an intent does on a computer, e.g. executing a process, it does on the computer of the executor,
 * through the agency the executor provides it with, so that the executor can be a process of its own,
 * while what it does within its cogitation, e.g. telling it, it does through the given agency,
 * the cogitations it invokes, and what it enunciates, being kept by the executor, see [ExecutedIntent].
 */
interface ScriptExecutor : AutoCloseable {
    /**
     * Executes the Kotlin [script] of an intent of nothing else, see the [execute] of an intent.
     */
    suspend fun execute(
        script: String,
        agency: Agency
    ): ExecutedIntent = execute(Phenomenon.Intent(script = script), agency)

    /**
     * Executes the [intent] expressed in the [Agency.cogitation] of the [agency]:
     * its edits applied first, all of them or none, see [Phenomenon.Intent.edits],
     * then its script, unless an edit failed, or the script is blank, in the [Phenomenon.Intent.language] of the intent.
     *
     * A Kotlin script is executed given the [agency] of the current step of the harness,
     * provided to the script as `agency`, except for what the script invokes, see [ExecutedIntent.invoked],
     * and what it enunciates, see [ExecutedIntent.output], neither of them part of its result.
     * A Python or bash script acts through no agency, so that its result is all of it.
     *
     * @return the [ExecutedIntent.result]: the report of the edits, if any, as a [Phenomenon.Text] telling where each one was applied,
     * followed by the result of the script:
     * of a Kotlin script, what the Python of the intents printed, see [Agency.python], if anything, as a [Phenomenon.Text],
     * followed by the value of the script as a [Phenomenon.Text] of its `toString()` in plain text,
     * unless it is `Unit`, `null`, or a collection of nothing but `Unit`, e.g. of invocations,
     * or [Phenomenon.Data] of a `ByteArray`, e.g. an image the cognizer sees, or a phenomenon of the cogitation as is,
     * and by the versions resolved for the dependencies the script declared without a version, if any;
     * of a Python script, what it printed, followed by the `repr` of the value of its last statement, unless `None`, or not an expression;
     * of a bash script, what its process wrote to its standard output and error, followed by its exit code.
     * If the script failed, or timed out, the [Phenomenon.Impediment] describing it follows what Python printed instead of the value.
     * If an edit failed, its [Phenomenon.Impediment] is the only phenomenon, no file being changed, and the script not executed.
     * The [ExecutedIntent.output] is what the script enunciated, and the [ExecutedIntent.invoked] the cogitations it invoked,
     * the content of the texts and data it created being the [ExecutedIntent.contents].
     */
    suspend fun execute(
        intent: Phenomenon.Intent,
        agency: Agency
    ): ExecutedIntent {
        val edited: Pair<Phenomenon.Text, ByteArray>? = if (intent.edits.isEmpty()) null else try {
            textOf(applyEdits(intent.edits), "text/plain")
        } catch (e: Exception) {
            return ExecutedIntent(listOf(Phenomenon.Impediment("${e.message}\nNo file was changed, and the script was not executed.")))
        }
        val executed = if (intent.script.isBlank()) ExecutedIntent(emptyList()) else executeScript(intent, agency)
        return executed.copy(
            result = listOfNotNull(edited?.first) + executed.result,
            contents = listOfNotNull(edited).associate { (text, content) -> text.ref to content } + executed.contents
        )
    }

    /**
     * Executes the script of the [intent] in its [Phenomenon.Intent.language],
     * its edits already applied, see the [execute] of an intent, returning the result of the script and its output.
     */
    suspend fun executeScript(
        intent: Phenomenon.Intent,
        agency: Agency
    ): ExecutedIntent

    /** Releases the session of the intents, closing it again having no effect. */
    override fun close() {}
}

/**
 * An intent executed by a [ScriptExecutor]: its [result], the response of the computer to the intent,
 * its [output], what its script enunciated through the agency, e.g. by [Agency.enunciate],
 * to be enunciated by the harness as the output of the cogitation,
 * the cogitations it [invoked], in their order, see [Agency.invoke], to be unfolded by the host,
 * and the [contents] of the texts and data it created, by their refs, to be kept by the host, see [Contents.keepAll].
 */
data class ExecutedIntent(
    val result: List<Phenomenon>,
    val output: List<Phenomenon> = emptyList(),
    val invoked: List<Invocation> = emptyList(),
    val contents: Map<String, ByteArray> = emptyMap()
)

/**
 * The invocation of a cogitation by an intent, see [Agency.invoke]: not a phenomenon of the invoking cogitation,
 * whose intent tells it, but what the host needs to unfold the invoked one,
 * the first enunciation of which is the [prompt], of this [id], which its outcome responds to, see [Enunciation.respondsTo].
 *
 * @property constitution of the invoked cogitation, `null` when it is the one of its invoker.
 * @property computer the new computer started for the invoked cogitation, `null` when it runs on the computer of its [repl].
 * @property repl the name of the REPL the invoked cogitation runs in, `null` when it is the one of its invoker,
 * the first one of the [computer] when a new one is started.
 * @property independent whether the invoked cogitation perceives no invoker, see [Agency.invoke].
 */
@Serializable
data class Invocation(
    val id: String,
    val prompt: String,
    val constitution: String? = null,
    val computer: Computer? = null,
    val repl: String? = null,
    val independent: Boolean = false
) {
    override fun toString(): String = "Invocation(id=$id)"
}
