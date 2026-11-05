"""Runs honest-annie as an installed agent of Harbor (Terminal-Bench) or Pier (DeepSWE).

The runners of FrontierHarness Eval drive the agent from the Runta runtime host,
while the task lives in a container. Annie acts only through Kotlin scripts,
so she runs inside the container, where her scripts and the processes they start
see the files of the task, like the shell tool of any other harness would.

The self-contained distribution of `./gradlew runtimeDistTar`, carrying its own Java runtime,
is uploaded into the container at setup, so the task image needs no JDK.
The model is reached through the OpenAI-compatible endpoint of the provider of the LiteLLM route
the runner passes as the model, e.g. `fireworks_ai/accounts/fireworks/models/kimi-k3`.

This module knows nothing of Harbor or Pier, see `honest_annie_harbor.py` and `honest_annie_pier.py`.
Run it as a script to print the usage a recording of cogitations reports:

    python3 honest_annie_adapter.py usage <cogitations-dir>
"""
from __future__ import annotations

import json
import os
import shlex
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping

NAME = "honest-annie"

# The distribution built by install-harness.sh on the runtime host, and its version.
DEFAULT_DIST = "/work/honest-annie/honest-annie-runtime.tar.gz"
DEFAULT_VERSION_FILE = "/work/honest-annie/version.txt"
# The CA of the Runta egress, which terminates TLS, trusted by the host but not by the JVM in the container.
DEFAULT_EGRESS_CA = "/usr/local/share/ca-certificates/runta-egress.crt"

INSTALL_DIR = "/installed-agent/honest-annie"
AGENT_LOGS_DIR = "/logs/agent"
COGITATIONS_DIR = f"{AGENT_LOGS_DIR}/cogitations"
# Per-call usage, in the format of the hermes parser of the eval's usage_details.py.
CALLS_FILE = "honest-annie-calls.jsonl"

# The settings of honest-annie passed on from the environment of the runner, see host/Main.kt.
PASSED_ON = (
    "ANNIE_TOKEN_BUDGET",
    "ANNIE_PARALLELISM",
    "ANNIE_REINFERENCES",
    "ANNIE_EFFORT",
    "ANNIE_MAX_TOKENS",
    "ANNIE_HARNESS",
    "JAVA_OPTS",
    "HONEST_ANNIE_OPTS",
)

# LiteLLM route prefix -> (OpenAI-compatible base URL, the environment variable of its key)
PROVIDERS: dict[str, tuple[str, str]] = {
    "fireworks_ai": ("https://api.fireworks.ai/inference/v1", "FIREWORKS_API_KEY"),
    "moonshot": ("https://api.moonshot.ai/v1", "MOONSHOT_API_KEY"),
    "openrouter": ("https://openrouter.ai/api/v1", "OPENROUTER_API_KEY"),
    "together_ai": ("https://api.together.xyz/v1", "TOGETHER_API_KEY"),
}


@dataclass(frozen=True)
class Endpoint:
    base_url: str
    model: str
    key_env: str

    @property
    def host(self) -> str:
        return self.base_url.split("://", 1)[-1].split("/", 1)[0].split(":", 1)[0]


def resolve_endpoint(route: str | None, env: Mapping[str, str]) -> Endpoint:
    """The endpoint serving the model of a LiteLLM route, each part overridable with `ANNIE_OPENAI_*`."""
    provider, _, model = (route or "").partition("/")
    base_url, key_env = PROVIDERS.get(provider, (None, None))
    if base_url is None:
        # an unknown prefix, e.g. `openai/` of a custom gateway, or a bare model id
        model = route or ""
    base_url = env.get("ANNIE_OPENAI_BASE_URL") or base_url
    key_env = env.get("ANNIE_API_KEY_ENV") or key_env or "OPENAI_API_KEY"
    model = env.get("ANNIE_OPENAI_MODEL") or model
    if not base_url:
        raise ValueError(f"No OpenAI-compatible endpoint known for the model route {route!r}; set ANNIE_OPENAI_BASE_URL")
    if not model:
        raise ValueError("No model given; pass the model to the runner, or set ANNIE_OPENAI_MODEL")
    return Endpoint(base_url=base_url, model=model, key_env=key_env)


