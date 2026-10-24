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
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.RedactedThinkingBlockParam
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.xemantic.ai.annie.Cogitation
import com.xemantic.ai.annie.Inference
import com.xemantic.ai.annie.Inference.Tokens
import com.xemantic.ai.annie.Phenomenon
import com.xemantic.ai.annie.Phenomenon.Monologue
import com.xemantic.ai.annie.Phenomenon.Monologue.Continuation
import com.xemantic.ai.annie.cognizer.Cognized
import com.xemantic.ai.annie.cognizer.Cognizer
import com.xemantic.ai.annie.cognizer.Turn
import com.xemantic.ai.annie.cognizer.toTurns
import kotlinx.coroutines.future.await

/**
 * A [Cognizer] speaking to Claude through the Messages API of the official Anthropic SDK,
 * the [client] deciding where the model is served, e.g. by a Bedrock backend.
 *
 * Claude always thinks adaptively, and its monologue (thinking blocks, redacted ones included)
 * is returned as [Monologue] phenomena, replayed on subsequent inferences only when it was produced
 * by the very same [modelId], whatever endpoint served it, as other models cannot verify it.
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
 */
class ClaudeInference(
    private val client: AnthropicClient,
    private val model: String,
    private val modelId: String = model,
    private val provider: String = "anthropic",
    private val maxTokens: Long = 128_000,
    private val effort: Monologue.Effort? = null,
    private val visibility: Monologue.Visibility = Monologue.Visibility.HIDDEN,
    private val tailCache: CacheControlEphemeral.Ttl = CacheControlEphemeral.Ttl.TTL_5M
) : Cognizer {

    override suspend fun cognize(cogitation: Cogitation): Cognized {
        val message = client.async().messages().create(request(cogitation)).await()
        return Cognized(phenomena(message), inference(message))
    }

    internal fun request(cogitation: Cogitation): MessageCreateParams = MessageCreateParams.builder()
        .model(model)
        .maxTokens(maxTokens)
        .systemOfTextBlockParams(listOf(
            TextBlockParam.builder()
                .text(cogitation.constitution.joinToString(separator = "\n\n"))
                .cacheControl(CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build())
                .build()
        ))
        .cacheControl(CacheControlEphemeral.builder().ttl(tailCache).build())
        .thinking(ThinkingConfigAdaptive.builder().display(visibility.claudeDisplay).build())
        .apply {
            effort?.let { outputConfig(OutputConfig.builder().effort(it.claudeEffort).build()) }
            if (visibility == Monologue.Visibility.UPDATES) {
                putAdditionalHeader("anthropic-beta", "thinking-display-updates-2026-08-18")
            }
        }
        .messages(
            cogitation
                .toTurns(
                    intent = { ContentBlockParam.ofText(TextBlockParam.builder().text(it.script).build()) },
                    monologue = { it.toBlock() }
                )
                .map { turn ->
                    when (turn) {
                        is Turn.User -> MessageParam.ofUser(turn.text)
                        is Turn.Assistant -> MessageParam.ofAssistantOfContentBlockParams(turn.parts)
                    }
                }
        )
        .build()

    /** The monologue is replayed only to the model which produced it, others cannot verify it. */
    private fun Monologue.toBlock(): ContentBlockParam? = when (val continuation = continuation) {
        is Continuation.ClaudeSignature -> if (continuation.producedBy == modelId) {
            // must be byte-identical to what was received, otherwise the signature is invalid
            ContentBlockParam.ofThinking(ThinkingBlockParam.builder()
                .thinking(summary.joinToString(separator = ""))
                .signature(continuation.signature)
                .build())
        } else null
        is Continuation.ClaudeRedacted -> if (continuation.producedBy == modelId) {
            ContentBlockParam.ofRedactedThinking(RedactedThinkingBlockParam.builder()
                .data(continuation.data)
                .build())
        } else null
        is Continuation.OpenAIReasoning, null -> null
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
                        "end_turn", "stop_sequence" -> Inference.Stop.Kind.COMPLETED
                        "max_tokens", "model_context_window_exceeded" -> Inference.Stop.Kind.TRUNCATED
                        "refusal" -> Inference.Stop.Kind.REFUSED
                        else -> Inference.Stop.Kind.OTHER
                    },
                    reason = reason
                )
            }
        )
    }

    /** Thinking blocks become monologues, and consecutive text blocks a single intent. */
    internal fun phenomena(message: Message): List<Phenomenon> = message.content().fold(
        mutableListOf()
    ) { phenomena, block ->
        when {
            block.isThinking() -> block.asThinking().let {
                phenomena += Monologue(
                    summary = listOf(it.thinking()).filter { text -> text.isNotEmpty() },
                    role = Monologue.Role.REASONING,
                    continuation = Continuation.ClaudeSignature(producedBy = modelId, signature = it.signature())
                )
            }
            block.isRedactedThinking() -> phenomena += Monologue(
                role = Monologue.Role.REASONING,
                continuation = Continuation.ClaudeRedacted(producedBy = modelId, data = block.asRedactedThinking().data())
            )
            block.isText() -> {
                val text = block.asText().text()
                val last = phenomena.lastOrNull()
                if (last is Phenomenon.Intent) {
                    phenomena[phenomena.lastIndex] = last.copy(script = "${last.script}\n$text")
                } else {
                    phenomena += Phenomenon.Intent(text)
                }
            }
            // no tools are offered to the model
            else -> {}
        }
        phenomena
    }

}

private val Monologue.Visibility.claudeDisplay: ThinkingConfigAdaptive.Display get() = when (this) {
    Monologue.Visibility.HIDDEN -> ThinkingConfigAdaptive.Display.OMITTED
    Monologue.Visibility.SUMMARY -> ThinkingConfigAdaptive.Display.SUMMARIZED
    // requires the thinking-display-updates-2026-08-18 beta header
    Monologue.Visibility.UPDATES -> ThinkingConfigAdaptive.Display.of("updates")
}

/** Claude's lowest effort is `low`. */
private val Monologue.Effort.claudeEffort: OutputConfig.Effort get() = when (this) {
    Monologue.Effort.NONE, Monologue.Effort.MINIMAL, Monologue.Effort.LOW -> OutputConfig.Effort.LOW
    Monologue.Effort.MEDIUM -> OutputConfig.Effort.MEDIUM
    Monologue.Effort.HIGH -> OutputConfig.Effort.HIGH
    Monologue.Effort.XHIGH -> OutputConfig.Effort.XHIGH
    Monologue.Effort.MAX -> OutputConfig.Effort.MAX
}
