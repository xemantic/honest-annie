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
 * A family is named by the name of the model in lowercase, e.g. of `moonshotai/Kimi-K3`, without what precedes its last `/`,
 * or by what is left of it when cut at a `-`, the longest one winning:
 * `deepseek-v4_1-flash` is constituted by `constitutions/deepseek-v4_1-flash.md`,
 * then `constitutions/deepseek-v4_1.md`, then `constitutions/deepseek.md`.
 */
fun constitutionTemplate(model: String): String {
    val segments = model.substringAfterLast('/').lowercase().split('-')
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

/**
 * The constitution of a cogitation unfolded by the [harness], filled into the [template],
 * telling how many times an inference calls the tool of intents, once, or several times when [parallelIntents] are allowed,
 * as the cognizer is told by its API, see `ClaudeInference` and `OpenAIInference`.
 */
fun constitution(
    harness: String,
    template: String = constitutionTemplate,
    parallelIntents: Boolean = false
): String = template
    .replace("{{harness}}", harness)
    .replace("{{calls}}", if (parallelIntents) PARALLEL_CALLS else SINGLE_CALL)

/** How an inference calls the tool of intents, unless parallel intents are allowed, see [constitution]. */
internal const val SINGLE_CALL: String = "Call it at most once per inference, which ends with the call."

/**
 * How an inference calls the tool of intents when parallel intents are allowed, see [constitution]:
 * models checked each part of a change by a call of its own, yet only an exception stops the calls after it, not a failing check.
 */
internal const val PARALLEL_CALLS: String =
    "Call it once per inference, or several times, e.g. once for each independent part of a change, " +
        "with the edits of that part and the script checking it:\n" +
        "the calls are executed in their order, and when one fails, an edit of it not applying, or its script throwing, " +
        "the calls after it are not executed, nor is any call of an inference carrying a call which cannot be executed.\n" +
        "A failing check is no failure of a script unless it throws, so end a script the calls after it depend on with a check, " +
        "e.g. `check(r.exitCode == 0) { r.output }` after `val r = agency.bash(\"cd /app && python -m pytest -q\")`.\n" +
        "The inference ends with its calls."

