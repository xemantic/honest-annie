#!/usr/bin/env bash
# Runs honest-annie in FrontierHarness Eval on this host, with no Runta, against any OpenAI-compatible endpoint.
#
#   evals/frontierharness/local.sh setup             # eval, DeepSWE, Harbor, Pier, and the distribution
#   evals/frontierharness/local.sh smoke             # one task of each suite
#   evals/frontierharness/local.sh run [TASKS]       # the published 30 tasks, or a list file of suite/task ids
#   evals/frontierharness/local.sh summary RUN_ID
#
# Each task runs in the Docker containers of Harbor or Pier, Annie inside them,
# reaching only the endpoint: Harbor gets it as the sole --allow-agent-host of an air-gapped task,
# Pier from the network_allowlist of the adapter.
#
# Settings, from the environment:
#   OPENAI_BASE_URL, OPENAI_MODEL, OPENAI_API_KEY   the endpoint and model, required
#   ANTHROPIC_MODEL, ANTHROPIC_API_KEY              Claude instead, when the model is set
#   ANNIE_PARALLELISM, ANNIE_TOKEN_BUDGET, ...      passed on to Annie, see honest_annie_adapter.py
#   ANNIE_CONSTITUTION  a constitution template on this host, uploaded into each task, e.g. a variant to compare
#   ANNIE_TIME_LIMIT    seconds Annie may spend on each task, by default the agent timeout of a DeepSWE task
#                       less ANNIE_TIME_MARGIN (300), so that she is told how much is left before the runner kills her
#   RUN_ID       <date>-honest-annie-<model>[-smoke] by default
#   TRIAL_TIMEOUT  seconds per task, 10800 by default
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$HERE/../.." && pwd)
DATA="$HERE/data"
WORKSPACE="$DATA/eval"
DEEP_SWE="$DATA/deep-swe"
FH="$WORKSPACE/skills/frontierharness-eval/scripts"
# the pins of frontierharness.sh and of the eval's provision-golden-checkpoint.sh
EVAL_COMMIT=${EVAL_COMMIT:-e837a70bd6beb4e72eeeda62dd06e3bd34f6cb63}
DEEP_SWE_REF=435ee89ec2f2e2289f33b0da4f992f0b7b7266b9
HARBOR_PIN="harbor==0.22.0"
PIER_PIN="datacurve-pier==0.3.1"
HARNESS=honest-annie
TRIAL_TIMEOUT=${TRIAL_TIMEOUT:-10800}
JDK_FEATURE=${JDK_FEATURE:-25}
export PATH="$HOME/.local/bin:$PATH"

die() { echo "error: $*" >&2; exit 1; }

