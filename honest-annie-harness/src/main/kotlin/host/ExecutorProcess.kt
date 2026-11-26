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

package com.xemantic.annie.harness.host

import com.xemantic.annie.Agency
import com.xemantic.annie.Cogitation
import com.xemantic.annie.ExecutorRequest
import com.xemantic.annie.ExecutorResponse
import com.xemantic.annie.IntentContext
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.ScriptExecutor
import com.xemantic.annie.decodeResponse
import com.xemantic.annie.encodeMessage
import com.xemantic.annie.randomId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.Path

private val logger = KotlinLogging.logger {}

/**
 * The executor as a process of its own, started by the [command], serving the sessions of the intents of the cogitations,
 * each [session] executing the intents of one cogitation, so that the harness and the executor can be apart,
 * the harness only acting within the cogitations, see [com.xemantic.annie.ExecutorRequest] for the protocol.
 *
 * The process exits when closed, its sessions with it, or when it fails, failing the intents still executing,
 * and every later one. What it writes to its standard error is logged, e.g. what a script prints.
 */
class ExecutorProcess(
    command: List<String> = sameJvmExecutorCommand()
) : AutoCloseable {

    private val process: Process = ProcessBuilder(command)
        .apply {
            // the classpath of the harness, when the executor is of the same JVM, too long to be an argument
            sameJvmClasspath?.let { environment()["CLASSPATH"] = it }
        }
        .start()

    private val requests = process.outputWriter(Charsets.UTF_8)

    private val pending = ConcurrentHashMap<String, CompletableDeferred<ExecutorResponse>>()

    private val lock = Any()

    /** Why no intent can be executed anymore, once the process exited, `null` while it serves. */
    @Volatile
    private var exited: String? = null

    init {
        logger.info { "Executor process ${process.pid()} started" }
        // logged, rather than inherited, so that why the process fails is told wherever the host logs, e.g. in the tests
        thread(isDaemon = true, name = "executor ${process.pid()} errors") {
            process.errorReader(Charsets.UTF_8).forEachLine { line ->
                logger.warn { "Executor process ${process.pid()}: $line" }
            }
        }
        thread(isDaemon = true, name = "executor ${process.pid()} responses") {
            try {
                process.inputReader(Charsets.UTF_8).forEachLine(::receive)
            } finally {
                val exitCode = process.waitFor()
                logger.info { "Executor process ${process.pid()} exited with $exitCode" }
                synchronized(lock) {
                    exited = "The executor process exited with $exitCode"
                    pending.values.forEach { it.completeExceptionally(IllegalStateException(exited)) }
                    pending.clear()
                }
            }
        }
    }

    private fun receive(line: String) {
        val response = try {
            decodeResponse(line)
        } catch (e: SerializationException) {
            logger.warn(e) { "Executor process ${process.pid()} responded with a malformed message" }
            return
        }
        if (response == null) {
            logger.debug { "Executor process ${process.pid()} printed: $line" }
            return
        }
        pending.remove(response.id)?.complete(response)
    }

    private fun send(request: ExecutorRequest) {
        val line = request.encodeMessage()
        synchronized(requests) {
            requests.write(line)
            requests.flush()
        }
    }

    /** Sends the [request], awaiting its response, cancelling the request when the waiting is cancelled. */
    private suspend fun request(request: ExecutorRequest.Execute): ExecutorResponse {
        val response = CompletableDeferred<ExecutorResponse>()
        synchronized(lock) {
            exited?.let { throw IllegalStateException(it) }
            pending[request.id] = response
        }
        try {
            send(request)
        } catch (e: IOException) {
            pending.remove(request.id)
            throw IllegalStateException(exited ?: "The executor process does not accept requests: ${e.message}", e)
        }
        try {
            return response.await()
        } catch (e: CancellationException) {
            pending.remove(request.id)
            try {
                send(ExecutorRequest.Cancel(request.id))
            } catch (_: IOException) {
                // the process exited, cancelling everything
            }
            throw e
        }
    }

    /** A [ScriptExecutor] of a session of its own in this process, see [ScriptExecutor.close]. */
    fun session(): ScriptExecutor = Session(randomId())

    private inner class Session(private val id: String) : ScriptExecutor {

        override suspend fun execute(
            script: String,
            cogitation: Cogitation,
            agency: Agency
        ): List<Phenomenon> = execute(Phenomenon.Intent(script), cogitation, agency)

        // the edits are applied by the executor, to the files of its computer
        override suspend fun execute(
            intent: Phenomenon.Intent,
            cogitation: Cogitation,
            agency: Agency
        ): List<Phenomenon> {
            val context = IntentContext(
                cogitation = cogitation,
                parentCogitation = agency.parentCogitation,
                allowance = agency.allowance
            )
            return when (val response = request(ExecutorRequest.Execute(randomId(), id, intent, context))) {
                is ExecutorResponse.Executed -> {
                    // enunciated through the agency of the harness, as if the intent acted through it
                    response.enunciated.forEach { phenomenon ->
                        when (phenomenon) {
                            is Phenomenon.Text -> agency.enunciate(phenomenon.text, phenomenon.contentType)
                            is Phenomenon.Data -> agency.enunciate(phenomenon.bytes, phenomenon.contentType)
                            is Phenomenon.Impediment -> agency.impede(phenomenon.reason)
                            else -> error("An intent enunciates no ${phenomenon::class.simpleName}")
                        }
                    }
                    response.result
                }
                is ExecutorResponse.Failed -> throw IllegalStateException(
                    "The executor failed to execute the intent:\n${response.reason}"
                )
            }
        }

        override fun close() {
            if (exited != null) return
            try {
                send(ExecutorRequest.Close(id))
            } catch (_: IOException) {
                // the process exited, closing every session
            }
        }

    }

    /** Ends the input of the process, which then exits, destroyed when it does not in a while. */
    override fun close() {
        try {
            requests.close()
        } catch (_: IOException) {
            // the process exited already
        }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            logger.warn { "Executor process ${process.pid()} did not exit, destroying it" }
            process.destroyForcibly()
        }
    }

}

/**
 * The command starting the executor in a JVM like the one of the harness:
 * of the same Java, with the same JVM arguments, e.g. allowing the native access of Python,
 * but the ones of a debugger or an agent, and the same classpath, see [sameJvmClasspath].
 */
fun sameJvmExecutorCommand(): List<String> {
    val java = ProcessHandle.current().info().command()
        .orElse(Path(System.getProperty("java.home"), "bin", "java").toString())
    val jvmArguments = ManagementFactory.getRuntimeMXBean().inputArguments.filterNot { argument ->
        listOf("-agentlib", "-agentpath", "-javaagent", "-Xdebug", "-Xrunjdwp").any { argument.startsWith(it) }
    }
    return listOf(java) + jvmArguments + "com.xemantic.annie.executor.MainKt"
}

private val sameJvmClasspath: String? get() = System.getProperty("java.class.path")
