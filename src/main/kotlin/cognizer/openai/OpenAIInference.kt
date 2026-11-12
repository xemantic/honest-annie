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
import com.openai.core.JsonField
import com.openai.core.JsonValue
import com.openai.core.jsonMapper
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.ReasoningEffort
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionContentPart
import com.openai.models.chat.completions.ChatCompletionContentPartImage
import com.openai.models.chat.completions.ChatCompletionContentPartText
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionFunctionTool
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseIncludable
import com.openai.models.responses.ResponseInputContent
import com.openai.models.responses.ResponseInputFile
import com.openai.models.responses.ResponseInputImage
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseInputText
import com.openai.models.responses.ResponseReasoningItem
import com.openai.models.responses.ToolChoiceOptions
import com.openai.models.Reasoning
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
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.future.await

private val logger = KotlinLogging.logger {}

/**
 * A [Cognizer] speaking to an OpenAI-compatible model through the official OpenAI SDK,
 * either through the Responses API, or Chat Completions, e.g. of open-weight models served by vLLM.
 *
 * Through the Responses API, the monologue travels encrypted, kept in the [monologues] as a [MonologueTrace.OpenAIReasoning],
 * and is replayed only to the [modelId] which produced it, nothing is stored server-side.
 * Through Chat Completions, the monologue of the model, e.g. `reasoning_content` of vLLM, is returned
 * as a [Monologue], but never replayed, as there is nothing to verify it with.
 * The model expresses its intent by calling the only tool offered, see [IntentTool], one call at a time,
 * the text it writes being a [Phenomenon.Text], replayed to any model: its answer when it calls no tool.
 *
 * @param model the model id as known to the endpoint.
 * @param modelId the canonical id of the [model], the same at every endpoint serving it.
 * @param provider who serves the [model] through the [client], reported in the [Inference].
 * @param responses whether the Responses API is used instead of Chat Completions.
 * @param maxTokens the maximal number of tokens of a single inference, the monologue included,
 * the maximum of GPT-6 Astra by default.
 * @param effort how much effort is put into the monologue and the response, the default of the model when `null`.
 * @param visibility how much of the monologue is returned, only through the Responses API.
 * @param monologues keeping the exact form of the monologues, see [MonologueStore].
 */
