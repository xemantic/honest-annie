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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import com.xemantic.kotlin.script.ScriptExecutor as KotlinScriptExecutor

class ScriptExecutorTest {

    private val executor = ScriptExecutor(KotlinScriptExecutor())

    /** [Agency] keeping everything enunciated, and invoking nothing for real. */
    private class TestAgency : Agency {
        override val harness = ""
        override val parentCogitation: Cogitation? = null
        override val invocations = mutableListOf<Phenomenon.Invocation>()
        override val enunciated = mutableListOf<Phenomenon>()
        override val enunciationInitiated: Instant? = null
        override fun invoke(prompt: String, harness: String, constitution: String?) = Phenomenon.Invocation(
            id = "invocation-${invocations.size + 1}",
            prompt = prompt,
            harness = harness,
            constitution = constitution
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
    }

    @Test
    fun `should collect phenomena enunciated and invoked in order, ignoring the value`() = runTest(timeout = 5.minutes) {
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
            Phenomenon.Text("first"),
            agency.invocations.single(),
            Phenomenon.Data(byteArrayOf(4, 2)),
            Phenomenon.Impediment("stuck")
        ))
        assert(agency.enunciated == listOf(
            Phenomenon.Text("first"),
            Phenomenon.Data(byteArrayOf(4, 2)),
            Phenomenon.Impediment("stuck")
        ))
    }

    @Test
    fun `should collect no phenomena of intent only evaluating to value`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute("6 * 7", TestAgency()) { collecting ->
            "agency" provides collecting
        }

        // then
        assert(phenomena.isEmpty())
    }

    @Test
    fun `should provide dependencies to intent along with agency`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
            "agency.enunciate(greeting + \" \" + answer)",
            TestAgency()
        ) { collecting ->
            "agency" provides collecting
            "greeting" provides "hello"
            "answer" provides 42
        }

        // then
        assert(phenomena == listOf(Phenomenon.Text("hello 42")))
    }

    @Test
    fun `should collect phenomena of agency provided under another name`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()

        // when
        val phenomena = executor.execute("self.enunciate(\"renamed\")", agency) { collecting ->
            "self" provides collecting
        }

        // then
        assert(phenomena == listOf(Phenomenon.Text("renamed")))
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
    fun `should follow phenomena with impediment of failing intent`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()

        // when
        val phenomena = executor.execute(
            """
            agency.enunciate("before")
            error("boom")
            """.trimIndent(),
            agency
        ) { collecting ->
            "agency" provides collecting
        }

        // then
        assert(phenomena.size == 2)
        assert(phenomena.first() == Phenomenon.Text("before"))
        assert((phenomena.last() as Phenomenon.Impediment).reason.contains("boom"))
        // the failure impedes the intent, not the cogitation
        assert(agency.enunciated == listOf(Phenomenon.Text("before")))
    }

}
