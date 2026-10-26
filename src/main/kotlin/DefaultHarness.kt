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

// The default harness: unfolds the cogitation by a single inference of the cognizer, executing the intent it expresses.
// The host executes it again for the next inference, unless the cogitation rests, or awaits invocations.
//
// Each intent is executed as a Kotlin script, its body evaluated as a suspending block with a `CoroutineScope`
// receiver, in the coroutine of the harness, and the value of its last expression is ignored. These properties are provided:
// - `cogitation`: Cogitation including the enunciation carrying the intent,
// - `agency`: Agency within the cogitation, invoking new ones, enunciating its output and resolving the Kotlin API.
//
// What an intent enunciates through `agency` is the output of the cogitation, enunciated by self after the intent,
// perceived by its invoker, and by the cognizer too, if the cogitation is unfolded further.
// The result of the intent is enunciated by the computer, responding to the intent: the invocations it made, in their order,
// followed by its value, unless `Unit` or `null`, or by the impediment describing its failure, if it failed.
// The cogitation rests, until another interaction progresses it, when the intent is not responded:
// when it enunciated output, invoking nothing and not failing, its value being ignored,
// or when its result is empty.
// An inference which did not complete, e.g. truncated or refused, rests the cogitation with an impediment
// enunciated by self instead, as its intent might be cut short.
import com.xemantic.ai.annie.cognizer.Cognizer
import kotlin.time.Clock

/**
 * Unfolds the [cogitation] by a single step, the parameters being the properties provided to a harness,
 * see [Cogitation]. The script of the default harness is this source, followed by the call of this function.
 */
@Suppress("unused") // only called by the script
private suspend fun unfold(
    cogitation: Cogitation,
    cognizer: Cognizer,
    executor: ScriptExecutor,
    agency: Agency
): Cogitation {
    val computer = Actant(type = Actant.Type.COMPUTER, name = "user's computer")
    val inferenceInitiated = Clock.System.now()
    val (phenomena, inference) = cognizer.cognize(cogitation)
    val inferred = cogitation + Enunciation(
        actant = Actant.SELF,
        initiated = inferenceInitiated,
        finalized = Clock.System.now(),
        phenomena = phenomena,
        inference = inference
    )
    val intent = phenomena.filterIsInstance<Phenomenon.Intent>().lastOrNull()
    val stop = inference?.stop
    fun impeded(reason: String) = inferred + Enunciation(
        actant = Actant.SELF,
        phenomena = listOf(Phenomenon.Impediment(reason))
    )
    return if (stop != null && stop.kind != Inference.Stop.Kind.COMPLETED) {
        impeded("Inference not completed, stopped by ${stop.reason}")
    } else if (intent == null) {
        impeded("No intent expressed")
    } else {
        val initiated = Clock.System.now()
        val result = executor.execute(intent.script, agency) { collecting ->
            "cogitation" provides inferred
            "agency" provides collecting
        }
        val executed = Clock.System.now()
        val output = agency.enunciated
        val enunciated = if (output.isEmpty()) inferred else inferred + Enunciation(
            actant = Actant.SELF,
            initiated = initiated,
            finalized = executed,
            phenomena = output
        )
        val rests = result.isEmpty()
                || output.isNotEmpty() && result.none { it is Phenomenon.Invocation || it is Phenomenon.Impediment }
        if (rests) {
            enunciated
        } else {
            enunciated + Enunciation(
                actant = computer,
                initiated = initiated,
                finalized = executed,
                respondsTo = intent.id,
                phenomena = result
            )
        }
    }
}
