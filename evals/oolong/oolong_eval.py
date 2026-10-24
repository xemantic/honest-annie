# Evaluates honest-annie on a fixed sample of OOLONG-synth tasks, scored like the OOLONG paper.
# pip install datasets
#
#   python evals/oolong/oolong_eval.py prepare [--context-len 32768] [--per-dataset 1]  # select a set of tasks, once
#   python evals/oolong/oolong_eval.py run [--set 32k] [--repeats 2] [--label x]  # needs ANTHROPIC_MODEL or OPENAI_MODEL
#   python evals/oolong/oolong_eval.py report <run-dir>               # evaluate a run again, from data/runs
#   python evals/oolong/oolong_eval.py score <cogitation-dir> <task>  # score a cogitation recorded by hand
#
# Each set of tasks is kept in data/sets/<set>, e.g. `32k` or `131k-x2`, and never changed once prepared,
# so that every run, recording the set it used, can be evaluated again against the same gold answers.
#
# Besides the score, the report tells apart the two ways an answer can be wrong:
# - agreement: how many of the items the agent judged it labeled as the dataset does,
# - consistency: whether the answer follows from the agent's own labels, the part a harness is responsible for.
import argparse
import ast
import json
import os
import re
import subprocess
import sys
import threading
import time
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timezone
from itertools import combinations
from pathlib import Path

import dateutil.parser

ROOT = Path(__file__).resolve().parents[2]
EVAL_DIR = Path(__file__).resolve().parent / "data"
SETS_DIR = EVAL_DIR / "sets"
RUNS_DIR = EVAL_DIR / "runs"
AGENT = ROOT / "build" / "install" / "honest-annie" / "bin" / "honest-annie"
# the set of the runs recorded before there were sets
FIRST_SET = "32k"


def contexts_dir(set_name):
    # the contexts are kept apart from the gold answers and labels, so that the agent does not stumble upon them
    return SETS_DIR / set_name / "contexts"


def tasks_dir(set_name):
    return SETS_DIR / set_name / "tasks"


# each dataset takes the next kind first, so that every kind of question is covered
KINDS = [
    ("counting", "TASK_TYPE.NUMERIC_ONE_CLASS"),
    ("counting", "TASK_TYPE.RELATIVE_FREQ"),
    ("user", "TASK_TYPE.MOST_FREQ"),
    ("timeline", "TASK_TYPE.NUMERIC_ONE_CLASS"),
    ("user", "TASK_TYPE.NUMERIC_ONE_CLASS"),
    ("timeline", "TASK_TYPE.LEAST_FREQ"),
    ("timeline", "TASK_TYPE.REPRESENTED_N_TIMES"),
    ("user", "TASK_TYPE.SECOND_MOST_FREQ"),
    ("counting", "TASK_TYPE.MOST_FREQ"),  # the only other kind yahoo has at 32k, its labels being many
]

PROMPT = (
    "The file {context} holds a dataset (~{tokens}K tokens). "
    "Without loading it all into your own context, answer: {question}"
)

# OOLONG-Pairs questions, framed as in the oolong_pairs environment of PrimeIntellect-ai/prime-envs,
# but for the answer being enunciated rather than written to a file
PAIRS = "PAIRS"
PAIRS_PREFIX = (
    "The context window contains thousands of general-knowledge questions, one per line. "
    "Each line has a User ID, which is not necessarily unique (each User ID can be associated "
    "with multiple questions). Each question has an answer that can be described as one of 6 "
    "categories: 'numeric value', 'entity', 'location', 'description and abstract concept', "
    "'abbreviation', 'human being' — they are not explicitly labeled, so you must infer the "
    "label from the semantics of each question.\n\n"
    "You will be asked to compute exact aggregate label statistics over pairs of users. "
    "Do not guess, estimate, or approximate the result — calculate the exact answer.\n\n"
)
PAIRS_INSTRUCTIONS = (
    "Answer with every matching pair as `(user_id_1, user_id_2)`, one per line, sorted by the first user ID, "
    "and nothing else. If there are no matching pairs, answer exactly `[]`."
)

ITEM = re.compile(r"(?ms)^Date: (.*?) \|\| User: (\d+) \|\| Instance: (.*?) \|\| Label: ([^\n]*)$")


def items(labeled_text):
    """The items of a context window with labels, as `[date, user, instance, label]`."""
    return [list(m) for m in ITEM.findall(labeled_text)]


