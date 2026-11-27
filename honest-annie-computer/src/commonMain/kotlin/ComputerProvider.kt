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

package com.xemantic.annie.computer

import com.xemantic.annie.Computer
import com.xemantic.annie.ExecutedIntent
import com.xemantic.annie.IntentContext
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.randomId

/**
 * Provides the computers the intents of cogitations execute on, see [Computer]: a plugin of the host,
 * e.g. executor processes of the host, without isolation, Docker containers, or Fly.io Machines,
 * several of them offered together by an [AggregateComputerProvider].
 *
 * Closing it stops the computers it started and which are still running.
 */
interface ComputerProvider : AutoCloseable {

    /** The types of the computers it starts, e.g. `default`, or `GPU64GB`, told to the cognizer in the constitution. */
    val types: List<String>

    /**
     * Starts the [computer], of one of its [types], failing with an [IllegalArgumentException] when of another one.
     * Its REPLs are opened once it runs, see [RunningComputer.openRepl].
     */
    suspend fun start(computer: Computer): RunningComputer

    override fun close() {}

}

/** A [computer] started by a [ComputerProvider], stopped when closed, its REPLs with it. */
interface RunningComputer : AutoCloseable {

    val computer: Computer

    /**
     * Opens a new REPL of the [id] on this computer, unique among its REPLs, keeping the declarations of the intents executed in it,
     * apart from the ones of the other REPLs, see [com.xemantic.annie.Cogitation.repl].
     */
    suspend fun openRepl(id: String = randomId()): Repl

}

/**
 * A REPL of a [RunningComputer], executing intents one after another, the declarations of each one
 * visible to the intents executed after it, released when closed.
 */
interface Repl : AutoCloseable {

    val id: String

    /**
     * Executes the [intent], see [com.xemantic.annie.ScriptExecutor.execute]: its edits, then its script,
     * a Kotlin one given an agency telling the [context] of its cogitation, whose invocations form its result,
     * and what it enunciates its output, see [ExecutedIntent], for the harness to enunciate and store.
     * Throws when the computer fails to execute it, not when the intent fails, which is its result.
     */
    suspend fun execute(intent: Phenomenon.Intent, context: IntentContext): ExecutedIntent

    override fun close() {}

}
