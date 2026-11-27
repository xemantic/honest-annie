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

package com.xemantic.annie.harness.cognizer.claude

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Message
import com.fasterxml.jackson.databind.JsonNode
import com.xemantic.annie.Actant
import com.xemantic.annie.Cogitation
import com.xemantic.annie.Enunciation
import com.xemantic.annie.Inference
import com.xemantic.annie.Inference.Tokens
import com.xemantic.annie.Edit
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.Phenomenon.Monologue
import com.xemantic.annie.harness.cognizer.Effort
import com.xemantic.annie.harness.cognizer.InMemoryMonologueStore
import com.xemantic.annie.harness.cognizer.IntentTool
import com.xemantic.annie.harness.cognizer.MonologueTrace
import com.xemantic.annie.harness.cognizer.MonologueVisibility
import com.xemantic.annie.harness.cognizer.minimalPdf
import com.xemantic.annie.harness.cognizer.redPixelPng
import com.xemantic.annie.harness.cognizer.redPixelPngBase64
import com.anthropic.models.messages.CacheControlEphemeral
import com.xemantic.kotlin.test.assert
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.time.Instant

class ClaudeInferenceTest {

    // never called, only the requests are built
    private val client = AnthropicOkHttpClient.builder().apiKey("test").build()

    private val initiated = Instant.parse("2026-10-22T10:15:30Z")

    private fun json(text: String): JsonNode = jsonMapper().readTree(text)

    private fun cogitation(vararg enunciations: Enunciation) = Cogitation(
        constitution = "constitution",
        enunciations = listOf(Enunciation(
            actant = Actant(type = Actant.Type.HUMAN, name = "user"),
            initiated = initiated,
            phenomena = listOf(Phenomenon.Text("test"))
        )) + enunciations
    )

    private fun message(usage: String, stopReason: String = "end_turn", stopDetails: String = "null"): Message = jsonMapper().readValue("""
        {
          "id": "msg_1",
          "type": "message",
          "role": "assistant",
          "model": "claude-opus-5-5",
          "content": [{"type": "text", "text": "6 * 7"}],
          "stop_reason": "$stopReason",
          "stop_details": $stopDetails,
          "stop_sequence": null,
          "usage": $usage
        }
    """, Message::class.java)

    private fun ClaudeInference.body(cogitation: Cogitation): JsonNode =
        json(jsonMapper().writeValueAsString(request(cogitation)._body()))

    /** The properties of the input of the tool of intents, see IntentToolTest. */
    private val intentProperties = jsonMapper().writeValueAsString(IntentTool.properties)

    @Test
    fun `should request adaptive thinking with effort, cached constitution and the tool executing intents`() {
        // given
        val inference = ClaudeInference(
            client = client,
            model = "claude-opus-5-5",
            maxTokens = 32000,
            effort = Effort.MAX,
            visibility = MonologueVisibility.SUMMARY
        )

        // when
        val body = inference.body(cogitation())

        // then
        assert(body["model"].asText() == "claude-opus-5-5")
        assert(body["max_tokens"].asLong() == 32000L)
        assert(body["thinking"] == json("""{"type": "adaptive", "display": "summarized"}"""))
        assert(body["output_config"] == json("""{"effort": "max"}"""))
        assert(body["tool_choice"] == json("""{"type": "auto", "disable_parallel_tool_use": true}"""))
        // a call cannot be forced on the latest models, but its arguments always fit the schema
        assert(body["tools"] == json("""
            [{"name": "execute", "description": ${jsonMapper().writeValueAsString(IntentTool.DESCRIPTION)},
              "input_schema": {"type": "object", "properties": $intentProperties, "additionalProperties": false},
              "strict": true}]
        """))
        assert(body["system"] == json("""
            [{"type": "text", "text": "constitution", "cache_control": {"type": "ephemeral", "ttl": "5m"}}]
        """))
        assert(body["cache_control"] == json("""{"type": "ephemeral", "ttl": "5m"}"""))
        assert(body["messages"] == json("""
            [{"role": "user", "content": "<annie:cogitation depth=\"0\"/>\n<annie:actant type=\"human\" name=\"user\" initiated=\"2026-10-22T10:15:30Z\"/>\ntest"}]
        """))
    }

