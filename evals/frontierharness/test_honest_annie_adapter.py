# python3 -m unittest discover evals/frontierharness
import asyncio
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

import honest_annie_adapter as adapter


def inferences(*tokens, initiated=("2026-11-05T10:00:00Z",)):
    """An inferences.json in the layout honest-annie writes, see CogitationRecorder."""
    return json.dumps({
        "inferences": [
            {"enunciation": f"00{i}", "initiated": initiated[i], "model": "kimi-k3", "provider": "api.fireworks.ai",
             "tokens": t, "total": sum(t.values()), "stop": None}
            for i, t in enumerate(tokens)
        ],
        "invoked": {},
        "total": {},
    })


def recording():
    """A cogitation invoking another one, which was initiated between the two inferences of its invoker."""
    root = Path(tempfile.mkdtemp())
    cogitation = root / "2026-11-05T10-00-00.000000Z"
    invoked = cogitation / "003-1-2026-11-05T10-00-30.000000Z"
    invoked.mkdir(parents=True)
    (cogitation / "inferences.json").write_text(inferences(
        {"input": 1000, "cache_read": 0, "output": 200, "reasoning": 50},
        {"input": 100, "cache_read": 1200, "output": 300},
        initiated=("2026-11-05T10:00:00Z", "2026-11-05T10:01:00Z"),
    ))
    (invoked / "inferences.json").write_text(inferences(
        {"input": 400, "cache_read": 600, "cache_write_1h": 10, "output": 40},
        initiated=("2026-11-05T10:00:30Z",),
    ))
    return root


class EndpointTest(unittest.TestCase):

    def test_resolves_fireworks_route_to_its_openai_compatible_endpoint(self):
        # when
        endpoint = adapter.resolve_endpoint("fireworks_ai/accounts/fireworks/models/kimi-k3", {})

        # then
        self.assertEqual(endpoint, adapter.Endpoint(
            base_url="https://api.fireworks.ai/inference/v1",
            model="accounts/fireworks/models/kimi-k3",
            key_env="FIREWORKS_API_KEY",
        ))
        self.assertEqual(endpoint.host, "api.fireworks.ai")

    def test_resolves_custom_route_from_overrides(self):
        # when
        endpoint = adapter.resolve_endpoint("openai/kimi-k3", {
            "ANNIE_OPENAI_BASE_URL": "https://gateway.example:8443/v1",
            "ANNIE_API_KEY_ENV": "MY_GATEWAY_API_KEY",
        })

        # then
        self.assertEqual(endpoint.model, "openai/kimi-k3")
        self.assertEqual(endpoint.key_env, "MY_GATEWAY_API_KEY")
        self.assertEqual(endpoint.host, "gateway.example")

    def test_refuses_unknown_route_without_base_url(self):
        # when / then
        with self.assertRaises(ValueError):
            adapter.resolve_endpoint("openai/kimi-k3", {})


class UsageTest(unittest.TestCase):

    def test_reads_every_inference_of_the_tree_in_the_order_initiated(self):
        # when
        calls = adapter.read_calls(recording())

        # then
        self.assertEqual([c["initiated"] for c in calls], [
            "2026-11-05T10:00:00Z", "2026-11-05T10:00:30Z", "2026-11-05T10:01:00Z"
        ])
        self.assertEqual(calls[0]["output_tokens"], 250)  # the reasoning is output too
        self.assertEqual(calls[1]["cache_write_tokens"], 10)

    def test_totals_count_the_cached_input_as_input(self):
        # when
        totals = adapter.usage_totals(adapter.read_calls(recording()))

        # then
        self.assertEqual(totals, {
            "n_input_tokens": 1000 + 100 + 1200 + 400 + 600 + 10,
            "n_cache_tokens": 1800,
            "n_output_tokens": 250 + 300 + 40,
            "cache_write_tokens": 10,
            "calls": 3,
        })

    def test_calls_are_written_in_the_format_of_the_hermes_parser(self):
        # given
        calls = adapter.read_calls(recording())

        # when
        first = json.loads(adapter.calls_jsonl(calls).splitlines()[0])

        # then the hermes parser of the eval reads fresh input_tokens and the cache_read_tokens apart
        self.assertEqual((first["input_tokens"], first["cache_read_tokens"], first["output_tokens"]), (1000, 0, 250))

    def test_populates_the_agent_context(self):
        # given
        context = SimpleNamespace(n_input_tokens=None, n_cache_tokens=None, n_output_tokens=None, metadata=None)

        # when
        adapter.populate(context, adapter.read_calls(recording()))

        # then
        self.assertEqual((context.n_input_tokens, context.n_cache_tokens, context.n_output_tokens), (3310, 1800, 590))
        self.assertEqual(context.metadata["honest_annie"]["cogitations"], 2)

    def test_leaves_the_context_empty_without_a_recording(self):
        # given
        context = SimpleNamespace(n_input_tokens=None, n_cache_tokens=None, n_output_tokens=None, metadata=None)

        # when
        adapter.populate(context, adapter.read_calls(Path(tempfile.mkdtemp())))

        # then
        self.assertIsNone(context.n_input_tokens)


