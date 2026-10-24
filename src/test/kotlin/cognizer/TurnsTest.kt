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
        assert((turns[0] as Turn.User).text == """
            <annie:cogitation depth="2"/>
            <annie:actant type="invoker" name="invoker" initiated="2026-10-24T10:15:30Z"/>
            judge
        """.trimIndent())
        assert((turns[1] as Turn.Assistant).parts == listOf("6 * 7"))
        assert((turns[2] as Turn.User).text == """
            <annie:actant type="computer" name="user's computer" initiated="2026-10-24T10:15:30Z"/>
            <annie:impediment/>
            boom
        """.trimIndent())
    }

}
