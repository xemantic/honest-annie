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

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.Base64PdfSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.DocumentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.RedactedThinkingBlockParam
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolChoiceAuto
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlockParam
import com.xemantic.ai.annie.Cogitation
import com.xemantic.ai.annie.Inference
import com.xemantic.ai.annie.Inference.Tokens
import com.xemantic.ai.annie.Phenomenon
import com.xemantic.ai.annie.Phenomenon.Monologue
import com.xemantic.ai.annie.cognizer.Cognized
import com.xemantic.ai.annie.cognizer.Cognizer
import com.xemantic.ai.annie.cognizer.Effort
import com.xemantic.ai.annie.cognizer.InMemoryMonologueStore
import com.xemantic.ai.annie.cognizer.IntentTool
import com.xemantic.ai.annie.cognizer.MonologueStore
import com.xemantic.ai.annie.cognizer.MonologueTrace
import com.xemantic.ai.annie.cognizer.MonologueVisibility
import com.xemantic.ai.annie.cognizer.Perceived
import com.xemantic.ai.annie.cognizer.Turn
import com.xemantic.ai.annie.cognizer.systemPrompt
import com.xemantic.ai.annie.cognizer.toTurns
import kotlinx.coroutines.future.await

/**
 * A [Cognizer] speaking to Claude through the Messages API of the official Anthropic SDK,
 * the [client] deciding where the model is served, e.g. by a Bedrock backend.
 *
 * Claude always thinks adaptively, and its monologue (thinking blocks, redacted ones included)
 * is returned as [Monologue] phenomena, their exact form kept in the [monologues],
 * to be replayed on subsequent inferences only when it was produced
 * by the very same [modelId], whatever endpoint served it, as other models cannot verify it.
 * It expresses its intent by calling the only tool offered, see [IntentTool], one call at a time,
 * the text it writes being a [Phenomenon.Text], replayed to any model: its answer when it calls no tool.
 *
 * The constitution is cached with a 1-hour breakpoint, while the growing cogitation is cached
 * by the automatic (top-level) breakpoint with [tailCache] TTL. Longer TTLs must precede shorter ones,
 * so the tail can be either 5 minutes or 1 hour, but the constitution never shorter.
 *
 * @param model the model id as known to the endpoint, e.g. a Bedrock one.
 * @param modelId the canonical id of the [model], the same at every endpoint serving it.
 * @param provider who serves the [model] through the [client], reported in the [Inference].
 * @param maxTokens the maximal number of tokens of a single inference, the monologue included,
 * the maximum of Claude Opus 5.5 by default.
 * @param effort how much effort is put into the monologue and the response, the default of the model when `null`.
 * @param visibility how much of the monologue is returned.
 * @param monologues keeping the exact form of the monologues, see [MonologueStore].
 */