def read_calls(cogitations_dir: Path) -> list[dict[str, Any]]:
    """Every inference of every recorded cogitation, the invoked ones included, in the order they were initiated.

    Each cogitation directory holds an `inferences.json` listing its own inferences,
    the ones of the cogitations it invoked being in their own directories.
    """
    calls = []
    for path in sorted(cogitations_dir.rglob("inferences.json")):
        try:
            recorded = json.loads(path.read_text())
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            continue
        for inference in recorded.get("inferences") or []:
            tokens = inference.get("tokens") or {}
            calls.append({
                "initiated": inference.get("initiated") or "",
                "cogitation": str(path.parent.relative_to(cogitations_dir)),
                "enunciation": inference.get("enunciation"),
                "model": inference.get("model"),
                # honest-annie counts the input processed anew apart from the cached one
                "input_tokens": int(tokens.get("input") or 0),
                "cache_read_tokens": int(tokens.get("cache_read") or 0),
                "cache_write_tokens": sum(
                    int(tokens.get(key) or 0) for key in ("cache_write", "cache_write_5m", "cache_write_1h")
                ),
                # the reasoning is output too, reported apart when the provider does
                "output_tokens": int(tokens.get("output") or 0) + int(tokens.get("reasoning") or 0),
                "reasoning_tokens": int(tokens.get("reasoning") or 0),
                "stop": inference.get("stop"),
            })
    calls.sort(key=lambda call: call["initiated"])
    return calls


def usage_totals(calls: list[dict[str, Any]]) -> dict[str, int]:
    """The totals in the terms of the AgentContext of Harbor and Pier, the input including the cached one."""
    cached = sum(call["cache_read_tokens"] for call in calls)
    cache_write = sum(call["cache_write_tokens"] for call in calls)
    return {
        "n_input_tokens": sum(call["input_tokens"] for call in calls) + cached + cache_write,
        "n_cache_tokens": cached,
        "n_output_tokens": sum(call["output_tokens"] for call in calls),
        "cache_write_tokens": cache_write,
        "calls": len(calls),
    }


def calls_jsonl(calls: list[dict[str, Any]]) -> str:
    return "".join(json.dumps(call) + "\n" for call in calls)


def populate(context: Any, calls: list[dict[str, Any]]) -> None:
    """Fills the AgentContext with the usage of the calls, when any was recorded."""
    if not calls:
        return
    totals = usage_totals(calls)
    context.n_input_tokens = totals["n_input_tokens"]
    context.n_cache_tokens = totals["n_cache_tokens"]
    context.n_output_tokens = totals["n_output_tokens"]
    if hasattr(context, "n_agent_steps"):  # only in the context of Pier
        context.n_agent_steps = totals["calls"]
    metadata = dict(context.metadata or {})
    metadata["honest_annie"] = {
        "inferences": totals["calls"],
        "cache_write_tokens": totals["cache_write_tokens"],
        "reasoning_tokens": sum(call["reasoning_tokens"] for call in calls),
        "cogitations": len({call["cogitation"] for call in calls}),
    }
    context.metadata = metadata


def run_command(instruction_path: str) -> str:
    """The shell command running Annie on the instruction, her answer and logs kept as evidence."""
    return (
        f"mkdir -p {COGITATIONS_DIR} && "
        f"{INSTALL_DIR}/bin/honest-annie \"$(cat {shlex.quote(instruction_path)})\" "
        f"> {AGENT_LOGS_DIR}/honest-annie.txt 2> {AGENT_LOGS_DIR}/honest-annie-stderr.txt"
    )


def install_command(archive: str, ca: str | None) -> str:
    """The shell command, run as root, unpacking the distribution and trusting the egress CA in its Java runtime."""
    steps = [
        f"rm -rf {INSTALL_DIR}",
        f"mkdir -p {INSTALL_DIR} {AGENT_LOGS_DIR}",
        f"tar -xzf {shlex.quote(archive)} -C {INSTALL_DIR} --strip-components=1",
        f"rm -f {shlex.quote(archive)}",
    ]
    if ca:
        steps.append(
            f"{INSTALL_DIR}/runtime/bin/keytool -importcert -noprompt -cacerts -storepass changeit "
            f"-alias runta-egress -file {shlex.quote(ca)} > /dev/null"
        )
    steps += [
        f"chmod -R a+rX {INSTALL_DIR}",
        f"chmod a+rwx {AGENT_LOGS_DIR}",
        f"{INSTALL_DIR}/runtime/bin/java -version 2>&1 | head -1",
    ]
    return " && ".join(steps)


