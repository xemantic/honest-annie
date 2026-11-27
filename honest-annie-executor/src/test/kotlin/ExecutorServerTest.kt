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

import com.xemantic.annie.*
import com.xemantic.kotlin.test.assert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

class ExecutorServerTest {

    private val cogitation = Cogitation(constitution = "Be honest", enunciations = emptyList(), depth = 2)

    private val context = IntentContext(
        cogitation = cogitation,
        parentCogitation = null
    )

    /** Serves the requests sent to the channel of [block], whose responses it receives, until the block returns. */
    private fun serving(
        block: suspend Serving.() -> Unit
    ) = runBlocking(Dispatchers.IO) { withTimeout(5.minutes) {
        val requests = Channel<String>(Channel.UNLIMITED)
        val responses = Channel<String>(Channel.UNLIMITED)
        val server = launch(Dispatchers.IO) {
            ExecutorServer { SessionScriptExecutor() }.serve(requests) { responses.trySend(it) }
        }
        try {
            Serving(requests, responses).block()
        } finally {
            requests.close()
            server.join()
        }
    } }

    private class Serving(
        private val requests: Channel<String>,
        private val responses: Channel<String>
    ) {
        suspend fun send(request: ExecutorRequest) = requests.send(request.encodeMessage())
        suspend fun send(line: String) = requests.send(line)
        suspend fun receive(): ExecutorResponse = checkNotNull(decodeResponse(responses.receive()))
    }

    private fun execute(id: String, script: String, session: String = "session-1", edits: List<Edit> = emptyList()) =
        ExecutorRequest.Execute(id = id, session = session, intent = Phenomenon.Intent(edits = edits, script = script), context = context)

    @Test
    fun `should respond with the result of the intent and what it enunciated`() = serving {
        // when
        send(execute("request-1", $$"""
            agency.enunciate("first")
            agency.enunciate(byteArrayOf(4, 2), "image/png")
            agency.impede("stuck")
            agency.invoke("Count", "Be brief")
            "${agency.cogitation.depth} ${agency.cogitation.constitution}"
        """.trimIndent()))
        val response = receive() as ExecutorResponse.Executed

        // then
        assert(response.id == "request-1")
        val invocation = response.result.first() as Phenomenon.Invocation
        assert(invocation.prompt == "Count" && invocation.constitution == "Be brief")
        assert(response.result.drop(1) == listOf(Phenomenon.Text("2 Be honest", "text/plain")))
        assert(response.output == listOf(
            Phenomenon.Text("first"),
            Phenomenon.Data(byteArrayOf(4, 2), "image/png"),
            Phenomenon.Impediment("stuck")
        ))
    }

    @Test
    fun `should keep what an intent declares for the next intents of its session only`() = serving {
        // when
        send(execute("request-1", "val answer = 42"))
        val declared = receive()
        send(execute("request-2", "answer"))
        val same = receive()
        send(execute("request-3", "answer", session = "session-2"))
        val other = receive()

        // then
        assert(declared == ExecutorResponse.Executed("request-1", result = emptyList()))
        assert(same == ExecutorResponse.Executed("request-2", result = listOf(Phenomenon.Text("42", "text/plain"))))
        assert(other is ExecutorResponse.Executed && other.result.single() is Phenomenon.Impediment)
    }

    @Test
    fun `should apply the edits of the intent in the executor`() = serving {
        // given
        val file = createTempDirectory().resolve("a.py")

        // when
        send(execute("request-1", "", edits = listOf(Edit(path = file.toString(), new = "x = 1\n"))))
        val response = receive() as ExecutorResponse.Executed

        // then
        assert(file.readText() == "x = 1\n")
        assert((response.result.single() as Phenomenon.Text).text.startsWith("Wrote $file"))
    }

    // The slow intents suspend in delay, as a cancelled intent is not interrupted, see ScriptSession of xemantic-kotlin-script,
    // so that one blocking the thread of its session, e.g. in Thread.sleep, would run to its end.
    @Test
    fun `should respond to intents of other sessions while one is still executing`() = serving {
        // when
        send(execute("slow", "delay(600_000)"))
        send(execute("fast", "6 * 7", session = "session-2"))
        val response = receive()
        send(ExecutorRequest.Cancel("slow"))

        // then
        assert(response == ExecutorResponse.Executed("fast", result = listOf(Phenomenon.Text("42", "text/plain"))))
    }

    @Test
    fun `should cancel an intent and execute the next one of its session`() = serving {
        // when
        send(execute("slow", "delay(600_000)"))
        send(ExecutorRequest.Cancel("slow"))
        send(execute("next", "6 * 7"))
        val response = receive()

        // then
        // the cancelled intent is responded by nothing, as nothing awaits it anymore
        assert(response == ExecutorResponse.Executed("next", result = listOf(Phenomenon.Text("42", "text/plain"))))
    }

    @Test
    fun `should forget what a closed session declared`() = serving {
        // when
        send(execute("request-1", "val answer = 42"))
        receive()
        send(ExecutorRequest.Close("session-1"))
        send(execute("request-2", "answer"))
        val response = receive()

        // then
        assert(response is ExecutorResponse.Executed && response.result.single() is Phenomenon.Impediment)
    }

    @Test
    fun `should respond with the failure of the executor`() = runBlocking(Dispatchers.IO) { withTimeout(5.minutes) {
        // given
        val requests = Channel<String>(Channel.UNLIMITED)
        val responses = Channel<String>(Channel.UNLIMITED)
        val server = CoroutineScope(Dispatchers.IO).launch {
            ExecutorServer { error("No session today") }.serve(requests) { responses.trySend(it) }
        }

        // when
        requests.send(execute("request-1", "6 * 7").encodeMessage())
        val response = decodeResponse(responses.receive())
        requests.close()
        server.join()

        // then
        assert(response is ExecutorResponse.Failed && response.id == "request-1" && "No session today" in response.reason)
    } }

    @Test
    fun `should ignore a line carrying no message`() = serving {
        // when
        send("printed by a native library")
        send(execute("request-1", "6 * 7"))
        val response = receive()

        // then
        assert(response == ExecutorResponse.Executed("request-1", result = listOf(Phenomenon.Text("42", "text/plain"))))
    }

}
