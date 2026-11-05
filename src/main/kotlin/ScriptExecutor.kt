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

    override suspend fun execute(
        script: String,
        cogitation: Cogitation,
        agency: Agency
    ): List<Phenomenon> {
        val collecting = CollectingAgency(agency, cogitation, this)
        this.agency.current = collecting
        val result = session.value.execute(script)
        val printed = if (interpreter.isInitialized()) interpreter.value.printed() else null
        return collecting.collected + listOfNotNull(printed) + when (result) {
            is ScriptResult.Error -> listOf(Phenomenon.Impediment(result.message.digest()))
            is ScriptResult.Value -> listOfNotNull(
                when (val value = result.value) {
                    null, Unit, is Phenomenon.Invocation -> null
                    is Phenomenon.Data -> value
                    // already in the result, e.g. `prompts.map { agency.invoke(it) }`
                    is Iterable<*> if value.any() && value.all { it is Phenomenon.Invocation } -> null
                    else -> Phenomenon.Text(value.toString(), Phenomenon.Text.PLAIN)
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
    val digest = if (printed.length <= MAX_PRINTED_LENGTH) printed else {
        printed.take(MAX_PRINTED_LENGTH) + "\n… ${printed.length - MAX_PRINTED_LENGTH} more characters omitted"
    }
    return Phenomenon.Text("Printed by Python:\n$digest", Phenomenon.Text.PLAIN)
}

private const val MAX_PRINTED_LENGTH = 10_000

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
 * The script of an intent expressed as this text, see [Phenomenon.Intent.script]:
 * what precedes the made up markup, within the first fence, without the closing tags ending it.
 */
internal fun String.executable(): String = beforeMarkup().unfenced().withoutClosingTags().trim()

/**
 * The text before its first line of markup, which no line of Kotlin starts with, except within a multiline string:
 * either perceived markup, e.g. an `annie:actant` tag heading a response of the computer,
 * which some cognizers, e.g. DeepSeek, write after their script, going on as if they perceived its result,
 * often with further scripts, all built on made up results,
 * or a line of closing tags, e.g. `</script>`, after which some cognizers keep repeating their script.
 * Only the latter is kept within a multiline string, e.g. of HTML, while perceived markup is cut even there,
 * as the quotes of a script built on made up results may not be balanced, executing them otherwise.
 */
private fun String.beforeMarkup(): String {
    val lines = lines()
    var quotes = 0
    val markup = lines.indexOfFirst { line ->
        val trimmed = line.trim()
        (trimmed.startsWith("<annie:") || quotes % 2 == 0 && trimmed.matches(CLOSING_TAGS)).also {
            quotes += RAW_QUOTES.findAll(line).count()
        }
    }
    return if (markup < 0) this else lines.take(markup).joinToString("\n")
}

private val CLOSING_TAGS = Regex("(</[^<>]+>)+")

private val RAW_QUOTES = Regex("\"\"\"")

/**
 * The script within the first Markdown code fence, e.g. ` ```kotlin `, which some cognizers keep writing
 * around their intents, often with text before and after it, which is discarded, failing to compile otherwise.
 * Fences nested within it, e.g. in a multiline string of Markdown, are counted, so that only the fence
 * closing the first one ends the script: a fence with an info string opens, and a bare one closes.
 * A script without a fence is kept as is, and so is one with nothing within its first fence,
 * while one whose fence is never closed extends to its end.
 */
private fun String.unfenced(): String {
    val lines = lines()
    val opening = lines.indexOfFirst { it.trim().matches(FENCE) }
    if (opening < 0) return this
    var depth = 0
    val closing = (opening + 1 until lines.size).firstOrNull { index ->
        val line = lines[index].trim()
        when {
            line == "```" -> depth-- == 0
            line.matches(FENCE) -> { depth++; false }
            else -> false
        }
    } ?: lines.size
    val script = lines.subList(opening + 1, closing).joinToString("\n")
    // a lone fence ending the text, e.g. of prose, closes rather than opens, executing the text as is
    return script.ifBlank { this }
}

private val FENCE = Regex("```[\\w+-]*")

/**
 * The script without the closing tags ending it, e.g. `</parameter>` or DeepSeek's `</｜DSML｜ invoke>`,
 * which some cognizers write after a script expressed as the parameter of a tool call,
 * the opening tags being taken apart by the endpoint, failing to compile otherwise.
 * No Kotlin script ends with a closing tag, a string literal containing one ending with its quote.
 */
private fun String.withoutClosingTags(): String {
    var script = this
    while (true) {
        val trimmed = script.trimEnd()
        val opening = trimmed.lastIndexOf("</")
        if (!trimmed.endsWith(">") || opening < 0) return script
        val name = trimmed.substring(opening + 2, trimmed.length - 1)
        if (name.isEmpty() || name.any { it == '<' || it == '>' || it == '\n' }) return script
        script = trimmed.substring(0, opening)
    }
}

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
    override val maxReinferences: Int get() = current.maxReinferences
    override val python: Python get() = current.python
    override val http: HttpClient get() = current.http

    override fun invoke(prompt: String, constitution: String, harness: String) =
        current.invoke(prompt, constitution, harness)

    override fun enunciate(text: String, contentType: String) = current.enunciate(text, contentType)

    override fun enunciate(bytes: ByteArray, contentType: String?) = current.enunciate(bytes, contentType)

    override fun impede(reason: String) = current.impede(reason)

    override fun isMalformed(intent: Phenomenon.Intent) = current.isMalformed(intent)

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
