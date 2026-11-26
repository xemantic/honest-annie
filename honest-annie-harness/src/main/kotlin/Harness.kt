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

package com.xemantic.annie.harness

import com.xemantic.annie.Actant
import com.xemantic.annie.Agency
import com.xemantic.annie.Cogitation
import com.xemantic.annie.Enunciation
import com.xemantic.annie.Inference
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.ScriptExecutor
// The harness: unfolds the cogitation by a single inference of the cognizer, executing the intent it expresses.
// The host calls it again for the next inference, unless the cogitation rests, or awaits invocations.
//
// The edits of files an intent carries, see `Edit`, are applied before its script, all of them or none,
// the response telling where, or which edit failed, the script then not being executed.
// Each intent is executed as a Kotlin script in the session of the intents of the cogitation, stateful like a Kotlin notebook,
// so that its declarations stay visible to the next intents. Its body suspends, calling suspend functions directly,
// and the value of its last expression is its result. `agency` is provided: the Agency within the
// cogitation, whose `cogitation` includes the enunciation carrying the intent, invoking new cogitations,
// enunciating its output and resolving the Kotlin API.
//
// An inference expressing text, and no intent, answers with it: the text is the output of the cogitation, which rests.
// What an intent enunciates through `agency` is the output of the cogitation too, enunciated by self after the intent,
// perceived by its invoker, and by the cognizer too, if the cogitation is unfolded further.
// The result of the intent is enunciated by the computer, responding to the intent: the invocations it made, in their order,
// followed by its value, unless `Unit` or `null`, or by the impediment describing its failure, if it failed.
// The cogitation rests, until another interaction progresses it, when the intent is not responded:
// when it enunciated output, invoking nothing and not failing, its value being ignored.
// An intent with an empty result, enunciating nothing, is responded with a text telling that nothing of it is perceived,
// e.g. what it printed, instead of resting the cogitation with no output.
// Each response ends with what the cogitation spent so far, its inferences and their tokens,
// and, once a fifth or less is left of what its host limits, what its whole tree may still spend, see `Agency.allowance`,
// so that the cognizer weighs further steps against what it already knows, and secures its result in time.
// A refused inference is followed by inferring once more, as a refusal might be a false positive of a filter of the provider,
// e.g. by a fallback model of the cognizer, see `ClaudeInference`: the refused inference stays in the cogitation,
// so that what it spent is counted, and the cognizer knows it was refused, while what it expressed is discarded.
// An inference which did not complete, e.g. truncated, or refused once more, rests the cogitation with an impediment
// enunciated by self instead, as its intent, or its answer, might be cut short.
// So does an inference expressing neither an intent, nor a text to answer with.
// A tool call which could not be executed, e.g. of another tool, or without a script, is responded by the computer
// with the impediment the cognizer perceived in it, so that the cognizer can express its intent again,
// rather than the text along the call ending the cogitation as its answer.
import com.xemantic.annie.harness.cognizer.Cognizer
import kotlin.time.Clock

/**
 * Unfolds the cogitation of the [agency] by a single step, see [Cogitation]:
 * an inference of the [cognizer], and the intent it expresses, executed by the [executor].
 * This source is part of every constitution, see `generateConstitution`, so that the cognizer knows how it is unfolded.
 */
