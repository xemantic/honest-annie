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

import com.xemantic.annie.harness.content
import com.xemantic.annie.harness.data
import com.xemantic.annie.harness.monologue
import com.xemantic.annie.harness.plus
import com.xemantic.annie.harness.testContents
import com.xemantic.annie.harness.text
import com.xemantic.annie.Actant
import com.xemantic.annie.Cogitation
import com.xemantic.annie.Edit
import com.xemantic.annie.Enunciation
import com.xemantic.annie.Inference
import com.xemantic.annie.Phenomenon
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

    private val intent = Phenomenon.Intent(script = "agency.invoke(\"sub\")")

    private val another = Phenomenon.Intent(script = "agency.invoke(\"another\")")

    @Test
    fun `should list recorded phenomena in the order of their enunciation`() = runTest {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val recorder = CogitationRecorder(recordingDir, testContents)
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(
                Enunciation(
                    actant = Actant(type = Actant.Type.HUMAN, name = "user"),
                    initiated = Instant.parse("2026-10-23T13:14:39Z"),
                    phenomena = listOf(text("question"))
                ),
                Enunciation(
                    actant = Actant.SELF,
                    phenomena = listOf(monologue("thinking"), intent)
                ),
                Enunciation(
                    actant = computer,
                    phenomena = listOf(text("first"), text("second"))
                )
            )
        )

        // when
        recorder.record(cogitation)
        recorder.invoked("sub", intent.id).record(Cogitation(
            constitution = "",
            enunciations = listOf(Enunciation(
                actant = Actant.SELF,
                initiated = Instant.parse("2026-10-23T13:14:59Z"),
                phenomena = listOf(text("sub"))
            ))
        ))

        // then
        val root = recordingDir.listDirectoryEntries().single()
        val files = root.listDirectoryEntries().map { it.name }.sorted() - setOf(
            "cogitation.md", "constitution.md", "inferences.json"
        )
        assert(files == listOf(
            "001-1-human-text.md",
            "002-1-self-monologue.md",
            "002-2-2026-10-23T13-14-59Z",
            "002-2-self-intent.kts",
            "003-1-computer-text.md",
            "003-2-computer-text.md"
        ))
    }

    @Test
    fun `should record script of intent in file of its language`() = runTest {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(Enunciation(
                actant = Actant.SELF,
                phenomena = listOf(
                    Phenomenon.Intent(script = "6 * 7"),
                    Phenomenon.Intent(language = Phenomenon.Intent.Language.PYTHON, script = "print(42)"),
                    Phenomenon.Intent(language = Phenomenon.Intent.Language.BASH, script = "echo 42")
                )
            ))
        )

        // when
        CogitationRecorder(recordingDir, testContents).record(cogitation)

        // then
        val root = recordingDir.listDirectoryEntries().single()
        val files = root.listDirectoryEntries().map { it.name }.sorted() - setOf(
            "cogitation.md", "constitution.md", "inferences.json"
        )
        assert(files == listOf("001-1-self-intent.kts", "001-2-self-intent.py", "001-3-self-intent.sh"))
        assert(root.resolve("001-2-self-intent.py").readText() == "print(42)")
    }

    @Test
    fun `should index response with position of its request`() = runTest {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(
                Enunciation(
                    actant = Actant.SELF,
                    phenomena = listOf(text("invoking"), intent, another)
                ),
                Enunciation(
                    actant = computer,
                    respondsTo = another.id,
                    phenomena = listOf(text("another done"))
                )
            )
        )

        // when
        CogitationRecorder(recordingDir, testContents).record(cogitation)

        // then
        val index = recordingDir.listDirectoryEntries().single().resolve("cogitation.md").readText()
        assert(index.contains("| 002 | computer `computer` ↩ 001-3 |"))
    }

    @Test
    fun `should index outcome of invoked cogitation with position of intent invoking it`() = runTest {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val recorder = CogitationRecorder(recordingDir, testContents)
        val invoking = Cogitation(
            constitution = "",
            enunciations = listOf(
                Enunciation(actant = Actant.SELF, phenomena = listOf(text("invoking"), intent)),
                Enunciation(actant = computer, respondsTo = intent.id, phenomena = listOf(text("invoked")))
            )
        )
        recorder.record(invoking)
        recorder.invoked("sub", intent.id)

        // when
        recorder.record(invoking + Enunciation(
            actant = Actant.SELF,
            respondsTo = "sub",
            phenomena = listOf(text("sub done"))
        ))

        // then
        val index = recordingDir.listDirectoryEntries().single().resolve("cogitation.md").readText()
        assert(index.contains("| 003 | self `self` ↩ 001-2 |"))
    }

    @Test
    fun `should index inference of enunciation`() = runTest {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(
                Enunciation(
                    actant = Actant.SELF,
                    phenomena = listOf(Phenomenon.Intent(script = "1")),
                    inference = Inference(
                        model = "claude-opus-5-5",
                        provider = "anthropic",
                        tokens = mapOf(
                            "input" to 3L,
                            "cacheWrite1h" to 1000L,
                            "output" to 40L
                        )
                    )
                ),
                Enunciation(actant = computer, phenomena = listOf(text("1")))
            )
        )

        // when
        CogitationRecorder(recordingDir, testContents).record(cogitation)

        // then
        val index = recordingDir.listDirectoryEntries().single().resolve("cogitation.md").readText()
        assert(index.contains(
            "| [intent](001-1-self-intent.kts) | `claude-opus-5-5` by anthropic: 3 input, 1000 cache write 1h, 40 output |\n"
        ))
        assert(index.contains("| [text](002-1-computer-text.md) |  |\n"))
    }

    private fun inferred(
        tokens: Map<String, Long>,
        stop: Inference.Stop? = Inference.Stop(Inference.Stop.Kind.COMPLETED, "end_turn")
    ) = Enunciation(
        actant = Actant.SELF,
        initiated = Instant.parse("2026-10-23T13:14:40Z"),
        finalized = Instant.parse("2026-10-23T13:14:42Z"),
        phenomena = listOf(Phenomenon.Intent(script = "1")),
        inference = Inference(model = "claude-opus-5-5", provider = "anthropic", tokens = tokens, stop = stop)
    )

    @Test
    fun `should describe token categories unknown to the harness after the known ones`() {
        // given
        val inference = inferred(mapOf("output" to 2L, "audioInput" to 5L, "input" to 1L, "cacheRead" to 3L)).inference!!

        // when
        val description = inference.tokensDescription()

        // then
        assert(description == "1 input, 3 cache read, 2 output, 5 audio input")
    }

    @Test
    fun `should record inferences as JSON`() = runTest {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(
                inferred(mapOf(
                    "output" to 40L,
                    "input" to 3L,
                    "cacheWrite1h" to 1000L
                )),
                Enunciation(actant = computer, phenomena = listOf(text("1")))
            )
        )

        // when
        CogitationRecorder(recordingDir, testContents).record(cogitation)

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
                  "tokens": {"input": 3, "cacheWrite1h": 1000, "output": 40},
                  "total": 1043,
                  "cost": "0.008812",
                  "stop": {"kind": "completed", "reason": "end_turn"}
                }
              ],
              "invoked": {},
              "total": {"inferences": 1, "tokens": {"input": 3, "cacheWrite1h": 1000, "output": 40}, "total": 1043, "cost": "0.008812"}
            }

        """.trimIndent())
    }

    @Test
    fun `should not cost inferences of a model of unknown prices`() = runTest {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(
                inferred(mapOf("input" to 3L, "output" to 1L)).let {
                    it.copy(inference = it.inference!!.copy(model = "deepseek-v4_1-flash", provider = "llm.example.com"))
                }
            )
        )

        // when
        CogitationRecorder(recordingDir, testContents).record(cogitation)

        // then
        val root = recordingDir.listDirectoryEntries().single()
        assert("cost" !in root.resolve("inferences.json").readText())
        assert(root.resolve("cogitation.md").readText().endsWith(": 3 input, 1 output, 4 tokens\n"))
    }

    @Test
    fun `should total inferences including invoked cogitations`() = runTest {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val recorder = CogitationRecorder(recordingDir, testContents)
        recorder.record(Cogitation(
            constitution = "",
            enunciations = listOf(
                inferred(mapOf("input" to 3L, "cacheWrite1h" to 1000L)).copy(phenomena = listOf(intent)),
                Enunciation(actant = computer, respondsTo = intent.id, phenomena = listOf(text("invoked")))
            )
        ))
        val invoked = recorder.invoked("sub", intent.id)

        // when
        invoked.record(Cogitation(
            constitution = "",
            enunciations = listOf(
                Enunciation(
                    actant = Actant.INVOKER,
                    initiated = Instant.parse("2026-10-23T13:14:59Z"),
                    phenomena = listOf(text("sub"))
                ),
                inferred(mapOf("input" to 2L, "cacheRead" to 1000L))
            )
        ))

        // then
        val root = recordingDir.listDirectoryEntries().single()
        val json = root.resolve("inferences.json").readText()
        assert(json.contains("""
              "invoked": {
                "001-1-2026-10-23T13-14-59Z": {"inferences": 1, "tokens": {"input": 2, "cacheRead": 1000}, "total": 1002, "cost": "0.000208"}
              },
              "total": {"inferences": 2, "tokens": {"input": 5, "cacheRead": 1000, "cacheWrite1h": 1000}, "total": 2005, "cost": "0.00822"}
        """.trimIndent().prependIndent("  ")))
        val index = root.resolve("cogitation.md").readText()
        assert(index.endsWith(
            "\n**Total** of 2 inferences, 1 in invoked cogitations: 5 input, 1000 cache read, 1000 cache write 1h, 2005 tokens, " +
                "costing 0.00822 USD at list prices\n"
        ))
    }

    @Test
    fun `should record discarded inferences before the enunciation following them`() = runTest {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val recorder = CogitationRecorder(recordingDir, testContents)
        val question = Enunciation(
            actant = Actant(type = Actant.Type.HUMAN, name = "user"),
            initiated = Instant.parse("2026-10-23T13:14:39Z"),
            phenomena = listOf(text("question"))
        )
        recorder.record(Cogitation(constitution = "", enunciations = listOf(question)))
        val discarded = inferred(mapOf("input" to 3L, "output" to 1L))
            .copy(phenomena = listOf(monologue("thinking"), Phenomenon.Intent(script = "<invoke name=\"fetch\">")))

        // when
        recorder.record(
            Cogitation(
                constitution = "",
                enunciations = listOf(question, inferred(mapOf("input" to 3L, "output" to 2L)))
            ),
            discarded = listOf(discarded)
        )

        // then
        val root = recordingDir.listDirectoryEntries().single()
        val files = root.listDirectoryEntries().map { it.name }.sorted() - setOf(
            "cogitation.md", "constitution.md", "inferences.json"
        )
        assert(files == listOf(
            "001-1-human-text.md",
            "002-1-self-intent.kts",
            "002-discarded-1-1-self-monologue.md",
            "002-discarded-1-2-self-intent.kts"
        ))
        assert(root.resolve("002-discarded-1-2-self-intent.kts").readText() == "<invoke name=\"fetch\">")
        val index = root.resolve("cogitation.md").readText()
        val discardedRow = index.indexOf("| 002-discarded-1 | self `self` |")
        assert(discardedRow >= 0 && discardedRow < index.indexOf("| 002 | self `self` |"))
        val json = root.resolve("inferences.json").readText()
        assert(json.contains("\"enunciation\": \"002-discarded-1\""))
        assert(json.contains("\"total\": {\"inferences\": 2, \"tokens\": {\"input\": 6, \"output\": 3}, \"total\": 9, \"cost\": \"0.000084\"}"))
    }

    @Test
    fun `should index stop of inference`() = runTest {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(
                inferred(mapOf("input" to 3L, "output" to 1L)),
                inferred(
                    mapOf("input" to 3L, "output" to 2L),
                    stop = Inference.Stop(Inference.Stop.Kind.REFUSED, "refusal", category = "cyber")
                )
            )
        )

        // when
        CogitationRecorder(recordingDir, testContents).record(cogitation)

        // then
        val dir = recordingDir.listDirectoryEntries().single()
        val index = dir.resolve("cogitation.md").readText()
        assert(index.contains("| `claude-opus-5-5` by anthropic: 3 input, 1 output · `end_turn` |\n"))
        assert(index.contains("| `claude-opus-5-5` by anthropic: 3 input, 2 output · **refused** `refusal` (cyber) |\n"))
        val json = dir.resolve("inferences.json").readText()
        assert(json.contains("\"stop\": {\"kind\": \"completed\", \"reason\": \"end_turn\"}"))
        assert(json.contains("\"stop\": {\"kind\": \"refused\", \"reason\": \"refusal\", \"category\": \"cyber\"}"))
    }

    @Test
    fun `should record edits of intent next to its script, as their JSON`() = runTest {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(Enunciation(
                actant = Actant.SELF,
                initiated = Instant.parse("2026-10-23T13:14:39Z"),
                phenomena = listOf(Phenomenon.Intent(
                    edits = listOf(
                        Edit(path = "/app/f.py", old = "    return 1\n", new = "    \"\"\"Docstring.\"\"\"\n"),
                        Edit(path = "/app/g.py", new = "x\n", old = null, all = false)
                    ),
                    script = "1"
                ))
            ))
        )

        // when
        CogitationRecorder(recordingDir, testContents).record(cogitation)

        // then
        val root = recordingDir.listDirectoryEntries().single()
        assert(root.resolve("001-1-self-intent.kts").readText() == "1")
        assert(root.resolve("001-1-self-edits.json").readText() ==
                "[\n  {\"path\": \"/app/f.py\", \"old\": \"    return 1\\n\", \"new\": \"    \\\"\\\"\\\"Docstring.\\\"\\\"\\\"\\n\"},\n" +
                "  {\"path\": \"/app/g.py\", \"new\": \"x\\n\"}\n]\n")
        assert("[intent](001-1-self-intent.kts) [edits](001-1-self-edits.json)" in root.resolve("cogitation.md").readText())
    }

}
