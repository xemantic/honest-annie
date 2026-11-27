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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ProtocolTest {

    private val cogitation = Cogitation(
        constitution = "Be honest",
        enunciations = listOf(Enunciation(
            actant = Actant.INVOKER,
            initiated = Instant.parse("2026-11-24T12:00:00Z"),
            phenomena = listOf(textOf("Count \"the\" lines\n\u001E").first)
        ))
    )

    private val execute = ExecutorRequest.Execute(
        id = "request-1",
        session = "session-1",
        intent = Phenomenon.Intent(edits = listOf(Edit(path = "a.py", new = "x = 1\n")), script = "1 + 1")
    )

    @Test
    fun `should encode each request as a single line and decode it back`() {
        // given
        val python = execute.copy(
            id = "request-2",
            intent = Phenomenon.Intent(language = Phenomenon.Intent.Language.PYTHON, script = "print(1)", timeout = 90.seconds)
        )
        val requests = listOf(
            execute,
            python,
            ExecutorRequest.Recalled("recall-1", listOf(cogitation)),
            ExecutorRequest.Recalled("recall-2", emptyList()),
            ExecutorRequest.Loaded("load-1", byteArrayOf(0, -1, 127, 0x1E)),
            ExecutorRequest.Loaded("load-2", null),
            ExecutorRequest.Cancel("request-1"),
            ExecutorRequest.Close("session-1")
        )

        // when
        val lines = requests.map { it.encodeMessage() }

        // then
        assert(lines.all { it.startsWith(MESSAGE_SEPARATOR) && it.endsWith("\n") && it.count { char -> char == '\n' } == 1 })
        assert(lines.map { decodeRequest(it) } == requests)
    }

    @Test
    fun `should encode each response as a single line and decode it back`() {
        // given
        val (two, twoContent) = textOf("2", "text/plain")
        val imageContent = byteArrayOf(1, 2, 3)
        val image = Phenomenon.Data(ref = refOf(imageContent), size = 3, contentType = "image/png")
        val responses = listOf(
            ExecutorResponse.Executed(
                id = "request-1",
                result = listOf(two),
                output = listOf(image, Phenomenon.Impediment("stuck")),
                invoked = listOf(
                    Invocation(id = "invocation-1", prompt = "Count", constitution = "Be brief"),
                    Invocation(id = "invocation-2", prompt = "Train", computer = Computer(id = "computer-1", type = "gpu"), repl = "training")
                ),
                contents = mapOf(two.ref to twoContent, image.ref to imageContent)
            ),
            ExecutorResponse.Recall(id = "request-3", recall = "recall-1", ancestors = true),
            ExecutorResponse.Load(id = "request-3", load = "load-1", ref = two.ref),
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
