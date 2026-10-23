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
// The host executes it again for the next inference, unless the intent produced no phenomena, or awaits invocations.
//
// Each intent is executed as a Kotlin script, its body evaluated as a suspending block with a `CoroutineScope`
// receiver, in the coroutine of the harness, and the value of its last expression is ignored. These properties are provided:
// - `cogitation`: Cogitation including the enunciation carrying the intent,
// - `agency`: Agency within the cogitation, invoking new ones and enunciating its output.
//
// The result of an intent is enunciated by the computer, responding to the intent: the phenomena it enunciated
// and invoked, in their order, followed by the impediment describing its failure, if it failed.
// An intent producing no phenomena is not responded: the cogitation rests until another interaction progresses it.
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
    val phenomena = cognizer.cognize(cogitation)
    val inferred = cogitation + Enunciation(
        actant = Actant.SELF,
        initiated = inferenceInitiated,
        finalized = Clock.System.now(),
        phenomena = phenomena
    )
    val intent = phenomena.filterIsInstance<Phenomenon.Intent>().lastOrNull()
    return if (intent == null) {
        agency.impede("No intent expressed")
        inferred
    } else {
        val initiated = Clock.System.now()
        val result = executor.execute(intent.script, agency) { collecting ->
            "cogitation" provides inferred
            "agency" provides collecting
        }
        if (result.isEmpty()) {
            inferred
        } else {
            inferred + Enunciation(
                actant = computer,
                initiated = initiated,
                finalized = Clock.System.now(),
                respondsTo = intent.id,
                phenomena = result
            )
        }
    }
}
