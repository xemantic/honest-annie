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

package com.xemantic.annie.harness

import com.xemantic.annie.Agency
import com.xemantic.kotlin.test.assert
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test

class ConstitutionTest {

    @Test
    fun `should load the constitution filled with the API, cognition and harness sources`() {
        // when
        val loaded = defaultConstitution

        // then
        assert(loaded.startsWith("# The constitution of honest-annie\n\n## Identity\n\n"))
        assert("interface Agency" in loaded)
        assert("class Cogitation(" in loaded)
        assert("{{api}}" !in loaded && "{{cognition}}" !in loaded)
        // the source of the harness, compiled into honest-annie, is part of the constitution
        assert("internal suspend fun unfold(" in loaded && "{{harness}}" !in loaded && "\n````\n\n## Your answer\n\n" in loaded)
    }

    @Test
    fun `should load the constitution of its own for a model having one`() {
        // when
        val loaded = constitution(model = "deepseek-v4_1-flash")

        // then
        assert(loaded != defaultConstitution)
        assert(loaded.startsWith("# The constitution of honest-annie\n\n## Identity\n\n"))
        assert("\n## Judgment and computation\n" in loaded)
        assert("interface Agency" in loaded)
        assert("class Cogitation(" in loaded)
        assert("{{api}}" !in loaded && "{{cognition}}" !in loaded)
        // the source of the harness, compiled into honest-annie, is part of the constitution
        assert("internal suspend fun unfold(" in loaded && "{{harness}}" !in loaded && "\n````\n\n## Your answer\n\n" in loaded)
    }

    @Test
    fun `should load the constitution of the closest family of a model`() {
        // when
        val loaded = constitution(model = "accounts/provider/models/deepseek-v9")

        // then
        assert(loaded == constitution(model = "deepseek-v4_1-flash"))
    }

    @Test
    fun `should load the constitution of Claude Fable`() {
        // when
        val loaded = constitution(model = "claude-fable-5-2")

        // then
        assert(loaded != defaultConstitution)
        assert(loaded != constitution(model = "deepseek-v4_1-flash"))
        assert(loaded.startsWith("# The constitution of honest-annie\n\n## Identity\n\n"))
        assert("\n## Steps\n" in loaded)
        assert("\n## Changing files\n" in loaded)
        assert("interface Agency" in loaded)
        assert("class Cogitation(" in loaded)
        assert("{{api}}" !in loaded && "{{cognition}}" !in loaded)
        // the source of the harness, compiled into honest-annie, is part of the constitution
        assert("internal suspend fun unfold(" in loaded && "{{harness}}" !in loaded && "\n````\n\n## Your answer\n\n" in loaded)
    }

    @Test
    fun `should load the constitution of Kimi for its model ids of every provider`() {
        // given
        val kimi = Agency::class.java.getResource("/constitutions/kimi.md")!!.readText().trim()

        // when
        val constitutions = listOf(
            "kimi-k3",
            "kimi-k3-2026-08-20",
            "moonshotai/kimi-k3",
            "accounts/fireworks/models/kimi-k3",
            "moonshotai/Kimi-K3"
        ).associateWith { constitution(model = it) }

        // then
        assert(constitutions.values.all { it == kimi })
        assert(kimi != defaultConstitution)
        assert(kimi.startsWith("# The constitution of honest-annie\n\n## Identity\n\n"))
        assert("{{api}}" !in kimi && "{{cognition}}" !in kimi)
        // the source of the harness, compiled into honest-annie, is part of the constitution
        assert("internal suspend fun unfold(" in kimi && "{{harness}}" !in kimi && "\n````\n\n## Your answer\n\n" in kimi)
    }

    @Test
    fun `should show edits only as the record of intents, not as a function of scripts, which change files by the edits of the call`() {
        // when
        val constitutions = listOf("claude-opus-5-5", "claude-fable-5-2", "deepseek-v4_1-flash", "kimi-k3")
            .map { constitution(model = it) }

        // then
        // models seeing a function changing files called it from scripts, escaping the code they changed for Kotlin
        assert(constitutions.none { "fun edit(" in it || "fun applyEdits(" in it })
        // the edits of the intents of a cogitation are read when researching it, e.g. the one of the parent
        assert(constitutions.all { "data class Edit(" in it && "changes no file" in it })
    }

    @Test
    fun `should tell that the Python of scripts cannot import the packages of the task`() {
        // when
        val constitutions = listOf("claude-opus-5-5", "claude-fable-5-2", "deepseek-v4_1-flash", "kimi-k3")
            .map { constitution(model = it) }

        // then
        // a model ran the tests of the task through it, failing on `No module named 'fastapi'`
        assert(constitutions.all { "cannot import the packages of the task" in it })
    }

    @Test
    fun `should tell DeepSeek and Kimi to name the file of every edit`() {
        // when
        val constitutions = listOf("deepseek-v4_1-flash", "kimi-k3").map { constitution(model = it) }

        // then
        // DeepSeek left out the `path` of every edit of a call, meaning the file of an earlier call
        assert(constitutions.all { "every edit naming its own file by its `path`" in it })
    }

    @Test
    fun `should tell DeepSeek and Kimi to write every file by an edit, a scratch file too, run in the same call`() {
        // when
        val constitutions = listOf("deepseek-v4_1-flash", "kimi-k3").map { constitution(model = it) }

        // then
        // DeepSeek wrote probe tests by JSON-escaping them in the script, writing the JSON by `cat >`, and Python writing the file from it
        assert(constitutions.all { "Write every file you need by an edit, a scratch file too, e.g. a probe test" in it })
    }

    @Test
    fun `should load the default constitution for a model without one of its own`() {
        // when
        val loaded = constitution(model = "claude-opus-5-5")

        // then
        assert(loaded == defaultConstitution)
    }

    @Test
    fun `should load the constitution of a file in place of the one of the model`() {
        // given
        val file = createTempFile(suffix = ".md").apply { writeText("## Variant\n\nOf a generated constitution.\n") }

        // when
        val loaded = constitution(model = "deepseek-v4_1-flash", file = file)

        // then
        assert(loaded == "## Variant\n\nOf a generated constitution.")
    }

    @Test
    fun `should load the constitution of the model without a file`() {
        // when
        val loaded = constitution(model = "deepseek-v4_1-flash", file = null)

        // then
        assert(loaded == constitution(model = "deepseek-v4_1-flash"))
    }

}
