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

package com.xemantic.ai.annie.cognizer

import com.fasterxml.jackson.databind.ObjectMapper
import com.xemantic.ai.annie.Phenomenon
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * The only tool offered to a model: executing the Kotlin script of its intent, see [Phenomenon.Intent],
 * coded against the API the constitution documents. A response calling no tool expresses no intent:
 * its text is the answer.
 */
object IntentTool {

    const val NAME: String = "kotlin_script"

    const val PARAMETER: String = "script"

    const val DESCRIPTION: String = "Executes your intent: a Kotlin script coded against the API of the constitution. " +
            "Its result is what the script evaluated to, or the error failing it, and the cogitations it invoked, " +
            "unless it enunciated your output, which ends the cogitation."

    const val SCRIPT_DESCRIPTION: String = "The raw source of the Kotlin script, starting with its imports."

    /** The JSON schema of the [PARAMETER], the only property of the input of the tool. */
    val parameter: Map<String, String> = mapOf("type" to "string", "description" to SCRIPT_DESCRIPTION)

    private val mapper = ObjectMapper()

    /**
     * The intent expressed by a tool call, calling the tool of the [name] with the [arguments] in JSON,
     * `null` for a call of another tool, or without a script, which expresses no intent, and is only logged.
     *
     * The intent gets an id of its own, rather than the one of the call, which is foreign to the cogitation,
     * e.g. `chatcmpl-tool-…`, and perceived by the scripts reading it, e.g. in the enunciations responding to the intent.
     * The call is replayed with the id of the intent instead, which only has to match the id its result responds to.
     */
    fun intent(name: String, arguments: String): Phenomenon.Intent? {
        val script = if (name != NAME) null else runCatching {
            mapper.readTree(arguments)?.get(PARAMETER)?.takeIf { it.isTextual }?.asText()
        }.getOrNull()
        if (script == null) logger.warn { "Tool call expressing no intent: $name $arguments" }
        return script?.let { Phenomenon.Intent(it) }
    }

}
