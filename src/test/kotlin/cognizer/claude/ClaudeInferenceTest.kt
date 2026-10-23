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

package com.xemantic.ai.annie.cognizer.claude

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Message
import com.fasterxml.jackson.databind.JsonNode
import com.xemantic.ai.annie.Actant
import com.xemantic.ai.annie.Cogitation
import com.xemantic.ai.annie.Enunciation
import com.xemantic.ai.annie.Inference
import com.xemantic.ai.annie.Inference.Tokens
import com.xemantic.ai.annie.Phenomenon
import com.xemantic.ai.annie.Phenomenon.Monologue
import com.xemantic.ai.annie.Phenomenon.Monologue.Continuation
import com.xemantic.kotlin.test.assert
import kotlin.test.Test
import kotlin.time.Instant

class ClaudeInferenceTest {

    // never called, only the requests are built
    private val client = AnthropicOkHttpClient.builder().apiKey("test").build()

    private val initiated = Instant.parse("2026-10-22T10:15:30Z")

    private fun json(text: String): JsonNode = jsonMapper().readTree(text)

    private fun cogitation(vararg enunciations: Enunciation) = Cogitation(
        constitution = listOf("constitution"),
        harness = "harness",
        enunciations = listOf(Enunciation(
            actant = Actant(type = Actant.Type.HUMAN, name = "user"),
            initiated = initiated,
            phenomena = listOf(Phenomenon.Text("test"))
        )) + enunciations
    )

    private fun message(usage: String): Message = jsonMapper().readValue("""
        {
          "id": "msg_1",
          "type": "message",
          "role": "assistant",
          "model": "claude-opus-5-5",
          "content": [{"type": "text", "text": "6 * 7"}],
          "stop_reason": "end_turn",
          "stop_sequence": null,
          "usage": $usage
        }
    """, Message::class.java)

    private fun ClaudeInference.body(cogitation: Cogitation): JsonNode =
        json(jsonMapper().writeValueAsString(request(cogitation)._body()))

    @Test
    fun `should request adaptive thinking with effort and cached constitution`() {
        // given
        val inference = ClaudeInference(
            client = client,
            model = "claude-opus-5-5",
            maxTokens = 32000,
            effort = Monologue.Effort.MAX,
            visibility = Monologue.Visibility.SUMMARY
        )

        // when
        val body = inference.body(cogitation())

        // then
        assert(body["model"].asText() == "claude-opus-5-5")
        assert(body["max_tokens"].asLong() == 32000L)
        assert(body["thinking"] == json("""{"type": "adaptive", "display": "summarized"}"""))
        assert(body["output_config"] == json("""{"effort": "max"}"""))
        assert(body["system"] == json("""
            [{"type": "text", "text": "constitution", "cache_control": {"type": "ephemeral", "ttl": "1h"}}]
        """))
        assert(body["cache_control"] == json("""{"type": "ephemeral", "ttl": "5m"}"""))
        assert(body["messages"] == json("""
            [{"role": "user", "content": "<golem:actant type=\"human\" name=\"user\" initiated=\"2026-10-22T10:15:30Z\"/>\ntest"}]
        """))
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
            visibility = Monologue.Visibility.UPDATES
        )

        // when
        val request = inference.request(cogitation())

        // then
        assert(request._headers().values("anthropic-beta") == listOf("thinking-display-updates-2026-08-18"))
        assert(jsonMapper().valueToTree<JsonNode>(request._body())["thinking"]["display"].asText() == "updates")
    }

    @Test
    fun `should replay monologue only to the model which produced it`() {
        // given
        val inference = ClaudeInference(
            client = client,
            model = "anthropic.claude-opus-5-5",
            modelId = "claude-opus-5-5"
        )
        val cogitation = cogitation(Enunciation(
            actant = Actant(type = Actant.Type.SELF, name = "claude"),
            initiated = initiated,
            phenomena = listOf(
                Monologue(
                    summary = listOf("other thought"),
                    continuation = Continuation.ClaudeSignature(producedBy = "claude-opus-5", signature = "other")
                ),
                Monologue(
                    summary = listOf("thought"),
                    continuation = Continuation.ClaudeSignature(producedBy = "claude-opus-5-5", signature = "sig")
                ),
                Monologue(continuation = Continuation.ClaudeRedacted(producedBy = "claude-opus-5-5", data = "redacted")),
                Phenomenon.Intent("6 * 7")
            )
        ))

        // when
        val body = inference.body(cogitation)

        // then
        assert(body["messages"][1] == json("""
            {"role": "assistant", "content": [
                {"type": "thinking", "thinking": "thought", "signature": "sig"},
                {"type": "redacted_thinking", "data": "redacted"},
                {"type": "text", "text": "6 * 7"}
            ]}
        """))
    }

    @Test
    fun `should perceive thinking redacted thinking and text of response`() {
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
                {"type": "text", "text": "6 *"},
                {"type": "text", "text": "7"}
              ],
              "stop_reason": "end_turn",
              "stop_sequence": null,
              "usage": {"input_tokens": 1, "output_tokens": 1}
            }
        """, Message::class.java)

        // when
        val phenomena = inference.phenomena(message)

        // then
        assert(phenomena.dropLast(1) == listOf(
            Monologue(
                role = Monologue.Role.REASONING,
                continuation = Continuation.ClaudeSignature(producedBy = "claude-opus-5-5", signature = "sig")
            ),
            Monologue(
                role = Monologue.Role.REASONING,
                continuation = Continuation.ClaudeRedacted(producedBy = "claude-opus-5-5", data = "redacted")
            )
        ))
        assert((phenomena.last() as Phenomenon.Intent).script == "6 *\n7")
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
            )
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
            tokens = mapOf(Tokens.INPUT to 3L, Tokens.CACHE_WRITE to 1500L, Tokens.OUTPUT to 100L)
        ))
    }

}
