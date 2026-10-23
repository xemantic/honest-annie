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

/** The ycluster.net OpenAI-compatible endpoint, the `/v1` suffix included, as the OpenAI SDK expects. */
const val YCLUSTER_BASE_URL = "https://dev1.ycluster.net/v1"

/** A client of the ycluster.net endpoint, the API key read from `YCLUSTER_API_KEY`. */
fun yclusterClient(
    apiKey: String = System.getenv("YCLUSTER_API_KEY") ?: error("YCLUSTER_API_KEY is not set")
): OpenAIClient = OpenAIOkHttpClient.builder()
    .apiKey(apiKey)
    .baseUrl(YCLUSTER_BASE_URL)
    .build()

/**
 * Lists the models served by ycluster.net and, when a prompt argument is given,
 * streams a chat completion from the `YCLUSTER_MODEL`.
 *
 * Usage: `./gradlew listModels [-Pprompt="text"]`
 */
fun main(args: Array<String>) {
    val client = yclusterClient()
    try {
        // the ycluster.net proxy omits the `created` field, which is never read here
        val models = client.models().list().data().map { it.id() }.sorted()
        println("Models available at $YCLUSTER_BASE_URL (${models.size}):")
        models.forEach { println("  $it") }

        val input = args.joinToString(" ").ifBlank { return }
        val modelId = System.getenv("YCLUSTER_MODEL") ?: "glm-5.3-flash"
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
