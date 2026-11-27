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

import kotlinx.serialization.json.JsonElement

/*
 * Navigating JSON of an unknown shape, received e.g. with `agency.http.get(url).body<JsonElement>()`,
 * as in JavaScript or Python, e.g. `gene["Transcript"][0]["Translation"]["id"].string`.
 * Each one fails on JSON of another kind, or on an absent key or index, telling what there is instead.
 */

/** The value of the [key] of this JSON object, failing when absent, naming the keys present. */
expect operator fun JsonElement.get(key: String): JsonElement

/** The value of the [key] of this JSON object, `null` when absent or JSON `null`. */
expect fun JsonElement.getOrNull(key: String): JsonElement?

/** The element at the [index] of this JSON array, failing when absent, naming its size. */
expect operator fun JsonElement.get(index: Int): JsonElement

/** The keys of this JSON object. */
expect val JsonElement.keys: Set<String>

/** The elements of this JSON array. */
expect val JsonElement.list: List<JsonElement>

/** The content of this JSON string, or of a number or boolean, as written. */
expect val JsonElement.string: String
expect val JsonElement.int: Int
expect val JsonElement.long: Long
expect val JsonElement.double: Double
/** This JSON boolean, also `1` or `0`, as some APIs encode booleans, e.g. `is_canonical` of Ensembl. */
expect val JsonElement.boolean: Boolean

/*
 * The same values, `null` for JSON `null`, or for JSON of another kind, e.g. an object, or text not being a number,
 * e.g. `transcript.getOrNull("phase")?.intOrNull ?: 0`.
 */
expect val JsonElement.contentOrNull: String?
expect val JsonElement.intOrNull: Int?
expect val JsonElement.longOrNull: Long?
expect val JsonElement.doubleOrNull: Double?
expect val JsonElement.booleanOrNull: Boolean?
