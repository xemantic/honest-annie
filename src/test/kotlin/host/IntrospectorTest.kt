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

package com.xemantic.ai.annie.host

import com.xemantic.kotlin.test.assert
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

class IntrospectorTest {

    @Test
    fun `should resolve the same declaration in parallel`() = runTest(timeout = 5.minutes) {
        // given
        val introspector = lazyKotlinIntrospector()

        // when
        val resolved = (1..8).map {
            async(Dispatchers.Default) {
                introspector.resolve("com.xemantic.ai.annie.Agency")
            }
        }.awaitAll()

        // then
        assert(resolved.first() != null && "interface Agency" in resolved.first()!!)
        assert(resolved.toSet().size == 1)
    }

    @Test
    fun `should search declarations of the classpath`() = runTest(timeout = 5.minutes) {
        // given
        val introspector = lazyKotlinIntrospector()

        // when
        val found = introspector.search("HarnessHost")

        // then
        assert(found != null && "com.xemantic.ai.annie.host.HarnessHost" in found)
    }

}
