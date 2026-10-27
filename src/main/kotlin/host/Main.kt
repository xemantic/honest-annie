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
import com.xemantic.ai.annie.Actant
import com.xemantic.ai.annie.Cogitation
import com.xemantic.ai.annie.Enunciation
import com.xemantic.ai.annie.Phenomenon
import com.xemantic.ai.annie.Phenomenon.Monologue
import com.xemantic.ai.annie.cognizer.Cognizer
import com.xemantic.ai.annie.cognizer.claude.ClaudeInference
import com.xemantic.ai.annie.cognizer.openai.OpenAIInference
import com.xemantic.ai.annie.cognizer.openai.openAIClient
import com.xemantic.ai.annie.cognizer.openai.openAIProvider
import com.xemantic.ai.annie.constitution
import com.xemantic.kotlin.script.ScriptExecutor
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlinx.coroutines.runBlocking

/**
 * A single cognizer unfolds every cogitation: Claude when `ANTHROPIC_MODEL` is set,
 * the `OPENAI_MODEL` served by the OpenAI-compatible endpoint at `OPENAI_BASE_URL` otherwise.
 *
 * Cogitations are recorded in `ANNIE_COGITATIONS_DIR`, `cogitations` by default, and unfolded
 * by the harness script at the `ANNIE_HARNESS` path, the [defaultHarness] when not set,
 * spending at most `ANNIE_TOKEN_BUDGET` tokens, and stepping at most `ANNIE_PARALLELISM` cogitations
 * at a time, when set, inferring again at most `ANNIE_REINFERENCES` times, 1 by default,
 * in place of a malformed intent, or of none, see [HarnessHost].
 */
fun main(args: Array<String>) {
    val cogitationsDir = Path(System.getenv("ANNIE_COGITATIONS_DIR") ?: "cogitations")
    val harness = System.getenv("ANNIE_HARNESS")?.let { Path(it).readText() } ?: defaultHarness
    val tokenBudget = System.getenv("ANNIE_TOKEN_BUDGET")?.toLong()
    val parallelism = System.getenv("ANNIE_PARALLELISM")?.toInt()
    val maxReinferences = System.getenv("ANNIE_REINFERENCES")?.toInt() ?: 1
    val claudeModel = System.getenv("ANTHROPIC_MODEL")
    val client: AutoCloseable
    val cognizer: Cognizer
    if (claudeModel != null) {
        // the API key is read from ANTHROPIC_API_KEY
        val claude = AnthropicOkHttpClient.fromEnv()
        client = AutoCloseable(claude::close)
        cognizer = ClaudeInference(claude, claudeModel, visibility = Monologue.Visibility.SUMMARY)
    } else {
        val model = System.getenv("OPENAI_MODEL") ?: error("Neither ANTHROPIC_MODEL nor OPENAI_MODEL is set")
        val openAI = openAIClient()
        client = AutoCloseable(openAI::close)
        cognizer = OpenAIInference(openAI, model, provider = openAIProvider)
    }
    client.use {
        val executor = ScriptExecutor()
        runBlocking {
            val output = HarnessHost(
                executor, cognizer, recordingDir = cogitationsDir, tokenBudget = tokenBudget, parallelism = parallelism,
                maxReinferences = maxReinferences
            ).cognize(Cogitation(
                constitution = listOf(constitution(harness)),
                harness = harness,
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
