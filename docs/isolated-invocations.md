# Isolated invocations

A sketch, not a decision:
how a cogitation could invoke another one in an environment of its own,
so that honest-annie can run its own evals,
and optimize its constitution per model.

## Why

The same constitution does not fit every model.
On OOLONG-Pairs `pairs-01` at 128k, the minimal constitution of 57 lines scored:

| Model | F1 | How |
|---|---|---|
| Fable 5.2 | 1.000 / 1.000 | chose its own strategy, reviewed the decisive items itself |
| Sonnet 5.5 | 0.982 / 0.986 | keyword rules, then judged the undecided questions in its own context |
| deepseek-v4_1-flash | 0 / 0 | read the questions into its own context, spent 6M tokens without answering |

With the delegation rules, deepseek reached 0.98, while Fable did slightly worse than without them.
Finding the constitution a model needs took a day of runs, read and compared by hand.
That loop is what a recursive self-improving harness should run itself:
invoke a candidate constitution on a task, score the outcome, read why it failed, revise.

To do that honestly, the invoking cogitation must hold what the invoked one must never reach,
e.g. the gold answers of an eval,
while seeing everything the invoked one does.
Today neither is possible from within a cogitation.

## Today

- A single JVM does everything:
  `HarnessHost` unfolds the cogitations, infers, records,
  and executes both the harness scripts and the intents in-process,
  with a `SessionScriptExecutor` for each cogitation.
- `Agency` is already the only door of a script to the host.
- An invoked cogitation shares the file system, the network and the process of its invoker.
- The invoker perceives only the output of the invoked cogitation.
  Its steps are recorded, but no script can read them.
- A tree of cogitations has one cognizer, and one token budget.
- Isolation is done outside of honest-annie, by the eval runner:
  `sandbox-exec`, a proxy holding the key of the model, withheld directories, and a copy of the context.

## The split

A host which holds what is trusted, and an executor which runs the code:

```
 host                                        executor, e.g. a container
 ───────────────────────────────             ─────────────────────────────────────
 cognizers and their keys                    Kotlin sessions: the harness and the intents
 the store and the recorder       agency     Python, processes, files
 budgets, parallelism, timeouts  ◄──RPC───►  RemoteAgency, forwarding invoke, enunciate,
 environments, created and destroyed         impede, infer, and the reads of cogitations
```

- The harness script moves to the executor too.
  An invoker can pass a modified harness, which is the point of self-improvement,
  so a harness is as untrusted as an intent.
- Inference becomes a call to the host, so that no key ever enters an executor.
  This replaces the proxy of the eval runner.
- The transport is an interface.
  Locally it can be the standard input and output of the executor,
  so that a container needs no network at all, `agency` being its only way out.
  A remote executor needs a channel over the network, e.g. a WebSocket over TLS with a token of its environment.
- What is local stays local: `execute`, `python` and `resolveDeclaration` act within the executor.
- `agency.http` is subject to the network of the environment, none by default when isolated.

## The API

```kotlin
fun invoke(
    prompt: String,
    constitution: String = this.constitution,
    harness: String = this.harness,
    environment: Environment = Environment.Inherited,
    cognizer: String? = null,
    budget: Budget? = null,
): Phenomenon.Invocation

sealed interface Environment {
    /** The environment of the invoker: its files, its network, as today. */
    object Inherited : Environment
    /** An environment of its own, holding only the files copied into it. */
    data class Isolated(
        val image: String = "honest-annie-executor",
        val files: Map<String, String> = emptyMap(),
        val network: Network = Network.None,
        val resources: Resources = Resources(),
    ) : Environment
}

/** What an environment needs, whichever provider runs it. */
data class Resources(val cpus: Int? = null, val memory: Long? = null, val gpus: Int = 0)

/** The tokens and the time the tree of an invoked cogitation may spend. */
data class Budget(val tokens: Long? = null, val time: Duration? = null)

/** The cogitation an invocation unfolds, as far as it got: its steps, intents, results and tokens. */
fun cogitationOf(invocation: Phenomenon.Invocation): Cogitation?
```

- Visibility goes one way.
  The invoker reads the steps, the results and the tokens of what it invoked.
  The invoked cogitation perceives its prompt and the files copied in, and nothing of its invoker.
- `cognizer` and `budget` are what an optimizer needs:
  a candidate constitution is run on the model it is meant for, within a limit of cost.
- What an isolated cogitation invokes inherits its environment, unless it asks for another one.
  The host creates every environment, so no executor needs access to the container runtime.

## Environment providers

There is no isolation within a JVM to build on.
The Security Manager, its own sandbox, is disabled for good since JDK 24.
Class loaders separate code, not privileges.
The sandboxing of GraalVM covers guest languages, not the JVM bytecode Kotlin scripts compile to.
And the scripts leave the JVM anyway, running processes, and embedding CPython natively.