def prepare(args):
    from datasets import load_dataset

    if args.pairs:
        return prepare_pairs(args)
    set_name = args.set or f"{args.context_len // 1024}k" + (f"-x{args.per_dataset}" if args.per_dataset > 1 else "")
    if tasks_dir(set_name).exists():
        sys.exit(f"the set {set_name} is already prepared, and runs might depend on it; name another with --set")
    ds = load_dataset("oolongbench/oolong-synth", split="test")
    # chosen by the columns of metadata alone, as materializing a row reads its context windows, up to 4M tokens
    ds = ds.select([i for i, n in enumerate(ds["context_len"]) if n == args.context_len])
    if len(ds) == 0:
        sys.exit(f"no tasks of context length {args.context_len}")
    meta_columns = ["id", "dataset", "task_group", "task"]
    rows = sorted(({"index": i} | r for i, r in enumerate(ds.select_columns(meta_columns))), key=lambda r: r["id"])
    datasets = sorted({r["dataset"] for r in rows})
    used = Counter()
    contexts_dir(set_name).mkdir(parents=True)
    for i, dataset in enumerate(datasets):
        own = [r for r in rows if r["dataset"] == dataset]
        # the kind assigned to this dataset, then the next ones, then any other kind it has
        order = [KINDS[(i + k) % len(KINDS)] for k in range(len(KINDS))]
        order += sorted({(r["task_group"], r["task"]) for r in own} - set(KINDS))
        chosen = []
        for _ in range(args.per_dataset):
            # preferring the kinds covered the fewest times so far, a kind once per dataset
            for group, task in sorted((k for k in order if k not in chosen), key=lambda kind: used[kind]):
                row = next((r for r in own if r["task_group"] == group and r["task"] == task), None)
                if row:
                    chosen.append((group, task))
                    used[(group, task)] += 1
                    break
            else:
                print(f"{dataset}: only {len(chosen)} kinds of task at this context length")
                break
            name = f"{dataset}-{group}-{task.removeprefix('TASK_TYPE.').lower()}"
            row = ds[row["index"]]
            (contexts_dir(set_name) / f"{name}.txt").write_text(row["context_window_text"])
            task_dir = tasks_dir(set_name) / name
            task_dir.mkdir(parents=True)
            meta = {k: row[k] for k in (
                "id", "context_window_id", "context_len", "dataset", "task_group", "task",
                "answer_type", "question", "answer"
            )}
            (task_dir / "task.json").write_text(json.dumps(meta, indent=2) + "\n")
            (task_dir / "labels.json").write_text(json.dumps(items(row["context_window_text_with_labels"])))
            print(f"{name}: expected {' or '.join(map(str, gold(meta)))}")
    print(f"prepared the set {set_name}")


def prepare_pairs(args):
    """The 20 OOLONG-Pairs questions over the trec_coarse context window of the context length, as prime-envs takes them."""
    from datasets import load_dataset
    from huggingface_hub import hf_hub_download

    set_name = args.set or f"pairs-{args.context_len // 1024}k"
    if tasks_dir(set_name).exists():
        sys.exit(f"the set {set_name} is already prepared, and runs might depend on it; name another with --set")
    # every trec_coarse task of a context length shares one context window, streamed not to read the whole split
    row = next((r for r in load_dataset("oolongbench/oolong-synth", split="validation", streaming=True)
                if r["dataset"] == "trec_coarse" and r["context_len"] == args.context_len), None)
    if row is None:
        sys.exit(f"no trec_coarse context window of context length {args.context_len}")
    rows = items(row["context_window_text_with_labels"])
    questions = json.loads(Path(hf_hub_download(
        "mit-oasys/oolong-pairs", f"data/oolong-pairs-{args.context_len}.json", repo_type="dataset"
    )).read_text())
    contexts_dir(set_name).mkdir(parents=True)
    for q in questions:
        name = f"pairs-{int(q['id']):02d}"
        (contexts_dir(set_name) / f"{name}.txt").write_text(row["context_window_text"])
        task_dir = tasks_dir(set_name) / name
        task_dir.mkdir(parents=True)
        meta = {
            "id": q["id"], "context_window_id": row["context_window_id"], "context_len": args.context_len,
            "dataset": row["dataset"], "task": PAIRS, "question": q["question"], "answer": q["answer"]
        }
        (task_dir / "task.json").write_text(json.dumps(meta, indent=2) + "\n")
        (task_dir / "labels.json").write_text(json.dumps(rows))
        # the gold pairs should follow from the labels, as the consistency of answers is judged by them
        solved = "" if solve_pairs(q["question"], rows) == gold(meta) else ", NOT the pairs solved from the labels"
        print(f"{name}: expected {len(gold(meta))} pairs{solved}")
    print(f"prepared the set {set_name}")


def gold(task):
    """Every answer counted as correct: several when tied, while OOLONG's own scoring takes only the first."""
    if task["task"] == PAIRS:
        return parse_pairs(task["answer"])
    if "datetime" in task["answer"]:
        return [date(*map(int, d)) for d in re.findall(r"datetime\.date\((\d+), (\d+), (\d+)\)", task["answer"])]
    return ast.literal_eval(task["answer"])


