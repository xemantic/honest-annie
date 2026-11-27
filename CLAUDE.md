# CLAUDE.md

This file captures only what cannot be inferred from the codebase itself.

## Rules for editing this file

Both developers and AI agents are expected to add entries as they encounter surprises.

- **Add an entry** when you encounter something unexpected: a build quirk, a non-obvious constraint, a dependency gotcha, or any behavior that would surprise the next agent or developer.
- **Add an entry** when a developer flags an anti-pattern produced by AI — describe the anti-pattern and the preferred alternative.
- **Do not** add codebase overviews, directory listings, or anything discoverable by reading the source.
- Keep entries concise: the lesson and its non-obvious *why*, grouped under a heading if a theme emerges.
  Name a symbol or test as a pointer, but don't restate what its source comment already says.

## Conventions

### Markdown authoring

Markdown files use [semantic line breaks](https://sembr.org/):
break a line after a sentence,
and optionally at clause boundaries within a long sentence,
so that diffs stay meaningful and reviewable.

There is no column width limit —
never reflow or hard-wrap a paragraph to fit some character count.
Modern editors soft-wrap Markdown visually,
see [DEVELOPMENT.md](DEVELOPMENT.md#markdown-soft-wrapping-in-the-ide) for how to enable it.

### Kotlin

- Place `@OptIn(...)` at the narrowest scope — on the statement or expression using the experimental API, not on the enclosing function.
  Annotating the whole function silently opts every other call in its body into the same API, hiding future accidental usages.

### Tests

- **Write the failing test first.**
  For any newly found bug, edge case or use case, add a test that reproduces it *before* touching the implementation, watch it fail, then make it pass.
  Add it at the narrowest level that exercises the behavior — an end-to-end test can pass while the bug hides in an untested combination.
  Scratch tests used to probe behavior are fine, but replace them with a named regression test before finishing.
- Tests keep the `// given`, `// when`, `// then` comment structure — AI agents tend to omit it.
  In a functional-style test where the setup is inlined into the action, `// given` may be omitted, but `// when` and `// then` stay.
- Assert a boolean expression with `assert(expr)` from `com.xemantic.kotlin.test` — it is power-assert-instrumented in `build.gradle.kts`, so a failure prints every subvalue of the expression.
  Do not fall back to `assertEquals` / `assertTrue` / `assertNotNull` from `kotlin.test`, which discard that information.
- The tests of `commonTest` of the API also run on Kotlin/Native, which rejects a backticked test name containing a comma (and other characters, e.g. `.`, `:`, `/`), while the JVM accepts it — word a common test's name without them.
- Never assert on wall-clock time (a ratio or a bound): tests run under whatever CI load there is, and fail with no code change.
  To guard against, say, quadratic backtracking, feed an input large enough that the regression hangs for minutes while the correct code takes milliseconds.

### Working with AI agents

- In Claude Code "auto mode", never commit on your own — leave the changes in the working tree so the developer can review the diff first, and commit only when explicitly asked.
- The copyright year range (e.g. 2025-2026) is applied on autosave — a new file uses only the current year.

## Known gotchas

- After upgrading the Gradle wrapper, `test` may fail with `NoSuchFileException: build/test-results/test/binary/in-progress-results-generic.bin`, because the results of the previous Gradle version are stale — delete `build/test-results` (or run `clean`) and retry.
- The Gradle configuration cache is enabled (`gradle.properties`), so a task action (`doLast { … }`) must not call a function declared in the build script — the script object is not serializable.
  Inline the logic or move the helper into `buildSrc` / an included build, and register tasks with `tasks.register(...)` rather than the `by tasks.registering` delegate.
- Calling `environment(...)` on an `Exec` / `JavaExec` task at configuration time snapshots the *whole* process environment into the configuration cache, so a reused entry runs with stale variables (e.g. a freshly exported `ANTHROPIC_MODEL` is missing).
  Set it in `doFirst` instead, as the `run` task does.
- `ExecutorProcess` starts the executor with the JVM arguments of the harness, `-Xmx` included, so under Gradle tests it gets the 512 MB of the test worker:
  a test cognizer that never rests fills it with snippets in minutes, the executor exiting with an `OutOfMemoryError`, logged as a warning of `ExecutorProcess`, not as a failure of the protocol.
  Make every scripted cognizer of a test answer with text once its script goes unexpected.
- `createPythonVenv` only checks that `.venv/bin/python` exists, so a `.venv` with no pip (see the Debian/Ubuntu note in [README.md](README.md#running)) is never recreated, and `installPythonPackages` fails with `No module named pip`.

- Since xemantic-kotlin-script 0.3.0 cancelling an intent never interrupts the thread of its session, so an intent blocking it, e.g. in `Thread.sleep`, or in `agency.python`, runs to its end, and the next intents of the session wait for it.
  `agency.execute` interrupts the thread itself, with `runInterruptible` in `ExecutorAgency`: wrap any blocking function of the agency the same way.
  A test cancelling a slow intent must make it suspend, e.g. in `delay`, or execute a process, or it hangs.
- `await { }` is annie's own, in `Api.kt`, kept for the scripts written before xemantic-kotlin-script 0.3.0 dropped it, the snippets suspending since:
  the constitutions no longer teach it.
- `ScriptSession` of xemantic-kotlin-script offers no default imports beyond `kotlinx.coroutines.*`, so `SessionScriptExecutor` imports annie's API in a snippet of its own before the first intent:
  the snippets of the intents are numbered from `snippet2`, which compilation errors name, so never assert on `snippet1`.

- `honest-annie-api` holds only what `generateConstitution` pastes into the constitutions, every line of it costing the context of each cognizer:
  a function of the scripts whose body is more than a delegation is declared on `Agency` when it acts on the computer, e.g. `Agency.read`, implemented by the agency of the executor,
  and otherwise as an `expect` declaration, e.g. of `Json.kt`, its `actual` one in `implMain`, the source set shared by every target, the generator stripping the `expect` keyword.
  A class of the API whose implementation is hidden is an interface, e.g. `Appending`, `expect class` still being Beta.
- `JsonImpl.kt` must import the `kotlinx.serialization.json` extensions (`booleanOrNull`, `intOrNull`, …) explicitly, never as `kotlinx.serialization.json.*`:
  a star import ranks below the declarations of the same package, so annie's own `JsonElement` extensions of the same names would call themselves, failing `JsonNavigationTest` with a `StackOverflowError`.
  The IDE collapses 5 or more imports of a package into a star import on optimizing them.

### Model APIs

- Some OpenAI-compatible proxies apply `stop` sequences to reasoning content too, so a `<golem:stop/>` stop sequence kills generation inside the thinking block; detect the stop tag client-side instead.
- Unlike the OpenAI API, the `/v1/models` response of some OpenAI-compatible endpoints omits the `created` field; the SDK only fails when that field is read, so never read it (nor call `validate()` on these models).
- In anthropic-java 3.x `MessageParam` is a union (`MessageParam.ofUser(...)`, `ofAssistantOfContentBlockParams(...)`), not the `builder().role(...)` shape of 2.x that most examples, including AI agents' priors, still show.

## Anti-patterns to avoid

- Do not wrap literal values of the API, like content types (`"text/plain"`, `"text/markdown"`), in named constants: the scripts are mostly written by LLMs, which read and write the plain string more reliably than a symbol they have to look up.

- Do not add content to this file that is already discoverable by reading the source or build scripts — that inflates context without adding signal, reducing AI agent task success rates (see [arxiv 2602.11988](https://arxiv.org/abs/2602.11988)).
