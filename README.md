# honest-annie

A recursive harness for cognizers acting through Kotlin scripts

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

## Why?

An experiment extracted from [Golem XIV](https://github.com/xemantic/golem-xiv):
a cognizer answers only with a Kotlin script,
which is executed by the harness,
itself a Kotlin script,
so the cognizer can invoke further cogitations of its own recursively.

The name comes from Honest Annie,
the supercomputer which in Stanisław Lem's *Golem XIV* went its own way.

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

`./gradlew listModels [-Pprompt="text"]` lists the models served at `OPENAI_BASE_URL`.

## License

[GNU Affero General Public License v3.0](LICENSE)

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md) for the documentation conventions,
and for maintenance notes on updating the gradle wrapper and the project dependencies.
