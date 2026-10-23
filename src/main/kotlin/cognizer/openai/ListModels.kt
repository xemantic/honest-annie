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
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import java.net.URI

/**
 * The OpenAI-compatible endpoint at `OPENAI_BASE_URL`, the `/v1` suffix included, as the OpenAI SDK expects,
 * the official OpenAI API when not set.
 */
val openAIBaseUrl: String = System.getenv("OPENAI_BASE_URL") ?: "https://api.openai.com/v1"

/** Who serves the [openAIBaseUrl] endpoint: `openai`, or the host of any other endpoint. */
val openAIProvider: String = System.getenv("OPENAI_BASE_URL")?.let { URI(it).host } ?: "openai"

/** A client of the [openAIBaseUrl] endpoint, the API key read from `OPENAI_API_KEY`. */
fun openAIClient(
    apiKey: String = System.getenv("OPENAI_API_KEY") ?: error("OPENAI_API_KEY is not set")
): OpenAIClient = OpenAIOkHttpClient.builder()
    .apiKey(apiKey)
    .baseUrl(openAIBaseUrl)
    .build()

/**
 * Lists the models served at the [openAIBaseUrl] and, when a prompt argument is given,
 * streams a chat completion from the `OPENAI_MODEL`.
 *
 * Usage: `./gradlew listModels [-Pprompt="text"]`
 */
fun main(args: Array<String>) {
    val client = openAIClient()
    try {
        // some OpenAI-compatible endpoints omit the `created` field, which is never read here
        val models = client.models().list().data().map { it.id() }.sorted()
        println("Models available at $openAIBaseUrl (${models.size}):")
        models.forEach { println("  $it") }

        val input = args.joinToString(" ").ifBlank { return }
        val modelId = System.getenv("OPENAI_MODEL") ?: error("OPENAI_MODEL is not set")
        println("\nStreaming from $modelId:\n")
        val params = ChatCompletionCreateParams.builder()
            .model(modelId)
            .maxCompletionTokens(512)
            .addSystemMessage("You are Honest Annie. Answer briefly.")
            .addUserMessage(input)
            .build()
        client.chat().completions().createStreaming(params).use { stream ->
            stream.stream().forEach { chunk ->
                chunk.choices().forEach { choice ->
                    choice.delta()._allProperties()["reasoning_content"]?.asString()?.ifPresent(::print)
                    choice.delta().content().ifPresent(::print)
                }
            }
        }
        println()
    } finally {
        client.close()
    }
}