    @Test
    fun `should cache constitution and cogitation for an hour when asked to`() {
        // given
        val inference = ClaudeInference(client = client, model = "claude-opus-5-5", cache = CacheControlEphemeral.Ttl.TTL_1H)

        // when
        val body = inference.body(cogitation())

        // then
        assert(body["system"][0]["cache_control"] == json("""{"type": "ephemeral", "ttl": "1h"}"""))
        assert(body["cache_control"] == json("""{"type": "ephemeral", "ttl": "1h"}"""))
    }

    @Test
    fun `should request with image perceived after its markup`() {
        // given
        val inference = ClaudeInference(client = client, model = "claude-opus-5-5")

        // when
        val body = inference.body(cogitation(
            Enunciation(
                actant = Actant(type = Actant.Type.COMPUTER, name = "user's computer"),
                initiated = initiated,
                phenomena = listOf(Phenomenon.Data(redPixelPng, "image/png"))
            )
        ))

        // then
        assert(body["messages"] == json("""
            [{"role": "user", "content": [
              {"type": "text", "text": "<annie:cogitation depth=\"0\"/>\n<annie:actant type=\"human\" name=\"user\" initiated=\"2026-10-22T10:15:30Z\"/>\ntest\n\n<annie:actant type=\"computer\" name=\"user's computer\" initiated=\"2026-10-22T10:15:30Z\"/>\n<annie:data content-type=\"image/png\" size=\"70\"/>"},
              {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "$redPixelPngBase64"}}
            ]}]
        """))
    }

    @Test
    fun `should request with PDF document perceived after its markup`() {
        // given
        val inference = ClaudeInference(client = client, model = "claude-opus-5-5")

        // when
        val body = inference.body(cogitation(
            Enunciation(
                actant = Actant(type = Actant.Type.COMPUTER, name = "user's computer"),
                initiated = initiated,
                phenomena = listOf(Phenomenon.Data(minimalPdf, "application/pdf"))
            )
        ))

        // then
        assert(body["messages"][0]["content"][1] == json("""
            {"type": "document", "source": {"type": "base64", "media_type": "application/pdf", "data": "${Base64.encode(minimalPdf)}"}}
        """))
    }

    @Test
    fun `should request without system prompt when constitution is blank`() {
        // given
        val inference = ClaudeInference(client = client, model = "claude-opus-5-5")

        // when
        val body = inference.body(cogitation().copy(constitution = ""))

        // then
        assert(body["system"] == null)
        assert(body["cache_control"] == json("""{"type": "ephemeral", "ttl": "5m"}"""))
    }

    @Test
    fun `should leave effort to the model and omit monologue by default`() {
        // given
        val inference = ClaudeInference(client = client, model = "claude-opus-5-5")

        // when
        val body = inference.body(cogitation())

        // then
        assert(body["max_tokens"].asLong() == 128_000L)
        assert(body["thinking"] == json("""{"type": "adaptive", "display": "omitted"}"""))
        assert(body["output_config"] == null)
    }

    @Test
    fun `should request progress updates with beta header`() {
        // given
        val inference = ClaudeInference(
            client = client,
            model = "claude-opus-5-5",
            visibility = MonologueVisibility.UPDATES
        )

        // when
        val request = inference.request(cogitation())

        // then
        assert(request._headers().values("anthropic-beta") == listOf("thinking-display-updates-2026-08-18"))
        assert(jsonMapper().valueToTree<JsonNode>(request._body())["thinking"]["display"].asText() == "updates")
    }

