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

package com.xemantic.ai.annie

import com.xemantic.kotlin.test.assert
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertFailsWith

class JsonNavigationTest {

    private val gene: JsonElement = Json.parseToJsonElement(
        """
        {
          "id": "ENSG00000115263",
          "start": 162142877,
          "length": 9527.5,
          "description": null,
          "Transcript": [
            {"id": "ENST1", "is_canonical": false},
            {"id": "ENST2", "is_canonical": true, "Translation": {"id": "ENSP2", "length": 180}}
          ]
        }
        """
    )

    @Test
    fun `should navigate JSON by keys and indices`() {
        // when
        val translation = gene["Transcript"][1]["Translation"]["id"].string

        // then
        assert(translation == "ENSP2")
    }

    @Test
    fun `should read primitives of JSON`() {
        // when
        val start = gene["start"].long
        val length = gene["length"].double
        val translationLength = gene["Transcript"][1]["Translation"]["length"].int
        val canonical = gene["Transcript"][1]["is_canonical"].boolean

        // then
        assert(start == 162142877L)
        assert(length == 9527.5)
        assert(translationLength == 180)
        assert(canonical)
    }

    @Test
    fun `should iterate JSON array as list`() {
        // when
        val canonical = gene["Transcript"].list.first { it["is_canonical"].boolean }["id"].string

        // then
        assert(canonical == "ENST2")
    }

    @Test
    fun `should list keys of JSON object`() {
        // when
        val keys = gene["Transcript"][1].keys

        // then
        assert(keys == setOf("id", "is_canonical", "Translation"))
    }

    @Test
    fun `should get null for absent or null value of optional key`() {
        // when
        val absent = gene["Transcript"][0].getOrNull("Translation")
        val nullValue = gene.getOrNull("description")
        val present = gene["Transcript"][1].getOrNull("Translation")?.get("id")?.string

        // then
        assert(absent == null)
        assert(nullValue == null)
        assert(present == "ENSP2")
    }

    @Test
    fun `should fail on absent key naming the keys present`() {
        // when
        val exception = assertFailsWith<NoSuchElementException> {
            gene["Transcript"][1]["Translations"]
        }

        // then
        assert(exception.message == "No key \"Translations\" in JSON object of keys: id, is_canonical, Translation")
    }

    @Test
    fun `should fail on index out of JSON array naming its size`() {
        // when
        val exception = assertFailsWith<IndexOutOfBoundsException> {
            gene["Transcript"][2]
        }

        // then
        assert(exception.message == "No index 2 in JSON array of size 2")
    }

    @Test
    fun `should fail on reading JSON of another kind than expected`() {
        // when
        val keyOfArray = assertFailsWith<IllegalStateException> { gene["Transcript"]["id"] }
        val stringOfObject = assertFailsWith<IllegalStateException> { gene["Transcript"][1].string }
        val stringOfNull = assertFailsWith<IllegalStateException> { gene["description"].string }
        val intOfString = assertFailsWith<IllegalStateException> { gene["id"].int }

        // then
        assert(keyOfArray.message == "Key \"id\" of JSON array, not object")
        assert(stringOfObject.message!!.startsWith("String of JSON object, not primitive: {\"id\":\"ENST2\""))
        assert(stringOfNull.message == "String of JSON null")
        assert(intOfString.message == "Int of JSON \"ENSG00000115263\"")
    }

    @Test
    fun `should read 1 and 0 as booleans, as Ensembl encodes them`() {
        // given
        val transcript = Json.parseToJsonElement("""{"is_canonical": 1, "is_obsolete": 0}""")

        // when
        val canonical = transcript["is_canonical"].boolean
        val obsolete = transcript["is_obsolete"].boolean

        // then
        assert(canonical)
        assert(!obsolete)
    }

    @Test
    fun `should fail on reading other number as boolean`() {
        // given
        val transcript = Json.parseToJsonElement("""{"exons": 6}""")

        // when
        val exception = assertFailsWith<IllegalStateException> { transcript["exons"].boolean }

        // then
        assert(exception.message == "Boolean of JSON 6")
    }

    @Test
    fun `should read primitives of JSON as null when absent or of another kind`() {
        // given
        val transcript = Json.parseToJsonElement(
            """{"is_canonical": 1, "length": 1118, "gc": 0.52, "id": "ENSCAFT1", "version": "3", "phase": null, "Exon": []}"""
        )

        // then
        assert(transcript.getOrNull("is_canonical")?.booleanOrNull == true)
        assert(transcript["length"].intOrNull == 1118)
        assert(transcript["length"].longOrNull == 1118L)
        assert(transcript["gc"].doubleOrNull == 0.52)
        assert(transcript["id"].contentOrNull == "ENSCAFT1")
        assert(transcript["version"].intOrNull == 3)
        assert(transcript["id"].intOrNull == null)
        assert(transcript["phase"].intOrNull == null)
        assert(transcript["phase"].contentOrNull == null)
        assert(transcript["Exon"].intOrNull == null)
        assert(transcript["Exon"].booleanOrNull == null)
        assert(transcript.getOrNull("Translation")?.intOrNull == null)
    }

}
