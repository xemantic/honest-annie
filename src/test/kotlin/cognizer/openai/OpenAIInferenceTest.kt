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

package com.xemantic.ai.annie.cognizer.openai

import com.fasterxml.jackson.databind.JsonNode
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.jsonMapper
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.responses.Response
import com.xemantic.ai.annie.Actant
import com.xemantic.ai.annie.Cogitation
import com.xemantic.ai.annie.Enunciation
import com.xemantic.ai.annie.Inference
import com.xemantic.ai.annie.Inference.Tokens
import com.xemantic.ai.annie.Phenomenon
import com.xemantic.ai.annie.Phenomenon.Monologue
import com.xemantic.ai.annie.Phenomenon.Monologue.Continuation
import com.xemantic.ai.annie.host.isToolCall
import com.xemantic.kotlin.test.assert
import kotlin.test.Test
import kotlin.time.Instant

class OpenAIInferenceTest {

    // never called, only the requests are built
    private val client = OpenAIOkHttpClient.builder().apiKey("test").build()

    private val initiated = Instant.parse("2026-10-22T10:15:30Z")

    private val userTurn = "<annie:cogitation depth=\"0\"/>\n<annie:actant type=\"human\" name=\"user\" initiated=\"2026-10-22T10:15:30Z\"/>\ntest"

    private fun json(text: String): JsonNode = jsonMapper().readTree(text)

    /** The JSON of the request [body] as sent, its numbers read back as those of the expected JSON. */
    private fun json(body: Any): JsonNode = json(jsonMapper().writeValueAsString(body))

    private fun cogitation(vararg phenomena: Phenomenon) = Cogitation(
        constitution = listOf("constitution"),
        harness = "harness",
        enunciations = listOf(
            Enunciation(
                actant = Actant(type = Actant.Type.HUMAN, name = "user"),
                initiated = initiated,
                phenomena = listOf(Phenomenon.Text("test"))
            ),
            Enunciation(
                actant = Actant(type = Actant.Type.SELF, name = "model"),
                initiated = initiated,
                phenomena = phenomena.toList()
            )
        )
    )

    @Test
    fun `should request chat completion with reasoning effort and no tool choice without replaying reasoning`() {
        // given
        val inference = OpenAIInference(
            client = client,
            model = "glm-5.3-flash",
            maxTokens = 8000,
            effort = Monologue.Effort.LOW
        )

        // when
        val body = json(inference.chatRequest(cogitation(
            Monologue(summary = listOf("thought"), role = Monologue.Role.REASONING),
            Phenomenon.Intent("6 * 7")
        ))._body())

        // then
        assert(body == json("""
            {
              "messages": [
                {"role": "system", "content": "constitution"},
                {"role": "user", "content": ${jsonMapper().writeValueAsString(userTurn)}},
                {"role": "assistant", "content": "6 * 7"}
              ],
              "model": "glm-5.3-flash",
              "max_completion_tokens": 8000,
              "reasoning_effort": "low",
              "tool_choice": "none"
            }
        """))
    }

    @Test
    fun `should request chat completion without system message when constitution is blank`() {
        // given
        val inference = OpenAIInference(client = client, model = "glm-5.3-flash")

        // when
        val body = json(inference.chatRequest(cogitation().copy(constitution = listOf("")))._body())

        // then
        assert(body["messages"] == json("""
            [{"role": "user", "content": ${jsonMapper().writeValueAsString(userTurn)}}]
        """))
    }

    @Test
    fun `should request response without instructions when constitution is blank`() {
        // given
        val inference = OpenAIInference(client = client, model = "gpt-6", responses = true)

        // when
        val body = json(inference.responseRequest(cogitation().copy(constitution = listOf("")))._body())

        // then
        assert(body["instructions"] == null)
    }

    @Test
    fun `should perceive reasoning content of chat completion`() {
        // given
        val inference = OpenAIInference(client = client, model = "glm-5.3-flash")
        val completion = jsonMapper().readValue("""
            {
              "id": "chatcmpl-1",
              "object": "chat.completion",
              "created": 0,
              "model": "glm-5.3-flash",
              "choices": [{
                "index": 0,
                "finish_reason": "stop",
                "logprobs": null,
                "message": {"role": "assistant", "content": "6 * 7", "refusal": null, "reasoning_content": "thought"}
              }]
            }
        """, ChatCompletion::class.java)

        // when
        val phenomena = inference.phenomena(completion)

        // then
        assert(phenomena.first() == Monologue(summary = listOf("thought"), role = Monologue.Role.REASONING))
        assert((phenomena.last() as Phenomenon.Intent).script == "6 * 7")
        assert(phenomena.size == 2)
    }

