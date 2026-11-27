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

package com.xemantic.annie.executor

import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import com.xemantic.annie.*
import com.xemantic.kotlin.test.assert
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class ScriptExecutorTest {

    private val executor = SessionScriptExecutor()


    @AfterTest
    fun closeExecutor() {
        executor.close()
    }

    /**
     * [Agency] of the cogitation given, the content of its phenomena kept by the [contents],
     * what an intent invokes and enunciates being kept by the executor.
     */
    private class TestAgency(
        private val cogitation: Cogitation = Cogitation(constitution = "", enunciations = emptyList()),
        private val contents: Contents = InMemoryContents()
    ) : Agency {
        override suspend fun cogitation() = cogitation
        override suspend fun parentCogitation(): Cogitation? = null
        override suspend fun invoke(prompt: String, constitution: String?, computer: String?, repl: String?, independent: Boolean): Unit =
            throw UnsupportedOperationException()
        override suspend fun enunciate(text: String, contentType: String): Unit = throw UnsupportedOperationException()
        override suspend fun enunciate(bytes: ByteArray, contentType: String?): Unit = throw UnsupportedOperationException()
        override suspend fun impede(reason: String): Unit = throw UnsupportedOperationException()
        override suspend fun resolveDeclaration(name: String, page: Int, pageSize: Int): String? = null
        override suspend fun searchDeclarations(query: String, page: Int, pageSize: Int): String? = null
        override suspend fun execute(
            command: List<String>,
            input: String?,
            directory: String?,
            environment: Map<String, String>,
            timeout: Duration
        ): Execution = throw UnsupportedOperationException()
        // the files of the intents are read by the executor
        override suspend fun read(path: String, vararg lines: IntRange): String = throw UnsupportedOperationException()
        // the content of the phenomena is kept by the host
        override suspend fun readText(phenomenon: Phenomenon, vararg lines: IntRange): String =
            contents.readText(phenomenon, lines.toList())
        override suspend fun readBytes(phenomenon: Phenomenon): ByteArray = contents.readBytes(phenomenon)
        // the Python of the intents is the one of the executor
        override val python: Python get() = throw UnsupportedOperationException()
        override val http = annieHttpClient
    }

    @Test
    fun `should return value excluding invoked cogitations and enunciated output`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()

        // when
        val executed = executor.execute(
            """
            agency.enunciate("first")
            agency.invoke("sub")
            agency.enunciate(byteArrayOf(4, 2))
            agency.impede("stuck")
            42
            """.trimIndent(),
            agency
        )

        // then
        assert(executed.result == listOf(text("42", "text/plain")))
        assert(executed.invoked.single().prompt == "sub")
        assert(executed.output == listOf(
            text("first"),
            data(byteArrayOf(4, 2)),
            Phenomenon.Impediment("stuck")
        ))
    }

    @Test
    fun `should return value of intent as text`() = runTest(timeout = 5.minutes) {
        // when
        val number = executor.execute("6 * 7", TestAgency()).result
        val string = executor.execute("\"*answer*\"", TestAgency()).result

        // then
        assert(number == listOf(text("42", "text/plain")))
        assert(string == listOf(text("*answer*", "text/plain")))
    }

    @Test
    fun `should resolve the API of annie without importing it`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
            "fun said(phenomenon: Phenomenon) = (phenomenon as Phenomenon.Impediment).reason\n" +
                "said(Phenomenon.Impediment(\"no import needed\"))",
            TestAgency()
        ).result

        // then
        assert(phenomena == listOf(text("no import needed", "text/plain")))
    }

    @Test
    fun `should resolve durations and paths without importing them`() = runTest(timeout = 5.minutes) {
        // given
        val file = kotlin.io.path.createTempFile()

        // when
        // e.g. the timeout of `agency.bash`, whose missing import failed an 8 KB intent of edits
        val phenomena = executor.execute(
            "Path(\"$file\").writeText(\"edited\")\n" +
                    $$"\"${10.minutes} ${Path(\"$$file\").readText()}\"",
            TestAgency()
        ).result

        // then
        assert(phenomena == listOf(text("10m edited", "text/plain")))
    }

    @Test
    fun `should write triple quotes into raw strings as TQ`() = runTest(timeout = 5.minutes) {
        // given
        // e.g. a Python docstring, whose triple quotes would end the raw string, which failed a 13 KB intent of edits
        val q = "\"\"\""
        val script = "val plain = ${q}def f():\n    \${TQ}Docstring.\${TQ}\n$q\n" +
                $$$"val multiDollar = $$$$${q}price = \"$\" + $$TQ + \"$$\" + $$TQ$$$q\n" +
            "plain + multiDollar"

        // when
        val phenomena = executor.execute(script, TestAgency()).result

        // then
        assert(phenomena == listOf(text(
            "def f():\n    ${q}Docstring.$q\nprice = \"$\" + $q + \"$$\" + $q",
            "text/plain"
        )))
    }

    @Test
    fun `should keep whole value of intent up to 1,000,000 characters`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute("\"a\".repeat(1_000_000)", TestAgency()).result

        // then
        assert(phenomena == listOf(text("a".repeat(1_000_000), "text/plain")))
    }

    @Test
    fun `should digest longer value of intent, telling the file of the whole value`() = runTest(timeout = 5.minutes) {
        // when
        val executed = executor.execute("(0 until 200_000).joinToString(\"\\n\")", TestAgency())

        // then
        val value = executed.text(executed.result.single())
        assert(value.length in 1_000_000..1_001_000)
        assert(value.startsWith("0\n1\n2\n"))
        assert(value.contains(Regex("\\d+ more characters omitted")))
        val file = Regex("the whole text being in (\\S+);").find(value)?.groupValues?.get(1)
        assert(file != null && Path(file).readText() == (0 until 200_000).joinToString("\n"))
        assert(value.contains("val"))
    }

    @Test
    fun `should interpolate only multi-dollar references in multi-dollar string of intent`() = runTest(timeout = 5.minutes) {
        // given
        val intent = "val n = 42\n\$\$\"\"\"for c in a b; do echo \"\$c \$\$n\"; done\"\"\""

        // when
        val result = executor.execute(intent, TestAgency()).result

        // then
        assert(result == listOf(text("""for c in a b; do echo "${'$'}c 42"; done""", "text/plain")))
    }

    @Test
    fun `should interpolate triple quotes of embedded code in multi-dollar string of intent`() = runTest(timeout = 5.minutes) {
        // given
        val intent = "\$\$\"\"\"\ndef f(\$x):\n    \$\${\"\\\"\\\"\\\"\"}Doc.\$\${\"\\\"\\\"\\\"\"}\n\"\"\".trimIndent()"

        // when
        val result = executor.execute(intent, TestAgency()).result

        // then
        val python = "def f(\$x):\n    \"\"\"Doc.\"\"\""
        assert(result == listOf(text(python, "text/plain")))
    }

    @Test
    fun `should return bytes value of intent as data, to perceive an image`() = runTest(timeout = 5.minutes) {
        // when
        val executed = executor.execute("byteArrayOf(4, 2)", TestAgency())

        // then
        assert(executed.result == listOf(data(byteArrayOf(4, 2))))
        assert(executed.contents.getValue(data(byteArrayOf(4, 2)).ref).contentEquals(byteArrayOf(4, 2)))
    }

    @Test
    fun `should return a phenomenon of the cogitation as is, without sending its content`() = runTest(timeout = 5.minutes) {
        // given
        val contents = InMemoryContents()
        val image = contents.data(byteArrayOf(4, 2), "image/png")
        val agency = TestAgency(cogitationOf(image), contents)

        // when
        val executed = executor.execute("agency.cogitation().enunciations[0].phenomena[0]", agency)

        // then
        assert(executed.result == listOf(image))
        assert(executed.contents.isEmpty())
    }

    @Test
    fun `should read the lines of a text of the cogitation`() = runTest(timeout = 5.minutes) {
        // given
        val contents = InMemoryContents()
        val agency = TestAgency(cogitationOf(contents.text("one\ntwo\nthree\n")), contents)

        // when
        val result = executor.execute("agency.readText(agency.cogitation().enunciations[0].phenomena[0], 2..3)", agency).result

        // then
        assert(result == listOf(text("two\nthree", "text/plain")))
    }

    @Test
    fun `should read the bytes of data of the cogitation`() = runTest(timeout = 5.minutes) {
        // given
        val contents = InMemoryContents()
        val agency = TestAgency(cogitationOf(contents.data(byteArrayOf(4, 2, 0), "image/png")), contents)

        // when
        val result = executor.execute("agency.readBytes(agency.cogitation().enunciations[0].phenomena[0]).sum()", agency).result

        // then
        assert(result == listOf(text("6", "text/plain")))
    }

    /** The cogitation of a single enunciation of the [phenomenon]. */
    private fun cogitationOf(phenomenon: Phenomenon) = Cogitation(
        constitution = "",
        enunciations = listOf(Enunciation(actant = Actant.INVOKER, phenomena = listOf(phenomenon)))
    )

    @Test
    fun `should return nothing for intent evaluating to Unit or null`()= runTest(timeout = 5.minutes) {
        // when
        val unit = executor.execute("agency.enunciate(\"output\")", TestAgency()).result
        val nothing = executor.execute("null", TestAgency()).result

        // then
        assert(unit.isEmpty())
        assert(nothing.isEmpty())
    }

    @Test
    fun `should not perceive the units of invocations which are the value of intent`() = runTest(timeout = 5.minutes) {
        // when
        val executed = executor.execute(
            "listOf(\"first\", \"second\").map { agency.invoke(it) }",
            TestAgency()
        )

        // then
        assert(executed.invoked.map { it.prompt } == listOf("first", "second"))
        assert(executed.result.isEmpty())
    }

    @Test
    fun `should start computer for invoked cogitation with its first REPL of the name given`() = runTest(timeout = 5.minutes) {
        // when
        val executed = executor.execute("agency.invoke(\"sub\", computer = \"gpu\", repl = \"training\")", TestAgency())

        // then
        val invocation = executed.invoked.single()
        assert(invocation.computer?.type == "gpu" && invocation.repl == "training")
        assert(executed.result.isEmpty())
    }

    @Test
    fun `should impede intent failing to compile with a digest of the first errors`() = runTest(timeout = 5.minutes) {
        // given
        val python = (1..500).joinToString("\n") { "for i,o in enumerate(opts$it): print(i)" }

        // when
        val phenomena = executor.execute(python, TestAgency()).result

        // then
        val reason = (phenomena.single() as Phenomenon.Impediment).reason
        assert(reason.length <= 5000)
        assert(reason.contains(Regex("^snippet\\d+\\.kts:1:")))
        assert(reason.contains(Regex("\\d+ more errors omitted")))
    }

    @Test
    fun `should impede intent escaping a backslash of shell command in plain string, hinting at raw string`() = runTest(timeout = 5.minutes) {
        // given
        // the alternation of grep, escaped for the shell, not for Kotlin, as models write it again and again
        val script = "val command = \"grep -n 'deprecated\\|sunset' fastapi/routing.py\"\ncommand"

        // when
        val phenomena = executor.execute(script, TestAgency()).result

        // then
        val reason = (phenomena.single() as Phenomenon.Impediment).reason
        assert("error: Unsupported escape sequence." in reason)
        assert(reason.endsWith(UNSUPPORTED_ESCAPE_HINT))
    }

    @Test
    fun `should read numbered lines of a file through the agency`() = runTest(timeout = 5.minutes) {
        // given
        val file = createTempFile(suffix = ".py").apply { writeText("a\nb\nc\nd\n") }

        // when
        val result = executor.execute("agency.read(\"$file\", 2..3)", TestAgency()).result

        // then
        assert(result == listOf(text("     2\tb\n     3\tc", "text/plain")))
    }

    @Test
    fun `should apply the edits of an intent, then execute its script`() = runTest(timeout = 5.minutes) {
        // given
        val file = createTempFile(suffix = ".ts").apply { writeText("a\n") }
        val intent = Phenomenon.Intent(edits = listOf(Edit(path = file.toString(), old = "a", new = "b")), script = "Path(\"$file\").readText()")

        // when
        val phenomena = executor.execute(intent, TestAgency()).result

        // then
        assert(phenomena == listOf(
            text("Edited $file at line 1, as:\n     1\tb", "text/plain"),
            text("b\n", "text/plain")
        ))
    }

    @Test
    fun `should not execute the script of an intent whose edits failed, changing no file`() = runTest(timeout = 5.minutes) {
        // given
        val file = createTempFile(suffix = ".ts").apply { writeText("a\n") }
        val intent = Phenomenon.Intent(
            edits = listOf(Edit(path = file.toString(), old = "a", new = "b"), Edit(path = file.toString(), old = "x", new = "y")),
            script = "Path(\"$file\").writeText(\"script\")"
        )

        // when
        val phenomena = executor.execute(intent, TestAgency()).result

        // then
        assert(file.readText() == "a\n")
        val reason = (phenomena.single() as Phenomenon.Impediment).reason
        assert(reason.startsWith("Edit 2 of 2 failed:") && reason.endsWith("No file was changed, and the script was not executed."))
    }

    @Test
    fun `should not let the script of an intent apply edits, telling to give them as the edits of the call`() = runTest(timeout = 5.minutes) {
        // given
        // DeepSeek saw the harness, in its constitution, applying the edits of the intent, and applied its own from the script,
        // escaping the code it changed for a Kotlin string
        val file = createTempFile(suffix = ".ts").apply { writeText("a\n") }

        // when
        val phenomena = executor.execute(
            "applyEdits(listOf(Edit(path = \"$file\", old = \"a\", new = \"b\")))",
            TestAgency()
        ).result

        // then
        assert(file.readText() == "a\n")
        val reason = (phenomena.single() as Phenomenon.Impediment).reason
        assert(reason.endsWith(
            "Files are changed by the `edits` of your call, applied before its script, never from the script: " +
                    "give them as the `edits` of the call, their texts the code as it is, never escaped for a Kotlin string."
        ))
    }

    @Test
    fun `should return impediment of failing intent`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()

        // when
        val executed = executor.execute(
            """
            agency.enunciate("before")
            agency.invoke("sub")
            error("boom")
            """.trimIndent(),
            agency
        )

        // then
        assert((executed.result.single() as Phenomenon.Impediment).reason.contains("boom"))
        assert(executed.invoked.single().prompt == "sub")
        // the failure impedes the intent, not the cogitation
        assert(executed.output == listOf(text("before")))
    }

    @Test
    fun `should keep declarations of intent visible to the next intents`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()
        executor.execute(
            $$"""
            import com.xemantic.annie.*
            val answer = 6 * 7
            fun describe(n: Int) = "answer is $n"
            """.trimIndent(),
            agency
        )

        // when
        val phenomena = executor.execute("Phenomenon.Impediment(describe(answer))", agency).result

        // then
        assert(phenomena == listOf(text("Impediment(reason=answer is 42)", "text/plain")))
    }

    @Test
    fun `should provide cogitation of intent and agency of current execution through agency`() = runTest(timeout = 5.minutes) {
        // given
        val first = TestAgency()
        val second = TestAgency(Cogitation(constitution = "", enunciations = emptyList(), depth = 7))
        val firstExecuted = executor.execute("agency.enunciate(\"first\")\nagency.invoke(\"first\")", first)

        // when
        val secondExecuted = executor.execute(
            "agency.enunciate(\"second\")\nagency.invoke(\"second\")\nagency.cogitation().depth",
            second
        )

        // then
        assert(secondExecuted.result == listOf(text("7", "text/plain")))
        assert(firstExecuted.invoked.single().prompt == "first" && secondExecuted.invoked.single().prompt == "second")
        assert(firstExecuted.output == listOf(text("first")))
        assert(secondExecuted.output == listOf(text("second")))
    }

    @Test
    fun `should launch coroutine outliving intent`() = runTest(timeout = 5.minutes) {
        // given
        executor.execute(
            """
            val started = CompletableDeferred<String>()
            val job = launch { started.complete("launched") }
            """.trimIndent(),
            TestAgency()
        )

        // when
        val phenomena = executor.execute("job.join(); started.await()", TestAgency()).result

        // then
        assert(phenomena == listOf(text("launched", "text/plain")))
    }

    @Test
    fun `should call suspend function directly in intent`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
            """
            delay(1)
            coroutineScope { async { 42 }.await() }
            """.trimIndent(),
            TestAgency()
        ).result

        // then
        assert(phenomena == listOf(text("42", "text/plain")))
    }

    // The thread of a session is not interrupted as its intent is cancelled, see ScriptSession of xemantic-kotlin-script,
    // so that a process the intent executes would run to its end, and the next intents of the session would wait for it.
    @Test
    fun `should destroy process of cancelled intent and execute the next one`() = runTest(timeout = 5.minutes) {
        // given
        val started = createTempDirectory().resolve("started")

        // when
        val next = withContext(Dispatchers.Default) {
            coroutineScope {
                val slow = async {
                    executor.execute("Path(\"$started\").writeText(\"\")\nagency.bash(\"sleep 600\", timeout = 20.minutes)", TestAgency())
                }
                while (!started.exists()) delay(10)
                slow.cancel()
            }
            // the intents of a session execute one after another, so this one waits until the slow one ends
            executor.execute("6 * 7", TestAgency()).result
        }

        // then
        assert(next == listOf(text("42", "text/plain")))
    }

    // The REPL compiler of xemantic-kotlin-script before 0.3.0 crashed internally on a function calling `await`.
    @Test
    fun `should await within suspend function declared in intent`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
            """
            suspend fun answer(): Int = await { delay(1); 42 }
            answer()
            """.trimIndent(),
            TestAgency()
        ).result

        // then
        assert(phenomena == listOf(text("42", "text/plain")))
    }

    // The functions of the agency suspend, so a helper calling one, as models wrote them, e.g. `fun sh(cmd: String) = agency.bash(cmd)`,
    // has to be declared suspend, which the error tells.
    @Test
    fun `should impede function calling agency which does not suspend`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
            """
            fun sh(command: String): String = agency.bash(command).output
            sh("echo 42")
            """.trimIndent(),
            TestAgency()
        ).result

        // then
        val reason = (phenomena.single() as Phenomenon.Impediment).reason
        assert("can only be called from a coroutine or another suspend function" in reason)
    }

    // `await` is kept for the scripts written before xemantic-kotlin-script 0.3.0, when the snippets did not suspend.
    @Test
    fun `should await suspend function declared in intent`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
            """
            suspend fun answer(): Int = coroutineScope { async { delay(1); 42 }.await() }
            await { answer() }
            """.trimIndent(),
            TestAgency()
        ).result

        // then
        assert(phenomena == listOf(text("42", "text/plain")))
    }

    // A function or class of inferred types, used in the intent declaring it, can still refer to what an earlier intent
    // declared through a val of the same intent.
    @Test
    fun `should use function and class referring to declaration of earlier intent through val of same intent`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()
        executor.execute("val sequence = \"gattaca\"", agency)

        // when
        val phenomena = executor.execute(
            """
            val bases = sequence
            fun prefix(length: Int) = bases.take(length)
            class Suffix(val length: Int) { val text = bases.takeLast(length) }
            prefix(3) + "-" + Suffix(3).text
            """.trimIndent(),
            agency
        ).result

        // then
        assert(phenomena == listOf(text("gat-aca", "text/plain")))
    }

    // The constitution tells to declare the types of a function used in the intent declaring it,
    // as the compiler then resolves what an earlier intent declared within it.
    @Test
    fun `should use function of declared type referring to declaration of earlier intent`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()
        executor.execute("val genome = \"gattaca\"", agency)

        // when
        val phenomena = executor.execute(
            """
            fun prefix(length: Int): String = genome.take(length)
            prefix(3)
            """.trimIndent(),
            agency
        ).result

        // then
        assert(phenomena == listOf(text("gat", "text/plain")))
    }

    // The REPL compiler of xemantic-kotlin-script before 0.3.0 could not resolve the declarations of earlier snippets
    // from a function of inferred type used in the snippet declaring it.
    @Test
    fun `should use function of inferred type referring to declaration of earlier intent`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()
        executor.execute("val genome = \"gattaca\"", agency)

        // when
        val phenomena = executor.execute(
            """
            fun prefix(length: Int) = genome.take(length)
            prefix(3)
            """.trimIndent(),
            agency
        ).result

        // then
        assert(phenomena == listOf(text("gat", "text/plain")))
    }

    @Test
    fun `should use class with properties of declared types referring to declaration of earlier intent`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()
        executor.execute("val genome = \"gattaca\"", agency)

        // when
        val phenomena = executor.execute(
            """
            class Suffix(val length: Int) { val text: String = genome.takeLast(length) }
            Suffix(3).text
            """.trimIndent(),
            agency
        ).result

        // then
        assert(phenomena == listOf(text("aca", "text/plain")))
    }

    @Test
    fun `should use suspend function of declared type referring to agency in intent declaring it`() = runTest(timeout = 5.minutes) {
        // given
        val server = serve("/seq") { exchange ->
            val body = """{"seq": "${exchange.requestURI.path.substringAfterLast('/')}"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val url = "http://localhost:${server.address.port}/seq"

        // when
        // as Claude wrote it, with the type declared
        val phenomena = try {
            executor.execute(
                $$"""
                import io.ktor.client.call.*
                import io.ktor.client.request.*
                import kotlinx.serialization.json.*

                suspend fun seq(id: String): String = agency.http.get("$$url/$id").body<JsonElement>()["seq"].string
                seq("MKSIY")
                """.trimIndent(),
                TestAgency()
            ).result
        } finally {
            server.stop(0)
        }

        // then
        assert(phenomena == listOf(text("MKSIY", "text/plain")))
    }

    /** Serves the [handler] on a free port of the loopback address, until stopped. */
    private fun serve(path: String, handler: HttpHandler): HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext(path, handler)
            // handles requests concurrently, like a real server
            executor = Executors.newCachedThreadPool()
            start()
        }

    @Test
    fun `should post and receive JSON with http client of agency`() = runTest(timeout = 5.minutes) {
        // given
        // responds with the method and the question posted, and the answer, with a field to ignore
        val server = serve("/answer") { exchange ->
            val question = exchange.requestBody.readBytes().decodeToString().substringAfter("\"question\":\"").substringBefore('"')
            val body = """{"question": "${exchange.requestMethod} $question", "answer": 42, "unknown": true}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val url = "http://localhost:${server.address.port}/answer"

        // when
        val phenomena = try {
            executor.execute(
                $$"""
                import io.ktor.client.call.*
                import io.ktor.client.plugins.*
                import io.ktor.client.request.*
                import io.ktor.http.*
                import kotlinx.serialization.json.*

                val json = agency.http.post("$$url") {
                    timeout { requestTimeoutMillis = 300_000 }
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("question", "ultimate") })
                }.body<JsonObject>()
                "${json["question"]!!.jsonPrimitive.content}: ${json["answer"]!!.jsonPrimitive.int}"
                """.trimIndent(),
                TestAgency()
            ).result
        } finally {
            server.stop(0)
        }

        // then
        assert(phenomena == listOf(text("POST ultimate: 42", "text/plain")))
    }

    @Test
    fun `should receive JSON as data class declared by intent with http client of agency`() = runTest(timeout = 5.minutes) {
        // given
        // responds with the answer, and a field to ignore
        val server = serve("/answer") { exchange ->
            val body = """{"question": "ultimate", "answer": 42, "unknown": true}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val url = "http://localhost:${server.address.port}/answer"

        // when
        val phenomena = try {
            executor.execute(
                $$"""
                import io.ktor.client.call.*
                import io.ktor.client.request.*
                import kotlinx.serialization.Serializable

                @Serializable
                data class Answer(val question: String, val answer: Int)

                val answer = agency.http.get("$$url").body<Answer>()
                "${answer.question}: ${answer.answer}"
                """.trimIndent(),
                TestAgency()
            ).result
        } finally {
            server.stop(0)
        }

        // then
        assert(phenomena == listOf(text("ultimate: 42", "text/plain")))
    }

    @Test
    fun `should navigate JSON received as JsonElement by http client of agency`() = runTest(timeout = 5.minutes) {
        // given
        val server = serve("/gene") { exchange ->
            val body = """{"id": "GCG", "Transcript": [{"id": "T1", "is_canonical": false}, {"id": "T2", "is_canonical": true, "Translation": {"id": "P2"}}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val url = "http://localhost:${server.address.port}/gene"

        // when
        val phenomena = try {
            executor.execute(
                $$"""
                import io.ktor.client.call.*
                import io.ktor.client.request.*
                import kotlinx.serialization.json.*

                val gene = agency.http.get("$$url").body<JsonElement>()
                val canonical = gene["Transcript"].list.first { it["is_canonical"].boolean }
                "${gene["id"].string}: ${canonical["id"].string} ${canonical["Translation"]["id"].string}"
                """.trimIndent(),
                TestAgency()
            ).result
        } finally {
            server.stop(0)
        }

        // then
        assert(phenomena == listOf(text("GCG: T2 P2", "text/plain")))
    }

    @Test
    fun `should send requests concurrently with http client of agency`() = runTest(timeout = 5.minutes) {
        // given
        // each request waits for the other two to arrive, so requests sent one after another fail with 503
        val arrivals = CyclicBarrier(3)
        val server = serve("/item") { exchange ->
            val id = exchange.requestURI.path.substringAfterLast('/')
            val arrivedTogether = try {
                arrivals.await(30, TimeUnit.SECONDS)
                true
            } catch (_: Exception) {
                false
            }
            val body = """{"id": "$id"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (arrivedTogether) 200 else 503, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val url = "http://localhost:${server.address.port}/item"

        // when
        val phenomena = try {
            executor.execute(
                $$"""
                import io.ktor.client.call.*
                import io.ktor.client.request.*
                import kotlinx.coroutines.sync.*
                import kotlinx.serialization.json.*

                val ids = listOf("a", "b", "c")
                val permits = Semaphore(5)
                val items = coroutineScope {
                    ids.map { id ->
                        async { permits.withPermit { agency.http.get("$$url/$id").body<JsonElement>() } }
                    }.awaitAll()
                }
                items.joinToString { it["id"].string }
                """.trimIndent(),
                TestAgency()
            ).result
        } finally {
            server.stop(0)
        }

        // then
        assert(phenomena == listOf(text("a, b, c", "text/plain")))
    }

    @Test
    fun `should append to string builder in its scope opened by intent`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
            $$"""
            val report = StringBuilder()
            for ((name, length) in listOf("dog" to 180, "cow" to 180)) report {
                +"$name: $length aa\n"
            }
            report
            """.trimIndent(),
            TestAgency()
        ).result

        // then
        assert(phenomena == listOf(text("dog: 180 aa\ncow: 180 aa\n", "text/plain")))
    }

    @Test
    fun `should append with unary plus in buildString of intent`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
            $$"""
            buildString {
                for ((name, length) in listOf("dog" to 180, "cow" to 180)) +"$name: $length aa\n"
            }
            """.trimIndent(),
            TestAgency()
        ).result

        // then
        assert(phenomena == listOf(text("dog: 180 aa\ncow: 180 aa\n", "text/plain")))
    }

    @Test
    fun `should append every part joined with plus after unary plus in intent`() = runTest(timeout = 5.minutes) {
        // when
        // as cognizers write it, e.g. deepseek
        val phenomena = executor.execute(
            $$"""
            fun digest(name: String) = "$name: present"
            buildString {
                +"DOG: " + digest("GCG") + "\n"
                +digest("GLP1R") + "\n"
            }
            """.trimIndent(),
            TestAgency()
        ).result

        // then
        assert(phenomena == listOf(text("DOG: GCG: present\nGLP1R: present\n", "text/plain")))
    }

    @Test
    fun `should retry request limited by rate with http client of agency`() = runTest(timeout = 5.minutes) {
        // given
        // limits the rate of the first request, telling to retry right away, and serves the second one
        val requests = AtomicInteger()
        val server = serve("/limited") { exchange ->
            if (requests.incrementAndGet() == 1) {
                exchange.responseHeaders.add("Retry-After", "0")
                exchange.sendResponseHeaders(429, -1)
                exchange.close()
            } else {
                val body = "served".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        val url = "http://localhost:${server.address.port}/limited"

        // when
        val phenomena = try {
            executor.execute(
                $$"""
                import io.ktor.client.request.*
                import io.ktor.client.statement.*

                agency.http.get("$$url").bodyAsText()
                """.trimIndent(),
                TestAgency()
            ).result
        } finally {
            server.stop(0)
        }

        // then
        assert(phenomena == listOf(text("served", "text/plain")))
        assert(requests.get() == 2)
    }

    // The example of the HTTP section of the constitution, as it is written there.
    @Test
    fun `should run JSON example of constitution digesting with buildString`() = runTest(timeout = 5.minutes) {
        // given
        val server = serve("/items") { exchange ->
            val body = """{"name": "honest annie", "stock": {"count": 3}, "price": 4.2, "tags": [{"label": "kotlin", "active": true}, {"label": "old", "active": false}, {"label": "agent", "active": true}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val url = "http://localhost:${server.address.port}/items"

        // when
        val phenomena = try {
            executor.execute(
                $$"""
                import io.ktor.client.call.*
                import io.ktor.client.request.*
                import kotlinx.serialization.json.*

                val item = agency.http.get("$$url/42").body<JsonElement>()
                val tags = item["tags"].list.filter { it["active"].boolean }.map { it["label"].string }
                buildString {
                    +"${item["name"].string}: ${item["stock"]["count"].int} in stock, price ${item.getOrNull("price")?.double}\n"
                    for (tag in tags) +"- $tag\n"
                }
                """.trimIndent(),
                TestAgency()
            ).result
        } finally {
            server.stop(0)
        }

        // then
        assert(phenomena == listOf(text("honest annie: 3 in stock, price 4.2\n- kotlin\n- agent\n", "text/plain")))
    }

    @Test
    fun `should fail intent on error status received by http client of agency`()= runTest(timeout = 5.minutes) {
        // given
        val server = serve("/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        val url = "http://localhost:${server.address.port}/missing"

        // when
        val phenomena = try {
            executor.execute(
                $$"""
                import io.ktor.client.request.*
                import io.ktor.client.statement.*

                agency.http.get("$$url").bodyAsText()
                """.trimIndent(),
                TestAgency()
            ).result
        } finally {
            server.stop(0)
        }

        // then
        val reason = (phenomena.single() as Phenomenon.Impediment).reason
        assert("404" in reason)
    }

    @Test
    fun `should impede intent using Python which cannot be prepared, telling to run Python as a process`() = runTest(timeout = 5.minutes) {
        // given
        val unprepared = SessionScriptExecutor(preparePython = { error("No jep in the Python of the host") })

        // when
        val phenomena = unprepared.use {
            it.execute("agency.python(\"6 * 7\")", TestAgency()).result
        }

        // then
        val reason = (phenomena.single() as Phenomenon.Impediment).reason
        assert("agency.python is unavailable" in reason)
        assert("No jep in the Python of the host" in reason)
        assert("agency.execute(listOf(\"python3\"" in reason)
    }

    @Test
    fun `should close executor again after Python was used`() = runTest(timeout = 5.minutes) {
        // given
        val used = SessionScriptExecutor()
        used.execute("agency.python(\"6 * 7\")", TestAgency())
        used.close()

        // when
        val closedAgain = runCatching { used.close() }

        // then
        assert(closedAgain.isSuccess)
    }

    @Test
    fun `should keep Python state across intents`() = runTest(timeout = 5.minutes) {
        // given
        executor.execute("agency.python(\"import math\\nfactor = 6\")", TestAgency())

        // when
        val phenomena = executor.execute("agency.python(\"math.prod([factor, 7])\")", TestAgency()).result

        // then
        assert(phenomena == listOf(text("42", "text/plain")))
    }

    @Test
    fun `should exchange values between Kotlin and Python`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
            """
            agency.python["numbers"] = listOf(1, 2, 3)
            agency.python("squares = [n * n for n in numbers]")
            (agency.python("squares") as List<*>).joinToString()
            """.trimIndent(),
            TestAgency()
        ).result

        // then
        assert(phenomena == listOf(text("1, 4, 9", "text/plain")))
    }

    @Test
    fun `should perceive what Python printed before the value of intent`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
            "agency.python(\"print('hello')\\nimport sys\\nprint('warning', file=sys.stderr)\\n6 * 7\")",
            TestAgency()
        ).result

        // then
        assert(phenomena == listOf(
            text("Printed by Python:\nhello\nwarning", "text/plain"),
            text("42", "text/plain")
        ))
    }

    @Test
    fun `should perceive what Python printed before failing`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
            "agency.python(\"print('before')\\nraise ValueError('boom')\")",
            TestAgency()
        ).result

        // then
        assert(phenomena.size == 2)
        assert(phenomena.first() == text("Printed by Python:\nbefore", "text/plain"))
        assert((phenomena.last() as Phenomenon.Impediment).reason.contains("ValueError"))
        assert((phenomena.last() as Phenomenon.Impediment).reason.contains("boom"))
    }

    @Test
    fun `should not perceive what Python of another executor printed`() = runTest(timeout = 5.minutes) {
        // given
        SessionScriptExecutor().use { other ->
            other.execute("agency.python(\"x = 1\")", TestAgency())
            executor.execute("agency.python(\"x = 2\")", TestAgency())

            // when
            val printed = other.execute("agency.python(\"print(x)\")", TestAgency()).result
            val nothing = executor.execute("agency.python(\"x\")", TestAgency()).result

            // then
            assert(printed == listOf(text("Printed by Python:\n1", "text/plain")))
            assert(nothing == listOf(text("2", "text/plain")))
        }
    }

    @Test
    fun `should digest what Python printed`() = runTest(timeout = 5.minutes) {
        // when
        val executed = executor.execute(
            "agency.python(\"for i in range(200000): print(i)\")",
            TestAgency()
        )

        // then
        val printed = executed.text(executed.result.single())
        assert(printed.length in 1_000_000..1_001_000)
        assert(printed.startsWith("Printed by Python:\n0\n1\n"))
        assert(printed.contains(Regex("\\d+ more characters omitted")))
        val file = Regex("the whole text being in (\\S+)$").find(printed)?.groupValues?.get(1)
        assert(file != null && Path(file).readText() == (0 until 200_000).joinToString("\n"))
    }

    @Test
    fun `should execute a process of the executor through the agency of the intent`() = runTest(timeout = 5.minutes) {
        // given
        // the agency of the host executes no process, as the files of the intents are the executor's
        val agency = TestAgency()

        // when
        val phenomena = executor.execute("agency.bash(\"echo hello\").output", agency).result

        // then
        assert(phenomena == listOf(text("hello\n", "text/plain")))
    }

    @Test
    fun `should resolve the declarations of the executor through the agency of the intent`() = runTest(timeout = 5.minutes) {
        // given
        // the agency of the host resolves nothing, as the scripts compile against the classpath of the executor
        val agency = TestAgency()

        // when
        val resolved = executor.execute("agency.resolveDeclaration(\"com.xemantic.annie.Agency\")", agency)
        val searched = executor.execute("agency.searchDeclarations(\"ScriptExecutor\")", agency)

        // then
        assert("interface Agency" in resolved.text(resolved.result.single()))
        assert("com.xemantic.annie.ScriptExecutor" in searched.text(searched.result.single()))
    }

    @Test
    fun `should refuse a process and an enunciation to the agency of an intent already executed`() = runTest(timeout = 5.minutes) {
        // given
        val released = "annie.test.released.${randomId()}"
        val stray = "annie.test.stray.${randomId()}"
        executor.execute(
            """
            Thread {
                while (System.getProperty("$released") == null) Thread.sleep(10)
                // the functions of the agency suspend, called from a thread of its own within runBlocking
                val executed = runCatching { runBlocking { agency.bash("echo late") } }
                val enunciated = runCatching { runBlocking { agency.enunciate("late") } }
                System.setProperty("$stray", listOf(executed, enunciated).joinToString("\n") { it.exceptionOrNull()?.message ?: "done" })
            }.start()
            """.trimIndent(),
            TestAgency()
        )

        // when
        System.setProperty(released, "true")
        while (System.getProperty(stray) == null) Thread.sleep(10)

        // then
        val refusal = "The intent is executed, its agency cannot be used anymore"
        assert(System.getProperty(stray) == "$refusal\n$refusal")
    }

    @Test
    fun `should perceive what Python script of intent printed and the repr of its value`() = runTest(timeout = 5.minutes) {
        // given
        val intent = Phenomenon.Intent(language = Phenomenon.Intent.Language.PYTHON, script = "print('hello')\nitems = [1, 'a', None]\n{'items': items, 'ok': True}")

        // when
        val phenomena = executor.execute(intent, TestAgency()).result

        // then
        assert(phenomena == listOf(
            text("hello", "text/plain"),
            text("{'items': [1, 'a', None], 'ok': True}", "text/plain")
        ))
    }

    @Test
    fun `should perceive nothing of Python script ending with statement or None`() = runTest(timeout = 5.minutes) {
        // when
        val statement = executor.execute(Phenomenon.Intent(language = Phenomenon.Intent.Language.PYTHON, script = "x = 1"), TestAgency()).result
        val none = executor.execute(Phenomenon.Intent(language = Phenomenon.Intent.Language.PYTHON, script = "None"), TestAgency()).result

        // then
        assert(statement.isEmpty())
        assert(none.isEmpty())
    }

    @Test
    fun `should share state between Python scripts and agency python of Kotlin scripts`() = runTest(timeout = 5.minutes) {
        // given
        executor.execute(Phenomenon.Intent(language = Phenomenon.Intent.Language.PYTHON, script = "import math\nfactor = 6"), TestAgency())

        // when
        val phenomena = executor.execute("agency.python(\"math.prod([factor, 7])\")", TestAgency()).result

        // then
        assert(phenomena == listOf(text("42", "text/plain")))
    }

    @Test
    fun `should impede Python script failing`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(Phenomenon.Intent(language = Phenomenon.Intent.Language.PYTHON, script = "1 / 0"), TestAgency()).result

        // then
        assert("ZeroDivisionError" in (phenomena.single() as Phenomenon.Impediment).reason)
    }

    @Test
    fun `should perceive output error and exit code of bash script of intent`() = runTest(timeout = 5.minutes) {
        // given
        val intent = Phenomenon.Intent(language = Phenomenon.Intent.Language.BASH, script = "echo out\necho err >&2\nexit 3")

        // when
        val phenomena = executor.execute(intent, TestAgency()).result

        // then
        assert(phenomena == listOf(text("out\nstandard error:\nerr\nexit code: 3", "text/plain")))
    }

    @Test
    fun `should perceive only exit code of bash script printing nothing`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(Phenomenon.Intent(language = Phenomenon.Intent.Language.BASH, script = "true"), TestAgency()).result

        // then
        assert(phenomena == listOf(text("exit code: 0", "text/plain")))
    }

    @Test
    fun `should apply edits before bash script of intent`() = runTest(timeout = 5.minutes) {
        // given
        val file = createTempDirectory().resolve("notes.txt")
        val intent = Phenomenon.Intent(edits = listOf(Edit(path = file.toString(), new = "edited\n")), language = Phenomenon.Intent.Language.BASH, script = "cat $file")

        // when
        val executed = executor.execute(intent, TestAgency())
        val phenomena = executed.result

        // then
        assert(phenomena.size == 2)
        assert(executed.text(phenomena.first()).startsWith("Wrote $file"))
        assert(phenomena.last() == text("edited\nexit code: 0", "text/plain"))
    }

    @Test
    fun `should destroy bash script of intent once its timeout elapses`() = runTest(timeout = 5.minutes) {
        // given
        val intent = Phenomenon.Intent(language = Phenomenon.Intent.Language.BASH, script = "echo started\nsleep 600", timeout = 1.seconds)

        // when
        val phenomena = executor.execute(intent, TestAgency()).result

        // then
        assert(phenomena == listOf(text("started\ndestroyed as its timeout of 1s elapsed", "text/plain")))
    }

    @Test
    fun `should impede Kotlin script of intent suspended once its timeout elapses`() = runTest(timeout = 5.minutes) {
        // given
        val intent = Phenomenon.Intent(script = "delay(600_000)", timeout = 1.seconds)

        // when
        val phenomena = withContext(Dispatchers.Default) { executor.execute(intent, TestAgency()).result }

        // then
        assert("timeout of 1s" in (phenomena.single() as Phenomenon.Impediment).reason)
    }

    @Test
    fun `should impede Python script of intent once its timeout elapses`() = runTest(timeout = 5.minutes) {
        // given
        val intent = Phenomenon.Intent(language = Phenomenon.Intent.Language.PYTHON, script = "import time\ntime.sleep(3)", timeout = 1.seconds)

        // when
        val phenomena = withContext(Dispatchers.Default) { executor.execute(intent, TestAgency()).result }

        // then
        assert("timeout of 1s" in (phenomena.single() as Phenomenon.Impediment).reason)
    }

}
