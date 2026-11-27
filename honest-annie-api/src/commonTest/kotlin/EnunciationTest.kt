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

class EnunciationTest {

    private val text = Phenomenon.Text(ref = "sha256:01", length = 6, lines = 1)

    @Test
    fun `should tell the outcome of an invoked cogitation apart from the output and responses`() {
        // given
        val outcome = Enunciation(actant = Actant.SELF, respondsTo = "invocation-1", phenomena = listOf(text))
        val output = Enunciation(actant = Actant.SELF, phenomena = listOf(text))
        val response = Enunciation(
            actant = Actant(type = Actant.Type.COMPUTER, name = "user's computer"), respondsTo = "intent-1", phenomena = listOf(text)
        )

        // when
        val outcomes = listOf(outcome, output, response).map { it.isOutcome }

        // then
        assert(outcomes == listOf(true, false, false))
    }

}