def attempt_answer_parse(answer):
    # OOLONG's synth_attempt_answer_parse, verbatim but for the confidence it reports
    if ":" not in answer:
        if len(answer) < 20:
            return answer
        else:
            return answer.split()[-1]
    candidate = answer.split(":")[-1].strip()
    candidate = candidate.replace("*", "").replace("[", "").replace("]", "")
    if len(candidate) < 20:
        pass
    elif "more common" in candidate:
        candidate = "more common"
    elif "less common" in candidate:
        candidate = "less common"
    elif "same frequency" in candidate:
        candidate = "same frequency"
    return candidate


def score_one(expected, answer_type, parsed):
    # OOLONG's synth_process_response: exact match, partial credit 0.75^|error| for numbers
    if str(parsed) == str(expected):
        return 1.0
    if parsed in ("more common", "less common", "same frequency"):
        return float(parsed in str(expected))
    if answer_type == "ANSWER_TYPE.NUMERIC":
        try:
            return 0.75 ** abs(int(expected) - int(parsed))
        except ValueError:
            return 0.0
    if answer_type == "ANSWER_TYPE.DATE":
        try:
            return float(dateutil.parser.parse(parsed).date() == expected)
        except (ValueError, OverflowError):
            return 0.0
    return 0.0


def score(answers, answer_type, output):
    """The parsed output, and its score against the best matching one of the answers."""
    parsed = attempt_answer_parse(output)
    return parsed, max(score_one(a, answer_type, parsed) for a in answers)


def parse_pairs(answer):
    # prime-envs oolong_pairs' _parse_pairs, verbatim but for the name
    pairs: set[tuple[int, int]] = set()
    items = answer if isinstance(answer, list) else [answer]
    for item in items:
        if not isinstance(item, str):
            continue
        matches = re.findall(r"\((\d+),\s*(\d+)\)", item)
        if not matches:
            matches = re.findall(r"(\d+),\s*(\d+)", item)
        for a, b in matches:
            id1, id2 = int(a), int(b)
            pairs.add((id1, id2) if id1 < id2 else (id2, id1))
    return pairs


def pair_metrics(predicted, gold_pairs):
    # prime-envs oolong_pairs' _pair_metrics, the F1 being the reward, but for think tags stripped
    pred_pairs = parse_pairs(predicted or "")
    if not pred_pairs and not gold_pairs:
        return {"precision": 1.0, "recall": 1.0, "f1": 1.0}
    correct = len(pred_pairs & gold_pairs)
    precision = correct / len(pred_pairs) if pred_pairs else 0.0
    recall = correct / len(gold_pairs) if gold_pairs else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0.0
    return {"precision": precision, "recall": recall, "f1": f1}


# --- answering a question from labels, to tell whether an answer follows from the agent's own labels ---

MONTHS = ["January", "February", "March", "April", "May", "June", "July",
          "August", "September", "October", "November", "December"]


def day(item):
    return datetime.strptime(item[0], "%b %d, %Y").date()


def most(counts, keys=None, second=False):
    """The keys with the highest count, or the second highest, all of them when tied."""
    counts = {k: counts.get(k, 0) for k in (keys if keys is not None else counts)}
    values = sorted(set(counts.values()), reverse=True)
    if second and len(values) < 2:
        return []
    top = values[1 if second else 0]
    return [k for k, v in counts.items() if v == top]


def compare(a, b, phrases):
    return [phrases[0] if a > b else phrases[1] if a < b else phrases[2]]


def considered(question, rows):
    """The indices of the rows an OOLONG-synth question asks to consider."""
    if m := re.search(r"only consider the subset of instances that occur in (\w+) of any year", question):
        month = MONTHS.index(m[1]) + 1
        return [i for i, r in enumerate(rows) if day(r).month == month]
    if m := re.search(r"only consider the subset of instances that occur between (.+?) and (.+?), inclusive", question):
        start, end = dateutil.parser.parse(m[1]).date(), dateutil.parser.parse(m[2]).date()
        return [i for i, r in enumerate(rows) if start <= day(r) <= end]
    if m := re.search(r"only consider the subset of (?:instances that are associated with user IDs|users with IDs) (.+?)\. Among", question):
        users = set(re.findall(r"\d+", m[1]))
        return [i for i, r in enumerate(rows) if r[1] in users]
    return list(range(len(rows)))