class HonestAnnieAgent:
    """The setup and run of honest-annie, mixed into the BaseAgent of Harbor or of Pier.

    Both runners give an environment with the same `exec`, `upload_file` and `download_dir`,
    and an `AgentContext` with the same token fields.
    """

    logs_dir: Path
    model_name: str | None

    @staticmethod
    def name() -> str:
        return NAME

    def version(self) -> str | None:
        version_file = Path(os.environ.get("ANNIE_VERSION_FILE", DEFAULT_VERSION_FILE))
        try:
            return version_file.read_text().strip() or None
        except OSError:
            return None

    def _env(self) -> Mapping[str, str]:
        # Harbor merges its --agent-env over the process environment, Pier passes it as extra_env
        extra = getattr(self, "_extra_env", None) or {}
        return {**os.environ, **extra}

    def endpoint(self) -> Endpoint:
        return resolve_endpoint(self.model_name, self._env())

    async def _checked(self, environment: Any, command: str, **kwargs: Any) -> Any:
        result = await environment.exec(command=command, **kwargs)
        if result.return_code != 0:
            raise RuntimeError(
                f"honest-annie command failed (exit {result.return_code}): {command}\n"
                f"stdout: {(result.stdout or '')[-2000:]}\nstderr: {(result.stderr or '')[-2000:]}"
            )
        return result

    async def setup(self, environment: Any) -> None:
        env = self._env()
        dist = Path(env.get("ANNIE_DIST", DEFAULT_DIST))
        if not dist.is_file():
            raise RuntimeError(f"No honest-annie distribution at {dist}, see install-harness.sh")
        await self._checked(environment, "mkdir -p /installed-agent", user="root")
        archive = "/installed-agent/honest-annie.tar.gz"
        await environment.upload_file(dist, archive)
        ca_source = Path(env.get("ANNIE_EGRESS_CA", DEFAULT_EGRESS_CA))
        ca = None
        if ca_source.is_file():
            ca = "/installed-agent/egress-ca.crt"
            await environment.upload_file(ca_source, ca)
        result = await self._checked(environment, install_command(archive, ca), user="root")
        setup_dir = self.logs_dir / "setup"
        setup_dir.mkdir(parents=True, exist_ok=True)
        (setup_dir / "honest-annie-install.txt").write_text(
            f"distribution: {dist}\nversion: {self.version()}\negress CA: {ca_source if ca else 'none'}\n"
            f"{result.stdout or ''}"
        )

    def process_env(self, environment: Any) -> dict[str, str]:
        env = self._env()
        endpoint = self.endpoint()
        key = env.get(endpoint.key_env)
        if not key:
            raise RuntimeError(f"{endpoint.key_env} is not set for the model {self.model_name}")
        process_env = {
            "OPENAI_BASE_URL": endpoint.base_url,
            "OPENAI_MODEL": endpoint.model,
            # a stub inside Runta, the egress proxy putting the real key in place
            "OPENAI_API_KEY": key,
            "ANNIE_COGITATIONS_DIR": COGITATIONS_DIR,
            # the Python of the intents gets its own virtual environment, out of the task's files
            "ANNIE_PYTHON_VENV": f"{INSTALL_DIR}/python-venv",
        }
        process_env.update({name: env[name] for name in PASSED_ON if env.get(name)})
        # Pier scopes its egress proxy variables to the commands of the agent
        scope = getattr(environment, "agent_process_env", None)
        if scope is None:
            return process_env
        return scope(process_env) or process_env

    async def run(self, instruction: str, environment: Any, context: Any) -> None:
        with tempfile.TemporaryDirectory() as local:
            instruction_file = Path(local) / "instruction.md"
            instruction_file.write_text(instruction)
            instruction_path = "/installed-agent/instruction.md"
            await environment.upload_file(instruction_file, instruction_path)
        await self._checked(environment, f"chmod a+r {instruction_path}", user="root")
        result = await environment.exec(command=run_command(instruction_path), env=self.process_env(environment))
        await self._collect(environment, context)
        if result.return_code != 0:
            # a crash of the harness, not a wrong answer, which exits with 0
            raise RuntimeError(
                f"honest-annie exited with {result.return_code}, see {AGENT_LOGS_DIR}/honest-annie-stderr.txt"
            )

    async def _collect(self, environment: Any, context: Any) -> None:
        """Reads the usage from the recorded cogitations, whether or not the logs directory is mounted."""
        cogitations = self.logs_dir / "cogitations"
        if not any(cogitations.rglob("inferences.json")):
            with tempfile.TemporaryDirectory() as local:
                try:
                    await environment.download_dir(COGITATIONS_DIR, local)
                except Exception:  # noqa: BLE001 - no recording is reported as no usage
                    return
                self._record(Path(local), context)
                return
        self._record(cogitations, context)

    def _record(self, cogitations: Path, context: Any) -> None:
        calls = read_calls(cogitations)
        self.logs_dir.mkdir(parents=True, exist_ok=True)
        (self.logs_dir / CALLS_FILE).write_text(calls_jsonl(calls))
        populate(context, calls)

    def populate_context_post_run(self, context: Any) -> None:
        if context.n_input_tokens is None:
            cogitations = self.logs_dir / "cogitations"
            if cogitations.is_dir():
                self._record(cogitations, context)


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] != "usage":
        sys.exit(__doc__)
    found = read_calls(Path(sys.argv[2]))
    print(json.dumps(usage_totals(found), indent=2))