    @Test
    fun `should replay monologue only to the model which produced it, in the exact form kept in the store`() {
        // given
        val monologues = InMemoryMonologueStore()
        val inference = ClaudeInference(
            client = client,
            model = "anthropic.claude-opus-5-5",
            modelId = "claude-opus-5-5",
            monologues = monologues
        )
        monologues["other"] = MonologueTrace.ClaudeThinking(producedBy = "claude-opus-5", thinking = "other thought", signature = "other")
        monologues["thought"] = MonologueTrace.ClaudeThinking(producedBy = "claude-opus-5-5", thinking = "thought", signature = "sig")
        monologues["redacted"] = MonologueTrace.ClaudeRedacted(producedBy = "claude-opus-5-5", data = "redacted")
        val cogitation = cogitation(Enunciation(
            actant = Actant(type = Actant.Type.SELF, name = "claude"),
            initiated = initiated,
            phenomena = listOf(
                Monologue("other thought", id = "other"),
                Monologue("thought", id = "thought"),
                Monologue("", id = "redacted"),
                Monologue("thought of another cognizer", id = "unknown"),
                Phenomenon.Intent(script = "6 * 7", id = "toolu_1")
            )
        ))

        // when
        val body = inference.body(cogitation)

        // then
        assert(body["messages"][1] == json("""
            {"role": "assistant", "content": [
                {"type": "thinking", "thinking": "thought", "signature": "sig"},
                {"type": "redacted_thinking", "data": "redacted"},
                {"type": "tool_use", "id": "toolu_1", "name": "execute", "input": {"script": "6 * 7"}}
            ]}
        """))
    }

    @Test
    fun `should replay the monologue it perceived`() {
        // given
        val inference = ClaudeInference(client = client, model = "claude-opus-5-5")
        val message = jsonMapper().readValue("""
            {
              "id": "msg_1",
              "type": "message",
              "role": "assistant",
              "model": "claude-opus-5-5",
              "content": [
                {"type": "thinking", "thinking": "thought", "signature": "sig"},
                {"type": "text", "text": "42"}
              ],
              "stop_reason": "end_turn",
              "stop_sequence": null,
              "usage": {"input_tokens": 1, "output_tokens": 1}
            }
        """, Message::class.java)
        val phenomena = inference.phenomena(message)

        // when
        val body = inference.body(cogitation(
            Enunciation(
                actant = Actant.SELF,
                initiated = initiated,
                phenomena = phenomena,
                inference = Inference(model = "claude-opus-5-5", provider = "anthropic")
            ),
            Enunciation(
                actant = Actant(type = Actant.Type.HUMAN, name = "user"),
                initiated = initiated,
                phenomena = listOf(Phenomenon.Text("and twice that?"))
            )
        ))

        // then
        assert(body["messages"][1] == json("""
            {"role": "assistant", "content": [
                {"type": "thinking", "thinking": "thought", "signature": "sig"},
                {"type": "text", "text": "42"}
            ]}
        """))
        assert(body["messages"][2] == json("""
            {"role": "user", "content": "<annie:actant type=\"human\" name=\"user\" initiated=\"2026-10-22T10:15:30Z\"/>\nand twice that?"}
        """))
    }

    @Test
    fun `should replay text before tool call, and execution of intent as its result`() {
        // given
        val inference = ClaudeInference(client = client, model = "claude-opus-5-5")
        val cogitation = cogitation(
            Enunciation(
                actant = Actant.SELF,
                initiated = initiated,
                phenomena = listOf(
                    Phenomenon.Text("Computing it."),
                    Phenomenon.Intent(script = "6 * 7", id = "toolu_1")
                )
            ),
            Enunciation(
                actant = Actant(type = Actant.Type.COMPUTER, name = "user's computer"),
                initiated = initiated,
                respondsTo = "toolu_1",
                phenomena = listOf(Phenomenon.Text("42"), Phenomenon.Data(redPixelPng, "image/png"))
            )
        )

        // when
        val body = inference.body(cogitation)

        // then
        assert(body["messages"][1] == json("""
            {"role": "assistant", "content": [
                {"type": "text", "text": "Computing it."},
                {"type": "tool_use", "id": "toolu_1", "name": "execute", "input": {"script": "6 * 7"}}
            ]}
        """))
        assert(body["messages"][2] == json("""
            {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "toolu_1", "content": [
              {"type": "text", "text": "<annie:actant type=\"computer\" name=\"user's computer\" initiated=\"2026-10-22T10:15:30Z\"/>\n42\n\n<annie:data content-type=\"image/png\" size=\"70\"/>"},
              {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "$redPixelPngBase64"}}
            ]}]}
        """))
    }

