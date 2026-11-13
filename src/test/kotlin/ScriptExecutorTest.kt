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

import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import com.xemantic.ai.annie.host.annieHttpClient
import com.xemantic.kotlin.test.assert
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

class ScriptExecutorTest {

    private val executor = SessionScriptExecutor()

    private val cogitation = Cogitation(constitution = emptyList(), harness = "", enunciations = emptyList())

    @AfterTest
    fun closeExecutor() {
        executor.close()
    }

    /** [Agency] keeping everything enunciated, and invoking nothing for real. */
    private class TestAgency : Agency {
        override val cogitation = Cogitation(constitution = emptyList(), harness = "", enunciations = emptyList())
        override val constitution = ""
        override val harness = ""
        override val parentCogitation: Cogitation? = null
        override val depth = 0
        override val invocations = mutableListOf<Phenomenon.Invocation>()
        override val enunciated = mutableListOf<Phenomenon>()
        override fun invoke(prompt: String, constitution: String, harness: String) = Phenomenon.Invocation(
            id = "invocation-${invocations.size + 1}",
            prompt = prompt,
            constitution = constitution,
            harness = harness
        ).also { invocations += it }
        override fun enunciate(text: String, contentType: String) {
            enunciated += Phenomenon.Text(text, contentType)
        }
        override fun enunciate(bytes: ByteArray, contentType: String?) {
            enunciated += Phenomenon.Data(bytes, contentType)
        }
        override fun impede(reason: String) {
            enunciated += Phenomenon.Impediment(reason)
        }
        override fun resolveDeclaration(name: String, page: Int, pageSize: Int): String? = null
        override fun searchDeclarations(query: String, page: Int, pageSize: Int): String? = null
        override fun execute(
            command: List<String>,
            input: String?,
            directory: String?,
            environment: Map<String, String>,
            timeout: Duration,
            maxOutputLength: Int
        ): Execution = throw UnsupportedOperationException()
        // the Python of the intents is the one of the executor
        override val python: Python get() = throw UnsupportedOperationException()
        override val http = annieHttpClient
    }

    @Test
    fun `should return invocations followed by value, excluding enunciated output`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()

        // when
        val phenomena = executor.execute(
            """
            agency.enunciate("first")
            agency.invoke("sub")
            agency.enunciate(byteArrayOf(4, 2))
            agency.impede("stuck")
            42
            """.trimIndent(),
            cogitation,
            agency
        )