def solve(question, rows):
    """
    Answers an OOLONG-synth question from the given rows, all the answers when tied,
    together with whether the answer depends on the labels, or returns `None` for a question this does not know.
    """
    subset = [rows[i] for i in considered(question, rows)]
    labels = Counter(r[3] for r in subset)
    listed = (m := re.search(r"one of the labels: (.+)\.$", question)) and m[1].split(", ")

    if m := re.search(r"how many data points should be classified as label '(.+?)'\?", question):
        return [labels[m[1]]], True
    if m := re.search(r"is label '(.+?)' more common, less common, or the same frequency as label '(.+?)'\?", question):
        return compare(labels[m[1]], labels[m[2]], ["more common than", "less common than", "same frequency as"]), True
    if "which of the labels is the most common?" in question:
        return most(labels, listed), True
    if "which of the labels is the least common?" in question:
        # among the labels listed, the ones never occurring being the least common
        counts = {k: labels.get(k, 0) for k in (listed or labels)}
        return [k for k, v in counts.items() if v == min(counts.values())], True
    if m := re.search(r"which user has the most instances with the label (.+?)\? ", question):
        return [int(u) for u in most(Counter(r[1] for r in subset if r[3] == m[1]))], True
    if m := re.search(r"which user has more instances with the label (.+?): User (\d+) or User (\d+)\?", question):
        with_label = Counter(r[1] for r in subset if r[3] == m[1])
        return [int(u) for u in most(with_label, [m[2], m[3]])], True
    if m := re.search(r"which user is represented (the second )?most often\?", question):
        return [int(u) for u in most(Counter(r[1] for r in subset), second=bool(m[1]))], False
    if m := re.search(r"which date is represented (the second )?most often\?", question):
        return most(Counter(day(r) for r in subset), second=bool(m[1])), False
    if m := re.search(r"how many dates are represented exactly (\d+) times\?", question):
        return [sum(1 for c in Counter(day(r) for r in subset).values() if c == int(m[1]))], False
    if m := re.search(r"was label '(.+?)' more common, less common, or the same frequency before (\S+), as compared to after", question):
        at = date.fromisoformat(m[2])
        before = [r[3] for r in subset if day(r) < at]
        after = [r[3] for r in subset if day(r) > at]
        return compare(before.count(m[1]) / len(before), after.count(m[1]) / len(after),
                       ["more common", "less common", "the same frequency"]), True

    by_month = defaultdict(Counter)
    for r in sorted(subset, key=day):
        by_month[(day(r).year, day(r).month)][r[3]] += 1
    if m := re.search(r"In which month did the label '(.+?) first occur more often than the label '(.+?)'\?", question):
        first = next((k for k, c in by_month.items() if c[m[1]] > c[m[2]]), None)
        return ([f"{MONTHS[first[1] - 1]} {first[0]}"] if first else []), True
    if m := re.search(r"For how many months does the label '(.+?)' occur more frequently than the label '(.+?)'\?", question):
        return [sum(1 for c in by_month.values() if c[m[1]] > c[m[2]])], True
    if m := re.search(r"For how many months is the label '(.+?)' the single most frequently occuring label\?", question):
        return [sum(1 for c in by_month.values() if most(c) == [m[1]])], True
    return None


TREC_LABELS = ["description and abstract concept", "entity", "human being", "numeric value", "location", "abbreviation"]
COUNTS = {"one": 1, "two": 2, "three": 3}


def labels_named(text):
    return [label for label in TREC_LABELS if label in text]


def label_conditions(text):
    """
    The conditions on the labels of one user, e.g. `at least two instances with entity and exactly one instance
    with abbreviation` as `[("at least", 2, "entity"), ("exactly", 1, "abbreviation")]`.
    """
    found, quantifier = [], None
    for said, n, label in re.findall(
            r"(at least |exactly )?(one|two|three) (?:instances? )?with (?:an? )?(.+?)"
            r"(?=,| and (?:at least |exactly )?(?:one|two|three) |$)", text):
        # an elided quantifier, e.g. `at least one with entity and one with abbreviation`, carries over
        quantifier = said.strip() or quantifier
        found.append((quantifier, COUNTS[n], labels_named(label)[0]))
    return found


