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

package com.xemantic.annie.executor

import com.xemantic.annie.ExecutedIntent
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.ref
import com.xemantic.annie.refOf
import com.xemantic.annie.textOf

/** The [Phenomenon.Text] of the [text], as an executor creates it, equal to the one of the same content wherever it is kept. */
internal fun text(text: String, contentType: String = "text/markdown"): Phenomenon.Text = textOf(text, contentType).first

/** The [Phenomenon.Data] of the [bytes], like the [text]. */
internal fun data(bytes: ByteArray, contentType: String? = null): Phenomenon.Data =
    Phenomenon.Data(ref = refOf(bytes), size = bytes.size.toLong(), contentType = contentType)

/** The text of the [phenomenon] of this intent, its content sent along its result. */
internal fun ExecutedIntent.text(phenomenon: Phenomenon): String =
    checkNotNull(contents[checkNotNull(phenomenon.ref)]) { "No content of $phenomenon sent" }.decodeToString()
