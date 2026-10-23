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

import com.xemantic.ai.annie.Actant
import com.xemantic.ai.annie.Cogitation
import com.xemantic.ai.annie.Enunciation
import com.xemantic.ai.annie.Agency
import com.xemantic.ai.annie.Phenomenon
import com.xemantic.ai.annie.ScriptExecutor as IntentExecutor
import com.xemantic.ai.annie.cognizer.Cognizer
import com.xemantic.ai.annie.randomId
import com.xemantic.ai.annie.responseId
import com.xemantic.kotlin.script.ScriptExecutor
import com.xemantic.kotlin.script.ScriptResult
import com.xemantic.kotlin.script.execute
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The harness unfolding cogitations unless another one is given, see `harness.kts`. */
val defaultHarness: String = checkNotNull(
    HarnessHost::class.java.getResource("/harness.kts")
) { "No default harness on the classpath" }.readText()

private val logger = KotlinLogging.logger {}

private val host = Actant(type = Actant.Type.COMPUTER, name = "harness host")

/**
 * Hosts harness scripts unfolding cogitations, see [Cogitation].
 *
 * Unfoldings ready for the next step are queued, and pulled from the queue to execute their
 * harness, the [Cogitation.harness] given to the invocation, by default the one of the invoker.
 * After each step a cogitation is queued again, unless it awaits invocations, or rests:
 * [Actant.SELF] enunciated last.
 *
 * Invocations are not executed by the intents enunciating them: they are recorded in the
 * cogitation, and only then dispatched by the host, each one unfolding a cogitation of its own,
 * whose output is delivered back as a response to the invocation once it rests. Once all of them
 * are answered, the invoking cogitation is queued again. The state of every unfolding is kept
 * in the [store] at each step.
 *
 * Recording is done here and not by the harness: every inference of the cognizer provided to it is recorded,
 * and so is the cogitation each execution of the harness evaluates to.
 * What the host does is logged, without the content of the phenomena, which is recorded instead.
 *
 * @param cognizer unfolding every cogitation, the root one and all the invoked ones.
 * @param recordingDir where cogitations are recorded by a [CogitationRecorder], `null` for no recording.
 */
