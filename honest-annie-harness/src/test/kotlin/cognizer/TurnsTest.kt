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

package com.xemantic.annie.harness.cognizer

import com.xemantic.annie.harness.content
import com.xemantic.annie.readBytes
import com.xemantic.annie.harness.data
import com.xemantic.annie.harness.monologue
import com.xemantic.annie.harness.testContents
import com.xemantic.annie.harness.text
import com.xemantic.annie.Actant
import com.xemantic.annie.Cogitation
import com.xemantic.annie.Enunciation
import com.xemantic.annie.Inference
import com.xemantic.annie.Phenomenon
import com.xemantic.kotlin.test.assert
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.time.Instant

class TurnsTest {

    private val initiated = Instant.parse("2026-10-24T10:15:30Z")

    private val computer = Actant(type = Actant.Type.COMPUTER, name = "user's computer")

    @Test
    fun `should head first turn with depth of cogitation and each enunciation with its actant`() {
        // given
        val intent = Phenomenon.Intent(script = "6 * 7", id = "call_1")
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(
                Enunciation(actant = Actant.INVOKER, initiated = initiated, phenomena = listOf(text("judge"))),
                Enunciation(actant = Actant.SELF, initiated = initiated, phenomena = listOf(intent)),
                Enunciation(
                    actant = computer,
                    initiated = initiated,
                    respondsTo = intent.id,
                    phenomena = listOf(Phenomenon.Impediment("boom"))
                )
            ),
            depth = 2
        )

        // when
        val turns = cogitation.toTurns(testContents, intent = { it.script }, monologue = { null }, text = { it })

