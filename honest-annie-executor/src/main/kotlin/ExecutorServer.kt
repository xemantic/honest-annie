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
import com.xemantic.annie.ExecutorRequest
import com.xemantic.annie.ExecutorResponse
import com.xemantic.annie.IntentContext
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.Python
import com.xemantic.annie.ScriptExecutor
import com.xemantic.annie.decodeRequest
import com.xemantic.annie.encodeMessage
import com.xemantic.annie.invocation
import com.xemantic.annie.randomId
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * Serves the requests of a harness of another process, see [ExecutorRequest], each session by an executor of its own,
 * created by the [executors] on its first intent, and closed with the session.
 *
 * The intents of different sessions execute at the same time, each one on a thread of its own,
 * as a snippet of a script blocks the thread evaluating it, while the intents of a session execute one after another.
 */
class ExecutorServer(
    private val executors: () -> ScriptExecutor
) {

    private class Session(val executor: ScriptExecutor) {
        val mutex = Mutex()
    }

    /**
     * Serves the requests of the [lines], until there are no more, e.g. as the harness exits,
     * when the intents still executing are cancelled, and every session closed.
     * Each response is passed as a line to [respond], one at a time, though from any thread.
     * A line carrying no request is ignored, see [decodeRequest].
     */
    suspend fun serve(
        lines: ReceiveChannel<String>,
        respond: (String) -> Unit
    ): Unit = coroutineScope {
        val sessions = HashMap<String, Session>()
        val executions = ConcurrentHashMap<String, Job>()
        val recalls = ConcurrentHashMap<String, CompletableDeferred<Cogitation?>>()
        val lock = Any()
        fun send(response: ExecutorResponse) {
            val line = response.encodeMessage()
            synchronized(lock) { respond(line) }
        }
        fun context(execution: String) = RecallingContext { parent ->
            val recall = randomId()
            val recalled = CompletableDeferred<Cogitation?>()
            recalls[recall] = recalled
            try {
                send(ExecutorResponse.Recall(execution, recall, parent))
                recalled.await()
            } finally {
                recalls.remove(recall)
            }
        }
        try {
            for (line in lines) {
                val request = try {
                    decodeRequest(line)
                } catch (e: SerializationException) {
                    System.err.println("Executor ignored a malformed request: ${e.message}")
                    null
                } ?: continue
                when (request) {
                    is ExecutorRequest.Execute -> {
                        val session = try {
                            sessions.getOrPut(request.session) { Session(executors()) }
                        } catch (e: Exception) {
                            send(ExecutorResponse.Failed(request.id, "Cannot start the session of the intent:\n${e.described()}"))
                            continue
                        }
                        // started once registered, so that a quick execution is not removed before it is registered
                        val execution = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                            val response = try {
                                session.mutex.withLock { execute(request, session.executor, context(request.id)) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                ExecutorResponse.Failed(request.id, e.described())
                            }
                            send(response)
                        }
                        executions[request.id] = execution
                        execution.invokeOnCompletion { executions.remove(request.id) }
                        execution.start()
                    }
                    is ExecutorRequest.Recalled -> recalls[request.recall]?.complete(request.cogitation)
                    is ExecutorRequest.Cancel -> executions[request.id]?.cancel()
                    is ExecutorRequest.Close -> sessions.remove(request.session)?.executor?.close()
                }
            }
        } finally {
            coroutineContext.cancelChildren()
            sessions.values.forEach { it.executor.close() }
        }
    }

    private suspend fun execute(
        request: ExecutorRequest.Execute,
        executor: ScriptExecutor,
        context: IntentContext
    ): ExecutorResponse {
        val (result, output) = executor.execute(request.intent, context)
        return ExecutorResponse.Executed(request.id, result, output)
    }

}

/**
 * The [IntentContext] of an intent executed for a harness of another process, recalling the cogitations from it,
 * the cogitation of the intent only once, as it is the same for the whole intent, see [Agency.cogitation].
 */
internal class RecallingContext(
    private val recall: suspend (parent: Boolean) -> Cogitation?
) : IntentContext {

    private val mutex = Mutex()

    private var cogitation: Cogitation? = null

    override suspend fun cogitation(): Cogitation = mutex.withLock {
        cogitation ?: checkNotNull(recall(false)) { "The harness recalled no cogitation of the intent" }.also { cogitation = it }
    }

    override suspend fun parentCogitation(): Cogitation? = recall(true)

}

/**
 * Executes the [intent] as for a harness of another process, given what its agency tells of the cogitation, the [context]:
 * the invocations the intent makes form its result, and what it enunciates its output, see [ProtocolAgency],
 * e.g. for a REPL of the process of the harness itself.
 */
suspend fun ScriptExecutor.execute(intent: Phenomenon.Intent, context: IntentContext): ExecutedIntent =
    execute(intent, ProtocolAgency(context))

/**
 * The agency of the harness of another process, as far as an intent acts through it within its cogitation:
 * what it tells of the cogitation is the [context] of the request, and the invocations of the intent are responded,
 * see [ExecutorResponse.Executed], together with its output, kept by the agency of the executor, see [SessionScriptExecutor].
 * What the intent does on the computer, the executor does itself, see [SessionScriptExecutor].
 */
internal class ProtocolAgency(
    private val context: IntentContext
) : Agency {

    override suspend fun cogitation(): Cogitation = context.cogitation()

    override suspend fun parentCogitation(): Cogitation? = context.parentCogitation()

    override suspend fun invoke(
        prompt: String,
        constitution: String?,
        computer: String?,
        repl: String?,
        independent: Boolean
    ): Phenomenon.Invocation = invocation(prompt, constitution, computer, repl, independent)

    override suspend fun enunciate(text: String, contentType: String): Unit = byExecutor()

    override suspend fun enunciate(bytes: ByteArray, contentType: String?): Unit = byExecutor()

    override suspend fun impede(reason: String): Unit = byExecutor()

    override suspend fun resolveDeclaration(name: String, page: Int, pageSize: Int): String? = onComputer()

    override suspend fun searchDeclarations(query: String, page: Int, pageSize: Int): String? = onComputer()

    override suspend fun execute(
        command: List<String>,
        input: String?,
        directory: String?,
        environment: Map<String, String>,
        timeout: Duration
    ): Execution = onComputer()

    override suspend fun read(path: String, vararg lines: IntRange): String = onComputer()

    override val python: Python get() = onComputer()

    override val http: HttpClient get() = onComputer()

    private fun onComputer(): Nothing = throw UnsupportedOperationException(
        "What an intent does on the computer, its executor does, see SessionScriptExecutor"
    )

    private fun byExecutor(): Nothing = throw UnsupportedOperationException(
        "What an intent enunciates, its executor keeps as the output of the intent, see SessionScriptExecutor"
    )

}

/** This exception and its causes, each by its class and message, as the stack tells nothing to a cognizer. */
internal fun Throwable.described(): String =
    generateSequence(this) { it.cause }.take(5).joinToString("\nCaused by: ") { it.toString() }