class HarnessHost(
    private val executor: ScriptExecutor,
    private val cognizer: Cognizer,
    private val store: CogitationStore = InMemoryCogitationStore(),
    private val recordingDir: Path? = null
) {

    private val intentExecutor = IntentExecutor(executor)

    private val runtimes = ConcurrentHashMap<String, Runtime>()

    private val dispatched: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The in-memory counterpart of an [Unfolding]: cancelling its [job] cancels
     * the unfoldings of all its [invoked] runtimes, and theirs.
     *
     * @property rested the output of the root cogitation, once it rests.
     * @property queue of the ids of unfoldings ready for the next step, shared by the whole tree of cogitations.
     */
    private class Runtime(
        val recorder: CogitationRecorder?,
        val job: Job,
        val rested: CompletableDeferred<Enunciation>,
        val queue: SendChannel<String>,
        handler: CoroutineExceptionHandler
    ) {
        val mutex = Mutex()
        val scope = CoroutineScope(job + handler)
        val invoked = CopyOnWriteArrayList<Runtime>()
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
            handler = CoroutineExceptionHandler { _, e -> rested.completeExceptionally(e) }
        )
        runtimes[root.id] = runtime
        store.save(root)
        queue.send(root.id)
        launch {
            for (id in queue) {
                runtimes.getValue(id).scope.launch { step(id) }
            }
        }
        try {
            rested.await()
        } finally {
            queue.close()
            job.cancel()
        }
    }

    /** Executes the harness of the unfolding [id] for a single step. */
    private suspend fun step(id: String) {
        val runtime = runtimes.getValue(id)
        runtime.mutex.withLock {
            var unfolding = checkNotNull(store.load(id)) { "No unfolding $id" }
            val cogitation = unfolding.cogitation
            logger.info { "[$id] Executing harness on ${cogitation.enunciations.size} enunciation(s)" }
            val started = Clock.System.now()
            val agency = HostedAgency(
                unfolding = unfolding,
                parentCogitation = unfolding.parent?.let { store.load(it)?.cogitation }
            )
            val result = executor.execute(cogitation.harness) {
                "cogitation" provides cogitation
                "cognizer" provides recordingCognizer(id, runtime)
                "executor" provides intentExecutor
                provide<Agency>("agency", agency)
            }
            unfolding = agency.seal()
            logger.debug { "[$id] Harness executed in ${Clock.System.now() - started}" }
            val failure = when (result) {
                is ScriptResult.Error -> "Harness failed to unfold the cogitation:\n${result.message}"
                is ScriptResult.Value -> when (val unfolded = result.value) {
                    is Cogitation -> if (unfolded == cogitation) {
                        "Harness did not unfold the cogitation"
                    } else {
                        unfolding = unfolding.copy(cogitation = unfolded)
                        null
                    }
                    else -> "Harness evaluated to $unfolded instead of the unfolded Cogitation"
                }
            }
            if (failure != null) {
                impede(unfolding, runtime, failure)
            } else {
                settle(unfolding, runtime)
            }
        }
    }

    /** Rests the cogitation of the [unfolding] on behalf of its failing harness, enunciating the impediment. */
    private suspend fun impede(unfolding: Unfolding, runtime: Runtime, reason: String) {
        logger.warn { "[${unfolding.id}] Resting cogitation with impediment: $reason" }
        val impediment = Phenomenon.Impediment(reason)
        val impeded = unfolding.copy(
            cogitation = unfolding.cogitation + Enunciation(actant = host, phenomena = listOf(impediment)),
            enunciated = unfolding.enunciated + impediment,
            enunciationInitiated = unfolding.enunciationInitiated ?: Clock.System.now()
        )
        store.save(impeded)
        runtime.recorder?.record(impeded.cogitation)
        rested(impeded, runtime)
    }

    /**
     * Stores and records the [unfolding], then either dispatches the invocations it awaits,
     * responds to its invoker once it rests, or queues it for the next step.
     */
    private suspend fun settle(unfolding: Unfolding, runtime: Runtime) {
        store.save(unfolding)
        runtime.recorder?.record(unfolding.cogitation)
        val cogitation = unfolding.cogitation
        when {
            cogitation.unansweredInvocations.isNotEmpty() -> dispatch(unfolding, runtime)
            cogitation.enunciations.lastOrNull()?.actant == Actant.SELF -> rested(unfolding, runtime)
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
                    handler = CoroutineExceptionHandler { _, e -> runtime.rested.completeExceptionally(e) }
                )
                runtime.invoked += invoked
                logger.info {
                    "[${unfolding.id}] Dispatching invocation ${invocation.id}" +
                            if (invocation.harness == unfolding.cogitation.harness) "" else ", with another harness"
                }
                invoked.scope.launch {
                    val child = store.load(invocation.id) ?: Unfolding(
                        id = invocation.id,
                        cogitation = Cogitation(
                            constitution = listOfNotNull(invocation.constitution),
                            harness = invocation.harness,
                            enunciations = listOf(Enunciation(
                                actant = Actant.SELF,
                                phenomena = listOf(Phenomenon.Text(invocation.prompt))
                            ))
                        ),
                        parent = unfolding.id,
                        invocation = invocation
                    ).also { store.save(it) }
                    runtimes[child.id] = invoked
                    invoked.queue.send(child.id)
                }
            }
    }

    /** Delivers the output of the rested [unfolding] to its invoker. */
    private suspend fun rested(unfolding: Unfolding, runtime: Runtime) {
        logger.info {
            "[${unfolding.id}] Rested after ${Clock.System.now() - unfolding.initiated}, " +
                    "${unfolding.cogitation.enunciations.size} enunciation(s), output: ${unfolding.enunciated.kinds()}"
        }
        runtime.invoked.forEach { it.job.cancel() }
        val parent = unfolding.parent
        val invocation = unfolding.invocation
        val now = Clock.System.now()
        if (parent == null || invocation == null) {
            runtime.rested.complete(Enunciation(
                actant = Actant.SELF,
                initiated = unfolding.enunciationInitiated ?: now,
                finalized = now,
                phenomena = unfolding.enunciated
            ))
            return
        }
        respond(parent, Enunciation(
            actant = Actant.SELF,
            initiated = unfolding.initiated,
            finalized = now,
            respondsTo = invocation.id,
            phenomena = unfolding.enunciated
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

    /** The [cognizer] recording and logging each inference of the unfolding [id]. */
    private fun recordingCognizer(id: String, runtime: Runtime) = Cognizer { cogitation ->
        runtime.recorder?.record(cogitation)
        logger.info { "[$id] Inferring from ${cogitation.enunciations.size} enunciation(s)" }
        val initiated = Clock.System.now()
        try {
            cognizer.cognize(cogitation)
        } catch (e: Exception) {
            logger.warn(e) { "[$id] Inference failed after ${Clock.System.now() - initiated}" }
            throw e
        }.also {
            logger.info { "[$id] Inferred in ${Clock.System.now() - initiated}: ${it.kinds()}" }
        }
    }

    /** [Agency] of a single execution of the harness of the [unfolding], see [seal]. */
    private inner class HostedAgency(
        @Volatile private var unfolding: Unfolding,
        override val parentCogitation: Cogitation?
    ) : Agency {

        private val lock = Mutex()

        @Volatile
        private var sealed = false

        override val harness: String = unfolding.cogitation.harness

        override val invocations = CopyOnWriteArrayList<Phenomenon.Invocation>()

        override val enunciated: List<Phenomenon> get() = unfolding.enunciated

        override val enunciationInitiated: Instant? get() = unfolding.enunciationInitiated

        /** Seals this agency once the harness has evaluated, returning the unfolding as it was left. */
        suspend fun seal(): Unfolding = lock.withLock {
            sealed = true
            unfolding
        }

        private fun checkNotSealed() {
            check(!sealed) { "The harness has already evaluated, its agency cannot be used anymore" }
        }

        override fun invoke(
            prompt: String,
            harness: String,
            constitution: String?,
        ): Phenomenon.Invocation {
            checkNotSealed()
            return Phenomenon.Invocation(
                id = randomId(),
                prompt = prompt,
                harness = harness,
                constitution = constitution,
            ).also {
                logger.info { "[${unfolding.id}] Intent invoked ${it.id}" }
                invocations += it
            }
        }

        override suspend fun enunciate(text: String, contentType: String) {
            enunciate(Phenomenon.Text(text, contentType))
        }

        override suspend fun enunciate(bytes: ByteArray, contentType: String?) {
            enunciate(Phenomenon.Data(bytes, contentType))
        }

        override suspend fun impede(reason: String) {
            enunciate(Phenomenon.Impediment(reason))
        }

        private suspend fun enunciate(phenomenon: Phenomenon) = lock.withLock {
            checkNotSealed()
            logger.info {
                "[${unfolding.id}] Enunciated " + when (phenomenon) {
                    is Text -> "text of ${phenomenon.text.length} chars (${phenomenon.contentType})"
                    is Data -> "data of ${phenomenon.bytes.size} bytes (${phenomenon.contentType})"
                    else -> phenomenon.kind
                }
            }
            unfolding = unfolding.copy(
                enunciated = unfolding.enunciated + phenomenon,
                enunciationInitiated = unfolding.enunciationInitiated ?: Clock.System.now()
            )
            store.save(unfolding)
        }

    }

}

/** The kinds of these phenomena, e.g. `[monologue, intent]`. */
private fun List<Phenomenon>.kinds(): String = joinToString(prefix = "[", postfix = "]") { it.kind }
