# LongCoT eval

Runs honest-annie on a fixed sample of [LongCoT](https://github.com/LongHorizonReasoning/longcot)-mini tasks:
self-contained problems in logic, cs, chemistry, chess and math,
each a long chain of dependent steps, e.g. a Sokoban to solve, a type inference trace, or problems feeding their answers to each other.

They are taken, framed and scored as in the `longcot_mini` environment of
[PrimeIntellect-ai/prime-envs](https://github.com/PrimeIntellect-ai/prime-envs/tree/main/environments/long_context/longcot_mini),
so that the scores compare with the ones [Prime Agent](https://arxiv.org/abs/2608.23552) reports,
but for the answer being enunciated rather than written to a file.

## Running

```shell
python3 evals/longcot/longcot_eval.py prepare                                # once per set, the set easy-10
python3 evals/longcot/longcot_eval.py run --set easy-10 --label what-changed  # with ANTHROPIC_MODEL or OPENAI_MODEL set
python3 evals/longcot/longcot_eval.py report evals/longcot/data/runs/<run>   # evaluate a run again
```

`prepare` takes the 486 tasks of the `easy` difficulty,
which `longcot_mini` takes, the 21 math questions flagged as broken upstream left out,
from the `longcot` package at the commit `longcot_mini` depends on,
and samples 10 per domain by default, or `--per-domain N`,
each template of a domain taking a share in proportion to its size.
A set is never changed once prepared,
and each run records its set, model, commit and token budget,
so that runs stay comparable.
Each task may spend 3M tokens, the invoked cogitations included, unless `--token-budget` says otherwise.
Everything under `data/` is local and ignored by git.

Scoring needs [uv](https://docs.astral.sh/uv/):
[longcot_verify.py](longcot_verify.py) is `longcot_mini`'s own verifier, copied verbatim,
a script installing the `longcot` package from GitHub, with RDKit, python-chess and SymPy, in an environment of its own.

## What the report tells

- **score**: 1 when the verifier judges the answer correct, otherwise 0, as `longcot_mini`'s reward.
  For math, **components** tells how many of the chained answers match.
- **estimated for all tasks**: the mean of each domain weighed by its size in `longcot_mini`,
  the figure to set beside Prime Agent's.

The verifier would ask Gemini to judge a math or chemistry answer it cannot match itself when `GEMINI_API_KEY` or `GOOGLE_API_KEY` is set.
The keys are withheld from it, as `longcot_mini`'s scoring, whose network allows only the package hosts, could not reach Gemini either.

Unlike `longcot_mini`, which runs the agent in a sandbox without network,
the cogitations run on this computer, where an intent could install the `longcot` package and read the gold answers.
The report marks a run whose intents mention `longcot` or a `task.json` as **LEAKED**.

## Reference

Prime Agent's [report](https://arxiv.org/abs/2608.23552), Table 1, the number of tasks and repeats not stated:

| Model | Prime Agent | Native harness |
|-------|-------------|----------------|
| Opus 5 | 0.722 | 0.558 (Claude Code) |
| GPT-5.6 Sol | 0.671 | 0.681 (Codex) |
| GLM-5.2 | 0.638 | 0.613 (Pi) |

## Reference runs

| Set | Run | Model | Commit | Estimate | Mean | Tokens per run |
|-----|-----|-------|--------|----------|------|----------------|

```shell
python3 -m unittest discover evals/longcot                              # tests of the evaluation itself
```
