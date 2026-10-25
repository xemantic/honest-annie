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

package com.xemantic.ai.annie.host

import com.xemantic.ai.annie.Actant
import com.xemantic.ai.annie.Cogitation
import com.xemantic.ai.annie.Enunciation
import com.xemantic.ai.annie.Inference
import com.xemantic.ai.annie.Phenomenon
import com.xemantic.ai.annie.cognizer.Cognized
import com.xemantic.ai.annie.cognizer.Cognizer
import com.xemantic.ai.annie.responseId
import com.xemantic.kotlin.script.ScriptExecutor
import com.xemantic.kotlin.test.assert
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

class HarnessTest {

    private val executor = ScriptExecutor()

    private val store = InMemoryCogitationStore()

    /**
     * Expresses the next of its [intents] on each inference, recording the cogitations it perceived,
     * each inference consuming as many input tokens as the enunciations it perceived.
     */
    private class ScriptedCognizer(
        private vararg val intents: String,
        private val stop: Inference.Stop? = null
    ) : Cognizer {
        val perceived = mutableListOf<Cogitation>()
        override suspend fun cognize(cogitation: Cogitation): Cognized {
            perceived += cogitation
            return Cognized(
                phenomena = listOf(Phenomenon.Intent(intents[perceived.size - 1])),
                inference = Inference(
                    model = "scripted",
                    provider = "test",
                    tokens = mapOf(Inference.Tokens.INPUT to cogitation.enunciations.size.toLong()),
                    stop = stop
                )
            )
        }
    }

    /** Unfolds the [cogitation] with the [cognizer]. */
    private suspend fun cognize(
        cognizer: Cognizer,
        cogitation: Cogitation
    ) = HarnessHost(executor, cognizer, store).cognize(cogitation)

    private fun cogitation(harness: String) = Cogitation(
        constitution = emptyList(),
        harness = harness,
        enunciations = listOf(Enunciation(
            actant = Actant(type = Actant.Type.HUMAN, name = "user"),
            phenomena = listOf(Phenomenon.Text("test"))
        ))
    )

    /** Intent producing no phenomena, which ends the loop of inferences. */
    private val restingIntent = "Unit"

    /** Harness of invoked cogitations, enunciating their prompt, without any inference. */
    private val echoHarness = """
        import com.xemantic.ai.annie.*
        agency.enunciate("echo: " + (cogitation.enunciations.first().phenomena.first() as Phenomenon.Text).text)
        cogitation + Enunciation(actant = Actant.SELF, phenomena = listOf(Phenomenon.Text("echoed")))
    """.trimIndent()

    /** This string as a Kotlin raw string literal. */
    private fun String.quoted() = "\"\"\"" + this + "\"\"\""

    /** Replaces `ECHO_HARNESS` in this intent with the literal of the [echoHarness]. */
    private fun String.withEchoHarness() = replace("ECHO_HARNESS", echoHarness.quoted())

    /** Intent enunciating the texts of all the responses to the invocations of the cogitation. */
    private val respondedIntent = """
        import com.xemantic.ai.annie.*
        val invocations = cogitation.enunciations.flatMap { it.phenomena }
            .filterIsInstance<Phenomenon.Invocation>().map { it.id }
        cogitation.enunciations.filter { it.respondsTo in invocations }.forEach { response ->
            agency.enunciate((response.phenomena.first() as Phenomenon.Text).text)
        }
    """.trimIndent()