def solve_pairs(question, rows):
    """The pairs of users an OOLONG-Pairs question asks for, answered from the given rows, or `None` for one this does not know."""
    by_user = defaultdict(list)
    for r in rows:
        by_user[int(r[1])].append(r)
    counts = {user: Counter(r[3] for r in own) for user, own in by_user.items()}
    users = sorted(by_user)

    def holds(user, conditions):
        return all(counts[user][label] >= n if quantifier == "at least" else counts[user][label] == n
                   for quantifier, n, label in conditions)

    if m := re.search(r"where both users have at least one instance with (?:an? )?(.+?)"
                      r"(?:, and all instances that are an? (.+?) for both users must be (before|after) (\w+ \d+, \d{4}))?\. Each",
                      question):
        wanted = labels_named(m[1])
        at = m[4] and datetime.strptime(m[4], "%B %d, %Y").date()

        def fits(user):
            dated = [day(r) for r in by_user[user] if r[3] == m[2]]
            return (any(counts[user][label] for label in wanted)
                    and all(d < at if m[3] == "before" else d > at for d in dated))

        return set(combinations([u for u in users if fits(u)], 2))
    if m := re.search(r"such that one user has (.+?), and the other user has (.+?)\. Each", question):
        one, other = label_conditions(m[1]), label_conditions(m[2])
        return {(a, b) for a, b in combinations(users, 2)
                if holds(a, one) and holds(b, other) or holds(b, one) and holds(a, other)}
    return None


# --- what the agent judged, read from the recorded cogitation ---

# a line keyed by a number, e.g. `17: True`, `[17] True`, `- 17. True` or `ID 17: True`
KEYED = re.compile(r"(?m)^[^\w\n]*(?:[A-Za-z]{1,6}\.?[ \t]+)?(\d+)[^\w'\"(\n]*(.*?)[ \t]*$")


def normalized(text):
    # the agent may collapse whitespace when passing items on
    return re.sub(r"\s+", " ", re.sub(r"^\W+|\s+$", "", text))


def judgments(recording, rows):
    """
    Labels the agent's invoked cogitations gave to the items, by the index of the item in `rows`:
    every invocation prompt listing items keyed by number is matched with the response keyed the same way.
    An item judged more than once keeps its first judgment, unless the agent's scripts reconcile it by its question.
    """
    by_text = defaultdict(list)
    for i, r in enumerate(rows):
        by_text[normalized(r[2])].append(i)
    labels = {r[3].lower(): r[3] for r in rows}

    def item(text):
        # the agent may shorten long instances, so a unique beginning identifies an item too
        if text in by_text:
            return by_text[text]
        starting = [t for t in by_text if len(text) >= 20 and t.startswith(text)]
        return by_text[starting[0]] if len(starting) == 1 else None

    def label(said):
        # a label, or a code it is the only one to begin with, e.g. `F` for formal
        said = said.strip("*`'\". ").lower()
        if said in labels:
            return labels[said]
        beginning = [l for l in labels if said and l.startswith(said)]
        return labels[beginning[0]] if len(beginning) == 1 else None

    judged, codes = {}, {}
    for table in sorted(recording.rglob("cogitation.md")):
        cogitation = table.parent
        responses = re.findall(r"(?m)^\| (\d{3}) \| self `self` ↩ (\d{3}-\d+) \|", table.read_text())
        for response, invocation in sorted(responses):
            prompt = cogitation / f"{invocation}-computer-invocation.md"
            answer = next(cogitation.glob(f"{response}-1-self-text.*"), None)
            if not prompt.exists() or answer is None:
                continue
            # a code the prompt defines for a label, e.g. `ENTY (entity: things)` or `ENTY = entity (things)`,
            # not always the beginning of the label
            legend = {code: labels[label.strip().lower()] for code, label in
                      re.findall(r"\b(\w+) ?(?:\(|= ?)([^:()=,]+)", prompt.read_text())
                      if label.strip().lower() in labels}
            listed = {}
            for key, text in KEYED.findall(prompt.read_text()):
                if (indices := item(normalized(text.split("|| Instance: ")[-1]))) is not None:
                    listed[key] = indices
                elif text.strip().lower() in labels:
                    # a code the prompt defines for a label, e.g. `5: Sports`
                    legend[key] = labels[text.strip().lower()]
            # items of the same text are told apart when every item is keyed by its index,
            # but not when keyed otherwise, e.g. among the distinct questions
            if all(int(key) in indices for key, indices in listed.items()):
                listed = {key: [int(key)] for key in listed}
            codes |= {code: label for code, label in legend.items() if not code.isdigit()}
            for key, said in KEYED.findall(answer.read_text()):
                judgment = legend.get(said.strip("*`'\". ")) or label(said)
                if judgment is not None and key in listed:
                    for i in listed[key]:
                        judged.setdefault(i, judgment)
    # identical questions labeled differently and reconciled in a script, e.g. `"what is hdlc ?" to "ABBR"`
    by_question = defaultdict(list)
    for text, indices in by_text.items():
        by_question[text.lower()] += indices
    for intent in sorted(recording.rglob("*-intent.kts")):
        for text, said in re.findall(r'"([^"\n]+)"\s+to\s+"([^"\n]+)"', intent.read_text()):
            judgment = codes.get(said) or label(said)
            for i in by_question.get(normalized(text).lower(), []) if judgment else []:
                judged[i] = judgment
    return judged


