#!/usr/bin/env bash
# Builds honest-annie in the Runta build runtime and registers it with Harbor and Pier.
#
# Given to the eval's provision-golden-checkpoint.sh as its --install-script,
# which runs it in /work/harness, the clone of honest-annie at the pinned commit,
# after Harbor and Pier are installed as uv tools, and before the checkpoint is frozen.
#
# It leaves in /work/honest-annie:
#   honest-annie-runtime.tar.gz  the distribution with its own Java runtime, uploaded into each task container
#   version.txt                  the version and commit of the build
#   honest_annie_*.py            the agent adapters, importable by Harbor and Pier through a .pth file
#   run-task.sh, annie.env       the runner command of each task and the settings it passes on
set -euo pipefail

# The JDK building the distribution, whose Java runtime jlink bundles, removed afterwards.
JDK_FEATURE=${JDK_FEATURE:-25}
OUT=/work/honest-annie
SOURCE=$(pwd)
export PATH="$HOME/.local/bin:$PATH"

case "$(uname -m)" in
  x86_64|amd64) arch=x64 ;;
  aarch64|arm64) arch=aarch64 ;;
  *) echo "unsupported architecture $(uname -m)" >&2; exit 1 ;;
esac

echo "installing Temurin JDK $JDK_FEATURE ($arch)" >&2
jdk=$(mktemp -d)
curl -fsSL "https://api.adoptium.net/v3/binary/latest/$JDK_FEATURE/ga/linux/$arch/jdk/hotspot/normal/eclipse" \
  | tar -xz -C "$jdk" --strip-components=1
export JAVA_HOME=$jdk
"$JAVA_HOME/bin/java" -version 2>&1 | head -1 >&2

# The Runta egress terminates TLS with a CA of its own, which the system trusts, but not the cacerts of the JDK,
# so Gradle could not download itself nor the dependencies. The task containers get it from the adapter instead.
EGRESS_CA=${EGRESS_CA:-/usr/local/share/ca-certificates/runta-egress.crt}
if [ -f "$EGRESS_CA" ]; then
  "$JAVA_HOME/bin/keytool" -importcert -noprompt -cacerts -storepass changeit \
    -alias runta-egress -file "$EGRESS_CA" >&2
fi

echo "building the runtime distribution" >&2
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$(mktemp -d)}
./gradlew --no-daemon --quiet runtimeDistTar
dist=$(ls build/distributions/honest-annie-runtime-*-linux-*.tar.gz)

rm -rf "$OUT"
mkdir -p "$OUT"
cp "$dist" "$OUT/honest-annie-runtime.tar.gz"
printf '%s (%s)\n' "$(git describe --tags --always --dirty 2>/dev/null || echo unknown)" "$(git rev-parse HEAD)" \
  > "$OUT/version.txt"
cp evals/frontierharness/honest_annie_adapter.py \
   evals/frontierharness/honest_annie_harbor.py \
   evals/frontierharness/honest_annie_pier.py \
   evals/frontierharness/run-task.sh \
   evals/frontierharness/annie.env \
   "$OUT/"
chmod +x "$OUT/run-task.sh"

# Registers the adapters with both runners: a custom agent of Harbor and Pier is any class
# importable by their Python, which a .pth file in the site-packages of each uv tool provides.
tools=$(uv tool dir)
for tool in harbor datacurve-pier; do
  python="$tools/$tool/bin/python"
  [ -x "$python" ] || { echo "no uv tool $tool at $tools/$tool" >&2; exit 1; }
  site=$("$python" -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')
  echo "$OUT" > "$site/honest_annie.pth"
done
"$tools/harbor/bin/python" -c 'import honest_annie_harbor; print("harbor agent:", honest_annie_harbor.HonestAnnie.import_path())'
"$tools/datacurve-pier/bin/python" -c 'import honest_annie_pier; print("pier agent:", honest_annie_pier.HonestAnnie.import_path())'

# The distribution runs on its own, with no JDK, as it will in the task containers.
check=$(mktemp -d)
tar -xzf "$OUT/honest-annie-runtime.tar.gz" -C "$check" --strip-components=1
"$check/runtime/bin/java" -version 2>&1 | head -1
"$check/runtime/bin/keytool" -help > /dev/null 2>&1 || { echo "no keytool in the bundled Java runtime" >&2; exit 1; }

# A small checkpoint: none of the build is needed by the trials.
rm -rf "$check" "$jdk" "$GRADLE_USER_HOME" "$SOURCE/build" "$SOURCE/.gradle" "$SOURCE/.kotlin"
echo "honest-annie $(cat "$OUT/version.txt") installed in $OUT" >&2
