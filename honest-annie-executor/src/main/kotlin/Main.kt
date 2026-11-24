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

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * The executor as a process of its own, serving the harness which started it, see [ExecutorServer]:
 * the requests are read from the standard input, and the responses written to the standard output,
 * which nothing else of the JVM writes to, e.g. a script printing, as [System.out] is the standard error instead.
 * The process exits once its standard input ends, e.g. as the harness exits.
 */
fun main() {
    val protocol = PrintStream(FileOutputStream(FileDescriptor.out), false, Charsets.UTF_8)
    System.setOut(System.err)
    val lines = Channel<String>(Channel.UNLIMITED)
    thread(isDaemon = true, name = "executor requests") {
        try {
            System.`in`.bufferedReader(Charsets.UTF_8).forEachLine { lines.trySend(it) }
        } finally {
            lines.close()
        }
    }
    runBlocking {
        ExecutorServer { SessionScriptExecutor(PythonVenv::prepare) }.serve(lines) { line ->
            protocol.print(line)
            protocol.flush()
        }
    }
    // the threads the scripts started, if any, would keep the JVM running
    exitProcess(0)
}