def self_judgments(recording, rows, question, first_line):
    """
    Labels the agent gave to the items itself, in the scripts of its intents, e.g. `mapOf(514 to "formal")`,
    or `listOf("negative", …)` with one label for each item the question considers.
    A key can be the index of the item, its line in the context file, or its position among the items considered,
    counted from 0 or 1: the only reading putting every key among the considered items is taken, none when ambiguous.
    """
    considered_items = considered(question, rows)
    labels = {r[3].lower(): r[3] for r in rows}

    def label(said):
        # a boolean is read only for the labels True and False: otherwise its meaning, e.g. whether `true`
        # says correct or incorrect, is in the name of a variable, and reading it from the answer would bias
        return labels.get(said.lower())

    readings = [
        lambda k: k,
        lambda k: k - first_line,
        lambda k: k - first_line - 1,
        lambda k: considered_items[k] if k < len(considered_items) else None,
        lambda k: considered_items[k - 1] if 0 < k <= len(considered_items) else None,
    ]
    root = next(recording.glob("*/"), None)
    judged = {}
    for intent in sorted(root.glob("*-intent.kts")) if root else []:
        script = intent.read_text()
        keyed = [(int(k), label(v)) for k, v in
                 re.findall(r'\b(\d+)\s+to\s+"?(\w[\w &/-]*?)"?\s*(?:[,)\n]|//)', script)]
        keyed = [(k, v) for k, v in keyed if v is not None]
        if keyed:
            fitting = {tuple(read(k) for k, _ in keyed) for read in readings
                       if all(read(k) in considered_items for k, _ in keyed)}
            if len(fitting) == 1:
                for i, (_, v) in zip(fitting.pop(), keyed):
                    judged.setdefault(i, v)
        for listed in re.findall(r"listOf\(([^()]*)\)", script):
            said = [label(v) for v in re.findall(r'"([^"]*)"', listed)]
            if said and len(said) == len(considered_items) and None not in said:
                for i, v in zip(considered_items, said):
                    judged.setdefault(i, v)
    return judged


def evaluate(result, run_dir, set_name):
    """The result of a cogitation, with its score, agreement and consistency evaluated."""
    task_dir = tasks_dir(set_name) / result["task"]
    task = json.loads((task_dir / "task.json").read_text())
    rows = json.loads((task_dir / "labels.json").read_text())
    answers = gold(task)
    output = result["output"]
    pairs = task["task"] == PAIRS
    if pairs:
        metrics = pair_metrics(output, answers)
        parsed, points = (f"{len(parse_pairs(output))} pairs" if output else None), metrics["f1"]
    else:
        parsed, points = score(answers, task["answer_type"], output) if output else (None, 0.0)
    recording = run_dir / result["run"]
    judged = judgments(recording, rows)
    if not judged:
        context = (task_dir.parents[1] / "contexts" / f"{result['task']}.txt").read_text().split("\n")
        first_line = next(n for n, line in enumerate(context) if line.startswith("Date: "))
        judged = self_judgments(recording, rows, task["question"], first_line)
    agreement = sum(rows[i][3] == label for i, label in judged.items()) / len(judged) if judged else None
    consistent = None
    solved = None if pairs else solve(task["question"], rows)
    if pairs and parsed is not None and len(judged) == len(rows):
        implied = solve_pairs(task["question"], [r[:3] + [judged[i]] for i, r in enumerate(rows)])
        consistent = implied is not None and parse_pairs(output) == implied
    if solved is not None and parsed is not None:
        own = [r[:3] + [judged.get(i, r[3])] for i, r in enumerate(rows)]
        # known only when every item the question considers was judged, unless the answer ignores the labels
        if not solved[1] or all(i in judged for i in considered(task["question"], rows)):
            implied = solve(task["question"], own)[0]
            consistent = bool(implied) and score(implied, task["answer_type"], output)[1] == 1.0
    leaked = any(re.search(r"task\.json|labels\.json", p.read_text()) for p in recording.rglob("*-intent.kts"))
    return result | {
        "expected": f"{len(answers)} pairs" if pairs else " or ".join(map(str, answers)),
        "parsed": parsed,
        "score": points,
        "precision": metrics["precision"] if pairs else None,
        "recall": metrics["recall"] if pairs else None,
        "judged": len(judged),
        "items": len(rows),
        "agreement": agreement,
        "consistent": consistent,
        "leaked": leaked,
    }


def inferences(recording_dir):
    """Totals of the cogitation recorded in the directory, the invoked ones included."""
    recorded = list(recording_dir.glob("*/inferences.json"))
    if not recorded:
        return None
    return json.loads(recorded[0].read_text())["total"]


