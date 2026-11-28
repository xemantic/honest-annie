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

package com.xemantic.annie.harness.cognizer.openai

import com.xemantic.annie.harness.content
import com.xemantic.annie.harness.data
import com.xemantic.annie.harness.monologue
import com.xemantic.annie.harness.testContents
import com.xemantic.annie.harness.text
import com.fasterxml.jackson.databind.JsonNode
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.jsonMapper
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.responses.Response
import com.xemantic.annie.Actant
import com.xemantic.annie.Cogitation
import com.xemantic.annie.Enunciation
import com.xemantic.annie.Inference
import com.xemantic.annie.Edit
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.Phenomenon.Monologue
import com.xemantic.annie.harness.cognizer.Effort
import com.xemantic.annie.harness.cognizer.InMemoryMonologueStore
import com.xemantic.annie.harness.cognizer.MonologueTrace
import com.xemantic.annie.harness.cognizer.MonologueVisibility
import com.xemantic.annie.harness.cognizer.IntentTool
import com.xemantic.annie.harness.cognizer.minimalPdf
import com.xemantic.annie.harness.cognizer.redPixelPng
import com.xemantic.annie.harness.cognizer.redPixelPngBase64
import com.xemantic.kotlin.test.assert
import kotlin.io.encoding.Base64
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

    /** The arguments of the call of the tool of intents with the [script], as sent. */
    private fun arguments(script: String): String = jsonMapper().writeValueAsString(mapOf(IntentTool.PARAMETER to script))

    private val tool = """
        {"name": "execute", "description": ${jsonMapper().writeValueAsString(IntentTool.DESCRIPTION)},
         "parameters": {"type": "object", "properties": ${jsonMapper().writeValueAsString(IntentTool.properties)}}}
    """

    /** A cogitation whose intent, expressed after a text, was executed, responded with text and an image. */
    private val executed = Cogitation(
        constitution = "constitution",
        enunciations = listOf(
            Enunciation(
                actant = Actant(type = Actant.Type.HUMAN, name = "user"),
                initiated = initiated,
                phenomena = listOf(text("test"))
            ),
            Enunciation(
                actant = Actant.SELF,
                initiated = initiated,
                phenomena = listOf(
                    text("Computing it."),
                    Phenomenon.Intent(script = "6 * 7", id = "call_1")
                )
            ),
            Enunciation(
                actant = Actant(type = Actant.Type.COMPUTER, name = "user's computer"),
                initiated = initiated,
                respondsTo = "call_1",
                phenomena = listOf(text("42"), data(redPixelPng, "image/png"))
            )
        )
    )

    private val executedResult = "<annie:actant type=\"computer\" name=\"user's computer\" initiated=\"2026-10-22T10:15:30Z\"/>\n" +
            "42\n\n<annie:data content-type=\"image/png\" size=\"70\"/>"

    private fun cogitation(vararg phenomena: Phenomenon) = Cogitation(
        constitution = "constitution",
        enunciations = listOf(
            Enunciation(
                actant = Actant(type = Actant.Type.HUMAN, name = "user"),
                initiated = initiated,
                phenomena = listOf(text("test"))
            ),
            Enunciation(
                actant = Actant(type = Actant.Type.SELF, name = "model"),
                initiated = initiated,
                phenomena = phenomena.toList()
            )
        )
    )

    @Test
    fun `should request chat completion with reasoning effort and the tool executing intents, never forced, without replaying reasoning`() {
        // given
        val inference = OpenAIInference(
            contents = testContents,
            client = client,
            model = "glm-5.3-flash",
            maxTokens = 8000,
            effort = Effort.LOW
        )

        // when
        val body = json(inference.chatRequest(cogitation(
            monologue("thought"),
            Phenomenon.Intent(script = "6 * 7", id = "call_1")
        ))._body())

        // then
        assert(body == json("""
            {
              "messages": [
                {"role": "system", "content": "constitution"},
                {"role": "user", "content": ${jsonMapper().writeValueAsString(userTurn)}},
                {"role": "assistant", "tool_calls": [{"id": "call_1", "type": "function",
                  "function": {"name": "execute", "arguments": ${jsonMapper().writeValueAsString(arguments("6 * 7"))}}}]}
              ],
              "model": "glm-5.3-flash",
              "max_completion_tokens": 8000,
              "reasoning_effort": "low",
              "tools": [{"type": "function", "function": $tool}],
              "tool_choice": "auto",
              "parallel_tool_calls": false
            }
        """))
    }

    @Test
    fun `should replay text as content of tool call, and execution of intent as its result in chat completion`() {
        // given
        val inference = OpenAIInference(contents = testContents, client = client, model = "deepseek-v4_1-flash")

        // when
        val body = json(inference.chatRequest(executed)._body())

        // then
        assert(body["messages"][2]["content"].asText() == "Computing it.")
        assert(body["messages"][3] == json("""
            {"role": "tool", "tool_call_id": "call_1", "content": ${jsonMapper().writeValueAsString(executedResult)}}
        """))
        assert(body["messages"][4] == json("""
            {"role": "user", "content": [{"type": "image_url", "image_url": {"url": "data:image/png;base64,$redPixelPngBase64"}}]}
        """))
        assert(body["messages"].size() == 5)
    }

    @Test
    fun `should request chat completion without system message when constitution is blank`() {
        // given
        val inference = OpenAIInference(contents = testContents, client = client, model = "glm-5.3-flash")

        // when
        val body = json(inference.chatRequest(cogitation().copy(constitution = ""))._body())

        // then
        assert(body["messages"] == json("""
            [{"role": "user", "content": ${jsonMapper().writeValueAsString(userTurn)}}]
        """))
    }

    @Test
    fun `should request response without instructions when constitution is blank`() {
        // given
        val inference = OpenAIInference(contents = testContents, client = client, model = "gpt-6", responses = true)

        // when
        val body = json(inference.responseRequest(cogitation().copy(constitution = ""))._body())

        // then
        assert(body["instructions"] == null)
    }

    @Test
    fun `should perceive reasoning content of chat completion`() {
        // given
        val inference = OpenAIInference(contents = testContents, client = client, model = "glm-5.3-flash")
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
        val monologue = phenomena.first() as Monologue
        assert(phenomena == listOf(monologue("thought", id = monologue.id), text("6 * 7")))
    }

    @Test
    fun `should perceive call of the tool of intents in chat completion as intent`() {
        // given
        val inference = OpenAIInference(contents = testContents, client = client, model = "deepseek-v4_1-flash")
        val completion = jsonMapper().readValue("""
            {
              "id": "chatcmpl-1",
              "object": "chat.completion",
              "created": 0,
              "model": "deepseek-v4_1-flash",
              "choices": [{
                "index": 0,
                "finish_reason": "tool_calls",
                "logprobs": null,
                "message": {
                  "role": "assistant", "content": "Computing it.", "refusal": null,
                  "tool_calls": [
                    {"id": "call-1", "type": "function", "function": {"name": "execute", "arguments": "{\"script\": \"6 * 7\"}"}},
                    {"id": "call-2", "type": "function", "function": {"name": "execute", "arguments": "{\"script\": \"42\"}"}}
                  ]
                }
              }]
            }
        """, ChatCompletion::class.java)

        // when
        val phenomena = inference.phenomena(completion)

        // then
        assert(phenomena.size == 2)
        assert(phenomena.first() == text("Computing it."))
        val intent = phenomena.last() as Phenomenon.Intent
        assert(intent.script == "6 * 7")
        // the id of the provider, foreign to the cogitation, is replaced by one of its own
        assert(intent.id != "call-1")
    }

    @Test
    fun `should perceive call of another tool in chat completion as an impediment`() {
        // given
        val inference = OpenAIInference(contents = testContents, client = client, model = "deepseek-v4_1-flash")
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
        assert(phenomena.first().content == "Let's run.")
        val impediment = phenomena.last() as Phenomenon.Impediment
        assert("kotlin" in impediment.reason && "execute" in impediment.reason)
    }

    @Test
    fun `should perceive the only text argument of a call of the tool of intents as its script, whatever its name`() {
        // given
        val inference = OpenAIInference(contents = testContents, client = client, model = "deepseek-v4_1-flash")
        val completion = jsonMapper().readValue("""
            {
              "id": "chatcmpl-1",
              "object": "chat.completion",
              "created": 0,
              "model": "deepseek-v4_1-flash",
              "choices": [{
                "index": 0,
                "finish_reason": "tool_calls",
                "logprobs": null,
                "message": {
                  "role": "assistant", "content": "Found it.", "refusal": null,
                  "tool_calls": [{
                    "id": "call-1",
                    "type": "function",
                    "function": {"name": "execute", "arguments": "{\"code\": \"6 * 7\"}"}
                  }]
                }
              }]
            }
        """, ChatCompletion::class.java)

        // when
        val phenomena = inference.phenomena(completion)

        // then
        assert(phenomena.first() == text("Found it."))
        assert((phenomena.last() as Phenomenon.Intent).script == "6 * 7")
    }

    @Test
    fun `should perceive call of tool of intents without script in chat completion as an impediment`() {
        // given
        val inference = OpenAIInference(contents = testContents, client = client, model = "deepseek-v4_1-flash")
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
                    "function": {"name": "execute", "arguments": "{\"script\": \"6 *"}
                  }]
                }
              }]
            }
        """, ChatCompletion::class.java)

        // when
        val phenomena = inference.phenomena(completion)

        // then
        assert(phenomena.none { it is Phenomenon.Intent })
        val impediment = phenomena.last() as Phenomenon.Impediment
        assert("{\"script\": \"6 *" in impediment.reason)
    }

    @Test
    fun `should request chat completion with image perceived after its markup`() {
        // given
        val inference = OpenAIInference(contents = testContents, client = client, model = "deepseek-v4_1-flash")

        // when
        val body = json(inference.chatRequest(perceivingImage)._body())

        // then
        assert(body["messages"][3] == json("""
            {"role": "tool", "tool_call_id": "call_1", "content": ${jsonMapper().writeValueAsString(imageMarkup)}}
        """))
        assert(body["messages"][4] == json("""
            {"role": "user", "content": [{"type": "image_url", "image_url": {"url": "data:image/png;base64,$redPixelPngBase64"}}]}
        """))
    }

    @Test
    fun `should request response with image perceived after its markup`() {
        // given
        val inference = OpenAIInference(contents = testContents, client = client, model = "gpt-6", responses = true)

        // when
        val body = json(inference.responseRequest(perceivingImage)._body())

        // then
        assert(body["input"][2] == json("""
            {"type": "function_call_output", "call_id": "call_1", "output": ${jsonMapper().writeValueAsString(imageMarkup)}}
        """))
        assert(body["input"][3] == json("""
            {"type": "message", "role": "user", "content": [
              {"type": "input_image", "detail": "auto", "image_url": "data:image/png;base64,$redPixelPngBase64"}
            ]}
        """))
    }

    @Test
    fun `should request chat completion with PDF document perceived after its markup`() {
        // given
        val inference = OpenAIInference(contents = testContents, client = client, model = "gpt-6")

        // when
        val body = json(inference.chatRequest(perceivingDocument)._body())

        // then
        assert(body["messages"][4]["content"][0] == json("""
            {"type": "file", "file": {"filename": "document.pdf", "file_data": "data:application/pdf;base64,${Base64.encode(minimalPdf)}"}}
        """))
    }

    @Test
    fun `should request response with PDF document perceived after its markup`() {
        // given
        val inference = OpenAIInference(contents = testContents, client = client, model = "gpt-6", responses = true)

        // when
        val body = json(inference.responseRequest(perceivingDocument)._body())

        // then
        assert(body["input"][3]["content"][0] == json("""
            {"type": "input_file", "filename": "document.pdf", "file_data": "data:application/pdf;base64,${Base64.encode(minimalPdf)}"}
        """))
    }

    private val perceivingDocument = cogitation(Phenomenon.Intent(script = "document()", id = "call_1")).let {
        it.copy(enunciations = it.enunciations + Enunciation(
            actant = Actant(type = Actant.Type.COMPUTER, name = "user's computer"),
            initiated = initiated,
            respondsTo = "call_1",
            phenomena = listOf(data(minimalPdf, "application/pdf"))
        ))
    }

    private val perceivingImage = cogitation(Phenomenon.Intent(script = "image()", id = "call_1")).let {
        it.copy(enunciations = it.enunciations + Enunciation(
            actant = Actant(type = Actant.Type.COMPUTER, name = "user's computer"),
            initiated = initiated,
            respondsTo = "call_1",
            phenomena = listOf(data(redPixelPng, "image/png"))
        ))
    }

    private val imageMarkup = "<annie:actant type=\"computer\" name=\"user's computer\" initiated=\"2026-10-22T10:15:30Z\"/>\n" +
            "<annie:data content-type=\"image/png\" size=\"70\"/>"

    @Test
    fun `should request encrypted reasoning from Responses API without storing it`() {
        // given
        val monologues = InMemoryMonologueStore()
        val inference = OpenAIInference(
            contents = testContents,
            client = client,
            model = "gpt-6",
            responses = true,
            effort = Effort.HIGH,
            visibility = MonologueVisibility.SUMMARY,
            monologues = monologues
        )
        monologues["other"] = MonologueTrace.OpenAIReasoning(
            producedBy = "gpt-5", itemId = "rs_0", summary = listOf("other thought"), encryptedContent = "other"
        )
        monologues["thought"] = MonologueTrace.OpenAIReasoning(
            producedBy = "gpt-6", itemId = "rs_1", summary = listOf("thought"), encryptedContent = "secret"
        )

        // when
        val body = json(inference.responseRequest(cogitation(
            monologue("other thought", id = "other"),
            monologue("thought", id = "thought"),
            monologue("thought of another cognizer", id = "unknown"),
            Phenomenon.Intent(script = "6 * 7", id = "call_1")
        ))._body())

        // then
        val tool = json(tool) as com.fasterxml.jackson.databind.node.ObjectNode
        tool.put("type", "function")
        tool.put("strict", false)
        assert(body["tools"] == json("[$tool]"))
        assert(body["tool_choice"].asText() == "auto")
        assert(body["parallel_tool_calls"].asBoolean() == false)
        assert(body["store"].asBoolean() == false)
        assert(body["include"] == json("""["reasoning.encrypted_content"]"""))
        assert(body["reasoning"] == json("""{"effort": "high", "summary": "auto"}"""))
        assert(body["instructions"].asText() == "constitution")
        assert(body["input"] == json("""
            [
              {"type": "message", "role": "user", "content": ${jsonMapper().writeValueAsString(userTurn)}},
              {"type": "reasoning", "id": "rs_1", "summary": [{"type": "summary_text", "text": "thought"}], "encrypted_content": "secret"},
              {"type": "function_call", "call_id": "call_1", "name": "execute", "arguments": ${jsonMapper().writeValueAsString(arguments("6 * 7"))}}
            ]
        """))
    }

    @Test
    fun `should replay text, and execution of intent as output of its function call in response`() {
        // given
        val inference = OpenAIInference(contents = testContents, client = client, model = "gpt-6", responses = true)

        // when
        val body = json(inference.responseRequest(executed)._body())

        // then
        assert(body["input"][1] == json("""{"type": "message", "role": "assistant", "content": "Computing it."}"""))
        assert(body["input"][2]["type"].asText() == "function_call")
        assert(body["input"][3] == json("""
            {"type": "function_call_output", "call_id": "call_1", "output": ${jsonMapper().writeValueAsString(executedResult)}}
        """))
        assert(body["input"][4] == json("""
            {"type": "message", "role": "user", "content": [
              {"type": "input_image", "image_url": "data:image/png;base64,$redPixelPngBase64", "detail": "auto"}
            ]}
        """))
        assert(body["input"].size() == 5)
    }

    @Test
    fun `should perceive encrypted reasoning as monologue, output text, and function call as intent of response`() {
        // given
        val monologues = InMemoryMonologueStore()
        val inference = OpenAIInference(contents = testContents, client = client, model = "gpt-6", responses = true, monologues = monologues)
        val response = jsonMapper().readValue("""
            {
              "id": "resp_1",
              "object": "response",
              "created_at": 0,
              "model": "gpt-6",
              "output": [
                {"type": "reasoning", "id": "rs_1", "summary": [{"type": "summary_text", "text": "thought"}], "encrypted_content": "secret"},
                {"type": "message", "id": "msg_1", "role": "assistant", "status": "completed", "content": [
                  {"type": "output_text", "text": "Computing it.", "annotations": []}
                ]},
                {"type": "function_call", "id": "fc_1", "call_id": "call_1", "name": "execute",
                 "arguments": "{\"script\": \"6 * 7\"}", "status": "completed"},
                {"type": "message", "id": "msg_2", "role": "assistant", "status": "completed", "content": [
                  {"type": "output_text", "text": "42", "annotations": []}
                ]}
              ]
            }
        """, Response::class.java)

        // when
        val phenomena = inference.phenomena(response)

        // then
        val monologue = phenomena.first() as Monologue
        assert(monologue.content == "thought")
        assert(monologues[monologue.id] == MonologueTrace.OpenAIReasoning(
            producedBy = "gpt-6", itemId = "rs_1", summary = listOf("thought"), encryptedContent = "secret"
        ))
        assert(phenomena.size == 3)
        assert(phenomena[1] == text("Computing it."))
        val intent = phenomena.last() as Phenomenon.Intent
        assert(intent.script == "6 * 7")
        // the id of the provider, foreign to the cogitation, is replaced by one of its own
        assert(intent.id != "call_1" && intent.id != "fc_1")
    }

    @Test
    fun `should count tokens of chat completion by category`() {
        // given
        val inference = OpenAIInference(
            contents = testContents,
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
                "input" to 200L,
                "cacheRead" to 800L,
                "reasoning" to 30L,
                "output" to 20L
            ),
            stop = Inference.Stop(Inference.Stop.Kind.COMPLETED, "stop")
        ))
        assert(inferred.total == 1050L)
    }

    @Test
    fun `should count no input and output tokens of chat completion without usage`() {
        // given
        val inference = OpenAIInference(contents = testContents, client = client, model = "glm-5.3-flash")
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
            tokens = mapOf("input" to 0L, "output" to 0L),
            stop = Inference.Stop(Inference.Stop.Kind.COMPLETED, "stop")
        ))
    }

    @Test
    fun `should report finish reason of chat completion by kind`() {
        // given
        val inference = OpenAIInference(contents = testContents, client = client, model = "glm-5.3-flash")

        // when
        val stops = listOf("stop", "tool_calls", "length", "content_filter", "abort").map { reason ->
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
            // calling the tool of intents completes an inference expressing one
            Inference.Stop(Inference.Stop.Kind.COMPLETED, "tool_calls"),
            Inference.Stop(Inference.Stop.Kind.TRUNCATED, "length"),
            Inference.Stop(Inference.Stop.Kind.REFUSED, "content_filter"),
            Inference.Stop(Inference.Stop.Kind.OTHER, "abort")
        ))
    }

    @Test
    fun `should report status of response by kind`() {
        // given
        val inference = OpenAIInference(contents = testContents, client = client, model = "gpt-6", responses = true)

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
        val inference = OpenAIInference(contents = testContents, client = client, model = "gpt-6", responses = true)
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
                "input" to 100L,
                "cacheRead" to 600L,
                "cacheWrite" to 300L,
                "reasoning" to 30L,
                "output" to 20L
            )
        ))
    }

    @Test
    fun `should perceive edits of call of the tool of intents in chat completion, also when passed as JSON text`() {
        // given
        val inference = OpenAIInference(contents = testContents, client = client, model = "deepseek-v4_1-flash")
        val edits = """[{"path": "/app/g.py", "new": "x = 1\n"}]"""
        val arguments = listOf(
            jsonMapper().writeValueAsString(mapOf("script" to "1", "edits" to jsonMapper().readTree(edits))),
            // some models pass an array as its JSON
            jsonMapper().writeValueAsString(mapOf("script" to "1", "edits" to edits))
        )

        // when
        val intents = arguments.map { IntentTool.intent("execute", it) }

        // then
        intents.forEach { intent ->
            assert(intent is Phenomenon.Intent && intent.script == "1" && intent.edits == listOf(Edit(path = "/app/g.py", new = "x = 1\n")))
        }
    }

    @Test
    fun `should take the path of an edit without one from the other edits of the call naming a single file`() {
        // given
        // DeepSeek left out the path of edits to the file the other edits of the call named
        val arguments = """{"script": "1", "edits": [
            {"path": "/app/a.py", "old": "a", "new": "b"},
            {"new": "d", "old": "c"},
            {"path": "/app/a.py", "old": "e", "new": "f", "all": true}
        ]}"""

        // when
        val intent = IntentTool.intent("execute", arguments)

        // then
        assert(intent is Phenomenon.Intent && intent.edits == listOf(
            Edit(path = "/app/a.py", old = "a", new = "b"),
            Edit(path = "/app/a.py", old = "c", new = "d"),
            Edit(path = "/app/a.py", old = "e", new = "f", all = true)
        ))
    }

    @Test
    fun `should not take the path of an edit without one when the other edits of the call name several files, or none`() {
        // when
        val impediments = listOf(
            """{"script": "1", "edits": [{"path": "/app/a.py", "new": "b"}, {"path": "/app/b.py", "new": "c"}, {"new": "d", "old": "c"}]}""",
            """{"script": "1", "edits": [{"new": "d", "old": "c"}, {"new": "f", "old": "e"}]}"""
        ).map { IntentTool.intent("execute", it) }

        // then
        assert(impediments.all { it is Phenomenon.Impediment && "had no `path`" in it.reason })
    }

    @Test
    fun `should perceive call of the tool of intents with malformed edits as an impediment`() {
        // when
        val impediment = IntentTool.intent("execute", """{"script": "1", "edits": [{"file": "/app/g.py", "new": "x"}]}""")

        // then
        assert(impediment == Phenomenon.Impediment(
            "Your call of `execute` carried a malformed edit, so nothing was executed: " +
                    "each edit is {\"path\", \"old\", \"new\", \"all\"}, `path` and `new` being required texts, `old` a text, and `all` a boolean, " +
                    "but edit 1 of 1 had no `path`, being {\"file\": a text of 9 characters, \"new\": a text of 1 character}: " +
                    "give every edit its own `path`, even one changing the file of the edit before it, or of an earlier call"
        ))
    }

    @Test
    fun `should tell to give every edit its path when the edits of the call name no file`() {
        // given
        // the shape of the malformed edits of DeepSeek: none of them naming its file, the file of an earlier call being meant
        val arguments = """{"script": "1", "edits": [{"old": "a", "new": "b"}, {"old": "c", "new": "d"}]}"""

        // when
        val impediment = IntentTool.intent("execute", arguments)

        // then
        assert(impediment == Phenomenon.Impediment(
            "Your call of `execute` carried a malformed edit, so nothing was executed: " +
                    "each edit is {\"path\", \"old\", \"new\", \"all\"}, `path` and `new` being required texts, `old` a text, and `all` a boolean, " +
                    "but edit 1 of 2 had no `path`, being {\"old\": a text of 1 character, \"new\": a text of 1 character}, " +
                    "and edit 2 of 2 had no `path`, being {\"old\": a text of 1 character, \"new\": a text of 1 character}: " +
                    "give every edit its own `path`, even one changing the file of the edit before it, or of an earlier call"
        ))
    }

    @Test
    fun `should name what a malformed edit among long ones has, rather than cutting its text`() {
        // given
        // the shape of the edits of DeepSeek which a cut text hid: a long `new` text, written first
        val long = "    \"\"\"Docstring.\"\"\"\n".repeat(50)
        val arguments = jsonMapper().writeValueAsString(mapOf(
            "script" to "1",
            "edits" to listOf(
                mapOf("path" to "/app/a.py", "old" to "a", "new" to "b"),
                mapOf("new" to long, "old" to "c", "all" to "yes"),
                "/app/c.py"
            )
        ))

        // when
        val impediment = IntentTool.intent("execute", arguments)

        // then
        assert(impediment == Phenomenon.Impediment(
            "Your call of `execute` carried a malformed edit, so nothing was executed: " +
                    "each edit is {\"path\", \"old\", \"new\", \"all\"}, `path` and `new` being required texts, `old` a text, and `all` a boolean, " +
                    // its path taken from the other edit, as the edits name a single file
                    "but edit 2 of 3 had `all` being a text, being " +
                    "{\"new\": a text of ${long.length} characters, \"old\": a text of 1 character, \"all\": a text of 3 characters}, " +
                    "and edit 3 of 3 was not an object, but a text of 9 characters"
        ))
    }

}
