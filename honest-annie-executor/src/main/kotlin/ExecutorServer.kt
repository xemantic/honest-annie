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
import com.xemantic.annie.Allowance
import com.xemantic.annie.Cogitation
import com.xemantic.annie.Execution
import com.xemantic.annie.ExecutorRequest
import com.xemantic.annie.ExecutorResponse
import com.xemantic.annie.IntentContext
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.Python
import com.xemantic.annie.ScriptExecutor
import com.xemantic.annie.decodeRequest
import com.xemantic.annie.encodeMessage
import com.xemantic.annie.randomId
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.CopyOnWriteArrayList
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
        val lock = Any()
        fun send(response: ExecutorResponse) {
            val line = response.encodeMessage()
            synchronized(lock) { respond(line) }
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
                                session.mutex.withLock { execute(request, session.executor) }
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
                    is ExecutorRequest.Cancel -> executions[request.id]?.cancel()
                    is ExecutorRequest.Close -> sessions.remove(request.session)?.executor?.close()
                }
            }
        } finally {
            coroutineContext.cancelChildren()
            sessions.values.forEach { it.executor.close() }
        }
    }

    private suspend fun execute(request: ExecutorRequest.Execute, executor: ScriptExecutor): ExecutorResponse {
        val agency = ProtocolAgency(request.context)
        val result = executor.execute(request.intent, request.context.cogitation, agency)
        return ExecutorResponse.Executed(request.id, result, agency.enunciated.toList())
    }

}

/**
 * The agency of the harness of another process, as far as an intent acts through it within its cogitation:
 * what it tells of the cogitation is the [context] of the request, and what the intent does within the cogitation,
 * invoking and enunciating, is kept to be responded, see [ExecutorResponse.Executed].
 * What the intent does on the computer, the executor does itself, see [SessionScriptExecutor].
 */
internal class ProtocolAgency(
    private val context: IntentContext
) : Agency {

    override val cogitation: Cogitation get() = context.cogitation

    override val parentCogitation: Cogitation? get() = context.parentCogitation

    override val allowance: Allowance get() = context.allowance

    override val invocations = CopyOnWriteArrayList<Phenomenon.Invocation>()

    override val enunciated = CopyOnWriteArrayList<Phenomenon>()

    override suspend fun invoke(prompt: String, constitution: String): Phenomenon.Invocation =
        Phenomenon.Invocation(id = randomId(), prompt = prompt, constitution = constitution).also { invocations += it }

    override suspend fun enunciate(text: String, contentType: String) {
        enunciated += Phenomenon.Text(text, contentType)
    }

    override suspend fun enunciate(bytes: ByteArray, contentType: String?) {
        enunciated += Phenomenon.Data(bytes, contentType)
    }

    override suspend fun impede(reason: String) {
        enunciated += Phenomenon.Impediment(reason)
    }

    override suspend fun resolveDeclaration(name: String, page: Int, pageSize: Int): String? = onComputer()

    override suspend fun searchDeclarations(query: String, page: Int, pageSize: Int): String? = onComputer()

    override suspend fun execute(
        command: List<String>,
        input: String?,
        directory: String?,
        environment: Map<String, String>,
        timeout: Duration,
        maxOutputLength: Int
    ): Execution = onComputer()

    override val python: Python get() = onComputer()

    override val http: HttpClient get() = onComputer()

    private fun onComputer(): Nothing = throw UnsupportedOperationException(
        "What an intent does on the computer, its executor does, see SessionScriptExecutor"
    )

}

/** This exception and its causes, each by its class and message, as the stack tells nothing to a cognizer. */
internal fun Throwable.described(): String =
    generateSequence(this) { it.cause }.take(5).joinToString("\nCaused by: ") { it.toString() }
