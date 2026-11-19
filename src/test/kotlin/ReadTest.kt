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
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test

class ReadTest {

    private val file = createTempFile(suffix = ".py").apply { writeText((1..100).joinToString("") { "line $it\n" }) }

    private fun numbered(range: IntRange) = range.map { "${it.toString().padStart(6)}\tline $it" }

    @Test
    fun `should read ranges of lines numbered, apart ones separated, none omitted`() {
        // when
        val read = read(file.toString(), 3..5, 40..90)

        // then
        assert(read == (numbered(3..5) + "     …" + numbered(40..90)).joinToString("\n"))
    }

    @Test
    fun `should read adjacent and overlapping ranges as one, within the file`() {
        // when
        val read = read(file.toString(), 95..120, 10..12, 13..14, 11..12)

        // then
        assert(read == (numbered(10..14) + "     …" + numbered(95..100)).joinToString("\n"))
    }

    @Test
    fun `should read the whole file numbered when no range is given`() {
        // when
        val read = read(file.toString())

        // then
        assert(read == numbered(1..100).joinToString("\n"))
    }

}