internal suspend fun unfold(
    cognizer: Cognizer,
    executor: ScriptExecutor,
    agency: Agency
): Cogitation {
    val cogitation = agency.cogitation
    val computer = Actant(type = Actant.Type.COMPUTER, name = "user's computer")
    val nothingPerceived = Phenomenon.Text(
        "The intent evaluated to nothing, and enunciated nothing, so that nothing of it is perceived, " +
                "e.g. what it printed: evaluate to what you need to know, or enunciate your answer.",
        "text/plain"
    )
    fun spent(cogitation: Cogitation): Phenomenon.Text {
        val inferences = cogitation.enunciations.mapNotNull { it.inference }
        val allowance = agency.allowance
        val tokensLeft = allowance.tokensLeft
        val timeLeft = allowance.timeLeft
        val left = listOfNotNull(
            tokensLeft?.let { "$it of ${allowance.tokens} token(s)" },
            timeLeft?.let { "${it.inWholeMinutes} of ${allowance.time?.inWholeMinutes} min" }
        )
        val little = (tokensLeft != null && tokensLeft * 5 <= allowance.tokens!!)
                || (timeLeft != null && timeLeft * 5 <= allowance.time!!)
        return Phenomenon.Text(
            "Spent by this cogitation so far: ${inferences.size} inference(s), ${inferences.sumOf { it.total }} token(s)." +
                    // told only once little is left: a model told a large allowance at every step spends more of it
                    (if (!little) "" else " Left to the whole tree of cogitations: ${left.joinToString(", ")}." +
                            " A fifth or less of what you may spend is left: " +
                            "secure the result the task asks for before anything else, e.g. by saving or committing it."),
            "text/plain"
        )
    }
    val firstInferred: Cogitation = infer(cognizer, cogitation)
    val inferred: Cogitation = if (firstInferred.refused) infer(cognizer, firstInferred.discardingRefused()) else firstInferred
    val phenomena: List<Phenomenon> = inferred.enunciations.last().phenomena
    val inference: Inference? = inferred.enunciations.last().inference
    val intent = phenomena.filterIsInstance<Phenomenon.Intent>().lastOrNull()
    val stop = inference?.stop
    fun impeded(reason: String) = inferred.discardingRefused() + Enunciation(
        actant = Actant.SELF,
        phenomena = listOf(Phenomenon.Impediment(reason))
    )
    return if (stop != null && stop.kind != Inference.Stop.Kind.COMPLETED) {
        impeded("Inference not completed, stopped by ${stop.reason}${stop.category?.let { " ($it)" } ?: ""}")
    } else if (intent == null) {
        val callImpeded = phenomena.filterIsInstance<Phenomenon.Impediment>()
        if (callImpeded.isNotEmpty()) {
            // a tool call which could not be executed is responded by the computer, so that the cognizer expresses it again
            inferred + Enunciation(
                actant = computer,
                phenomena = callImpeded + spent(inferred)
            )
        } else if (phenomena.any { it is Phenomenon.Text }) {
            // the text of an inference expressing no intent is the answer, enunciated by self last, so the cogitation rests
            inferred
        } else {
            impeded("Neither an intent nor an answer expressed")
        }
    } else {
        val initiated = Clock.System.now()
        val (result, output) = executor.execute(intent, inferred, agency)
        val executed = Clock.System.now()
        val enunciated = if (output.isEmpty()) inferred else inferred + Enunciation(
            actant = Actant.SELF,
            initiated = initiated,
            finalized = executed,
            phenomena = output
        )
        val rests = output.isNotEmpty() && result.none { it is Phenomenon.Invocation || it is Phenomenon.Impediment }
        if (rests) {
            enunciated
        } else {
            enunciated + Enunciation(
                actant = computer,
                initiated = initiated,
                finalized = executed,
                respondsTo = intent.id,
                phenomena = result.ifEmpty { listOf(nothingPerceived) } + spent(inferred)
            )
        }
    }
}

/** The [cogitation] followed by the enunciation of self expressing what the [cognizer] inferred from it. */
private suspend fun infer(cognizer: Cognizer, cogitation: Cogitation): Cogitation {
    val initiated = Clock.System.now()
    val cognized = cognizer.cognize(cogitation)
    return cogitation + Enunciation(
        actant = Actant.SELF,
        initiated = initiated,
        finalized = Clock.System.now(),
        phenomena = cognized.phenomena,
        inference = cognized.inference
    )
}

/** Whether the last inference of this cogitation was refused. */
private val Cogitation.refused: Boolean get() = enunciations.last().inference?.stop?.kind == Inference.Stop.Kind.REFUSED

/** This cogitation keeping its refused last inference, for what it spent, and discarding what it expressed, being cut short. */
private fun Cogitation.discardingRefused(): Cogitation = if (!refused) this else copy(
    enunciations = enunciations.dropLast(1) + enunciations.last().copy(phenomena = emptyList())
)