class ClaudeInference(
    private val client: AnthropicClient,
    private val model: String,
    private val modelId: String = model,
    private val provider: String = "anthropic",
    private val maxTokens: Long = 128_000,
    private val effort: Effort? = null,
    private val visibility: MonologueVisibility = MonologueVisibility.HIDDEN,
    private val tailCache: CacheControlEphemeral.Ttl = CacheControlEphemeral.Ttl.TTL_5M,
    private val monologues: MonologueStore = InMemoryMonologueStore()
) : Cognizer {

    override suspend fun cognize(cogitation: Cogitation): Cognized {
        val message = client.async().messages().create(request(cogitation)).await()
        return Cognized(phenomena(message), inference(message))
    }

    internal fun request(cogitation: Cogitation): MessageCreateParams = MessageCreateParams.builder()
        .model(model)
        .maxTokens(maxTokens)
        .apply {
            cogitation.systemPrompt?.let {
                systemOfTextBlockParams(listOf(
                    TextBlockParam.builder()
                        .text(it)
                        .cacheControl(CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build())
                        .build()
                ))
            }
        }
        .cacheControl(CacheControlEphemeral.builder().ttl(tailCache).build())
        .thinking(ThinkingConfigAdaptive.builder().display(visibility.claudeDisplay).build())
        .addTool(Tool.builder()
            .name(IntentTool.NAME)
            .description(IntentTool.DESCRIPTION)
            .inputSchema(Tool.InputSchema.builder()
                .properties(Tool.InputSchema.Properties.builder()
                    .putAdditionalProperty(IntentTool.PARAMETER, JsonValue.from(IntentTool.parameter))
                    .build())
                .required(listOf(IntentTool.PARAMETER))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build())
            // the arguments of a call always fit the schema
            .strict(true)
            .build())
        // a single intent per inference, never forced: a response calling no tool answers with its text
        .toolChoice(ToolChoiceAuto.builder().disableParallelToolUse(true).build())
        .apply {
            effort?.let { outputConfig(OutputConfig.builder().effort(it.claudeEffort).build()) }
            if (visibility == MonologueVisibility.UPDATES) {
                putAdditionalHeader("anthropic-beta", "thinking-display-updates-2026-08-18")
            }
        }
        .messages(
            cogitation
                .toTurns(
                    intent = { intent ->
                        ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                            .id(intent.id)
                            .name(IntentTool.NAME)
                            .input(ToolUseBlockParam.Input.builder()
                                .putAdditionalProperty(IntentTool.PARAMETER, JsonValue.from(intent.script))
                                .build())
                            .build())
                    },
                    monologue = { it.toBlock() },
                    text = { text ->
                        text.text.takeIf { it.isNotBlank() }?.let {
                            ContentBlockParam.ofText(TextBlockParam.builder().text(it).build())
                        }
                    }
                )
                .map { turn ->
                    when (turn) {
                        // text only as a string, as before images were perceived, so that the cached prefix stays the same
                        is Turn.User -> turn.parts.singleOrNull().let { part ->
                            if (part is Perceived.Text) MessageParam.ofUser(part.text)
                            else MessageParam.ofUserOfContentBlockParams(turn.parts.map { it.toBlock() })
                        }
                        is Turn.Assistant -> MessageParam.ofAssistantOfContentBlockParams(turn.parts)
                    }
                }
        )
        .build()

    /** The monologue is replayed only to the model which produced it, others cannot verify it, in the exact form it had. */
    private fun Monologue.toBlock(): ContentBlockParam? = when (val trace = monologues[id]) {
        is MonologueTrace.ClaudeThinking -> if (trace.producedBy == modelId) {
            ContentBlockParam.ofThinking(ThinkingBlockParam.builder()
                .thinking(trace.thinking)
                .signature(trace.signature)
                .build())
        } else null
        is MonologueTrace.ClaudeRedacted -> if (trace.producedBy == modelId) {
            ContentBlockParam.ofRedactedThinking(RedactedThinkingBlockParam.builder()
                .data(trace.data)
                .build())
        } else null
        is MonologueTrace.OpenAIReasoning, null -> null
    }

    /** The input tokens of Claude exclude the cached ones, while its output tokens include the thinking ones. */
    internal fun inference(message: Message): Inference {
        val usage = message.usage()
        val cacheCreation = usage.cacheCreation().orElse(null)
        val thinking = usage.outputTokensDetails().map { it.thinkingTokens() }.orElse(0L)
        return Inference(
            model = modelId,
            provider = provider,
            tokens = buildMap {
                put(Tokens.INPUT, usage.inputTokens())
                put(Tokens.CACHE_READ, usage.cacheReadInputTokens().orElse(0L))
                if (cacheCreation != null) {
                    put(Tokens.CACHE_WRITE_5M, cacheCreation.ephemeral5mInputTokens())
                    put(Tokens.CACHE_WRITE_1H, cacheCreation.ephemeral1hInputTokens())
                } else {
                    put(Tokens.CACHE_WRITE, usage.cacheCreationInputTokens().orElse(0L))
                }
                put(Tokens.REASONING, thinking)
                put(Tokens.OUTPUT, usage.outputTokens() - thinking)
            }.filterValues { it > 0 },
            stop = message.stopReason().map { it.asString() }.orElse(null)?.let { reason ->
                Inference.Stop(
                    kind = when (reason) {
                        // no stop sequences are requested, but they would complete the response as intended
                        "end_turn", "stop_sequence", "tool_use" -> Inference.Stop.Kind.COMPLETED
                        "max_tokens", "model_context_window_exceeded" -> Inference.Stop.Kind.TRUNCATED
                        "refusal" -> Inference.Stop.Kind.REFUSED
                        else -> Inference.Stop.Kind.OTHER
                    },
                    reason = reason
                )
            }
        )
    }

    /**
     * Thinking blocks become monologues, text blocks texts, and the call of the tool the intent.
     * Whatever follows the tool call is not perceived:
     * the model continues as if it perceived the result of the intent, which is not executed yet.
     */
    internal fun phenomena(message: Message): List<Phenomenon> {
        val phenomena = mutableListOf<Phenomenon>()
        for (block in message.content()) {
            when {
                block.isThinking() -> block.asThinking().let {
                    val monologue = Monologue(it.thinking())
                    monologues[monologue.id] = MonologueTrace.ClaudeThinking(
                        producedBy = modelId, thinking = it.thinking(), signature = it.signature()
                    )
                    phenomena += monologue
                }
                block.isRedactedThinking() -> {
                    val monologue = Monologue("")
                    monologues[monologue.id] = MonologueTrace.ClaudeRedacted(
                        producedBy = modelId, data = block.asRedactedThinking().data()
                    )
                    phenomena += monologue
                }
                block.isText() -> block.asText().text().takeIf { it.isNotBlank() }?.let {
                    phenomena += Phenomenon.Text(it)
                }
                block.isToolUse() -> {
                    val use = block.asToolUse()
                    IntentTool.intent(use.name(), jsonMapper().writeValueAsString(use._input()))
                        ?.let { phenomena += it }
                    break
                }
                else -> {}
            }
        }
        return phenomena
    }

}

