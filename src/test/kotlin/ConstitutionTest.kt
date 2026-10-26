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

class ConstitutionTest {

    @Test
    fun `should load the template filled with the API and cognition sources`() {
        // when
        val template = constitutionTemplate

        // then
        assert(template.startsWith("# The constitution of honest-annie\n\n## Honesty\n\n"))
        assert("interface Agency" in template)
        assert("class Cogitation(" in template)
        assert("{{api}}" !in template && "{{cognition}}" !in template)
        assert("agency.resolveDeclaration(" in template)
        assert("````kotlin\n{{harness}}\n````\n\n## Your answer\n\n" in template)
    }

    @Test
    fun `should fill the harness into the template`() {
        // given
        val harness = "println(\"harness\")"

        // when
        val constitution = constitution(harness, template = "## Harness\n\n{{harness}}")

        // then
        assert(constitution == "## Harness\n\nprintln(\"harness\")")
    }

}
