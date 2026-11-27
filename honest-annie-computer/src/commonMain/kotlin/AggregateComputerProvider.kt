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

/**
 * Offers the computers of all the [providers], starting each one by the provider of its type,
 * a type being offered by only one of them.
 */
class AggregateComputerProvider(private val providers: List<ComputerProvider>) : ComputerProvider {

    constructor(vararg providers: ComputerProvider) : this(providers.toList())

    override val types: List<String> = providers.flatMap { it.types }

    init {
        val duplicates = types.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "Computer types offered by more than one provider: ${duplicates.joinToString()}" }
    }

    override suspend fun start(computer: Computer): RunningComputer {
        val provider = requireNotNull(providers.find { computer.type in it.types }) {
            "No provider of computers of the type ${computer.type}, but of ${types.joinToString()}"
        }
        return provider.start(computer)
    }

    /** Closes every provider, also when closing one fails, rethrowing the first failure. */
    override fun close() {
        var failure: Throwable? = null
        providers.forEach { provider ->
            try {
                provider.close()
            } catch (e: Throwable) {
                failure?.addSuppressed(e) ?: run { failure = e }
            }
        }
        failure?.let { throw it }
    }

}
