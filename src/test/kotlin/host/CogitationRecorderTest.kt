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

    private val invocation = Phenomenon.Invocation(id = "sub", prompt = "sub", constitution = "", harness = "")

    private val another = Phenomenon.Invocation(id = "another", prompt = "another", constitution = "", harness = "")

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
            "cogitation.md", "constitution.md", "harness.kts", "inferences.json"
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

    private fun inferred(
        tokens: Map<Inference.Tokens, Long>,
        stop: Inference.Stop? = Inference.Stop(Inference.Stop.Kind.COMPLETED, "end_turn")
    ) = Enunciation(
        actant = Actant.SELF,
        initiated = Instant.parse("2026-10-23T13:14:40Z"),
        finalized = Instant.parse("2026-10-23T13:14:42Z"),
        phenomena = listOf(Phenomenon.Intent("1")),
        inference = Inference(model = "claude-opus-5-5", provider = "anthropic", tokens = tokens, stop = stop)
    )

    @Test
    fun `should record inferences as JSON`() = runTest {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val cogitation = Cogitation(
            constitution = emptyList(),
            harness = "",
            enunciations = listOf(
                inferred(mapOf(
                    Inference.Tokens.OUTPUT to 40L,
                    Inference.Tokens.INPUT to 3L,
                    Inference.Tokens.CACHE_WRITE_1H to 1000L
                )),
                Enunciation(actant = computer, phenomena = listOf(Phenomenon.Text("1")))
            )
        )

        // when
        CogitationRecorder(recordingDir).record(cogitation)

        // then
        val json = recordingDir.listDirectoryEntries().single().resolve("inferences.json").readText()
        assert(json == """
            {
              "inferences": [
                {
                  "enunciation": "001",
                  "initiated": "2026-10-23T13:14:40Z",
                  "finalized": "2026-10-23T13:14:42Z",
                  "model": "claude-opus-5-5",
                  "provider": "anthropic",
                  "tokens": {"input": 3, "cache_write_1h": 1000, "output": 40},
                  "total": 1043,
                  "stop": {"kind": "completed", "reason": "end_turn"}
                }
              ],
              "invoked": {},
              "total": {"inferences": 1, "tokens": {"input": 3, "cache_write_1h": 1000, "output": 40}, "total": 1043}
            }

        """.trimIndent())
    }

    @Test
    fun `should total inferences including invoked cogitations`() = runTest {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val recorder = CogitationRecorder(recordingDir)
        recorder.record(Cogitation(
            constitution = emptyList(),
            harness = "",
            enunciations = listOf(
                inferred(mapOf(Inference.Tokens.INPUT to 3L, Inference.Tokens.CACHE_WRITE_1H to 1000L)),
                Enunciation(actant = computer, phenomena = listOf(invocation))
            )
        ))
        val invoked = recorder.invoked(invocation)

        // when
        invoked.record(Cogitation(
            constitution = emptyList(),
            harness = "",
            enunciations = listOf(
                Enunciation(
                    actant = Actant.INVOKER,
                    initiated = Instant.parse("2026-10-23T13:14:59Z"),
                    phenomena = listOf(Phenomenon.Text("sub"))
                ),
                inferred(mapOf(Inference.Tokens.INPUT to 2L, Inference.Tokens.CACHE_READ to 1000L))
            )
        ))

        // then
        val root = recordingDir.listDirectoryEntries().single()
        val json = root.resolve("inferences.json").readText()
        assert(json.contains("""
              "invoked": {
                "002-1-2026-10-23T13-14-59Z": {"inferences": 1, "tokens": {"input": 2, "cache_read": 1000}, "total": 1002}
              },
              "total": {"inferences": 2, "tokens": {"input": 5, "cache_read": 1000, "cache_write_1h": 1000}, "total": 2005}
        """.trimIndent().prependIndent("  ")))
        val index = root.resolve("cogitation.md").readText()
        assert(index.endsWith(
            "\n**Total** of 2 inferences, 1 in invoked cogitations: 5 input, 1000 cache read, 1000 cache write 1h, 2005 tokens\n"
        ))
    }

    @Test
    fun `should index stop of inference`() = runTest {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val cogitation = Cogitation(
            constitution = emptyList(),
            harness = "",
            enunciations = listOf(
                inferred(mapOf(Inference.Tokens.INPUT to 3L, Inference.Tokens.OUTPUT to 1L)),
                inferred(
                    mapOf(Inference.Tokens.INPUT to 3L, Inference.Tokens.OUTPUT to 2L),
                    stop = Inference.Stop(Inference.Stop.Kind.REFUSED, "refusal")
                )
            )
        )

        // when
        CogitationRecorder(recordingDir).record(cogitation)

        // then
        val index = recordingDir.listDirectoryEntries().single().resolve("cogitation.md").readText()
        assert(index.contains("| `claude-opus-5-5` by anthropic: 3 input, 1 output · `end_turn` |\n"))
        assert(index.contains("| `claude-opus-5-5` by anthropic: 3 input, 2 output · **refused** `refusal` |\n"))
    }

}
