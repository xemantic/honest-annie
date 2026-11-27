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

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * What a tree of cogitations may spend: its [tokens], and its [time], each `null` when unlimited,
 * given by the host to the harness at each step, see [unfold], which tells it to the cognizer once little is left.
 *
 * @property spentTokens inferred so far by the whole tree, see [com.xemantic.annie.Inference.total].
 * @property started when the tree started unfolding, its [time] being measured from it.
 */
data class Allowance(
    val tokens: Long? = null,
    val spentTokens: Long = 0,
    val time: Duration? = null,
    val started: Instant = Clock.System.now()
) {
    /** The tokens left to spend, `null` when unlimited. */
    val tokensLeft: Long? get() = tokens?.let { (it - spentTokens).coerceAtLeast(0) }
    /** The time left until the [time] has passed, `null` when unlimited. */
    val timeLeft: Duration? get() = time?.let { (started + it - Clock.System.now()).coerceAtLeast(Duration.ZERO) }
}
