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

import io.ktor.client.HttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The agency within the current cogitation: how its harness, and the intents it executes, act within it,
 * provided to both as `agency`, see [Cogitation], always acting within the current execution of the harness.
 * None of its functions suspends: each one is called directly, e.g. `agency.enunciate(answer)`,
 * the ones which wait, e.g. [execute], blocking the thread calling them.
 */
interface Agency {
    /**
     * The current cogitation: the one the harness unfolds,
     * or, within an intent, the one including the enunciation which expressed it, see [ScriptExecutor.execute].
     */
    val cogitation: Cogitation
    /**
     * The constitution of the current cogitation, its parts joined as the cognizer perceives them,
     * see [Cogitation.constitution]. It embeds the source of this [harness].
     */
    val constitution: String
    /** The source of the harness unfolding the current cogitation, see [Cogitation.harness]. */
    val harness: String
    /** [Cogitation] which invoked the current one, `null` for the root one. */
    val parentCogitation: Cogitation?
    /** How deep the current cogitation is in its tree, see [Cogitation.depth]. */
    val depth: Int
    /** Invocations made during this execution of the harness, e.g. by the intent it executes. */
    val invocations: List<Phenomenon.Invocation>
    /**
     * Output enunciated during this execution of the harness, e.g. by the intent it executes,
     * to be enunciated by the harness in the cogitation it unfolds, see [Enunciation.isOutput].
     */
    val enunciated: List<Phenomenon>
    /**
     * Invokes a cogitation under the [constitution], by default this [Agency.constitution],
     * unfolded by the [harness] script, which can be a different one, e.g. a modification of this [Agency.harness].
     * The invoked cogitation is unfolded by the same cognizer, starting with the [prompt]
     * enunciated by [Actant.INVOKER].
     *
     * Returns immediately: the returned [Phenomenon.Invocation] is enunciated in the result
     * of the current intent, and the outcome of the invoked cogitation is perceived later,
     * as an enunciation responding to its [Phenomenon.Invocation.id].
     */
    fun invoke(
        prompt: String,
        constitution: String = this.constitution,
        harness: String = this.harness,
    ): Phenomenon.Invocation
    /**
     * Enunciates a [Phenomenon.Text] as the output of the current cogitation, see [enunciated],
     * which is perceived by its invoker once the cogitation rests, see [Cogitation.output].
     * Enunciated by an intent, it is not part of the result of the intent, but precedes it in the cogitation:
     * what the intent needs to perceive in the response to it is the value of its last expression.
     */
    fun enunciate(text: String, contentType: String = Phenomenon.Text.MARKDOWN)
    /** Enunciates a [Phenomenon.Data] as the output of the current cogitation, like the text [enunciate]. */
    fun enunciate(bytes: ByteArray, contentType: String? = null)
    /** Enunciates a [Phenomenon.Impediment] as the output of the current cogitation, like the text [enunciate]. */
    fun impede(reason: String)
    /**
     * Renders the Kotlin declaration of the fully qualified [name], as available to scripts,
     * e.g. `kotlinx.coroutines.flow.Flow`: its current signature, together with the extensions of a type,
     * paged by [pageSize] declarations, the first [page] being `1`.
     * Only Kotlin declarations can be resolved: Java classes, e.g. of the JDK, have no Kotlin metadata.
     *
     * @return the rendered declaration, or `null` when nothing is found.
     */
    fun resolveDeclaration(name: String, page: Int = 1, pageSize: Int = 10): String?
    /**
     * Lists the fully qualified names of the Kotlin declarations available to scripts containing the [query],
     * to be resolved with [resolveDeclaration], paged like it.
     *
     * @return the names, or `null` when nothing is found.
     */
    fun searchDeclarations(query: String, page: Int = 1, pageSize: Int = 10): String?
    /**
     * Executes the [command] as a process of the user's computer, e.g. `listOf("bash", "-c", script)`, see [bash],
     * writing the [input] to its standard input, in the [directory], with the [environment] added to the inherited one.
     * Blocks until the process exits, and its outputs are read.
     * The process is destroyed, together with its descendants, when the [timeout] elapses,
     * or when the thread executing it is interrupted, e.g. as the intent is cancelled.
     * Each of its outputs is truncated to [maxOutputLength] characters, the truncation being marked.
     *
     * What the process outputs is perceived only when the script makes it so:
     * by evaluating to it, so that you perceive it in the result of the intent,
     * or by [enunciate]-ing it as the output of the current cogitation.
     */
    fun execute(
        command: List<String>,
        input: String? = null,
        directory: String? = null,
        environment: Map<String, String> = emptyMap(),
        timeout: Duration = 1.minutes,
        maxOutputLength: Int = 100_000
    ): Execution
    /** The Python interpreter of the current cogitation, see [Python]. */
    val python: Python
    /**
     * The HTTP client of every cogitation, whose functions suspend, so they are called within `await { }`,
     * e.g. `await { agency.http.get(url).body<JsonObject>() }`, see the HTTP section of the constitution.
     * It negotiates JSON with kotlinx.serialization, ignoring unknown keys, times out a request after a minute,
     * and throws on a response with an error status, e.g. `ClientRequestException` on `404`.
     */
    val http: HttpClient
}

/**
 * The CPython interpreter of the current cogitation, started when first used,
 * stateful like your Kotlin session: the variables, functions, classes and imports of the Python code it executes
 * stay visible to the Python code of your later intents.
 *
 * What the code prints, to `sys.stdout` or `sys.stderr`, is perceived in the result of the intent,
 * truncated, before the value of the intent, also when the intent fails.
 * An exception of Python fails the intent, perceived with its type and message.
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
    operator fun invoke(code: String): Any?
    /**
     * Sets the global Python variable of the [name] to the [value], e.g. `agency.python["items"] = items`,
     * so that values pass from Kotlin to Python without being rendered into the code,
     * converted like the values of [invoke] in reverse.
     */
    operator fun set(name: String, value: Any?)
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
fun Agency.bash(
    script: String,
    timeout: Duration = 1.minutes
): Execution = execute(listOf("bash", "-c", script), timeout = timeout)
