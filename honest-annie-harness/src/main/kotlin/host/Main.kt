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

package com.xemantic.annie.harness.host

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.core.http.ProxyAuthenticator
import com.xemantic.annie.harness.cognizer.httpsProxy
import java.net.URI
import com.xemantic.annie.Actant
import com.xemantic.annie.Cogitation
import com.xemantic.annie.Computer
import com.xemantic.annie.InMemoryContents
import com.xemantic.annie.readText
import com.xemantic.annie.text
import com.xemantic.annie.computer.local.LocalComputerProvider
import com.xemantic.annie.Enunciation
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.harness.cognizer.Cognizer
import com.xemantic.annie.harness.cognizer.Effort
import com.xemantic.annie.harness.cognizer.MonologueVisibility
import com.xemantic.annie.harness.cognizer.claude.ClaudeInference
import com.xemantic.annie.harness.cognizer.openai.OpenAIInference
import com.xemantic.annie.harness.cognizer.openai.openAIClient
import com.xemantic.annie.harness.cognizer.openai.openAIProvider
import com.xemantic.annie.harness.constitution
import kotlin.io.path.Path
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess
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
 * The cogitations may start computers of the `ANNIE_COMPUTER_TYPES`, separated by commas, `default` when not set,
 * the root one running on a computer of the first of them, see [Computer],
 * each computer, whatever its type, being an executor process of the host, see [LocalComputerProvider].
 * The cognizer reasons with the `ANNIE_EFFORT`, e.g. `xhigh`, see [Effort], the default of its model when not set,
 * inferring at most `ANNIE_MAX_TOKENS` tokens at a time, when set.
 * Claude caches what it reads for `ANNIE_CACHE_TTL`, `5m` by default, or `1h`, see [ClaudeInference],
 * and infers after a refused inference by `ANNIE_FALLBACK_MODEL`, `claude-opus-5-5` by default, or by its own model when `none`.
 * The Python of the intents runs in the virtual environment of `VIRTUAL_ENV`, or of `ANNIE_PYTHON_VENV`,
 * created with `ANNIE_PYTHON` when it does not exist, see `PythonVenv` of the executor.
 *
 * The process exits with 1 when the root cogitation rests impeded, e.g. by an inference failing, as one of an invalid API key,
 * so that a script running it tells a task left undone, the impediment still printed as its output.
 */
fun main(args: Array<String>) {
    val cogitationsDir = Path(System.getenv("ANNIE_COGITATIONS_DIR") ?: "cogitations")
    val constitutionFile = System.getenv("ANNIE_CONSTITUTION")?.let { Path(it) }
    val tokenBudget = System.getenv("ANNIE_TOKEN_BUDGET")?.toLong()
    val timeLimit = System.getenv("ANNIE_TIME_LIMIT")?.toLong()?.seconds
    val parallelism = System.getenv("ANNIE_PARALLELISM")?.toInt()
    val computerTypes = System.getenv("ANNIE_COMPUTER_TYPES")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?.ifEmpty { null } ?: listOf("default")
    val effort = System.getenv("ANNIE_EFFORT")?.let { Effort.valueOf(it.uppercase()) }
    val maxTokens = System.getenv("ANNIE_MAX_TOKENS")?.toLong()
    val claudeModel = System.getenv("ANTHROPIC_MODEL")
    val model = claudeModel ?: System.getenv("OPENAI_MODEL") ?: error("Neither ANTHROPIC_MODEL nor OPENAI_MODEL is set")
    // the content of every text and data, of the cogitations the cognizer infers, and the ones the host unfolds
    val contents = InMemoryContents()
    val client: AutoCloseable
    val cognizer: Cognizer
    if (claudeModel != null) {
        // the API key is read from ANTHROPIC_API_KEY, the proxy from HTTPS_PROXY, see httpsProxy:
        // without a key every inference fails, which would only be told once the root cogitation rests impeded
        if (System.getenv("ANTHROPIC_API_KEY") == null && System.getenv("ANTHROPIC_AUTH_TOKEN") == null) {
            error("ANTHROPIC_MODEL is set, but neither ANTHROPIC_API_KEY nor ANTHROPIC_AUTH_TOKEN is")
        }
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
                claude, claudeModel, contents, maxTokens = maxTokens, effort = effort, visibility = MonologueVisibility.SUMMARY,
                cache = cache, fallback = fallback
            )
        } else {
            ClaudeInference(claude, claudeModel, contents, effort = effort, visibility = MonologueVisibility.SUMMARY, cache = cache, fallback = fallback)
        }
    } else {
        val openAI = openAIClient()
        client = AutoCloseable(openAI::close)
        cognizer = if (maxTokens != null) {
            OpenAIInference(openAI, model, contents, provider = openAIProvider, maxTokens = maxTokens, effort = effort)
        } else {
            OpenAIInference(openAI, model, contents, provider = openAIProvider, effort = effort)
        }
    }
    // the intents are executed on computers of the host, each one an executor process of its own, each REPL a session of it
    val impeded = client.use { LocalComputerProvider(computerTypes).use { computers ->
        runBlocking {
            val output = HarnessHost(
                cognizer, recordingDir = cogitationsDir, tokenBudget = tokenBudget, timeLimit = timeLimit, parallelism = parallelism,
                computers = computers, contents = contents
            ).cognize(Cogitation(
                constitution = constitution(model, constitutionFile),
                computer = Computer(type = computerTypes.first()),
                enunciations = listOf(Enunciation(
                    actant = Actant(
                        type = Actant.Type.HUMAN,
                        name = "user"
                    ),
                    phenomena = listOf(contents.text(
                    args.firstOrNull() ?: "What's fibonacci number 45"
                )))
            )))
            // the texts printed with their content, as the evals read them, e.g. `Text(text=…, contentType=…)`
            println(output.toString().let { printed ->
                output.phenomena.filterIsInstance<Phenomenon.Text>().fold(printed) { printed, text ->
                    printed.replace(text.toString(), "Text(text=${contents.readText(text)}, contentType=${text.contentType})")
                }
            })
            output.phenomena.any { it is Phenomenon.Impediment }
        }
    } }
    if (impeded) exitProcess(1)
}
