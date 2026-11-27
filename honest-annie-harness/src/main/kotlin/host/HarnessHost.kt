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
import com.xemantic.annie.Contents
import com.xemantic.annie.InMemoryContents
import com.xemantic.annie.Enunciation
import com.xemantic.annie.Execution
import com.xemantic.annie.ExecutedIntent
import com.xemantic.annie.Invocation
import com.xemantic.annie.Agency
import com.xemantic.annie.harness.Allowance
import com.xemantic.annie.Inference
import com.xemantic.annie.Phenomenon
import com.xemantic.annie.ScriptExecutor
import com.xemantic.annie.computer.ComputerProvider
import com.xemantic.annie.Python
import com.xemantic.annie.harness.cognizer.Cognizer
import com.xemantic.annie.randomId
import com.xemantic.annie.readBytes
import com.xemantic.annie.readText
import com.xemantic.annie.text
import com.xemantic.annie.harness.unfold
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

/** The actant of the prompt of an independent cogitation, which perceives no invoker, see [Agency.invoke]. */
private val unidentified = Actant(type = Actant.Type.UNIDENTIFIED, name = "unidentified")

/** The placeholder of a constitution the host replaces with the computer types it offers, see [HarnessHost]. */
const val COMPUTERS_PLACEHOLDER: String = "{{computers}}"

/**
 * Hosts the harness unfolding cogitations, see [Cogitation] and [unfold].
 *
 * Unfoldings ready for the next step are queued, and pulled from the queue to be unfolded by the harness.
 * After each step a cogitation is queued again, unless it awaits the cogitations it invoked, or rests:
 * [Actant.SELF] enunciated last.
 *
 * The cogitations an intent invokes are not unfolded by the intent: they are kept by its executor, see [ExecutedIntent.invoked],
 * recorded in the unfolding of the invoking cogitation, see [Unfolding.invoked], and only then dispatched by the host,
 * each one unfolding a cogitation of its own from its prompt, whose output is delivered back as a response to it once it rests.
 * Once all of them are answered, their responses are appended to the invoking cogitation, in the order of their invocations,
 * and it is queued again. The state of every unfolding is kept
 * in the [store] at each step.
 *
 * The intents of a cogitation execute on its computer, in its REPL, see [Cogitation.computer] and [Computers],
 * shared with the cogitations it invokes, unless they start a computer of one of the types of the [computers], or name another REPL,
 * so that their declarations stay visible across the steps, and to the invoked cogitations,
 * each computer being started by the [computers] when an intent first executes on it,
 * and stopped once the whole tree of cogitations rests, its REPLs closed, the coroutines launched in them being cancelled.
 * What an intent does on a computer, e.g. executing a process, or its Python, see [Agency.python],
 * is done by its executor, not by the host, which only acts within the cogitations, see [HostedAgency].
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
 * What is left of both is told to the harness at each step, see [Allowance].
 * @param parallelism how many steps of a tree of cogitations may execute at a time, `null` for no limit.
 * The steps beyond it wait in the queue.
 * @param computers providing the computers of the cogitations, see [ComputerProvider]: the one of the root cogitation,
 * and the ones a cogitation may start for the ones it invokes, see [Agency.invoke], of its types,
 * told to the cognizer in the constitution, by its `{{computers}}`.
 * @property contents keeping the content of the texts and data of every cogitation, see [Contents]:
 * the ones of a cogitation given to [cognize], and the ones the [cognizer] expresses, are kept by it too.
 */
