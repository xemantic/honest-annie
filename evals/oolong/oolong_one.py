# Extracts a single OOLONG-synth task for a honest-annie test run.
# pip install datasets
import ast
from pathlib import Path

from datasets import load_dataset

CONTEXT_LEN = 131072          # try 1024 first for a smoke test
TASK = "TASK_TYPE.NUMERIC_ONE_CLASS"  # exact counting: the hardest type for aggregation
OUT = Path(__file__).resolve().parent / "data" / "one"

ds = load_dataset("oolongbench/oolong-synth", split="test")
row = next(r for r in ds if r["context_len"] == CONTEXT_LEN and r["task"] == TASK)

OUT.mkdir(parents=True, exist_ok=True)
with open(OUT / "context.txt", "w") as f:
    f.write(row["context_window_text"])
with open(OUT / "question.txt", "w") as f:
    f.write(row["question"])
with open(OUT / "answer.txt", "w") as f:
    f.write(str(ast.literal_eval(row["answer"])[0]))  # stored as a list literal, e.g. "[1366]"

print(f"id={row['id']} dataset={row['dataset']} task={row['task']}")
print(f"question: {row['question']}")
print(f"expected: {row['answer']} ({row['answer_type']})")
