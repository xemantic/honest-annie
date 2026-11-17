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

import com.xemantic.kotlin.test.assert
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith

class EditTest {

    private val source = """
        package vm

        func (vm *VM) run() {
        	vm.ip++
        	return nil
        }

        func (vm *VM) stop() {
        	return nil
        }
    """.trimIndent()

    private fun file() = createTempFile(suffix = ".go").apply { writeText(source) }

    @Test
    fun `should replace the only occurrence of the old text, telling where`() {
        // given
        val file = file()

        // when
        val edited = edit(file.toString(), "\tvm.ip++\n", "\tvm.ip += 2\n\tvm.steps++\n")

        // then
        assert(edited == "Edited $file at line 4")
        assert(file.readText() == source.replace("\tvm.ip++\n", "\tvm.ip += 2\n\tvm.steps++\n"))
    }

    @Test
    fun `should fail on old text not occurring, naming the closest line, leaving the file unchanged`() {
        // given
        val file = file()

        // when
        val failure = assertFailsWith<IllegalArgumentException> {
            // indented with spaces instead of the tab of the file
            edit(file.toString(), "    vm.ip++", "    vm.ip += 2")
        }

        // then
        assert(failure.message == "The old text does not occur in $file, its closest line being 4: \"\tvm.ip++\"")
        assert(file.readText() == source)
    }

    @Test
    fun `should fail on old text occurring more than once, naming the lines, leaving the file unchanged`() {
        // given
        val file = file()

        // when
        val failure = assertFailsWith<IllegalArgumentException> {
            edit(file.toString(), "\treturn nil\n", "\treturn vm.err\n")
        }

        // then
        assert(failure.message == "The old text occurs 2 times in $file, at lines 5, 9: extend it to occur once")
        assert(file.readText() == source)
    }

    @Test
    fun `should apply edits in their order, writing whole files and replacing texts, telling where`() {
        // given
        val file = file()
        val created = createTempDirectory().resolve("docs/notes.py")

        // when
        val edited = edit(listOf(
            Edit(path = created.toString(), new = "def f():\n    \"\"\"Docstring with ${'$'}dollar.\"\"\"\n"),
            Edit(path = file.toString(), old = "\tvm.ip++\n", new = "\tvm.ip += 2\n"),
            // perceives the file as the edit before left it
            Edit(path = file.toString(), old = "\tvm.ip += 2\n", new = "\tvm.ip += 3\n"),
            Edit(path = file.toString(), old = "\treturn nil\n", new = "\treturn vm.err\n", all = true)
        ))

        // then
        assert(edited == "Wrote $created, 2 lines\nEdited $file at line 4\nEdited $file at line 4\nEdited $file at lines 5, 9")
        assert(created.readText() == "def f():\n    \"\"\"Docstring with ${'$'}dollar.\"\"\"\n")
        assert(file.readText() == source.replace("\tvm.ip++\n", "\tvm.ip += 3\n").replace("\treturn nil\n", "\treturn vm.err\n"))
    }

    @Test
    fun `should apply no edit when one of them fails, naming it`() {
        // given
        val file = file()
        val created = createTempDirectory().resolve("created.txt")

        // when
        val failure = assertFailsWith<IllegalArgumentException> {
            edit(listOf(
                Edit(path = created.toString(), new = "created\n"),
                Edit(path = file.toString(), old = "\tvm.ip++\n", new = "\tvm.ip += 2\n"),
                Edit(path = file.toString(), old = "\treturn nil\n", new = "\treturn vm.err\n")
            ))
        }

        // then
        assert(failure.message == "Edit 3 of 3 failed: the old text occurs 2 times in $file, at lines 5, 9: extend it to occur once, or replace all")
        assert(!created.exists())
        assert(file.readText() == source)
    }

    @Test
    fun `should fail on editing a file which does not exist`() {
        // given
        val path = createTempDirectory().resolve("absent.kt").toString()

        // when
        val failure = assertFailsWith<IllegalArgumentException> {
            edit(listOf(Edit(path = path, old = "a", new = "b")))
        }

        // then
        assert(failure.message == "Edit 1 of 1 failed: no file at $path to edit: write it whole, without the old text")
    }

}
