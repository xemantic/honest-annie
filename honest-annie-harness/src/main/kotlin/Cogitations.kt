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
import com.xemantic.annie.Cogitation
import com.xemantic.annie.Enunciation
import com.xemantic.annie.Phenomenon

// Kept out of Harness.kt, so that they are not pasted into the constitutions, see `generateConstitution`.

/** This cogitation followed by the [enunciation]. */
operator fun Cogitation.plus(enunciation: Enunciation): Cogitation = copy(
    enunciations = enunciations + enunciation
)

/**
 * Whether this is the output of its cogitation, addressed to its invoker:
 * enunciated by [Actant.SELF], not responding to anything, e.g. to an intent, or as the outcome of an invoked cogitation, and expressing no intent, but something else than a monologue,
 * e.g. what an intent enunciates through its agency, or the text of an inference answering without an intent,
 * unless the inference also expressed a tool call which could not be executed, as an impediment, see `IntentTool`.
 */
val Enunciation.isOutput: Boolean get() = actant == Actant.SELF && respondsTo == null
        && phenomena.none { it is Phenomenon.Intent } && phenomena.any { it !is Phenomenon.Monologue }
        && (inference == null || phenomena.none { it is Phenomenon.Impediment })

/** The phenomena of every [isOutput] of this cogitation, in their order, without the monologues. */
val Cogitation.output: List<Phenomenon> get() = enunciations.filter { it.isOutput }.flatMap { it.phenomena }
    .filter { it !is Phenomenon.Monologue }
