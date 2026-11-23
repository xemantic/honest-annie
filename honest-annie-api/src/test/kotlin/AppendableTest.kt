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
import kotlin.test.Test

class AppendableTest {

    @Test
    fun `should append text with unary plus in scope of string builder`() {
        // given
        val report = StringBuilder()

        // when
        report {
            +"dog: "
            +"present\n"
        }

        // then
        assert(report.toString() == "dog: present\n")
    }

    @Test
    fun `should keep appending to string builder across its scopes`() {
        // given
        val report = StringBuilder()

        // when
        for (species in listOf("dog", "cow")) {
            report { +"$species\n" }
        }

        // then
        assert(report.toString() == "dog\ncow\n")
    }

    @Test
    fun `should call functions of appendable in its scope`() {
        // given
        val report = StringBuilder()

        // when
        report {
            appendLine("header")
            append('x')
            +"y"
        }

        // then
        assert(report.toString() == "header\nxy")
    }

    @Test
    fun `should append null as text like append does`() {
        // given
        val report = StringBuilder()
        val missing: String? = null

        // when
        report { +missing }

        // then
        assert(report.toString() == "null")
    }

    @Test
    fun `should evaluate scope to the appendable itself`() {
        // given
        val report = StringBuilder()

        // when
        val result = report { +"42" }

        // then
        assert(result === report)
    }

    @Test
    fun `should open scope of any appendable`() {
        // given
        val writer = java.io.StringWriter()

        // when
        writer { +"written" }

        // then
        assert(writer.toString() == "written")
    }

    @Test
    fun `should append text with unary plus in buildString`() {
        // when
        val report = buildString {
            +"dog: "
            +"present\n"
        }

        // then
        assert(report == "dog: present\n")
    }

    @Test
    fun `should append every part joined with plus after unary plus`() {
        // given
        val digest = "ENSCAFG00845030200"
        val missing: String? = null

        // when
        val report = buildString {
            +"dog: " + digest + "\n"
            +"length: " + 180 + " aa, " + missing + "\n"
        }

        // then
        assert(report == "dog: ENSCAFG00845030200\nlength: 180 aa, null\n")
    }

}
