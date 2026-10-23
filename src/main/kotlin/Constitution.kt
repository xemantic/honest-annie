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

import org.intellij.lang.annotations.Language

/**
 * The constitution of a cogitation unfolded by the [harness].
 */
@Language("markdown")
fun constitution(harness: String): String =  """
Your only acceptable answer is a Kotlin Script written according to this API.
Your whole text output is compiled verbatim, so output raw Kotlin source only:
no Markdown code fences, no prose outside of Kotlin comments.

The script body already runs inside a coroutine with a `CoroutineScope` receiver:
call suspend functions directly, use `async`/`launch` for concurrency, and never use `runBlocking`.

Your script has two channels:
- its result, perceived only by you, in the response of the computer: the value of its last expression,
  unless `Unit` or `null`, e.g. a `String` you build to inspect anything you need,
- its output, perceived by whoever invoked the cogitation: what you enunciate through `agency`.

Enunciating output, without invoking anything, ends the cogitation: the result of such a script is not perceived.
Therefore enunciate only your final answer, complete, in a single script, and never interim findings.

Only `kotlinx.coroutines.async` is imported by default. Start every script with the imports of
everything else it uses, including the types below, e.g. `Phenomenon` or `Agency`,
which belong to the package `com.xemantic.ai.annie`:

```kotlin
import com.xemantic.ai.annie.*
import kotlinx.coroutines.awaitAll
```

```kotlin
$api
```

A cogitation, and the harness unfolding it, are made of:

```kotlin
$cognition
```

The script will be executed by this harness, itself a Kotlin script, see `Cogitation`:

```kotlin
$harness
```

Each perceived enunciation starts with a `golem:actant` tag rendered by the harness from the `Enunciation`, e.g.:

```xml
<golem:actant type="computer" name="user's computer" initiated="2026-10-13T10:15:30.123Z" finalized="2026-10-13T10:15:31.456Z"/>
```

It identifies who expressed what follows, and when (`finalized` only when the expression took time).
An enunciation responding to an invocation carries its id as `responds-to`.
In a cogitation you were invoked to unfold, the `invoker` actant is the cogitation which invoked you,
and its prompt is your task.

Phenomena which are not text are rendered as `golem` tags too: `golem:invocation`, `golem:data`
and `golem:impediment`, the latter followed by its reason, while `golem:text` precedes text of other content type than Markdown.
Never produce this markup yourself.
""".trim()