class OpenAIInference(
    private val client: OpenAIClient,
    private val model: String,
    private val modelId: String = model,
    private val provider: String = "openai",
    private val responses: Boolean = false,
    private val maxTokens: Long = 128_000,
    private val effort: Effort? = null,
    private val visibility: MonologueVisibility = MonologueVisibility.HIDDEN,
    private val monologues: MonologueStore = InMemoryMonologueStore()
) : Cognizer {

    override suspend fun cognize(cogitation: Cogitation): Cognized = if (responses) {
        val response = client.async().responses().create(responseRequest(cogitation)).await()
        Cognized(phenomena(response), inference(response))
    } else {
        val completion = client.async().chat().completions().create(chatRequest(cogitation)).await()
        Cognized(phenomena(completion), inference(completion))
    }

    internal fun chatRequest(cogitation: Cogitation): ChatCompletionCreateParams =
        ChatCompletionCreateParams.builder()
            .model(model)
            .maxCompletionTokens(maxTokens)
            .apply { effort?.let { reasoningEffort(it.openAIEffort) } }
            .addTool(ChatCompletionFunctionTool.builder()
                .function(FunctionDefinition.builder()
                    .name(IntentTool.NAME)
                    .description(IntentTool.DESCRIPTION)
                    .parameters(intentParameters)
                    .build())
                .build())
            // never forced: a completion calling no tool answers with its content
            .toolChoice(ChatCompletionToolChoiceOption.Auto.AUTO)
            .parallelToolCalls(false)
            .apply { cogitation.systemPrompt?.let { addSystemMessage(it) } }
            .apply {
                cogitation
                    // the reasoning of Chat Completions is never replayed
                    .toTurns<Phenomenon>(intent = { it }, monologue = { null }, text = { it.takeIf { it.text.isNotBlank() } })
                    .forEach { turn ->
                        when (turn) {
                            is Turn.User -> {
                                val result = turn.parts.firstOrNull() as? Perceived.Result
                                if (result != null) {
                                    addMessage(ChatCompletionToolMessageParam.builder()
                                        .toolCallId(result.intent)
                                        .content(result.text)
                                        .build())
                                }
                                // a tool result is only text, the attachments of the result follow it
                                val parts = if (result == null) turn.parts else result.attachments + turn.parts.drop(1)
                                when (val part = parts.singleOrNull()) {
                                    null if parts.isEmpty() -> {}
                                    is Perceived.Text -> addUserMessage(part.text)
                                    else -> addUserMessageOfArrayOfContentParts(parts.map { it.toContentPart() })
                                }
                            }
                            is Turn.Assistant -> addMessage(ChatCompletionAssistantMessageParam.builder()
                                .apply {
                                    turn.parts.text()?.let { content(it) }
                                    turn.parts.filterIsInstance<Phenomenon.Intent>().forEach { intent ->
                                        addToolCall(ChatCompletionMessageFunctionToolCall.builder()
                                            .id(intent.id)
                                            .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                                .name(IntentTool.NAME)
                                                .arguments(intent.arguments)
                                                .build())
                                            .build())
                                    }
                                }
                                .build())
                        }
                    }
            }
            .build()

    internal fun responseRequest(cogitation: Cogitation): ResponseCreateParams = ResponseCreateParams.builder()
        .model(model)
        .maxOutputTokens(maxTokens)
        .addTool(FunctionTool.builder()
            .name(IntentTool.NAME)
            .description(IntentTool.DESCRIPTION)
            .parameters(FunctionTool.Parameters.builder()
                .putAllAdditionalProperties(intentParameters._allProperties())
                .build())
            .strict(false)
            .build())
        .toolChoice(ToolChoiceOptions.AUTO)
        .parallelToolCalls(false)
        .apply { cogitation.systemPrompt?.let { instructions(it) } }
        .reasoning(Reasoning.builder()
            .apply { effort?.let { effort(it.openAIEffort) } }
            .apply { if (visibility != MonologueVisibility.HIDDEN) summary(Reasoning.Summary.AUTO) }
            .build())
        // stateless: the reasoning travels encrypted, see `MonologueTrace.OpenAIReasoning`
        .addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT)
        .store(false)
        .inputOfResponse(
            cogitation
                .toTurns(
                    intent = { intent ->
                        ResponseInputItem.ofFunctionCall(ResponseFunctionToolCall.builder()
                            .callId(intent.id)
                            .name(IntentTool.NAME)
                            .arguments(intent.arguments)
                            .build())
                    },
                    monologue = { it.toReasoningItem() },
                    text = { text ->
                        text.text.takeIf { it.isNotBlank() }?.let {
                            ResponseInputItem.ofEasyInputMessage(it.message(EasyInputMessage.Role.ASSISTANT))
                        }
                    }
                )
                .flatMap { turn ->
                    when (turn) {
                        is Turn.User -> {
                            val result = turn.parts.firstOrNull() as? Perceived.Result
                            // a function call output is only text, the attachments of the result follow it
                            val parts = if (result == null) turn.parts else result.attachments + turn.parts.drop(1)
                            listOfNotNull(
                                result?.let {
                                    ResponseInputItem.ofFunctionCallOutput(ResponseInputItem.FunctionCallOutput.builder()
                                        .callId(it.intent)
                                        .output(it.text)
                                        .build())
                                },
                                parts.takeIf { it.isNotEmpty() }?.let { ResponseInputItem.ofEasyInputMessage(Turn.User(it).message()) }
                            )
                        }
                        is Turn.Assistant -> turn.parts
                    }
                }
        )
        .build()

    /** The monologue is replayed only to the model which produced it, others cannot verify it. */
    private fun Monologue.toReasoningItem(): ResponseInputItem? = when (val trace = monologues[id]) {
        is MonologueTrace.OpenAIReasoning -> if (trace.producedBy == modelId) {
            ResponseInputItem.ofReasoning(ResponseReasoningItem.builder()
                .id(trace.itemId)
                .summary(trace.summary.map { ResponseReasoningItem.Summary.builder().text(it).build() })
                .encryptedContent(trace.encryptedContent)
                .build())
        } else null
        is MonologueTrace.ClaudeThinking, is MonologueTrace.ClaudeRedacted, null -> null
    }

    /** OpenAI-compatible servers might report less than OpenAI, e.g. no usage details at all. */
    internal fun inference(completion: ChatCompletion): Inference {
        val usage = completion._usage().known()
        val input = usage?._promptTokensDetails()?.known()
        val output = usage?._completionTokensDetails()?.known()
        return inference(
            input = usage?._promptTokens()?.known(),
            cacheRead = input?._cachedTokens()?.known(),
            cacheWrite = input?._cacheWriteTokens()?.known(),
            output = usage?._completionTokens()?.known(),
            reasoning = output?._reasoningTokens()?.known(),
            stop = completion.choices().firstOrNull()?._finishReason()?.known()?.asString()?.let { reason ->
                Inference.Stop(
                    kind = when (reason) {
                        "stop", "tool_calls" -> Inference.Stop.Kind.COMPLETED
                        "length" -> Inference.Stop.Kind.TRUNCATED
                        "content_filter" -> Inference.Stop.Kind.REFUSED
                        else -> Inference.Stop.Kind.OTHER
                    },
                    reason = reason
                )
            }
        )
    }

    internal fun inference(response: Response): Inference {
        val usage = response._usage().known()
        val input = usage?._inputTokensDetails()?.known()
        val output = usage?._outputTokensDetails()?.known()
        return inference(
            input = usage?._inputTokens()?.known(),
            cacheRead = input?._cachedTokens()?.known(),
            cacheWrite = input?._cacheWriteTokens()?.known(),
            output = usage?._outputTokens()?.known(),
            reasoning = output?._reasoningTokens()?.known(),
            stop = response._status().known()?.asString()?.let { status ->
                // an incomplete response tells why in its details
                val reason = response._incompleteDetails().known()?._reason()?.known()?.asString()
                when {
                    status == "completed" -> Inference.Stop(Inference.Stop.Kind.COMPLETED, status)
                    status != "incomplete" || reason == null -> Inference.Stop(Inference.Stop.Kind.OTHER, status)
                    reason == "max_output_tokens" -> Inference.Stop(Inference.Stop.Kind.TRUNCATED, reason)
                    reason == "content_filter" -> Inference.Stop(Inference.Stop.Kind.REFUSED, reason)
                    else -> Inference.Stop(Inference.Stop.Kind.OTHER, reason)
                }
            }
        )
    }

    /** The [input] tokens of OpenAI include the cached ones, and its [output] tokens the reasoning ones. */
    private fun inference(
        input: Long?,
        cacheRead: Long?,
        cacheWrite: Long?,
        output: Long?,
        reasoning: Long?,
        stop: Inference.Stop?
    ) = Inference(
        model = modelId,
        provider = provider,
        tokens = buildMap {
            put(Tokens.INPUT, (input ?: 0) - (cacheRead ?: 0) - (cacheWrite ?: 0))
            put(Tokens.CACHE_READ, cacheRead ?: 0)
            put(Tokens.CACHE_WRITE, cacheWrite ?: 0)
            put(Tokens.REASONING, reasoning ?: 0)
            put(Tokens.OUTPUT, (output ?: 0) - (reasoning ?: 0))
        }.filterValues { it > 0 },
        stop = stop
    )

    /**
     * The reasoning becomes a monologue, the content a text, and the first call of a tool the intent,
     * unless it calls another tool, or carries no script, see [IntentTool.intent].
     */
    internal fun phenomena(completion: ChatCompletion): List<Phenomenon> {
        val message = completion.choices().first().message()
        // not part of the OpenAI API, but returned by vLLM
        val reasoning = message._allProperties().let { it["reasoning_content"] ?: it["reasoning"] }
            ?.asString()?.orElse(null)
        val content = message.content().orElse(null)?.takeIf { it.isNotBlank() }
        val intent = message.toolCalls().orElse(emptyList()).firstOrNull()?.function()?.orElse(null)?.let {
            IntentTool.intent(it.function().name(), it.function().arguments())
        }
        if (content == null && intent == null) {
            // what the output tokens were spent on is not perceived, e.g. a field of the endpoint's own
            logger.warn { "Chat completion with neither content nor tool calls: ${jsonMapper().writeValueAsString(message)}" }
        }
        return listOfNotNull(
            reasoning?.takeIf { it.isNotEmpty() }?.let { Monologue(it) },
            content?.let { Phenomenon.Text(it) },
            intent
        )
    }

    /**
     * Reasoning items become monologues, output texts texts, and the first function call the intent,
     * whatever follows it not being perceived, as the model continues as if it perceived the result of the intent.
     */
    internal fun phenomena(response: Response): List<Phenomenon> {
        val phenomena = mutableListOf<Phenomenon>()
        for (item in response.output()) {
            when {
                item.isReasoning() -> item.asReasoning().let { reasoning ->
                    val summary = reasoning.summary().map { it.text() }.filter { it.isNotEmpty() }
                    val monologue = Monologue(summary.joinToString(separator = "\n\n"))
                    monologues[monologue.id] = MonologueTrace.OpenAIReasoning(
                        producedBy = modelId,
                        itemId = reasoning.id(),
                        summary = summary,
                        encryptedContent = reasoning.encryptedContent().orElse(null)
                    )
                    phenomena += monologue
                }
                item.isMessage() -> item.asMessage().content()
                    .filter { it.isOutputText() }
                    .map { it.asOutputText().text() }
                    .filter { it.isNotBlank() }
                    .forEach { phenomena += Phenomenon.Text(it) }
                item.isFunctionCall() -> {
                    val call = item.asFunctionCall()
                    IntentTool.intent(call.name(), call.arguments())?.let { phenomena += it }
                    break
                }
                else -> {}
            }
        }
        return phenomena
    }

}

