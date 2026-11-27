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

package com.xemantic.annie

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal actual fun sha256(content: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(content)

/** The [Contents] kept in memory, e.g. of the cogitations of a host, which lives as long as they do. */
class InMemoryContents : Contents {

    private val contents = ConcurrentHashMap<String, ByteArray>()

    override fun keep(content: ByteArray): String = refOf(content).also { contents.putIfAbsent(it, content) }

    override fun content(ref: String): ByteArray? = contents[ref]

}
