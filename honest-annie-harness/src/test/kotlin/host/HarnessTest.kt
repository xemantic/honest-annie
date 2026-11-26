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

import com.xemantic.annie.Actant
import com.xemantic.annie.Cogitation
import com.xemantic.annie.Edit
import com.xemantic.annie.Enunciation
import com.xemantic.annie.Inference
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.harness.cognizer.Cognized
import com.xemantic.annie.harness.cognizer.Cognizer
import com.xemantic.annie.executor.PythonVenv
import com.xemantic.annie.executor.SessionScriptExecutor
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

    private val executors = { SessionScriptExecutor(PythonVenv::prepare) }

    /**
     * Expresses the next of its [intents] on each inference of the root cogitation, recording the cogitations it perceived,
     * each inference consuming as many input tokens as the enunciations it perceived.
     * The cogitations it invokes are unfolded by the same harness, and so inferred by [invoked]:
     * by default each one answers with its prompt, by the text of an inference expressing no intent, without being recorded.
     */
    private class ScriptedCognizer(
        private vararg val intents: String,
        private val stop: Inference.Stop? = null,
        private val invoked: (Cogitation) -> Phenomenon = { Phenomenon.Text("echo: " + it.prompt) }
    ) : Cognizer {
        val perceived = mutableListOf<Cogitation>()
        override suspend fun cognize(cogitation: Cogitation): Cognized {
            if (cogitation.depth > 0) return Cognized(listOf(invoked(cogitation)))
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
    ) = HarnessHost(cognizer, store, executors = executors).cognize(cogitation)

    private fun cogitation() = Cogitation(
        constitution = "",
        enunciations = listOf(Enunciation(
            actant = Actant(type = Actant.Type.HUMAN, name = "user"),
            phenomena = listOf(Phenomenon.Text("test"))
        ))
    )

    /** Intent enunciating output, which ends the loop of inferences. */
    private val restingIntent = "agency.enunciate(\"rested\")"

    /** Intent enunciating the texts of all the responses to the invocations of the cogitation. */
    private val respondedIntent = """
        import com.xemantic.annie.*
        val invocations = agency.cogitation.enunciations.flatMap { it.phenomena }
            .filterIsInstance<Phenomenon.Invocation>().map { it.id }
        agency.cogitation.enunciations.filter { it.respondsTo in invocations }.forEach { response ->
            agency.enunciate((response.phenomena.first() as Phenomenon.Text).text)
        }
    """.trimIndent()

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
        assert(output.phenomena == listOf(Phenomenon.Text("answer is 42")))
        assert(cognizer.perceived.size == 2)
        val perceived = cognizer.perceived.last().enunciations
        val intent = perceived[perceived.size - 2].phenomena.single() as Phenomenon.Intent
        val response = perceived.last()
        assert(response.actant.type == Actant.Type.COMPUTER)
        assert(response.respondsTo == intent.id)
        assert(response.phenomena.first() == Phenomenon.Text("42", "text/plain"))
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
        assert(output.phenomena == listOf(Phenomenon.Text("interim"), Phenomenon.Text("echo: sub")))
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
        assert(perceived[2].actant == Actant.SELF && perceived[2].phenomena == listOf(Phenomenon.Text("interim")))
        assert(perceived[3].actant.type == Actant.Type.COMPUTER)
    }

    @Test
    fun `should tell intent producing no phenomena that nothing is perceived and infer again`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer("println(42)", restingIntent)

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(Phenomenon.Text("rested")))
        assert(cognizer.perceived.size == 2)
        val response = cognizer.perceived[1].enunciations.last()
        assert(response.actant.type == Actant.Type.COMPUTER)
        assert((response.phenomena.first() as Phenomenon.Text).text.startsWith("The intent evaluated to nothing"))
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
        assert(output.phenomena == listOf(Phenomenon.Text("fetched")))
        assert(cognizer.perceived.size == 2)
        val response = cognizer.perceived[1].enunciations.last()
        assert(response.actant.type == Actant.Type.COMPUTER)
        assert((response.phenomena.first() as Phenomenon.Text).text.startsWith("The intent evaluated to nothing"))
    }

    @Test
    fun `should rest with the text of an inference expressing no intent as the output`() = runTest(timeout = 5.minutes) {
        // given
        val inferences = AtomicInteger()
        val cognizer = Cognizer {
            inferences.incrementAndGet()
            Cognized(listOf(Phenomenon.Monologue("It is 42."), Phenomenon.Text("The answer is 42.")))
        }

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(Phenomenon.Text("The answer is 42.")))
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
                    Phenomenon.Text("Found it: the retry frame."),
                    Phenomenon.Impediment("Your call of kotlin_script carried no script")
                )
                else listOf(Phenomenon.Text("The answer is 42.")),
                Inference(model = "scripted", provider = "test", tokens = emptyMap())
            )
        }

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(Phenomenon.Text("The answer is 42.")))
        assert(perceived.size == 2)
        val response = perceived[1].enunciations.last()
        assert(response.actant.type == Actant.Type.COMPUTER)
        assert(response.phenomena.first() == Phenomenon.Impediment("Your call of kotlin_script carried no script"))
    }

    @Test
    fun `should answer with text after executing intents`() = runTest(timeout = 5.minutes) {
        // given
        val perceived = mutableListOf<Cogitation>()
        val cognizer = Cognizer { cogitation ->
            perceived += cogitation
            Cognized(
                if (perceived.size == 1) listOf(Phenomenon.Text("Computing it."), Phenomenon.Intent("6 * 7"))
                else listOf(Phenomenon.Text("The answer is 42."))
            )
        }

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(Phenomenon.Text("The answer is 42.")))
        assert(perceived.size == 2)
        assert((perceived[1].enunciations.last().phenomena.first() as Phenomenon.Text).text == "42")
    }

    @Test
    fun `should deliver the text of an invoked cogitation expressing no intent to its invoker`() = runTest(timeout = 5.minutes) {
        // given
        val perceived = mutableListOf<Cogitation>()
        val cognizer = Cognizer { cogitation ->
            perceived += cogitation
            Cognized(
                when {
                    cogitation.depth == 1 -> listOf(Phenomenon.Monologue("A label."), Phenomenon.Text("1: NUM"))
                    cogitation.enunciations.size == 1 -> listOf(Phenomenon.Intent("agency.invoke(\"judge\").id"))
                    else -> listOf(Phenomenon.Intent(respondedIntent))
                }
            )
        }

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(Phenomenon.Text("1: NUM")))
    }

    @Test
    fun `should enunciate impediment when neither an intent nor a text is expressed`() = runTest(timeout = 5.minutes) {
        // given
        val inferences = AtomicInteger()
        val cognizer = Cognizer {
            inferences.incrementAndGet()
            Cognized(listOf(Phenomenon.Monologue("no kotlin")))
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
                listOf(Phenomenon.Text("Let's")),
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
            Phenomenon.Text("Let's"),
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
                listOf(Phenomenon.Monologue("I'm weighing"), Phenomenon.Text("partial")),
                Inference(
                    model = "refusing",
                    provider = "test",
                    tokens = mapOf(Inference.Tokens.OUTPUT to 7L),
                    stop = Inference.Stop(Inference.Stop.Kind.REFUSED, "refusal", category = "cyber")
                )
            ) else Cognized(
                listOf(Phenomenon.Text("answer is 42")),
                Inference(model = "falling back", provider = "test", stop = Inference.Stop(Inference.Stop.Kind.COMPLETED, "end_turn"))
            )
        }

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(Phenomenon.Text("answer is 42")))
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
                listOf(Phenomenon.Text("partial")),
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
        val cognizer = ScriptedCognizer("\"step 1\"", "\"step 2\"", "agency.enunciate(\"after \" + agency.cogitation.enunciations.size)")

        // when
        val output = cognize(cognizer, cogitation())

        // then
        // the task, then two intents and the responses to them, then the intent enunciating the output
        assert(output.phenomena == listOf(Phenomenon.Text("after 6")))
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
        val text = (output.phenomena.single() as Phenomenon.Text).text
        assert("interface Agency" in text)
        assert("val cogitation: Cogitation" in text)
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
        assert(output.phenomena == listOf(Phenomenon.Text("hello\n", "text/plain")))
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
        assert(output.phenomena == listOf(Phenomenon.Text("echo: sub")))
        val perceived = cognizer.perceived[1].enunciations
        val invocation = perceived[perceived.size - 2].phenomena.first() as Phenomenon.Invocation
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
        val cognizer = ScriptedCognizer(
            "agency.invoke(\"sub\")",
            respondedIntent,
            invoked = { invoked ->
                val actant = invoked.enunciations.single().actant
                Phenomenon.Text(actant.type.name + " " + actant.name)
            }
        )

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(Phenomenon.Text("INVOKER invoker")))
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
        assert(output.phenomena.toSet() == setOf(Phenomenon.Text("echo: first"), Phenomenon.Text("echo: second")))
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
            Phenomenon.Text("Spent by this cogitation so far: 1 inference(s), 1 token(s).", "text/plain"),
            Phenomenon.Text("Spent by this cogitation so far: 2 inference(s), 4 token(s).", "text/plain")
        ))
    }

    @Test
    fun `should tell cognizer what its tree may still spend of the token budget and time only once little is left`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer("6 * 7", "6 * 8", restingIntent)
        val host = HarnessHost(cognizer, store, tokenBudget = 5, timeLimit = 90.minutes, executors = executors)

        // when
        host.cognize(cogitation())

        // then
        // each inference consumes as many tokens as the enunciations it perceived: 1, then 3
        val responses = cognizer.perceived.last().enunciations.filter { it.actant.type == Actant.Type.COMPUTER }
        val first = (responses[0].phenomena.last() as Phenomenon.Text).text
        val second = (responses[1].phenomena.last() as Phenomenon.Text).text
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
        val output = HarnessHost(cognizer, store, timeLimit = Duration.ZERO, executors = executors).cognize(cogitation())

        // then
        assert(cognizer.perceived.isEmpty())
        assert(output.phenomena == listOf(Phenomenon.Impediment("Time limit of 0s passed")))
    }

    @Test
    fun `should impede cogitation once token budget is spent`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer("6 * 7", restingIntent)

        // when
        val output = HarnessHost(cognizer, store, tokenBudget = 1, executors = executors).cognize(cogitation())

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
        val output = HarnessHost(cognizer, store, tokenBudget = 2, executors = executors).cognize(cogitation())

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
        HarnessHost(cognizer, store, parallelism = 1, executors = executors).cognize(cogitation())

        // then
        assert(maxInFlight.get() == 1)
    }

    @Test
    fun `should provide depth of cogitation to intent`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            "agency.invoke(\"sub at depth ${'$'}{agency.cogitation.depth}\")",
            respondedIntent,
            invoked = { Phenomenon.Intent("agency.enunciate(\"depth \" + agency.cogitation.depth)") }
        )

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(Phenomenon.Text("depth 1")))
        val invocation = cognizer.perceived[1].enunciations.flatMap { it.phenomena }
            .filterIsInstance<Phenomenon.Invocation>().single()
        assert(invocation.prompt == "sub at depth 0")
    }

    @Test
    fun `should invoke cogitation under constitution of invoker by default`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            "agency.invoke(\"sub\")",
            respondedIntent
        )
        val cogitation = cogitation().copy(constitution = "Be honest")

        // when
        cognize(cognizer, cogitation)

        // then
        val invocation = cognizer.perceived[1].enunciations.flatMap { it.phenomena }
            .filterIsInstance<Phenomenon.Invocation>().single()
        assert(invocation.constitution == "Be honest")
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
        HarnessHost(cognizer, store, recordingDir, executors = executors).cognize(cogitation())

        // then
        val root = recordingDir.listDirectoryEntries().single()
        val files = root.listDirectoryEntries().map { it.name }.toSet()
        assert(files.containsAll(listOf(
            "cogitation.md", "constitution.md",
            "001-1-human-text.md", "002-1-self-intent.kts", "003-1-computer-invocation.md",
            "004-1-self-text.md", "005-1-self-intent.kts", "006-1-self-text.md"
        )))
        assert(files.none { it.startsWith("007-") })
        val invoked = root.listDirectoryEntries().single { it.isDirectory() }
        assert(invoked.name.startsWith("003-1-"))
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
                listOf(Phenomenon.Intent("error(\"discarded\")")),
                Inference(model = "refusing", provider = "test", stop = Inference.Stop(Inference.Stop.Kind.REFUSED, "refusal"))
            ) else Cognized(
                listOf(Phenomenon.Intent("agency.enunciate(\"fetched\")")),
                Inference(model = "falling back", provider = "test", stop = Inference.Stop(Inference.Stop.Kind.COMPLETED, "end_turn"))
            )
        }

        // when
        HarnessHost(cognizer, store, recordingDir, executors = executors).cognize(cogitation())

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
        HarnessHost(cognizer, store, recordingDir, executors = executors).cognize(cogitation())

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
        assert(output.phenomena == listOf(Phenomenon.Text("answer is 42")))
    }

    @Test
    fun `should not share declarations of intent with invoked cogitation`() = runTest(timeout = 5.minutes) {
        // given
        val cognizer = ScriptedCognizer(
            "val answer = 42\nagency.invoke(\"sub\")",
            respondedIntent,
            // the invoked cogitation evaluates the declaration of its invoker, then answers whether it could
            invoked = { invoked ->
                if (invoked.enunciations.size == 1) Phenomenon.Intent("answer")
                else Phenomenon.Text(if (invoked.enunciations.last().phenomena.any { it is Phenomenon.Impediment }) "not shared" else "shared")
            }
        )

        // when
        val output = cognize(cognizer, cogitation())

        // then
        assert(output.phenomena == listOf(Phenomenon.Text("not shared")))
    }

    /** Expresses the [intent] first, then answers with the text of the response to it. */
    private class EditingCognizer(private val intent: Phenomenon.Intent) : Cognizer {
        val perceived = mutableListOf<Cogitation>()
        override suspend fun cognize(cogitation: Cogitation): Cognized {
            perceived += cogitation
            return Cognized(if (perceived.size == 1) listOf(intent) else listOf(Phenomenon.Text("Done.")))
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
            script = "java.io.File(${'"'}$source${'"'}).readText()",
            edits = listOf(
                Edit(path = source.toString(), old = "    return 1\n", new = "    ${'"'}${'"'}${'"'}Docstring of ${'$'}f.${'"'}${'"'}${'"'}\n    return 2\n"),
                Edit(path = created.toString(), new = "notes\n")
            )
        ))

        // when
        cognize(cognizer, cogitation())

        // then
        assert(cognizer.response.take(2) == listOf(
            Phenomenon.Text(
                "Edited $source at line 2, as:\n" +
                    "     1\tdef f():\n" +
                    "     2\t    ${'"'}${'"'}${'"'}Docstring of ${'$'}f.${'"'}${'"'}${'"'}\n" +
                    "     3\t    return 2\n" +
                    "Wrote $created, 1 lines",
                "text/plain"
            ),
            Phenomenon.Text("def f():\n    ${'"'}${'"'}${'"'}Docstring of ${'$'}f.${'"'}${'"'}${'"'}\n    return 2\n", "text/plain")
        ))
        assert(created.readText() == "notes\n")
    }

    @Test
    fun `should apply edits of intent without script`() = runTest(timeout = 5.minutes) {
        // given
        val created = createTempDirectory().resolve("notes.md")
        val cognizer = EditingCognizer(Phenomenon.Intent(script = "", edits = listOf(Edit(path = created.toString(), new = "notes\n"))))

        // when
        cognize(cognizer, cogitation())

        // then
        assert(cognizer.response.first() == Phenomenon.Text("Wrote $created, 1 lines", "text/plain"))
        assert(created.readText() == "notes\n")
    }

    @Test
    fun `should not execute script of intent when one of its edits fails`() = runTest(timeout = 5.minutes) {
        // given
        val dir = createTempDirectory()
        val source = dir.resolve("main.py").apply { writeText("x = 1\n") }
        val executed = dir.resolve("executed")
        val cognizer = EditingCognizer(Phenomenon.Intent(
            script = "java.io.File(${'"'}$executed${'"'}).writeText(${'"'}yes${'"'})",
            edits = listOf(Edit(path = source.toString(), old = "y = 1\n", new = "y = 2\n"))
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
private val Cogitation.prompt: String get() = (enunciations.first().phenomena.first() as Phenomenon.Text).text
