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
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class ProcessesTest {

    @Test
    fun `should return exit code, output and error of process`() = runTest(timeout = 5.minutes) {
        // when
        val execution = executeProcess(listOf("bash", "-c", "echo out; echo err >&2; exit 3"))

        // then
        assert(execution.exitCode == 3)
        assert(execution.output == "out\n")
        assert(execution.error == "err\n")
        assert(!execution.succeeded)
    }

    @Test
    fun `should execute process with input, in directory, with environment`() = runTest(timeout = 5.minutes) {
        // given
        val directory = createTempDirectory("annie-process")

        // when
        val execution = executeProcess(
            command = listOf("bash", "-c", "cat; pwd -P; echo \$GREETING"),
            input = "from input\n",
            directory = directory.toString(),
            environment = mapOf("GREETING" to "hello")
        )

        // then
        assert(execution.succeeded)
        assert(execution.output == "from input\n${directory.toRealPath()}\nhello\n")
    }

    @Test
    fun `should not wait on input the process does not read`() = runTest(timeout = 5.minutes) {
        // when
        val execution = executeProcess(
            command = listOf("true"),
            input = "x".repeat(10_000_000)
        )

        // then
        assert(execution.succeeded)
    }

    @Test
    fun `should truncate output of process`() = runTest(timeout = 5.minutes) {
        // when
        val execution = executeProcess(
            command = listOf("bash", "-c", "head -c 100000 /dev/zero | tr '\\0' a"),
            maxOutputLength = 10
        )

        // then
        assert(execution.succeeded)
        assert(execution.output == "aaaaaaaaaa\n[99990 more characters truncated]")
    }

    @Test
    fun `should destroy process when timeout elapses`() = runTest(timeout = 5.minutes) {
        // when
        val execution = executeProcess(listOf("sleep", "600"), timeout = 100.milliseconds)

        // then
        assert(execution.exitCode == null)
        assert(!execution.succeeded)
    }

    @Test
    fun `should destroy process with its descendants when cancelled`() = runTest(timeout = 5.minutes) {
        // given
        val pidFile = createTempDirectory("annie-process").resolve("pid")
        val job = launch {
            executeProcess(listOf("bash", "-c", "sleep 600 & echo \$! > $pidFile.tmp && mv $pidFile.tmp $pidFile; wait"))
        }
        withContext(Dispatchers.Default) {
            while (!pidFile.exists()) delay(10.milliseconds)
        }
        val pid = pidFile.readText().trim().toLong()

        // when
        job.cancelAndJoin()

        // then
        // hangs until the test times out, unless the descendant is destroyed
        ProcessHandle.of(pid).orElse(null)?.onExit()?.await()
        assert(ProcessHandle.of(pid).map { it.isAlive }.orElse(false) == false)
    }

}