/** The JSON schema of the input of the tool of intents, see [IntentTool]. */
private val intentParameters: FunctionParameters = FunctionParameters.builder()
    .putAdditionalProperty("type", JsonValue.from("object"))
    .putAdditionalProperty("properties", JsonValue.from(mapOf(IntentTool.PARAMETER to IntentTool.parameter)))
    .putAdditionalProperty("required", JsonValue.from(listOf(IntentTool.PARAMETER)))
    .build()

/** The arguments of the call of the tool of intents, executing the script of this intent. */
private val Phenomenon.Intent.arguments: String get() = jsonMapper().writeValueAsString(mapOf(IntentTool.PARAMETER to script))

/** The texts among these parts of an assistant turn, as a single text, `null` when none. */
private fun List<Phenomenon>.text(): String? =
    filterIsInstance<Phenomenon.Text>().joinToString(separator = "\n") { it.text }.takeIf { it.isNotEmpty() }

/** The text of a tool result, which an OpenAI API accepts only as text. */
private val Perceived.Result.text: String get() =
    parts.filterIsInstance<Perceived.Text>().joinToString(separator = "\n\n") { it.text }

/** The images and documents of a tool result, perceived in a user message following it. */
private val Perceived.Result.attachments: List<Perceived> get() = parts.filterIsInstance<Perceived.Attachment>()

