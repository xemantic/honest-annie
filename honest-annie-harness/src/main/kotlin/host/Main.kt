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

package com.xemantic.ai.annie.host

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.core.http.ProxyAuthenticator
import com.xemantic.ai.annie.cognizer.httpsProxy
import java.net.URI
import com.xemantic.ai.annie.Actant
import com.xemantic.ai.annie.Cogitation
import com.xemantic.ai.annie.Enunciation
import com.xemantic.ai.annie.Phenomenon
import com.xemantic.ai.annie.cognizer.Cognizer
import com.xemantic.ai.annie.cognizer.Effort
import com.xemantic.ai.annie.cognizer.MonologueVisibility
import com.xemantic.ai.annie.cognizer.claude.ClaudeInference
import com.xemantic.ai.annie.cognizer.openai.OpenAIInference
import com.xemantic.ai.annie.cognizer.openai.openAIClient
import com.xemantic.ai.annie.cognizer.openai.openAIProvider
import com.xemantic.ai.annie.constitution
import kotlin.io.path.Path
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/**
 * A single cognizer unfolds every cogitation: Claude when `ANTHROPIC_MODEL` is set,
 * the `OPENAI_MODEL` served by the OpenAI-compatible endpoint at `OPENAI_BASE_URL` otherwise,
 * under the constitution of the family of its model, see [constitution],
 * or the one at the `ANNIE_CONSTITUTION` path, when set, e.g. a variant of a generated one to compare against.
 *
 * Cogitations are recorded in `ANNIE_COGITATIONS_DIR`, `cogitations` by default, and unfolded
 * spending at most `ANNIE_TOKEN_BUDGET` tokens within `ANNIE_TIME_LIMIT` seconds, and stepping at most `ANNIE_PARALLELISM` cogitations
 * at a time, when set, see [HarnessHost].
 * The cognizer reasons with the `ANNIE_EFFORT`, e.g. `xhigh`, see [Effort], the default of its model when not set,
 * inferring at most `ANNIE_MAX_TOKENS` tokens at a time, when set.
 * Claude caches what it reads for `ANNIE_CACHE_TTL`, `5m` by default, or `1h`, see [ClaudeInference],
 * and infers after a refused inference by `ANNIE_FALLBACK_MODEL`, `claude-opus-5-5` by default, or by its own model when `none`.
 * The Python of the intents runs in the virtual environment of `VIRTUAL_ENV`, or of `ANNIE_PYTHON_VENV`,
 * created with `ANNIE_PYTHON` when it does not exist, see `PythonVenv` of the executor.
 */
fun main(args: Array<String>) {
    val cogitationsDir = Path(System.getenv("ANNIE_COGITATIONS_DIR") ?: "cogitations")
    val constitutionFile = System.getenv("ANNIE_CONSTITUTION")?.let { Path(it) }
    val tokenBudget = System.getenv("ANNIE_TOKEN_BUDGET")?.toLong()
    val timeLimit = System.getenv("ANNIE_TIME_LIMIT")?.toLong()?.seconds
    val parallelism = System.getenv("ANNIE_PARALLELISM")?.toInt()
    val effort = System.getenv("ANNIE_EFFORT")?.let { Effort.valueOf(it.uppercase()) }
    val maxTokens = System.getenv("ANNIE_MAX_TOKENS")?.toLong()
    val claudeModel = System.getenv("ANTHROPIC_MODEL")
    val model = claudeModel ?: System.getenv("OPENAI_MODEL") ?: error("Neither ANTHROPIC_MODEL nor OPENAI_MODEL is set")
    val client: AutoCloseable
    val cognizer: Cognizer
    if (claudeModel != null) {
        // the API key is read from ANTHROPIC_API_KEY, the proxy from HTTPS_PROXY, see httpsProxy
        val claude = AnthropicOkHttpClient.builder()
            .fromEnv()
            .apply {
                val baseUrl = System.getenv("ANTHROPIC_BASE_URL") ?: "https://api.anthropic.com"
                httpsProxy(URI(baseUrl).host)?.let { proxy ->
                    proxy(proxy.proxy)
                    if (proxy.user != null) proxyAuthenticator(ProxyAuthenticator.basic(proxy.user, proxy.password.orEmpty()))
                }
            }
            .build()
        client = AutoCloseable(claude::close)
        val cache = when (val ttl = System.getenv("ANNIE_CACHE_TTL")) {
            null, "5m" -> CacheControlEphemeral.Ttl.TTL_5M
            "1h" -> CacheControlEphemeral.Ttl.TTL_1H
            else -> error("ANNIE_CACHE_TTL is either 5m or 1h, not $ttl")
        }
        val fallback = when (val fallback = System.getenv("ANNIE_FALLBACK_MODEL")) {
            null -> "claude-opus-5-5"
            "none" -> null
            else -> fallback
        }
        cognizer = if (maxTokens != null) {
            ClaudeInference(
                claude, claudeModel, maxTokens = maxTokens, effort = effort, visibility = MonologueVisibility.SUMMARY,
                cache = cache, fallback = fallback
            )
        } else {
            ClaudeInference(claude, claudeModel, effort = effort, visibility = MonologueVisibility.SUMMARY, cache = cache, fallback = fallback)
        }
    } else {
        val openAI = openAIClient()
        client = AutoCloseable(openAI::close)
        cognizer = if (maxTokens != null) {
            OpenAIInference(openAI, model, provider = openAIProvider, maxTokens = maxTokens, effort = effort)
        } else {
            OpenAIInference(openAI, model, provider = openAIProvider, effort = effort)
        }
    }
    client.use {
        runBlocking {
            val output = HarnessHost(
                cognizer, recordingDir = cogitationsDir, tokenBudget = tokenBudget, timeLimit = timeLimit, parallelism = parallelism
            ).cognize(Cogitation(
                constitution = listOf(constitution(model, constitutionFile)),
                enunciations = listOf(Enunciation(
                    actant = Actant(
                        type = Actant.Type.HUMAN,
                        name = "user"
                    ),
                    phenomena = listOf(Phenomenon.Text(
                    args.firstOrNull() ?: "What's fibonacci number 45"
                )))
            )))
            println(output)
        }
    }
}
