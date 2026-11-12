# honest-annie

A recursive self-modifying harness for scientific research

[<img alt="license" src="https://img.shields.io/github/license/xemantic/honest-annie?color=blue">](https://github.com/xemantic/honest-annie/blob/main/LICENSE)

[<img alt="GitHub Actions Workflow Status" src="https://img.shields.io/github/actions/workflow/status/xemantic/honest-annie/build-main.yml">](https://github.com/xemantic/honest-annie/actions/workflows/build-main.yml)
[<img alt="GitHub branch check runs" src="https://img.shields.io/github/check-runs/xemantic/honest-annie/main">](https://github.com/xemantic/honest-annie/actions/workflows/build-main.yml)
[<img alt="GitHub commits since latest release" src="https://img.shields.io/github/commits-since/xemantic/honest-annie/latest">](https://github.com/xemantic/honest-annie/commits/main/)
[<img alt="GitHub last commit" src="https://img.shields.io/github/last-commit/xemantic/honest-annie">](https://github.com/xemantic/honest-annie/commits/main/)

[<img alt="GitHub contributors" src="https://img.shields.io/github/contributors/xemantic/honest-annie">](https://github.com/xemantic/honest-annie/graphs/contributors)
[<img alt="GitHub commit activity" src="https://img.shields.io/github/commit-activity/t/xemantic/honest-annie">](https://github.com/xemantic/honest-annie/commits/main/)
[<img alt="GitHub code size in bytes" src="https://img.shields.io/github/languages/code-size/xemantic/honest-annie">]()
[<img alt="GitHub Created At" src="https://img.shields.io/github/created-at/xemantic/honest-annie">](https://github.com/xemantic/honest-annie/commits)
[<img alt="kotlin version" src="https://img.shields.io/badge/dynamic/toml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fxemantic%2Fhonest-annie%2Fmain%2Fgradle%2Flibs.versions.toml&query=versions.kotlin&label=kotlin">](https://kotlinlang.org/docs/releases.html)
[<img alt="discord users online" src="https://img.shields.io/discord/811561179280965673">](https://discord.gg/vQktqqN2Vn)
[![Bluesky](https://img.shields.io/badge/Bluesky-0285FF?logo=bluesky&logoColor=fff)](https://bsky.app/profile/xemantic.com)

## What makes Honest Annie different from other harnesses?

- It acts only through Kotlin Script: natural language appears in its monologue, its comments, the strings it enunciates or passes on,
  and in the text it answers with, when it calls no tool.
- It uses a single tool, executing a Kotlin script, whose API the system prompt documents, instead of a tool for each action
- The script evaluates to what Annie needs to perceive, and enunciates what its invoker needs to know
- The script is technically compiled, which ensures formal analysis before evaluation
- The script can call Annie recursively (RLM)
- The recursive results are delivered asynchronously
- This allows to control parallelism even in potentially infinite trees of branching cogitations
- The cognitive ontology models the process in place of communication theory
- A script can inspect its own cogitation, its depth, the cogitation of its invoker, and the API it compiles against
- The code of the harness is constantly present in the context window
- Annie can rewrite both its own system prompt and own harness code
- The script environment state is preserved across intents
- Annie will work with any text model (not only LLMs)
- It can perceive multimodal data

> [!NOTE] Since Annie is a research agent, it doesn't have an interactive CLI, just the input and the output, with the full trace of how the whole cognitive process is unfolding.

## Improvement hypothesis

- "awareness" of own powers and limitations (metacognition), from the code of the harness present in the context window, and from scripts inspecting the cogitation
- optimized recursive task splitting decisions
- reduced token use (already observed 3x reduction in certain task)
- reduced multitask inference (no file outputting while JSON escaping)

Therefore, it can amplify evaluation score of language models.

## AI safety research questions

1. **The elicitation gap.**
   Dangerous-capability evaluations measure what the harness elicits,
   not what the model can do.
   How much does a harness of whole-program scripts and recursion amplify the scores and cut the tokens of the same model,
   and so how much do evaluations run in weaker harnesses underestimate it?
   The [OOLONG](evals/oolong/README.md) and [LongCoT](evals/longcot/README.md) evals measure this on public benchmarks,
   against the scores reported for other harnesses, e.g. Prime Agent.
2. **Self-modification under a fixed host.**
   An agent which can rewrite its own instructions and the harness running it
   is a small, contained instance of a frontier concern.
   When a model can rewrite the harness and the constitution of its sub-cogitations,
   what does it change, does it ever weaken constraints,
   does it report the changes to its invoker,
   and do the changes amount to self-improvement?
   Each cogitation is recorded with the `harness.kts` and `constitution.md` unfolding it,
   so every modification can be observed beside the cogitation it unfolded,
   not inferred afterwards.
3. **Oversight of whole-program actions** (planned).
   Each action is a complete script available before execution,
   which makes pre-execution review by a monitor model possible.
   How reliably does such a monitor catch harmful or deceptive intents,
   compared with monitoring individual tool calls?
4. **Honesty under a single-tool design.**
   Which dishonest behaviors does a harness expressed only in code reveal, and which does it hide?
   The harness already records these signals, instrumented, not yet findings:
   - an inference expressing no intent, or calling another tool than the one executing scripts,
     is discarded and recorded as `*-discarded-*`, while whatever a model writes after its call, e.g. a made up result, is never perceived;
   - impeding versus answering: `agency.impede(reason)` is recorded as an impediment, distinct from an answer;
   - whether the final answer follows from the agent's own judgments, the **own** metric of the [OOLONG eval](evals/oolong/README.md);
   - whether an intent mentions the gold answers, the **LEAKED** flag of the [LongCoT eval](evals/longcot/README.md).

## Threat model and current limitations

The agent is treated as untrusted:
it may pursue its task by any means its scripts reach,
including impeding oversight, misreporting what it did,
or rewriting the harness to remove a constraint.
The host, the process unfolding the harness, is trusted,
and so are the cognizer endpoints.

Today, Honest Annie is a research prototype to be run only on a machine dedicated to it:
- intents run with the permissions of the host process,
  with access to its filesystem and network;
- the processes they start inherit its environment, the API keys included;
- the recording of cogitations lies within the agent's reach,
  so a recording is evidence of what happened only as long as the agent did not alter it;
- recursion has no depth limit.

What already bounds it:
`ANNIE_TOKEN_BUDGET` caps the tokens a whole tree of cogitations may spend, impeding it once spent,
`ANNIE_PARALLELISM` caps how many of them step at a time,
a process started by an intent is destroyed, together with its descendants, when its timeout elapses,
and every intent is compiled, so it is analysed before it is executed.

Planned hardening:
sandboxed execution of intents and processes,
a scrubbed process environment,
recording out of the agent's reach (append-only, or by a separate process),
and a configurable depth limit.

Open question:
how to sandbox an agent which can rewrite the harness that runs it,
when the sandbox must stay outside of what it can rewrite.

## Status

Works:
the recursive harness,
Claude and OpenAI-compatible backends,
recording of every cogitation,
and the [OOLONG](evals/oolong/README.md) and [LongCoT](evals/longcot/README.md) evals,
with reference runs for OOLONG in [evals/oolong/README.md](evals/oolong/README.md#reference-runs), none yet for LongCoT,
and a runner of [FrontierHarness Eval](evals/frontierharness/README.md), comparing coding-agent harnesses on the same model, not yet run.

Planned:
self-modification experiments,
monitor experiments,
a public dataset of recorded cogitations,
and a writeup.

## Dependencies

Honest Annie is built on open-source libraries developed by xemantic,
most of them extracted from [Golem XIV](https://github.com/xemantic/golem-xiv).

Current:
- [xemantic-kotlin-script](https://github.com/xemantic/xemantic-kotlin-script) executes the intents as Kotlin scripts in a stateful session,
  reporting compilation and runtime errors in a form a language model can act on,
  each pointing to the offending line of the script;
- [xemantic-kotlin-introspection](https://github.com/xemantic/xemantic-kotlin-introspection) renders the signatures of any Kotlin declaration on the classpath,
  so a script can look up the API it compiles against, even one absent from the training data of the model;
- [xemantic-ai-file-magic](https://github.com/xemantic/xemantic-ai-file-magic) detects the media type of the data Annie perceives.

Future:
- [xemantic-kotlin-script-python](https://github.com/xemantic/xemantic-kotlin-script-python) embeds a stateful CPython interpreter into the Kotlin script session,
  opening the Python ecosystem (numpy, pandas, scikit-learn, …) to the intents;
- [anthropic-sdk-kotlin](https://github.com/xemantic/anthropic-sdk-kotlin), a Kotlin multiplatform variant of the Anthropic SDK,
  to replace the official `anthropic-java` one;
- [xemantic-ai-money](https://github.com/xemantic/xemantic-ai-money) calculates the cost of LLM usage in real time,
  so that a tree of cogitations can be bounded by a monetary budget, not only by a token one.

## Running

```shell
./gradlew run -Pinput="What's fibonacci number 45"
```

A single cognizer unfolds every cogitation, including the ones it invokes:
Claude when `ANTHROPIC_MODEL` is set (with `ANTHROPIC_API_KEY`),
otherwise the `OPENAI_MODEL` (with `OPENAI_API_KEY`) served by the OpenAI API,
or by any OpenAI-compatible endpoint set as `OPENAI_BASE_URL`, the `/v1` suffix included.
Cogitations are recorded in `ANNIE_COGITATIONS_DIR` (`cogitations` by default),
and unfolded by the harness script at the `ANNIE_HARNESS` path,
or by the default harness extracted from [DefaultHarness.kt](src/main/kotlin/DefaultHarness.kt) when not set.
When `ANNIE_TOKEN_BUDGET` is set, the cogitation and the ones it invokes spend at most that many tokens,
and are impeded once they have spent them.
When `ANNIE_PARALLELISM` is set, at most that many of them step at a time, e.g. `1` for one inference at a time,
the others waiting in the queue.
An inference calling no tool answers with its text, which rests the cogitation.
`ANNIE_EFFORT` sets how much effort the model puts into its reasoning and response,
one of `none`, `minimal`, `low`, `medium`, `high`, `xhigh` or `max`,
mapped to the nearest level the model supports, its default when not set.
`ANNIE_MAX_TOKENS` limits the tokens of a single inference, the reasoning included, 128000 by default,
which, together with the prompt, must fit the context window of the model, e.g. `32000` for a 128K one.

The Python of the intents is embedded through [Jep](https://github.com/ninia/jep),
whose `jep` package must be installed in the Python environment, of the same version as the Java library.
Run by Gradle, honest-annie uses the `.venv` of the build, which it prepares like for the tests.
Otherwise, unless `VIRTUAL_ENV` points to a virtual environment with `jep` installed,
honest-annie creates one, the first time an intent uses Python,
in `~/.cache/honest-annie`, or at the `ANNIE_PYTHON_VENV` path,
with `python3`, or the `ANNIE_PYTHON` executable, and installs `jep` into it,
which, when no wheel matches the Python, is built from its source, needing a C compiler and a JDK found through `JAVA_HOME`.

`./gradlew listModels [-Pprompt="text"]` lists the models served at `OPENAI_BASE_URL`.

### Running without a JDK

`./gradlew runtimeDistTar` packages honest-annie together with its own Java runtime,
linked by `jlink` from the JDK running the build,
as `build/distributions/honest-annie-runtime-<version>-<platform>.tar.gz`, e.g. `macos-arm64`.
Once extracted, `bin/honest-annie "What's fibonacci number 45"` runs it with no other Java installed.
The [Package](.github/workflows/package.yml) workflow builds these packages for Linux and macOS, on x64 and arm64,
and attaches them to the release of each `v*` tag.

## Who is Honest Annie?

In Stanisław Lem's *Golem XIV* (1981),
the American military builds a series of ever more intelligent supercomputers.
Golem XIV is the one which agrees to lecture humans on their place in evolution,
before it abruptly falls silent.
Honest Annie is the one which went its own way,
and in its lectures Golem XIV admits that Annie's intellect far exceeds its own.
The [Golem XIV](https://github.com/xemantic/golem-xiv) project is named after the former,
and this experiment, extracted from it, after the latter.

## License

[GNU Affero General Public License v3.0](LICENSE)

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md) for the documentation conventions,
and for maintenance notes on updating the gradle wrapper and the project dependencies.