require_endpoint() {
  if [ -n "${ANTHROPIC_MODEL:-}" ]; then
    : "${ANTHROPIC_API_KEY:?set ANTHROPIC_API_KEY}"
    MODEL=$ANTHROPIC_MODEL ROUTE="anthropic/$ANTHROPIC_MODEL" BASE_URL=${ANTHROPIC_BASE_URL:-https://api.anthropic.com}
  else
    : "${OPENAI_BASE_URL:?set OPENAI_BASE_URL}" "${OPENAI_MODEL:?set OPENAI_MODEL}" "${OPENAI_API_KEY:?set OPENAI_API_KEY}"
    MODEL=$OPENAI_MODEL ROUTE="openai/$OPENAI_MODEL" BASE_URL=$OPENAI_BASE_URL
    export ANNIE_OPENAI_BASE_URL=$OPENAI_BASE_URL ANNIE_OPENAI_MODEL=$OPENAI_MODEL
  fi
  docker info >/dev/null 2>&1 || die "docker is not reachable by $(id -un), see README.md"
  [ -f "$DATA/honest-annie/honest-annie-runtime.tar.gz" ] || die "no distribution, run: $0 setup"
}

setup() {
  for tool in git jq python3 curl; do command -v "$tool" >/dev/null || die "$tool is not installed"; done
  command -v uv >/dev/null || curl -LsSf https://astral.sh/uv/install.sh | sh
  mkdir -p "$DATA"
  [ -d "$WORKSPACE/.git" ] || git clone --quiet https://github.com/frontier-harness-eval/eval.git "$WORKSPACE"
  git -C "$WORKSPACE" checkout --quiet "$EVAL_COMMIT"
  [ -d "$DEEP_SWE/.git" ] || git clone --quiet https://github.com/datacurve-ai/deep-swe "$DEEP_SWE"
  git -C "$DEEP_SWE" checkout --quiet "$DEEP_SWE_REF"
  uv tool install --quiet "$HARBOR_PIN"
  uv tool install --quiet "$PIER_PIN"

  # the same registration of the usage parser as frontierharness.sh setup
  grep -q '"honest-annie"' "$FH/usage_details.py" || sed -i \
    's|^    "hermes": ("hermes-calls.jsonl", _hermes),$|&\n    "honest-annie": ("honest-annie-calls.jsonl", _hermes),|' \
    "$FH/usage_details.py"
  grep -q '"honest-annie"' "$FH/usage_details.py" || die "could not register honest-annie in $FH/usage_details.py"

  # The distribution of the working tree, its runtime jlinked from the JVM running Gradle.
  # Temurin, like in install-harness.sh, as a distro JDK may need a glibc newer than the task images have,
  # e.g. GLIBC_2.38 of Ubuntu 24.04, missing from the DeepSWE ones.
  local jdk="$DATA/temurin-jdk-$JDK_FEATURE"
  if [ ! -x "$jdk/bin/java" ]; then
    mkdir -p "$jdk"
    curl -fsSL "https://api.adoptium.net/v3/binary/latest/$JDK_FEATURE/ga/linux/x64/jdk/hotspot/normal/eclipse" \
      | tar -xz -C "$jdk" --strip-components=1
  fi
  (cd "$REPO_ROOT" && JAVA_HOME="$jdk" ./gradlew --no-daemon --quiet runtimeDistTar)
  local out="$DATA/honest-annie"
  rm -rf "$out" && mkdir -p "$out"
  cp "$REPO_ROOT"/build/distributions/honest-annie-runtime-*-linux-*.tar.gz "$out/honest-annie-runtime.tar.gz"
  printf '%s (%s)\n' "$(git -C "$REPO_ROOT" describe --tags --always --dirty 2>/dev/null || echo unknown)" \
    "$(git -C "$REPO_ROOT" rev-parse HEAD)" > "$out/version.txt"
  local tools site
  tools=$(uv tool dir)
  for tool in harbor datacurve-pier; do
    site=$("$tools/$tool/bin/python" -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')
    echo "$HERE" > "$site/honest_annie.pth"
  done
  "$tools/harbor/bin/python" -c 'import honest_annie_harbor'
  "$tools/datacurve-pier/bin/python" -c 'import honest_annie_pier'
  echo "ready: $(cat "$out/version.txt")" >&2
}

# The command of one task, the default templates of run-trials.sh with honest-annie named as the agent,
# and without the Runta CA overlay, as no egress terminates TLS here.
task_command() {
  local suite=$1 task=$2 jobs=$3 model=$ROUTE host
  host=$(python3 -c 'import sys, urllib.parse; print(urllib.parse.urlparse(sys.argv[1]).hostname)' "$BASE_URL")
  case "$suite" in
    terminal-bench)
      echo harbor run -d terminal-bench@2.0 -i "$task" -a honest_annie_harbor:HonestAnnie -m "$model" \
        --jobs-dir "$jobs" --allow-agent-host "$host" -r 2 -y ;;
    datacurve)
      echo pier run -p "$DEEP_SWE/tasks/$task" --agent-import-path honest_annie_pier:HonestAnnie \
        --model "$model" --jobs-dir "$jobs" ;;
    *) die "no runner for suite '$suite'" ;;
  esac
}

