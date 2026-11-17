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

import java.nio.file.Path
import kotlin.io.path.readText

/** The default constitution, with the placeholder of its `{{harness}}`, see `constitution.md`. */
val constitutionTemplate: String = checkNotNull(
    constitutionResource("/constitution.md")
) { "No constitution on the classpath" }

/**
 * The constitution of the [model], with the placeholder of its `{{harness}}`:
 * the one of the closest family of the model, see `constitutions`, the default [constitutionTemplate] when there is none.
 *
 * A family is named by the name of the model, without what precedes its last `/`,
 * or by what is left of it when cut at a `-`, the longest one winning:
 * `deepseek-v4_1-flash` is constituted by `constitutions/deepseek-v4_1-flash.md`,
 * then `constitutions/deepseek-v4_1.md`, then `constitutions/deepseek.md`.
 */
fun constitutionTemplate(model: String): String {
    val segments = model.substringAfterLast('/').split('-')
    return segments.indices.reversed().firstNotNullOfOrNull { last ->
        val family = segments.subList(0, last + 1).joinToString("-")
        if (family.matches(familyName)) constitutionResource("/constitutions/$family.md") else null
    } ?: constitutionTemplate
}

/**
 * The constitution template of the [file], e.g. a variant of a generated one, see `generateConstitution`,
 * whose API and cognition are therefore already filled in, or the one of the [model] when there is no [file].
 */
fun constitutionTemplate(model: String, file: Path?): String =
    file?.readText()?.trim() ?: constitutionTemplate(model)

private val familyName = Regex("""[\w.-]*\w""")

private fun constitutionResource(path: String): String? =
    Agency::class.java.getResource(path)?.readText()?.trim()

/** The constitution of a cogitation unfolded by the [harness], filled into the [template]. */
fun constitution(
    harness: String,
    template: String = constitutionTemplate
): String = template.replace("{{harness}}", harness)