    @Test
    fun `should rest cogitation right after intent enunciating output`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            """
            import com.xemantic.ai.annie.*
            agency.enunciate("answer is 42")
            agency.enunciate(byteArrayOf(4, 2), "application/octet-stream")
            "ignored value"
            """.trimIndent(),
            restingIntent
        )

        // when
        val output = cognize(cognizer, cogitation(defaultHarness))

        // then
        assert(output.actant == Actant.SELF)
        assert(output.phenomena == listOf(
            Phenomenon.Text("answer is 42"),
            Phenomenon.Data(byteArrayOf(4, 2), "application/octet-stream")
        ))
        assert(cognizer.perceived.size == 1)
    }

    @Test
    fun `should impede instead of executing intent of inference not completed`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            "agency.enunciate(\"truncated\")",
            stop = Inference.Stop(Inference.Stop.Kind.TRUNCATED, "max_tokens")
        )

        // when
        val output = cognize(cognizer, cogitation(defaultHarness))

        // then
        assert(output.phenomena == listOf(
            Phenomenon.Impediment("Inference not completed, stopped by max_tokens")
        ))
        assert(cognizer.perceived.size == 1)
    }

    @Test
    fun `should carry inference of each enunciation of self`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            "6 * 7",
            "agency.enunciate(\"answer is 42\")"
        )

        // when
        cognize(cognizer, cogitation(defaultHarness))

        // then
        val enunciations = cognizer.perceived.last().enunciations
        assert(enunciations.map { it.inference } == listOf(
            null,
            Inference(model = "scripted", provider = "test", tokens = mapOf(Inference.Tokens.INPUT to 1L)),
            null
        ))
    }

    @Test
    fun `should perceive value of intent and infer again`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            "6 * 7",
            "agency.enunciate(\"answer is 42\")"
        )

        // when
        val output = cognize(cognizer, cogitation(defaultHarness))

        // then
        assert(output.phenomena == listOf(Phenomenon.Text("answer is 42")))
        assert(cognizer.perceived.size == 2)
        val perceived = cognizer.perceived.last().enunciations
        val intent = perceived[perceived.size - 2].phenomena.single() as Phenomenon.Intent
        val response = perceived.last()
        assert(response.actant.type == Actant.Type.COMPUTER)
        assert(response.respondsTo == intent.id)
        assert(response.phenomena == listOf(Phenomenon.Text("42", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should not rest after intent enunciating output while invoking`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            """
            agency.enunciate("interim")
            agency.invoke("sub", harness = ECHO_HARNESS)
            """.trimIndent().withEchoHarness(),
            respondedIntent
        )

        // when
        val output = cognize(cognizer, cogitation(defaultHarness))

        // then
        assert(output.phenomena == listOf(Phenomenon.Text("interim"), Phenomenon.Text("echo: sub")))
        assert(cognizer.perceived.size == 2)
    }

    @Test
    fun `should rest cogitation after intent producing no phenomena`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(restingIntent)

        // when
        val output = cognize(cognizer, cogitation(defaultHarness))

        // then
        assert(output.phenomena.isEmpty())
        assert(cognizer.perceived.size == 1)
    }

    @Test
    fun `should respond to failing intent with impediment and infer again`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            "error(\"boom\")",
            "agency.impede(\"cannot proceed\")"
        )

        // when
        val output = cognize(cognizer, cogitation(defaultHarness))

        // then
        assert(output.phenomena == listOf(Phenomenon.Impediment("cannot proceed")))
        assert(cognizer.perceived.size == 2)
        val response = cognizer.perceived[1].enunciations.last()
        assert(response.actant.type == Actant.Type.COMPUTER)
        assert((response.phenomena.single() as Phenomenon.Impediment).reason.contains("boom"))
    }

    @Test
    fun `should enunciate impediment when no intent is expressed`() = runTest(timeout = 5.minutes) {
        // when
        val output = cognize(Cognizer { Cognized(listOf(Phenomenon.Text("no kotlin"))) }, cogitation(defaultHarness))

        // then
        assert(output.phenomena == listOf(Phenomenon.Impediment("No intent expressed")))
    }

    @Test
    fun `should execute harness again until self enunciates last`() = runTest(timeout = 5.minutes) {
        // given
        val stepHarness = """
            import com.xemantic.ai.annie.*
            if (cogitation.enunciations.size < 3) {
                cogitation + Enunciation(
                    actant = Actant(type = Actant.Type.COMPUTER, name = "step"),
                    phenomena = listOf(Phenomenon.Text("step"))
                )
            } else {
                agency.enunciate("after ${'$'}{cogitation.enunciations.size}")
                cogitation + Enunciation(actant = Actant.SELF, phenomena = listOf(Phenomenon.Text("done")))
            }
        """.trimIndent()

        // when
        val output = cognize(ScriptedCognizer(), cogitation(stepHarness))

        // then
        assert(output.phenomena == listOf(Phenomenon.Text("after 3")))
    }

    @Test
    fun `should provide intent with dependencies altered by harness`() = runTest(timeout = 5.minutes) {
        // given
        val harness = defaultHarness.replace(
            "\"cogitation\" provides inferred",
            "\"cogitation\" provides inferred\n    \"answer\" provides 42"
        )
        val cognizer = ScriptedCognizer(
            "agency.enunciate(\"answer is \" + answer + \" after \" + cogitation.enunciations.size)"
        )

        // when
        val output = cognize(cognizer, cogitation(harness))

        // then
        assert(harness != defaultHarness)
        assert(output.phenomena == listOf(Phenomenon.Text("answer is 42 after 2")))
    }

    @Test
    fun `should perceive response to invocation of cogitation unfolded by another harness`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            "agency.invoke(\"sub\", harness = ${echoHarness.quoted()})",
            respondedIntent
        )

        // when
        val output = cognize(cognizer, cogitation(defaultHarness))

        // then
        assert(output.phenomena == listOf(Phenomenon.Text("echo: sub")))
        val perceived = cognizer.perceived[1].enunciations
        val invocation = perceived[perceived.size - 2].phenomena.single() as Phenomenon.Invocation
        assert(invocation.prompt == "sub")
        val response = perceived.last()
        assert(response.respondsTo == invocation.id)
        assert(response.id == responseId(invocation.id))
        assert(response.phenomena == listOf(Phenomenon.Text("echo: sub")))
        val invoked = store.load(invocation.id)
        assert(invoked?.invocation == invocation)
        assert(invoked.cogitation.enunciations.last().actant == Actant.SELF)
    }

    @Test
    fun `should enunciate prompt of invoked cogitation by its invoker`() = runTest(timeout = 5.minutes) {
        // given
        val actantHarness = """
            import com.xemantic.ai.annie.*
            val actant = cogitation.enunciations.single().actant
            agency.enunciate(actant.type.name + " " + actant.name)
            cogitation + Enunciation(actant = Actant.SELF, phenomena = listOf(Phenomenon.Text("done")))
        """.trimIndent()
        val cognizer = ScriptedCognizer(
            "agency.invoke(\"sub\", harness = ${actantHarness.quoted()})",
            respondedIntent
        )

        // when
        val output = cognize(cognizer, cogitation(defaultHarness))

        // then
        assert(output.phenomena == listOf(Phenomenon.Text("INVOKER invoker")))
    }

    @Test
    fun `should await responses to all the invocations before cognizing further`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            """
            val harness = ECHO_HARNESS
            agency.invoke("first", harness = harness)
            agency.invoke("second", harness = harness)
            """.trimIndent().withEchoHarness(),
            respondedIntent
        )

        // when
        val output = cognize(cognizer, cogitation(defaultHarness))

        // then
        assert(cognizer.perceived.size == 2)
        val responses = cognizer.perceived[1].enunciations.takeLast(2)
        assert(responses.all { it.respondsTo != null })
        assert(output.phenomena.toSet() == setOf(Phenomenon.Text("echo: first"), Phenomenon.Text("echo: second")))
    }

    @Test
    fun `should enunciate impediment of broken harness`() = runTest(timeout = 5.minutes) {
        // when
        val output = cognize(ScriptedCognizer(), cogitation("this is not kotlin"))

        // then
        val impediment = output.phenomena.single() as Phenomenon.Impediment
        assert(impediment.reason.startsWith("Harness failed to unfold the cogitation:"))
    }

    @Test
    fun `should enunciate impediment of harness not evaluating to cogitation`() = runTest(timeout = 5.minutes) {
        // when
        val output = cognize(ScriptedCognizer(), cogitation("42"))

        // then
        assert(output.phenomena == listOf(
            Phenomenon.Impediment("Harness evaluated to 42 instead of the unfolded Cogitation")
        ))
    }

    @Test
    fun `should enunciate impediment of harness not unfolding cogitation`() = runTest(timeout = 5.minutes) {
        // when
        val output = cognize(ScriptedCognizer(), cogitation("cogitation"))

        // then
        assert(output.phenomena == listOf(Phenomenon.Impediment("Harness did not unfold the cogitation")))
    }

    @Test
    fun `should impede cogitation once token budget is spent`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer("6 * 7", restingIntent)

        // when
        val output = HarnessHost(executor, cognizer, store, tokenBudget = 1).cognize(cogitation(defaultHarness))

        // then
        assert(cognizer.perceived.size == 1)
        assert(output.phenomena == listOf(
            Phenomenon.Impediment("Token budget of 1 spent: 1 token(s) inferred by this cogitation and the ones invoked in its tree")
        ))
    }

    @Test
    fun `should spend token budget across the tree of cogitations`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer("agency.invoke(\"sub\")", "6 * 7", restingIntent)

        // when
        val output = HarnessHost(executor, cognizer, store, tokenBudget = 2).cognize(cogitation(defaultHarness))

        // then
        // the root and the invoked cogitation inferred once each, 1 token each, then both were impeded
        assert(cognizer.perceived.size == 2)
        val impediment = output.phenomena.single() as Phenomenon.Impediment
        assert(impediment.reason.startsWith("Token budget of 2 spent"))
    }

    @Test
    fun `should not step more cogitations at a time than the parallelism`() = runTest(timeout = 5.minutes) {
        // given
        val invocations = 3
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val allInFlight = CompletableDeferred<Unit>()
        // the invoked cogitations wait for each other, so that without the limit they would infer together
        val cognizer = Cognizer { cogitation ->
            val intent = if (cogitation.depth == 0) {
                if (cogitation.enunciations.size == 1) {
                    (1..invocations).joinToString("\n") { "agency.invoke(\"sub $it\")" }
                } else restingIntent
            } else {
                val current = inFlight.incrementAndGet()
                maxInFlight.accumulateAndGet(current, ::maxOf)
                if (current == invocations) allInFlight.complete(Unit)
                withTimeoutOrNull(2.seconds) { allInFlight.await() }
                inFlight.decrementAndGet()
                restingIntent
            }
            Cognized(phenomena = listOf(Phenomenon.Intent(intent)))
        }

        // when
        HarnessHost(executor, cognizer, store, parallelism = 1).cognize(cogitation(defaultHarness))

        // then
        assert(maxInFlight.get() == 1)
    }

    @Test
    fun `should provide depth of cogitation to intent`() = runTest(timeout = 5.minutes) {
        // given
        val depthHarness = """
            import com.xemantic.ai.annie.*
            agency.enunciate("depth " + agency.depth)
            cogitation + Enunciation(actant = Actant.SELF, phenomena = listOf(Phenomenon.Text("done")))
        """.trimIndent()
        val cognizer = ScriptedCognizer(
            "agency.invoke(\"sub at depth ${'$'}{agency.depth}\", harness = ${depthHarness.quoted()})",
            respondedIntent
        )

        // when
        val output = cognize(cognizer, cogitation(defaultHarness))

        // then
        assert(output.phenomena == listOf(Phenomenon.Text("depth 1")))
        val invocation = cognizer.perceived[1].enunciations.flatMap { it.phenomena }
            .filterIsInstance<Phenomenon.Invocation>().single()
        assert(invocation.prompt == "sub at depth 0")
    }

    @Test
    fun `should record root and invoked cogitations`() = runTest(timeout = 5.minutes) {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val cognizer = ScriptedCognizer(
            "agency.invoke(\"sub\", harness = ${echoHarness.quoted()})",
            respondedIntent
        )

        // when
        HarnessHost(executor, cognizer, store, recordingDir).cognize(cogitation(defaultHarness))

        // then
        val root = recordingDir.listDirectoryEntries().single()
        val files = root.listDirectoryEntries().map { it.name }.toSet()
        assert(files.containsAll(listOf(
            "cogitation.md", "constitution.md", "harness.kts",
            "001-1-human-text.md", "002-1-self-intent.kts", "003-1-computer-invocation.md",
            "004-1-self-text.md", "005-1-self-intent.kts"
        )))
        assert(files.none { it.startsWith("006-") })
        val invoked = root.listDirectoryEntries().single { it.isDirectory() }
        assert(invoked.name.startsWith("003-1-"))
        assert(invoked.listDirectoryEntries().any { it.name == "002-1-self-text.md" })
    }

}
