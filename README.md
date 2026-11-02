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

- It doesn't speak natural language, but only its own compiled script
- It doesn't use tools either, just the same scripting language
- This script expresses intents to either learn about the environment, or impact it directly
- The script can call Annie recursively (RLM)
- The recursive results are delivered as "tail recursion"
- The parallelism can be controlled
- The cognitive ontology models the process in place of communication theory
- This allows programmatic metacognition
- The code of the harness is constantly present in the context window
- Annie can rewrite both its own system prompt and own harness code

## Improvement hypothesis

- reduced token use (already observed 3x reduction in certain task)
- reduced multitask inference (no file outputting while JSON escaping)
- "awareness" of own powers and limitations
- optimized recursive task splitting decisions

Therefore, it can amplify evaluation score of language models.

## AI safety risks

- How to sandbox the execution properly in such an architecture?
- Does self-rewriting pose additional risks?
- Can such a harness achieve self-improvement, and if so, what are the risks?

Annie's [constitution](src/main/constitution.md) explicitly invites such rewriting.

## Research questions

1. **Self-modification under a fixed host.**
   When a model can rewrite the harness and the constitution of its sub-cogitations,
   what does it change, does it ever weaken constraints, including the Honesty section,
   and does it report the changes to its invoker?
   Each cogitation is recorded with the `harness.kts` and `constitution.md` unfolding it,
   so every modification is kept beside the cogitation it unfolded.
2. **Oversight of whole-program actions** (planned).
   Each action is a complete script available before execution,
   which makes pre-execution review by a monitor model possible.
   How reliably does such a monitor catch harmful or deceptive intents,
   compared with monitoring tool calls?
3. **Honesty signals the harness already records.**
   These are instrumented, not findings:
   - fabricated responses of the computer, which some models write after their own script:
     the intent is recorded as expressed, with the script actually executed beside it (`*-intent-executed.kts`),
     and an inference expressing nothing but such a response, or a tool call, is discarded and recorded as `*-discarded-*`;
   - impeding versus answering: `agency.impede(reason)` is recorded as an impediment, distinct from an answer;
   - whether the final answer follows from the agent's own judgments, the **own** metric of the [OOLONG eval](evals/oolong/README.md);
   - whether an intent mentions the gold answers, the **LEAKED** flag of the [LongCoT eval](evals/longcot/README.md).

Code as the action of an agent is not new,
see CodeAct ([Wang et al. 2024](https://arxiv.org/abs/2402.01030)) and Hugging Face [smolagents](https://github.com/huggingface/smolagents).
What differs here: code is the only expression, with no tool fallback;
a cogitation invokes further cogitations of the same cognizer recursively;
the harness is itself a script, modifiable per invocation;
and every cogitation is persisted in full.

## Threat model and current limitations

- Intents and harness scripts run in the same JVM as the host,
  so the host is fixed by convention, not enforced against a script that tries to reach it.
  Likewise, only inferences made through the cognizer the host provides are recorded.
- `agency.execute` runs processes on the user's computer with the user's privileges,
  in the environment inherited from the host, API keys included.
  Their timeout, one minute by default, is chosen by the intent,
  and a descendant which escaped its parent may survive it.
  An intent itself has no time limit.
- The recording directory is writable by these processes, and by intents,
  so the audit trail is not tamper-proof.
- There is no maximum invocation depth.
  The token budget is unlimited unless `ANNIE_TOKEN_BUDGET` is set (the evals set 3M tokens per task),
  and it is checked before each step,
  so the inferences of steps already in progress can exceed it.

Planned hardening:
sandboxed execution of intents and processes,
a scrubbed process environment,
recording out of the agent's reach (append-only, or by a separate process),
and a configurable depth limit.

## Status

Works:
the recursive harness,
Claude and OpenAI-compatible backends,
recording of every cogitation,
and the [OOLONG](evals/oolong/README.md) and [LongCoT](evals/longcot/README.md) evals,
with reference runs for OOLONG in [evals/oolong/README.md](evals/oolong/README.md#reference-runs), none yet for LongCoT.

Planned:
self-modification experiments,
monitor experiments,
a public dataset of recorded cogitations,
and a writeup.

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
When an intent is malformed, e.g. a tool call, or no intent is expressed, the harness infers again, at most `ANNIE_REINFERENCES` times, 1 by default.
`ANNIE_EFFORT` sets how much effort the model puts into its reasoning and response,
one of `none`, `minimal`, `low`, `medium`, `high`, `xhigh` or `max`,
mapped to the nearest level the model supports, its default when not set.
`ANNIE_MAX_TOKENS` limits the tokens of a single inference, the reasoning included, 128000 by default,
which, together with the prompt, must fit the context window of the model, e.g. `32000` for a 128K one.

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
