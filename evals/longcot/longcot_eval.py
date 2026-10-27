# Evaluates honest-annie on a fixed sample of LongCoT-mini tasks, scored like Prime Agent's longcot_mini environment.
# needs uv, which runs the verifier of the longcot package in an environment of its own
#
#   python evals/longcot/longcot_eval.py prepare [--per-domain 10]   # select a set of tasks, once
#   python evals/longcot/longcot_eval.py run [--set easy-10] [--repeats 2] [--label x]  # needs ANTHROPIC_MODEL or OPENAI_MODEL
#   python evals/longcot/longcot_eval.py report <run-dir>            # evaluate a run again, from data/runs
#
# Each set of tasks is kept in data/sets/<set> and never changed once prepared,
# so that every run, recording the set it used, can be evaluated again against the same gold answers.
import argparse
import json
import os
import random
import re
import subprocess
import sys
import threading
import time
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EVAL_DIR = Path(__file__).resolve().parent / "data"
SETS_DIR = EVAL_DIR / "sets"
RUNS_DIR = EVAL_DIR / "runs"
AGENT = ROOT / "build" / "install" / "honest-annie" / "bin" / "honest-annie"
VERIFY = Path(__file__).resolve().parent / "longcot_verify.py"

# the commit of LongHorizonReasoning/longcot which prime-envs' longcot_mini depends on
LONGCOT_COMMIT = "6a569ab"
DATA_URL = "https://raw.githubusercontent.com/LongHorizonReasoning/longcot/{commit}/src/data/{domain}/{difficulty}.json"
DOMAINS = ("logic", "cs", "chemistry", "chess", "math")
DIFFICULTY = "easy"
# easy-math questions flagged as wrong or impossible upstream, which longcot_mini drops by default
BROKEN_EASY_MATH_IDS = frozenset({
    "2", "7", "17", "18", "20", "27", "30", "32", "33", "38", "43",
    "44", "45", "46", "49", "50", "57", "58", "65", "66", "206",
})

# longcot_mini's instructions, but for the answer being enunciated rather than written to /workspace/answer.txt
INSTRUCTIONS = (
    "When you have the answer, enunciate it — and ONLY your final answer, in the exact format the "
    "question requests."
)

# the verifier falls back to a Gemini judge for math and chemistry when a key is set,
# which longcot_mini's scoring, its network allowing only package hosts, never reaches
JUDGE_KEYS = ("GEMINI_API_KEY", "GOOGLE_API_KEY")


def tasks_dir(set_name):
    return SETS_DIR / set_name / "tasks"


def load_questions(domain):
    """The questions of a domain at the difficulty longcot_mini takes, as bundled in the longcot package."""
    url = DATA_URL.format(commit=LONGCOT_COMMIT, domain=domain, difficulty=DIFFICULTY)
    with urllib.request.urlopen(url) as response:
        questions = json.load(response)["questions"]
    return [q for q in questions if not (domain == "math" and str(q["question_id"]) in BROKEN_EASY_MATH_IDS)]


def template(question):
    return (question.get("problem") or {}).get("template", "")


def sample(questions, n, seed=0):
    """
    `n` of the questions, each template taking a share proportional to its size:
    the questions of every template are shuffled, then taken in the order of their position within the template
    relative to its size, so that any `n` spreads over the templates as evenly as their sizes allow.
    """
    by_template = defaultdict(list)
    for q in questions:
        by_template[template(q)].append(q)
    ranked = []
    for name, own in sorted(by_template.items()):
        own = sorted(own, key=lambda q: str(q["question_id"]))
        random.Random(f"{seed}-{name}").shuffle(own)
        ranked += [((k + 0.5) / len(own), name, q) for k, q in enumerate(own)]
    return [q for _, _, q in sorted(ranked, key=lambda r: r[:2])[:n]]


def task_name(domain, question):
    # the template, unless the id begins with it already, e.g. `HM_easy_1`
    question_id = str(question["question_id"])
    named = question_id if question_id.startswith(template(question)) else f"{template(question)}-{question_id}"
    return re.sub(r"[^\w.-]", "_", f"{domain}-{named}")


def prepare(args):
    set_name = args.set or f"{DIFFICULTY}-{args.per_domain}"
    if tasks_dir(set_name).exists():
        sys.exit(f"the set {set_name} is already prepared, and runs might depend on it; name another with --set")
    sizes = {}
    for domain in DOMAINS:
        questions = load_questions(domain)
        sizes[domain] = len(questions)
        for q in sample(questions, args.per_domain, args.seed):
            name = task_name(domain, q)
            task_dir = tasks_dir(set_name) / name
            task_dir.mkdir(parents=True)
            task = {
                "question_id": str(q["question_id"]), "domain": domain, "difficulty": DIFFICULTY,
                "template": template(q), "prompt": q["prompt"], "problem": q.get("problem"), "answer": q.get("answer"),
            }
            (task_dir / "task.json").write_text(json.dumps(task, indent=2, ensure_ascii=False) + "\n")
            print(f"{name}: {len(q['prompt'])} characters")
    # the domain sizes weigh the domain means into an estimate for the whole of longcot_mini
    (SETS_DIR / set_name / "set.json").write_text(json.dumps({
        "longcot": LONGCOT_COMMIT, "difficulty": DIFFICULTY, "per_domain": args.per_domain, "seed": args.seed,
        "domain_sizes": sizes,
    }, indent=2) + "\n")
    print(f"prepared the set {set_name}, of {sum(sizes.values())} tasks in longcot_mini")


