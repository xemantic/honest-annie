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

package com.xemantic.annie.harness.cognizer

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.xemantic.annie.Edit
import com.xemantic.annie.Phenomenon
import com.xemantic.ai.tool.schema.generator.jsonSchemaOf
import com.xemantic.ai.tool.schema.meta.Description
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

/**
 * The only tool offered to a model: applying the edits of files of its intent, and executing its Kotlin script, see [Phenomenon.Intent],
 * coded against the API the constitution documents. A response calling no tool expresses no intent:
 * its text is the answer.
 */
object IntentTool {

    private val mapper = ObjectMapper()

    const val NAME: String = "kotlin_script"

    const val PARAMETER: String = "script"

    const val DESCRIPTION: String = "Executes your intent: the edits of files, applied first, and a Kotlin script coded against the API of the constitution. " +
            "Its result is where the edits were applied, and what the script evaluated to, or the error failing it, and the cogitations it invoked, " +
            "unless it enunciated your output, which ends the cogitation."

    const val EDITS: String = "edits"

    /** The hint of an impediment of edits without a `path`, as DeepSeek leaves it out of every edit of a call, meaning the file of an earlier call. */
    const val PATH_HINT: String = "give every edit its own `path`, even one changing the file of the edit before it, or of an earlier call"

    /**
     * The input of the tool, only describing its JSON schema:
     * the input is parsed leniently by [intent] instead, as models send malformed edits a decoding would only refuse.
     */
    @Serializable
    private class Input(
        @Description("The raw source of the Kotlin script, starting with its imports, executed after the edits.")
        val script: String? = null,
        @Description(
            "Edits of files, applied in their order before the script, all of them or none, " +
                "each to the file as the edits before it left it: the code of any language written as it is, never escaped for Kotlin."
        )
        val edits: List<EditInput>? = null
    )

    /** An edit of the [Input], see [Edit]. */
    @Serializable
    private class EditInput(
        @Description(
            "The path of the file, absolute, or relative to the working directory of the scripts, " +
                "given in every edit, even one changing the file of the edit before it."
        )
        val path: String,
        @Description(
            "The text to replace, exactly as in the file, whitespace included, occurring once unless `all`: " +
                "omitted, the `new` text is written as the whole file, created when absent."
        )
        val old: String? = null,
        @Description("The text replacing the `old` one, or the whole file without it.")
        val new: String,
        @Description("Whether to replace every occurrence of the `old` text, rather than its only one.")
        val all: Boolean = false
    )

    /** The JSON schema of the input of the tool, generated of the [Input], with its edits inlined, and no other properties than theirs. */
    private val schema: Map<String, Any> = mapper.readValue(
        Json.encodeToString(jsonSchemaOf<Input>(additionalProperties = false, inlineRefs = true)),
        object : TypeReference<Map<String, Any>>() {}
    )

    /** The JSON schema of the properties of the input of the tool, none of them required: the [PARAMETER], and the [EDITS]. */
    @Suppress("UNCHECKED_CAST")
    val properties: Map<String, Any> = schema.getValue("properties") as Map<String, Any>

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
            val sent = if (node.isArray) node.toList() else listOf(node)
            val items = sent.withPathOfOthers()
            val malformed = items.mapIndexedNotNull { index, item ->
                if (item.toEdit() != null) null else "edit ${index + 1} of ${items.size} ${item.malformation(shape = sent[index])}"
            }
            if (malformed.isNotEmpty()) {
                logger.warn { "Call of $NAME with malformed edits: $arguments" }
                return Phenomenon.Impediment(
                    "Your call of `$NAME` carried a malformed edit, so nothing was executed: " +
                            "each edit is {\"path\", \"old\", \"new\", \"all\"}, `path` and `new` being required texts, `old` a text, and `all` a boolean, " +
                            "but ${malformed.joinToString(", and ")}" +
                            if (items.any { it.isObject && it.get("path") == null }) ": $PATH_HINT" else ""
                )
            }
            items.mapNotNull { it.toEdit() }
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

    /**
     * These items of edits, each object without a `path` given the one of the others, when they all name a single file,
     * as models leave out the path of further edits of the file they are editing, e.g. DeepSeek;
     * when they name several files, or none, an edit without a path stays malformed, as its file is not known.
     */
    private fun List<JsonNode>.withPathOfOthers(): List<JsonNode> {
        val paths = mapNotNull { item -> item.get("path")?.takeIf { it.isTextual }?.asText() }.distinct()
        val path = paths.singleOrNull() ?: return this
        return map { item ->
            if (item.isObject && item.get("path") == null) (item.deepCopy<JsonNode>() as ObjectNode).put("path", path) else item
        }
    }

    /**
     * What is wrong with this JSON, not being an edit, described by the [shape] the model sent, e.g. `had no `path`, being {"new": a text of 731 characters}`:
     * its keys and the kinds of their values, rather than its text, whose beginning, e.g. a long `new` one, may hide what is wrong.
     */
    private fun JsonNode.malformation(shape: JsonNode = this): String {
        if (!isObject) return "was not an object, but ${described()}"
        val defects = listOf("path" to true, "new" to true, "old" to false).mapNotNull { (key, required) ->
            val value = get(key)
            when {
                value == null -> if (required) "no `$key`" else null
                value.isTextual || (!required && value.isNull) -> null
                else -> "`$key` being ${value.kind()}"
            }
        } + listOfNotNull(get("all")?.takeUnless { it.isBoolean || it.isNull }?.let { "`all` being ${it.kind()}" })
        val sent = shape.properties().joinToString(prefix = "{", postfix = "}") { (key, value) -> "\"$key\": ${value.described()}" }
        return "had ${defects.joinToString(", and ")}, being $sent"
    }

    /** The kind of this JSON value, e.g. `a text`. */
    private fun JsonNode.kind(): String = when {
        isTextual -> "a text"
        isNumber -> "a number"
        isBoolean -> "a boolean"
        isNull -> "null"
        isArray -> "an array"
        else -> "an object"
    }

    /** The kind of this JSON value and its size, e.g. `a text of 12 characters`. */
    private fun JsonNode.described(): String = when {
        isTextual -> "a text of ${asText().length} character${if (asText().length == 1) "" else "s"}"
        isArray -> "an array of ${size()} item${if (size() == 1) "" else "s"}"
        isObject -> "an object of the keys ${properties().joinToString { "\"${it.key}\"" }}"
        else -> kind()
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
