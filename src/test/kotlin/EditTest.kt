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
        val edited = applyEdits(listOf(Edit(path = file.toString(), old = "\tvm.ip++\n", new = "\tvm.ip += 2\n\tvm.steps++\n")))

        // then
        assert(edited == listOf(
            "Edited $file at line 4, as:",
            "     1\tpackage vm",
            "     2\t",
            "     3\tfunc (vm *VM) run() {",
            "     4\t\tvm.ip += 2",
            "     5\t\tvm.steps++",
            "     6\t\treturn nil",
            "     7\t}",
            "     8\t"
        ).joinToString("\n"))
        assert(file.readText() == source.replace("\tvm.ip++\n", "\tvm.ip += 2\n\tvm.steps++\n"))
    }

    @Test
    fun `should fail on old text not occurring, naming the closest line, leaving the file unchanged`() {
        // given
        val file = file()

        // when
        val failure = assertFailsWith<IllegalArgumentException> {
            applyEdits(listOf(Edit(path = file.toString(), old = "\tvm.ip--", new = "\tvm.ip -= 2")))
        }

        // then
        assert(failure.message == "Edit 1 of 1 failed: the old text does not occur in $file, its closest line being 4: \"\tvm.ip++\"")
        assert(file.readText() == source)
    }

    @Test
    fun `should fail on old text occurring only with other whitespace, showing the lines where, leaving the file unchanged`() {
        // given
        val file = file()

        // when
        val failure = assertFailsWith<IllegalArgumentException> {
            // indented with spaces instead of the tab of the file, as retyped by a model
            applyEdits(listOf(Edit(path = file.toString(), old = "func (vm *VM) run() {\n    vm.ip++\n", new = "func (vm *VM) run() {\n    vm.ip += 2\n")))
        }

        // then
        assert(failure.message ==
            "Edit 1 of 1 failed: the old text does not occur in $file, but does with other whitespace, at line 3, as:\n" +
            "     3\tfunc (vm *VM) run() {\n" +
            "     4\t\tvm.ip++"
        )
        assert(file.readText() == source)
    }

    @Test
    fun `should fail on old text occurring only with other whitespace more than once, naming the lines`() {
        // given
        val file = file()

        // when
        val failure = assertFailsWith<IllegalArgumentException> {
            applyEdits(listOf(Edit(path = file.toString(), old = "  return  nil\n}", new = "  return vm.err\n}")))
        }

        // then
        assert(failure.message == "Edit 1 of 1 failed: the old text does not occur in $file, but does with other whitespace, at lines 5, 9")
        assert(file.readText() == source)
    }

    @Test
    fun `should fail on old text occurring more than once, naming the lines, leaving the file unchanged`() {
        // given
        val file = file()

        // when
        val failure = assertFailsWith<IllegalArgumentException> {
            applyEdits(listOf(Edit(path = file.toString(), old = "\treturn nil\n", new = "\treturn vm.err\n")))
        }

        // then
        assert(failure.message == listOf(
            "Edit 1 of 1 failed: the old text occurs 2 times in $file, at lines 5, 9: extend it to occur once, or replace all, the file around them being:",
            "     2\t",
            "     3\tfunc (vm *VM) run() {",
            "     4\t\tvm.ip++",
            "     5\t\treturn nil",
            "     6\t}",
            "     7\t",
            "     8\tfunc (vm *VM) stop() {",
            "     9\t\treturn nil",
            "    10\t}"
        ).joinToString("\n"))
        assert(file.readText() == source)
    }

    @Test
    fun `should apply edits in their order, writing whole files and replacing texts, telling where`() {
        // given
        val file = file()
        val created = createTempDirectory().resolve("docs/notes.py")

        // when
        val edited = applyEdits(listOf(
            Edit(path = created.toString(), new = "def f():\n    \"\"\"Docstring with ${'$'}dollar.\"\"\"\n"),
            Edit(path = file.toString(), old = "\tvm.ip++\n", new = "\tvm.ip += 2\n"),
            // perceives the file as the edit before left it
            Edit(path = file.toString(), old = "\tvm.ip += 2\n", new = "\tvm.ip += 3\n"),
            Edit(path = file.toString(), old = "\treturn nil\n", new = "\treturn vm.err\n", all = true)
        ))

        // then
        assert(edited == listOf(
            "Wrote $created, 2 lines",
            "Edited $file at line 4, as:",
            "     1\tpackage vm",
            "     2\t",
            "     3\tfunc (vm *VM) run() {",
            "     4\t\tvm.ip += 2",
            "     5\t\treturn nil",
            "     6\t}",
            "     7\t",
            "Edited $file at line 4, as:",
            "     1\tpackage vm",
            "     2\t",
            "     3\tfunc (vm *VM) run() {",
            "     4\t\tvm.ip += 3",
            "     5\t\treturn nil",
            "     6\t}",
            "     7\t",
            "Edited $file at lines 5, 9, as:",
            "     2\t",
            "     3\tfunc (vm *VM) run() {",
            "     4\t\tvm.ip += 3",
            "     5\t\treturn vm.err",
            "     6\t}",
            "     7\t",
            "     8\tfunc (vm *VM) stop() {",
            "     9\t\treturn vm.err",
            "    10\t}"
        ).joinToString("\n"))
        assert(created.readText() == "def f():\n    \"\"\"Docstring with ${'$'}dollar.\"\"\"\n")
        assert(file.readText() == source.replace("\tvm.ip++\n", "\tvm.ip += 3\n").replace("\treturn nil\n", "\treturn vm.err\n"))
    }

    @Test
    fun `should show distant edited lines apart, omitting the middle of long ones`() {
        // given
        val file = createTempFile(suffix = ".txt").apply { writeText((1..40).joinToString("") { "line $it\n" }) }
        val long = (1..40).joinToString("") { "new $it\n" }

        // when
        val edited = applyEdits(listOf(
            Edit(path = file.toString(), old = "line 3\n", new = "line three\n"),
            Edit(path = file.toString(), old = "line 37\n", new = long)
        ))

        // then
        val numbered = { from: Int, to: Int, text: (Int) -> String -> (from..to).map { "${it.toString().padStart(6)}\t${text(it)}" } }
        assert(edited == (
            listOf("Edited $file at line 3, as:") +
                numbered(1, 6) { if (it == 3) "line three" else "line $it" } +
                listOf("Edited $file at line 37, as:") +
                numbered(34, 36) { "line $it" } +
                numbered(37, 48) { "new ${it - 36}" } +
                listOf("     …\t16 lines omitted") +
                numbered(65, 79) { if (it <= 76) "new ${it - 36}" else "line ${it - 39}" }
            ).joinToString("\n"))
    }

    @Test
    fun `should show the file around the first occurrences of old text occurring many times`() {
        // given
        // old text occurring on lines 5, 15, ..., 65
        val file = createTempFile(suffix = ".txt").apply { writeText((1..70).joinToString("") { if (it % 10 == 5) "x\n" else "line $it\n" }) }

        // when
        val failure = assertFailsWith<IllegalArgumentException> {
            applyEdits(listOf(Edit(path = file.toString(), old = "x\n", new = "y\n")))
        }

        // then
        val around = { at: Int -> (at - 3..at + 3).map { "${it.toString().padStart(6)}\t${if (it == at) "x" else "line $it"}" } }
        assert(failure.message == (
            listOf("Edit 1 of 1 failed: the old text occurs 7 times in $file, at lines 5, 15, 25, 35, 45, 55, 65: " +
                "extend it to occur once, or replace all, the file around the first 5 of them being:") +
                listOf(5, 15, 25, 35, 45).map { around(it).joinToString("\n") }.joinToString("\n     …\n").lines()
            ).joinToString("\n"))
        assert(file.readText().count { it == 'x' } == 7)
    }

    @Test
    fun `should show edited lines of each occurrence apart when distant`() {
        // given
        val file = createTempFile(suffix = ".txt").apply { writeText((1..20).joinToString("") { if (it == 2 || it == 18) "x\n" else "line $it\n" }) }

        // when
        val edited = applyEdits(listOf(Edit(path = file.toString(), old = "x\n", new = "y\n", all = true)))

        // then
        assert(edited == listOf(
            "Edited $file at lines 2, 18, as:",
            "     1\tline 1", "     2\ty", "     3\tline 3", "     4\tline 4", "     5\tline 5",
            "     …",
            "    15\tline 15", "    16\tline 16", "    17\tline 17", "    18\ty", "    19\tline 19", "    20\tline 20"
        ).joinToString("\n"))
    }

    @Test
    fun `should apply no edit when one of them fails, naming it`() {
        // given
        val file = file()
        val created = createTempDirectory().resolve("created.txt")

        // when
        val failure = assertFailsWith<IllegalArgumentException> {
            applyEdits(listOf(
                Edit(path = created.toString(), new = "created\n"),
                Edit(path = file.toString(), old = "\tvm.ip++\n", new = "\tvm.ip += 2\n"),
                Edit(path = file.toString(), old = "\treturn nil\n", new = "\treturn vm.err\n")
            ))
        }

        // then
        assert(failure.message == listOf(
            "Edit 3 of 3 failed: the old text occurs 2 times in $file, at lines 5, 9: extend it to occur once, or replace all, the file around them being:",
            "     2\t",
            "     3\tfunc (vm *VM) run() {",
            // as the edit before left it
            "     4\t\tvm.ip += 2",
            "     5\t\treturn nil",
            "     6\t}",
            "     7\t",
            "     8\tfunc (vm *VM) stop() {",
            "     9\t\treturn nil",
            "    10\t}"
        ).joinToString("\n"))
        assert(!created.exists())
        assert(file.readText() == source)
    }

    @Test
    fun `should fail on editing a file which does not exist`() {
        // given
        val path = createTempDirectory().resolve("absent.kt").toString()

        // when
        val failure = assertFailsWith<IllegalArgumentException> {
            applyEdits(listOf(Edit(path = path, old = "a", new = "b")))
        }

        // then
        assert(failure.message == "Edit 1 of 1 failed: no file at $path to edit: write it whole, without the old text")
    }

}
