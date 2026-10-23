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

import com.xemantic.ai.annie.Actant
import com.xemantic.ai.annie.Cogitation
import com.xemantic.ai.annie.Enunciation
import com.xemantic.ai.annie.Inference
import com.xemantic.ai.annie.Phenomenon
import com.xemantic.kotlin.test.assert
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class CogitationRecorderTest {

    private val computer = Actant(type = Actant.Type.COMPUTER, name = "computer")

    private val invocation = Phenomenon.Invocation(id = "sub", prompt = "sub", harness = "", constitution = null)

    private val another = Phenomenon.Invocation(id = "another", prompt = "another", harness = "", constitution = null)

    @Test
    fun `should list recorded phenomena in the order of their enunciation`() = runTest {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val recorder = CogitationRecorder(recordingDir)
        val cogitation = Cogitation(
            constitution = emptyList(),
            harness = "",
            enunciations = listOf(
                Enunciation(
                    actant = Actant(type = Actant.Type.HUMAN, name = "user"),
                    initiated = Instant.parse("2026-10-23T13:14:39Z"),
                    phenomena = listOf(Phenomenon.Text("question"))
                ),
                Enunciation(
                    actant = Actant.SELF,
                    phenomena = listOf(Phenomenon.Monologue(listOf("thinking")), Phenomenon.Intent("1"))
                ),
                Enunciation(
                    actant = computer,
                    phenomena = listOf(Phenomenon.Text("first"), invocation, Phenomenon.Text("second"))
                )
            )
        )

        // when
        recorder.record(cogitation)
        recorder.invoked(invocation).record(Cogitation(
            constitution = emptyList(),
            harness = "",
            enunciations = listOf(Enunciation(
                actant = Actant.SELF,
                initiated = Instant.parse("2026-10-23T13:14:59Z"),
                phenomena = listOf(Phenomenon.Text("sub"))
            ))
        ))

        // then
        val root = recordingDir.listDirectoryEntries().single()
        val files = root.listDirectoryEntries().map { it.name }.sorted() - setOf(
            "cogitation.md", "constitution.md", "harness.kts"
        )
        assert(files == listOf(
            "001-1-human-text.md",
            "002-1-self-monologue.md",
            "002-2-self-intent.kts",
            "003-1-computer-text.md",
            "003-2-2026-10-23T13-14-59Z",
            "003-2-computer-invocation.md",
            "003-3-computer-text.md"
        ))
    }

    @Test
    fun `should index response with position of its request`() = runTest {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val cogitation = Cogitation(
            constitution = emptyList(),
            harness = "",
            enunciations = listOf(
                Enunciation(
                    actant = computer,
                    phenomena = listOf(Phenomenon.Text("invoking"), invocation, another)
                ),
                Enunciation(
                    actant = Actant.SELF,
                    respondsTo = another.id,
                    phenomena = listOf(Phenomenon.Text("another done"))
                )
            )
        )

        // when
        CogitationRecorder(recordingDir).record(cogitation)

        // then
        val index = recordingDir.listDirectoryEntries().single().resolve("cogitation.md").readText()
        assert(index.contains("| 002 | self `self` ↩ 001-3 |"))
    }

    @Test
    fun `should index inference of enunciation`() = runTest {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val cogitation = Cogitation(
            constitution = emptyList(),
            harness = "",
            enunciations = listOf(
                Enunciation(
                    actant = Actant.SELF,
                    phenomena = listOf(Phenomenon.Intent("1")),
                    inference = Inference(
                        model = "claude-opus-5-5",
                        provider = "anthropic",
                        tokens = mapOf(
                            Inference.Tokens.INPUT to 3L,
                            Inference.Tokens.CACHE_WRITE_1H to 1000L,
                            Inference.Tokens.OUTPUT to 40L
                        )
                    )
                ),
                Enunciation(actant = computer, phenomena = listOf(Phenomenon.Text("1")))
            )
        )

        // when
        CogitationRecorder(recordingDir).record(cogitation)

        // then
        val index = recordingDir.listDirectoryEntries().single().resolve("cogitation.md").readText()
        assert(index.contains(
            "| [intent](001-1-self-intent.kts) | `claude-opus-5-5` by anthropic: 3 input, 1000 cache write 1h, 40 output |\n"
        ))
        assert(index.contains("| [text](002-1-computer-text.md) |  |\n"))
    }

}