    @Test
    fun `should perceive thinking and redacted thinking as monologues, text, and tool call as intent`() {
        // given
        val inference = ClaudeInference(
            client = client,
            model = "anthropic.claude-opus-5-5",
            modelId = "claude-opus-5-5"
        )
        val message = jsonMapper().readValue("""
            {
              "id": "msg_1",
              "type": "message",
              "role": "assistant",
              "model": "claude-opus-5-5",
              "content": [
                {"type": "thinking", "thinking": "", "signature": "sig"},
                {"type": "redacted_thinking", "data": "redacted"},
                {"type": "text", "text": "Computing it."},
                {"type": "tool_use", "id": "toolu_1", "name": "execute", "input": {"script": "6 * 7"}}
              ],
              "stop_reason": "end_turn",
              "stop_sequence": null,
              "usage": {"input_tokens": 1, "output_tokens": 1}
            }
        """, Message::class.java)

        // when
        val phenomena = inference.phenomena(message)

        // then
        val (thinking, redacted) = phenomena.filterIsInstance<Monologue>()
        val intent = phenomena.last() as Phenomenon.Intent
        assert(phenomena == listOf(
            Monologue("", id = thinking.id),
            Monologue("", id = redacted.id),
            Phenomenon.Text("Computing it."),
            Phenomenon.Intent(script = "6 * 7", id = intent.id)
        ))
        assert(thinking.id != redacted.id)
    }

    @Test
    fun `should perceive response without tool call as text`() {
        // given
        val inference = ClaudeInference(client = client, model = "claude-opus-5-5")

        // when
        val phenomena = inference.phenomena(message("""{"input_tokens": 1, "output_tokens": 1}"""))

        // then
        assert(phenomena == listOf(Phenomenon.Text("6 * 7")))
    }

    @Test
    fun `should perceive call of another tool as an impediment`() {
        // given
        val inference = ClaudeInference(client = client, model = "claude-opus-5-5")
        val message = jsonMapper().readValue("""
            {
              "id": "msg_1",
              "type": "message",
              "role": "assistant",
              "model": "claude-opus-5-5",
              "content": [{"type": "tool_use", "id": "toolu_1", "name": "bash", "input": {"command": "ls"}}],
              "stop_reason": "tool_use",
              "stop_sequence": null,
              "usage": {"input_tokens": 1, "output_tokens": 1}
            }
        """, Message::class.java)

        // when
        val phenomena = inference.phenomena(message)

        // then
        val impediment = phenomena.single() as Phenomenon.Impediment
        assert("bash" in impediment.reason && "execute" in impediment.reason)
    }

    @Test
    fun `should perceive only first intent of response thinking again after it`() {
        // given
        val inference = ClaudeInference(client = client, model = "claude-opus-5-5")
        val message = jsonMapper().readValue("""
            {
              "id": "msg_1",
              "type": "message",
              "role": "assistant",
              "model": "claude-opus-5-5",
              "content": [
                {"type": "thinking", "thinking": "first", "signature": "sig1"},
                {"type": "tool_use", "id": "toolu_1", "name": "execute", "input": {"script": "6 * 7"}},
                {"type": "thinking", "thinking": "as if the result was perceived", "signature": "sig2"},
                {"type": "text", "text": "42"}
              ],
              "stop_reason": "end_turn",
              "stop_sequence": null,
              "usage": {"input_tokens": 1, "output_tokens": 1}
            }
        """, Message::class.java)

        // when
        val phenomena = inference.phenomena(message)

        // then
        assert(phenomena.size == 2)
        assert((phenomena.first() as Monologue).text == "first")
        val intent = phenomena.last() as Phenomenon.Intent
        assert(intent.script == "6 * 7")
        // the id of the provider, foreign to the cogitation, is replaced by one of its own
        assert(intent.id != "toolu_1")
    }

