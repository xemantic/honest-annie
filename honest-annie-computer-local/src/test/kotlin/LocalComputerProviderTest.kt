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

import com.xemantic.annie.Cogitation
import com.xemantic.annie.Computer
import com.xemantic.annie.IntentContext
import com.xemantic.annie.Phenomenon
import com.xemantic.kotlin.test.assert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes

class LocalComputerProviderTest {

    private val provider = LocalComputerProvider(types = listOf("default", "GPU64GB"))

    @AfterTest
    fun closeProvider() {
        provider.close()
    }

    private val context = IntentContext(Cogitation(constitution = "", enunciations = emptyList()), parentCogitation = null)

    private val pid = Phenomenon.Intent(script = "ProcessHandle.current().pid()")

    private fun test(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) { withTimeout(5.minutes) { block() } }

    @Test
    fun `should start each computer as an executor process of its own`() = test {
        // given
        val first = provider.start(Computer(type = "default"))
        val second = provider.start(Computer(type = "GPU64GB"))

        // when
        val pids = listOf(first.openRepl(), first.openRepl(), second.openRepl()).map { repl ->
            (repl.execute(pid, context).result.single() as Phenomenon.Text).text.toLong()
        }

        // then
        assert(pids[0] == pids[1] && pids[0] != pids[2])
        assert(ProcessHandle.current().pid() !in pids)
    }

    @Test
    fun `should stop the process of a computer once closed`() = test {
        // given
        val computer = provider.start(Computer())
        val repl = computer.openRepl()

        // when
        computer.close()
        val stopped = assertFailsWith<IllegalStateException> { repl.execute(pid, context) }

        // then
        assert(stopped.message == "The executor process exited with 0")
    }

    @Test
    fun `should stop the processes of the computers still running once closed`() = test {
        // given
        val repl = provider.start(Computer()).openRepl()

        // when
        provider.close()
        val stopped = assertFailsWith<IllegalStateException> { repl.execute(pid, context) }

        // then
        assert(stopped.message == "The executor process exited with 0")
    }

    @Test
    fun `should refuse to start a computer of a type it does not offer`() = test {
        // when
        val refused = assertFailsWith<IllegalArgumentException> { provider.start(Computer(type = "TPU")) }

        // then
        assert(refused.message == "No computer of the type TPU, but of default, GPU64GB")
    }

}