So the executor is a process of its own, and its isolation is whatever wraps that process.
A cogitation describes the environment it needs, without naming who provides it,
and the configuration of the host decides where it runs:

| Provider | Isolation | Good for |
|---|---|---|
| a process of the host | none | development, tests |
| `sandbox-exec` on macOS, bubblewrap or Landlock on Linux | a sandbox of the operating system | fast local evals, without a daemon |
| Docker or Podman | a container, on the kernel of the host | the default, with reproducible images |
| gVisor | a kernel in user space | stronger than a container, without virtualization |
| Kata Containers, Firecracker, Cloud Hypervisor | a microVM, with a kernel of its own | untrusted code, on Linux hosts with KVM |
| libkrun, the `container` tool of Apple | a microVM | the same, on macOS |
| Fly.io Machines, Kubernetes, the VMs of a cloud | a remote microVM or VM | scaling out, GPUs where the provider offers them |

Three choices keep all of them open:

1. The executor is built as an OCI image, which every provider above but the first two starts from.
2. The transport is an interface, see above.
3. The environment is described declaratively: its image, resources, network, files and lifetime.

A JVM runs in any of them, as they all run Linux.
What is specific to it is the time it takes to start: the JVM and the Kotlin compiler need seconds to respond,
far more than a microVM needs to boot. When it shows in measurements, the remedies are:

- a snapshot of a warmed microVM, restored for every environment, as Firecracker offers,
- a checkpoint of the warmed JVM, restored with CRaC, which Azul Zulu supports,
- the ahead-of-time cache of the JDK, a smaller gain needing only a training run.

A native image of GraalVM is no remedy: the Kotlin compiler loads and compiles classes at run time.

An environment with a GPU could host a model as well as code:
an optimizer could start a server of the model it evaluates, and name it as the `cognizer` of what it invokes,
so that the model under test becomes part of what the harness controls.

## An eval run by a cogitation

What `evals/oolong/oolong_eval.py` does, as an optimizer cogitation would do it:

1. It holds the tasks and their gold answers in its own environment.
2. For each candidate constitution, task and repeat, it invokes an isolated cogitation,
   with the cognizer the constitution is meant for,
   only the context copied in, and no network.
3. It scores each output by a script, against the gold answers the invoked cogitation could not reach.
4. It reads the steps of the invoked cogitation to tell why it failed,
   e.g. no delegation, a growing context, outcomes shifted against their inputs, or reaching for the labels.
5. It revises the constitution, and repeats.

The leak detection of the eval runner becomes a reading of the intents of the invoked cogitation,
and its counting of tokens a reading of its inferences.

## Modules

| Module | Holds | Depends on |
|---|---|---|
| `api` | `Api.kt` and `Cognition.kt`, what scripts compile against, serializable | nothing |
| `executor` | the sessions of scripts, Python, processes, `RemoteAgency`, a `main` speaking the protocol | `api` |
| `executor-image` | the OCI image of the executor | `executor` |
| `host` | `HarnessHost`, the cognizers, the recorder, the environments, the server of the protocol | `api` |
| `app` | `Main` | `host` |

Without a container runtime, the executor runs as a process of the host, speaking the same protocol,
so that there is a single code path.

## The protocol

Two directions over one channel:

- host to executor: execute a script in a session, close a session,
- executor to host, while a script executes: the functions of `agency` which are not local,
  i.e. `invoke`, `enunciate`, `impede`, the reads of `cogitation`, `constitution` and `harness`, `cogitationOf`,
  and the inference of the harness.

The phenomena cross the channel serialized, the bytes of `Phenomenon.Data` included.
A cogitation can be large, so its enunciations should be read in pages, not at once.

## Order

1. The API alone, without isolation: `cogitationOf`, and `cognizer` and `budget` of `invoke`.
   This already lets an optimizer run under the sandbox of the eval runner.
2. The modules, with the executor as a process of the host.
   This proves the protocol without a container runtime.
3. The `executor-image` module, and `Environment.Isolated`, with Docker as its first provider.
4. The optimizer, as a harness and a constitution of its own.

## Open decisions

1. Does the harness script run in the executor?
   Proposed: yes, as a modified harness is untrusted code.
   The price is that every inference crosses the channel.
2. An executor for every cogitation, or for every isolated tree?
   Proposed: for every tree, as starting a JVM and the Kotlin compiler takes seconds,
   and a remote environment takes longer still.
   For every cogitation is stricter, as cogitations of a tree no longer share files.
3. How do files get in, and out?
   Proposed: copied over the protocol, which works with every provider, as mounts do not exist across machines.
   An invoker needing a file the invoked cogitation wrote asks for it through the protocol as well.
4. What of a session survives a restart of the host?
   Today a cogitation can be resumed from the store, while its session cannot:
   the lifetime of an executor would be the lifetime of its cogitation.
5. How is an invoked cogitation cancelled, and what does its invoker perceive then?
   A budget of time needs it, and so does an optimizer giving up on a candidate.