def verify(task, output, answer_file):
    """The verifier's `correct`, `component_match_fraction` and `any_list_item_matches` for the output."""
    answer_file.write_text(output or "")
    process = subprocess.run(
        ["uv", "run", "--quiet", "--script", str(VERIFY), str(answer_file),
         task["question_id"], task["domain"], task["difficulty"], task["prompt"],
         json.dumps(task["problem"], ensure_ascii=False), json.dumps(task["answer"], ensure_ascii=False), "1"],
        env={k: v for k, v in os.environ.items() if k not in JUDGE_KEYS},
        capture_output=True, text=True
    )
    if process.returncode != 0:
        raise RuntimeError(f"the verifier failed: {process.stderr.strip()[-500:]}")
    correct, components, any_item = map(float, process.stdout.strip().splitlines())
    return correct, components, any_item


# the gold answers are bundled in the longcot package and the data of this eval, which an intent can reach
LEAKS = re.compile(r"longcot|LongHorizonReasoning|task\.json")


def leaked(recording):
    return any(LEAKS.search(p.read_text()) for p in recording.rglob("*-intent.kts"))


def evaluate(result, run_dir, set_name):
    """The result of a cogitation, with its score evaluated."""
    task = json.loads((tasks_dir(set_name) / result["task"] / "task.json").read_text())
    recording = run_dir / result["run"]
    correct, components, any_item = verify(task, result["output"], recording / "answer.txt")
    return result | {
        "domain": task["domain"],
        "template": task["template"],
        "score": correct,
        "components": components,
        "any_item": any_item,
        "leaked": leaked(recording),
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
    prompt = f"{task['prompt']}\n\n{INSTRUCTIONS}"
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
        got = "correct" if evaluated["score"] else (result["failure"] or result["impediment"] or "wrong")
        print(f"{result['run']}: {evaluated['score']:.0f} ({got})", flush=True)

    jobs = [(t, run_dir, r) for r in range(1, args.repeats + 1) for t in tasks]
    with ThreadPoolExecutor(args.parallel) as pool:
        list(pool.map(one, jobs))
    report(argparse.Namespace(run_dir=run_dir))


def mean(values):
    return sum(values) / len(values) if values else None


def estimate(results, domain_sizes):
    """The mean score of each domain weighed by the domain's size in longcot_mini, as if every task were run."""
    by_domain = {d: mean([r["score"] for r in results if r["domain"] == d]) for d in domain_sizes}
    weighed = {d: n for d, n in domain_sizes.items() if by_domain[d] is not None}
    return sum(by_domain[d] * n for d, n in weighed.items()) / sum(weighed.values()) if weighed else None


def report(args):
    run_dir = Path(args.run_dir)
    meta = json.loads((run_dir / "run.json").read_text())
    set_name = meta["set"]
    domain_sizes = json.loads((SETS_DIR / set_name / "set.json").read_text())["domain_sizes"]
    lines = (run_dir / "results.jsonl").read_text().splitlines()
    results = [evaluate(json.loads(l), run_dir, set_name) for l in lines]
    print(f"\n{run_dir.name}: {meta['model']} at {meta['commit'][:7]}{' (dirty)' if meta['dirty'] else ''}"
          f" on the set {set_name}")
    print("  score: correct as longcot_mini's verifier judges, without the Gemini fallback;"
          " components: of a math answer's parts, the ones matching\n")
    for task in sorted({r["task"] for r in results}):
        runs = sorted((r for r in results if r["task"] == task), key=lambda r: r["repeat"])
        print(f"  {task}")
        for r in runs:
            failed = r["failure"] or (f"impeded: {r['impediment']}" if r["impediment"] else None)
            got = failed or (r["output"] or "").strip().replace("\n", " ")[:80]
            components = f"  components {r['components']:.2f}" if r["domain"] == "math" else ""
            leak = "  LEAKED: an intent reached the gold answers" if r["leaked"] else ""
            print(f"    {r['repeat']}  score {r['score']:.0f}{components}  got {got}{leak}")
    print()
    for domain in domain_sizes:
        own = [r["score"] for r in results if r["domain"] == domain]
        if own:
            print(f"  {domain:<10} {mean(own):.3f} over {len(own)} runs")
    tokens = [r["total_tokens"] for r in results if r["total_tokens"] is not None]
    print(f"\n  mean score {mean([r['score'] for r in results]):.3f} over {len(results)} runs")
    print(f"  estimated for all {sum(domain_sizes.values())} tasks of longcot_mini,"
          f" the domains weighed by size: {estimate(results, domain_sizes):.3f}")
    print(f"  {sum(tokens) / max(len(tokens), 1):,.0f} tokens per run on average")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(required=True)
    p = commands.add_parser("prepare")
    p.add_argument("--per-domain", type=int, default=10, help="tasks per domain, spread over its templates")
    p.add_argument("--seed", type=int, default=0)
    p.add_argument("--set", help="the name of the set, by default from the difficulty and tasks per domain")
    p.set_defaults(command=prepare)
    p = commands.add_parser("run")
    p.add_argument("--set", help="the set of tasks to run, required when there are several")
    p.add_argument("--repeats", type=int, default=2)
    p.add_argument("--parallel", type=int, default=4)
    p.add_argument("--timeout", type=int, default=3600, help="seconds per cogitation")
    p.add_argument("--token-budget", type=int, default=3_000_000,
                   help="tokens the cogitation of a task may spend, the invoked ones included, 0 for no limit")
    p.add_argument("--tasks", nargs="*", help="substrings of task names to run, all by default")
    p.add_argument("--label", help="appended to the name of the run directory, e.g. what changed")
    p.set_defaults(command=run)
    p = commands.add_parser("report")
    p.add_argument("run_dir")
    p.set_defaults(command=report)
    args = parser.parse_args()
    args.command(args)
