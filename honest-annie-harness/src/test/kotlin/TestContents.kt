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

import com.xemantic.annie.InMemoryContents
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.data
import com.xemantic.annie.monologue
import com.xemantic.annie.randomId
import com.xemantic.annie.readText
import com.xemantic.annie.text

/**
 * The content of the texts and data of the tests, shared by them, given to the host, the cognizers and the recorders they test,
 * as the same content is kept once whatever its test.
 */
val testContents = InMemoryContents()

/** The [Phenomenon.Text] of the [text], its content kept by the [testContents]. */
fun text(text: String, contentType: String = "text/markdown"): Phenomenon.Text = testContents.text(text, contentType)

/** The [Phenomenon.Data] of the [bytes], its content kept by the [testContents]. */
fun data(bytes: ByteArray, contentType: String): Phenomenon.Data = testContents.data(bytes, contentType)

/** The [Phenomenon.Monologue] of the [text], its content kept by the [testContents]. */
fun monologue(text: String, id: String = randomId()): Phenomenon.Monologue = testContents.monologue(text, id)

/** The content of this phenomenon as text, kept by the [testContents]. */
val Phenomenon.content: String get() = testContents.readText(this)
