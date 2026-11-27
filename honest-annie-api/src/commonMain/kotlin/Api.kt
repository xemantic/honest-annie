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

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

interface Agency {
    /** current */
    suspend fun cogitation(): Cogitation
    /** The path from the root, or from an `independent` one, see [invoke], to the parent, empty for the root */
    suspend fun ancestorCogitations(): List<Cogitation>
    /**
     * Invokes a child-cogitation under the [constitution], its first enunciation being the [prompt].
     *
     * Returns immediately: once the current intent is executed, the cogitations it invoked unfold, and the current one awaits them,
     * perceiving their outcomes once all of them rest, in the order they were invoked.
     *
     * @param constitution when null, the same as the current constitution
     * @param computer the type of a new computer to start for it, one the host offers, otherwise it runs on the computer of the [repl].
     * @param repl the name of the REPL to run in, the one of that name of the cogitations of the tree, on the computer it is open on,
     * otherwise opened on the current computer, or on the new [computer], its first REPL, reached by the intents naming it, see [Phenomenon.Intent.repl].
     * When `null`, the current REPL, sharing its declarations, or a new one, unnamed, on a new [computer], or when [independent].
     * @param independent whether it perceives itself as a root, unable to reach the current cogitation as a parent, only its outcome returning:
     * with a new [computer] too, it shares nothing with it.
     */
    suspend fun invoke(
        prompt: String,
        constitution: String? = null,
        computer: String? = null,
        repl: String? = null,
        independent: Boolean = false
    )
    /**
     * Enunciates a [Phenomenon.Text] as the output of the current cogitation,
     * which is perceived by its invoker once the cogitation rests, see [Cogitation.output].
     * Enunciated by an intent, it is not part of the result of the intent, but precedes it in the cogitation:
     * what the intent needs to perceive in the response to it is the value of its last expression.
     */
    suspend fun enunciate(text: String, contentType: String = "text/markdown")
    /** Enunciates a [Phenomenon.Data] as the output of the current cogitation, like the text [enunciate]. */
    suspend fun enunciate(bytes: ByteArray, contentType: String? = null)
    /** Enunciates a [Phenomenon.Impediment] as the output of the current cogitation, like the text [enunciate]. */
    suspend fun impede(reason: String)
    /** Renders the Kotlin declaration of the fully qualified [name], as available to scripts */
    suspend fun resolveDeclaration(name: String, page: Int = 1, pageSize: Int = 10): String?
    suspend fun searchDeclarations(query: String, page: Int = 1, pageSize: Int = 10): String?
    /**
     * Executes the [command] as a process,
     * writing the [input] to its standard input, in the [directory], with the [environment] added to the inherited one.
     * Suspends until the process exits, and its outputs are read.
     * The process is destroyed, together with its descendants, when the [timeout] elapses,
     * or when the intent executing it is cancelled.
     * Its outputs are kept whole, e.g. to be processed by the script, also when long:
     * you perceive only the beginning of a long text of the result of an intent.
     *
     * What the process outputs is perceived only when the script makes it so:
     * by evaluating to it, so that you perceive it in the result of the intent,
     * or by [enunciate]-ing it as the output of the current cogitation.
     */
    suspend fun execute(
        command: List<String>,
        input: String? = null,
        directory: String? = null,
        environment: Map<String, String> = emptyMap(),
        timeout: Duration = 1.minutes
    ): Execution
    /**
     * The [lines] of the file at the [path], each range of line numbers.
     * @return numbered as by `cat -n`, ranges overlapping or adjacent read as one, and apart ones separated by `…`, the whole file when no [lines] is given
     */
    suspend fun read(path: String, vararg lines: IntRange): String
    /**
     * The content of the [phenomenon] as text: of a [Phenomenon.Text], or a [Phenomenon.Monologue], or of [Phenomenon.Data] of UTF-8 text, e.g. JSON,
     * the script of an [Phenomenon.Intent], the reason of an [Phenomenon.Impediment],
     * e.g. `agency.readText(agency.cogitation().enunciations[3].phenomena[0])`.
     * @return the [lines], each range of line numbers, in their order, as in the text, unnumbered, the whole text when no [lines] is given
     */
    suspend fun readText(phenomenon: Phenomenon, vararg lines: IntRange): String
    /** The content of the [phenomenon] as bytes, e.g. of an image to save to a file, a text being encoded in UTF-8. */
    suspend fun readBytes(phenomenon: Phenomenon): ByteArray
    /** persistent Python REPL of the current cogitation */
    val python: Python
    /**
     * The HTTP client of every cogitation, whose functions suspend, like the ones of the agency,
     * e.g. `agency.http.get(url).body<JsonElement>()`, navigated with [get], see the HTTP section of the constitution.
     * It negotiates JSON with kotlinx.serialization, ignoring unknown keys, times out a request after a minute,
     * retries a request limited by rate (`429`), failed by the server (`5xx`), or by the network, up to 5 times, backing off,
     * and throws on a response with an error status, e.g. `ClientRequestException` on `404`.
     */
    val http: HttpClient
}