private fun Perceived.toBlock(): ContentBlockParam = when (this) {
    is Perceived.Result -> ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
        .toolUseId(intent)
        .contentOfBlocks(parts.map { it.toResultBlock() })
        .build())
    is Perceived.Text -> ContentBlockParam.ofText(this.text)
    is Perceived.Image -> ContentBlockParam.ofImage(ImageBlockParam.builder()
        .source(Base64ImageSource.builder()
            .data(base64)
            .mediaType(Base64ImageSource.MediaType.of(mediaType))
            .build())
        .build())
    is Perceived.Document -> ContentBlockParam.ofDocument(DocumentBlockParam.builder()
        .source(Base64PdfSource.builder().data(base64).build())
        .build())
}

private fun Perceived.toResultBlock(): ToolResultBlockParam.Content.Block = when (this) {
    is Perceived.Text -> ToolResultBlockParam.Content.Block.ofText(text)
    is Perceived.Image -> ToolResultBlockParam.Content.Block.ofImage(Base64ImageSource.builder()
        .data(base64)
        .mediaType(Base64ImageSource.MediaType.of(mediaType))
        .build())
    is Perceived.Document -> ToolResultBlockParam.Content.Block.ofDocument(Base64PdfSource.builder().data(base64).build())
    is Perceived.Result -> error("A tool result never nests another one")
}

private val MonologueVisibility.claudeDisplay: ThinkingConfigAdaptive.Display get() = when (this) {
    HIDDEN -> ThinkingConfigAdaptive.Display.OMITTED
    SUMMARY -> ThinkingConfigAdaptive.Display.SUMMARIZED
    // requires the thinking-display-updates-2026-08-18 beta header
    UPDATES -> ThinkingConfigAdaptive.Display.of("updates")
}

/** Claude's lowest effort is `low`. */
private val Effort.claudeEffort: OutputConfig.Effort get() = when (this) {
    NONE, MINIMAL, LOW -> OutputConfig.Effort.LOW
    MEDIUM -> OutputConfig.Effort.MEDIUM
    HIGH -> OutputConfig.Effort.HIGH
    XHIGH -> OutputConfig.Effort.XHIGH
    MAX -> OutputConfig.Effort.MAX
}
