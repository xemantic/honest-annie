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
        assert(template.startsWith("# The constitution of honest-annie\n\n## Identity\n\n"))
        assert("interface Agency" in template)
        assert("class Cogitation(" in template)
        assert("{{api}}" !in template && "{{cognition}}" !in template)
        assert("````kotlin\n{{harness}}\n````\n\n## Your answer\n\n" in template)
    }

    @Test
    fun `should load the constitution of its own for a model having one`() {
        // when
        val template = constitutionTemplate(model = "deepseek-v4_1-flash")

        // then
        assert(template != constitutionTemplate)
        assert(template.startsWith("# The constitution of honest-annie\n\n## Identity\n\n"))
        assert("\n## Judgment and computation\n" in template)
        assert("interface Agency" in template)
        assert("class Cogitation(" in template)
        assert("{{api}}" !in template && "{{cognition}}" !in template)
        assert("````kotlin\n{{harness}}\n````\n\n## Your answer\n\n" in template)
    }

    @Test
    fun `should load the constitution of the closest family of a model`() {
        // when
        val template = constitutionTemplate(model = "accounts/provider/models/deepseek-v9")

        // then
        assert(template == constitutionTemplate(model = "deepseek-v4_1-flash"))
    }

    @Test
    fun `should load the default constitution for a model without one of its own`() {
        // when
        val template = constitutionTemplate(model = "claude-fable-5-2")

        // then
        assert(template == constitutionTemplate)
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
