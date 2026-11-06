#!/usr/bin/env bash
# Runs honest-annie in FrontierHarness Eval (https://frontierharness.org), see README.md.
#
#   evals/frontierharness/frontierharness.sh setup       # benchmark workspace and prerequisites
#   evals/frontierharness/frontierharness.sh provision   # golden checkpoint of the pushed HEAD
#   evals/frontierharness/frontierharness.sh smoke       # one task of each suite
#   evals/frontierharness/frontierharness.sh run         # the published 30 tasks
#   evals/frontierharness/frontierharness.sh report RUN_ID
#
# Settings, from the environment:
#   PROVIDER    who serves Kimi K3: fireworks (default), moonshot, openrouter, together
#   COMMIT      the commit of honest-annie to evaluate, HEAD by default, which must be pushed
#   CHECKPOINT  fh-golden-honest-annie-<commit> by default
#   RUN_ID      <date>-honest-annie-<commit>[-smoke] by default
# Any further arguments are passed on to the script of the eval the command runs.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$HERE/../.." && pwd)
REPO_URL=https://github.com/xemantic/honest-annie
# The eval at the commit this runner was written against, see README.md.
EVAL_REPO=https://github.com/frontier-harness-eval/eval.git
EVAL_COMMIT=${EVAL_COMMIT:-e837a70bd6beb4e72eeeda62dd06e3bd34f6cb63}
WORKSPACE="$HERE/data/eval"
FH="$WORKSPACE/skills/frontierharness-eval/scripts"
HARNESS=honest-annie
RUNNER_CMD='/work/honest-annie/run-task.sh {suite} {task} {model} {jobs}'

PROVIDER=${PROVIDER:-fireworks}
COMMIT=$(git -C "$REPO_ROOT" rev-parse "${COMMIT:-HEAD}")
SHORT=${COMMIT:0:7}
CHECKPOINT=${CHECKPOINT:-fh-golden-$HARNESS-$SHORT}

die() { echo "error: $*" >&2; exit 1; }

require_workspace() {
  [ -d "$FH" ] || die "no benchmark workspace in $WORKSPACE, run: $0 setup"
}

setup() {
  for tool in runta jq node python3 git; do
    command -v "$tool" >/dev/null || die "$tool is not installed, see README.md"
  done
  node -e 'process.exit(parseInt(process.versions.node) >= 18 ? 0 : 1)' || die "node >= 18 is needed"
  runta checkpoint ls >/dev/null || die "the runta CLI is not authenticated, run: runta login"

  if [ ! -d "$WORKSPACE/.git" ]; then
    mkdir -p "$(dirname "$WORKSPACE")"
    git clone --quiet "$EVAL_REPO" "$WORKSPACE"
  fi
  git -C "$WORKSPACE" fetch --quiet origin
  git -C "$WORKSPACE" checkout --quiet "$EVAL_COMMIT"

  # The first-call cache reads, which the first-cold cost reprices, come from a per-harness log parser.
  # honest-annie writes its calls in the format of the hermes one, so it only needs registering.
  local details="$FH/usage_details.py"
  if ! grep -q '"honest-annie"' "$details"; then
    python3 - "$details" <<'PATCH'
import sys
path = sys.argv[1]
source = open(path).read()
anchor = '    "hermes": ("hermes-calls.jsonl", _hermes),\n'
if anchor not in source:
    sys.exit(f"no hermes entry to register honest-annie beside in {path}")
open(path, "w").write(source.replace(
    anchor, anchor + '    "honest-annie": ("honest-annie-calls.jsonl", _hermes),\n'
))
PATCH
  fi
  git -C "$WORKSPACE" diff --stat
  echo "benchmark workspace ready: $WORKSPACE (eval $EVAL_COMMIT)" >&2
}

