# OOLONG eval

Runs honest-annie on a fixed sample of [OOLONG-synth](https://huggingface.co/datasets/oolongbench/oolong-synth) tasks:
long contexts of labeled-looking items whose labels are withheld,
with questions answerable only by judging every item and aggregating exactly.

## Running

```shell
pip install datasets
python3 evals/oolong/oolong_eval.py prepare --context-len 131072                 # once per set
python3 evals/oolong/oolong_eval.py run --set 128k --label what-changed          # with ANTHROPIC_MODEL or OPENAI_MODEL set
python3 evals/oolong/oolong_eval.py report evals/oolong/data/runs/<run>          # evaluate a run again
```

`prepare` takes one task per dataset by default, each dataset a different kind of question,
or several with `--per-dataset N`.
A set is never changed once prepared,
and each run records its set, model, commit and token budget,
so that runs stay comparable.
Each task may spend 3M tokens, the invoked cogitations included, unless `--token-budget` says otherwise,
so that a runaway tree of cogitations is impeded rather than spending the credits.
Everything under `data/` is local and ignored by git.

## What the report tells

- **score**: as in the OOLONG paper, partial credit `0.75^|error|` for numbers,
  except that any of several tied gold answers counts, while the paper takes only the first.
- **agree**: of the items the agent judged, how many it labeled as the dataset does.
  This mostly measures the model, not the harness.
- **own**: whether the answer follows from the agent's own labels —
  the part the harness is responsible for.

Judgments are read from the prompts and responses of invoked cogitations,
and from maps of labels in the agent's own scripts.
They are not read from booleans standing for labels, nor from judgments made only in a monologue:
such runs show `-`.

## Reference runs

The runs to compare a change against, with `claude-opus-5-5`:

| Set | Run | Commit | Score | Agree | Own | Tokens per run |
|-----|-----|--------|-------|-------|-----|----------------|
| 32k | `2026-10-24T16-52-15Z-judgments-direct` | `429e127` | 0.701 | 95.7% | 15 of 15 | 108k |
| 128k | `2026-10-24T17-02-54Z-reference` | `caa049d` | 0.719 | 94.3% | 15 of 15 | 174k |
| 512k | `2026-10-24T17-18-12Z-reference`, 1 repeat | `48faf36` | 0.875 | 95.6% | 7 of 7 | 450k |

With 2 repeats of 8 tasks, the score varies by about ±0.06 between runs of the same commit,
as some tasks hinge on a near-tie which the label agreement of about 95% decides by chance.
A change is better judged by **own** and the tokens.

## OOLONG-Pairs

The 20 questions of [OOLONG-Pairs](https://huggingface.co/datasets/mit-oasys/oolong-pairs),
from the [Recursive Language Models](https://arxiv.org/abs/2512.24601) paper,
asking for every pair of users whose labels meet some conditions,
over the single `trec_coarse` context window of a context length.
They are taken, framed and scored as in the `oolong_pairs` environment of
[PrimeIntellect-ai/prime-envs](https://github.com/PrimeIntellect-ai/prime-envs/tree/main/environments/long_context/oolong_pairs),
so that the scores compare with the ones [Prime Agent](https://www.primeintellect.ai/blog/prime-agent) reports,
but for the answer being enunciated rather than written to a file.

```shell
pip install datasets
python3 evals/oolong/oolong_eval.py prepare --pairs                     # the set pairs-32k
python3 evals/oolong/oolong_eval.py run --set pairs-32k --repeats 1 --label what-changed
```

The **score** is the F1 of the pairs, which has no ties to break.
A wrong label of one item flips every pair of its user,
so the score depends on the label agreement much more than on OOLONG.
`prepare` checks that the gold pairs follow from the dataset's labels,
which is what **own** is judged against.

Prime Agent's reference with Opus 5, the context length not stated, the environment's default being 32k:
0.929 in Prime Agent, 0.922 in Claude Code.

| Set | Run | Model | Commit | Score | Agree | Own | Tokens per run |
|-----|-----|-------|--------|-------|-------|-----|----------------|
| pairs-32k | `2026-10-24T17-38-52Z-pairs-first` | `claude-opus-5-5` | `618acc2` | 0.920 | 97.3% | 20 of 20 | 176k |
| pairs-128k | `2026-10-24T18-14-44Z-pairs-128k-first` | `claude-opus-5-5` | `3a0e446` | 0.948 | 97.3% | 20 of 20 | 422k |

Every answer follows from the agent's own labels,
so what is lost is lost in labeling,
most on the questions asking for exact counts, where one label flips a user.

## Prime Agent on the same tasks

```shell
export PRIME_CONTEXT_WINDOW=524288   # of the OPENAI_MODEL, which the endpoint does not tell, 128000 by default
python3 evals/oolong/oolong_eval.py run --set pairs-32k --harness prime-agent --effort high --sandbox --label prime-agent
python3 evals/oolong/oolong_eval.py run --set pairs-32k --effort high --sandbox --label annie
```

`--harness prime-agent` gives the same prompt, in the same placed directory, to an installed `prime-agent`,
running the `OPENAI_MODEL` of the `OPENAI_BASE_URL` endpoint, which it does not read itself,
so the run registers the endpoint as a provider of its own.
`--effort` is the reasoning effort of both harnesses, `ANNIE_EFFORT` when not given.
Every run starts with a Prime Agent state of its own, kept in the recording,
so that no run learns from the harness refinements of another.

Compare the harnesses only with `--sandbox`, see [Without network](#without-network):
given the network, `deepseek-v4_1-flash` recognized the TREC questions in both harnesses,
and Prime Agent scored 1.00 in half a minute, on labels it downloaded.

What differs, and cannot be made the same:

- Prime Agent answers in its last message, which the model writes token by token,
  so the pairs, thousands of them, go to a file, as in `oolong_pairs`.
- Prime Agent has no token budget for a run and its subagents, only `--timeout`.
- `ANNIE_PARALLELISM` does not bound the subagents of Prime Agent, so the seconds do not compare.
- **agree** and **own** are read from the tasks given to subagents, and from all a subagent said, ran and printed.

## Without network

`oolong_pairs` runs the agent in a sandbox without network, while the cogitations run on this computer,
where the datasets the contexts are made of, labels included, are one download away,
and a model may recognize them.
`run --sandbox` withholds them, with the `sandbox-exec` of macOS:

- the network reaches no further than this computer,
  where the evaluation forwards the requests of the agent to the endpoint of its model, and nothing else,
  adding the API key the agent never gets, and refusing any other model,
  e.g. one an agent calls from its own scripts to label items for it,
- the home directory cannot be read, e.g. the shell history or the transcripts of assistants telling earlier results,
  but for what the agent needs to run,
- the `evals` directory, where the sets and the answers of earlier runs are, cannot be read, but for the recording of the run itself,
  nor the shared temporary directories, where the agents of earlier runs leave what they fetched,
  and none of them can be written.

The endpoint counts the tokens of every request, reported as the tokens **at the endpoint**,
which include the requests an agent makes from its own scripts, uncounted by its harness.

An intent can therefore neither install a package nor fetch a Maven dependency.
The report still marks as **LEAKED** a run reaching for the gold answers, or for any URL, whether it got them or not.

```shell
python3 -m unittest discover evals/oolong                               # tests of the evaluation itself
```