class FakeEnvironment:
    """The environment of Harbor and Pier as the agent uses it, recording what it is asked to do."""

    def __init__(self, return_code=0, proxy=None):
        self.commands = []
        self.uploads = []
        self.return_code = return_code
        if proxy:
            self.agent_process_env = lambda env: {**env, "HTTPS_PROXY": proxy}

    async def exec(self, command, cwd=None, env=None, timeout_sec=None, user=None):
        self.commands.append((command, env, user))
        return SimpleNamespace(return_code=self.return_code if "bin/honest-annie" in command else 0,
                               stdout="", stderr="")

    async def upload_file(self, source_path, target_path):
        self.uploads.append((Path(source_path).read_bytes(), target_path))

    async def download_dir(self, source_dir, target_dir):
        raise FileNotFoundError(source_dir)


class Agent(adapter.HonestAnnieAgent):

    def __init__(self, logs_dir, env):
        self.logs_dir = logs_dir
        self.model_name = "fireworks_ai/accounts/fireworks/models/kimi-k3"
        self._extra_env = env


class AgentTest(unittest.TestCase):

    def test_runs_annie_on_the_instruction_against_the_endpoint_of_the_model(self):
        # given
        environment = FakeEnvironment(proxy="http://agent:token@pier-egress-proxy:8080")
        agent = Agent(Path(tempfile.mkdtemp()), {"FIREWORKS_API_KEY": "runta-secret-stub", "ANNIE_TOKEN_BUDGET": "3000000"})
        context = SimpleNamespace(n_input_tokens=None, n_cache_tokens=None, n_output_tokens=None, metadata=None)

        # when
        asyncio.run(agent.run("Fix the failing test.", environment, context))

        # then
        self.assertEqual(environment.uploads, [(b"Fix the failing test.", "/installed-agent/instruction.md")])
        command, env, user = environment.commands[-1]
        self.assertIn("/installed-agent/honest-annie/bin/honest-annie \"$(cat /installed-agent/instruction.md)\"", command)
        self.assertIsNone(user)  # the default user of the task, not root
        self.assertEqual(env["OPENAI_BASE_URL"], "https://api.fireworks.ai/inference/v1")
        self.assertEqual(env["OPENAI_MODEL"], "accounts/fireworks/models/kimi-k3")
        self.assertEqual(env["OPENAI_API_KEY"], "runta-secret-stub")
        self.assertEqual(env["ANNIE_TOKEN_BUDGET"], "3000000")
        self.assertEqual(env["HTTPS_PROXY"], "http://agent:token@pier-egress-proxy:8080")

    def test_raises_when_annie_crashes(self):
        # given
        agent = Agent(Path(tempfile.mkdtemp()), {"FIREWORKS_API_KEY": "stub"})
        context = SimpleNamespace(n_input_tokens=None, n_cache_tokens=None, n_output_tokens=None, metadata=None)

        # when / then
        with self.assertRaises(RuntimeError):
            asyncio.run(agent.run("Fix it.", FakeEnvironment(return_code=1), context))

    def test_refuses_to_run_without_the_key_of_the_provider(self):
        # given
        agent = Agent(Path(tempfile.mkdtemp()), {"ANNIE_EGRESS_CA": "/nonexistent/runta-egress.crt"})

        # when / then
        with self.assertRaises(RuntimeError):
            agent.process_env(FakeEnvironment())

    def test_sends_the_stub_without_the_key_behind_the_runta_egress(self):
        # given the egress CA of Runta, whose rule injects the key, while the runtime has no stub variable
        ca = Path(tempfile.mkdtemp()) / "runta-egress.crt"
        ca.write_text("CA")
        agent = Agent(Path(tempfile.mkdtemp()), {"ANNIE_EGRESS_CA": str(ca)})

        # when
        env = agent.process_env(FakeEnvironment())

        # then
        self.assertEqual(env["OPENAI_API_KEY"], "runta-secret-stub")

    def test_install_trusts_the_egress_ca_in_the_bundled_java_runtime(self):
        # when
        command = adapter.install_command("/installed-agent/honest-annie.tar.gz", "/installed-agent/egress-ca.crt")

        # then
        self.assertIn("--strip-components=1", command)
        self.assertIn("runtime/bin/keytool -importcert -noprompt -cacerts", command)

    def test_install_replaces_the_egress_ca_the_java_runtime_already_trusts(self):
        # when
        command = adapter.install_command("/installed-agent/honest-annie.tar.gz", "/installed-agent/egress-ca.crt")

        # then the import, failing on an existing alias, follows deleting it, whose failure is ignored
        delete = command.index("keytool -delete -cacerts -storepass changeit -alias runta-egress")
        self.assertLess(delete, command.index("keytool -importcert"))
        self.assertIn("|| true", command[delete:command.index("keytool -importcert")])


if __name__ == "__main__":
    unittest.main()
