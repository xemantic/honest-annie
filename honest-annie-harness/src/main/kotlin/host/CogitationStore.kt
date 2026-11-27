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

import com.xemantic.annie.Cogitation
import com.xemantic.annie.Enunciation
import com.xemantic.annie.Invocation
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A [cogitation] being unfolded by the [HarnessHost], the unit of its persistence.
 *
 * Everything still to be done can be derived from unfoldings: each of the [awaited] invocations
 * of a cogitation is pending, and is unfolded by an [Unfolding] of its own.
 *
 * @property id the id of the [invocation] which started it, the one of the first enunciation of its cogitation, a random one for a root.
 * @property parent the id of the unfolding whose [invocation] started this one, `null` for a root.
 * @property invoked the cogitations invoked by the intents of this one, in their order.
 * @property outcomes the responses to the [invoked] cogitations which rested, kept until all of them do,
 * to be appended to the [cogitation] together, in the order of their invocations.
 */
data class Unfolding(
    val id: String,
    val cogitation: Cogitation,
    val parent: String? = null,
    val invocation: Invocation? = null,
    val invoked: List<Invocation> = emptyList(),
    val outcomes: List<Enunciation> = emptyList(),
    val initiated: Instant = Clock.System.now()
) {
    /** The [invoked] cogitations whose outcome is neither enunciated in the [cogitation], nor among the [outcomes], see [Enunciation.respondsTo]. */
    val awaited: List<Invocation> get() {
        val responded = (cogitation.enunciations + outcomes).mapNotNullTo(mutableSetOf()) { it.respondsTo }
        return invoked.filterNot { it.id in responded }
    }
}

interface CogitationStore {
    suspend fun save(unfolding: Unfolding)
    suspend fun load(id: String): Unfolding?
}

class InMemoryCogitationStore : CogitationStore {

    private val unfoldings = ConcurrentHashMap<String, Unfolding>()

    override suspend fun save(unfolding: Unfolding) {
        unfoldings[unfolding.id] = unfolding
    }

    override suspend fun load(id: String): Unfolding? = unfoldings[id]

}
