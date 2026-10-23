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

import com.openai.client.OpenAIClient
import com.openai.models.ReasoningEffort
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseIncludable
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseReasoningItem
import com.openai.models.Reasoning
import com.xemantic.ai.annie.Cogitation
import com.xemantic.ai.annie.Phenomenon
import com.xemantic.ai.annie.Phenomenon.Monologue
import com.xemantic.ai.annie.Phenomenon.Monologue.Continuation
import com.xemantic.ai.annie.cognizer.Cognizer
import com.xemantic.ai.annie.cognizer.Turn
import com.xemantic.ai.annie.cognizer.toTurns
import kotlinx.coroutines.future.await

/**
 * A [Cognizer] speaking to an OpenAI-compatible model through the official OpenAI SDK,
 * either through the Responses API, or Chat Completions, e.g. of open-weight models served by vLLM.
 *
 * Through the Responses API, the monologue travels encrypted in the [Continuation.OpenAIReasoning]
 * and is replayed only to the [modelId] which produced it, nothing is stored server-side.
 * Through Chat Completions, the monologue of the model, e.g. `reasoning_content` of vLLM, is returned
 * as a [Monologue], but never replayed, as there is nothing to verify it with.
 *
 * @param model the model id as known to the endpoint.
 * @param modelId the canonical id of the [model], the same at every endpoint serving it.
 * @param responses whether the Responses API is used instead of Chat Completions.
 * @param maxTokens the maximal number of tokens of a single inference, the monologue included,
 * the maximum of GPT-6 Astra by default.
 * @param effort how much effort is put into the monologue and the response, the default of the model when `null`.
 * @param visibility how much of the monologue is returned, only through the Responses API.
 */
class OpenAIInference(
    private val client: OpenAIClient,
    private val model: String,
    private val modelId: String = model,
    private val responses: Boolean = false,
    private val maxTokens: Long = 128_000,
    private val effort: Monologue.Effort? = null,
    private val visibility: Monologue.Visibility = Monologue.Visibility.HIDDEN
) : Cognizer {

    override suspend fun cognize(cogitation: Cogitation): List<Phenomenon> = if (responses) {
        phenomena(client.async().responses().create(responseRequest(cogitation)).await())
    } else {
        phenomena(client.async().chat().completions().create(chatRequest(cogitation)).await())
    }

    internal fun chatRequest(cogitation: Cogitation): ChatCompletionCreateParams =
        ChatCompletionCreateParams.builder()
            .model(model)
            .maxCompletionTokens(maxTokens)
            .apply { effort?.let { reasoningEffort(it.openAIEffort) } }
            .addSystemMessage(cogitation.constitution.joinToString(separator = "\n\n"))
            .apply {
                cogitation.enunciations
                    // the reasoning of Chat Completions is never replayed
                    .toTurns(intent = { it.script }, monologue = { null })
                    .forEach { turn ->
                        when (turn) {
                            is Turn.User -> addUserMessage(turn.text)
                            is Turn.Assistant -> addAssistantMessage(turn.parts.joinToString(separator = "\n"))
                        }
                    }
            }
            .build()

    internal fun responseRequest(cogitation: Cogitation): ResponseCreateParams = ResponseCreateParams.builder()
        .model(model)
        .maxOutputTokens(maxTokens)
        .instructions(cogitation.constitution.joinToString(separator = "\n\n"))
        .reasoning(Reasoning.builder()
            .apply { effort?.let { effort(it.openAIEffort) } }
            .apply { if (visibility != Monologue.Visibility.HIDDEN) summary(Reasoning.Summary.AUTO) }
            .build())
        // stateless: the reasoning travels encrypted in the Monologue continuation
        .addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT)
        .store(false)
        .inputOfResponse(
            cogitation.enunciations
                .toTurns(
                    intent = { ResponseInputItem.ofEasyInputMessage(it.script.message(EasyInputMessage.Role.ASSISTANT)) },
                    monologue = { it.toReasoningItem() }
                )
                .flatMap { turn ->
                    when (turn) {
                        is Turn.User -> listOf(
                            ResponseInputItem.ofEasyInputMessage(turn.text.message(EasyInputMessage.Role.USER))
                        )
                        is Turn.Assistant -> turn.parts
                    }
                }
        )
        .build()

    /** The monologue is replayed only to the model which produced it, others cannot verify it. */
    private fun Monologue.toReasoningItem(): ResponseInputItem? = when (val continuation = continuation) {
        is Continuation.OpenAIReasoning -> if (continuation.producedBy == modelId) {
            ResponseInputItem.ofReasoning(ResponseReasoningItem.builder()
                .id(continuation.itemId)
                .summary(summary.map { ResponseReasoningItem.Summary.builder().text(it).build() })
                .encryptedContent(continuation.encryptedContent)
                .build())
        } else null
        is Continuation.ClaudeSignature, is Continuation.ClaudeRedacted, null -> null
    }

    internal fun phenomena(completion: ChatCompletion): List<Phenomenon> {
        val message = completion.choices().first().message()
        // not part of the OpenAI API, but returned by vLLM
        val reasoning = message._allProperties().let { it["reasoning_content"] ?: it["reasoning"] }
            ?.asString()?.orElse(null)
        return listOfNotNull(
            reasoning?.takeIf { it.isNotEmpty() }?.let {
                Monologue(summary = listOf(it), role = Monologue.Role.REASONING)
            },
            message.content().orElse(null)?.let { Phenomenon.Intent(it) }
        )
    }

    /** Reasoning items become monologues, and consecutive output texts a single intent. */
    internal fun phenomena(response: Response): List<Phenomenon> = response.output().fold(
        mutableListOf()
    ) { phenomena, item ->
        when {
            item.isReasoning() -> item.asReasoning().let { reasoning ->
                phenomena += Monologue(
                    summary = reasoning.summary().map { it.text() }.filter { it.isNotEmpty() },
                    role = Monologue.Role.REASONING,
                    continuation = Continuation.OpenAIReasoning(
                        producedBy = modelId,
                        itemId = reasoning.id(),
                        encryptedContent = reasoning.encryptedContent().orElse(null)
                    )
                )
            }
            item.isMessage() -> item.asMessage().content()
                .filter { it.isOutputText() }
                .forEach { content ->
                    val text = content.asOutputText().text()
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

private fun String.message(role: EasyInputMessage.Role): EasyInputMessage =
    EasyInputMessage.builder().role(role).content(this).build()

private val Monologue.Effort.openAIEffort: ReasoningEffort get() = when (this) {
    Monologue.Effort.NONE -> ReasoningEffort.NONE
    Monologue.Effort.MINIMAL -> ReasoningEffort.MINIMAL
    Monologue.Effort.LOW -> ReasoningEffort.LOW
    Monologue.Effort.MEDIUM -> ReasoningEffort.MEDIUM
    Monologue.Effort.HIGH -> ReasoningEffort.HIGH
    Monologue.Effort.XHIGH, Monologue.Effort.MAX -> ReasoningEffort.XHIGH
}
