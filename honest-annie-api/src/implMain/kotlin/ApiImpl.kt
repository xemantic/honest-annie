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

// The implementation of the API declared in Api.kt, kept out of the constitutions.

context(appendable: Appendable)
actual operator fun CharSequence?.unaryPlus(): Appending {
    appendable.append(this)
    return AppendableAppending(appendable)
}

private class AppendableAppending(private val appendable: Appendable) : Appending {
    override fun plus(value: Any?): Appending {
        appendable.append(value.toString())
        return this
    }
}
