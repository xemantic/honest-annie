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

package com.xemantic.annie.harness.host

import com.xemantic.annie.harness.content
import com.xemantic.annie.harness.data
import com.xemantic.annie.harness.monologue
import com.xemantic.annie.harness.testContents
import com.xemantic.annie.harness.text
import com.xemantic.annie.Actant
import com.xemantic.annie.Cogitation
import com.xemantic.annie.Edit
import com.xemantic.annie.Enunciation
import com.xemantic.annie.Inference
import com.xemantic.annie.Invocation
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.harness.cognizer.Cognized
import com.xemantic.annie.harness.cognizer.Cognizer
import com.xemantic.annie.computer.local.InProcessComputerProvider
import com.xemantic.annie.responseId
import com.xemantic.kotlin.test.assert
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

class HarnessTest {

    private val store = InMemoryCogitationStore()

    private val computers = InProcessComputerProvider()

    /** Computers of two types, so that a cogitation can start one for the cogitation it invokes. */
    private val gpuComputers = InProcessComputerProvider(types = listOf("default", "GPU64GB"))

    /**
     * Expresses the next of its [intents] on each inference of the root cogitation, recording the cogitations it perceived,
     * each inference consuming as many input tokens as the enunciations it perceived.
     * The cogitations it invokes are unfolded by the same harness, and so inferred by [invoked]:
     * by default each one answers with its prompt, by the text of an inference expressing no intent, without being recorded.
     */
    private class ScriptedCognizer(
        private vararg val intents: String,
        private val stop: Inference.Stop? = null,
        private val invoked: (Cogitation) -> Phenomenon = { text("echo: " + it.prompt) }
    ) : Cognizer {
        val perceived = mutableListOf<Cogitation>()
        override suspend fun cognize(cogitation: Cogitation): Cognized {
            if (cogitation.depth > 0) return Cognized(listOf(invoked(cogitation)))
            perceived += cogitation
            return Cognized(
                phenomena = listOf(Phenomenon.Intent(script = intents[perceived.size - 1])),
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
    ) = HarnessHost(cognizer, store, contents = testContents, computers = computers).cognize(cogitation)

    private fun cogitation() = Cogitation(
        constitution = "",
        enunciations = listOf(Enunciation(
            actant = Actant(type = Actant.Type.HUMAN, name = "user"),
            phenomena = listOf(text("test"))
        ))
    )

    /** Intent enunciating output, which ends the loop of inferences. */
    private val restingIntent = "agency.enunciate(\"rested\")"

    /** Intent enunciating the texts of all the outcomes of the cogitations invoked by the cogitation, responding to no intent of it. */
    private val respondedIntent = """
        import com.xemantic.annie.*
        val intents = agency.cogitation().enunciations.flatMap { it.phenomena }
            .filterIsInstance<Phenomenon.Intent>().map { it.id }
        agency.cogitation().enunciations.filter { it.respondsTo != null && it.respondsTo !in intents }.forEach { response ->
            agency.enunciate(agency.readText(response.phenomena.first()))
        }
    """.trimIndent()

    /** The outcomes of the cogitations invoked by this one, the enunciations responding to none of its intents. */
    private val Cogitation.outcomes: List<Enunciation> get() {
        val intents = enunciations.flatMap { it.phenomena }.filterIsInstance<Phenomenon.Intent>().map { it.id }
        return enunciations.filter { it.respondsTo != null && it.respondsTo !in intents }
    }

    /** The id of the first cogitation invoked by this one, which its outcome responds to. */
    private val Cogitation.invokedId: String get() = checkNotNull(outcomes.first().respondsTo)

    /** The invocation of the cogitation invoked by the first intent of this one, kept by the host. */
    private suspend fun Cogitation.invocation(): Invocation = checkNotNull(store.load(invokedId)?.invocation)

    @Test
    fun `should rest cogitation right after intent enunciating output`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            """
            import com.xemantic.annie.*
            agency.enunciate("answer is 42")
            agency.enunciate(byteArrayOf(4, 2), "application/octet-stream")
            "ignored value"
            """.trimIndent(),
            restingIntent
        )

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.actant == Actant.SELF)
        assert(output.phenomena == listOf(
            text("answer is 42"),
            data(byteArrayOf(4, 2), "application/octet-stream")
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
        val output = cognize(cognizer, cogitation())

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
        cognize(cognizer, cogitation())

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
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(text("answer is 42")))
        assert(cognizer.perceived.size == 2)
        val perceived = cognizer.perceived.last().enunciations
        val intent = perceived[perceived.size - 2].phenomena.single() as Phenomenon.Intent
        val response = perceived.last()
        assert(response.actant.type == Actant.Type.COMPUTER)
        assert(response.respondsTo == intent.id)
        assert(response.phenomena.first() == text("42", "text/plain"))
    }