/** The value of this field, `null` when missing, or of unexpected type, instead of throwing. */
private fun <T : Any> JsonField<T>.known(): T? = asKnown().orElse(null)

private fun String.message(role: EasyInputMessage.Role): EasyInputMessage =
    EasyInputMessage.builder().role(role).content(this).build()

/** Text only as a string, as before images were perceived, so that the cached prefix of the cogitation stays the same. */
private fun Turn.User.message(): EasyInputMessage = parts.singleOrNull().let { part ->
    if (part is Perceived.Text) part.text.message(EasyInputMessage.Role.USER)
    else EasyInputMessage.builder()
        .role(EasyInputMessage.Role.USER)
        .contentOfResponseInputMessageContentList(parts.map { it.toInputContent() })
        .build()
}

private fun Perceived.toContentPart(): ChatCompletionContentPart = when (this) {
    is Perceived.Result -> error("A tool result is a message of its own")
    is Perceived.Text -> ChatCompletionContentPart.ofText(ChatCompletionContentPartText.builder().text(text).build())
    is Perceived.Image -> ChatCompletionContentPart.ofImageUrl(ChatCompletionContentPartImage.builder()
        .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder().url(dataUrl).build())
        .build())
    is Perceived.Document -> ChatCompletionContentPart.ofFile(ChatCompletionContentPart.File.builder()
        .file(ChatCompletionContentPart.File.FileObject.builder().filename(FILENAME).fileData(dataUrl).build())
        .build())
}

private fun Perceived.toInputContent(): ResponseInputContent = when (this) {
    is Perceived.Result -> error("A function call output is an item of its own")
    is Perceived.Text -> ResponseInputContent.ofInputText(ResponseInputText.builder().text(text).build())
    is Perceived.Image -> ResponseInputContent.ofInputImage(ResponseInputImage.builder()
        .imageUrl(dataUrl)
        .detail(ResponseInputImage.Detail.AUTO)
        .build())
    is Perceived.Document -> ResponseInputContent.ofInputFile(ResponseInputFile.builder()
        .filename(FILENAME)
        .fileData(dataUrl)
        .build())
}

/** The name of every PDF document, required along its data, which tells the model nothing more than its markup. */
private const val FILENAME = "document.pdf"

private val Effort.openAIEffort: ReasoningEffort get() = when (this) {
    NONE -> ReasoningEffort.NONE
    MINIMAL -> ReasoningEffort.MINIMAL
    LOW -> ReasoningEffort.LOW
    MEDIUM -> ReasoningEffort.MEDIUM
    HIGH -> ReasoningEffort.HIGH
    XHIGH, MAX -> ReasoningEffort.XHIGH
}