        // then
        assert(turns.size == 3)
        assert((turns[0] as Turn.User).parts == listOf(Perceived.Text("""
            <annie:cogitation depth="2"/>
            <annie:actant type="invoker" name="invoker" initiated="2026-10-24T10:15:30Z"/>
            judge
        """.trimIndent())))
        assert((turns[1] as Turn.Assistant).parts == listOf("6 * 7"))
        assert((turns[2] as Turn.User).parts == listOf(Perceived.Result("call_1", listOf(Perceived.Text("""
            <annie:actant type="computer" name="user's computer" initiated="2026-10-24T10:15:30Z"/>
            <annie:impediment/>
            boom
        """.trimIndent())))))
    }

    @Test
    fun `should perceive impediment of a tool call it could not execute only as the response of the computer`() {
        // given
        val inference = Inference(model = "m", provider = "p", tokens = emptyMap())
        val impediment = Phenomenon.Impediment("Your call carried no script")
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(
                Enunciation(actant = Actant.INVOKER, initiated = initiated, phenomena = listOf(text("task"))),
                Enunciation(
                    actant = Actant.SELF,
                    initiated = initiated,
                    phenomena = listOf(text("Found it."), impediment),
                    inference = inference
                ),
                Enunciation(actant = computer, initiated = initiated, phenomena = listOf(impediment))
            )
        )

        // when
        val turns = cogitation.toTurns(testContents, intent = { it.script }, monologue = { null }, text = { it })

        // then
        assert(turns.size == 3)
        assert((turns[1] as Turn.Assistant).parts == listOf("Found it."))
        assert((turns[2] as Turn.User).parts == listOf(Perceived.Text("""
            <annie:actant type="computer" name="user's computer" initiated="2026-10-24T10:15:30Z"/>
            <annie:impediment/>
            Your call carried no script
        """.trimIndent())))
        // nor is the text along the call the output of the cogitation
        assert(cogitation.output.isEmpty())
    }

    @Test
    fun `should perceive output enunciated by intent and response to it as result of its tool call, and later outcomes after it`() {
        // given
        val intent = Phenomenon.Intent(script = "agency.invoke(\"judge\")", id = "call_1")
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(
                Enunciation(actant = Actant.INVOKER, initiated = initiated, phenomena = listOf(text("task"))),
                Enunciation(actant = Actant.SELF, initiated = initiated, phenomena = listOf(intent)),
                Enunciation(actant = Actant.SELF, initiated = initiated, phenomena = listOf(text("so far"))),
                Enunciation(
                    actant = computer,
                    initiated = initiated,
                    respondsTo = intent.id,
                    phenomena = listOf(text("Spent", "text/plain"))
                ),
                Enunciation(
                    actant = Actant.SELF,
                    initiated = initiated,
                    respondsTo = "c1",
                    phenomena = listOf(text("judged"))
                )
            )
        )

        // when
        val turns = cogitation.toTurns(testContents, intent = { it.script }, monologue = { null }, text = { it })

        // then
        assert(turns.size == 3)
        assert((turns[2] as Turn.User).parts == listOf(
            Perceived.Result("call_1", listOf(Perceived.Text("""
                <annie:actant type="self" name="self" initiated="2026-10-24T10:15:30Z"/>
                so far

                <annie:actant type="computer" name="user's computer" initiated="2026-10-24T10:15:30Z"/>
                <annie:text content-type="text/plain"/>
                Spent
            """.trimIndent()))),
            // the id of the invoked cogitation is never rendered, its outcome perceived in the order of the invocations
            Perceived.Text("""
                <annie:actant type="self" name="self" initiated="2026-10-24T10:15:30Z"/>
                judged
            """.trimIndent())
        ))
    }

    @Test
    fun `should perceive image responding to intent within result of its tool call`() {
        // given
        val intent = Phenomenon.Intent(script = "render()", id = "call_1")
        val image = data(redPixelPng, "image/png")
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(
                Enunciation(actant = Actant.INVOKER, initiated = initiated, phenomena = listOf(text("draw"))),
                Enunciation(actant = Actant.SELF, initiated = initiated, phenomena = listOf(intent)),
                Enunciation(actant = computer, initiated = initiated, respondsTo = intent.id, phenomena = listOf(image))
            )
        )

        // when
        val turns = cogitation.toTurns(testContents, intent = { it.script }, monologue = { null }, text = { it })

        // then
        assert((turns[2] as Turn.User).parts == listOf(Perceived.Result("call_1", listOf(
            Perceived.Text("""
                <annie:actant type="computer" name="user's computer" initiated="2026-10-24T10:15:30Z"/>
                <annie:data content-type="image/png" size="70"/>
            """.trimIndent()),
            Perceived.Image(testContents.readBytes(image), "image/png")
        ))))
    }

    @Test
    fun `should tell tool call of intent never executed as such, since every tool call needs its result`() {
        // given
        val intent = Phenomenon.Intent(script = "6 * 7", id = "call_1")
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(
                Enunciation(actant = Actant.INVOKER, initiated = initiated, phenomena = listOf(text("task"))),
                Enunciation(actant = Actant.SELF, initiated = initiated, phenomena = listOf(intent)),
                Enunciation(
                    actant = Actant(type = Actant.Type.HUMAN, name = "user"),
                    initiated = initiated,
                    phenomena = listOf(text("stop"))
                )
            )
        )

        // when
        val turns = cogitation.toTurns(testContents, intent = { it.script }, monologue = { null }, text = { it })

        // then
        assert((turns[2] as Turn.User).parts == listOf(
            Perceived.Result("call_1", listOf(Perceived.Text(NOT_EXECUTED))),
            Perceived.Text("""
                <annie:actant type="human" name="user" initiated="2026-10-24T10:15:30Z"/>
                stop
            """.trimIndent())
        ))
    }

    @Test
    fun `should perceive image after its markup, and data which is not an image only as markup`() {
        // given
        val image = data(redPixelPng, "image/png")
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(
                Enunciation(
                    actant = Actant(type = Actant.Type.HUMAN, name = "user"),
                    initiated = initiated,
                    phenomena = listOf(
                        text("what is it?"),
                        image,
                        // an error page mislabelled as an image, which an API would reject as one
                        data("<html>".encodeToByteArray(), "image/jpeg"),
                        text("and this?")
                    )
                )
            )
        )

        // when
        val turns = cogitation.toTurns(testContents, intent = { it.script }, monologue = { null }, text = { it })

        // then
        assert(turns.size == 1)
        assert((turns[0] as Turn.User).parts == listOf(
            Perceived.Text("""
                <annie:cogitation depth="0"/>
                <annie:actant type="human" name="user" initiated="2026-10-24T10:15:30Z"/>
                what is it?

                <annie:data content-type="image/png" size="70"/>
            """.trimIndent()),
            Perceived.Image(testContents.readBytes(image), "image/png"),
            Perceived.Text("""
                <annie:data content-type="image/jpeg" size="6"/>

                and this?
            """.trimIndent())
        ))
    }

    @Test
    fun `should perceive image of undeclared content type by its detected media type`() {
        // given
        val image = data(redPixelPng)
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(
                Enunciation(
                    actant = Actant(type = Actant.Type.COMPUTER, name = "user's computer"),
                    initiated = initiated,
                    phenomena = listOf(image)
                )
            )
        )

        // when
        val turns = cogitation.toTurns(testContents, intent = { it.script }, monologue = { null }, text = { it })

        // then
        assert((turns.single() as Turn.User).parts.last() == Perceived.Image(testContents.readBytes(image), "image/png"))
    }

    @Test
    fun `should perceive PDF document after its markup`() {
        // given
        val document = data(minimalPdf, "application/pdf")
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(
                Enunciation(
                    actant = Actant(type = Actant.Type.COMPUTER, name = "user's computer"),
                    initiated = initiated,
                    phenomena = listOf(document)
                )
            )
        )

        // when
        val turns = cogitation.toTurns(testContents, intent = { it.script }, monologue = { null }, text = { it })

        // then
        assert((turns.single() as Turn.User).parts == listOf(
            Perceived.Text("""
                <annie:cogitation depth="0"/>
                <annie:actant type="computer" name="user's computer" initiated="2026-10-24T10:15:30Z"/>
                <annie:data content-type="application/pdf" size="${minimalPdf.size}"/>
            """.trimIndent()),
            Perceived.Document(testContents.readBytes(document))
        ))
    }

    @Test
    fun `should perceive PDF document larger than any API accepts only as markup`() {
        // given
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(
                Enunciation(
                    actant = Actant(type = Actant.Type.COMPUTER, name = "user's computer"),
                    initiated = initiated,
                    phenomena = listOf(data(minimalPdf + ByteArray(MAX_DOCUMENT_SIZE), "application/pdf"))
                )
            )
        )

        // when
        val turns = cogitation.toTurns(testContents, intent = { it.script }, monologue = { null }, text = { it })

        // then
        assert((turns.single() as Turn.User).parts.single() is Perceived.Text)
    }

    @Test
    fun `should perceive image larger than any API accepts only as markup`() {
        // given
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(
                Enunciation(
                    actant = Actant(type = Actant.Type.COMPUTER, name = "user's computer"),
                    initiated = initiated,
                    phenomena = listOf(data(jpegHeader + ByteArray(MAX_IMAGE_SIZE), "image/jpeg"))
                )
            )
        )

        // when
        val turns = cogitation.toTurns(testContents, intent = { it.script }, monologue = { null }, text = { it })

        // then
        assert((turns.single() as Turn.User).parts.single() is Perceived.Text)
    }

    @Test
    fun `should replay text expressed by the cognizer in assistant turns, as commentary of an intent and as an answer`() {
        // given
        val inference = Inference(model = "scripted", provider = "test")
        val intent = Phenomenon.Intent(script = "6 * 7", id = "call_1")
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(
                Enunciation(actant = Actant.INVOKER, initiated = initiated, phenomena = listOf(text("compute"))),
                Enunciation(
                    actant = Actant.SELF,
                    initiated = initiated,
                    phenomena = listOf(monologue("thought"), text("Computing it."), intent),
                    inference = inference
                ),
                Enunciation(actant = computer, initiated = initiated, respondsTo = intent.id, phenomena = listOf(text("42"))),
                Enunciation(
                    actant = Actant.SELF,
                    initiated = initiated,
                    phenomena = listOf(text("The answer is 42.")),
                    inference = inference
                ),
                Enunciation(actant = Actant.INVOKER, initiated = initiated, phenomena = listOf(text("and twice that?")))
            )
        )

        // when
        val turns = cogitation.toTurns(testContents, intent = { it.script }, monologue = { "<${it.content}>" }, text = { it })

        // then
        assert(turns.size == 5)
        assert((turns[1] as Turn.Assistant).parts == listOf("<thought>", "Computing it.", "6 * 7"))
        assert(turns[2] is Turn.User)
        assert((turns[3] as Turn.Assistant).parts == listOf("The answer is 42."))
        assert(((turns[4] as Turn.User).parts.single() as Perceived.Text).text.endsWith("\nand twice that?"))
    }

    @Test
    fun `should perceive output enunciated by an intent in the result of its tool call, not as assistant text`() {
        // given
        val intent = Phenomenon.Intent(script = "agency.enunciate(\"42\")", id = "call_1")
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(
                Enunciation(actant = Actant.INVOKER, initiated = initiated, phenomena = listOf(text("compute"))),
                Enunciation(actant = Actant.SELF, initiated = initiated, phenomena = listOf(intent)),
                Enunciation(actant = Actant.SELF, initiated = initiated, phenomena = listOf(text("42")))
            )
        )

        // when
        val turns = cogitation.toTurns(testContents, intent = { it.script }, monologue = { null }, text = { it })

        // then
        assert(turns.size == 3)
        assert((turns[1] as Turn.Assistant).parts == listOf("agency.enunciate(\"42\")"))
        val result = (turns[2] as Turn.User).parts.single() as Perceived.Result
        assert((result.parts.single() as Perceived.Text).text.endsWith("\n42"))
    }


    @Test
    fun `should cut text of tool result beyond 30000 characters telling how a script reads the rest`() {
        // given
        val intent = Phenomenon.Intent(script = "lines", id = "call_1")
        // 3,001 lines of 9 characters and a line break, the cut falling within the line 3,001
        val long = (1..3_001).joinToString("\n") { "line %04d".format(it) }
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(
                Enunciation(actant = Actant.INVOKER, initiated = initiated, phenomena = listOf(text("task"))),
                Enunciation(actant = Actant.SELF, initiated = initiated, phenomena = listOf(intent)),
                Enunciation(
                    actant = computer,
                    initiated = initiated,
                    respondsTo = intent.id,
                    phenomena = listOf(text("short", "text/plain"), text(long, "text/plain"))
                )
            )
        )

        // when
        val turns = cogitation.toTurns(testContents, intent = { it.script }, monologue = { null }, text = { it })

        // then
        val result = (turns[2] as Turn.User).parts.single() as Perceived.Result
        assert(result.parts == listOf(Perceived.Text(
            "<annie:actant type=\"computer\" name=\"user's computer\" initiated=\"2026-10-24T10:15:30Z\"/>\n" +
                "<annie:text content-type=\"text/plain\"/>\nshort\n\n" +
                "<annie:text content-type=\"text/plain\"/>\n" + long.take(30_000) +
                "\n… 9 more characters omitted, of 3001 lines, read by " +
                "`agency.readText(agency.cogitation().enunciations[2].phenomena[1], 3001..3001)` in a Kotlin script"
        )))
    }

    @Test
    fun `should perceive whole text of task beyond 30000 characters`() {
        // given
        val task = "a".repeat(40_000)
        val cogitation = Cogitation(
            constitution = "",
            enunciations = listOf(Enunciation(actant = Actant.INVOKER, initiated = initiated, phenomena = listOf(text(task))))
        )

        // when
        val turns = cogitation.toTurns(testContents, intent = { it.script }, monologue = { null }, text = { it })

        // then
        assert(((turns.single() as Turn.User).parts.single() as Perceived.Text).text.endsWith(task))
    }

}

/** A PNG of a single red pixel, encoded in Base64. */
const val redPixelPngBase64 =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg=="

/** A PNG of a single red pixel. */
val redPixelPng: ByteArray = Base64.decode(redPixelPngBase64)

/** Bytes detected as a PDF, never parsed by the harness. */
val minimalPdf: ByteArray = "%PDF-1.7\n%%EOF\n".encodeToByteArray()
