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

import com.xemantic.annie.Actant
import com.xemantic.annie.Cogitation
import com.xemantic.annie.Enunciation
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.computer.local.LocalComputerProvider
import com.xemantic.annie.harness.cognizer.Cognized
import com.xemantic.annie.harness.cognizer.Cognizer
import com.xemantic.kotlin.test.assert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/** The harness unfolding cogitations on the computers of the host, each one an executor process, see [LocalComputerProvider]. */
class LocalComputersTest {

    private val computers = LocalComputerProvider()

    @AfterTest
    fun closeComputers() {
        computers.close()
    }

    private fun test(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) { withTimeout(5.minutes) { block() } }

    @Test
    fun `should unfold a tree of cogitations whose intents execute on a computer of an executor process`() = test {
        // given
        val cognizer = Cognizer { cogitation ->
            val prompt = cogitation.enunciations.first().phenomena.single() as Phenomenon.Text
            Cognized(listOf(
                when {
                    cogitation.depth > 0 -> Phenomenon.Text("echo: ${prompt.text}")
                    cogitation.enunciations.size == 1 -> Phenomenon.Intent(script = "agency.invoke(\"sub\")")
                    // the task, the intent invoking, the response to the intent, and the one to the invocation
                    cogitation.enunciations.size == 4 -> Phenomenon.Intent(
                        script = "agency.enunciate(\"got: \" + (agency.cogitation.enunciations[3].phenomena.single() as Phenomenon.Text).text)"
                    )
                    // answering, so that the cogitation rests, rather than inferring again and again
                    else -> Phenomenon.Text("unexpected: ${cogitation.enunciations.last().phenomena}")
                }
            ))
        }

        // when
        val output = HarnessHost(cognizer, computers = computers).cognize(Cogitation(
            constitution = "",
            enunciations = listOf(Enunciation(
                actant = Actant(type = Actant.Type.HUMAN, name = "user"),
                phenomena = listOf(Phenomenon.Text("test"))
            ))
        ))

        // then
        assert(output.phenomena == listOf(Phenomenon.Text("got: echo: sub")))
    }
}
