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

### Kotlin Multiplatform

- Code in `commonMain` ships to JVM, JS, Wasm and Native, so it must never reach for `java.*`, `javax.*` or any other JVM-only type (`java.util.ArrayDeque`, `File`, `Thread`, `System.currentTimeMillis`, …), even when dev runs default to `jvmTest`.
  Use the Kotlin stdlib equivalents (`kotlin.collections.ArrayDeque`, `kotlin.time.TimeSource`, `kotlinx.coroutines`), and isolate a genuinely JVM-only API behind `expect`/`actual`.
- `MutableMap.putIfAbsent` is JVM-only and not in the common stdlib.
  For "put only if the key is absent" use `getOrPutIfMissing(key) { value }` (Kotlin 2.4, `@ExperimentalStdlibApi`), not `getOrPut` — `getOrPut` also recomputes when the stored value is `null`, conflating "missing" with "null" ([KEEP-0457](https://github.com/Kotlin/KEEP/blob/main/proposals/stdlib/KEEP-0457-alternative-behavior-for-map-getOrElse.md)).
- Place `@OptIn(...)` at the narrowest scope — on the statement or expression using the experimental API, not on the enclosing function.
  Annotating the whole function silently opts every other call in its body into the same API, hiding future accidental usages.
- A backtick-quoted identifier (e.g. a test name) must not contain CR, LF, or any of ``` ` \ < > [ ] / . : ; * ? " | , # ( ) & ~ @ $ { } ```.
  The JVM accepts most of them, but Native and JS reject them with `Name contains illegal characters: …`, so the strict rules govern — keep to alphanumerics, `_`, `-`, space and `!`.

### Tests

- **Write the failing test first.**
  For any newly found bug, edge case or use case, add a test that reproduces it *before* touching the implementation, watch it fail, then make it pass.
  Add it at the narrowest level that exercises the behavior — an end-to-end test can pass while the bug hides in an untested combination.
  Scratch tests used to probe behavior are fine, but replace them with a named regression test before finishing.
- Tests keep the `// given`, `// when`, `// then` comment structure — AI agents tend to omit it.
  In a functional-style test where the setup is inlined into the action, `// given` may be omitted, but `// when` and `// then` stay.
- Assert a boolean expression with `assert(expr)` from `com.xemantic.kotlin.test` — it is power-assert-instrumented in `build.gradle.kts`, so a failure prints every subvalue of the expression.
  Do not fall back to `assertEquals` / `assertTrue` / `assertNotNull` from `kotlin.test`, which discard that information.
- Never assert on wall-clock time (a ratio or a bound) in `commonTest`: it runs on every target under whatever CI load there is, and fails with no code change.
  To guard against, say, quadratic backtracking, feed an input large enough that the regression hangs for minutes while the correct code takes milliseconds.

### Working with AI agents

- In Claude Code "auto mode", never commit on your own — leave the changes in the working tree so the developer can review the diff first, and commit only when explicitly asked.
- The copyright year range (e.g. 2025-2026) is applied on autosave — a new file uses only the current year.

## Known gotchas

- After upgrading the Gradle wrapper, `jvmTest` may fail with `NoSuchFileException: build/test-results/jvmTest/binary/in-progress-results-generic.bin`, because the results of the previous Gradle version are stale — delete `build/test-results` (or run `clean`) and retry.
- The `rootPackageJson` and `wasmRootPackageJson` tasks do not track yarn `resolution(...)` entries as inputs,
  so after changing them the lock files stay stale unless these tasks are forced with `--rerun`
  (see the comment above `npmResolutions` in `build.gradle.kts` for the full command).
- Dependabot alerts on `kotlin-js-store/yarn.lock` and `kotlin-js-store/wasm/yarn.lock` concern only the Kotlin/JS test tooling (mocha, karma, webpack),
  none of which ships in the published artifacts —
  dismiss them on GitHub as "vulnerable code is not actually used" instead of forcing versions via yarn resolutions,
  which pins majors the tooling never declared support for.
- The `Reporter option 'alsoWithHtml' has no effect` warning printed by JS/Wasm test tasks
  comes from JetBrains' own `kotlin-web-helpers` npm package ([kotlin-web-helpers#11](https://github.com/Kotlin/kotlin-web-helpers/issues/11)) —
  nothing in this build causes it and there is no knob to suppress it, so leave it alone.
- After a fresh Gradle daemon starts, the first build reports `configuration cache cannot be reused because file '.../caches/jreleaser/jreleaser/<version>/marker.txt' has changed` —
  the JReleaser plugin bumps a banner counter in that file behind Gradle's back on every configuration,
  so the cache is invalidated once per daemon lifetime and reused normally afterwards; it is harmless.

- The Gradle configuration cache is enabled (`gradle.properties`), so a task action (`doLast { … }`) must not call a function declared in the build script — the script object is not serializable.
  Inline the logic or move the helper into `buildSrc` / an included build, and register tasks with `tasks.register(...)` rather than the `by tasks.registering` delegate.
- A `Regex` used with `matches()` in `commonMain` must be explicitly anchored (`^(?:a|b)$`) when it contains a top-level alternation:
  Kotlin/JS resolves `matches` through the leftmost match, so the first branch wins on a prefix and the whole-input check fails (`0x1F` matched `[0-9]+|0x[0-9a-fA-F]+` as `0`) — the JVM backtracks across the branches and never shows it,
  so a JVM-only test run is not enough evidence for a regex change.
- A `commonMain` `Regex` applied to untrusted input must not repeat a group (`(?:_?[0-9])*`):
  `java.util.regex` matches each repetition one stack frame deeper, so a few thousand repetitions throw `StackOverflowError` — an `Error` nothing catches.
  Repeat a character class instead (`[0-9_]*`) and check what the group expressed by hand.

## Anti-patterns to avoid

- Do not add content to this file that is already discoverable by reading the source or build scripts — that inflates context without adding signal, reducing AI agent task success rates (see [arxiv 2602.11988](https://arxiv.org/abs/2602.11988)).
