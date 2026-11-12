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

import com.xemantic.kotlin.script.ScriptResult
import io.ktor.client.HttpClient
import com.xemantic.kotlin.script.ScriptSession
import com.xemantic.kotlin.script.python.Python as Interpreter
import org.intellij.lang.annotations.Language
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * Executes the intents of a cogitation, provided to its harness as `executor`, see [Cogitation].
 *
 * The intents are executed one after another in a session of their own, which is stateful, like a Kotlin notebook:
 * what an intent declares, e.g. a `val`, a function or a class, stays visible to the intents executed after it.
 */
interface ScriptExecutor {
    /**
     * Executes the [script] of an intent expressed in the [cogitation], given the [agency] of the current execution
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
}

/**
 * The [ScriptExecutor] executing the intents of a single cogitation in a [ScriptSession],
 * created on the first intent, and released when closed,
 * together with the [python] of the intents, started after [preparePython] prepared its environment,
 * e.g. installing Jep.
 */
class SessionScriptExecutor(
    private val preparePython: () -> Unit = {}
) : ScriptExecutor, AutoCloseable {

    // started on first use, as starting the first one initializes the Python environment of the JVM
    private val interpreter = lazy {
        try {
            preparePython()
            capturingOutput(Interpreter())
        } catch (e: Exception) {
            throw IllegalStateException(
                "agency.python is unavailable, as the Python environment of the harness cannot be prepared:\n" +
                    "${e.message}\n" +
                    "Meanwhile run Python as a process, e.g. agency.execute(listOf(\"python3\", file)), " +
                    "which keeps nothing between intents, and whose result is only what it prints.",
                e
            )
        }
    }

    /** The Python of the intents, see [Agency.python], forwarding to the [interpreter]. */
    val python: Python = object : Python {
        override fun invoke(code: String): Any? = interpreter.value(code)
        override fun set(name: String, value: Any?) {
            interpreter.value[name] = value
        }
    }

    private val agency = SwitchedAgency()

    private val session = lazy {
        ScriptSession { provide<Agency>("agency", agency) }
    }

    // whether the API of annie is imported into the session, see DEFAULT_IMPORTS
    private var imported = false

    override suspend fun execute(
        script: String,
        cogitation: Cogitation,
        agency: Agency
    ): List<Phenomenon> {
        val collecting = CollectingAgency(agency, cogitation, this)
        this.agency.current = collecting
        if (!imported) {
            val imports = session.value.execute(DEFAULT_IMPORTS)
            check(imports !is ScriptResult.Error) { "Cannot import the API of annie: ${(imports as ScriptResult.Error).message}" }
            imported = true
        }
        val result = session.value.execute(script)
        val printed = if (interpreter.isInitialized()) interpreter.value.printed() else null
        return buildList {
            addAll(collecting.collected)
            addAll(listOfNotNull(printed))
            addAll(
                when (result) {
                    is ScriptResult.Error -> listOf(Phenomenon.Impediment(result.message.digest()))
                    is ScriptResult.Value -> listOfNotNull(
                        when (val value = result.value) {
                            null, Unit, is Phenomenon.Invocation -> null
                            is Phenomenon.Data -> value
                            // already in the result, e.g. `prompts.map { agency.invoke(it) }`
                            is Iterable<*> if value.any() && value.all { it is Phenomenon.Invocation } -> null
                            else -> Phenomenon.Text(value.toString().digest(VALUE_OMISSION), Phenomenon.Text.PLAIN)
                        },
                        result.resolvedVersions.takeIf { it.isNotEmpty() }?.let { versions ->
                            Phenomenon.Text(
                                versions.entries.joinToString(prefix = "Resolved dependency versions: ") { (dependency, version) ->
                                    "$dependency:$version"
                                },
                                Phenomenon.Text.PLAIN
                            )
                        }
                    )
                })
        }
    }

    private val closed = AtomicBoolean(false)

    /** Releases the session and the Python of the intents, calling it again having no effect. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (session.isInitialized()) session.value.close()
        if (interpreter.isInitialized()) interpreter.value.use {
            it.exec("__import__('sys').annie_output.pop(__import__('threading').get_ident(), None)")
        }
    }

}

/**
 * Starts capturing what the [python] prints, to be perceived in the result of the intent, see [printed],
 * as otherwise it would be printed to the standard output of the host.
 *
 * Every Python of the JVM shares the `sys` module, so its streams are replaced once,
 * by ones keeping what each Python prints by the thread it runs on, see [CAPTURE_OUTPUT].
 */
private fun capturingOutput(python: Interpreter): Interpreter = try {
    synchronized(CAPTURE_OUTPUT) {
        python.exec(CAPTURE_OUTPUT)
    }
    python
} catch (e: Exception) {
    python.close()
    throw e
}

/**
 * Replaces `sys.stdout` and `sys.stderr`, once in the JVM, with streams keeping what each thread registered
 * in `sys.annie_output` writes, while passing on what any other thread writes,
 * and registers the thread of the Python executing it, without leaving any global variable.
 */
@Language("python")
private const val CAPTURE_OUTPUT = """
def _annie_capture_output():
    import io, sys, threading

    class CapturedOutput(io.TextIOBase):
        def __init__(self, stream, buffers):
            self.stream = stream
            self.buffers = buffers
        encoding = 'utf-8'
        errors = 'strict'
        def writable(self):
            return True
        def write(self, text):
            buffer = self.buffers.get(threading.get_ident())
            if buffer is None:
                return self.stream.write(text)
            buffer.append(text)
            return len(text)
        def flush(self):
            if threading.get_ident() not in self.buffers:
                self.stream.flush()

    if not hasattr(sys, 'annie_output'):
        sys.annie_output = {}
        sys.stdout = CapturedOutput(sys.stdout, sys.annie_output)
        sys.stderr = CapturedOutput(sys.stderr, sys.annie_output)
    sys.annie_output[threading.get_ident()] = []

_annie_capture_output()
del _annie_capture_output
"""

/** Takes what this Python printed since the last call, as a [Phenomenon.Text], digested, or `null` if nothing. */
private fun Interpreter.printed(): Phenomenon.Text? {
    val printed = eval<String>(
        "(lambda buffer: (''.join(buffer), buffer.clear())[0])" +
            "(__import__('sys').annie_output[__import__('threading').get_ident()])"
    ).trimEnd()
    if (printed.isEmpty()) return null
    return Phenomenon.Text("Printed by Python:\n${printed.digest("")}", Phenomenon.Text.PLAIN)
}

/**
 * The first [MAX_RESULT_LENGTH] characters of this text perceived in the result of an intent,
 * telling how many more are omitted, followed by the [advice], so that a single result does not flood the cogitation,
 * where it would be perceived again at every later step.
 */
private fun String.digest(advice: String): String =
    if (length <= MAX_RESULT_LENGTH) this
    else take(MAX_RESULT_LENGTH) + "\n… ${length - MAX_RESULT_LENGTH} more characters omitted$advice"

private const val MAX_RESULT_LENGTH = 10_000

/**
 * What the session of the intents imports before the first one, so that a script uses the API of annie,
 * e.g. `Phenomenon`, without importing it: one forgetting to was the most frequent failure of an intent,
 * costing a whole step to fix, whatever the model. An import of a snippet stays visible to the later ones.
 */
private const val DEFAULT_IMPORTS = "import com.xemantic.ai.annie.*"

private const val VALUE_OMISSION = ": keep the whole value in a `val`, " +
        "and let the script evaluate to a digest of what you need to know of it"

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
 * The [agency] collecting the invocations made through it, within the [cogitation] of the intent,
 * and providing the Python of the [executor].
 */
private class CollectingAgency(
    private val agency: Agency,
    override val cogitation: Cogitation,
    private val executor: SessionScriptExecutor
) : Agency by agency {

    override val python: Python get() = executor.python

    val collected = CopyOnWriteArrayList<Phenomenon.Invocation>()

    override fun invoke(
        prompt: String,
        constitution: String,
        harness: String
    ): Phenomenon.Invocation = agency.invoke(prompt, constitution, harness).also { collected += it }

}

/**
 * The [Agency] provided to a [ScriptSession], whose properties are fixed when it is created,
 * acting as the [current] agency, the one of the script being executed.
 */
internal class SwitchedAgency : Agency {

    @Volatile
    lateinit var current: Agency

    override val cogitation: Cogitation get() = current.cogitation
    override val constitution: String get() = current.constitution
    override val harness: String get() = current.harness
    override val parentCogitation: Cogitation? get() = current.parentCogitation
    override val depth: Int get() = current.depth
    override val invocations: List<Phenomenon.Invocation> get() = current.invocations
    override val enunciated: List<Phenomenon> get() = current.enunciated
    override val python: Python get() = current.python
    override val http: HttpClient get() = current.http

    override fun invoke(prompt: String, constitution: String, harness: String) =
        current.invoke(prompt, constitution, harness)

    override fun enunciate(text: String, contentType: String) = current.enunciate(text, contentType)

    override fun enunciate(bytes: ByteArray, contentType: String?) = current.enunciate(bytes, contentType)

    override fun impede(reason: String) = current.impede(reason)


    override fun resolveDeclaration(name: String, page: Int, pageSize: Int) =
        current.resolveDeclaration(name, page, pageSize)

    override fun searchDeclarations(query: String, page: Int, pageSize: Int) =
        current.searchDeclarations(query, page, pageSize)

    override fun execute(
        command: List<String>,
        input: String?,
        directory: String?,
        environment: Map<String, String>,
        timeout: Duration,
        maxOutputLength: Int
    ) = current.execute(command, input, directory, environment, timeout, maxOutputLength)

}
