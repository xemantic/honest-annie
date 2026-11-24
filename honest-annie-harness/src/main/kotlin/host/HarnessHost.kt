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

package com.xemantic.annie.harness.host

import com.xemantic.annie.Actant
import com.xemantic.annie.Cogitation
import com.xemantic.annie.Enunciation
import com.xemantic.annie.Execution
import com.xemantic.annie.Agency
import com.xemantic.annie.Allowance
import com.xemantic.annie.Inference
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.ScriptExecutor
import com.xemantic.annie.Python
import com.xemantic.annie.harness.cognizer.Cognizer
import com.xemantic.annie.executor.PythonVenv
import com.xemantic.annie.executor.SessionScriptExecutor
import com.xemantic.annie.executor.annieHttpClient
import com.xemantic.annie.executor.executeProcess
import com.xemantic.annie.executor.lazyKotlinIntrospector
import com.xemantic.annie.randomId
import com.xemantic.annie.harness.unfold
import com.xemantic.kotlin.introspection.KotlinIntrospector
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

private val logger = KotlinLogging.logger {}

private val host = Actant(type = Actant.Type.COMPUTER, name = "harness host")

/**
 * Hosts the harness unfolding cogitations, see [Cogitation] and [unfold].
 *
 * Unfoldings ready for the next step are queued, and pulled from the queue to be unfolded by the harness.
 * After each step a cogitation is queued again, unless it awaits invocations, or rests:
 * [Actant.SELF] enunciated last.
 *
 * Invocations are not executed by the intents enunciating them: they are recorded in the
 * cogitation, and only then dispatched by the host, each one unfolding a cogitation of its own,
 * whose output is delivered back as a response to the invocation once it rests. Once all of them
 * are answered, the invoking cogitation is queued again. The state of every unfolding is kept
 * in the [store] at each step.
 *
 * Each cogitation has a script session of its own, executing its intents, see [SessionScriptExecutor],
 * so that their declarations stay visible across the steps, until the cogitation rests,
 * when the session is closed, together with the ones of all the cogitations it invoked,
 * the coroutines launched in them being cancelled.
 * Likewise, each cogitation has a Python interpreter of its own, see [Agency.python], started when first used.
 *
 * Recording is done here and not by the harness: every inference of the cognizer provided to it is recorded,
 * and so is the cogitation each step of the harness unfolds, preceded by the inferences
 * the harness discarded, not enunciating them, e.g. to infer again, see [CogitationRecorder.record].
 * What the host does is logged, without the content of the phenomena, which is recorded instead.
 *
 * @param cognizer unfolding every cogitation, the root one and all the invoked ones.
 * @param recordingDir where cogitations are recorded by a [CogitationRecorder], `null` for no recording.
 * @param tokenBudget the tokens all the inferences of a tree of cogitations may spend, see [Inference.total],
 * `null` for no limit. Once spent, every cogitation of the tree is impeded at its next step instead of inferring again,
 * the inferences still in progress being the only ones exceeding it.
 * @param timeLimit how long a tree of cogitations may unfold, from when [cognize] is called, `null` for no limit.
 * Once passed, every cogitation of the tree is impeded at its next step, like once the [tokenBudget] is spent.
 * What is left of both is told to the cogitations by their [Agency.allowance].
 * @param parallelism how many steps of a tree of cogitations may execute at a time, `null` for no limit.
 * The steps beyond it wait in the queue.
 * @param introspector of the Kotlin API available to scripts, resolving declarations for every [Agency].
 * @param preparePython preparing the Python environment of the cogitations, before their first Python is started, see [PythonVenv].
 * @param http the HTTP client of every cogitation, see [Agency.http], shared by default with the other hosts of the JVM.
 */
