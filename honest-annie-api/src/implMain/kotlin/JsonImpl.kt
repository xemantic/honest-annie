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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// The implementation of the navigation of JSON declared in Json.kt, kept out of the constitutions.
// Import the extensions of kotlinx.serialization.json by name, never by a star import, see CLAUDE.md.

actual operator fun JsonElement.get(key: String): JsonElement = when (this) {
    is JsonObject -> get(key) ?: throw NoSuchElementException(
        "No key \"$key\" in JSON object of keys: ${keys.joinToString()}"
    )
    else -> error("Key \"$key\" of JSON ${kind()}, not object")
}

actual fun JsonElement.getOrNull(key: String): JsonElement? = when (this) {
    is JsonObject -> get(key)?.takeUnless { it is JsonNull }
    else -> error("Key \"$key\" of JSON ${kind()}, not object")
}

actual operator fun JsonElement.get(index: Int): JsonElement = when (this) {
    is JsonArray -> getOrNull(index) ?: throw IndexOutOfBoundsException(
        "No index $index in JSON array of size $size"
    )
    else -> error("Index $index of JSON ${kind()}, not array")
}

actual val JsonElement.keys: Set<String> get() = (this as? JsonObject)?.keys ?: error("Keys of JSON ${kind()}, not object")

actual val JsonElement.list: List<JsonElement> get() = this as? JsonArray ?: error("List of JSON ${kind()}, not array")

actual val JsonElement.string: String get() = primitive("String").content

actual val JsonElement.int: Int get() = primitive("Int").intOrNull ?: error("Int of JSON $this")

actual val JsonElement.long: Long get() = primitive("Long").longOrNull ?: error("Long of JSON $this")

actual val JsonElement.double: Double get() = primitive("Double").doubleOrNull ?: error("Double of JSON $this")

actual val JsonElement.boolean: Boolean get() = primitive("Boolean").toBoolean() ?: error("Boolean of JSON $this")

actual val JsonElement.contentOrNull: String? get() = (this as? JsonPrimitive)?.contentOrNull

actual val JsonElement.intOrNull: Int? get() = (this as? JsonPrimitive)?.intOrNull

actual val JsonElement.longOrNull: Long? get() = (this as? JsonPrimitive)?.longOrNull

actual val JsonElement.doubleOrNull: Double? get() = (this as? JsonPrimitive)?.doubleOrNull

actual val JsonElement.booleanOrNull: Boolean? get() = (this as? JsonPrimitive)?.toBoolean()

private fun JsonPrimitive.toBoolean(): Boolean? = booleanOrNull ?: if (isString) null else when (content) {
    "1" -> true
    "0" -> false
    else -> null
}

private fun JsonElement.primitive(type: String): JsonPrimitive = when (this) {
    is JsonNull -> error("$type of JSON null")
    is JsonPrimitive -> this
    else -> error("$type of JSON ${kind()}, not primitive: ${toString().take(200)}")
}

private fun JsonElement.kind(): String = when (this) {
    is JsonObject -> "object"
    is JsonArray -> "array"
    is JsonNull -> "null"
    is JsonPrimitive -> "primitive"
}
