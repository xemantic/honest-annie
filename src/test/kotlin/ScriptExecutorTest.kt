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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

class ScriptExecutorTest {

    private val executor = SessionScriptExecutor()

    private val cogitation = Cogitation(constitution = emptyList(), harness = "", enunciations = emptyList())

    @AfterTest
    fun closeExecutor() {
        executor.close()
    }

    /** [Agency] keeping everything enunciated, and invoking nothing for real. */
    private class TestAgency : Agency {
        override val cogitation = Cogitation(constitution = emptyList(), harness = "", enunciations = emptyList())
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
        override fun enunciate(text: String, contentType: String) {
            enunciated += Phenomenon.Text(text, contentType)
        }
        override fun enunciate(bytes: ByteArray, contentType: String?) {
            enunciated += Phenomenon.Data(bytes, contentType)
        }
        override fun impede(reason: String) {
            enunciated += Phenomenon.Impediment(reason)
        }
        override fun isMalformed(intent: Phenomenon.Intent) = false
        override val maxReinferences = 0
        override fun resolveDeclaration(name: String, page: Int, pageSize: Int): String? = null
        override fun searchDeclarations(query: String, page: Int, pageSize: Int): String? = null
        override fun execute(
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
            agency.enunciate("first")
            agency.invoke("sub")
            agency.enunciate(byteArrayOf(4, 2))
            agency.impede("stuck")
            42
            """.trimIndent(),
            cogitation,
            agency
        )

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
        val number = executor.execute("6 * 7", cogitation, TestAgency())
        val string = executor.execute("\"*answer*\"", cogitation, TestAgency())

        // then
        assert(number == listOf(Phenomenon.Text("42", Phenomenon.Text.PLAIN)))
        assert(string == listOf(Phenomenon.Text("*answer*", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should interpolate only multi-dollar references in multi-dollar string of intent`() = runTest(timeout = 5.minutes) {
        // given
        val intent = "val n = 42\n\$\$\"\"\"for c in a b; do echo \"\$c \$\$n\"; done\"\"\""

        // when
        val result = executor.execute(intent, cogitation, TestAgency())

        // then
        assert(result == listOf(Phenomenon.Text("""for c in a b; do echo "${'$'}c 42"; done""", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should interpolate triple quotes of embedded code in multi-dollar string of intent`() = runTest(timeout = 5.minutes) {
        // given
        val intent = "\$\$\"\"\"\ndef f(\$x):\n    \$\${\"\\\"\\\"\\\"\"}Doc.\$\${\"\\\"\\\"\\\"\"}\n\"\"\".trimIndent()"

        // when
        val result = executor.execute(intent, cogitation, TestAgency())

        // then
        val python = "def f(\$x):\n    \"\"\"Doc.\"\"\""
        assert(result == listOf(Phenomenon.Text(python, Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should return data value of intent as data, to perceive an image`() = runTest(timeout = 5.minutes) {
        // when
        val result = executor.execute(
            """
            import com.xemantic.ai.annie.*
            Phenomenon.Data(byteArrayOf(4, 2), "image/png")
            """.trimIndent(),
            cogitation,
            TestAgency()
        )

        // then
        assert(result == listOf(Phenomenon.Data(byteArrayOf(4, 2), "image/png")))
    }

    @Test
    fun `should return nothing for intent evaluating to Unit or null`()= runTest(timeout = 5.minutes) {
        // when
        val unit = executor.execute("agency.enunciate(\"output\")", cogitation, TestAgency())
        val nothing = executor.execute("null", cogitation, TestAgency())

        // then
        assert(unit.isEmpty())
        assert(nothing.isEmpty())
    }

    @Test
    fun `should not repeat invocation which is the value of intent`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()

        // when
        val phenomena = executor.execute("agency.invoke(\"sub\")", cogitation, agency)

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
            cogitation,
            agency
        )

        // then
        assert(phenomena == agency.invocations)
    }

    @Test
    fun `should impede intent failing to compile with a digest of the first errors`() = runTest(timeout = 5.minutes) {
        // given
        val python = (1..500).joinToString("\n") { "for i,o in enumerate(opts$it): print(i)" }

        // when
        val phenomena = executor.execute(python, cogitation, TestAgency())

        // then
        val reason = (phenomena.single() as Phenomenon.Impediment).reason
        assert(reason.length <= 5000)
        assert(reason.startsWith("snippet1.kts:1:"))
        assert(reason.contains(Regex("\\d+ more errors omitted")))
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
            cogitation,
            agency
        )

        // then
        assert(phenomena.size == 2)
        assert(phenomena.first() == agency.invocations.single())
        assert((phenomena.last() as Phenomenon.Impediment).reason.contains("boom"))
        // the failure impedes the intent, not the cogitation
        assert(agency.enunciated == listOf(Phenomenon.Text("before")))
    }

    @Test
    fun `should keep declarations of intent visible to the next intents`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()
        executor.execute(
            """
            import com.xemantic.ai.annie.*
            val answer = 6 * 7
            fun describe(n: Int) = "answer is ${'$'}n"
            """.trimIndent(),
            cogitation,
            agency
        )

        // when
        val phenomena = executor.execute("Phenomenon.Text(describe(answer))", cogitation, agency)

        // then
        assert(phenomena == listOf(Phenomenon.Text("Text(text=answer is 42, contentType=text/markdown)", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should provide cogitation of intent and agency of current execution through agency`() = runTest(timeout = 5.minutes) {
        // given
        val first = TestAgency()
        val second = TestAgency()
        val expressed = cogitation.copy(harness = "expressed")
        executor.execute("agency.enunciate(\"first\")", cogitation, first)

        // when
        val phenomena = executor.execute(
            "agency.enunciate(\"second\")\nagency.cogitation.harness",
            expressed,
            second
        )

        // then
        assert(phenomena == listOf(Phenomenon.Text("expressed", Phenomenon.Text.PLAIN)))
        assert(first.enunciated == listOf(Phenomenon.Text("first")))
        assert(second.enunciated == listOf(Phenomenon.Text("second")))
    }

    @Test
    fun `should await suspend function and launch coroutine outliving intent`() = runTest(timeout = 5.minutes) {
        // given
        executor.execute(
            """
            val started = CompletableDeferred<String>()
            val job = launch { started.complete("launched") }
            """.trimIndent(),
            cogitation,
            TestAgency()
        )

        // when
        val phenomena = executor.execute("await { job.join(); started.await() }", cogitation, TestAgency())

        // then
        assert(phenomena == listOf(Phenomenon.Text("launched", Phenomenon.Text.PLAIN)))
    }

}
