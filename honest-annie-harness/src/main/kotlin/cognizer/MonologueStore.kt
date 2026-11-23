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

import com.xemantic.ai.annie.Phenomenon
import java.util.concurrent.ConcurrentHashMap

/**
 * The exact form in which a provider expressed a [Phenomenon.Monologue], which the model having thought it
 * needs replayed as it was, e.g. to verify it, and to read its cached cogitation.
 * A technicality of the provider, kept apart from the cogitation, see [MonologueStore].
 */
sealed interface MonologueTrace {

    /** The model which produced it, the only one it can be replayed to. */
    val producedBy: String

    /** A thinking block of Claude: the [thinking] must be replayed byte-identical, otherwise the [signature] is invalid. */
    data class ClaudeThinking(
        override val producedBy: String,
        val thinking: String,
        val signature: String
    ) : MonologueTrace

    /** A redacted thinking block of Claude, revealing nothing. */
    data class ClaudeRedacted(
        override val producedBy: String,
        val data: String
    ) : MonologueTrace

    /**
     * A reasoning item of the Responses API.
     *
     * @property encryptedContent `null` when the reasoning is stored server-side.
     */
    data class OpenAIReasoning(
        override val producedBy: String,
        val itemId: String,
        val summary: List<String>,
        val encryptedContent: String?
    ) : MonologueTrace

}

/**
 * Where a cognizer keeps the [MonologueTrace] of every monologue it expressed, by [Phenomenon.Monologue.id].
 * A monologue without a trace, e.g. expressed by another cognizer, or before a restart of a host keeping them in memory,
 * is not replayed: the model then infers without what it thought, which costs the cached cogitation, not its correctness.
 */
interface MonologueStore {

    operator fun get(id: String): MonologueTrace?

    operator fun set(id: String, trace: MonologueTrace)

}

class InMemoryMonologueStore : MonologueStore {

    private val traces = ConcurrentHashMap<String, MonologueTrace>()

    override fun get(id: String): MonologueTrace? = traces[id]

    override fun set(id: String, trace: MonologueTrace) {
        traces[id] = trace
    }

}
