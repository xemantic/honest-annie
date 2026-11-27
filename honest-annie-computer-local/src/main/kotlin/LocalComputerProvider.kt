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

package com.xemantic.annie.computer.local

import com.xemantic.annie.Computer
import com.xemantic.annie.computer.ComputerProvider
import com.xemantic.annie.computer.Repl
import com.xemantic.annie.computer.RunningComputer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.ConcurrentHashMap

/**
 * Computers of the [types] on the host itself, without isolation, whatever their type:
 * each one an executor process of its own, started by the [command], see [ExecutorProcess],
 * sharing the files, the network and the user of the host, its REPLs being sessions of the process.
 */
class LocalComputerProvider(
    override val types: List<String> = listOf("default"),
    private val command: List<String> = sameJvmExecutorCommand()
) : ComputerProvider {

    private val running: MutableSet<ProcessComputer> = ConcurrentHashMap.newKeySet()

    override suspend fun start(computer: Computer): RunningComputer {
        require(computer.type in types) { "No computer of the type ${computer.type}, but of ${types.joinToString()}" }
        val process = runInterruptible(Dispatchers.IO) { ExecutorProcess(command) }
        return ProcessComputer(computer, process).also { running += it }
    }

    private inner class ProcessComputer(
        override val computer: Computer,
        private val process: ExecutorProcess
    ) : RunningComputer {

        override suspend fun openRepl(id: String): Repl = process.repl(id)

        override fun close() {
            running -= this
            process.close()
        }

    }

    /** Stops the computers still running. */
    override fun close() {
        running.toList().forEach { it.close() }
    }

}
