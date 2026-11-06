#!/usr/bin/env bash
# Runs one FrontierHarness task with honest-annie, inside the restored Runta runtime.
#
# Installed by install-harness.sh as /work/honest-annie/run-task.sh, and given to run-trials.sh as its --cmd:
#
#   --cmd '/work/honest-annie/run-task.sh {suite} {task} {model} {jobs}'
#
# The eval's default runner templates name the agent with `-a {harness}` for Harbor
# and `--agent {harness}` for Pier, the latter taking only its built-in agents,
# so a single --cmd dispatches on the suite instead, keeping every other flag of the default templates.
set -euo pipefail

suite=$1
task=$2
model=$3
jobs=$4

here=$(cd "$(dirname "$0")" && pwd)
# the settings of honest-annie frozen into the golden checkpoint, see annie.env
if [ -f "$here/annie.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$here/annie.env"
  set +a
fi
export ANNIE_VERSION_FILE="$here/version.txt"
export ANNIE_DIST="$here/honest-annie-runtime.tar.gz"

case "$suite" in
  terminal-bench)
    exec harbor run -d terminal-bench@2.0 -i "$task" -a honest_annie_harbor:HonestAnnie -m "$model" \
      --jobs-dir "$jobs" --extra-docker-compose /work/runta-ca-overlay.yaml -r 2 -y ;;
  datacurve)
    exec pier run -p "/work/deep-swe/tasks/$task" --agent-import-path honest_annie_pier:HonestAnnie \
      --model "$model" --jobs-dir "$jobs" ;;
  *)
    echo "no runner for suite '$suite'" >&2
    exit 2 ;;
esac
