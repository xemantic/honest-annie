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

package com.xemantic.annie

import com.xemantic.kotlin.test.assert
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ContentsTest {

    private val contents = InMemoryContents()

    @Test
    fun `should address a text by the SHA-256 of its content`() {
        // when
        val text = contents.text("abc", "text/plain")

        // then
        assert(text == Phenomenon.Text(
            ref = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            length = 3,
            lines = 1,
            contentType = "text/plain"
        ))
        assert(contents.readText(text) == "abc")
    }

    @Test
    fun `should keep the same content once whatever its phenomenon`() {
        // when
        val text = contents.text("same")
        val data = contents.data("same".encodeToByteArray(), "application/octet-stream")

        // then
        assert(text.ref == data.ref)
        assert(contents.readBytes(text).contentEquals(contents.readBytes(data)))
    }

    @Test
    fun `should count the lines of a text as they are read`() {
        // when
        val empty = contents.text("")
        val ended = contents.text("a\nb\n")
        val unended = contents.text("a\nb")

        // then
        assert(empty.lines == 0)
        assert(ended.lines == 2)
        assert(unended.lines == 2)
    }

    @Test
    fun `should read the lines of a text in the order of their ranges`() {
        // given
        val text = contents.text("one\ntwo\nthree\nfour\n")

        // when
        val read = contents.readText(text, listOf(3..10, 1..1))

        // then
        assert(read == "three\nfour\none")
    }

    @Test
    fun `should read data of UTF-8 text as text`() {
        // given
        val data = contents.data("""{"answer": 42}""".encodeToByteArray(), "application/json")

        // when
        val text = contents.readText(data)

        // then
        assert(text == """{"answer": 42}""")
    }

    @Test
    fun `should refuse to read binary data as text`() {
        // given
        val data = contents.data(byteArrayOf(-1, -2, 0), "image/png")

        // when
        val failure = assertFailsWith<IllegalArgumentException> { contents.readText(data) }

        // then
        assert(failure.message!!.contains("agency.readBytes"))
    }

    @Test
    fun `should keep the text of a monologue apart`() {
        // when
        val monologue = contents.monologue("Let me count\nthe lines", id = "monologue-1")

        // then
        assert(monologue == Phenomenon.Monologue(ref = refOf("Let me count\nthe lines".encodeToByteArray()), length = 22, lines = 2, id = "monologue-1"))
        assert(contents.readText(monologue, listOf(2..2)) == "the lines")
    }

    @Test
    fun `should read the content a phenomenon carries itself`() {
        // given
        val intent = Phenomenon.Intent(script = "1 + 1\n2 + 2")

        // when
        val script = contents.readText(intent, listOf(2..2))
        val bytes = contents.readBytes(Phenomenon.Impediment("stuck"))

        // then
        assert(script == "2 + 2")
        assert(bytes.decodeToString() == "stuck")
    }

    @Test
    fun `should fail to read a text whose content is not kept`() {
        // given
        val (text, _) = textOf("elsewhere")

        // when
        val failure = assertFailsWith<NoSuchElementException> { contents.readText(text) }

        // then
        assert(failure.message!!.contains(text.ref))
    }

    @Test
    fun `should send only the contents it keeps`() {
        // given
        val created = contents.text("created")
        val (recalled, _) = textOf("of the cogitation")

        // when
        val sent = contents.of(listOf(created, recalled, Phenomenon.Impediment("stuck")))

        // then
        assert(sent.keys == setOf(created.ref))
    }

    @Test
    fun `should refuse to keep a content under the ref of another one`() {
        // given
        val (text, _) = textOf("original")

        // when
        val failure = assertFailsWith<IllegalStateException> {
            contents.keepAll(mapOf(text.ref to "forged".encodeToByteArray()))
        }

        // then
        assert(failure.message!!.contains(text.ref))
    }

}
