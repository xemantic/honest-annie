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

class IntentTest {

    private val madeUpResponse = """
        <annie:actant type="computer" name="user's computer" initiated="2026-10-27T20:37:16.835Z" finalized="2026-10-27T20:37:16.932Z"/>
        no rdkit
    """.trimIndent()

    @Test
    fun `should be the expression of a script as is`() {
        // when
        val intent = Phenomenon.Intent("val answer = 6 * 7\nanswer")

        // then
        assert(intent.script == "val answer = 6 * 7\nanswer")
        assert(intent.script == intent.expression)
    }

    @Test
    fun `should be the script before the response of the computer made up by the cognizer`() {
        // given
        val expression = """
            |import com.xemantic.ai.annie.*
            |agency.bash("python3 -c 'import rdkit'").output
            |
            |$madeUpResponse
            |
            |No RDKit, so the answer has to be derived by hand:
            |```kotlin
            |agency.enunciate("solution = C")
            |```
        """.trimMargin()

        // when
        val intent = Phenomenon.Intent(expression)

        // then
        assert(intent.script == "import com.xemantic.ai.annie.*\nagency.bash(\"python3 -c 'import rdkit'\").output")
        assert(intent.expression == expression)
    }

    @Test
    fun `should be the fenced script before the response of the computer made up by the cognizer`() {
        // when
        val intent = Phenomenon.Intent("Checking first:\n```kotlin\n6 * 7\n```\n$madeUpResponse\n```kotlin\n41\n```")

        // then
        assert(intent.script == "6 * 7")
    }

    @Test
    fun `should be blank when the cognizer expressed only a made up response of the computer`() {
        // when
        val intent = Phenomenon.Intent("\n\n$madeUpResponse\nSo the answer is 42.")

        // then
        assert(intent.script.isBlank())
    }

    @Test
    fun `should keep markup of the response of the computer within a string of the script`() {
        // when
        val intent = Phenomenon.Intent("val markup = \"<annie:actant type=\\\"computer\\\"/>\"\nmarkup")

        // then
        assert(intent.script == intent.expression)
    }

    @Test
    fun `should be the script fenced as Markdown code block`() {
        // when
        val tagged = Phenomenon.Intent("```kotlin\nval answer = 6 * 7\nanswer\n```")
        val untagged = Phenomenon.Intent("\n```\n6 * 7\n```\n")

        // then
        assert(tagged.script == "val answer = 6 * 7\nanswer")
        assert(untagged.script == "6 * 7")
    }

    @Test
    fun `should keep fences within a string of the script`() {
        // given
        val expression = $$"val fence = \"```\"\n\"${fence}kotlin\\n$fence\""

        // when
        val intent = Phenomenon.Intent(expression)

        // then
        assert(intent.script == expression)
    }

    @Test
    fun `should be the fenced script, discarding text around the fence`() {
        // when
        val intent = Phenomenon.Intent(
            """
            Forgot the import, fixed:

            ```kotlin
            val answer = 6 * 7
            answer
            ```

            This should evaluate to 42.
            """.trimIndent()
        )

        // then
        assert(intent.script == "val answer = 6 * 7\nanswer")
    }

    @Test
    fun `should keep fences nested within fenced script`() {
        // when
        val intent = Phenomenon.Intent(
            """
            The script enunciating Markdown:
            ```kotlin
            val markdown = '''
            ```kotlin
            println()
            ```
            '''
            markdown.trim()
            ```
            Done.
            """.trimIndent().replace("'''", "\"\"\"")
        )

        // then
        assert(intent.script == "val markdown = \"\"\"\n```kotlin\nprintln()\n```\n\"\"\"\nmarkdown.trim()")
    }

    @Test
    fun `should be the expression ending with a lone fence as is`() {
        // given
        val expression = "Subproblem 5 — the amine acylates the ester.\nBut waiting for computed confirmation.\n```"

        // when
        val intent = Phenomenon.Intent(expression)

        // then
        assert(intent.script == expression)
    }

    @Test
    fun `should discard closing tag of tool call parameter after the script`() {
        // when
        val intent = Phenomenon.Intent("val answer = 6 * 7\nanswer\n</parameter>\n")

        // then
        assert(intent.script == "val answer = 6 * 7\nanswer")
    }

    @Test
    fun `should discard closing tag of tool call parameter ending the last line of the script`() {
        // when
        val intent = Phenomenon.Intent($$"val answer = 6 * 7\n\"$answer\"</parameter>")

        // then
        assert(intent.script == $$"val answer = 6 * 7\n\"$answer\"")
    }

    @Test
    fun `should discard closing tags of DeepSeek tool call after the script`() {
        // when
        val intent = Phenomenon.Intent("val answer = 6 * 7\nanswer\n</｜DSML｜ parameter>\n</｜DSML｜ invoke>\n</body>\n</output>")

        // then
        assert(intent.script == "val answer = 6 * 7\nanswer")
    }

    @Test
    fun `should keep closing tag of tool call parameter within a string of the script`() {
        // given
        val expression = "\"\"\"\n</parameter>\n\"\"\".trim()"

        // when
        val intent = Phenomenon.Intent(expression)

        // then
        assert(intent.script == expression)
    }

}
