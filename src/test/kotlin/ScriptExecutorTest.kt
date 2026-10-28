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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import com.xemantic.kotlin.script.ScriptExecutor as KotlinScriptExecutor

class ScriptExecutorTest {

    private val executor = ScriptExecutor(KotlinScriptExecutor())

    /** [Agency] keeping everything enunciated, and invoking nothing for real. */
    private class TestAgency : Agency {
        override val constitution = ""
        override val harness = ""
        override val parentCogitation: Cogitation? = null
        override val depth = 0
        override val invocations = mutableListOf<Phenomenon.Invocation>()
        override val enunciated = mutableListOf<Phenomenon>()
        override fun invoke(prompt: String, constitution: String, harness: String) = Phenomenon.Invocation(
            id = "invocation-${invocations.size + 1}",
            prompt = prompt,
            constitution = constitution,
            harness = harness
        ).also { invocations += it }
        override suspend fun enunciate(text: String, contentType: String) {
            enunciated += Phenomenon.Text(text, contentType)
        }
        override suspend fun enunciate(bytes: ByteArray, contentType: String?) {
            enunciated += Phenomenon.Data(bytes, contentType)
        }
        override suspend fun impede(reason: String) {
            enunciated += Phenomenon.Impediment(reason)
        }
        override fun isMalformed(intent: Phenomenon.Intent) = false
        override val maxReinferences = 0
        override fun resolveDeclaration(name: String, page: Int, pageSize: Int): String? = null
        override fun searchDeclarations(query: String, page: Int, pageSize: Int): String? = null
        override suspend fun execute(
            command: List<String>,
            input: String?,
            directory: String?,
            environment: Map<String, String>,
            timeout: Duration,
            maxOutputLength: Int
        ): Execution = throw UnsupportedOperationException()
    }

    @Test
    fun `should return invocations followed by value, excluding enunciated output`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()

        // when
        val phenomena = executor.execute(
            """
            import com.xemantic.ai.annie.*
            agency.enunciate("first")
            agency.invoke("sub")
            agency.enunciate(byteArrayOf(4, 2))
            agency.impede("stuck")
            42
            """.trimIndent(),
            agency
        ) { collecting ->
            "agency" provides collecting
        }