def enunciated(stdout):
    """The output of the root cogitation, from the `Enunciation` the host prints last."""
    start = stdout.rfind("Enunciation(actant=")
    if start < 0:
        return None, "no output enunciated"
    printed = stdout[start:]
    texts = re.findall(r"Text\(text=(.*?), contentType=", printed, re.DOTALL)
    impediments = re.findall(r"Impediment\(reason=(.*?)\)(?:, |\])", printed, re.DOTALL)
    return ("\n".join(texts) if texts else None), ("; ".join(impediments) or None)


def cogitate(task_dir, run_dir, repeat, timeout, token_budget):
    task = json.loads((task_dir / "task.json").read_text())
    name = f"{task_dir.name}-{repeat}"
    recording_dir = run_dir / name
    recording_dir.mkdir()
    question = task["question"]
    if task["task"] == PAIRS:
        question = f"{PAIRS_PREFIX}{question}\n\n{PAIRS_INSTRUCTIONS}"
    prompt = PROMPT.format(
        context=task_dir.parents[1] / "contexts" / f"{task_dir.name}.txt",
        tokens=task["context_len"] // 1024,
        question=question
    )
    started = time.monotonic()
    try:
        process = subprocess.run(
            [str(AGENT), prompt],
            env=os.environ | {"ANNIE_COGITATIONS_DIR": str(recording_dir)}
                | ({"ANNIE_TOKEN_BUDGET": str(token_budget)} if token_budget else {}),
            capture_output=True, text=True, timeout=timeout
        )
        stdout, failure = process.stdout, (None if process.returncode == 0 else f"exit {process.returncode}")
    except subprocess.TimeoutExpired as e:
        stdout, failure = (e.stdout or b"").decode(errors="replace"), f"timeout after {timeout}s"
    (recording_dir / "stdout.log").write_text(stdout)
    output, impediment = enunciated(stdout)
    totals = inferences(recording_dir)
    return {
        "run": name,
        "task": task_dir.name,
        "repeat": repeat,
        "output": output,
        "impediment": impediment,
        "failure": failure,
        "seconds": round(time.monotonic() - started, 1),
        "inferences": totals["inferences"] if totals else None,
        "tokens": totals["tokens"] if totals else None,
        "total_tokens": totals["total"] if totals else None,
    }


def run(args):
    sets = sorted(p.name for p in SETS_DIR.iterdir()) if SETS_DIR.exists() else []
    set_name = args.set or (sets[0] if len(sets) == 1 else None)
    if set_name not in sets:
        sys.exit(f"no set {set_name}, run `prepare` first" if set_name else f"choose a set with --set: {', '.join(sets)}")
    tasks = sorted(p for p in tasks_dir(set_name).iterdir() if (p / "task.json").exists())
    if args.tasks:
        tasks = [t for t in tasks if any(s in t.name for s in args.tasks)]
    if not tasks:
        sys.exit("no tasks, run `prepare` first")
    if not os.environ.get("ANTHROPIC_MODEL") and not os.environ.get("OPENAI_MODEL"):
        sys.exit("neither ANTHROPIC_MODEL nor OPENAI_MODEL is set")
    subprocess.run([str(ROOT / "gradlew"), "-q", "installDist"], cwd=ROOT, check=True)
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")
    run_dir = RUNS_DIR / (f"{stamp}-{args.label}" if args.label else stamp)
    run_dir.mkdir(parents=True)
    (run_dir / "run.json").write_text(json.dumps({
        "model": os.environ.get("ANTHROPIC_MODEL") or os.environ.get("OPENAI_MODEL"),
        "commit": subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True
        ).stdout.strip(),
        "dirty": bool(subprocess.run(
            ["git", "status", "--porcelain", "src"], cwd=ROOT, capture_output=True, text=True
        ).stdout.strip()),
        "set": set_name,
        "repeats": args.repeats,
        "token_budget": args.token_budget or None,
        "tasks": [t.name for t in tasks],
    }, indent=2) + "\n")
    print(f"recording in {run_dir}")
    lock = threading.Lock()

    def one(job):
        result = cogitate(*job, args.timeout, args.token_budget)
        with lock:
            with open(run_dir / "results.jsonl", "a") as f:
                f.write(json.dumps(result) + "\n")
            evaluated = evaluate(result, run_dir, set_name)
            print(f"{result['run']}: {evaluated['score']:.2f} "
                  f"(expected {evaluated['expected']}, got {evaluated['parsed']})", flush=True)

    jobs = [(t, run_dir, r) for r in range(1, args.repeats + 1) for t in tasks]
    with ThreadPoolExecutor(args.parallel) as pool:
        list(pool.map(one, jobs))
    report(argparse.Namespace(run_dir=run_dir))


