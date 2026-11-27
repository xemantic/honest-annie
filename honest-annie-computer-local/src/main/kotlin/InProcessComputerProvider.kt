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
import com.xemantic.annie.ExecutedIntent
import com.xemantic.annie.IntentContext
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.ScriptExecutor
import com.xemantic.annie.computer.ComputerProvider
import com.xemantic.annie.computer.Repl
import com.xemantic.annie.computer.RunningComputer
import com.xemantic.annie.executor.PythonVenv
import com.xemantic.annie.executor.SessionScriptExecutor
import com.xemantic.annie.executor.execute
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Computers of the [types] in the process of the host itself, without isolation, whatever their type,
 * each REPL being a [ScriptExecutor] created by the [executors], a session of its own,
 * e.g. for the tests, which start no process, or a host which never runs untrusted code.
 */
class InProcessComputerProvider(
    override val types: List<String> = listOf("default"),
    private val executors: () -> ScriptExecutor = { SessionScriptExecutor(PythonVenv::prepare) }
) : ComputerProvider {

    override suspend fun start(computer: Computer): RunningComputer {
        require(computer.type in types) { "No computer of the type ${computer.type}, but of ${types.joinToString()}" }
        return InProcessComputer(computer)
    }

    private inner class InProcessComputer(override val computer: Computer) : RunningComputer {

        private val repls = CopyOnWriteArrayList<Repl>()

        override suspend fun openRepl(id: String): Repl = InProcessRepl(id, executors()).also { repls += it }

        override fun close() {
            repls.forEach { it.close() }
        }

    }

    private class InProcessRepl(override val id: String, private val executor: ScriptExecutor) : Repl {

        override suspend fun execute(intent: Phenomenon.Intent, context: IntentContext): ExecutedIntent =
            executor.execute(intent, context)

        override fun close() {
            executor.close()
        }

    }

}