/**
 * What the code prints, to `sys.stdout` or `sys.stderr`, is perceived in the result of the intent,
 * truncated, before the value of the intent, also when the intent fails.
 * An exception of Python fails the intent, perceived with its type and message.
 *
 * It is the Python of honest-annie, in an environment of its own, embedded in the JVM:
 * it cannot import the packages of the task, e.g. of the project being changed, nor its code,
 * so run the code, tests and tools of the task by [Agency.execute], e.g. `agency.bash("python -m pytest")`,
 * with the Python the task provides.
 */
interface Python {
    /**
     * Executes the Python [code] like a cell of a Jupyter notebook,
     * evaluating to the value of its last statement, when it is an expression, otherwise to `null`,
     * e.g. `agency.python("import numpy as np\na = np.arange(10)")`, then `agency.python("int(a.sum())")`,
     * or `agency.python("name")` to read a global variable.
     *
     * The value is converted: an `int` becomes a `Long`, a `float` a `Double`, a `str` a `String`,
     * a `list` a `List`, a `dict` a `Map`, while a value which cannot be converted, e.g. a numpy array,
     * evaluates to its Python `repr`, so convert it in Python, e.g. with `.tolist()`.
     *
     * The code can declare the packages of PyPI it needs, installed before it is executed when they are missing,
     * with inline script metadata, see [PEP 723](https://peps.python.org/pep-0723/), e.g.:
     * ```python
     * # /// script
     * # dependencies = ["rdkit"]
     * # ///
     * from rdkit import Chem
     * ```
     */
    suspend operator fun invoke(code: String): Any?
    /**
     * Sets the global Python variable of the [name] to the [value], e.g. `agency.python["items"] = items`,
     * so that values pass from Kotlin to Python without being rendered into the code,
     * converted like the values of [invoke] in reverse.
     */
    suspend operator fun set(name: String, value: Any?)
}

/** The outcome of [Agency.execute]. */
data class Execution(
    /** The exit code of the process, `null` when it was destroyed as the timeout elapsed. */
    val exitCode: Int?,
    /** What the process wrote to its standard output, until it exited, or was destroyed. */
    val output: String,
    /** What the process wrote to its standard error, until it exited, or was destroyed. */
    val error: String
) {
    /** Whether the process exited with `0`. */
    val succeeded: Boolean get() = exitCode == 0
}

/** Executes the [script] with `bash`, see [Agency.execute]. */
suspend fun Agency.bash(
    script: String,
    timeout: Duration = 1.minutes
): Execution = execute(listOf("bash", "-c", script), timeout = timeout)

/**
 * Runs the suspending [block] in a scope of its own, returning its result,
 * waiting for the coroutines the [block] launches, like [coroutineScope],
 * e.g. `await { urls.map { async { agency.http.get(it).bodyAsText() } }.awaitAll() }`.
 */
suspend fun <T> await(block: suspend CoroutineScope.() -> T): T = coroutineScope(block)

/**
 * Triple quotes, which end a raw string, written into one as `$TQ`, or `$$TQ` in a multi-dollar one,
 * e.g. the docstrings of Python: `"""def f():\n    ${TQ}Docstring.$TQ"""`.
 */
const val TQ: String = "\"\"\""

/**
 * Opens the scope of this appendable, e.g. a `StringBuilder`, where `+text` appends the text,
 * see [unaryPlus], evaluating to the appendable, e.g.:
 * ```kotlin
 * val report = StringBuilder()
 * for ((name, length) in proteins) report {
 *     +"$name: $length aa\n"
 * }
 * report
 * ```
 */
operator fun <T : Appendable> T.invoke(block: T.() -> Unit): T = apply(block)

/**
 * Appends this text, `null` as `"null"`, to the appendable in scope,
 * e.g. within `buildString { }`, or the scope opened by [invoke],
 * so that `+"$name\n"` stands for `appendLine(name)`,
 * appending also whatever is joined to it with `+`, e.g. `+"exons: " + exons.size + "\n"`, see [Appending].
 */
context(appendable: Appendable)
expect operator fun CharSequence?.unaryPlus(): Appending

/** Appends to the appendable what is joined with `+` after [unaryPlus], as its text, `null` as `"null"`. */
interface Appending {
    operator fun plus(value: Any?): Appending
}