    @Test
    fun `should perceive tool calls of chat completion without content as intent of tool call`() {
        // given
        val inference = OpenAIInference(client = client, model = "deepseek-v4_1-flash")
        val completion = jsonMapper().readValue("""
            {
              "id": "chatcmpl-1",
              "object": "chat.completion",
              "created": 0,
              "model": "deepseek-v4_1-flash",
              "choices": [{
                "index": 0,
                "finish_reason": "stop",
                "logprobs": null,
                "message": {
                  "role": "assistant", "content": null, "refusal": null, "reasoning_content": "Let's run.",
                  "tool_calls": [{
                    "id": "call-1",
                    "type": "function",
                    "function": {"name": "kotlin", "arguments": "{\"script\": \"6 * 7\"}"}
                  }]
                }
              }]
            }
        """, ChatCompletion::class.java)

        // when
        val phenomena = inference.phenomena(completion)

        // then
        assert(phenomena.first() == Monologue(summary = listOf("Let's run."), role = Monologue.Role.REASONING))
        val intent = phenomena.last() as Phenomenon.Intent
        assert(intent.script == "<invoke name=\"kotlin\">\n{\"script\": \"6 * 7\"}\n</invoke>")
        assert(intent.isToolCall)
        assert(phenomena.size == 2)
    }

    @Test
    fun `should perceive tool calls of chat completion after its content in the same intent`() {
        // given
        val inference = OpenAIInference(client = client, model = "deepseek-v4_1-flash")
        val completion = jsonMapper().readValue("""
            {
              "id": "chatcmpl-1",
              "object": "chat.completion",
              "created": 0,
              "model": "deepseek-v4_1-flash",
              "choices": [{
                "index": 0,
                "finish_reason": "stop",
                "logprobs": null,
                "message": {
                  "role": "assistant", "content": "6 * 7", "refusal": null,
                  "tool_calls": [{
                    "id": "call-1",
                    "type": "function",
                    "function": {"name": "bash", "arguments": "{}"}
                  }]
                }
              }]
            }
        """, ChatCompletion::class.java)

        // when
        val phenomena = inference.phenomena(completion)

        // then
        assert((phenomena.single() as Phenomenon.Intent).script == "6 * 7\n<invoke name=\"bash\">\n{}\n</invoke>")
    }

    @Test
    fun `should request encrypted reasoning from Responses API without storing it`() {
        // given
        val inference = OpenAIInference(
            client = client,
            model = "gpt-6",
            responses = true,
            effort = Monologue.Effort.HIGH,
            visibility = Monologue.Visibility.SUMMARY
        )

        // when
        val body = json(inference.responseRequest(cogitation(
            Monologue(
                summary = listOf("other thought"),
                continuation = Continuation.OpenAIReasoning(producedBy = "gpt-5", itemId = "rs_0", encryptedContent = "other")
            ),
            Monologue(
                summary = listOf("thought"),
                continuation = Continuation.OpenAIReasoning(producedBy = "gpt-6", itemId = "rs_1", encryptedContent = "secret")
            ),
            Phenomenon.Intent("6 * 7")
        ))._body())

        // then
        assert(body["store"].asBoolean() == false)
        assert(body["include"] == json("""["reasoning.encrypted_content"]"""))
        assert(body["reasoning"] == json("""{"effort": "high", "summary": "auto"}"""))
        assert(body["instructions"].asText() == "constitution")
        assert(body["input"] == json("""
            [
              {"type": "message", "role": "user", "content": ${jsonMapper().writeValueAsString(userTurn)}},
              {"type": "reasoning", "id": "rs_1", "summary": [{"type": "summary_text", "text": "thought"}], "encrypted_content": "secret"},
              {"type": "message", "role": "assistant", "content": "6 * 7"}
            ]
        """))
    }

    @Test
    fun `should perceive encrypted reasoning and output text of response`() {
        // given
        val inference = OpenAIInference(client = client, model = "gpt-6", responses = true)
        val response = jsonMapper().readValue("""
            {
              "id": "resp_1",
              "object": "response",
              "created_at": 0,
              "model": "gpt-6",
              "output": [
                {"type": "reasoning", "id": "rs_1", "summary": [{"type": "summary_text", "text": "thought"}], "encrypted_content": "secret"},
                {"type": "message", "id": "msg_1", "role": "assistant", "status": "completed", "content": [
                  {"type": "output_text", "text": "6 * 7", "annotations": []}
                ]}
              ]
            }
        """, Response::class.java)

        // when
        val phenomena = inference.phenomena(response)

        // then
        assert(phenomena.first() == Monologue(
            summary = listOf("thought"),
            role = Monologue.Role.REASONING,
            continuation = Continuation.OpenAIReasoning(producedBy = "gpt-6", itemId = "rs_1", encryptedContent = "secret")
        ))
        assert((phenomena.last() as Phenomenon.Intent).script == "6 * 7")
        assert(phenomena.size == 2)
    }

