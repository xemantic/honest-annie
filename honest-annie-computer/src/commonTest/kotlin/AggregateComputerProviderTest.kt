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

package com.xemantic.annie.computer

import com.xemantic.annie.Cogitation
import com.xemantic.annie.Computer
import com.xemantic.annie.Contents
import com.xemantic.annie.ExecutedIntent
import com.xemantic.annie.IntentContext
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.refOf
import com.xemantic.annie.textOf
import com.xemantic.kotlin.test.assert
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AggregateComputerProviderTest {

    /** Starts computers of the [types], each REPL answering with the [name] of this provider. */
    private class NamedProvider(val name: String, override val types: List<String>) : ComputerProvider {
        var closed = false
        override suspend fun start(computer: Computer): RunningComputer = object : RunningComputer {
            override val computer = computer
            override suspend fun openRepl(id: String): Repl = object : Repl {
                override val id = id
                override suspend fun execute(intent: Phenomenon.Intent, context: IntentContext) =
                    ExecutedIntent(listOf(text(name)))
            }
            override fun close() {}
        }
        override fun close() {
            closed = true
        }
    }

    /** Keeping no content, as no intent reads any. */
    private val contents = object : Contents {
        override fun keep(content: ByteArray): String = refOf(content)
        override fun content(ref: String): ByteArray? = null
    }

    private val context = IntentContext(cogitation = Cogitation(constitution = "", enunciations = emptyList()), parentCogitation = null, contents = contents)

    @Test
    fun `should offer the types of every provider`() {
        // when
        val aggregate = AggregateComputerProvider(NamedProvider("local", listOf("default")), NamedProvider("fly", listOf("GPU64GB", "CPU8")))

        // then
        assert(aggregate.types == listOf("default", "GPU64GB", "CPU8"))
    }

    @Test
    fun `should start each computer by the provider of its type`() = runTest {
        // given
        val aggregate = AggregateComputerProvider(NamedProvider("local", listOf("default")), NamedProvider("fly", listOf("GPU64GB")))

        // when
        val onGpu = aggregate.start(Computer(type = "GPU64GB")).openRepl("r").execute(Phenomenon.Intent(script = ""), context).result
        val local = aggregate.start(Computer(type = "default")).openRepl("r").execute(Phenomenon.Intent(script = ""), context).result

        // then
        assert(onGpu == listOf(text("fly")))
        assert(local == listOf(text("local")))
    }

    @Test
    fun `should refuse to start a computer of a type no provider offers`() = runTest {
        // given
        val aggregate = AggregateComputerProvider(NamedProvider("local", listOf("default")))

        // when
        val refused = assertFailsWith<IllegalArgumentException> { aggregate.start(Computer(type = "TPU")) }

        // then
        assert(refused.message == "No provider of computers of the type TPU, but of default")
    }

    @Test
    fun `should refuse providers offering the same type`() {
        // when
        val refused = assertFailsWith<IllegalArgumentException> {
            AggregateComputerProvider(NamedProvider("local", listOf("default")), NamedProvider("docker", listOf("default", "CPU8")))
        }

        // then
        assert(refused.message == "Computer types offered by more than one provider: default")
    }

    @Test
    fun `should close every provider`() {
        // given
        val providers = listOf(NamedProvider("local", listOf("default")), NamedProvider("fly", listOf("GPU64GB")))

        // when
        AggregateComputerProvider(providers).close()

        // then
        assert(providers.all { it.closed })
    }

}

private fun text(text: String) = textOf(text).first