def report(args):
    run_dir = Path(args.run_dir)
    meta = json.loads((run_dir / "run.json").read_text())
    set_name = meta.get("set", FIRST_SET)
    lines = (run_dir / "results.jsonl").read_text().splitlines()
    results = [evaluate(json.loads(l), run_dir, set_name) for l in lines]
    print(f"\n{run_dir.name}: {meta['model']} at {meta['commit'][:7]}{' (dirty)' if meta['dirty'] else ''}"
          f" on the set {set_name}")
    if any(r["precision"] is not None for r in results):
        print("  score: F1 of the pairs, as prime-envs; agree: items labeled as the dataset does, of those judged;")
    else:
        print("  score: as OOLONG, any tied answer counted; agree: items labeled as the dataset does, of those judged;")
    print("  own: whether the answer follows from the agent's own labels, '-' when not all items were judged\n")
    width = max(len(r["task"]) for r in results)
    for task in sorted({r["task"] for r in results}):
        runs = sorted((r for r in results if r["task"] == task), key=lambda r: r["repeat"])
        print(f"  {task}  expected {runs[0]['expected']}")
        for r in runs:
            got = r["parsed"] if r["parsed"] is not None else (r["failure"] or f"impeded: {r['impediment']}")
            agree = f"{r['agreement']:.1%} of {r['judged']}" if r["agreement"] is not None else "-"
            own = {True: "yes", False: "NO", None: "-"}[r["consistent"]]
            leak = "  LEAKED: an intent read the gold answers" if r["leaked"] else ""
            recall = f"  precision {r['precision']:.2f}  recall {r['recall']:.2f}" if r["precision"] is not None else ""
            print(f"    {r['repeat']}  score {r['score']:.2f}{recall}  agree {agree:>13}  own {own:>3}  got {got}{leak}")
    mean = sum(r["score"] for r in results) / len(results)
    agreements = [r["agreement"] for r in results if r["agreement"] is not None]
    consistency = [r["consistent"] for r in results if r["consistent"] is not None]
    tokens = [r["total_tokens"] for r in results if r["total_tokens"] is not None]
    print(f"\n  mean score {mean:.3f} over {len(results)} runs")
    if agreements:
        print(f"  mean agreement {sum(agreements) / len(agreements):.1%} over {len(agreements)} runs judging items")
    if consistency:
        print(f"  consistent with own labels in {sum(consistency)} of {len(consistency)} runs judging every item")
    print(f"  {sum(tokens) / max(len(tokens), 1):,.0f} tokens per run on average")


def score_recorded(args):
    # a cogitation recorded by `./gradlew run` holds its output only in the intent which enunciated it
    task = json.loads((Path(args.task) / "task.json").read_text())
    intents = sorted(Path(args.cogitation_dir).glob("*-self-intent.kts"))
    found = re.findall(r'agency\.enunciate\("(.*?)"\)', intents[-1].read_text()) if intents else []
    if not found:
        sys.exit("no literal enunciation found in the last intent")
    parsed, points = score(gold(task), task["answer_type"], found[-1])
    print(f"expected {' or '.join(map(str, gold(task)))}, got {parsed}: score {points:.3f}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(required=True)
    p = commands.add_parser("prepare")
    p.add_argument("--context-len", type=int, default=32768)
    p.add_argument("--per-dataset", type=int, default=1, help="tasks per dataset, each of another kind")
    p.add_argument("--pairs", action="store_true", help="the 20 OOLONG-Pairs questions instead of OOLONG-synth tasks")
    p.add_argument("--set", help="the name of the set, by default from the context length and tasks per dataset")
    p.set_defaults(command=prepare)
    p = commands.add_parser("run")
    p.add_argument("--set", help="the set of tasks to run, required when there are several")
    p.add_argument("--repeats", type=int, default=2)
    p.add_argument("--parallel", type=int, default=4)
    p.add_argument("--timeout", type=int, default=1800, help="seconds per cogitation")
    p.add_argument("--token-budget", type=int, default=3_000_000,
                   help="tokens the cogitation of a task may spend, the invoked ones included, 0 for no limit")
    p.add_argument("--tasks", nargs="*", help="substrings of task names to run, all by default")
    p.add_argument("--label", help="appended to the name of the run directory, e.g. what changed")
    p.set_defaults(command=run)
    p = commands.add_parser("report")
    p.add_argument("run_dir")
    p.set_defaults(command=report)
    p = commands.add_parser("score")
    p.add_argument("cogitation_dir")
    p.add_argument("task", help="the task directory, e.g. evals/oolong/data/tasks/<name>")
    p.set_defaults(command=score_recorded)
    args = parser.parse_args()
    args.command(args)