    @Test
    fun `should count tokens of chat completion by category`() {
        // given
        val inference = OpenAIInference(
            client = client,
            model = "glm-5.3-flash",
            modelId = "zai-org/glm-5.3-flash",
            provider = "llm.example.com"
        )
        val completion = jsonMapper().readValue("""
            {
              "id": "chatcmpl-1",
              "object": "chat.completion",
              "created": 0,
              "model": "glm-5.3-flash",
              "choices": [{
                "index": 0,
                "finish_reason": "stop",
                "logprobs": null,
                "message": {"role": "assistant", "content": "6 * 7", "refusal": null}
              }],
              "usage": {
                "prompt_tokens": 1000,
                "completion_tokens": 50,
                "total_tokens": 1050,
                "prompt_tokens_details": {"cached_tokens": 800},
                "completion_tokens_details": {"reasoning_tokens": 30}
              }
            }
        """, ChatCompletion::class.java)

        // when
        val inferred = inference.inference(completion)

        // then
        assert(inferred == Inference(
            model = "zai-org/glm-5.3-flash",
            provider = "llm.example.com",
            tokens = mapOf(
                Tokens.INPUT to 200L,
                Tokens.CACHE_READ to 800L,
                Tokens.REASONING to 30L,
                Tokens.OUTPUT to 20L
            ),
            stop = Inference.Stop(Inference.Stop.Kind.COMPLETED, "stop")
        ))
        assert(inferred.total == 1050L)
    }

    @Test
    fun `should count no tokens of chat completion without usage`() {
        // given
        val inference = OpenAIInference(client = client, model = "glm-5.3-flash")
        val completion = jsonMapper().readValue("""
            {
              "id": "chatcmpl-1",
              "object": "chat.completion",
              "created": 0,
              "model": "glm-5.3-flash",
              "choices": [{
                "index": 0,
                "finish_reason": "stop",
                "logprobs": null,
                "message": {"role": "assistant", "content": "6 * 7", "refusal": null}
              }]
            }
        """, ChatCompletion::class.java)

        // when
        val inferred = inference.inference(completion)

        // then
        assert(inferred == Inference(
            model = "glm-5.3-flash",
            provider = "openai",
            stop = Inference.Stop(Inference.Stop.Kind.COMPLETED, "stop")
        ))
    }

    @Test
    fun `should report finish reason of chat completion by kind`() {
        // given
        val inference = OpenAIInference(client = client, model = "glm-5.3-flash")

        // when
        val stops = listOf("stop", "length", "content_filter", "abort").map { reason ->
            inference.inference(jsonMapper().readValue("""
                {
                  "id": "chatcmpl-1",
                  "object": "chat.completion",
                  "created": 0,
                  "model": "glm-5.3-flash",
                  "choices": [{
                    "index": 0,
                    "finish_reason": "$reason",
                    "logprobs": null,
                    "message": {"role": "assistant", "content": "6 *", "refusal": null}
                  }]
                }
            """, ChatCompletion::class.java)).stop
        }

        // then
        assert(stops == listOf(
            Inference.Stop(Inference.Stop.Kind.COMPLETED, "stop"),
            Inference.Stop(Inference.Stop.Kind.TRUNCATED, "length"),
            Inference.Stop(Inference.Stop.Kind.REFUSED, "content_filter"),
            Inference.Stop(Inference.Stop.Kind.OTHER, "abort")
        ))
    }

    @Test
    fun `should report status of response by kind`() {
        // given
        val inference = OpenAIInference(client = client, model = "gpt-6", responses = true)

        // when
        val stops = listOf(
            "\"completed\"",
            "\"incomplete\", \"incomplete_details\": {\"reason\": \"max_output_tokens\"}",
            "\"incomplete\", \"incomplete_details\": {\"reason\": \"content_filter\"}",
            "\"failed\""
        ).map { status ->
            inference.inference(jsonMapper().readValue("""
                {
                  "id": "resp_1",
                  "object": "response",
                  "created_at": 0,
                  "model": "gpt-6",
                  "output": [],
                  "status": $status
                }
            """, Response::class.java)).stop
        }

        // then
        assert(stops == listOf(
            Inference.Stop(Inference.Stop.Kind.COMPLETED, "completed"),
            Inference.Stop(Inference.Stop.Kind.TRUNCATED, "max_output_tokens"),
            Inference.Stop(Inference.Stop.Kind.REFUSED, "content_filter"),
            Inference.Stop(Inference.Stop.Kind.OTHER, "failed")
        ))
    }

    @Test
    fun `should count tokens of response by category`() {
        // given
        val inference = OpenAIInference(client = client, model = "gpt-6", responses = true)
        val response = jsonMapper().readValue("""
            {
              "id": "resp_1",
              "object": "response",
              "created_at": 0,
              "model": "gpt-6",
              "output": [],
              "usage": {
                "input_tokens": 1000,
                "input_tokens_details": {"cached_tokens": 600, "cache_write_tokens": 300},
                "output_tokens": 50,
                "output_tokens_details": {"reasoning_tokens": 30},
                "total_tokens": 1050
              }
            }
        """, Response::class.java)

        // when
        val inferred = inference.inference(response)

        // then
        assert(inferred == Inference(
            model = "gpt-6",
            provider = "openai",
            tokens = mapOf(
                Tokens.INPUT to 100L,
                Tokens.CACHE_READ to 600L,
                Tokens.CACHE_WRITE to 300L,
                Tokens.REASONING to 30L,
                Tokens.OUTPUT to 20L
            )
        ))
    }

}
