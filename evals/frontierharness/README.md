# FrontierHarness Eval

Runs honest-annie in [FrontierHarness Eval](https://frontierharness.org),
which holds the model at Kimi K3 and the tasks at 30 software-engineering ones,
21 of Terminal-Bench run by Harbor
and 9 of [DeepSWE](https://github.com/datacurve-ai/deep-swe) run by Pier,
so that only the harness differs from the [published baselines](https://github.com/frontier-harness-eval/eval#full-results),
e.g. Codex at 66.7% and Pi at 60.0%.

## Prerequisites

- the [Runta](https://runta.com/docs/) CLI, authenticated: `brew install runta-dev/tap/runta && runta login`
- `jq`, node >= 18, python3 >= 3.9, git
- the key of the provider of Kimi K3 stored as a Runta secret once, e.g. `runta secret set FIREWORKS_API_KEY --prompt`

## Running

```shell
evals/frontierharness/frontierharness.sh setup       # clones the eval into data/eval, checks the prerequisites
evals/frontierharness/frontierharness.sh provision   # builds the pushed HEAD into a golden checkpoint
evals/frontierharness/frontierharness.sh smoke       # one task of each suite, to check the plumbing
evals/frontierharness/frontierharness.sh run         # the published 30 tasks, one fresh restore each
evals/frontierharness/frontierharness.sh report <run-id>
```

`PROVIDER` selects who serves Kimi K3 (`fireworks` by default, like the baselines),
`COMMIT` the commit of honest-annie to evaluate (`HEAD` by default),
which must be pushed, as the build runtime clones it from GitHub.
Further arguments go to the script of the eval, e.g. `--tasks <file>` for a subset.
Everything under `data/` is local and ignored by git.

Before the full sweep, check the smoke run by hand, as the [eval's prompt](https://github.com/frontier-harness-eval/eval/blob/main/skills/frontierharness-eval/PROMPT.md) asks:
`jq . data/eval/runs/<run-id>/trials/*/trial.json` should show real token usage and a reward read from the verifier,
and the agent's directory of each job holds `honest-annie.txt`, her answer,
`honest-annie-stderr.txt`, and the `cogitations` she recorded.

## How it works

1. `provision` runs the eval's `provision-golden-checkpoint.sh` with [install-harness.sh](install-harness.sh),
   which builds the [runtime distribution](../../README.md#running-without-a-jdk) in the Runta build runtime,
   and makes the adapters importable by the uv tools of Harbor and Pier with a `.pth` file.
   The adapters, [run-task.sh](run-task.sh) and the settings in [annie.env](annie.env) come from the clone at the pinned commit,
   so they are frozen into the checkpoint together with the harness.
2. Each trial runs [run-task.sh](run-task.sh) as the `--cmd` of `run-trials.sh`, which keeps every flag of the eval's default runner templates,
   except for naming the agent:
   `-a honest_annie_harbor:HonestAnnie` for Harbor, and `--agent-import-path honest_annie_pier:HonestAnnie` for Pier,
   whose `--agent` takes only its built-in agents.
3. At setup, [the adapter](honest_annie_adapter.py) uploads the distribution into the task container,
   and adds the CA of the Runta egress, which terminates TLS, to its Java runtime.
   Annie runs inside the container, as her scripts must act on the files of the task,
   against the OpenAI-compatible endpoint of the provider of the model route,
   e.g. `fireworks_ai/accounts/fireworks/models/kimi-k3` → `https://api.fireworks.ai/inference/v1`,
   with the stub of the key, which the egress proxy replaces with the real one.
   Under Pier, the task is air-gapped, and only the endpoint is reachable, through an authenticated proxy,
   which honest-annie takes from `HTTPS_PROXY`, see [HttpsProxy.kt](../../src/main/kotlin/cognizer/HttpsProxy.kt).
4. After the run, the adapter reads the tokens of every inference of the tree of cogitations from their `inferences.json`,
   reports their totals to the runner, the cached input included in the input, the reasoning in the output,
   and writes each call to `honest-annie-calls.jsonl`, in the format of the `hermes` parser of the eval's `usage_details.py`,
   which `setup` registers for `honest-annie`, so that the first-cold cost and the turns are accounted for like those of the baselines.

## Caveats

- Like any new run, it is reported with `methodology_comparable: false`, unranked,
  since the egress policy of the baselines is not recorded,
  until a matched control, e.g. Pi, runs under the same policy, see the [eval's reference](https://github.com/frontier-harness-eval/eval/blob/main/skills/frontierharness-eval/reference.md#corpus-pin-and-pi-control).
- The distribution is linked against glibc, as are the task images of the eval; an Alpine-based task would fail at setup.
- The Python of the intents needs `jep`, built from source when no wheel matches, needing a C compiler the task image may lack;
  an intent using Python then fails, like a missing tool would.
- `ANNIE_TOKEN_BUDGET` in [annie.env](annie.env) guards against a runaway tree of cogitations,
  far above the tokens the baselines spend on a task, so it should not decide any outcome.
- The registry entry `setup` adds to the eval's `usage_details.py` is the only change made to the eval's scripts;
  it is a candidate for an upstream pull request.

## Testing

```shell
python3 -m unittest discover evals/frontierharness
```