        // then
        assert(phenomena == listOf(
            agency.invocations.single(),
            Phenomenon.Text("42", Phenomenon.Text.PLAIN)
        ))
        assert(agency.enunciated == listOf(
            Phenomenon.Text("first"),
            Phenomenon.Data(byteArrayOf(4, 2)),
            Phenomenon.Impediment("stuck")
        ))
    }

    @Test
    fun `should return value of intent as text`() = runTest(timeout = 5.minutes) {
        // when
        val number = executor.execute("6 * 7", TestAgency()) { collecting ->
            "agency" provides collecting
        }
        val string = executor.execute("\"*answer*\"", TestAgency()) { collecting ->
            "agency" provides collecting
        }

        // then
        assert(number == listOf(Phenomenon.Text("42", Phenomenon.Text.PLAIN)))
        assert(string == listOf(Phenomenon.Text("*answer*", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should interpolate only multi-dollar references in multi-dollar string of intent`() = runTest(timeout = 5.minutes) {
        // given
        val intent = "val n = 42\n\$\$\"\"\"for c in a b; do echo \"\$c \$\$n\"; done\"\"\""

        // when
        val result = executor.execute(intent, TestAgency()) { collecting ->
            "agency" provides collecting
        }

        // then
        assert(result == listOf(Phenomenon.Text("""for c in a b; do echo "${'$'}c 42"; done""", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should return data value of intent as data, to perceive an image`() = runTest(timeout = 5.minutes) {
        // when
        val result = executor.execute(
            """
            import com.xemantic.ai.annie.*
            Phenomenon.Data(byteArrayOf(4, 2), "image/png")
            """.trimIndent(),
            TestAgency()
        ) { collecting ->
            "agency" provides collecting
        }

        // then
        assert(result == listOf(Phenomenon.Data(byteArrayOf(4, 2), "image/png")))
    }

    @Test
    fun `should return nothing for intent evaluating to Unit or null`()= runTest(timeout = 5.minutes) {
        // when
        val unit = executor.execute("agency.enunciate(\"output\")", TestAgency()) { collecting ->
            "agency" provides collecting
        }
        val nothing = executor.execute("null", TestAgency()) { collecting ->
            "agency" provides collecting
        }

        // then
        assert(unit.isEmpty())
        assert(nothing.isEmpty())
    }

    @Test
    fun `should not repeat invocation which is the value of intent`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()

        // when
        val phenomena = executor.execute("agency.invoke(\"sub\")", agency) { collecting ->
            "agency" provides collecting
        }

        // then
        assert(phenomena == listOf(agency.invocations.single()))
    }

    @Test
    fun `should not repeat invocations which are the value of intent`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()

        // when
        val phenomena = executor.execute(
            "listOf(\"first\", \"second\").map { agency.invoke(it) }",
            agency
        ) { collecting ->
            "agency" provides collecting
        }

        // then
        assert(phenomena == agency.invocations)
    }

    @Test
    fun `should execute intent fenced as Markdown code block`() = runTest(timeout = 5.minutes) {
        // when
        val tagged = executor.execute("```kotlin\nval answer = 6 * 7\nanswer\n```", TestAgency()) { collecting ->
            "agency" provides collecting
        }
        val untagged = executor.execute("\n```\n6 * 7\n```\n", TestAgency()) { collecting ->
            "agency" provides collecting
        }

        // then
        assert(tagged == listOf(Phenomenon.Text("42", Phenomenon.Text.PLAIN)))
        assert(untagged == listOf(Phenomenon.Text("42", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should keep fences within intent`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute($$"val fence = \"```\"\n\"${fence}kotlin\\n$fence\"", TestAgency()) { collecting ->
            "agency" provides collecting
        }

        // then
        assert(phenomena == listOf(Phenomenon.Text("```kotlin\n```", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should execute fenced intent, discarding text around the fence`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
            """
            Forgot the import, fixed:

            ```kotlin
            val answer = 6 * 7
            answer
            ```

            This should evaluate to 42.
            """.trimIndent(),
            TestAgency()
        ) { collecting ->
            "agency" provides collecting
        }

        // then
        assert(phenomena == listOf(Phenomenon.Text("42", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should execute intent, discarding closing tag of tool call parameter after it`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute("val answer = 6 * 7\nanswer\n</parameter>\n", TestAgency()) { collecting ->
            "agency" provides collecting
        }

        // then
        assert(phenomena == listOf(Phenomenon.Text("42", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should execute intent, discarding closing tag of tool call parameter ending its last line`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute($$"val answer = 6 * 7\n\"$answer\"</parameter>", TestAgency()) { collecting ->
            "agency" provides collecting
        }

        // then
        assert(phenomena == listOf(Phenomenon.Text("42", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should execute intent, discarding closing tags of DeepSeek tool call after it`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
            "val answer = 6 * 7\nanswer\n</｜DSML｜ parameter>\n</｜DSML｜ invoke>\n</body>\n</output>",
            TestAgency()
        ) { collecting ->
            "agency" provides collecting
        }

        // then
        assert(phenomena == listOf(Phenomenon.Text("42", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should keep closing tag of tool call parameter within intent`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute("\"\"\"\n</parameter>\n\"\"\".trim()", TestAgency()) { collecting ->
            "agency" provides collecting
        }

        // then
        assert(phenomena == listOf(Phenomenon.Text("</parameter>", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should execute intent ending with a lone fence as is, failing`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
            "Subproblem 5 — the amine acylates the ester.\nBut waiting for computed confirmation.\n```",
            TestAgency()
        ) { collecting ->
            "agency" provides collecting
        }

        // then
        assert(phenomena.single() is Phenomenon.Impediment)
    }

    @Test
    fun `should keep fences nested within fenced intent`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
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
            """.trimIndent().replace("'''", "\"\"\""),
            TestAgency()
        ) { collecting ->
            "agency" provides collecting
        }

        // then
        assert(phenomena == listOf(Phenomenon.Text("```kotlin\nprintln()\n```", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should provide dependencies to intent along with agency`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
            "agency.harness + greeting + \" \" + answer",
            TestAgency()
        ) { collecting ->
            "agency" provides collecting
            "greeting" provides "hello"
            "answer" provides 42
        }

        // then
        assert(phenomena == listOf(Phenomenon.Text("hello 42", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should collect invocations of agency provided under another name`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()

        // when
        val phenomena = executor.execute(
            """
            self.enunciate("renamed")
            self.invoke("sub")
            Unit
            """.trimIndent(),
            agency
        ) { collecting ->
            "self" provides collecting
        }

        // then
        assert(phenomena == listOf(agency.invocations.single()))
        assert(agency.enunciated == listOf(Phenomenon.Text("renamed")))
    }

    @Test
    fun `should impede intent referring to agency not provided`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute("agency.enunciate(\"lost\")", TestAgency()) {}

        // then
        val impediment = phenomena.single() as Phenomenon.Impediment
        assert(impediment.reason.contains("agency"))
    }

    @Test
    fun `should return impediment of failing intent`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()

        // when
        val phenomena = executor.execute(
            """
            agency.enunciate("before")
            agency.invoke("sub")
            error("boom")
            """.trimIndent(),
            agency
        ) { collecting ->
            "agency" provides collecting
        }

        // then
        assert(phenomena.size == 2)
        assert(phenomena.first() == agency.invocations.single())
        assert((phenomena.last() as Phenomenon.Impediment).reason.contains("boom"))
        // the failure impedes the intent, not the cogitation
        assert(agency.enunciated == listOf(Phenomenon.Text("before")))
    }

}
