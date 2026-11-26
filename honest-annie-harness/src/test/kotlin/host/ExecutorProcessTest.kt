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
import com.xemantic.annie.Agency
import com.xemantic.annie.Allowance
import com.xemantic.annie.Cogitation
import com.xemantic.annie.Edit
import com.xemantic.annie.Enunciation
import com.xemantic.annie.Execution
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.Python
import com.xemantic.annie.harness.cognizer.Cognized
import com.xemantic.annie.harness.cognizer.Cognizer
import com.xemantic.kotlin.test.assert
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class ExecutorProcessTest {

    private val executor = ExecutorProcess()

    @AfterTest
    fun closeExecutor() {
        executor.close()
    }

    private val cogitation = Cogitation(constitution = "Be honest", enunciations = emptyList(), depth = 1)

    /** The agency of the host, telling of the [cogitation], what the intent enunciates being kept by the executor. */
    private inner class HostAgency : Agency {
        override val cogitation: Cogitation = this@ExecutorProcessTest.cogitation
        override val parentCogitation: Cogitation? = null
        override val allowance = Allowance(tokens = 100, spentTokens = 40)
        override suspend fun invoke(prompt: String, constitution: String): Phenomenon.Invocation = throw UnsupportedOperationException()
        override suspend fun enunciate(text: String, contentType: String): Unit = throw UnsupportedOperationException()
        override suspend fun enunciate(bytes: ByteArray, contentType: String?): Unit = throw UnsupportedOperationException()
        override suspend fun impede(reason: String): Unit = throw UnsupportedOperationException()
        override suspend fun resolveDeclaration(name: String, page: Int, pageSize: Int): String? = throw UnsupportedOperationException()
        override suspend fun searchDeclarations(query: String, page: Int, pageSize: Int): String? = throw UnsupportedOperationException()
        override suspend fun execute(
            command: List<String>,
            input: String?,
            directory: String?,
            environment: Map<String, String>,
            timeout: Duration,
            maxOutputLength: Int
        ): Execution = throw UnsupportedOperationException()
        override val python: Python get() = throw UnsupportedOperationException()
        override val http: HttpClient get() = throw UnsupportedOperationException()
    }

    private fun test(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) { withTimeout(5.minutes) { block() } }

    @Test
    fun `should execute an intent in the executor process`() = test {
        // given
        val session = executor.session()
        val agency = HostAgency()

        // when
        val result = session.execute(
            "\"${'$'}{ProcessHandle.current().pid() != ${ProcessHandle.current().pid()}L} ${'$'}{agency.cogitation.depth} ${'$'}{agency.allowance.tokensLeft}\"",
            cogitation,
            agency
        ).result

        // then
        assert(result == listOf(Phenomenon.Text("true 1 60", "text/plain")))
    }

    @Test
    fun `should return what the intent enunciated in the executor process as its output`() = test {
        // given
        val agency = HostAgency()

        // when
        val executed = executor.session().execute(
            """
            agency.enunciate("first")
            agency.enunciate(byteArrayOf(4, 2), "image/png")
            agency.impede("stuck")
            agency.invoke("Count", "Be brief")
            """.trimIndent(),
            cogitation,
            agency
        )

        // then
        val invocation = executed.result.single() as Phenomenon.Invocation
        assert(invocation.prompt == "Count" && invocation.constitution == "Be brief")
        assert(executed.output == listOf(
            Phenomenon.Text("first"),
            Phenomenon.Data(byteArrayOf(4, 2), "image/png"),
            Phenomenon.Impediment("stuck")
        ))
    }

    @Test
    fun `should apply the edits of the intent in the executor process`() = test {
        // given
        val file = createTempDirectory().resolve("a.py")

        // when
        val result = executor.session().execute(
            Phenomenon.Intent(script = "Path(\"$file\").readText()", edits = listOf(Edit(path = file.toString(), new = "x = 1\n"))),
            cogitation,
            HostAgency()
        ).result

        // then
        assert(file.readText() == "x = 1\n")
        assert(result.last() == Phenomenon.Text("x = 1\n", "text/plain"))
    }

    @Test
    fun `should keep the declarations of each session apart until it is closed`() = test {
        // given
        val session = executor.session()
        val other = executor.session()

        // when
        session.execute("val answer = 42", cogitation, HostAgency())
        val same = session.execute("answer", cogitation, HostAgency()).result
        val apart = other.execute("answer", cogitation, HostAgency()).result
        session.close()
        val reopened = executor.session().execute("6 * 7", cogitation, HostAgency()).result

        // then
        assert(same == listOf(Phenomenon.Text("42", "text/plain")))
        assert(apart.single() is Phenomenon.Impediment)
        assert(reopened == listOf(Phenomenon.Text("42", "text/plain")))
    }

    @Test
    fun `should not mistake what the executor process prints for a response`() = test {
        // when
        val result = executor.session().execute(
            """
            println("printed by a script")
            java.io.FileOutputStream(java.io.FileDescriptor.out).apply { write("printed without a line feed".toByteArray()); flush() }
            6 * 7
            """.trimIndent(),
            cogitation,
            HostAgency()
        ).result

        // then
        assert(result == listOf(Phenomenon.Text("42", "text/plain")))
    }

    // suspending in delay, as a cancelled intent blocking the thread of its session, e.g. in Thread.sleep, is not interrupted
    @Test
    fun `should cancel the intent executing in the executor process`() = test {
        // given
        val session = executor.session()
        val started = createTempDirectory().resolve("started")

        // when
        coroutineScope {
            val slow = async {
                session.execute("Path(\"$started\").writeText(\"\")\ndelay(600_000)", cogitation, HostAgency())
            }
            while (!started.exists()) delay(10)
            slow.cancel()
        }
        // the intents of a session execute one after another, so this one waits until the slow one is cancelled
        val next = session.execute("6 * 7", cogitation, HostAgency()).result

        // then
        assert(next == listOf(Phenomenon.Text("42", "text/plain")))
    }

    @Test
    fun `should unfold a tree of cogitations whose intents execute in the executor process`() = test {
        // given
        val cognizer = Cognizer { cogitation ->
            val prompt = cogitation.enunciations.first().phenomena.single() as Phenomenon.Text
            Cognized(listOf(
                when {
                    cogitation.depth > 0 -> Phenomenon.Text("echo: ${prompt.text}")
                    cogitation.enunciations.size == 1 -> Phenomenon.Intent("agency.invoke(\"sub\")")
                    // the task, the intent invoking, the response to the intent, and the one to the invocation
                    cogitation.enunciations.size == 4 -> Phenomenon.Intent(
                        "agency.enunciate(\"got: \" + (agency.cogitation.enunciations[3].phenomena.single() as Phenomenon.Text).text)"
                    )
                    // answering, so that the cogitation rests, rather than inferring again and again
                    else -> Phenomenon.Text("unexpected: ${cogitation.enunciations.last().phenomena}")
                }
            ))
        }

        // when
        val output = HarnessHost(cognizer, executors = executor::session).cognize(Cogitation(
            constitution = "",
            enunciations = listOf(Enunciation(
                actant = Actant(type = Actant.Type.HUMAN, name = "user"),
                phenomena = listOf(Phenomenon.Text("test"))
            ))
        ))

        // then
        assert(output.phenomena == listOf(Phenomenon.Text("got: echo: sub")))
    }

    @Test
    fun `should fail the intents once the executor process exits`() = test {
        // given
        val session = executor.session()

        // when
        val exited = assertFailsWith<IllegalStateException> {
            session.execute("kotlin.system.exitProcess(3)", cogitation, HostAgency())
        }
        val after = assertFailsWith<IllegalStateException> {
            executor.session().execute("6 * 7", cogitation, HostAgency())
        }

        // then
        assert(exited.message == "The executor process exited with 3")
        assert(after.message == "The executor process exited with 3")
    }

}
