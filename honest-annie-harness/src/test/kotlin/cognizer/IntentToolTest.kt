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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.xemantic.kotlin.test.assert
import kotlin.test.Test

class IntentToolTest {

    private val mapper = ObjectMapper()

    @Test
    fun `should describe the script and the edits applied before it, neither required, as the properties of the input`() {
        // when
        val properties = mapper.valueToTree<JsonNode>(IntentTool.properties)

        // then
        assert(properties == mapper.readTree("""
            {"script": {"type": "string", "description": "The raw source of the Kotlin script, starting with its imports, executed after the edits."},
             "edits": {"type": "array",
               "description": "Edits of files, applied in their order before the script, all of them or none, each to the file as the edits before it left it: the code of any language written as it is, never escaped for Kotlin.",
               "items": {
                 "type": "object",
                 "properties": {
                   "path": {"type": "string", "description": "The path of the file, absolute, or relative to the working directory of the scripts, given in every edit, even one changing the file of the edit before it."},
                   "old": {"type": "string", "description": "The text to replace, exactly as in the file, whitespace included, occurring once unless `all`: omitted, the `new` text is written as the whole file, created when absent."},
                   "new": {"type": "string", "description": "The text replacing the `old` one, or the whole file without it."},
                   "all": {"type": "boolean", "description": "Whether to replace every occurrence of the `old` text, rather than its only one."}
                 },
                 "required": ["path", "new"],
                 "additionalProperties": false
               }}}
        """))
    }

}
