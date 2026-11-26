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

package com.xemantic.annie

import com.xemantic.kotlin.test.assert
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ProtocolTest {

    private val cogitation = Cogitation(
        constitution = "Be honest",
        enunciations = listOf(Enunciation(
            actant = Actant.INVOKER,
            initiated = Instant.parse("2026-11-24T12:00:00Z"),
            phenomena = listOf(Phenomenon.Text("Count \"the\" lines\n\u001E"))
        ))
    )

    private val execute = ExecutorRequest.Execute(
        id = "request-1",
        session = "session-1",
        intent = Phenomenon.Intent(script = "1 + 1", edits = listOf(Edit(path = "a.py", new = "x = 1\n"))),
        context = IntentContext(
            cogitation = cogitation,
            parentCogitation = null,
            allowance = Allowance(tokens = 100, time = 5.minutes, started = Instant.parse("2026-11-24T12:00:00Z"))
        )
    )

    @Test
    fun `should encode each request as a single line and decode it back`() {
        // given
        val requests = listOf(execute, ExecutorRequest.Cancel("request-1"), ExecutorRequest.Close("session-1"))

        // when
        val lines = requests.map { it.encodeMessage() }

        // then
        assert(lines.all { it.startsWith(MESSAGE_SEPARATOR) && it.endsWith("\n") && it.count { char -> char == '\n' } == 1 })
        assert(lines.map { decodeRequest(it) } == requests)
    }

    @Test
    fun `should encode each response as a single line and decode it back`() {
        // given
        val responses = listOf(
            ExecutorResponse.Executed(
                id = "request-1",
                result = listOf(
                    Phenomenon.Invocation(id = "invocation-1", prompt = "Count", constitution = "Be brief"),
                    Phenomenon.Text("2", "text/plain")
                ),
                enunciated = listOf(Phenomenon.Data(byteArrayOf(1, 2, 3), "image/png"), Phenomenon.Impediment("stuck"))
            ),
            ExecutorResponse.Failed(id = "request-2", reason = "No session\nat all")
        )

        // when
        val lines = responses.map { it.encodeMessage() }

        // then
        assert(lines.all { it.startsWith(MESSAGE_SEPARATOR) && it.endsWith("\n") && it.count { char -> char == '\n' } == 1 })
        assert(lines.map { decodeResponse(it) } == responses)
    }

    @Test
    fun `should decode a message preceded by what something else wrote on the same line`() {
        // given
        val line = "printed without a line feed" + ExecutorResponse.Failed("request-1", "boom").encodeMessage()

        // when
        val response = decodeResponse(line)

        // then
        assert(response == ExecutorResponse.Failed("request-1", "boom"))
    }

    @Test
    fun `should decode nothing of a line carrying no message`() {
        // when
        val request = decodeRequest("hello from a native library")
        val response = decodeResponse("")

        // then
        assert(request == null)
        assert(response == null)
    }

}