    @Test
    fun `should count tokens of response by category`() {
        // given
        val inference = ClaudeInference(
            client = client,
            model = "anthropic.claude-opus-5-5",
            modelId = "claude-opus-5-5",
            provider = "bedrock"
        )
        val message = message("""
            {
              "input_tokens": 3,
              "cache_read_input_tokens": 1200,
              "cache_creation_input_tokens": 1500,
              "cache_creation": {"ephemeral_5m_input_tokens": 500, "ephemeral_1h_input_tokens": 1000},
              "output_tokens": 100,
              "output_tokens_details": {"thinking_tokens": 60}
            }
        """)

        // when
        val inferred = inference.inference(message)

        // then
        assert(inferred == Inference(
            model = "claude-opus-5-5",
            provider = "bedrock",
            tokens = mapOf(
                Tokens.INPUT to 3L,
                Tokens.CACHE_READ to 1200L,
                Tokens.CACHE_WRITE_5M to 500L,
                Tokens.CACHE_WRITE_1H to 1000L,
                Tokens.REASONING to 60L,
                Tokens.OUTPUT to 40L
            ),
            stop = Inference.Stop(Inference.Stop.Kind.COMPLETED, "end_turn")
        ))
        assert(inferred.total == 2803L)
    }

    @Test
    fun `should count cache writes of unreported lifetime and omit categories without tokens`() {
        // given
        val inference = ClaudeInference(client = client, model = "claude-opus-5-5")
        val message = message("""
            {"input_tokens": 3, "cache_read_input_tokens": 0, "cache_creation_input_tokens": 1500, "output_tokens": 100}
        """)

        // when
        val inferred = inference.inference(message)

        // then
        assert(inferred == Inference(
            model = "claude-opus-5-5",
            provider = "anthropic",
            tokens = mapOf(Tokens.INPUT to 3L, Tokens.CACHE_WRITE to 1500L, Tokens.OUTPUT to 100L),
            stop = Inference.Stop(Inference.Stop.Kind.COMPLETED, "end_turn")
        ))
    }

    @Test
    fun `should report stop reason of message by kind`() {
        // given
        val inference = ClaudeInference(client = client, model = "claude-opus-5-5")
        val usage = """{"input_tokens": 3, "output_tokens": 1}"""

        // when
        val stops = listOf("end_turn", "tool_use", "max_tokens", "model_context_window_exceeded", "refusal", "pause_turn")
            .map { inference.inference(message(usage, stopReason = it)).stop }

        // then
        assert(stops == listOf(
            Inference.Stop(Inference.Stop.Kind.COMPLETED, "end_turn"),
            // calling the tool of intents completes an inference expressing one
            Inference.Stop(Inference.Stop.Kind.COMPLETED, "tool_use"),
            Inference.Stop(Inference.Stop.Kind.TRUNCATED, "max_tokens"),
            Inference.Stop(Inference.Stop.Kind.TRUNCATED, "model_context_window_exceeded"),
            Inference.Stop(Inference.Stop.Kind.REFUSED, "refusal"),
            Inference.Stop(Inference.Stop.Kind.OTHER, "pause_turn")
        ))
    }

    @Test
    fun `should report category of refusal`() {
        // given
        val inference = ClaudeInference(client = client, model = "claude-fable-5-2")
        val message = message(
            """{"input_tokens": 3, "output_tokens": 1}""",
            stopReason = "refusal",
            stopDetails = """{"type": "refusal", "category": "cyber", "explanation": null}"""
        )

        // when
        val stop = inference.inference(message).stop

        // then
        assert(stop == Inference.Stop(Inference.Stop.Kind.REFUSED, "refusal", category = "cyber"))
    }

    @Test
    fun `should infer after refused inference by fallback model, replaying no monologue of the refused model`() {
        // given
        val monologues = InMemoryMonologueStore()
        val inference = ClaudeInference(
            client = client,
            model = "claude-fable-5-2",
            fallback = "claude-opus-5-5",
            monologues = monologues
        )
        monologues["thought"] = MonologueTrace.ClaudeThinking(producedBy = "claude-fable-5-2", thinking = "thought", signature = "sig")
        val cogitation = cogitation(
            Enunciation(
                actant = Actant.SELF,
                initiated = initiated,
                phenomena = listOf(Monologue("thought", id = "thought"), Phenomenon.Intent(script = "6 * 7", id = "toolu_1")),
                inference = Inference(model = "claude-fable-5-2", provider = "anthropic")
            ),
            Enunciation(
                actant = Actant(type = Actant.Type.COMPUTER, name = "computer"),
                initiated = initiated,
                respondsTo = "toolu_1",
                phenomena = listOf(Phenomenon.Text("42"))
            ),
            // what the refused inference expressed is discarded by the harness
            Enunciation(
                actant = Actant.SELF,
                initiated = initiated,
                phenomena = emptyList(),
                inference = Inference(
                    model = "claude-fable-5-2",
                    provider = "anthropic",
                    stop = Inference.Stop(Inference.Stop.Kind.REFUSED, "refusal")
                )
            )
        )

        // when
        val body = inference.body(cogitation)
        val inferred = inference.inference(message("""{"input_tokens": 3, "output_tokens": 1}"""), cogitation)

        // then
        assert(body["model"].asText() == "claude-opus-5-5")
        assert(body["messages"][1] == json("""
            {"role": "assistant", "content": [
                {"type": "tool_use", "id": "toolu_1", "name": "execute", "input": {"script": "6 * 7"}}
            ]}
        """))
        assert(body["messages"].size() == 3)
        assert(inferred.model == "claude-opus-5-5")
    }