class HarnessHost(
    private val cognizer: Cognizer,
    private val store: CogitationStore = InMemoryCogitationStore(),
    private val recordingDir: Path? = null,
    private val tokenBudget: Long? = null,
    private val timeLimit: Duration? = null,
    private val parallelism: Int? = null,
    private val introspector: KotlinIntrospector = lazyKotlinIntrospector(),
    private val preparePython: () -> Unit = PythonVenv::prepare,
    private val http: HttpClient = annieHttpClient
) {

    private val runtimes = ConcurrentHashMap<String, Runtime>()

    private val dispatched: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The in-memory counterpart of an [Unfolding]: cancelling its [job] cancels
     * the unfoldings of all its [invoked] runtimes, and theirs.
     *
     * @property rested the output of the root cogitation, once it rests.
     * @property queue of the ids of unfoldings ready for the next step, shared by the whole tree of cogitations.
     * @property spent the tokens inferred so far, shared by the whole tree of cogitations.
     * @property started when the whole tree of cogitations started unfolding.
     * @property executor of the intents, in a session of their own, see [ScriptExecutor].
     * @property inferred by the current step of the harness, see [recordingCognizer].
     */
    private class Runtime(
        val recorder: CogitationRecorder?,
        val job: Job,
        val rested: CompletableDeferred<Enunciation>,
        val queue: SendChannel<String>,
        val spent: AtomicLong,
        val started: Instant,
        handler: CoroutineExceptionHandler,
        preparePython: () -> Unit
    ) {
        val mutex = Mutex()
        val scope = CoroutineScope(job + handler)
        val invoked = CopyOnWriteArrayList<Runtime>()
        val executor = SessionScriptExecutor(preparePython)
        @Volatile
        var inferred: MutableList<Enunciation> = CopyOnWriteArrayList()

        /** Releases the session of this runtime, and of all its [invoked] runtimes, and theirs. */
        fun close() {
            executor.close()
            invoked.forEach { it.close() }
        }
    }

    /**
     * Unfolds the [cogitation] until it rests, together with all the cogitations it invokes.
     *
     * @return the output of the cogitation, enunciated by [Actant.SELF].
     */
    suspend fun cognize(
        cogitation: Cogitation
    ): Enunciation = coroutineScope {
        val root = Unfolding(id = randomId(), cogitation = cogitation)
        logger.info { "[${root.id}] Cognizing root cogitation of ${cogitation.enunciations.size} enunciation(s)" }
        val rested = CompletableDeferred<Enunciation>()
        val job = SupervisorJob(coroutineContext[Job])
        val queue = Channel<String>(Channel.UNLIMITED)
        val runtime = Runtime(
            recorder = recordingDir?.let(::CogitationRecorder),
            job = job,
            rested = rested,
            queue = queue,
            spent = AtomicLong(),
            started = Clock.System.now(),
            handler = CoroutineExceptionHandler { _, e -> rested.completeExceptionally(e) },
            preparePython = preparePython
        )
        runtimes[root.id] = runtime
        store.save(root)
        queue.send(root.id)
        val permits = parallelism?.let(::Semaphore)
        launch {
            for (id in queue) {
                permits?.acquire()
                runtimes.getValue(id).scope.launch { step(id) }.also { job ->
                    // released also when cancelled before it started
                    if (permits != null) job.invokeOnCompletion { permits.release() }
                }
            }
        }
        try {
            rested.await()
        } finally {
            queue.close()
            job.cancel()
            runtime.close()
        }
    }

    /** Unfolds the unfolding [id] by a single step of the harness, see [unfold]. */
    private suspend fun step(id: String) {
        val runtime = runtimes.getValue(id)
        runtime.mutex.withLock {
            var unfolding = checkNotNull(store.load(id)) { "No unfolding $id" }
            if (timeLimit != null && Clock.System.now() >= runtime.started + timeLimit) {
                impede(unfolding, runtime, "Time limit of $timeLimit passed")
                return
            }
            val spent = runtime.spent.get()
            if (tokenBudget != null && spent >= tokenBudget) {
                impede(unfolding, runtime, "Token budget of $tokenBudget spent: " +
                        "$spent token(s) inferred by this cogitation and the ones invoked in its tree")
                return
            }
            val cogitation = unfolding.cogitation
            logger.info { "[$id] Unfolding ${cogitation.enunciations.size} enunciation(s)" }
            val started = Clock.System.now()
            val agency = HostedAgency(
                unfolding = unfolding,
                parentCogitation = unfolding.parent?.let { store.load(it)?.cogitation },
                executor = runtime.executor,
                runtime = runtime
            )
            val inferred = CopyOnWriteArrayList<Enunciation>()
            runtime.inferred = inferred
            val failure = try {
                unfolding = unfolding.copy(cogitation = unfold(recordingCognizer(id, runtime), runtime.executor, agency))
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // e.g. an inference failing: the cogitation rests, telling why, rather than failing its whole tree
                "Harness failed to unfold the cogitation:\n${e.described()}"
            } finally {
                agency.seal()
            }
            logger.debug { "[$id] Unfolded in ${Clock.System.now() - started}" }
            val unfolded = unfolding.cogitation.enunciations.drop(cogitation.enunciations.size)
            val discarded = inferred.filter { inference ->
                unfolded.none { it.phenomena == inference.phenomena && it.inference == inference.inference }
            }
            if (discarded.isNotEmpty()) {
                logger.info { "[$id] Harness discarded ${discarded.size} inference(s)" }
            }
            if (failure != null) {
                impede(unfolding, runtime, failure, discarded)
            } else {
                settle(unfolding, runtime, discarded)
            }
        }
    }

    /**
     * Rests the cogitation of the [unfolding] on behalf of its failing step, enunciating the impediment,
     * which follows the output of the cogitation, recording the [discarded] inferences before it.
     */
    private suspend fun impede(
        unfolding: Unfolding,
        runtime: Runtime,
        reason: String,
        discarded: List<Enunciation> = emptyList()
    ) {
        logger.warn { "[${unfolding.id}] Resting cogitation with impediment: $reason" }
        val impediment = Enunciation(actant = host, phenomena = listOf(Phenomenon.Impediment(reason)))
        val impeded = unfolding.copy(cogitation = unfolding.cogitation + impediment)
        store.save(impeded)
        runtime.recorder?.record(impeded.cogitation, discarded)
        rested(impeded, runtime, extra = impediment)
    }

    /**
     * Stores and records the [unfolding], preceded by the [discarded] inferences, then either dispatches
     * the invocations it awaits, responds to its invoker once it rests, or queues it for the next step.
     */
    private suspend fun settle(unfolding: Unfolding, runtime: Runtime, discarded: List<Enunciation>) {
        store.save(unfolding)
        runtime.recorder?.record(unfolding.cogitation, discarded)
        val cogitation = unfolding.cogitation
        when {
            cogitation.unansweredInvocations.isNotEmpty() -> dispatch(unfolding, runtime)
            cogitation.enunciations.lastOrNull()?.actant == Actant.SELF -> rested(unfolding, runtime, extra = null)
            else -> {
                logger.info { "[${unfolding.id}] Unfolded to ${cogitation.enunciations.size} enunciation(s), queued for the next step" }
                runtime.queue.send(unfolding.id)
            }
        }
    }

    private fun dispatch(unfolding: Unfolding, runtime: Runtime) {
        val awaited = unfolding.cogitation.unansweredInvocations
        logger.info { "[${unfolding.id}] Awaiting ${awaited.size} invocation(s): ${awaited.joinToString { it.id }}" }
        awaited
            .filter { dispatched.add(it.id) }
            .forEach { invocation ->
                val invoked = Runtime(
                    recorder = runtime.recorder?.invoked(invocation),
                    job = SupervisorJob(runtime.job),
                    rested = runtime.rested,
                    queue = runtime.queue,
                    spent = runtime.spent,
                    started = runtime.started,
                    handler = CoroutineExceptionHandler { _, e -> runtime.rested.completeExceptionally(e) },
                    preparePython = preparePython
                )
                runtime.invoked += invoked
                logger.info { "[${unfolding.id}] Dispatching invocation ${invocation.id}" }
                invoked.scope.launch {
                    val child = store.load(invocation.id) ?: Unfolding(
                        id = invocation.id,
                        cogitation = Cogitation(
                            constitution = listOf(invocation.constitution),
                            enunciations = listOf(Enunciation(
                                actant = Actant.INVOKER,
                                phenomena = listOf(Phenomenon.Text(invocation.prompt))
                            )),
                            depth = unfolding.cogitation.depth + 1
                        ),
                        parent = unfolding.id,
                        invocation = invocation
                    ).also { store.save(it) }
                    runtimes[child.id] = invoked
                    invoked.queue.send(child.id)
                }
            }
    }

    /**
     * Delivers the output of the rested [unfolding] to its invoker, see [Cogitation.output],
     * followed by the phenomena of the [extra] enunciation, if any.
     */
    private suspend fun rested(unfolding: Unfolding, runtime: Runtime, extra: Enunciation?) {
        val enunciations = unfolding.cogitation.enunciations.filter { it.isOutput } + listOfNotNull(extra)
        val output = enunciations.flatMap { it.phenomena }.filter { it !is Phenomenon.Monologue }
        logger.info {
            "[${unfolding.id}] Rested after ${Clock.System.now() - unfolding.initiated}, " +
                    "${unfolding.cogitation.enunciations.size} enunciation(s), output: ${output.kinds()}"
        }
        runtime.invoked.forEach { it.job.cancel() }
        runtime.close()
        val parent = unfolding.parent
        val invocation = unfolding.invocation
        val now = Clock.System.now()
        if (parent == null || invocation == null) {
            runtime.rested.complete(Enunciation(
                actant = Actant.SELF,
                initiated = enunciations.firstOrNull()?.initiated ?: now,
                finalized = now,
                phenomena = output
            ))
            return
        }
        respond(parent, Enunciation(
            actant = Actant.SELF,
            initiated = unfolding.initiated,
            finalized = now,
            respondsTo = invocation.id,
            phenomena = output
        ))
    }

    /** Appends the [response] to the cogitation of the unfolding [id], queued once it awaits no more invocations. */
    private suspend fun respond(id: String, response: Enunciation) {
        val runtime = runtimes.getValue(id)
        runtime.mutex.withLock {
            var unfolding = checkNotNull(store.load(id)) { "No unfolding $id" }
            // delivered again, e.g. when retried
            if (unfolding.cogitation.enunciations.any { it.id == response.id }) {
                logger.info { "[$id] Response to invocation ${response.respondsTo} already delivered" }
                return
            }
            unfolding = unfolding.copy(cogitation = unfolding.cogitation + response)
            store.save(unfolding)
            val awaited = unfolding.cogitation.unansweredInvocations
            logger.info { "[$id] Response to invocation ${response.respondsTo} delivered, ${awaited.size} still awaited" }
            if (awaited.isEmpty()) {
                runtime.queue.send(id)
            }
        }
    }

    /**
     * The [cognizer] recording and logging each inference of the unfolding [id],
     * collecting them as the enunciations [Runtime.inferred] by the current step of the harness,
     * to tell the ones the harness discards.
     */
    private fun recordingCognizer(
        id: String,
        runtime: Runtime
    ) = Cognizer { cogitation ->
        runtime.recorder?.record(cogitation)
        logger.info { "[$id] Inferring from ${cogitation.enunciations.size} enunciation(s)" }
        val initiated = Clock.System.now()
        try {
            cognizer.cognize(cogitation)
        } catch (e: Exception) {
            logger.warn(e) { "[$id] Inference failed after ${Clock.System.now() - initiated}" }
            throw e
        }.also { (phenomena, inference) ->
            inference?.let { runtime.spent.addAndGet(it.total) }
            runtime.inferred += Enunciation(
                actant = Actant.SELF,
                initiated = initiated,
                finalized = Clock.System.now(),
                phenomena = phenomena,
                inference = inference
            )
            logger.info {
                "[$id] Inferred in ${Clock.System.now() - initiated}: ${phenomena.kinds()}" +
                        (inference?.let { ", by ${it.model} of ${it.provider}: ${it.tokensDescription()}${it.stopDescription()}" } ?: "")
            }
        }
    }

    /** [Agency] of a single step of the harness unfolding the [unfolding], see [seal]. */
    private inner class HostedAgency(
        private val unfolding: Unfolding,
        override val parentCogitation: Cogitation?,
        private val executor: SessionScriptExecutor,
        private val runtime: Runtime
    ) : Agency {

        private val lock = Any()

        @Volatile
        private var sealed = false

        override val cogitation: Cogitation = unfolding.cogitation

        override val constitution: String = unfolding.cogitation.constitution.joinToString(separator = "\n\n")

        override val depth: Int = unfolding.cogitation.depth

        override val invocations = CopyOnWriteArrayList<Phenomenon.Invocation>()

        override val allowance: Allowance get() = Allowance(
            tokens = tokenBudget, spentTokens = runtime.spent.get(), time = timeLimit, started = runtime.started
        )

        override val enunciated = CopyOnWriteArrayList<Phenomenon>()

        /** Seals this agency once the step of the harness is over. */
        fun seal(): Unit = synchronized(lock) {
            sealed = true
        }

        private fun checkNotSealed() {
            check(!sealed) { "The step of the harness is over, its agency cannot be used anymore" }
        }

        override fun invoke(
            prompt: String,
            constitution: String
        ): Phenomenon.Invocation {
            checkNotSealed()
            return Phenomenon.Invocation(
                id = randomId(),
                prompt = prompt,
                constitution = constitution
            ).also {
                logger.info { "[${unfolding.id}] Intent invoked ${it.id}" }
                invocations += it
            }
        }

        override fun enunciate(text: String, contentType: String) {
            enunciate(Phenomenon.Text(text, contentType))
        }

        override fun enunciate(bytes: ByteArray, contentType: String?) {
            enunciate(Phenomenon.Data(bytes, contentType))
        }

        override fun impede(reason: String) {
            enunciate(Phenomenon.Impediment(reason))
        }



        override fun resolveDeclaration(name: String, page: Int, pageSize: Int) =
            introspector.resolve(name, page, pageSize)

        override fun searchDeclarations(query: String, page: Int, pageSize: Int) =
            introspector.search(query, page, pageSize)

        override fun execute(
            command: List<String>,
            input: String?,
            directory: String?,
            environment: Map<String, String>,
            timeout: Duration,
            maxOutputLength: Int
        ): Execution {
            checkNotSealed()
            logger.info { "[${unfolding.id}] Executing process ${command.first()}" }
            return executeProcess(command, input, directory, environment, timeout, maxOutputLength)
        }

        override val python: Python get() = executor.python

        override val http: HttpClient get() = this@HarnessHost.http

        private fun enunciate(phenomenon: Phenomenon) = synchronized(lock) {
            checkNotSealed()
            logger.info {
                "[${unfolding.id}] Enunciated " + when (phenomenon) {
                    is Text -> "text of ${phenomenon.text.length} chars (${phenomenon.contentType})"
                    is Data -> "data of ${phenomenon.bytes.size} bytes (${phenomenon.contentType})"
                    else -> phenomenon.kind
                }
            }
            enunciated += phenomenon
        }

    }

}
/** This exception and its causes, each by its class and message, e.g. of an inference failing, as the stack tells nothing to a cognizer. */
private fun Throwable.described(): String =
    generateSequence(this) { it.cause }.take(5).joinToString("\nCaused by: ") { it.toString() }

/** The kinds of these phenomena, e.g. `[monologue, intent]`. */
private fun List<Phenomenon>.kinds(): String = joinToString(prefix = "[", postfix = "]") { it.kind }