class HarnessHost(
    private val cognizer: Cognizer,
    private val store: CogitationStore = InMemoryCogitationStore(),
    private val recordingDir: Path? = null,
    private val tokenBudget: Long? = null,
    private val timeLimit: Duration? = null,
    private val parallelism: Int? = null,
    private val computers: ComputerProvider,
    val contents: Contents = InMemoryContents()
) {

    private val computerTypes: List<String> get() = computers.types

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
     * @property computers the computers of the whole tree of cogitations, and their REPLs, executing the intents.
     * @property inferred by the current step of the harness, see [recordingCognizer].
     * @property intents the ids of the intents which made the invocations of its cogitation, by the id of the invocation.
     */
    private class Runtime(
        val recorder: CogitationRecorder?,
        val job: Job,
        val rested: CompletableDeferred<Enunciation>,
        val queue: SendChannel<String>,
        val spent: AtomicLong,
        val started: Instant,
        handler: CoroutineExceptionHandler,
        val computers: Computers
    ) {
        val mutex = Mutex()
        val scope = CoroutineScope(job + handler)
        val invoked = CopyOnWriteArrayList<Runtime>()
        val intents = ConcurrentHashMap<String, String>()
        @Volatile
        var inferred: MutableList<Enunciation> = CopyOnWriteArrayList()
    }

    /**
     * Unfolds the [cogitation] until it rests, together with all the cogitations it invokes.
     *
     * @return the output of the cogitation, enunciated by [Actant.SELF].
     */
    suspend fun cognize(
        cogitation: Cogitation
    ): Enunciation = coroutineScope {
        val told = cogitation.copy(constitution = cogitation.constitution.replace(
            COMPUTERS_PLACEHOLDER, computerTypes.joinToString { "`$it`" }
        ))
        val root = Unfolding(id = randomId(), cogitation = told)
        logger.info { "[${root.id}] Cognizing root cogitation of ${cogitation.enunciations.size} enunciation(s)" }
        val rested = CompletableDeferred<Enunciation>()
        val job = SupervisorJob(coroutineContext[Job])
        val queue = Channel<String>(Channel.UNLIMITED)
        val runtime = Runtime(
            recorder = recordingDir?.let { CogitationRecorder(it, contents) },
            job = job,
            rested = rested,
            queue = queue,
            spent = AtomicLong(),
            started = Clock.System.now(),
            handler = CoroutineExceptionHandler { _, e -> rested.completeExceptionally(e) },
            computers = Computers(computers, contents).apply { start(told.computer, told.repl) }
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
            runtime.computers.close()
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
                parent = unfolding.parent?.takeUnless { unfolding.invocation?.independent == true },
                runtime = runtime
            )
            val inferred = CopyOnWriteArrayList<Enunciation>()
            runtime.inferred = inferred
            val invoked = CopyOnWriteArrayList<Invocation>()
            // the computers of the tree, keeping what each intent invoked, to be dispatched once the step is settled
            val executor = object : ScriptExecutor by runtime.computers {
                override suspend fun execute(intent: Phenomenon.Intent, agency: Agency): ExecutedIntent =
                    runtime.computers.execute(intent, agency).also { executed ->
                        executed.invoked.forEach { runtime.intents[it.id] = intent.id }
                        invoked += executed.invoked
                    }
            }
            val failure = try {
                unfolding = unfolding.copy(
                    cogitation = unfold(recordingCognizer(id, runtime), executor, agency, contents) {
                        Allowance(tokens = tokenBudget, spentTokens = runtime.spent.get(), time = timeLimit, started = runtime.started)
                    },
                    invoked = unfolding.invoked + invoked
                )
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // e.g. an inference failing: the cogitation rests, telling why, rather than failing its whole tree
                "Harness failed to unfold the cogitation:\n${e.described()}"
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
            unfolding.awaited.isNotEmpty() -> dispatch(unfolding, runtime)
            cogitation.enunciations.lastOrNull()?.actant == Actant.SELF -> rested(unfolding, runtime, extra = null)
            else -> {
                logger.info { "[${unfolding.id}] Unfolded to ${cogitation.enunciations.size} enunciation(s), queued for the next step" }
                runtime.queue.send(unfolding.id)
            }
        }
    }

    private fun dispatch(unfolding: Unfolding, runtime: Runtime) {
        val awaited = unfolding.awaited
        logger.info { "[${unfolding.id}] Awaiting ${awaited.size} invocation(s): ${awaited.joinToString { it.id }}" }
        awaited
            .filter { dispatched.add(it.id) }
            .forEach { invocation ->
                val invoked = Runtime(
                    recorder = runtime.recorder?.invoked(invocation.id, runtime.intents.getValue(invocation.id)),
                    job = SupervisorJob(runtime.job),
                    rested = runtime.rested,
                    queue = runtime.queue,
                    spent = runtime.spent,
                    started = runtime.started,
                    handler = CoroutineExceptionHandler { _, e -> runtime.rested.completeExceptionally(e) },
                    computers = runtime.computers
                )
                runtime.invoked += invoked
                fun notInvoked(reason: String) {
                    logger.warn { "[${unfolding.id}] Invocation ${invocation.id} not dispatched: $reason" }
                    invoked.scope.launch {
                        respond(unfolding.id, Enunciation(
                            actant = host,
                            respondsTo = invocation.id,
                            phenomena = listOf(Phenomenon.Impediment("$reason, so that the cogitation was not invoked."))
                        ))
                    }
                }
                val computer = invocation.computer
                if (computer != null && computer.type !in computerTypes) {
                    notInvoked("No computer of the type ${computer.type} can be started, but of ${computerTypes.joinToString()}")
                    return@forEach
                }
                val repl = invocation.repl
                if (computer != null && !runtime.computers.start(computer, checkNotNull(repl))) {
                    notInvoked("The REPL $repl is already open on another computer than the one started for the cogitation")
                    return@forEach
                }
                logger.info { "[${unfolding.id}] Dispatching invocation ${invocation.id}" }
                invoked.scope.launch {
                    val child = store.load(invocation.id) ?: Unfolding(
                        id = invocation.id,
                        cogitation = Cogitation(
                            constitution = invocation.constitution ?: unfolding.cogitation.constitution,
                            // an independent cogitation perceives itself as a root, see Agency.invoke
                            enunciations = listOf(Enunciation(
                                actant = if (invocation.independent) unidentified else Actant.INVOKER,
                                id = invocation.id,
                                phenomena = listOf(contents.text(invocation.prompt))
                            )),
                            depth = if (invocation.independent) 0 else unfolding.cogitation.depth + 1,
                            // on the computer of the REPL it names, when a cogitation of the tree has it
                            computer = computer ?: repl?.let(runtime.computers::hosting) ?: unfolding.cogitation.computer,
                            repl = repl ?: unfolding.cogitation.repl
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

    /**
     * Keeps the [response] to an invocation of the unfolding [id] among its [Unfolding.outcomes],
     * until it awaits no more invocations: then all of them are appended to its cogitation, in the order of their invocations,
     * and it is queued for the next step.
     */
    private suspend fun respond(id: String, response: Enunciation) {
        val runtime = runtimes.getValue(id)
        runtime.mutex.withLock {
            var unfolding = checkNotNull(store.load(id)) { "No unfolding $id" }
            // delivered again, e.g. when retried
            if ((unfolding.cogitation.enunciations + unfolding.outcomes).any { it.id == response.id }) {
                logger.info { "[$id] Response to invocation ${response.respondsTo} already delivered" }
                return
            }
            unfolding = unfolding.copy(outcomes = unfolding.outcomes + response)
            val awaited = unfolding.awaited
            logger.info { "[$id] Response to invocation ${response.respondsTo} delivered, ${awaited.size} still awaited" }
            if (awaited.isEmpty()) {
                val order = unfolding.invoked.map { it.id }
                unfolding = unfolding.copy(
                    cogitation = unfolding.outcomes.sortedBy { order.indexOf(it.respondsTo) }.fold(unfolding.cogitation, Cogitation::plus),
                    outcomes = emptyList()
                )
            }
            store.save(unfolding)
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

    /**
     * [Agency] of a single step of the harness unfolding the [unfolding],
     * acting only within the cogitations: telling them, the [parent] one loaded only when asked for.
     * What an intent does on a computer, e.g. executing a process, and what it invokes and enunciates, the executor of the intent does,
     * through the agency it provides the intent with, see [ScriptExecutor].
     */
    private inner class HostedAgency(
        private val unfolding: Unfolding,
        private val parent: String?,
        private val runtime: Runtime
    ) : Agency {

        override suspend fun cogitation(): Cogitation = unfolding.cogitation

        override suspend fun parentCogitation(): Cogitation? = parent?.let { store.load(it)?.cogitation }

        override suspend fun invoke(
            prompt: String,
            constitution: String?,
            computer: String?,
            repl: String?,
            independent: Boolean
        ): Unit = byExecutor()

        override suspend fun enunciate(text: String, contentType: String): Unit = byExecutor()

        override suspend fun enunciate(bytes: ByteArray, contentType: String?): Unit = byExecutor()

        override suspend fun impede(reason: String): Unit = byExecutor()

        override suspend fun resolveDeclaration(name: String, page: Int, pageSize: Int): String? = onComputer()

        override suspend fun searchDeclarations(query: String, page: Int, pageSize: Int): String? = onComputer()

        override suspend fun execute(
            command: List<String>,
            input: String?,
            directory: String?,
            environment: Map<String, String>,
            timeout: Duration
        ): Execution = onComputer()

        override suspend fun read(path: String, vararg lines: IntRange): String = onComputer()

        override suspend fun readText(phenomenon: Phenomenon, vararg lines: IntRange): String =
            contents.readText(phenomenon, lines.toList())

        override suspend fun readBytes(phenomenon: Phenomenon): ByteArray = contents.readBytes(phenomenon)

        override val python: Python get() = onComputer()

        override val http: HttpClient get() = onComputer()

        private fun onComputer(): Nothing = throw UnsupportedOperationException(
            "The host acts only within the cogitations: what an intent does on a computer, its executor does"
        )

        private fun byExecutor(): Nothing = throw UnsupportedOperationException(
            "What an intent invokes and enunciates, its executor keeps, see ExecutedIntent"
        )

    }

}
/** This exception and its causes, each by its class and message, e.g. of an inference failing, as the stack tells nothing to a cognizer. */
private fun Throwable.described(): String =
    generateSequence(this) { it.cause }.take(5).joinToString("\nCaused by: ") { it.toString() }

/** The kinds of these phenomena, e.g. `[monologue, intent]`. */
private fun List<Phenomenon>.kinds(): String = joinToString(prefix = "[", postfix = "]") { it.kind }
