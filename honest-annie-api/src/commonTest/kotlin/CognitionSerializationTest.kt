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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CognitionSerializationTest {

    private val initiated = Instant.parse("2026-11-24T12:00:00Z")

    private val intent = Phenomenon.Intent(
        script = "1 + 1",
        edits = listOf(
            Edit(path = "a.py", new = "x = 1\n"),
            Edit(path = "b.py", new = "y", old = "x", all = true)
        )
    )

    private val invocation = Phenomenon.Invocation(id = "invocation-1", prompt = "Count", constitution = "Be brief")

    private val cogitation = Cogitation(
        constitution = listOf("Be honest", "Be brief"),
        enunciations = listOf(
            Enunciation(
                actant = Actant.INVOKER,
                initiated = initiated,
                phenomena = listOf(Phenomenon.Text("Count the lines"))
            ),
            Enunciation(
                actant = Actant.SELF,
                initiated = initiated,
                finalized = initiated + 3.seconds,
                phenomena = listOf(Phenomenon.Monologue("Let me count"), intent),
                inference = Inference(
                    model = "claude-fable-5-2",
                    provider = "anthropic",
                    tokens = mapOf(Inference.Tokens.INPUT to 100L, Inference.Tokens.OUTPUT to 20L),
                    stop = Inference.Stop(kind = Inference.Stop.Kind.COMPLETED, reason = "tool_use")
                )
            ),
            Enunciation(
                actant = Actant(type = Actant.Type.COMPUTER, name = "user's computer"),
                initiated = initiated + 3.seconds,
                respondsTo = intent.id,
                phenomena = listOf(
                    invocation,
                    Phenomenon.Text("2", "text/plain"),
                    Phenomenon.Data(byteArrayOf(0, 1, -1, 127), "image/png"),
                    Phenomenon.Impediment("Failed")
                )
            )
        ),
        depth = 1
    )

    @Test
    fun `should serialize a cogitation to JSON and back`() {
        // when
        val json = Json.encodeToString(cogitation)
        val decoded = Json.decodeFromString<Cogitation>(json)

        // then
        assert(decoded == cogitation)
    }

    @Test
    fun `should serialize the bytes of data as Base64 rather than as an array of numbers`() {
        // given
        val data = Phenomenon.Data(byteArrayOf(0, 1, -1, 127), "image/png")

        // when
        val json = Json.parseToJsonElement(Json.encodeToString<Phenomenon>(data))

        // then
        assert(json["bytes"].string == "AAH/fw==")
        assert(json["contentType"].string == "image/png")
    }

    @Test
    fun `should name the kind of a phenomenon in its JSON`() {
        // when
        val kinds = listOf(
            Phenomenon.Text("a"),
            Phenomenon.Data(byteArrayOf()),
            intent,
            invocation,
            Phenomenon.Impediment("b"),
            Phenomenon.Monologue("c")
        ).map { Json.parseToJsonElement(Json.encodeToString<Phenomenon>(it))["type"].string }

        // then
        assert(kinds == listOf("text", "data", "intent", "invocation", "impediment", "monologue"))
    }

    @Test
    fun `should keep the id of an enunciation responding to a request`() {
        // given
        val enunciation = cogitation.enunciations.last()

        // when
        val decoded = Json.decodeFromString<Enunciation>(Json.encodeToString(enunciation))

        // then
        assert(decoded.id == responseId(intent.id))
        assert(decoded.respondsTo == intent.id)
    }

    @Test
    fun `should serialize an allowance to JSON and back`() {
        // given
        val allowance = Allowance(tokens = 1000, spentTokens = 10, time = 30.minutes, started = initiated)

        // when
        val decoded = Json.decodeFromString<Allowance>(Json.encodeToString(allowance))

        // then
        assert(decoded == allowance)
    }

    @Test
    fun `should not serialize the properties derived from others`() {
        // when
        val json: JsonElement = Json.parseToJsonElement(Json.encodeToString(cogitation))

        // then
        assert(json.keys == setOf("constitution", "enunciations", "depth"))
        assert(json["enunciations"][1].keys == setOf("actant", "initiated", "finalized", "id", "phenomena", "inference"))
        assert(json["enunciations"][1]["inference"].keys == setOf("model", "provider", "tokens", "stop"))
    }

}
