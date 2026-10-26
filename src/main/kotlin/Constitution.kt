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

/** The constitution of every cogitation, with the placeholder of its `{{harness}}`, see `constitution.md`. */
val constitutionTemplate: String = checkNotNull(
    Agency::class.java.getResource("/constitution.md")
) { "No constitution on the classpath" }.readText().trim()

/** The constitution of a cogitation unfolded by the [harness], filled into the [template]. */
fun constitution(
    harness: String,
    template: String = constitutionTemplate
): String = template.replace("{{harness}}", harness)
