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

import com.xemantic.annie.Agency
import com.xemantic.annie.Computer
import com.xemantic.annie.Contents
import com.xemantic.annie.ExecutedIntent
import com.xemantic.annie.IntentContext
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.ScriptExecutor
import com.xemantic.annie.computer.ComputerProvider
import com.xemantic.annie.computer.Repl
import com.xemantic.annie.computer.RunningComputer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * The computers started for a tree of cogitations, and the REPLs opened on them, see [Computer]:
 * the executor of every intent of the tree, executing it in the REPL it names, by default the one of its cogitation,
 * see [Phenomenon.Intent.repl], one intent at a time in each REPL,
 * as its declarations, and its `agency`, belong to the intent it executes.
 * Each name of a REPL names a single one in the tree, on the computer it was first given to,
 * the first REPL of a computer started, see [start], or else the computer of the cogitation of the first intent naming it.
 *
 * A computer is started by the [provider] when an intent first executes on it, and a REPL opened on it
 * when an intent first executes in it, both kept until the tree of cogitations rests, see [close],
 * as the cogitations invoked by its cogitation share them.
 * The content of the phenomena an intent reads is the one the [contents] of the host keep.
 */
internal class Computers(
    private val provider: ComputerProvider,
    private val contents: Contents
) : ScriptExecutor {

    private class OpenRepl(val repl: Repl) {
        val mutex = Mutex()
    }

    /** A computer started for the tree, by the [provider] once an intent first executes on it. */
    private inner class Started(val computer: Computer) {

        private val mutex = Mutex()

        private var running: RunningComputer? = null

        private val repls = HashMap<String, OpenRepl>()

        @Volatile
        private var closed = false

        suspend fun repl(id: String): OpenRepl = mutex.withLock {
            check(!closed) { "The computer ${computer.id} is stopped" }
            val running = running ?: provider.start(computer).also { running = it }
            repls.getOrPut(id) { OpenRepl(running.openRepl(id)) }
        }

        fun close() {
            closed = true
            repls.values.forEach { it.repl.close() }
            running?.close()
        }

    }

    /** The computers started, by their id. */
    private val started = ConcurrentHashMap<String, Started>()

    /** The computers of the REPLs, by the name of the REPL. */
    private val hosts = ConcurrentHashMap<String, Started>()

    /**
     * Starts the [computer], its first REPL being the [repl], unless it is already running,
     * returning whether the name of the [repl] is its own, rather than of a REPL of another computer.
     */
    fun start(computer: Computer, repl: String): Boolean {
        val running = started.computeIfAbsent(computer.id) { Started(computer) }
        return hosts.putIfAbsent(repl, running).let { it == null || it === running }
    }

    /** The computer the [repl] of this name is open on, or given to, `null` when no cogitation of the tree has it yet. */
    fun hosting(repl: String): Computer? = hosts[repl]?.computer

    // the edits are applied by the computer, to its files
    override suspend fun execute(
        intent: Phenomenon.Intent,
        agency: Agency
    ): ExecutedIntent {
        val cogitation = agency.cogitation()
        val name = intent.repl ?: cogitation.repl
        val own = started[cogitation.computer.id] ?: return notRunning(cogitation.computer)
        val open = hosts.computeIfAbsent(name) { own }.repl(name)
        val context = object : IntentContext {
            override suspend fun cogitation() = cogitation
            override suspend fun ancestorCogitations() = agency.ancestorCogitations()
            override suspend fun content(ref: String) = contents.content(ref)
        }
        return open.mutex.withLock { open.repl.execute(intent, context) }
    }

    // the script of an intent of no edits, as the edits are applied by the computer
    override suspend fun executeScript(
        intent: Phenomenon.Intent,
        agency: Agency
    ): ExecutedIntent = execute(intent.copy(edits = emptyList()), agency)

    private fun notRunning(computer: Computer) = ExecutedIntent(listOf(Phenomenon.Impediment(
        "The computer ${computer.id} of the cogitation is not running, " +
            "so that neither the edits of the intent were applied, nor its script executed."
    )))

    /** Stops every computer started, and closes the REPLs opened on them, once the tree of cogitations rests. */
    override fun close() {
        started.values.forEach { it.close() }
    }

}