provision() {
  require_workspace
  git -C "$REPO_ROOT" fetch --quiet origin
  [ -n "$(git -C "$REPO_ROOT" branch -r --contains "$COMMIT")" ] \
    || die "commit $SHORT is not pushed to origin, the runtime clones it from $REPO_URL"
  # The adapters come from the clone at the commit, the install script from this working tree.
  git -C "$REPO_ROOT" diff --quiet "$COMMIT" -- evals/frontierharness \
    || echo "warning: evals/frontierharness differs from $SHORT; the checkpoint uses the adapters of $SHORT" >&2
  local build_runtime="fh-build-$HARNESS-$SHORT"
  # The eval sets the secret rule right after creating the runtime, retrying 3 times 2s apart,
  # while a new runtime can take longer to be ready, failing with "runtime provisioning is not ready".
  (cd "$WORKSPACE" \
    && FH_TRANSPORT_ATTEMPTS=${FH_TRANSPORT_ATTEMPTS:-30} FH_RETRY_DELAY=${FH_RETRY_DELAY:-5} \
    bash "$FH/provision-golden-checkpoint.sh" \
    --runtime "$build_runtime" \
    --checkpoint "$CHECKPOINT" \
    --harness "$HARNESS" \
    --provider "$PROVIDER" \
    --repo "$REPO_URL" \
    --commit "$COMMIT" \
    --cpus 4 --memory 8192 --disk-size-gib 50 --keep-runtime \
    --install-script "$HERE/install-harness.sh" \
    "$@")
  # The provider key must stay in the egress proxy, never reaching the runtime nor the checkpoint.
  local secret
  # shellcheck disable=SC1091
  secret=${SECRET_NAME:-$(source "$FH/providers.sh" && resolve_provider "$PROVIDER" && echo "$PROVIDER_SECRET")}
  [ -n "$secret" ] || die "set SECRET_NAME to the --secret-name of the custom provider"
  # Recent runtimes do not expose the stub at all, the adapter then sending one, see honest_annie_adapter.py.
  runta exec "$build_runtime" -- sh -lc "test -z \"\$$secret\" || test \"\$$secret\" = runta-secret-stub" \
    || die "$secret holds more than a stub in $build_runtime; inspect it before running any trial"
  runta exec "$build_runtime" -- sh -lc 'cat /work/honest-annie/version.txt'
  runta rm "$build_runtime"
  echo "checkpoint $CHECKPOINT ready" >&2
}

trials() {
  local run_id=$1 tasks=$2
  shift 2
  require_workspace
  (cd "$WORKSPACE" && bash "$FH/run-trials.sh" \
    --checkpoint "$CHECKPOINT" \
    --harness "$HARNESS" \
    --provider "$PROVIDER" \
    --run-id "$run_id" \
    --tasks "$tasks" \
    --cmd "$RUNNER_CMD" \
    --out runs \
    "$@")
  echo "trials in $WORKSPACE/runs/$run_id, next: $0 report $run_id" >&2
}

report() {
  local run_id=${1:?usage: $0 report RUN_ID}
  shift
  require_workspace
  (cd "$WORKSPACE" \
    && node "$FH/normalize-results.mjs" --run "runs/$run_id" --label "Honest Annie ($SHORT)" "$@" \
    && node "$FH/generate-chart.mjs" --run "runs/$run_id" \
    && node "$FH/build-report.mjs" --run "runs/$run_id")
  echo "report: $WORKSPACE/runs/$run_id/report/REPORT.md" >&2
}

command=${1:-}
[ $# -gt 0 ] && shift
case "$command" in
  setup) setup "$@" ;;
  provision) provision "$@" ;;
  smoke) trials "${RUN_ID:-$(date +%Y-%m-%d)-$HARNESS-$SHORT-smoke}" "$WORKSPACE/skills/frontierharness-eval/smoke-tasks.txt" "$@" ;;
  run) trials "${RUN_ID:-$(date +%Y-%m-%d)-$HARNESS-$SHORT}" "$WORKSPACE/tasks" "$@" ;;
  report) report "$@" ;;
  *) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
