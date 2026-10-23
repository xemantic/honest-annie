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
import com.xemantic.ai.annie.cognizer.openai.yclusterClient
import com.xemantic.ai.annie.constitution
import com.xemantic.kotlin.script.ScriptExecutor
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlinx.coroutines.runBlocking

/**
 * A single cognizer unfolds every cogitation: Claude when `ANTHROPIC_MODEL` is set,
 * the `YCLUSTER_MODEL` served by ycluster.net otherwise.
 *
 * Cogitations are recorded in `ANNIE_COGITATIONS_DIR`, `cogitations` by default, and unfolded
 * by the harness script at the `ANNIE_HARNESS` path, the [defaultHarness] when not set.
 */
fun main(args: Array<String>) {
    val cogitationsDir = Path(System.getenv("ANNIE_COGITATIONS_DIR") ?: "cogitations")
    val harness = System.getenv("ANNIE_HARNESS")?.let { Path(it).readText() } ?: defaultHarness
    val claudeModel = System.getenv("ANTHROPIC_MODEL")
    val client: AutoCloseable
    val cognizer: Cognizer
    if (claudeModel != null) {
        // the API key is read from ANTHROPIC_API_KEY
        val claude = AnthropicOkHttpClient.fromEnv()
        client = AutoCloseable(claude::close)
        cognizer = ClaudeInference(claude, claudeModel, visibility = Monologue.Visibility.SUMMARY)
    } else {
        val model = System.getenv("YCLUSTER_MODEL") ?: "glm-5.3-flash"
        val ycluster = yclusterClient()
        client = AutoCloseable(ycluster::close)
        cognizer = OpenAIInference(ycluster, model, provider = "ycluster.net")
    }
    client.use {
        val executor = ScriptExecutor()
        runBlocking {
            val output = HarnessHost(executor, cognizer, recordingDir = cogitationsDir).cognize(Cogitation(
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