        // then
        assert(phenomena == listOf(
            agency.invocations.single(),
            Phenomenon.Text("42", Phenomenon.Text.PLAIN)
        ))
        assert(agency.enunciated == listOf(
            Phenomenon.Text("first"),
            Phenomenon.Data(byteArrayOf(4, 2)),
            Phenomenon.Impediment("stuck")
        ))
    }

    @Test
    fun `should return value of intent as text`() = runTest(timeout = 5.minutes) {
        // when
        val number = executor.execute("6 * 7", cogitation, TestAgency())
        val string = executor.execute("\"*answer*\"", cogitation, TestAgency())

        // then
        assert(number == listOf(Phenomenon.Text("42", Phenomenon.Text.PLAIN)))
        assert(string == listOf(Phenomenon.Text("*answer*", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should resolve the API of annie without importing it`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
            "fun said(phenomenon: Phenomenon) = (phenomenon as Phenomenon.Text).text\n" +
                "said(Phenomenon.Text(\"no import needed\"))",
            cogitation,
            TestAgency()
        )

        // then
        assert(phenomena == listOf(Phenomenon.Text("no import needed", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should digest value of intent, telling to keep the whole value in a val`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute("(0 until 100_000).joinToString(\"\\n\")", cogitation, TestAgency())

        // then
        val value = (phenomena.single() as Phenomenon.Text).text
        assert(value.length < 11_000)
        assert(value.startsWith("0\n1\n2\n"))
        assert(value.contains(Regex("\\d+ more characters omitted")))
        assert(value.contains("val"))
    }

    @Test
    fun `should interpolate only multi-dollar references in multi-dollar string of intent`() = runTest(timeout = 5.minutes) {
        // given
        val intent = "val n = 42\n\$\$\"\"\"for c in a b; do echo \"\$c \$\$n\"; done\"\"\""

        // when
        val result = executor.execute(intent, cogitation, TestAgency())

        // then
        assert(result == listOf(Phenomenon.Text("""for c in a b; do echo "${'$'}c 42"; done""", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should interpolate triple quotes of embedded code in multi-dollar string of intent`() = runTest(timeout = 5.minutes) {
        // given
        val intent = "\$\$\"\"\"\ndef f(\$x):\n    \$\${\"\\\"\\\"\\\"\"}Doc.\$\${\"\\\"\\\"\\\"\"}\n\"\"\".trimIndent()"

        // when
        val result = executor.execute(intent, cogitation, TestAgency())

        // then
        val python = "def f(\$x):\n    \"\"\"Doc.\"\"\""
        assert(result == listOf(Phenomenon.Text(python, Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should return data value of intent as data, to perceive an image`() = runTest(timeout = 5.minutes) {
        // when
        val result = executor.execute(
            """
            import com.xemantic.ai.annie.*
            Phenomenon.Data(byteArrayOf(4, 2), "image/png")
            """.trimIndent(),
            cogitation,
            TestAgency()
        )

        // then
        assert(result == listOf(Phenomenon.Data(byteArrayOf(4, 2), "image/png")))
    }

    @Test
    fun `should return nothing for intent evaluating to Unit or null`()= runTest(timeout = 5.minutes) {
        // when
        val unit = executor.execute("agency.enunciate(\"output\")", cogitation, TestAgency())
        val nothing = executor.execute("null", cogitation, TestAgency())

        // then
        assert(unit.isEmpty())
        assert(nothing.isEmpty())
    }

    @Test
    fun `should not repeat invocation which is the value of intent`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()

        // when
        val phenomena = executor.execute("agency.invoke(\"sub\")", cogitation, agency)

        // then
        assert(phenomena == listOf(agency.invocations.single()))
    }

    @Test
    fun `should not repeat invocations which are the value of intent`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()

        // when
        val phenomena = executor.execute(
            "listOf(\"first\", \"second\").map { agency.invoke(it) }",
            cogitation,
            agency
        )

        // then
        assert(phenomena == agency.invocations)
    }

    @Test
    fun `should impede intent failing to compile with a digest of the first errors`() = runTest(timeout = 5.minutes) {
        // given
        val python = (1..500).joinToString("\n") { "for i,o in enumerate(opts$it): print(i)" }

        // when
        val phenomena = executor.execute(python, cogitation, TestAgency())

        // then
        val reason = (phenomena.single() as Phenomenon.Impediment).reason
        assert(reason.length <= 5000)
        assert(reason.contains(Regex("^snippet\\d+\\.kts:1:")))
        assert(reason.contains(Regex("\\d+ more errors omitted")))
    }

    @Test
    fun `should return impediment of failing intent`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()

        // when
        val phenomena = executor.execute(
            """
            agency.enunciate("before")
            agency.invoke("sub")
            error("boom")
            """.trimIndent(),
            cogitation,
            agency
        )

        // then
        assert(phenomena.size == 2)
        assert(phenomena.first() == agency.invocations.single())
        assert((phenomena.last() as Phenomenon.Impediment).reason.contains("boom"))
        // the failure impedes the intent, not the cogitation
        assert(agency.enunciated == listOf(Phenomenon.Text("before")))
    }

    @Test
    fun `should keep declarations of intent visible to the next intents`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()
        executor.execute(
            $$"""
            import com.xemantic.ai.annie.*
            val answer = 6 * 7
            fun describe(n: Int) = "answer is $n"
            """.trimIndent(),
            cogitation,
            agency
        )

        // when
        val phenomena = executor.execute("Phenomenon.Text(describe(answer))", cogitation, agency)

        // then
        assert(phenomena == listOf(Phenomenon.Text("Text(text=answer is 42, contentType=text/markdown)", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should provide cogitation of intent and agency of current execution through agency`() = runTest(timeout = 5.minutes) {
        // given
        val first = TestAgency()
        val second = TestAgency()
        val expressed = cogitation.copy(harness = "expressed")
        executor.execute("agency.enunciate(\"first\")", cogitation, first)

        // when
        val phenomena = executor.execute(
            "agency.enunciate(\"second\")\nagency.cogitation.harness",
            expressed,
            second
        )

        // then
        assert(phenomena == listOf(Phenomenon.Text("expressed", Phenomenon.Text.PLAIN)))
        assert(first.enunciated == listOf(Phenomenon.Text("first")))
        assert(second.enunciated == listOf(Phenomenon.Text("second")))
    }

    @Test
    fun `should await suspend function and launch coroutine outliving intent`() = runTest(timeout = 5.minutes) {
        // given
        executor.execute(
            """
            val started = CompletableDeferred<String>()
            val job = launch { started.complete("launched") }
            """.trimIndent(),
            cogitation,
            TestAgency()
        )

        // when
        val phenomena = executor.execute("await { job.join(); started.await() }", cogitation, TestAgency())

        // then
        assert(phenomena == listOf(Phenomenon.Text("launched", Phenomenon.Text.PLAIN)))
    }

    // The constitution tells to declare a suspend function rather than calling `await` within a function,
    // which fails the compiler internally.
    @Test
    fun `should await suspend function declared in intent`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
            """
            suspend fun answer(): Int = coroutineScope { async { delay(1); 42 }.await() }
            await { answer() }
            """.trimIndent(),
            cogitation,
            TestAgency()
        )

        // then
        assert(phenomena == listOf(Phenomenon.Text("42", Phenomenon.Text.PLAIN)))
    }

    // A function or class of inferred types, used in the intent declaring it, can still refer to what an earlier intent
    // declared through a val of the same intent.
    @Test
    fun `should use function and class referring to declaration of earlier intent through val of same intent`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()
        executor.execute("val sequence = \"gattaca\"", cogitation, agency)

        // when
        val phenomena = executor.execute(
            """
            val bases = sequence
            fun prefix(length: Int) = bases.take(length)
            class Suffix(val length: Int) { val text = bases.takeLast(length) }
            prefix(3) + "-" + Suffix(3).text
            """.trimIndent(),
            cogitation,
            agency
        )

        // then
        assert(phenomena == listOf(Phenomenon.Text("gat-aca", Phenomenon.Text.PLAIN)))
    }

    // The constitution tells to declare the types of a function used in the intent declaring it,
    // as the compiler then resolves what an earlier intent declared within it.
    @Test
    fun `should use function of declared type referring to declaration of earlier intent`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()
        executor.execute("val genome = \"gattaca\"", cogitation, agency)

        // when
        val phenomena = executor.execute(
            """
            fun prefix(length: Int): String = genome.take(length)
            prefix(3)
            """.trimIndent(),
            cogitation,
            agency
        )

        // then
        assert(phenomena == listOf(Phenomenon.Text("gat", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should impede function of inferred type referring to declaration of earlier intent, hinting to declare its type`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()
        executor.execute("val genome = \"gattaca\"", cogitation, agency)

        // when
        val phenomena = executor.execute(
            """
            fun prefix(length: Int) = genome.take(length)
            prefix(3)
            """.trimIndent(),
            cogitation,
            agency
        )

        // then
        val reason = (phenomena.single() as Phenomenon.Impediment).reason
        assert("Unresolved reference 'genome'" in reason)
        assert("declare the type" in reason)
    }

    @Test
    fun `should use class with properties of declared types referring to declaration of earlier intent`() = runTest(timeout = 5.minutes) {
        // given
        val agency = TestAgency()
        executor.execute("val genome = \"gattaca\"", cogitation, agency)

        // when
        val phenomena = executor.execute(
            """
            class Suffix(val length: Int) { val text: String = genome.takeLast(length) }
            Suffix(3).text
            """.trimIndent(),
            cogitation,
            agency
        )

        // then
        assert(phenomena == listOf(Phenomenon.Text("aca", Phenomenon.Text.PLAIN)))
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
                await { seq("MKSIY") }
                """.trimIndent(),
                cogitation,
                TestAgency()
            )
        } finally {
            server.stop(0)
        }

        // then
        assert(phenomena == listOf(Phenomenon.Text("MKSIY", Phenomenon.Text.PLAIN)))
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

                val json = await {
                    agency.http.post("$$url") {
                        timeout { requestTimeoutMillis = 300_000 }
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject { put("question", "ultimate") })
                    }.body<JsonObject>()
                }
                "${json["question"]!!.jsonPrimitive.content}: ${json["answer"]!!.jsonPrimitive.int}"
                """.trimIndent(),
                cogitation,
                TestAgency()
            )
        } finally {
            server.stop(0)
        }

        // then
        assert(phenomena == listOf(Phenomenon.Text("POST ultimate: 42", Phenomenon.Text.PLAIN)))
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

                val answer = await { agency.http.get("$$url").body<Answer>() }
                "${answer.question}: ${answer.answer}"
                """.trimIndent(),
                cogitation,
                TestAgency()
            )
        } finally {
            server.stop(0)
        }

        // then
        assert(phenomena == listOf(Phenomenon.Text("ultimate: 42", Phenomenon.Text.PLAIN)))
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

                val gene = await { agency.http.get("$$url").body<JsonElement>() }
                val canonical = gene["Transcript"].list.first { it["is_canonical"].boolean }
                "${gene["id"].string}: ${canonical["id"].string} ${canonical["Translation"]["id"].string}"
                """.trimIndent(),
                cogitation,
                TestAgency()
            )
        } finally {
            server.stop(0)
        }

        // then
        assert(phenomena == listOf(Phenomenon.Text("GCG: T2 P2", Phenomenon.Text.PLAIN)))
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
                val items = await {
                    ids.map { id ->
                        async { permits.withPermit { agency.http.get("$$url/$id").body<JsonElement>() } }
                    }.awaitAll()
                }
                items.joinToString { it["id"].string }
                """.trimIndent(),
                cogitation,
                TestAgency()
            )
        } finally {
            server.stop(0)
        }

        // then
        assert(phenomena == listOf(Phenomenon.Text("a, b, c", Phenomenon.Text.PLAIN)))
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
            cogitation,
            TestAgency()
        )

        // then
        assert(phenomena == listOf(Phenomenon.Text("dog: 180 aa\ncow: 180 aa\n", Phenomenon.Text.PLAIN)))
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
            cogitation,
            TestAgency()
        )

        // then
        assert(phenomena == listOf(Phenomenon.Text("dog: 180 aa\ncow: 180 aa\n", Phenomenon.Text.PLAIN)))
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
            cogitation,
            TestAgency()
        )

        // then
        assert(phenomena == listOf(Phenomenon.Text("DOG: GCG: present\nGLP1R: present\n", Phenomenon.Text.PLAIN)))
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

                await { agency.http.get("$$url").bodyAsText() }
                """.trimIndent(),
                cogitation,
                TestAgency()
            )
        } finally {
            server.stop(0)
        }

        // then
        assert(phenomena == listOf(Phenomenon.Text("served", Phenomenon.Text.PLAIN)))
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

                val item = await { agency.http.get("$$url/42").body<JsonElement>() }
                val tags = item["tags"].list.filter { it["active"].boolean }.map { it["label"].string }
                buildString {
                    +"${item["name"].string}: ${item["stock"]["count"].int} in stock, price ${item.getOrNull("price")?.double}\n"
                    for (tag in tags) +"- $tag\n"
                }
                """.trimIndent(),
                cogitation,
                TestAgency()
            )
        } finally {
            server.stop(0)
        }

        // then
        assert(phenomena == listOf(Phenomenon.Text("honest annie: 3 in stock, price 4.2\n- kotlin\n- agent\n", Phenomenon.Text.PLAIN)))
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

                await { agency.http.get("$$url").bodyAsText() }
                """.trimIndent(),
                cogitation,
                TestAgency()
            )
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
            it.execute("agency.python(\"6 * 7\")", cogitation, TestAgency())
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
        used.execute("agency.python(\"6 * 7\")", cogitation, TestAgency())
        used.close()

        // when
        val closedAgain = runCatching { used.close() }

        // then
        assert(closedAgain.isSuccess)
    }

    @Test
    fun `should keep Python state across intents`() = runTest(timeout = 5.minutes) {
        // given
        executor.execute("agency.python(\"import math\\nfactor = 6\")", cogitation, TestAgency())

        // when
        val phenomena = executor.execute("agency.python(\"math.prod([factor, 7])\")", cogitation, TestAgency())

        // then
        assert(phenomena == listOf(Phenomenon.Text("42", Phenomenon.Text.PLAIN)))
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
            cogitation,
            TestAgency()
        )

        // then
        assert(phenomena == listOf(Phenomenon.Text("1, 4, 9", Phenomenon.Text.PLAIN)))
    }

    @Test
    fun `should perceive what Python printed before the value of intent`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
            "agency.python(\"print('hello')\\nimport sys\\nprint('warning', file=sys.stderr)\\n6 * 7\")",
            cogitation,
            TestAgency()
        )

        // then
        assert(phenomena == listOf(
            Phenomenon.Text("Printed by Python:\nhello\nwarning", Phenomenon.Text.PLAIN),
            Phenomenon.Text("42", Phenomenon.Text.PLAIN)
        ))
    }

    @Test
    fun `should perceive what Python printed before failing`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
            "agency.python(\"print('before')\\nraise ValueError('boom')\")",
            cogitation,
            TestAgency()
        )

        // then
        assert(phenomena.size == 2)
        assert(phenomena.first() == Phenomenon.Text("Printed by Python:\nbefore", Phenomenon.Text.PLAIN))
        assert((phenomena.last() as Phenomenon.Impediment).reason.contains("ValueError"))
        assert((phenomena.last() as Phenomenon.Impediment).reason.contains("boom"))
    }

    @Test
    fun `should not perceive what Python of another executor printed`() = runTest(timeout = 5.minutes) {
        // given
        SessionScriptExecutor().use { other ->
            other.execute("agency.python(\"x = 1\")", cogitation, TestAgency())
            executor.execute("agency.python(\"x = 2\")", cogitation, TestAgency())

            // when
            val printed = other.execute("agency.python(\"print(x)\")", cogitation, TestAgency())
            val nothing = executor.execute("agency.python(\"x\")", cogitation, TestAgency())

            // then
            assert(printed == listOf(Phenomenon.Text("Printed by Python:\n1", Phenomenon.Text.PLAIN)))
            assert(nothing == listOf(Phenomenon.Text("2", Phenomenon.Text.PLAIN)))
        }
    }

    @Test
    fun `should digest what Python printed`() = runTest(timeout = 5.minutes) {
        // when
        val phenomena = executor.execute(
            "agency.python(\"for i in range(100000): print(i)\")",
            cogitation,
            TestAgency()
        )

        // then
        val printed = (phenomena.single() as Phenomenon.Text).text
        assert(printed.length < 11_000)
        assert(printed.startsWith("Printed by Python:\n0\n1\n"))
        assert(printed.contains(Regex("\\d+ more characters omitted")))
    }

}
