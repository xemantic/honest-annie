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
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

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
     * which is perceived by its invoker once the cogitation rests.
     * Enunciated by an intent, it is not part of the result of the intent, but precedes it in the cogitation:
     * what the intent needs to perceive in the response to it is the value of its last expression.
     */
    suspend fun enunciate(text: String, contentType: String = "text/markdown")
    /** Enunciates a [Phenomenon.Data] as the output of the current cogitation, like the text [enunciate]. */
    suspend fun enunciate(bytes: ByteArray, contentType: String)
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

/** Executes the [script] with `bash`, see [Agency.execute]. */
suspend fun Agency.bash(
    script: String,
    timeout: Duration = 1.minutes
): Execution = execute(listOf("bash", "-c", script), timeout = timeout)

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

/**
 * Cognitive process instance
 * @property depth how deep this cogitation is in its recursive tree: 0
 * @property computer the computer its intents execute on, unless an intent names a REPL open on another one
 * @property repl the REPL of the [computer] its intents execute in, keeping their declarations,
 * shared with the cogitations it invokes, unless they run in another one, see [Agency.invoke].
 */
@Serializable
data class Cogitation(
    val constitution: String,
    val enunciations: List<Enunciation>,
    val depth: Int = 0,
    val computer: Computer = Computer(),
    val repl: String = randomId()
)

/**
 * @property initiated the moment the expression of this enunciation started, e.g. the inference request.
 * @property finalized the moment the expression was completed; equal to [initiated] for instantaneous ones.
 * @property respondsTo the id of the [Phenomenon.Intent] this enunciation responds to,
 * or of the cogitation invoked, see [Agency.invoke], when this enunciation is its outcome.
 * @property id unique within its cogitation, the [responseId] of what it responds to,
 * so that enunciating the same response again is recognized as such.
 * @property inference the inference expressing this enunciation, `null` when it was not inferred.
 * Never perceived by the cognizer, so that the rendering of past turns never changes.
 */
@Serializable
data class Enunciation(
    val actant: Actant,
    val initiated: Instant = Clock.System.now(),
    val finalized: Instant = initiated,
    val respondsTo: String? = null,
    val id: String = respondsTo?.let(::responseId) ?: randomId(),
    val phenomena: List<Phenomenon>,
    val inference: Inference? = null
) {
    init {
        require(finalized >= initiated) {
            "Enunciation finalized ($finalized) before initiated ($initiated)"
        }
    }
    val duration: Duration get() = finalized - initiated
    /** Whether this is the outcome of a cogitation invoked by this one, its output, enunciated by [Actant.SELF] responding to the invocation. */
    val isOutcome: Boolean get() = actant == Actant.SELF && respondsTo != null
}

/** Id of the enunciation responding to the request with [requestId], the same however many times it is enunciated. */
fun responseId(requestId: String): String = "response-to-$requestId"

/**
 * The content of phenomena with `ref` can be retrieved through [Agency.readText] and [Agency.readBytes]
 */
@Serializable
sealed interface Phenomenon {
    /**
     * A request to learn about [Computer] state or to alter it, by editing files and executing scripts in REPL.
     * A computer connected to the internet becomes a window for perceiving the state of affairs in the world and alter it.
     */
    @Serializable
    data class Intent(
        val repl: String? = null,
        val edits: List<Edit> = emptyList(),
        val language: Language = Language.KOTLIN,
        val script: String,
        val timeout: Duration? = null,
        val id: String = randomId()
    ) : Phenomenon {
        enum class Language { KOTLIN, PYTHON, BASH }
    }
    @Serializable
    data class Text(
        val ref: String,
        val length: Int,
        val lines: Int,
        val contentType: String = "text/markdown"
    ) : Phenomenon
    /** e.g. image, PDF, etc. */
    @Serializable
    data class Data(
        val ref: String,
        val size: Long,
        val contentType: String
    ) : Phenomenon
    /** What impeded an intent, as the [reason] for it, see [Agency.impede]. */
    @Serializable
    data class Impediment(val reason: String) : Phenomenon
    /** Your reasoning trace */
    @Serializable
    data class Monologue(
        val ref: String,
        val length: Int,
        val lines: Int,
        val id: String = randomId()
    ) : Phenomenon
}

