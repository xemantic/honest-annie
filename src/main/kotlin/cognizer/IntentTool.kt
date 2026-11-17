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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.xemantic.ai.annie.Edit
import com.xemantic.ai.annie.Phenomenon
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * The only tool offered to a model: applying the edits of files of its intent, and executing its Kotlin script, see [Phenomenon.Intent],
 * coded against the API the constitution documents. A response calling no tool expresses no intent:
 * its text is the answer.
 */
object IntentTool {

    const val NAME: String = "kotlin_script"

    const val PARAMETER: String = "script"

    const val DESCRIPTION: String = "Executes your intent: the edits of files, applied first, and a Kotlin script coded against the API of the constitution. " +
            "Its result is where the edits were applied, and what the script evaluated to, or the error failing it, and the cogitations it invoked, " +
            "unless it enunciated your output, which ends the cogitation."

    const val SCRIPT_DESCRIPTION: String = "The raw source of the Kotlin script, starting with its imports, executed after the edits."

    const val EDITS: String = "edits"

    const val EDITS_DESCRIPTION: String = "Edits of files, applied in their order before the script, all of them or none, " +
            "each to the file as the edits before it left it: the code of any language written as it is, never escaped for Kotlin."

    const val PATH_DESCRIPTION: String = "The path of the file, absolute, or relative to the working directory of the scripts."

    const val OLD_DESCRIPTION: String = "The text to replace, exactly as in the file, whitespace included, occurring once unless `all`: " +
            "omitted, the `new` text is written as the whole file, created when absent."

    const val NEW_DESCRIPTION: String = "The text replacing the `old` one, or the whole file without it."

    const val ALL_DESCRIPTION: String = "Whether to replace every occurrence of the `old` text, rather than its only one."

    /** The JSON schema of the [PARAMETER]. */
    val parameter: Map<String, String> = mapOf("type" to "string", "description" to SCRIPT_DESCRIPTION)

    /** The JSON schema of the properties of the input of the tool, none of them required: the [PARAMETER], and the [EDITS]. */
    val properties: Map<String, Any> = mapOf(
        PARAMETER to parameter,
        EDITS to mapOf(
            "type" to "array",
            "description" to EDITS_DESCRIPTION,
            "items" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to PATH_DESCRIPTION),
                    "old" to mapOf("type" to "string", "description" to OLD_DESCRIPTION),
                    "new" to mapOf("type" to "string", "description" to NEW_DESCRIPTION),
                    "all" to mapOf("type" to "boolean", "description" to ALL_DESCRIPTION)
                ),
                "required" to listOf("path", "new"),
                "additionalProperties" to false
            )
        )
    )

    /** The input of the call of the tool expressing the [intent], replayed: its script, and its edits, when any. */
    fun input(intent: Phenomenon.Intent): Map<String, Any> = buildMap {
        put(PARAMETER, intent.script)
        if (intent.edits.isNotEmpty()) put(EDITS, intent.edits.map { edit ->
            buildMap {
                put("path", edit.path)
                edit.old?.let { put("old", it) }
                put("new", edit.new)
                if (edit.all) put("all", true)
            }
        })
    }

    private val mapper = ObjectMapper()

    /**
     * The intent expressed by a tool call, calling the tool of the [name] with the [arguments] in JSON:
     * its [PARAMETER], or, when it is missing, the only text argument of the call, as a model may name it otherwise, e.g. `code`.
     * A call of another tool, or without a script, e.g. of arguments cut short, is an [Phenomenon.Impediment] instead,
     * responded to by the harness, so that the cognizer can express its intent again,
     * rather than its commentary along the call ending the cogitation as its answer.
     *
     * The intent gets an id of its own, rather than the one of the call, which is foreign to the cogitation,
     * e.g. `chatcmpl-tool-…`, and perceived by the scripts reading it, e.g. in the enunciations responding to the intent.
     * The call is replayed with the id of the intent instead, which only has to match the id its result responds to.
     */
    fun intent(name: String, arguments: String): Phenomenon {
        if (name != NAME) {
            logger.warn { "Call of another tool: $name $arguments" }
            return Phenomenon.Impediment("You called `$name`, but your only tool is `$NAME`, taking the script as `$PARAMETER`.")
        }
        val input = runCatching { mapper.readTree(arguments) }.getOrNull()?.takeIf { it.isObject }
        val editsNode = input?.get(EDITS)?.let { node ->
            // some models pass an array as its JSON
            if (node.isTextual) runCatching { mapper.readTree(node.asText()) }.getOrNull() ?: node else node
        }
        val edits = editsNode?.let { node ->
            val items = if (node.isArray) node.toList() else listOf(node)
            items.mapIndexed { index, item ->
                item.toEdit() ?: return Phenomenon.Impediment(
                    "Your call of `$NAME` carried an edit without its `path` and `new` texts, so nothing was executed: " +
                            "each edit is {\"path\", \"old\", \"new\", \"all\"}, only `path` and `new` being required, " +
                            "but edit ${index + 1} was ${mapper.writeValueAsString(item).take(200)}"
                )
            }
        } ?: emptyList()
        val script = input?.let {
            it.get(PARAMETER)?.takeIf { node -> node.isTextual }
                ?: it.properties().filter { (key, _) -> key != EDITS }.map { (_, value) -> value }.singleOrNull()?.takeIf { node -> node.isTextual }
        }?.asText() ?: if (edits.isNotEmpty()) "" else null
        if (script == null) {
            logger.warn { "Call of $NAME without a script: $arguments" }
            return Phenomenon.Impediment(
                "Your call of `$NAME` carried no script as `$PARAMETER`, so nothing was executed: " +
                        "its arguments begin with ${arguments.take(200)}"
            )
        }
        return Phenomenon.Intent(script, edits)
    }

    /** The edit of this JSON object, `null` when it is not one. */
    private fun JsonNode.toEdit(): Edit? {
        if (!isObject) return null
        val path = get("path")?.takeIf { it.isTextual }?.asText() ?: return null
        val new = get("new")?.takeIf { it.isTextual }?.asText() ?: return null
        val old = get("old")?.let { if (it.isNull) null else it.takeIf { node -> node.isTextual }?.asText() ?: return null }
        val all = get("all")?.let { if (it.isNull) false else it.takeIf { node -> node.isBoolean }?.asBoolean() ?: return null } ?: false
        return Edit(path = path, new = new, old = old, all = all)
    }

}