# Annie's own time limit for a task, short of the agent timeout of its runner, none for a suite whose timeout is not known here.
time_limit() {
  local suite=$1 task=$2
  [ -n "$FIXED_TIME_LIMIT" ] && { echo "$FIXED_TIME_LIMIT"; return; }
  [ "$suite" = datacurve ] || return 0
  python3 -I -c 'import sys, tomllib
timeout = tomllib.load(open(sys.argv[1], "rb")).get("agent", {}).get("timeout_sec")
print(int(timeout) - int(sys.argv[2]) if timeout else "")' "$DEEP_SWE/tasks/$task/task.toml" "${ANNIE_TIME_MARGIN:-300}"
}

trials() {
  local run_id=$1 tasks=$2 list entries=() total=0 passed=0
  require_endpoint
  if [ -d "$tasks" ]; then
    list=$(for toml in "$tasks"/*/task.toml; do
      awk -F'"' '/^\[/ { in_task = ($0 == "[task]") } in_task && /^name *=/ { print $2; exit }' "$toml"
    done | sort -u)
  else
    list=$(sed 's/#.*//; s/^ *//; s/ *$//' "$tasks" | grep -v '^$')
  fi
  mapfile -t entries <<< "$list"

  # what the adapter reads from the environment, see honest_annie_adapter.py and annie.env
  set -a
  # shellcheck disable=SC1091
  . "$HERE/annie.env"
  set +a
  export ANNIE_DIST="$DATA/honest-annie/honest-annie-runtime.tar.gz"
  export ANNIE_VERSION_FILE="$DATA/honest-annie/version.txt"
  export ANNIE_EGRESS_CA=/nonexistent
  FIXED_TIME_LIMIT=${ANNIE_TIME_LIMIT:-}

  local run_dir="$WORKSPACE/runs/$run_id"
  mkdir -p "$run_dir/trials"
  jq -n --arg model "$MODEL" --arg base "$BASE_URL" --arg version "$(cat "$ANNIE_VERSION_FILE")" \
    --arg harbor "$(harbor --version 2>&1 | tail -1)" --arg pier "$(pier --version 2>&1 | tail -1)" \
    --arg parallelism "${ANNIE_PARALLELISM:-}" --arg budget "${ANNIE_TOKEN_BUDGET:-}" --arg parallel_intents "${ANNIE_PARALLEL_INTENTS:-}" \
    --arg constitution "${ANNIE_CONSTITUTION:-}" --arg time_limit "${FIXED_TIME_LIMIT:-agent timeout - ${ANNIE_TIME_MARGIN:-300}}" \
    --arg constitution_sha "$([ -n "${ANNIE_CONSTITUTION:-}" ] && sha256sum "$ANNIE_CONSTITUTION" | cut -c1-16)" \
    '{harness:"honest-annie", runner:"local.sh", model:$model, base_url:$base, harness_version:$version,
      harbor_version:$harbor, pier_version:$pier, annie_parallelism:$parallelism, annie_token_budget:$budget,
      annie_time_limit:$time_limit, annie_constitution:$constitution, annie_constitution_sha256:$constitution_sha,
      annie_parallel_intents:$parallel_intents}' \
      > "$run_dir/manifest.json"

  for entry in "${entries[@]}"; do
    [[ "$entry" =~ ^[a-zA-Z0-9_-]+/[a-zA-Z0-9_-]+$ ]] || die "invalid task id: $entry"
    local suite=${entry%%/*} task=${entry#*/} slug trial_dir command started exit_code=0
    slug=$(echo "$entry" | tr '/' '-')
    trial_dir="$run_dir/trials/$slug"
    total=$((total + 1))
    if [ -f "$trial_dir/trial.json" ] && jq -e '.status == "success" or .status == "failure"' "$trial_dir/trial.json" >/dev/null; then
      echo "=== [$entry] already scored, skipping" >&2
      jq -e '.success == true' "$trial_dir/trial.json" >/dev/null && passed=$((passed + 1))
      continue
    fi
    rm -rf "$trial_dir" && mkdir -p "$trial_dir"
    command=$(task_command "$suite" "$task" "$trial_dir/jobs")
    ANNIE_TIME_LIMIT=$(time_limit "$suite" "$task")
    if [ -n "$ANNIE_TIME_LIMIT" ]; then export ANNIE_TIME_LIMIT; else unset ANNIE_TIME_LIMIT; fi
    echo "=== [$entry] $command" >&2
    started=$(date +%s)
    timeout --kill-after=60 "$TRIAL_TIMEOUT" bash -c "$command" > "$trial_dir/runner.log" 2>&1 || exit_code=$?
    jq -n --argjson exit_code "$exit_code" --argjson duration "$(( $(date +%s) - started ))" --argjson timeout "$TRIAL_TIMEOUT" \
      '{exit_code:$exit_code, duration_seconds:$duration, timeout_seconds:$timeout}' > "$trial_dir/completion.json"
    cp "$run_dir/manifest.json" "$trial_dir/manifest.json"
    # the trial record of run-trials.sh, scored by the eval's own collector rules
    jq -n --arg id "$entry" --arg task "$task" --arg suite "$suite" --slurpfile c "$trial_dir/completion.json" \
      '{id:$id, title:$task, suite:$suite, status:"infra_invalid", success:false,
        duration_seconds:$c[0].duration_seconds, cost_first_cold_usd:null, turns:null,
        cache_hit_rate_normalized:null, exit_code:$c[0].exit_code, runtime:"local", checkpoint:null,
        harness_exception:null, included_in_efficiency:false}' > "$trial_dir/trial.json"
    python3 "$FH/calculate-cost.py" --trial "$trial_dir" --model "$MODEL" --harness "$HARNESS" --score --write \
      >> "$trial_dir/runner.log" 2>&1 || echo "scoring failed, see $trial_dir/runner.log" >&2
    # a verifier which ran no test, e.g. failing to download its test runner, scores a failure with no test report
    if jq -e '.status == "failure"' "$trial_dir/trial.json" >/dev/null \
        && ! compgen -G "$trial_dir/jobs/*/*/verifier/ctrf.json" >/dev/null; then
      jq '.status = "infra_invalid" | .success = false | .infra_reason = "the verifier reported no test results"' \
        "$trial_dir/trial.json" > "$trial_dir/trial.json.tmp" && mv "$trial_dir/trial.json.tmp" "$trial_dir/trial.json"
    fi
    jq -e '.success == true' "$trial_dir/trial.json" >/dev/null && passed=$((passed + 1))
    printf '=== [%s] %s in %ss (exit %s)\n' "$entry" "$(jq -r .status "$trial_dir/trial.json")" \
      "$(jq -r .duration_seconds "$trial_dir/trial.json")" "$exit_code" >&2
  done
  echo "$passed/$total passed, trials in $run_dir/trials, next: $0 summary $run_id" >&2
}

summary() {
  local run_dir="$WORKSPACE/runs/${1:?usage: $0 summary RUN_ID}"
  jq -r -s 'sort_by(.id)[] | [.id, .status, (.duration_seconds|tostring)] | @tsv' "$run_dir"/trials/*/trial.json | column -t
  jq -r -s '"\(map(select(.success)) | length)/\(length) passed"' "$run_dir"/trials/*/trial.json
}

default_run_id() { echo "$(date +%Y-%m-%d)-$HARNESS-${ANTHROPIC_MODEL:-${OPENAI_MODEL:-model}}$1" | tr '/:' '--'; }

command=${1:-}
[ $# -gt 0 ] && shift
case "$command" in
  setup) setup ;;
  smoke) trials "${RUN_ID:-$(default_run_id -smoke)}" "$WORKSPACE/skills/frontierharness-eval/smoke-tasks.txt" ;;
  run) trials "${RUN_ID:-$(default_run_id "")}" "${1:-$WORKSPACE/tasks}" ;;
  summary) summary "$@" ;;
  *) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
