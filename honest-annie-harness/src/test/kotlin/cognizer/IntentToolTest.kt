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
import com.xemantic.annie.Edit
import com.xemantic.annie.Phenomenon
import com.xemantic.kotlin.test.assert
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class IntentToolTest {

    private val mapper = ObjectMapper()

    @Test
    fun `should describe where to execute, the script, its language and timeout, and the edits applied before it, none required, as the properties of the input`() {
        // when
        val properties = mapper.valueToTree<JsonNode>(IntentTool.properties)

        // then
        assert(properties == mapper.readTree("""
            {"computer": {"type": "string", "description": "The id of the computer to execute on, e.g. one started for a cogitation you invoked, your own when omitted."},
             "repl": {"type": "string", "description": "The name of the REPL of the computer to execute in, opened by the first call naming it, your own when omitted, or the first one of another `computer`."},
             "script": {"type": "string", "description": "The raw source of the script, executed after the edits: of Kotlin, starting with its imports, coded against the API of the constitution, or of the `language` given."},
             "language": {"type": "string", "enum": ["kotlin", "python", "bash"], "description": "The language of the script, `kotlin` when omitted."},
             "timeout": {"type": "number", "description": "Seconds after which the script is cancelled: by default never, but for bash after 60. A bash process is destroyed, while Kotlin or Python code blocking its thread, rather than suspending, runs to its end, and the next calls wait for it."},
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

    @Test
    fun `should list the properties of the input in the order of the execution, the edits applied before the language framing the script`() {
        // when
        val names = IntentTool.properties.keys.toList()

        // then
        assert(names == listOf("computer", "repl", "edits", "language", "script", "timeout"))
    }

    @Test
    fun `should replay input of intent in the order of the properties`() {
        // given
        val intent = Phenomenon.Intent(
            computer = "gpu-1",
            repl = "scratch",
            edits = listOf(Edit(path = "a.txt", new = "a")),
            language = Phenomenon.Intent.Language.BASH,
            script = "cat a.txt",
            timeout = 30.seconds
        )

        // when
        val names = IntentTool.input(intent).keys.toList()

        // then
        assert(names == listOf("computer", "repl", "edits", "language", "script", "timeout"))
    }

    @Test
    fun `should express intent of the language and timeout of the call`() {
        // when
        val intent = IntentTool.intent("execute", """{"script": "print(1)", "language": "python", "timeout": 1.5}""")

        // then
        assert(intent is Phenomenon.Intent
                && intent.script == "print(1)"
                && intent.language == Phenomenon.Intent.Language.PYTHON
                && intent.timeout == 1500.milliseconds)
    }

    @Test
    fun `should express Kotlin intent of no timeout by default`() {
        // when
        val intent = IntentTool.intent("execute", """{"script": "6 * 7"}""")

        // then
        assert(intent is Phenomenon.Intent && intent.language == Phenomenon.Intent.Language.KOTLIN && intent.timeout == null)
    }

    @Test
    fun `should read language of any case, and numbers sent as texts`() {
        // when
        val intent = IntentTool.intent("execute", """{"script": "ls", "language": "Bash", "timeout": "30"}""")

        // then
        assert(intent is Phenomenon.Intent && intent.language == Phenomenon.Intent.Language.BASH && intent.timeout == 30.seconds)
    }

    @Test
    fun `should read script given another name along the language`() {
        // when
        val intent = IntentTool.intent("execute", """{"code": "print(1)", "language": "python"}""")

        // then
        assert(intent is Phenomenon.Intent && intent.script == "print(1)" && intent.language == Phenomenon.Intent.Language.PYTHON)
    }

    @Test
    fun `should impede call of unknown language, executing nothing`() {
        // when
        val impediment = IntentTool.intent("execute", """{"script": "puts 42", "language": "ruby"}""")

        // then
        assert(impediment == Phenomenon.Impediment(
            "Your call of `execute` carried the `language` \"ruby\", rather than one of kotlin, python, bash, so nothing was executed."
        ))
    }

    @Test
    fun `should impede call of timeout which is not a positive number, executing nothing`() {
        // when
        val timeout = IntentTool.intent("execute", """{"script": "6 * 7", "timeout": "soon"}""")

        // then
        assert(timeout == Phenomenon.Impediment(
            "Your call of `execute` carried the `timeout` \"soon\", rather than a positive number of seconds, so nothing was executed."
        ))
    }

    @Test
    fun `should replay input of intent with its language and timeout, when given`() {
        // given
        val kotlin = Phenomenon.Intent(script = "6 * 7")
        val python = Phenomenon.Intent(language = Phenomenon.Intent.Language.PYTHON, script = "print(1)", timeout = 1500.milliseconds)

        // when
        val kotlinInput = IntentTool.input(kotlin)
        val pythonInput = IntentTool.input(python)

        // then
        assert(kotlinInput == mapOf("script" to "6 * 7"))
        assert(pythonInput == mapOf("script" to "print(1)", "language" to "python", "timeout" to 1.5))
    }

    @Test
    fun `should express intent of the computer and REPL of the call`() {
        // when
        val intent = IntentTool.intent("execute", """{"computer": "gpu-1", "repl": " scratch ", "script": "nvidia-smi", "language": "bash"}""")

        // then
        assert(intent is Phenomenon.Intent && intent.computer == "gpu-1" && intent.repl == "scratch")
    }

    @Test
    fun `should impede call of computer which is not a text, executing nothing`() {
        // when
        val impediment = IntentTool.intent("execute", """{"computer": 7, "script": "6 * 7"}""")

        // then
        assert(impediment == Phenomenon.Impediment(
            "Your call of `execute` carried the `computer` 7, rather than the id of a computer, so nothing was executed."
        ))
    }

}