    @Test
    fun `should not rest after intent enunciating output while invoking`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            """
            agency.enunciate("interim")
            agency.invoke("sub")
            """.trimIndent(),
            respondedIntent
        )

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(text("interim"), text("echo: sub")))
        assert(cognizer.perceived.size == 2)
    }

    @Test
    fun `should perceive own output when not resting`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            """
            agency.enunciate("interim")
            agency.invoke("sub")
            """.trimIndent(),
            respondedIntent
        )

        // when
        cognize(cognizer, cogitation())

        // then
        val perceived = cognizer.perceived[1].enunciations
        assert(perceived[2].actant == Actant.SELF && perceived[2].phenomena == listOf(text("interim")))
        assert(perceived[3].actant.type == Actant.Type.COMPUTER)
    }

    @Test
    fun `should tell intent producing no phenomena that nothing is perceived and infer again`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer("println(42)", restingIntent)

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(text("rested")))
        assert(cognizer.perceived.size == 2)
        val response = cognizer.perceived[1].enunciations.last()
        assert(response.actant.type == Actant.Type.COMPUTER)
        assert(response.phenomena.first().content.startsWith("The intent evaluated to nothing"))
    }

    @Test
    fun `should respond to failing intent with impediment and infer again`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            "error(\"boom\")",
            "agency.impede(\"cannot proceed\")"
        )

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(Phenomenon.Impediment("cannot proceed")))
        assert(cognizer.perceived.size == 2)
        val response = cognizer.perceived[1].enunciations.last()
        assert(response.actant.type == Actant.Type.COMPUTER)
        assert((response.phenomena.first() as Phenomenon.Impediment).reason.contains("boom"))
    }

    @Test
    fun `should execute intent with a blank script, responding that nothing of it is perceived`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            " \n",
            "agency.enunciate(\"fetched\")"
        )

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(text("fetched")))
        assert(cognizer.perceived.size == 2)
        val response = cognizer.perceived[1].enunciations.last()
        assert(response.actant.type == Actant.Type.COMPUTER)
        assert(response.phenomena.first().content.startsWith("The intent evaluated to nothing"))
    }

    @Test
    fun `should rest with the text of an inference expressing no intent as the output`() = runTest(timeout = 5.minutes) {
        // given
        val inferences = AtomicInteger()
        val cognizer = Cognizer {
            inferences.incrementAndGet()
            Cognized(listOf(monologue("It is 42."), text("The answer is 42.")))
        }

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(text("The answer is 42.")))
        assert(inferences.get() == 1)
    }

    @Test
    fun `should respond to a tool call it could not execute, rather than resting with the text along it`() = runTest(timeout = 5.minutes) {
        // given
        val perceived = mutableListOf<Cogitation>()
        val cognizer = Cognizer { cogitation ->
            perceived += cogitation
            Cognized(
                if (perceived.size == 1) listOf(
                    text("Found it: the retry frame."),
                    Phenomenon.Impediment("Your call of execute carried no script")
                )
                else listOf(text("The answer is 42.")),
                Inference(model = "scripted", provider = "test", tokens = emptyMap())
            )
        }

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(text("The answer is 42.")))
        assert(perceived.size == 2)
        val response = perceived[1].enunciations.last()
        assert(response.actant.type == Actant.Type.COMPUTER)
        assert(response.phenomena.first() == Phenomenon.Impediment("Your call of execute carried no script"))
    }

    @Test
    fun `should answer with text after executing intents`() = runTest(timeout = 5.minutes) {
        // given
        val perceived = mutableListOf<Cogitation>()
        val cognizer = Cognizer { cogitation ->
            perceived += cogitation
            Cognized(
                if (perceived.size == 1) listOf(text("Computing it."), Phenomenon.Intent(script = "6 * 7"))
                else listOf(text("The answer is 42."))
            )
        }

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(text("The answer is 42.")))
        assert(perceived.size == 2)
        assert(perceived[1].enunciations.last().phenomena.first().content == "42")
    }

    @Test
    fun `should deliver the text of an invoked cogitation expressing no intent to its invoker`() = runTest(timeout = 5.minutes) {
        // given
        val perceived = mutableListOf<Cogitation>()
        val cognizer = Cognizer { cogitation ->
            perceived += cogitation
            Cognized(
                when {
                    cogitation.depth == 1 -> listOf(monologue("A label."), text("1: NUM"))
                    cogitation.enunciations.size == 1 -> listOf(Phenomenon.Intent(script = "agency.invoke(\"judge\")"))
                    cogitation.enunciations.size == 4 -> listOf(Phenomenon.Intent(script = respondedIntent))
                    else -> listOf(text("unexpected"))
                }
            )
        }

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(text("1: NUM")))
    }

    @Test
    fun `should enunciate impediment when neither an intent nor a text is expressed`() = runTest(timeout = 5.minutes) {
        // given
        val inferences = AtomicInteger()
        val cognizer = Cognizer {
            inferences.incrementAndGet()
            Cognized(listOf(monologue("no kotlin")))
        }

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(Phenomenon.Impediment("Neither an intent nor an answer expressed")))
        assert(inferences.get() == 1)
    }

    @Test
    fun `should impede without inferring again when inference without intent is not completed`() = runTest(timeout = 5.minutes) {
        // given
        val inferences = AtomicInteger()
        val cognizer = Cognizer {
            inferences.incrementAndGet()
            Cognized(
                listOf(text("Let's")),
                Inference(
                    model = "scripted",
                    provider = "test",
                    tokens = emptyMap(),
                    stop = Inference.Stop(Inference.Stop.Kind.TRUNCATED, "length")
                )
            )
        }

        // when
        val output = cognize(cognizer, cogitation())

        // then
        // what was expressed of the answer is delivered, followed by the impediment
        assert(output.phenomena == listOf(
            text("Let's"),
            Phenomenon.Impediment("Inference not completed, stopped by length")
        ))
        assert(inferences.get() == 1)
    }

    @Test
    fun `should infer once more after refused inference, discarding what it expressed`() = runTest(timeout = 5.minutes) {
        // given
        val perceived = mutableListOf<Cogitation>()
        val cognizer = Cognizer { cogitation ->
            perceived += cogitation
            if (perceived.size == 1) Cognized(
                listOf(monologue("I'm weighing"), text("partial")),
                Inference(
                    model = "refusing",
                    provider = "test",
                    tokens = mapOf(Inference.Tokens.OUTPUT to 7L),
                    stop = Inference.Stop(Inference.Stop.Kind.REFUSED, "refusal", category = "cyber")
                )
            ) else Cognized(
                listOf(text("answer is 42")),
                Inference(model = "falling back", provider = "test", stop = Inference.Stop(Inference.Stop.Kind.COMPLETED, "end_turn"))
            )
        }

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(text("answer is 42")))
        assert(perceived.size == 2)
        // the refused inference stays, so that what it spent is counted, and the cognizer knows it was refused
        val refused = perceived.last().enunciations.last()
        assert(refused.actant == Actant.SELF)
        assert(refused.phenomena.isEmpty())
        assert(refused.inference?.stop == Inference.Stop(Inference.Stop.Kind.REFUSED, "refusal", category = "cyber"))
    }

    @Test
    fun `should impede when inference made once more after refused inference is refused too`() = runTest(timeout = 5.minutes) {
        // given
        val inferences = AtomicInteger()
        val cognizer = Cognizer {
            inferences.incrementAndGet()
            Cognized(
                listOf(text("partial")),
                Inference(
                    model = "refusing",
                    provider = "test",
                    stop = Inference.Stop(Inference.Stop.Kind.REFUSED, "refusal", category = "cyber")
                )
            )
        }

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(Phenomenon.Impediment("Inference not completed, stopped by refusal (cyber)")))
        assert(inferences.get() == 2)
    }

    @Test
    fun `should unfold the cogitation step after step until self enunciates last`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer("\"step 1\"", "\"step 2\"", "agency.enunciate(\"after \" + agency.cogitation().enunciations.size)")

        // when
        val output = cognize(cognizer, cogitation())

        // then
        // the task, then two intents and the responses to them, then the intent enunciating the output
        assert(output.phenomena == listOf(text("after 6")))
        assert(cognizer.perceived.map { it.enunciations.size } == listOf(1, 3, 5))
    }

    @Test
    fun `should resolve declarations through agency of intent`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            "agency.enunciate(agency.resolveDeclaration(\"com.xemantic.annie.Agency\") ?: \"not found\")"
        )

        // when
        val output = cognize(cognizer, cogitation())

        // then
        val text = output.phenomena.single().content
        assert("interface Agency" in text)
        assert("suspend fun cogitation(): Cogitation" in text)
    }

    @Test
    fun `should enunciate output of process executed through agency of intent`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            """
            import com.xemantic.annie.*
            agency.enunciate(agency.bash("echo hello").output, "text/plain")
            """.trimIndent()
        )

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(text("hello\n", "text/plain")))
    }

    @Test
    fun `should perceive response to invocation of a cogitation, unfolded by the same harness`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            "agency.invoke(\"sub\")",
            respondedIntent
        )

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(text("echo: sub")))
        val perceived = cognizer.perceived[1]
        // the response to the intent, then the outcome of the cogitation it invoked
        assert(perceived.enunciations.dropLast(1).last().respondsTo == perceived.enunciations[1].phenomena.filterIsInstance<Phenomenon.Intent>().single().id)
        val response = perceived.enunciations.last()
        val id = checkNotNull(response.respondsTo)
        assert(response.id == responseId(id))
        assert(response.phenomena == listOf(text("echo: sub")))
        val invoked = store.load(id)
        assert(invoked?.invocation?.prompt == "sub")
        // the invocation is the first enunciation of the invoked cogitation, its prompt, of the same id
        assert(invoked.cogitation.enunciations.first().id == id)
        assert(invoked.cogitation.enunciations.last().actant == Actant.SELF)
    }

    @Test
    fun `should enunciate prompt of invoked cogitation by its invoker`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            "agency.invoke(\"sub\")",
            respondedIntent,
            invoked = { invoked ->
                val actant = invoked.enunciations.single().actant
                text(actant.type.name + " " + actant.name)
            }
        )

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(text("INVOKER invoker")))
    }

    @Test
    fun `should await responses to all the invocations before cognizing further`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            """
            agency.invoke("first")
            agency.invoke("second")
            """.trimIndent(),
            respondedIntent
        )

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(cognizer.perceived.size == 2)
        val responses = cognizer.perceived[1].enunciations.takeLast(2)
        assert(responses.all { it.respondsTo != null })
        assert(output.phenomena.toSet() == setOf(text("echo: first"), text("echo: second")))
    }

    @Test
    fun `should rest the cogitation with the impediment of a step which throws, e g of an inference failing`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = Cognizer { throw IllegalStateException("Request failed", java.io.IOException("Unexpected response code for CONNECT: 503")) }

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(Phenomenon.Impediment(
            "Harness failed to unfold the cogitation:\n" +
                    "java.lang.IllegalStateException: Request failed\n" +
                    "Caused by: java.io.IOException: Unexpected response code for CONNECT: 503"
        )))
    }

    @Test
    fun `should tell cognizer what cogitation spent so far with each response to its intents`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer("6 * 7", "6 * 8", restingIntent)

        // when
        cognize(cognizer, cogitation())

        // then
        // each inference consumes as many tokens as the enunciations it perceived: 1, then 3
        val responses = cognizer.perceived.last().enunciations.filter { it.actant.type == Actant.Type.COMPUTER }
        assert(responses.map { it.phenomena.last() } == listOf(
            text("Spent by this cogitation so far: 1 inference(s), 1 token(s).", "text/plain"),
            text("Spent by this cogitation so far: 2 inference(s), 4 token(s).", "text/plain")
        ))
    }

    @Test
    fun `should tell cognizer what its tree may still spend of the token budget and time only once little is left`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer("6 * 7", "6 * 8", restingIntent)
        val host = HarnessHost(cognizer, store, contents = testContents, tokenBudget = 5, timeLimit = 90.minutes, computers = computers)

        // when
        host.cognize(cogitation())

        // then
        // each inference consumes as many tokens as the enunciations it perceived: 1, then 3
        val responses = cognizer.perceived.last().enunciations.filter { it.actant.type == Actant.Type.COMPUTER }
        val first = responses[0].phenomena.last().content
        val second = responses[1].phenomena.last().content
        // a budget told at every step is spent rather than guarded, see the Fable constitution
        assert(first == "Spent by this cogitation so far: 1 inference(s), 1 token(s).")
        assert(second.startsWith("Spent by this cogitation so far: 2 inference(s), 4 token(s). Left to the whole tree of cogitations: 1 of 5 token(s)"))
        assert("A fifth or less of what you may spend is left" in second)
    }

    @Test
    fun `should impede cogitation once its time limit has passed`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer("6 * 7", restingIntent)

        // when
        val output = HarnessHost(cognizer, store, contents = testContents, timeLimit = Duration.ZERO, computers = computers).cognize(cogitation())

        // then
        assert(cognizer.perceived.isEmpty())
        assert(output.phenomena == listOf(Phenomenon.Impediment("Time limit of 0s passed")))
    }

    @Test
    fun `should impede cogitation once token budget is spent`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer("6 * 7", restingIntent)

        // when
        val output = HarnessHost(cognizer, store, contents = testContents, tokenBudget = 1, computers = computers).cognize(cogitation())

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
        val output = HarnessHost(cognizer, store, contents = testContents, tokenBudget = 2, computers = computers).cognize(cogitation())

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
            Cognized(phenomena = listOf(Phenomenon.Intent(script = intent)))
        }

        // when
        HarnessHost(cognizer, store, contents = testContents, parallelism = 1, computers = computers).cognize(cogitation())

        // then
        assert(maxInFlight.get() == 1)
    }

    @Test
    fun `should provide depth of cogitation to intent`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            "agency.invoke(\"sub at depth ${'$'}{agency.cogitation().depth}\")",
            respondedIntent,
            invoked = { Phenomenon.Intent(script = "agency.enunciate(\"depth \" + agency.cogitation().depth)") }
        )

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(text("depth 1")))
        assert(cognizer.perceived[1].invocation().prompt == "sub at depth 0")
    }

    @Test
    fun `should invoke cogitation under constitution of invoker by default`() = runTest(timeout = 5.minutes) {
        // given
        val invokedConstitutions = mutableListOf<String>()
        val cognizer = ScriptedCognizer(
            "agency.invoke(\"sub\")",
            respondedIntent,
            invoked = { invokedConstitutions += it.constitution; text("done") }
        )
        val cogitation = cogitation().copy(constitution = "Be honest")

        // when
        cognize(cognizer, cogitation)

        // then
        val invocation = cognizer.perceived[1].invocation()
        // the invocation records no constitution of its own, the invoked cogitation unfolding under the one of its invoker
        assert(invocation.constitution == null)
        assert(invokedConstitutions == listOf("Be honest"))
    }

    @Test
    fun `should record root and invoked cogitations`() = runTest(timeout = 5.minutes) {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val cognizer = ScriptedCognizer(
            "agency.invoke(\"sub\")",
            respondedIntent
        )

        // when
        HarnessHost(cognizer, store, recordingDir = recordingDir, contents = testContents, computers = computers).cognize(cogitation())

        // then
        val root = recordingDir.listDirectoryEntries().single()
        val files = root.listDirectoryEntries().map { it.name }.toSet()
        assert(files.containsAll(listOf(
            "cogitation.md", "constitution.md",
            // what the intent invoking a cogitation spent, its only result
            "001-1-human-text.md", "002-1-self-intent.kts", "003-1-computer-text.txt",
            "004-1-self-text.md", "005-1-self-intent.kts", "006-1-self-text.md"
        )))
        assert(files.none { it.startsWith("007-") })
        val invoked = root.listDirectoryEntries().single { it.isDirectory() }
        // next to the intent invoking it
        assert(invoked.name.startsWith("002-1-"))
        assert(invoked.listDirectoryEntries().any { it.name == "002-1-self-text.md" })
    }

    @Test
    fun `should record inference discarded by harness without enunciating it`() = runTest(timeout = 5.minutes) {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val inferences = AtomicInteger()
        // refused at first, so that the harness infers once more, discarding what the refused inference expressed
        val cognizer = Cognizer {
            if (inferences.incrementAndGet() == 1) Cognized(
                listOf(Phenomenon.Intent(script = "error(\"discarded\")")),
                Inference(model = "refusing", provider = "test", stop = Inference.Stop(Inference.Stop.Kind.REFUSED, "refusal"))
            ) else Cognized(
                listOf(Phenomenon.Intent(script = "agency.enunciate(\"fetched\")")),
                Inference(model = "falling back", provider = "test", stop = Inference.Stop(Inference.Stop.Kind.COMPLETED, "end_turn"))
            )
        }

        // when
        HarnessHost(cognizer, store, recordingDir = recordingDir, contents = testContents, computers = computers).cognize(cogitation())

        // then
        val root = recordingDir.listDirectoryEntries().single()
        assert(root.resolve("003-discarded-1-1-self-intent.kts").readText() == "error(\"discarded\")")
        assert(root.resolve("003-1-self-intent.kts").readText() == "agency.enunciate(\"fetched\")")
        assert(root.resolve("inferences.json").readText().contains("\"enunciation\": \"003-discarded-1\""))
    }

    @Test
    fun `should record output of root cogitation`() = runTest(timeout = 5.minutes) {
        // given
        val recordingDir = createTempDirectory("cogitations")
        val cognizer = ScriptedCognizer("agency.enunciate(\"answer is 42\")")

        // when
        HarnessHost(cognizer, store, recordingDir = recordingDir, contents = testContents, computers = computers).cognize(cogitation())

        // then
        val root = recordingDir.listDirectoryEntries().single()
        assert(root.resolve("003-1-self-text.md").readText() == "answer is 42")
    }

    @Test
    fun `should keep declarations of intent visible to the next intents of cogitation`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            """
            val answer = 6 * 7
            fun describe(n: Int) = "answer is ${'$'}n"
            "declared"
            """.trimIndent(),
            "agency.enunciate(describe(answer))"
        )

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(text("answer is 42")))
    }

    /**
     * Expresses the phenomenon of each of its [steps] on the inferences of the root cogitation, given what it perceived,
     * answering with a text once they are all expressed, recording the cogitations it perceived,
     * the cogitations it invokes being inferred by [invoked].
     */
    private class SteppingCognizer(
        private vararg val steps: (Cogitation) -> Phenomenon,
        private val invoked: (Cogitation) -> Phenomenon = { text("echo: " + it.prompt) }
    ) : Cognizer {
        val perceived = mutableListOf<Cogitation>()
        override suspend fun cognize(cogitation: Cogitation): Cognized {
            if (cogitation.depth > 0) return Cognized(listOf(invoked(cogitation)))
            perceived += cogitation
            return Cognized(listOf(steps.getOrNull(perceived.size - 1)?.invoke(cogitation) ?: text("Done.")))
        }
        /** The phenomena of the response to the intent of the step of the [index], counted from 0. */
        fun response(index: Int): List<Phenomenon> = perceived[index + 1].enunciations.last { it.respondsTo != null }.phenomena
    }

    @Test
    fun `should read the lines of a text of an earlier result in the next intent`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = SteppingCognizer(
            { Phenomenon.Intent(script = "\"one\\ntwo\\nthree\"") },
            // the task, the intent, and the response to it, whose first phenomenon is the result
            { Phenomenon.Intent(script = "agency.readText(agency.cogitation().enunciations[2].phenomena[0], 2..3)") }
        )

        // when
        cognize(cognizer, cogitation())

        // then
        assert(cognizer.response(1).first() == text("two\nthree", "text/plain"))
    }

    @Test
    fun `should read the text of the outcome of an invoked cogitation`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = SteppingCognizer(
            { Phenomenon.Intent(script = "agency.invoke(\"sub\")") },
            // the task, the intent invoking, the response to it, and the outcome following it
            { Phenomenon.Intent(script = "agency.readText(agency.cogitation().enunciations[3].phenomena.single()).uppercase()") }
        )

        // when
        cognize(cognizer, cogitation())

        // then
        assert(cognizer.response(1).first() == text("ECHO: SUB", "text/plain"))
    }

    @Test
    fun `should impede a text returned by a script whose content is kept nowhere`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = SteppingCognizer(
            { Phenomenon.Intent(script = "Phenomenon.Text(ref = \"sha256:00\", length = 1, lines = 1)") }
        )

        // when
        cognize(cognizer, cogitation())

        // then
        val impediment = cognizer.response(0).first() as Phenomenon.Impediment
        assert(impediment.reason.startsWith("No content of sha256:00 is kept"))
    }

    /** The cogitation invoked by an intent evaluates the declaration `answer` of its invoker, then answers whether it could. */
    private val evaluatingAnswer: (Cogitation) -> Phenomenon = { invoked ->
        if (invoked.enunciations.size == 1) Phenomenon.Intent(script = "answer")
        else text(if (invoked.enunciations.last().phenomena.any { it is Phenomenon.Impediment }) "not shared" else "shared")
    }

    @Test
    fun `should run invoked cogitation in the REPL of its invoker, sharing its declarations`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer("val answer = 42\nagency.invoke(\"sub\")", respondedIntent, invoked = evaluatingAnswer)

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(text("shared")))
        val invocation = cognizer.perceived[1].invocation()
        assert(invocation.computer == null && invocation.repl == null)
    }

    @Test
    fun `should run invoked cogitation started on a new computer in a REPL of its own`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            "val answer = 42\nagency.invoke(\"sub\", computer = \"GPU64GB\")",
            respondedIntent,
            invoked = evaluatingAnswer
        )

        // when
        val output = HarnessHost(cognizer, store, contents = testContents, computers = gpuComputers).cognize(cogitation())

        // then
        assert(output.phenomena == listOf(text("not shared")))
        val invocation = cognizer.perceived[1].invocation()
        val computer = invocation.computer
        assert(computer != null && computer.type == "GPU64GB" && computer.id != cognizer.perceived[0].computer.id)
        assert(invocation.repl != null && invocation.repl != cognizer.perceived[0].repl)
    }

    @Test
    fun `should run invoked cogitation in a REPL of another name, opened on the computer of its invoker`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer("val answer = 42\nagency.invoke(\"sub\", repl = \"scratch\")", respondedIntent, invoked = evaluatingAnswer)

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(text("not shared")))
    }

    @Test
    fun `should invoke independent cogitation which perceives itself as a root and cannot reach its invoker`() = runTest(timeout = 5.minutes) {
        // given
        val independent = mutableListOf<Cogitation>()
        // an independent cogitation is told apart by its prompt, as it perceives itself at the depth of a root
        val cognizer = object : Cognizer {
            val perceived = mutableListOf<Cogitation>()
            override suspend fun cognize(cogitation: Cogitation): Cognized {
                if (cogitation.enunciations.first().phenomena.first() == text("sub")) {
                    independent += cogitation
                    return Cognized(listOf(
                        if (cogitation.enunciations.size == 1) {
                            Phenomenon.Intent(script = "\"${'$'}{agency.parentCogitation() == null} ${'$'}{agency.cogitation().depth}\"")
                        } else if (cogitation.enunciations.size == 3) {
                            Phenomenon.Intent(script = "answer")
                        } else {
                            text("independent")
                        }
                    ))
                }
                perceived += cogitation
                return Cognized(listOf(
                    if (perceived.size == 1) Phenomenon.Intent(script = "val answer = 42\nagency.invoke(\"sub\", independent = true)")
                    else text("Done.")
                ))
            }
        }

        // when
        cognize(cognizer, cogitation())

        // then
        val invocation = cognizer.perceived[1].invocation()
        assert(invocation.independent && invocation.computer == null && invocation.repl != null)
        assert(cognizer.perceived[1].enunciations.single { it.respondsTo == invocation.id }.phenomena == listOf(text("independent")))
        val first = independent.first()
        assert(first.depth == 0 && first.repl == invocation.repl)
        assert(first.enunciations.single().actant.type == Actant.Type.UNIDENTIFIED)
        // what it tells of itself, and then the declaration of its invoker it cannot reach
        val told = independent[1].enunciations.last().phenomena.first()
        assert(told == text("true 0", "text/plain"))
        assert(independent[2].enunciations.last().phenomena.first() is Phenomenon.Impediment)
    }

    @Test
    fun `should respond to invocation of a computer type the host does not offer with an impediment`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = SteppingCognizer(
            { Phenomenon.Intent(script = "agency.invoke(\"sub\", computer = \"GPU64GB\")") },
            { Phenomenon.Intent(script = restingIntent) }
        )

        // when
        cognize(cognizer, cogitation())

        // then
        val id = cognizer.perceived[1].invokedId
        val outcome = cognizer.perceived[1].enunciations.single { it.respondsTo == id }.phenomena
        val reason = (outcome.single() as Phenomenon.Impediment).reason
        assert("GPU64GB" in reason && "default" in reason)
    }

    @Test
    fun `should execute intent in the REPL it names, opened by the first intent naming it`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = SteppingCognizer(
            { Phenomenon.Intent(script = "val answer = 42") },
            { Phenomenon.Intent(repl = "scratch", script = "val other = 7") },
            { Phenomenon.Intent(repl = "scratch", script = "other * 6") },
            { Phenomenon.Intent(repl = "scratch", script = "answer") },
            { Phenomenon.Intent(script = "answer") }
        )

        // when
        cognize(cognizer, cogitation())

        // then
        assert(cognizer.response(2).first() == text("42", "text/plain"))
        assert(cognizer.response(3).first() is Phenomenon.Impediment)
        assert(cognizer.response(4).first() == text("42", "text/plain"))
    }

    @Test
    fun `should execute intent on a computer started by an invocation, naming its first REPL`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = SteppingCognizer(
            { Phenomenon.Intent(script = "agency.invoke(\"sub\", computer = \"GPU64GB\", repl = \"training\")") },
            { Phenomenon.Intent(repl = "training", script = "trained") },
            { Phenomenon.Intent(script = "trained") },
            // the invoked cogitation declares in its REPL, on the computer started for it
            invoked = { invoked ->
                if (invoked.enunciations.size == 1) Phenomenon.Intent(script = "val trained = 42") else text("trained")
            }
        )

        // when
        HarnessHost(cognizer, store, contents = testContents, computers = gpuComputers).cognize(cogitation())

        // then
        assert(store.load(cognizer.perceived[1].invokedId)?.cogitation?.computer?.type == "GPU64GB")
        assert(cognizer.response(1).first() == text("42", "text/plain"))
        // declared in the REPL of the other computer, not in the one of the cogitation
        assert(cognizer.response(2).first() is Phenomenon.Impediment)
    }

    @Test
    fun `should not start a computer for a cogitation invoked in a REPL open on another computer`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = SteppingCognizer(
            { Phenomenon.Intent(repl = "scratch", script = "val here = 1") },
            { Phenomenon.Intent(script = "agency.invoke(\"sub\", computer = \"GPU64GB\", repl = \"scratch\")") },
            { Phenomenon.Intent(script = restingIntent) }
        )

        // when
        HarnessHost(cognizer, store, contents = testContents, computers = gpuComputers).cognize(cogitation())

        // then
        val reason = (cognizer.perceived[2].outcomes.single().phenomena.single() as Phenomenon.Impediment).reason
        assert("scratch" in reason && "not invoked" in reason)
    }

    @Test
    fun `should deliver outcomes in the order of invocations, once all invoked cogitations rest`() = runTest(timeout = 5.minutes) {
        // given
        val secondBuffered = CompletableDeferred<Unit>()
        // the outcome of the second cogitation is kept by the host, before the first one rests
        val observedStore = object : CogitationStore by store {
            override suspend fun save(unfolding: Unfolding) {
                store.save(unfolding)
                if (unfolding.outcomes.isNotEmpty()) secondBuffered.complete(Unit)
            }
        }
        val perceived = mutableListOf<Cogitation>()
        val cognizer = Cognizer { cogitation ->
            if (cogitation.depth > 0) {
                if (cogitation.prompt == "first") secondBuffered.await()
                Cognized(listOf(text(cogitation.prompt)))
            } else {
                perceived += cogitation
                Cognized(listOf(
                    if (perceived.size == 1) Phenomenon.Intent(script = "listOf(\"first\", \"second\").map { agency.invoke(it) }")
                    else text("Done.")
                ))
            }
        }

        // when
        HarnessHost(cognizer, observedStore, contents = testContents, computers = computers).cognize(cogitation())

        // then
        // unfolded once more, after both of them rested
        assert(perceived.size == 2)
        val outcomes = perceived[1].outcomes
        assert(outcomes.map { it.phenomena } == listOf(listOf(text("first")), listOf(text("second"))))
        assert(perceived[1].enunciations.takeLast(2) == outcomes)
    }

    @Test
    fun `should tell the cognizer the computer types the host offers in the constitution`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = SteppingCognizer()
        val cogitation = cogitation().copy(constitution = "Computers: {{computers}}")

        // when
        HarnessHost(cognizer, store, contents = testContents, computers = gpuComputers).cognize(cogitation)

        // then
        assert(cognizer.perceived.single().constitution == "Computers: `default`, `GPU64GB`")
    }

    /** Expresses the [intent] first, then answers with the text of the response to it. */
    private class EditingCognizer(private val intent: Phenomenon.Intent) : Cognizer {
        val perceived = mutableListOf<Cogitation>()
        override suspend fun cognize(cogitation: Cogitation): Cognized {
            perceived += cogitation
            return Cognized(if (perceived.size == 1) listOf(intent) else listOf(text("Done.")))
        }
        val response: List<Phenomenon> get() = perceived[1].enunciations.last().phenomena
    }

    @Test
    fun `should apply edits of intent before executing its script, responding with where`() = runTest(timeout = 5.minutes) {
        // given
        val dir = createTempDirectory()
        val source = dir.resolve("main.py").apply { writeText("def f():\n    return 1\n") }
        val created = dir.resolve("notes.md")
        val cognizer = EditingCognizer(Phenomenon.Intent(
            edits = listOf(
                Edit(path = source.toString(), old = "    return 1\n", new = "    ${'"'}${'"'}${'"'}Docstring of ${'$'}f.${'"'}${'"'}${'"'}\n    return 2\n"),
                Edit(path = created.toString(), new = "notes\n")
            ),
            script = "java.io.File(${'"'}$source${'"'}).readText()"
        ))

        // when
        cognize(cognizer, cogitation())

        // then
        assert(cognizer.response.take(2) == listOf(
            text(
                "Edited $source at line 2, as:\n" +
                    "     1\tdef f():\n" +
                    "     2\t    ${'"'}${'"'}${'"'}Docstring of ${'$'}f.${'"'}${'"'}${'"'}\n" +
                    "     3\t    return 2\n" +
                    "Wrote $created, 1 lines",
                "text/plain"
            ),
            text("def f():\n    ${'"'}${'"'}${'"'}Docstring of ${'$'}f.${'"'}${'"'}${'"'}\n    return 2\n", "text/plain")
        ))
        assert(created.readText() == "notes\n")
    }

    @Test
    fun `should apply edits of intent without script`() = runTest(timeout = 5.minutes) {
        // given
        val created = createTempDirectory().resolve("notes.md")
        val cognizer = EditingCognizer(Phenomenon.Intent(edits = listOf(Edit(path = created.toString(), new = "notes\n")), script = ""))

        // when
        cognize(cognizer, cogitation())

        // then
        assert(cognizer.response.first() == text("Wrote $created, 1 lines", "text/plain"))
        assert(created.readText() == "notes\n")
    }

    @Test
    fun `should not execute script of intent when one of its edits fails`() = runTest(timeout = 5.minutes) {
        // given
        val dir = createTempDirectory()
        val source = dir.resolve("main.py").apply { writeText("x = 1\n") }
        val executed = dir.resolve("executed")
        val cognizer = EditingCognizer(Phenomenon.Intent(
            edits = listOf(Edit(path = source.toString(), old = "y = 1\n", new = "y = 2\n")),
            script = "java.io.File(${'"'}$executed${'"'}).writeText(${'"'}yes${'"'})"
        ))

        // when
        cognize(cognizer, cogitation())

        // then
        assert(cognizer.response.first() == Phenomenon.Impediment(
            "Edit 1 of 1 failed: the old text does not occur in $source, its closest line being 1: \"x = 1\"\n" +
                    "No file was changed, and the script was not executed."
        ))
        assert(!executed.exists())
        assert(source.readText() == "x = 1\n")
    }

}

/** The prompt of this invoked cogitation, enunciated first, by its invoker. */
private val Cogitation.prompt: String get() = enunciations.first().phenomena.first().content