    @Test
    fun `should infer by its own model when no inference was refused before`() {
        // given
        val inference = ClaudeInference(client = client, model = "claude-fable-5-2", fallback = "claude-opus-5-5")
        val cogitation = cogitation()

        // when
        val body = inference.body(cogitation)
        val inferred = inference.inference(message("""{"input_tokens": 3, "output_tokens": 1}"""), cogitation)

        // then
        assert(body["model"].asText() == "claude-fable-5-2")
        assert(inferred.model == "claude-fable-5-2")
    }

    @Test
    fun `should perceive edits of tool call as edits of intent, whatever the code they carry`() {
        // given
        val inference = ClaudeInference(client = client, model = "claude-opus-5-5")
        val message = jsonMapper().readValue("""
            {
              "id": "msg_1",
              "type": "message",
              "role": "assistant",
              "model": "claude-opus-5-5",
              "content": [
                {"type": "tool_use", "id": "toolu_1", "name": "execute", "input": {
                  "script": "agency.bash(\"pytest\")",
                  "edits": [
                    {"path": "/app/f.py", "old": "    return 1\n", "new": "    \"\"\"Costs ${'$'}1.\"\"\"\n    return 2\n"},
                    {"path": "/app/g.py", "new": "x = 1\n"},
                    {"path": "/app/h.py", "old": "a", "new": "b", "all": true}
                  ]
                }}
              ],
              "stop_reason": "tool_use",
              "stop_sequence": null,
              "usage": {"input_tokens": 1, "output_tokens": 1}
            }
        """, Message::class.java)

        // when
        val intent = inference.phenomena(message).single() as Phenomenon.Intent

        // then
        assert(intent.script == "agency.bash(\"pytest\")")
        assert(intent.edits == listOf(
            Edit(path = "/app/f.py", old = "    return 1\n", new = "    \"\"\"Costs ${'$'}1.\"\"\"\n    return 2\n"),
            Edit(path = "/app/g.py", new = "x = 1\n"),
            Edit(path = "/app/h.py", old = "a", new = "b", all = true)
        ))
    }

    @Test
    fun `should replay edits of intent in its tool call`() {
        // given
        val inference = ClaudeInference(client = client, model = "claude-opus-5-5")
        val cogitation = cogitation(
            Enunciation(
                actant = Actant.SELF,
                initiated = initiated,
                phenomena = listOf(Phenomenon.Intent(
                    edits = listOf(Edit(path = "/app/g.py", new = "x = 1\n"), Edit(path = "/app/h.py", old = "a", new = "b", all = true)),
                    script = "",
                    id = "toolu_1"
                ))
            ),
            Enunciation(
                actant = Actant(type = Actant.Type.COMPUTER, name = "user's computer"),
                initiated = initiated,
                respondsTo = "toolu_1",
                phenomena = listOf(Phenomenon.Text("Wrote /app/g.py, 1 lines"))
            )
        )

        // when
        val body = inference.body(cogitation)

        // then
        assert(body["messages"][1] == json("""
            {"role": "assistant", "content": [
                {"type": "tool_use", "id": "toolu_1", "name": "execute", "input": {"script": "", "edits": [
                  {"path": "/app/g.py", "new": "x = 1\n"},
                  {"path": "/app/h.py", "old": "a", "new": "b", "all": true}
                ]}}
            ]}
        """))
    }

}