/**
 * A change of the file at the [path].
 * @param old
 * @param all
 */
@Serializable
data class Edit(val path: String, val new: String, val old: String? = null, val all: Boolean = false)
@Serializable
data class Actant(val type: Type, val name: String, val spec: String? = null) {
    enum class Type { SELF, INVOKER, OTHER_AI, HUMAN, COMPUTER, UNIDENTIFIED }
    companion object {
        /** The actant of every enunciation of the cognizer, never revealing its model. */
        val SELF = Actant(type = Type.SELF, name = "self")
        /**
         * The actant of the prompt of an invoked cogitation, its first enunciation: the cogitation which invoked it, see [Agency.invoke].
         * The id of the prompt is the one of the invoked cogitation, which its outcome responds to in the invoking one, see [Enunciation.respondsTo].
         */
        val INVOKER = Actant(type = Type.INVOKER, name = "invoker")
    }
}

/**
 * A single inference of a model, as reported by the provider serving it, the same for every provider.
 *
 * @property model the canonical id of the model which inferred, the same at every endpoint serving it.
 * @property provider who served the inference, e.g. `anthropic`, `bedrock`, `openai`, or the host of another OpenAI-compatible endpoint.
 * @property tokens the numbers of tokens by their category, a category without tokens being absent.
 * The categories are disjoint, so that each token is counted once, and they sum up to the [total].
 * @property stop why the inference stopped, `null` when not reported.
 */
@Serializable
data class Inference(
    val model: String,
    val provider: String,
    val tokens: Map<Tokens, Long> = emptyMap(),
    val stop: Stop? = null
) {
    enum class Tokens {
        /** Input processed anew, neither read from a cache, nor written to it. */
        INPUT,
        /** Input read from a cache, usually billed at a fraction of the [INPUT]. */
        CACHE_READ,
        /** Input written to a cache whose lifetime is not reported. */
        CACHE_WRITE,
        /** Input written to a cache living 5 minutes. */
        CACHE_WRITE_5M,
        /** Input written to a cache living 1 hour. */
        CACHE_WRITE_1H,
        /** Output of the monologue, when reported apart from the rest of the [OUTPUT]. */
        REASONING,
        /** Output, except the [REASONING]. */
        OUTPUT
    }
    val total: Long get() = tokens.values.sum()

    /**
     * Why the inference stopped, the same for every provider.
     *
     * @property reason as reported by the provider, e.g. `end_turn` of Claude, or `length` of Chat Completions.
     * @property category of a refusal, as reported by the provider, e.g. `cyber` of Claude, `null` when not reported.
     */
    @Serializable
    data class Stop(val kind: Kind, val reason: String, val category: String? = null) {
        enum class Kind {
            /** The model completed what it expressed. */
            COMPLETED,
            /** Cut short by a limit of tokens, of the output or of the context window. */
            TRUNCATED,
            /** Cut short by the model refusing to continue, or by a filter of the provider. */
            REFUSED,
            /** Stopped for any other reason, e.g. one introduced by a newer API. */
            OTHER
        }
    }
}

/**
 * A computer the intents of cogitations execute on, see [Cogitation.computer], identified by its [id],
 * of a [type] the host offers, e.g. one with a GPU, a new one being started for an invoked cogitation, see [Agency.invoke].
 * Each computer runs REPLs, sessions keeping the declarations of the intents executed in them, see [Cogitation.repl],
 * each name of a REPL naming a single one in the tree of cogitations, see [Phenomenon.Intent.repl].
 */
@Serializable
data class Computer(
    val id: String = randomId(),
    val type: String = "default"
)

fun randomId(): String = Uuid.random().toString()
