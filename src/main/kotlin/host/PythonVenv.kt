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

import com.xemantic.kotlin.script.python.PythonEnvironment
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.minutes

/** The version of the `jep` Python package matching the `org.ninia:jep` library, see `python.properties`. */
val jepVersion: String = Properties().apply {
    load(checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("python.properties")) {
        "No python.properties on the classpath"
    })
}.getProperty("jep.version")

/**
 * The Python of the intents, see [com.xemantic.ai.annie.Agency.python], embedded through Jep,
 * needs the `jep` package of the version of the library installed in its Python environment.
 *
 * Unless `VIRTUAL_ENV` tells the virtual environment to use, e.g. the one of the build,
 * it is a virtual environment prepared by the harness, at the `ANNIE_PYTHON_VENV` path,
 * or in the user cache by default, created on first use with the `ANNIE_PYTHON` executable, `python3` by default,
 * since a Python of the system, e.g. of Homebrew, refuses packages installed by pip outside a virtual environment.
 */
object PythonVenv {

    private val prepared: Result<Unit> by lazy {
        runCatching {
            if (System.getenv("VIRTUAL_ENV") != null) return@runCatching
            val directory = System.getenv("ANNIE_PYTHON_VENV")?.let { Path(it) } ?: Path(
                System.getProperty("user.home"), ".cache", "honest-annie", "python-jep-$jepVersion"
            )
            PythonEnvironment.pythonExecutable = preparePythonVenv(
                directory, basePython = System.getenv("ANNIE_PYTHON") ?: "python3", jepVersion = jepVersion
            )
        }
    }

    /**
     * Prepares the Python environment once in the JVM, before the first Python is started,
     * failing again with the same error on every later call, rather than repeating a failed installation.
     */
    fun prepare() {
        prepared.getOrThrow()
    }

}

/**
 * Prepares the virtual environment in the [directory], creating it with the [basePython] when it does not exist,
 * and installing the `jep` package of the [jepVersion] into it, unless already installed.
 *
 * @return the Python executable of the virtual environment.
 * @throws IllegalStateException telling what failed, and how to set up the virtual environment by hand.
 */
fun preparePythonVenv(directory: Path, basePython: String, jepVersion: String): Path {
    val python = directory.resolve("bin/python").takeIf { it.exists() }
        ?: directory.resolve("Scripts/python.exe").takeIf { it.exists() }
    val manually = "set it up by hand, in a terminal:\n" +
        "python3 -m venv $directory\n" +
        "$directory/bin/python -m pip install jep==$jepVersion\n" +
        "or point to another virtual environment with jep $jepVersion installed, e.g. VIRTUAL_ENV=$directory"
    if (python == null) {
        val created = runOrNull(listOf(basePython, "-m", "venv", directory.toString()), timeout = 2)
        check(created?.succeeded == true) {
            "Cannot create the Python virtual environment $directory with $basePython" +
                (created?.let { ": ${it.error.ifBlank { it.output }.takeLast(1000).trim()}" } ?: ", which cannot be run") +
                "\nSet ANNIE_PYTHON to a Python 3 executable having the venv module, or $manually"
        }
        return preparePythonVenv(directory, basePython, jepVersion)
    }
    val installed = runOrNull(
        listOf(python.toString(), "-c", "import importlib.metadata as m; print(m.version('jep'))"), timeout = 1
    )
    if (installed?.succeeded == true && installed.output.trim() == jepVersion) return python
    val installation = runOrNull(
        listOf(python.toString(), "-m", "pip", "install", "--quiet", "--disable-pip-version-check", "jep==$jepVersion"),
        timeout = 15
    )
    check(installation?.succeeded == true) {
        "Cannot install jep $jepVersion into the Python virtual environment $directory" +
            (installation?.let { ": ${it.error.ifBlank { it.output }.takeLast(2000).trim()}" } ?: "") +
            "\nWhen no wheel of jep matches the Python, jep is built from its source, " +
            "needing a C compiler, and a JDK, found through JAVA_HOME " +
            (System.getenv("JAVA_HOME")?.let { "($it)" } ?: "(not set)") +
            ", so fix the cause, or $manually"
    }
    return python
}

/** Executes the [command] within the [timeout] in minutes, or `null` when it cannot be started. */
private fun runOrNull(command: List<String>, timeout: Int) = try {
    executeProcess(command, timeout = timeout.minutes, maxOutputLength = 100_000)
} catch (_: java.io.IOException) {
    null
}
