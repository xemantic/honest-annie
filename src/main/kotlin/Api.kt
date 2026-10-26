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

/**
 * The agency within the current cogitation: how its harness, and the intents it executes, act within it,
 * provided to both as `agency`, see [Cogitation]. It cannot be used once the harness has evaluated.
 */
interface Agency {
    /**
     * The constitution of the current cogitation, its parts joined as the cognizer perceives them,
     * see [Cogitation.constitution]. It embeds the source of this [harness].
     */
    val constitution: String
    /** The source of the harness unfolding the current cogitation, see [Cogitation.harness]. */
    val harness: String
    /** [Cogitation] which invoked the current one, `null` for the root one. */
    val parentCogitation: Cogitation?
    /** How deep the current cogitation is in its tree, see [Cogitation.depth]. */
    val depth: Int
    /** Invocations made during this execution of the harness, e.g. by the intent it executes. */
    val invocations: List<Phenomenon.Invocation>
    /**
     * Output enunciated during this execution of the harness, e.g. by the intent it executes,
     * to be enunciated by the harness in the cogitation it unfolds, see [Enunciation.isOutput].
     */
    val enunciated: List<Phenomenon>
    /**
     * Invokes a cogitation under the [constitution], by default this [Agency.constitution],
     * unfolded by the [harness] script, which can be a different one, e.g. a modification of this [Agency.harness].
     * The invoked cogitation is unfolded by the same cognizer, starting with the [prompt]
     * enunciated by [Actant.INVOKER].
     *
     * Returns immediately: the returned [Phenomenon.Invocation] is enunciated in the result
     * of the current intent, and the outcome of the invoked cogitation is perceived later,
     * as an enunciation responding to its [Phenomenon.Invocation.id].
     */
    fun invoke(
        prompt: String,
        constitution: String = this.constitution,
        harness: String = this.harness,
    ): Phenomenon.Invocation
    /**
     * Enunciates a [Phenomenon.Text] as the output of the current cogitation, see [enunciated],
     * which is perceived by its invoker once the cogitation rests, see [Cogitation.output].
     * Enunciated by an intent, it is not part of the result of the intent, but precedes it in the cogitation:
     * what the intent needs to perceive in the response to it is the value of its last expression.
     */
    suspend fun enunciate(text: String, contentType: String = Phenomenon.Text.MARKDOWN)
    /** Enunciates a [Phenomenon.Data] as the output of the current cogitation, like the text [enunciate]. */
    suspend fun enunciate(bytes: ByteArray, contentType: String? = null)
    /** Enunciates a [Phenomenon.Impediment] as the output of the current cogitation, like the text [enunciate]. */
    suspend fun impede(reason: String)
    /**
     * Renders the Kotlin declaration of the fully qualified [name], as available to scripts,
     * e.g. `kotlinx.coroutines.flow.Flow`: its current signature, together with the extensions of a type,
     * paged by [pageSize] declarations, the first [page] being `1`.
     * Only Kotlin declarations can be resolved: Java classes, e.g. of the JDK, have no Kotlin metadata.
     *
     * @return the rendered declaration, or `null` when nothing is found.
     */
    fun resolveDeclaration(name: String, page: Int = 1, pageSize: Int = 10): String?
    /**
     * Lists the fully qualified names of the Kotlin declarations available to scripts containing the [query],
     * to be resolved with [resolveDeclaration], paged like it.
     *
     * @return the names, or `null` when nothing is found.
     */
    fun searchDeclarations(query: String, page: Int = 1, pageSize: Int = 10): String?
}
