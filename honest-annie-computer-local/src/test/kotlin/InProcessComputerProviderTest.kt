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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

class InProcessComputerProviderTest {

    private val context = IntentContext(Cogitation(constitution = "", enunciations = emptyList(), depth = 2), parentCogitation = null)

    @Test
    fun `should execute intents in the process of the host, the declarations of each REPL apart`() = runTest(timeout = 5.minutes) {
        // given
        val computer = InProcessComputerProvider().start(Computer())
        val repl = computer.openRepl()
        val other = computer.openRepl()

        // when
        val inHost = repl.execute(Phenomenon.Intent(script = "val answer = ProcessHandle.current().pid()\nanswer"), context).result
        val apart = other.execute(Phenomenon.Intent(script = "answer"), context).result
        computer.close()

        // then
        assert(inHost == listOf(Phenomenon.Text("${ProcessHandle.current().pid()}", "text/plain")))
        assert(apart.single() is Phenomenon.Impediment)
    }

    @Test
    fun `should return the cogitations an intent invoked, and what it enunciated as its output`() = runTest(timeout = 5.minutes) {
        // given
        val repl = InProcessComputerProvider().start(Computer()).openRepl()

        // when
        val executed = repl.execute(
            Phenomenon.Intent(script = $$"agency.enunciate(\"depth ${agency.cogitation().depth}\")\nagency.invoke(\"sub\")"),
            context
        )

        // then
        assert(executed.invoked.single().prompt == "sub")
        assert(executed.result.isEmpty())
        assert(executed.output == listOf(Phenomenon.Text("depth 2")))
    }

}
