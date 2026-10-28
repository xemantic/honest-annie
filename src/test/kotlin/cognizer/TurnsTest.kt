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

package com.xemantic.ai.annie.cognizer

import com.xemantic.ai.annie.Actant
import com.xemantic.ai.annie.Cogitation
import com.xemantic.ai.annie.Enunciation
import com.xemantic.ai.annie.Phenomenon
import com.xemantic.kotlin.test.assert
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.time.Instant

class TurnsTest {

    private val initiated = Instant.parse("2026-10-24T10:15:30Z")

    @Test
    fun `should head first turn with depth of cogitation and each enunciation with its actant`() {
        // given
        val cogitation = Cogitation(
            constitution = emptyList(),
            harness = "",
            enunciations = listOf(
                Enunciation(actant = Actant.INVOKER, initiated = initiated, phenomena = listOf(Phenomenon.Text("judge"))),
                Enunciation(actant = Actant.SELF, initiated = initiated, phenomena = listOf(Phenomenon.Intent("6 * 7"))),
                Enunciation(
                    actant = Actant(type = Actant.Type.COMPUTER, name = "user's computer"),
                    initiated = initiated,
                    phenomena = listOf(Phenomenon.Impediment("boom"))
                )
            ),
            depth = 2
        )

        // when
        val turns = cogitation.toTurns(intent = { it.script }, monologue = { null })

        // then
        assert(turns.size == 3)
        assert((turns[0] as Turn.User).parts == listOf(Perceived.Text("""
            <annie:cogitation depth="2"/>
            <annie:actant type="invoker" name="invoker" initiated="2026-10-24T10:15:30Z"/>
            judge
        """.trimIndent())))
        assert((turns[1] as Turn.Assistant).parts == listOf("6 * 7"))
        assert((turns[2] as Turn.User).parts == listOf(Perceived.Text("""
            <annie:actant type="computer" name="user's computer" initiated="2026-10-24T10:15:30Z"/>
            <annie:impediment/>
            boom
        """.trimIndent())))
    }

    @Test
    fun `should perceive image after its markup, and data which is not an image only as markup`() {
        // given
        val image = Phenomenon.Data(redPixelPng, "image/png")
        val cogitation = Cogitation(
            constitution = emptyList(),
            harness = "",
            enunciations = listOf(
                Enunciation(
                    actant = Actant(type = Actant.Type.HUMAN, name = "user"),
                    initiated = initiated,
                    phenomena = listOf(
                        Phenomenon.Text("what is it?"),
                        image,
                        // an error page mislabelled as an image, which an API would reject as one
                        Phenomenon.Data("<html>".encodeToByteArray(), "image/jpeg"),
                        Phenomenon.Text("and this?")
                    )
                )
            )
        )

        // when
        val turns = cogitation.toTurns(intent = { it.script }, monologue = { null })

        // then
        assert(turns.size == 1)
        assert((turns[0] as Turn.User).parts == listOf(
            Perceived.Text("""
                <annie:cogitation depth="0"/>
                <annie:actant type="human" name="user" initiated="2026-10-24T10:15:30Z"/>
                what is it?

                <annie:data content-type="image/png" size="70"/>
            """.trimIndent()),
            Perceived.Image(image, "image/png"),
            Perceived.Text("""
                <annie:data content-type="image/jpeg" size="6"/>

                and this?
            """.trimIndent())
        ))
    }

    @Test
    fun `should perceive image of undeclared content type by its detected media type`() {
        // given
        val image = Phenomenon.Data(redPixelPng)
        val cogitation = Cogitation(
            constitution = emptyList(),
            harness = "",
            enunciations = listOf(
                Enunciation(
                    actant = Actant(type = Actant.Type.COMPUTER, name = "user's computer"),
                    initiated = initiated,
                    phenomena = listOf(image)
                )
            )
        )

        // when
        val turns = cogitation.toTurns(intent = { it.script }, monologue = { null })

        // then
        assert((turns.single() as Turn.User).parts.last() == Perceived.Image(image, "image/png"))
    }

    @Test
    fun `should perceive PDF document after its markup`() {
        // given
        val document = Phenomenon.Data(minimalPdf, "application/pdf")
        val cogitation = Cogitation(
            constitution = emptyList(),
            harness = "",
            enunciations = listOf(
                Enunciation(
                    actant = Actant(type = Actant.Type.COMPUTER, name = "user's computer"),
                    initiated = initiated,
                    phenomena = listOf(document)
                )
            )
        )

        // when
        val turns = cogitation.toTurns(intent = { it.script }, monologue = { null })

        // then
        assert((turns.single() as Turn.User).parts == listOf(
            Perceived.Text("""
                <annie:cogitation depth="0"/>
                <annie:actant type="computer" name="user's computer" initiated="2026-10-24T10:15:30Z"/>
                <annie:data content-type="application/pdf" size="${minimalPdf.size}"/>
            """.trimIndent()),
            Perceived.Document(document)
        ))
    }

    @Test
    fun `should perceive PDF document larger than any API accepts only as markup`() {
        // given
        val cogitation = Cogitation(
            constitution = emptyList(),
            harness = "",
            enunciations = listOf(
                Enunciation(
                    actant = Actant(type = Actant.Type.COMPUTER, name = "user's computer"),
                    initiated = initiated,
                    phenomena = listOf(Phenomenon.Data(minimalPdf + ByteArray(MAX_DOCUMENT_SIZE), "application/pdf"))
                )
            )
        )

        // when
        val turns = cogitation.toTurns(intent = { it.script }, monologue = { null })

        // then
        assert((turns.single() as Turn.User).parts.single() is Perceived.Text)
    }

    @Test
    fun `should perceive image larger than any API accepts only as markup`() {
        // given
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val cogitation = Cogitation(
            constitution = emptyList(),
            harness = "",
            enunciations = listOf(
                Enunciation(
                    actant = Actant(type = Actant.Type.COMPUTER, name = "user's computer"),
                    initiated = initiated,
                    phenomena = listOf(Phenomenon.Data(jpegHeader + ByteArray(MAX_IMAGE_SIZE), "image/jpeg"))
                )
            )
        )

        // when
        val turns = cogitation.toTurns(intent = { it.script }, monologue = { null })

        // then
        assert((turns.single() as Turn.User).parts.single() is Perceived.Text)
    }

}

/** A PNG of a single red pixel, encoded in Base64. */
const val redPixelPngBase64 =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg=="

/** A PNG of a single red pixel. */
val redPixelPng: ByteArray = Base64.decode(redPixelPngBase64)

/** Bytes detected as a PDF, never parsed by the harness. */
val minimalPdf: ByteArray = "%PDF-1.7\n%%EOF\n".encodeToByteArray()
