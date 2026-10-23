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

package com.xemantic.ai.annie.cognizer

import com.xemantic.ai.annie.Cogitation
import com.xemantic.ai.annie.Inference
import com.xemantic.ai.annie.Phenomenon

/**
 * The cognizer unfolding every cogitation, e.g. by inferring with a model through its API.
 * Hidden from intents, together with the specificity of its model, it cognizes
 * only through the harness, provided to it as `cognizer`, see [com.xemantic.ai.annie.Cogitation].
 */
fun interface Cognizer {

    /** A single inference, see [Cognized]. */
    suspend fun cognize(cogitation: Cogitation): Cognized

}

/**
 * @property phenomena of a single inference, e.g. a [Phenomenon.Monologue] followed by a [Phenomenon.Intent].
 * @property inference expressing the [phenomena], `null` when nothing was inferred, e.g. by a scripted cognizer.
 */
data class Cognized(
    val phenomena: List<Phenomenon>,
    val inference: Inference? = null
)
