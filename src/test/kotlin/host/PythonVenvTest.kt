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

package com.xemantic.ai.annie.host

import com.xemantic.kotlin.test.assert
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PythonVenvTest {

    @Test
    fun `should use virtual environment having jep of the version without creating it`() {
        // given
        val venv = Path(System.getenv("VIRTUAL_ENV"))

        // when
        val python = preparePythonVenv(venv, basePython = "/nonexistent/python3", jepVersion = jepVersion)

        // then
        assert(python == venv.resolve("bin/python"))
    }

    @Test
    fun `should tell how to set up virtual environment by hand when it cannot be created`() {
        // given
        val venv = createTempDirectory("annie-venv-test").resolve("venv")

        // when
        val error = assertFailsWith<IllegalStateException> {
            preparePythonVenv(venv, basePython = "/nonexistent/python3", jepVersion = "4.3.2")
        }

        // then
        val message = error.message!!
        assert("/nonexistent/python3" in message)
        assert("python3 -m venv $venv" in message)
        assert("$venv/bin/python -m pip install jep==4.3.2" in message)
        assert("VIRTUAL_ENV=$venv" in message)
        assert(!venv.exists())
    }

}
