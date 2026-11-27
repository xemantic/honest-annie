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

package com.xemantic.annie.executor

import com.xemantic.annie.Agency
import com.xemantic.annie.Cogitation
import com.xemantic.annie.ExecutedIntent
import com.xemantic.annie.Execution
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.Python
import com.xemantic.annie.ScriptExecutor
import com.xemantic.annie.readNumbered
import com.xemantic.kotlin.introspection.KotlinIntrospector
import com.xemantic.kotlin.script.ScriptResult
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import com.xemantic.kotlin.script.ScriptSession
import com.xemantic.kotlin.script.python.Python as Interpreter
import org.intellij.lang.annotations.Language
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The [ScriptExecutor] executing the intents of a single cogitation in a [ScriptSession],
 * created on the first intent, and released when closed,
 * together with the [python] of the intents, started after [preparePython] prepared its environment,
 * e.g. installing Jep.
 *
 * What an intent does on the computer of the executor, it does through the agency of the executor,
 * not the one of the host: it executes processes, resolves the declarations of the [introspector],
 * the classpath of the scripts being the one of the executor, and requests by the [http] client,
 * so that the host and the executor can be processes of their own, even on other machines.
 */
class SessionScriptExecutor(
    private val preparePython: () -> Unit = {},
    private val introspector: KotlinIntrospector = annieIntrospector,
    private val http: HttpClient = annieHttpClient
) : ScriptExecutor {

    // started on first use, as starting the first one initializes the Python environment of the JVM
    private val interpreter = lazy {
        try {
            preparePython()
            capturingOutput(Interpreter()).apply { exec(SPLIT_CELL) }
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
        override suspend fun invoke(code: String): Any? = interpreter.value(code)
        override suspend fun set(name: String, value: Any?) {
            interpreter.value[name] = value
        }
    }

    private val agency = SwitchedAgency()

    private val session = lazy {
        ScriptSession { provide<Agency>("agency", agency) }
    }

    // whether the API of annie is imported into the session, see DEFAULT_IMPORTS
    private var imported = false

    override suspend fun executeScript(
        intent: Phenomenon.Intent,
        cogitation: Cogitation,
        agency: Agency
    ): ExecutedIntent {
        return when (intent.language) {
            Phenomenon.Intent.Language.KOTLIN -> kotlin(intent.script, intent.timeout, cogitation, agency)
            Phenomenon.Intent.Language.PYTHON -> ExecutedIntent(python(intent.script, intent.timeout))
            Phenomenon.Intent.Language.BASH -> ExecutedIntent(bash(intent.script, intent.timeout ?: 1.minutes))
        }
    }

    private suspend fun kotlin(
        script: String,
        timeout: Duration?,
        cogitation: Cogitation,
        agency: Agency
    ): ExecutedIntent {
        val collecting = ExecutorAgency(agency, cogitation, this, introspector, http)
        this.agency.current = collecting
        val result: ScriptResult? = try {
            if (!imported) {
                val imports = session.value.execute(DEFAULT_IMPORTS)
                check(imports !is ScriptResult.Error) { "Cannot import the API of annie: ${(imports as ScriptResult.Error).message}" }
                imported = true
            }
            if (timeout == null) session.value.execute(script) else withTimeoutOrNull(timeout) { session.value.execute(script) }
        } finally {
            collecting.seal()
        }
        val printed = if (interpreter.isInitialized()) interpreter.value.printed() else null
        val phenomena = buildList {
            addAll(collecting.invoked)
            printed?.let { add(Phenomenon.Text("Printed by Python:\n${it.digest("")}", "text/plain")) }
            addAll(
                when (result) {
                    null -> listOf(Phenomenon.Impediment(timedOut(timeout!!)))
                    is ScriptResult.Error -> listOf(Phenomenon.Impediment(result.message.digest().hinted()))
                    is ScriptResult.Value -> listOfNotNull(
                        when (val value = result.value) {
                            null, Unit, is Phenomenon.Invocation -> null
                            is Phenomenon.Data -> value
                            // already in the result, e.g. `prompts.map { agency.invoke(it) }`
                            is Iterable<*> if value.any() && value.all { it is Phenomenon.Invocation } -> null
                            else -> Phenomenon.Text(value.toString().digest(VALUE_OMISSION), "text/plain")
                        },
                        result.resolvedVersions.takeIf { it.isNotEmpty() }?.let { versions ->
                            Phenomenon.Text(
                                versions.entries.joinToString(prefix = "Resolved dependency versions: ") { (dependency, version) ->
                                    "$dependency:$version"
                                },
                                "text/plain"
                            )
                        }
                    )
                })
        }
        return ExecutedIntent(result = phenomena, output = collecting.output.toList())
    }

    /**
     * The result of the Python [script] of an intent, executed by the Python of the intents, like a cell of a notebook:
     * what it printed, followed by the `repr` of the value of its last statement, unless `None`, or not an expression,
     * or the impediment of its failure.
     * Once the [timeout] elapses, it is no longer awaited, as the Python of the intents cannot be interrupted,
     * so that the script runs on to its end, and the next intents using Python wait for it.
     */
    private suspend fun python(script: String, timeout: Duration?): List<Phenomenon> {
        val python = try {
            interpreter.value
        } catch (e: IllegalStateException) {
            return listOf(Phenomenon.Impediment(e.message ?: e.toString()))
        }
        val cell: suspend () -> Result<String?> = {
            try {
                runInterruptible { Result.success(python.cell(script)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        val value = if (timeout == null) cell() else withTimeoutOrNull(timeout) { cell() }
        // what it printed is taken only once it ended, as the Python of the intents runs one call at a time
        if (value == null) return listOf(Phenomenon.Impediment(timedOut(timeout!!)))
        return listOfNotNull(
            python.printed()?.let { Phenomenon.Text(it.digest(""), "text/plain") },
            value.fold(
                onSuccess = { repr -> repr?.let { Phenomenon.Text(it.digest(""), "text/plain") } },
                onFailure = { e -> Phenomenon.Impediment((e.message ?: e.toString()).digest()) }
            )
        )
    }

    /**
     * The result of the bash [script] of an intent, executed as a process, destroyed once the [timeout] elapses:
     * what it wrote to its standard output, and to its standard error, followed by its exit code.
     */
    private suspend fun bash(script: String, timeout: Duration): List<Phenomenon> {
        val execution = runInterruptible {
            executeProcess(listOf("bash", "-c", script), timeout = timeout, maxOutputLength = MAX_PROCESS_OUTPUT_LENGTH)
        }
        val text = listOfNotNull(
            execution.output.trimEnd().ifEmpty { null },
            execution.error.trimEnd().ifEmpty { null }?.let { "standard error:\n$it" },
            execution.exitCode?.let { "exit code: $it" } ?: "destroyed as its timeout of $timeout elapsed"
        ).joinToString("\n")
        return listOf(Phenomenon.Text(text.digest(""), "text/plain"))
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

/** Takes what this Python printed since the last call, or `null` if nothing. */
private fun Interpreter.printed(): String? = eval<String>(
    "(lambda buffer: (''.join(buffer), buffer.clear())[0])" +
        "(__import__('sys').annie_output[__import__('threading').get_ident()])"
).trimEnd().ifEmpty { null }

/**
 * Executes the Python [code] like a cell of a notebook, its dependencies installed, see [Python],
 * returning the `repr` of the value of its last statement, or `null` when it is `None`, or not an expression,
 * so that the value is perceived as Python renders it, rather than converted to Kotlin.
 */
private fun Interpreter.cell(code: String): String? {
    this["_annie_cell"] = code
    val (statements, expression) = eval<List<*>>("_annie_split_cell(_annie_cell)").map { it as String }
    if (statements.isNotBlank()) exec(statements)
    if (expression.isEmpty()) return null
    return this("(lambda value: None if value is None else repr(value))(\n$expression\n)") as String?
}

/**
 * Declares `_annie_split_cell`, splitting Python code into its statements and its last expression, empty when there is none,
 * so that the expression is evaluated on its own, see [cell].
 * Code failing to parse is all statements, so that executing it raises its `SyntaxError`.
 */
@Language("python")
private const val SPLIT_CELL = """
def _annie_split_cell(code):
    import ast
    try:
        body = ast.parse(code).body
    except SyntaxError:
        return [code, '']
    if not body or not isinstance(body[-1], ast.Expr):
        return [code, '']
    last = body[-1]
    lines = code.splitlines(keepends=True)
    head = ''.join(lines[:last.lineno - 1]) + lines[last.lineno - 1].encode()[:last.col_offset].decode()
    return [head, code[len(head):]]
"""

/**
 * The first [MAX_RESULT_LENGTH] characters of this text of the result of an intent,
 * telling how many more are omitted and the file holding the whole text, followed by the [advice],
 * so that a runaway value does not swamp the cogitation, sent with every intent and recorded,
 * while the rest can still be read, e.g. by `sed -n`, without executing the script again.
 * How much of a result the model perceives is up to the cognizer, which cuts each text far shorter.
 */
private fun String.digest(advice: String): String {
    if (length <= MAX_RESULT_LENGTH) return this
    val whole = createTempFile("annie-result-", ".txt").apply { writeText(this@digest) }
    return take(MAX_RESULT_LENGTH) +
        "\n… ${length - MAX_RESULT_LENGTH} more characters omitted, the whole text being in $whole$advice"
}

/** How many characters of each text of the result of an intent the cogitation keeps, see [digest]. */
private const val MAX_RESULT_LENGTH = 1_000_000

/**
 * How many characters of each output of a process, of [Agency.execute] or of a bash script, are kept,
 * only so that a runaway process does not exhaust the memory of the executor:
 * how much of it the model perceives is up to the cognizer.
 */
private const val MAX_PROCESS_OUTPUT_LENGTH = 10_000_000

/** The impediment of an intent cancelled once its [timeout] elapsed. */
private fun timedOut(timeout: Duration): String =
    "The intent did not complete within its timeout of $timeout, so that it was cancelled: " +
        "code blocking its thread, rather than suspending, may still be running, and the next intents wait for it."

/**
 * What the session of the intents imports before the first one, so that a script uses the API of annie,
 * e.g. `Phenomenon`, without importing it: one forgetting to was the most frequent failure of an intent,
 * costing a whole step to fix, whatever the model. An import of a snippet stays visible to the later ones.
 * So are durations, e.g. `10.minutes` timing out `agency.bash`, and paths, e.g. `Path(file).writeText(…)`,
 * whose missing imports failed whole intents of edits, to be sent again.
 */
private const val DEFAULT_IMPORTS = """
import com.xemantic.annie.*
import kotlin.io.path.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
"""

private const val VALUE_OMISSION = "; keep the whole value in a `val`, " +
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
 * This digest of the errors of a failed script with a hint at the cause of an error it is known to be misread by models,
 * so that the next intent fixes it rather than repeating it.
 */
private fun String.hinted(): String = when {
    "error: Unsupported escape sequence." in this -> "$this\n  $UNSUPPORTED_ESCAPE_HINT"
    "applyEdits" in this -> "$this\n  $EDITS_OF_SCRIPT_HINT"
    else -> this
}

/**
 * The hint at the edits of a script, as models seeing the harness apply the edits of the intent, in their constitution,
 * called `applyEdits`, internal to the executor, from their scripts, escaping the code they changed for a Kotlin string.
 */
internal const val EDITS_OF_SCRIPT_HINT: String =
    "Files are changed by the `edits` of your call, applied before its script, never from the script: " +
        "give them as the `edits` of the call, their texts the code as it is, never escaped for a Kotlin string."

/**
 * The hint at a backslash escaping for a shell or a regex, e.g. `\|` of `grep`, within a plain string,
 * which models kept writing in the commands of `agency.bash`, failing one intent after another.
 */
internal const val UNSUPPORTED_ESCAPE_HINT: String =
    "hint: a backslash of a shell command or a regex, like `\\|` of grep or `\\d`, is no escape of a Kotlin string - " +
        "write the command in a multi-dollar raw string, like `agency.bash(${'$'}${'$'}\"\"\"grep -n 'a\\|b' f\"\"\")`, " +
        "where a backslash is itself, and only `${'$'}${'$'}` interpolates."

/**
 * The agency of an intent: the [agency] of the host, collecting the invocations made through it,
 * and keeping what the intent enunciates as its [output], within the [cogitation] of the intent, and acting on the computer of the [executor] itself:
 * its Python, its processes, the declarations of its [introspector], and its [http] client.
 * Sealed once the intent is executed, so that a thread or a coroutine the intent left running
 * acts no more on the computer, as the host no longer awaits it.
 */
private class ExecutorAgency(
    private val agency: Agency,
    override val cogitation: Cogitation,
    private val executor: SessionScriptExecutor,
    private val introspector: KotlinIntrospector,
    override val http: HttpClient
) : Agency by agency {

    @Volatile
    private var sealed = false

    fun seal() {
        sealed = true
    }

    private fun checkNotSealed() {
        check(!sealed) { "The intent is executed, its agency cannot be used anymore" }
    }

    override val python: Python get() = executor.python

    val invoked = CopyOnWriteArrayList<Phenomenon.Invocation>()

    val output = CopyOnWriteArrayList<Phenomenon>()

    override suspend fun invoke(
        prompt: String,
        constitution: String,
        computer: String?,
        repl: String?,
        independent: Boolean
    ): Phenomenon.Invocation {
        checkNotSealed()
        return agency.invoke(prompt, constitution, computer, repl, independent).also { invoked += it }
    }

    // refused once sealed, as the output of the intent is already returned to the harness
    override suspend fun enunciate(text: String, contentType: String) {
        checkNotSealed()
        output += Phenomenon.Text(text, contentType)
    }

    override suspend fun enunciate(bytes: ByteArray, contentType: String?) {
        checkNotSealed()
        output += Phenomenon.Data(bytes, contentType)
    }

    override suspend fun impede(reason: String) {
        checkNotSealed()
        output += Phenomenon.Impediment(reason)
    }

    override suspend fun resolveDeclaration(name: String, page: Int, pageSize: Int): String? =
        introspector.resolve(name, page, pageSize)

    override suspend fun searchDeclarations(query: String, page: Int, pageSize: Int): String? =
        introspector.search(query, page, pageSize)

    override suspend fun execute(
        command: List<String>,
        input: String?,
        directory: String?,
        environment: Map<String, String>,
        timeout: Duration
    ): Execution {
        checkNotSealed()
        // the thread of the session is not interrupted as the intent is cancelled, so that the process is destroyed by interrupting it here
        return runInterruptible { executeProcess(command, input, directory, environment, timeout, MAX_PROCESS_OUTPUT_LENGTH) }
    }

    override suspend fun read(path: String, vararg lines: IntRange): String =
        runInterruptible { readNumbered(path, lines.toList()) }

}

/**
 * The [Agency] provided to a [ScriptSession], whose properties are fixed when it is created,
 * acting as the [current] agency, the one of the script being executed.
 */
internal class SwitchedAgency : Agency {

    @Volatile
    lateinit var current: Agency

    override val cogitation: Cogitation get() = current.cogitation
    override val parentCogitation: Cogitation? get() = current.parentCogitation
    override val python: Python get() = current.python
    override val http: HttpClient get() = current.http

    override suspend fun invoke(prompt: String, constitution: String, computer: String?, repl: String?, independent: Boolean) =
        current.invoke(prompt, constitution, computer, repl, independent)

    override suspend fun enunciate(text: String, contentType: String) = current.enunciate(text, contentType)

    override suspend fun enunciate(bytes: ByteArray, contentType: String?) = current.enunciate(bytes, contentType)

    override suspend fun impede(reason: String) = current.impede(reason)


    override suspend fun resolveDeclaration(name: String, page: Int, pageSize: Int) =
        current.resolveDeclaration(name, page, pageSize)

    override suspend fun searchDeclarations(query: String, page: Int, pageSize: Int) =
        current.searchDeclarations(query, page, pageSize)

    override suspend fun execute(
        command: List<String>,
        input: String?,
        directory: String?,
        environment: Map<String, String>,
        timeout: Duration
    ) = current.execute(command, input, directory, environment, timeout)

    override suspend fun read(path: String, vararg lines: IntRange) = current.read(path, *lines)

}
