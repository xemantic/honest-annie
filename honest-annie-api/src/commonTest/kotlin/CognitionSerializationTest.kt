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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CognitionSerializationTest {

    private val initiated = Instant.parse("2026-11-24T12:00:00Z")

    private val intent = Phenomenon.Intent(
        edits = listOf(
            Edit(path = "a.py", new = "x = 1\n"),
            Edit(path = "b.py", new = "y", old = "x", all = true)
        ),
        script = "1 + 1"
    )

    private val cogitation = Cogitation(
        constitution = "Be honest",
        enunciations = listOf(
            Enunciation(
                actant = Actant.INVOKER,
                initiated = initiated,
                phenomena = listOf(Phenomenon.Text(ref = "sha256:01", length = 15, lines = 1))
            ),
            Enunciation(
                actant = Actant.SELF,
                initiated = initiated,
                finalized = initiated + 3.seconds,
                phenomena = listOf(Phenomenon.Monologue(ref = "sha256:05", length = 12, lines = 1), intent),
                inference = Inference(
                    model = "claude-fable-5-2",
                    provider = "anthropic",
                    tokens = mapOf("input" to 100L, "output" to 20L),
                    stop = Inference.Stop(kind = Inference.Stop.Kind.COMPLETED, reason = "tool_use")
                )
            ),
            Enunciation(
                actant = Actant(type = Actant.Type.COMPUTER, name = "user's computer"),
                initiated = initiated + 3.seconds,
                respondsTo = intent.id,
                phenomena = listOf(
                    Phenomenon.Text(ref = "sha256:02", length = 1, lines = 1, contentType = "text/plain"),
                    Phenomenon.Data(ref = "sha256:03", size = 4, contentType = "image/png"),
                    Phenomenon.Impediment("Failed")
                )
            ),
            // the outcome of a cogitation the intent invoked
            Enunciation(
                actant = Actant.SELF,
                initiated = initiated + 3.seconds,
                finalized = initiated + 5.seconds,
                respondsTo = "invoked-1",
                phenomena = listOf(Phenomenon.Text(ref = "sha256:04", length = 2, lines = 1))
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
    fun `should keep the id of an enunciation responding to a request`() {
        // given
        val enunciation = cogitation.enunciations[2]

        // when
        val decoded = Json.decodeFromString<Enunciation>(Json.encodeToString(enunciation))

        // then
        assert(decoded.id == responseId(intent.id))
        assert(decoded.respondsTo == intent.id)
    }

    @Test
    fun `should not serialize the properties derived from others`() {
        // when
        val json: JsonElement = Json.parseToJsonElement(Json.encodeToString(cogitation))

        // then
        assert(json.keys == setOf("constitution", "enunciations", "depth", "computer", "repl"))
        assert(json["enunciations"][1].keys == setOf("actant", "initiated", "finalized", "id", "phenomena", "inference"))
        assert(json["enunciations"][1]["inference"].keys == setOf("model", "provider", "tokens", "stop"))
        assert(json["enunciations"][1]["inference"]["tokens"] == Json.parseToJsonElement("""{"input": 100, "output": 20}"""))
    }

    @Test
    fun `should serialize texts and data by the refs of their content`() {
        // when
        val json: JsonElement = Json.parseToJsonElement(Json.encodeToString(cogitation))

        // then
        val phenomena = json["enunciations"][2]["phenomena"]
        assert(phenomena[0].keys == setOf("type", "ref", "length", "lines", "contentType"))
        assert(phenomena[1].keys == setOf("type", "ref", "size", "contentType"))
    }

}
