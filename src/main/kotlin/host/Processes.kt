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

package com.xemantic.ai.annie.host

import com.xemantic.ai.annie.Execution
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Executes the [command] as a process, see [com.xemantic.ai.annie.Agency.execute].
 *
 * The streams of the process are read and written by threads of their own, not awaited once the process is destroyed:
 * a descendant which escaped destruction, e.g. one orphaned by its exited parent, can hold them open indefinitely.
 */
suspend fun executeProcess(
    command: List<String>,
    input: String? = null,
    directory: String? = null,
    environment: Map<String, String> = emptyMap(),
    timeout: Duration = Duration.INFINITE,
    maxOutputLength: Int = Int.MAX_VALUE
): Execution = withContext(Dispatchers.IO) { // real time, also under a test dispatcher
    val process = ProcessBuilder(command)
        .apply {
            directory?.let { directory(File(it)) }
            environment().putAll(environment)
        }
        .start()
    try {
        val output = Capture(process.inputStream, maxOutputLength)
        val error = Capture(process.errorStream, maxOutputLength)
        thread(isDaemon = true, name = "process input") {
            try {
                process.outputStream.use { stream -> input?.let { stream.write(it.toByteArray()) } }
            } catch (_: IOException) {
                // the process exited, or closed its input, without reading all of it
            }
        }
        val exited = withTimeoutOrNull(timeout) {
            process.onExit().await()
            output.read.await()
            error.read.await()
        } != null
        Execution(
            exitCode = if (exited) process.exitValue() else null,
            output = output.text,
            error = error.text
        )
    } finally {
        // the descendants first, as they cannot be reached once the process is destroyed
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }
}

/** The text read from the [stream] by a thread of its own, truncated to [maxLength] characters. */
private class Capture(stream: InputStream, private val maxLength: Int) {

    private val captured = StringBuilder()

    private var truncated = 0L

    /** Completed once the [stream] is read to its end. */
    val read = CompletableDeferred<Unit>()

    val text: String get() = synchronized(this) {
        if (truncated == 0L) captured.toString() else "$captured\n[$truncated more characters truncated]"
    }

    init {
        thread(isDaemon = true, name = "process output") {
            try {
                stream.bufferedReader().use { reader ->
                    val buffer = CharArray(8192)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        synchronized(this) {
                            val kept = count.coerceAtMost(maxLength - captured.length)
                            captured.appendRange(buffer, 0, kept)
                            truncated += count - kept
                        }
                    }
                }
            } catch (_: IOException) {
                // the stream is closed as the process is destroyed
            } finally {
                read.complete(Unit)
            }
        }
    }

}
