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

import com.xemantic.annie.Actant
import com.xemantic.annie.Cogitation
import com.xemantic.annie.Edit
import com.xemantic.annie.Enunciation
import com.xemantic.annie.InMemoryContents
import com.xemantic.annie.ExecutedIntent
import com.xemantic.annie.IntentContext
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.data
import com.xemantic.annie.computer.Repl
import com.xemantic.kotlin.test.assert
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ExecutorProcessTest {

    private val executor = ExecutorProcess()

    @AfterTest
    fun closeExecutor() {
        executor.close()
    }

    /** The content of the phenomena of the [cogitation], kept by the harness. */
    private val contents = InMemoryContents()

    private val image = contents.data(byteArrayOf(4, 2, 0), "image/png")

    private val cogitation = Cogitation(
        constitution = "Be honest",
        enunciations = listOf(Enunciation(actant = Actant.INVOKER, phenomena = listOf(image))),
        depth = 1
    )

    private val context = IntentContext(cogitation, ancestorCogitations = emptyList(), contents = contents)

    private suspend fun Repl.execute(script: String): ExecutedIntent = execute(Phenomenon.Intent(script = script), context)

    private suspend fun Repl.execute(intent: Phenomenon.Intent): ExecutedIntent = execute(intent, context)

    private fun test(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) { withTimeout(5.minutes) { block() } }

    @Test
    fun `should execute an intent in the executor process`() = test {
        // given
        val session = executor.repl()

        // when
        val result = session.execute(
            "\"${'$'}{ProcessHandle.current().pid() != ${ProcessHandle.current().pid()}L} ${'$'}{agency.cogitation().depth}\"").result

        // then
        assert(result == listOf(text("true 1", "text/plain")))
    }

    @Test
    fun `should recall the cogitations from the harness only when the intent asks for them`() = test {
        // given
        val recalled = mutableListOf<String>()
        val recording = object : IntentContext {
            override suspend fun cogitation() = cogitation.also { recalled += "cogitation" }
            override suspend fun ancestorCogitations() = listOf(cogitation.copy(depth = 0)).also { recalled += "ancestors" }
            override suspend fun content(ref: String) = contents.content(ref).also { recalled += "content" }
        }
        val session = executor.repl()

        // when
        val unasked = session.execute(Phenomenon.Intent(script = "6 * 7"), recording).result
        val unaskedRecalls = recalled.toList()
        val asked = session.execute(
            Phenomenon.Intent(script = "\"${'$'}{agency.cogitation().depth} ${'$'}{agency.ancestorCogitations().map { it.depth }}\""), recording
        ).result

        // then
        assert(unasked == listOf(text("42", "text/plain")))
        assert(unaskedRecalls.isEmpty())
        assert(asked == listOf(text("1 [0]", "text/plain")))
        assert(recalled == listOf("cogitation", "ancestors"))
    }

    @Test
    fun `should load the content of a phenomenon from the harness when the intent reads it`() = test {
        // given
        val session = executor.repl()

        // when
        val result = session.execute("agency.readBytes(agency.cogitation().enunciations[0].phenomena[0]).sum()").result

        // then
        assert(result == listOf(text("6", "text/plain")))
    }

    @Test
    fun `should send the content of what the intent created along its result`() = test {
        // when
        val executed = executor.repl().execute("agency.enunciate(byteArrayOf(4, 2), \"image/png\")\n\"value\"")

        // then
        assert(executed.text(executed.result.single()) == "value")
        assert(executed.contents.getValue(data(byteArrayOf(4, 2), "image/png").ref).contentEquals(byteArrayOf(4, 2)))
    }

    @Test
    fun `should return what the intent enunciated in the executor process as its output`() = test {
        // given

        // when
        val executed = executor.repl().execute(
            """
            agency.enunciate("first")
            agency.enunciate(byteArrayOf(4, 2), "image/png")
            agency.impede("stuck")
            agency.invoke("Count", "Be brief")
            """.trimIndent())

        // then
        val invocation = executed.invoked.single()
        assert(invocation.prompt == "Count" && invocation.constitution == "Be brief")
        assert(executed.result.isEmpty())
        assert(executed.output == listOf(
            text("first"),
            data(byteArrayOf(4, 2), "image/png"),
            Phenomenon.Impediment("stuck")
        ))
    }

    @Test
    fun `should apply the edits of the intent in the executor process`() = test {
        // given
        val file = createTempDirectory().resolve("a.py")

        // when
        val result = executor.repl().execute(
            Phenomenon.Intent(edits = listOf(Edit(path = file.toString(), new = "x = 1\n")), script = "Path(\"$file\").readText()")).result

        // then
        assert(file.readText() == "x = 1\n")
        assert(result.last() == text("x = 1\n", "text/plain"))
    }

    @Test
    fun `should execute Python and bash scripts of intents in the executor process`() = test {
        // given
        val session = executor.repl()

        // when
        val python = session.execute(Phenomenon.Intent(language = Phenomenon.Intent.Language.PYTHON, script = "[n * n for n in range(4)]")).result
        val bash = session.execute(
            Phenomenon.Intent(language = Phenomenon.Intent.Language.BASH, script = "seq 1 3", timeout = 30.seconds)).result

        // then
        assert(python == listOf(text("[0, 1, 4, 9]", "text/plain")))
        assert(bash == listOf(text("1\n2\n3\nexit code: 0", "text/plain")))
    }

    @Test
    fun `should keep the declarations of each session apart until it is closed`() = test {
        // given
        val session = executor.repl()
        val other = executor.repl()

        // when
        session.execute("val answer = 42")
        val same = session.execute("answer").result
        val apart = other.execute("answer").result
        session.close()
        val reopened = executor.repl().execute("6 * 7").result

        // then
        assert(same == listOf(text("42", "text/plain")))
        assert(apart.single() is Phenomenon.Impediment)
        assert(reopened == listOf(text("42", "text/plain")))
    }

    @Test
    fun `should not mistake what the executor process prints for a response`() = test {
        // when
        val result = executor.repl().execute(
            """
            println("printed by a script")
            java.io.FileOutputStream(java.io.FileDescriptor.out).apply { write("printed without a line feed".toByteArray()); flush() }
            6 * 7
            """.trimIndent()).result

        // then
        assert(result == listOf(text("42", "text/plain")))
    }

    // suspending in delay, as a cancelled intent blocking the thread of its session, e.g. in Thread.sleep, is not interrupted
    @Test
    fun `should cancel the intent executing in the executor process`() = test {
        // given
        val session = executor.repl()
        val started = createTempDirectory().resolve("started")

        // when
        coroutineScope {
            val slow = async {
                session.execute("Path(\"$started\").writeText(\"\")\ndelay(600_000)")
            }
            while (!started.exists()) delay(10)
            slow.cancel()
        }
        // the intents of a session execute one after another, so this one waits until the slow one is cancelled
        val next = session.execute("6 * 7").result

        // then
        assert(next == listOf(text("42", "text/plain")))
    }

    @Test
    fun `should fail the intents once the executor process exits`() = test {
        // given
        val session = executor.repl()

        // when
        val exited = assertFailsWith<IllegalStateException> {
            session.execute("kotlin.system.exitProcess(3)")
        }
        val after = assertFailsWith<IllegalStateException> {
            executor.repl().execute("6 * 7")
        }

        // then
        assert(exited.message == "The executor process exited with 3")
        assert(after.message == "The executor process exited with 3")
    }

}
