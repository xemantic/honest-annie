# python3 -m unittest discover evals/oolong
import json
import os
import shutil
import subprocess
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import oolong_eval

ROWS = [
    ["Jun 29, 2022", "44436", "What does IOC stand for ?", "abbreviation"],
    ["Jan 20, 2025", "89840", "What kind of car did Janis Joplin ask the Lord to buy her ?", "entity"],
    ["Feb 12, 2024", "33449", "How do you recognize anorexia ?", "description and abstract concept"],
]


def recording(prompt, answer):
    """A recording of a cogitation invoking one other, judging items, in the layout honest-annie writes."""
    root = Path(tempfile.mkdtemp())
    cogitation = root / "2026-10-24T17-43-28.986610Z"
    cogitation.mkdir()
    (cogitation / "cogitation.md").write_text(
        "| 005 | computer `user's computer` ↩ 004-2 | … |\n"
        "| 006 | self `self` ↩ 005-01 | … |\n"
    )
    (cogitation / "005-01-computer-invocation.md").write_text(prompt)
    (cogitation / "006-1-self-text.txt").write_text(answer)
    return root


def prime_recording(*subagents, said=("Done.",), code=()):
    """A recording of a Prime Agent run, of the root session and its subagents, each a list of entries, in the layout it writes."""
    root = Path(tempfile.mkdtemp())
    session = [{"type": "session", "id": "root"}]
    session += [assistant([{"type": "toolCall", "name": "python_repl", "arguments": {"code": c}}]) for c in code]
    session += [assistant([{"type": "text", "text": text}]) for text in said]
    (root / "sessions").mkdir()
    (root / "sessions" / "root.jsonl").write_text("".join(json.dumps(e) + "\n" for e in session))
    for n, entries in enumerate(subagents):
        directory = root / "agent" / "session-artifacts" / "root" / f"sub-{n}"
        directory.mkdir(parents=True)
        (directory / f"child-{n}.jsonl").write_text("".join(json.dumps(e) + "\n" for e in entries))
        (directory / "semantic-edges.jsonl").write_text('{"type":"session_registered"}\n')
    return root


def assistant(content, tokens=10):
    return {"type": "message", "message": {
        "role": "assistant", "content": content, "stopReason": "stop",
        "usage": {"input": tokens, "output": 2, "cacheRead": 100, "cacheWrite": 0, "totalTokens": tokens + 102},
    }}


def task(prompt):
    return {"type": "custom_message", "customType": "agent_message", "content": f"[task from parent]\n\n{prompt}"}


class JudgmentsTest(unittest.TestCase):

    def test_reads_label_codes_defined_in_the_prompt(self):
        # given
        prompt = (
            "Classify each question below as exactly one of:\n"
            "DESC (description and abstract concept: definitions, reasons), ENTY (entity: things, animals), "
            "ABBR (abbreviation: what an abbreviation stands for).\n\n"
            "0: What does IOC stand for ?\n"
            "1: What kind of car did Janis Joplin ask the Lord to buy her ?\n"
            "2: How do you recognize anorexia ?\n"
        )
        answer = "0: ABBR\n1: ENTY\n2: DESC\n"

        # when
        judged = oolong_eval.judgments(recording(prompt, answer), ROWS)

        # then
        assert judged == {0: "abbreviation", 1: "entity", 2: "description and abstract concept"}, judged

    def test_reads_label_codes_defined_with_equals_sign(self):
        # given
        prompt = (
            "Codes: ENTY = entity (objects, animals, products), DESC = description and abstract concept "
            "(definitions, reasons), ABBR = abbreviation (what an abbreviation stands for).\n\n"
            "0: What does IOC stand for ?\n"
            "1: What kind of car did Janis Joplin ask the Lord to buy her ?\n"
            "2: How do you recognize anorexia ?\n"
        )
        answer = "0: ABBR\n1: ENTY\n2: DESC\n"

        # when
        judged = oolong_eval.judgments(recording(prompt, answer), ROWS)

        # then
        assert judged == {0: "abbreviation", 1: "entity", 2: "description and abstract concept"}, judged


    def test_tells_apart_items_of_the_same_text_by_their_index(self):
        # given
        rows = ROWS + [["Nov 25, 2024", "35398", "What does IOC stand for ?", "abbreviation"]]
        first = "Classify as ABBR (abbreviation) or DESC (description and abstract concept).\n\n0: What does IOC stand for ?\n"
        second = "Classify as ABBR (abbreviation) or DESC (description and abstract concept).\n\n3: What does IOC stand for ?\n"
        root = recording(first, "0: ABBR\n")
        cogitation = next(root.iterdir())
        (cogitation / "cogitation.md").write_text(
            (cogitation / "cogitation.md").read_text() + "| 007 | self `self` ↩ 005-02 | … |\n"
        )
        (cogitation / "005-02-computer-invocation.md").write_text(second)
        (cogitation / "007-1-self-text.txt").write_text("3: DESC\n")

        # when
        judged = oolong_eval.judgments(root, rows)

        # then
        assert judged == {0: "abbreviation", 3: "description and abstract concept"}, judged

    def test_judges_every_item_of_a_question_keyed_among_distinct_questions(self):
        # given
        rows = [
            ROWS[1],
            ["Nov 25, 2024", "35398", ROWS[1][2], "entity"],
            ROWS[0],
        ]
        # the distinct questions keyed in order, the second one's key falling on an item of another text
        prompt = (
            "Classify as ABBR (abbreviation) or ENTY (entity).\n\n"
            f"0: {ROWS[1][2]}\n"
            f"1: {ROWS[0][2]}\n"
        )

        # when
        judged = oolong_eval.judgments(recording(prompt, "0: ENTY\n1: ABBR\n"), rows)

        # then
        assert judged == {0: "entity", 1: "entity", 2: "abbreviation"}, judged


    def test_reads_labels_reconciled_by_question_in_a_script(self):
        # given
        rows = ROWS + [["Nov 25, 2024", "35398", "What does IOC stand for ?", "abbreviation"]]
        prompt = (
            "Classify as ABBR (abbreviation) or DESC (description and abstract concept).\n\n"
            "0: What does IOC stand for ?\n"
            "3: What does IOC stand for ?\n"
        )
        root = recording(prompt, "0: ABBR\n3: DESC\n")
        # identical questions labeled differently, reconciled by the agent
        (next(root.iterdir()) / "008-2-self-intent.kts").write_text(
            'val fix = mapOf("what does ioc stand for ?" to "ABBR", "what is html ?" to "ABBR")\n'
        )

        # when
        judged = oolong_eval.judgments(root, rows)

        # then
        assert judged == {0: "abbreviation", 3: "abbreviation"}, judged


class PrimeAgentTest(unittest.TestCase):

    def test_reads_the_judgments_of_a_subagent(self):
        # given
        prompt = (
            "Classify each question as one of: abbreviation, entity, description and abstract concept.\n\n"
            "0: What does IOC stand for ?\n"
            "1: What kind of car did Janis Joplin ask the Lord to buy her ?\n"
            "2: How do you recognize anorexia ?\n"
        )
        root = prime_recording([
            task(prompt),
            assistant([{"type": "text", "text": "0: abbreviation\n1: entity\n2: description and abstract concept"}]),
        ])

        # when
        judged = oolong_eval.judgments(root, ROWS, oolong_eval.PRIME_AGENT)

        # then
        assert judged == {0: "abbreviation", 1: "entity", 2: "description and abstract concept"}, judged

    def test_reads_the_judgments_a_subagent_printed_in_its_repl(self):
        # given
        prompt = "Label these:\n0: What does IOC stand for ?\n1: What kind of car did Janis Joplin ask the Lord to buy her ?\n"
        root = prime_recording([
            task(prompt),
            assistant([{"type": "toolCall", "name": "python_repl", "arguments": {"code": "print(labels)"}}]),
            {"type": "message", "message": {
                "role": "toolResult", "content": [{"type": "text", "text": "0: abbreviation\n1: entity\n"}]
            }},
            assistant([{"type": "text", "text": "Sent to the parent."}]),
        ])

        # when
        judged = oolong_eval.judgments(root, ROWS, oolong_eval.PRIME_AGENT)

        # then
        assert judged == {0: "abbreviation", 1: "entity"}, judged

    def test_totals_the_inferences_of_the_root_and_its_subagents(self):
        # given
        root = prime_recording(
            [task("a"), assistant([{"type": "text", "text": "a"}], tokens=20)],
            [task("b"), assistant([{"type": "text", "text": "b"}], tokens=30)],
            said=("Working.", "Done."),
        )

        # when
        totals = oolong_eval.prime_totals(root)

        # then
        assert totals == {
            "inferences": 4,
            "invoked": 2,
            "tokens": {"input": 70, "cache_read": 400, "output": 8},
            "total_tokens": 478,
        }, totals

    def test_answers_with_the_last_message_of_the_root_session(self):
        # given
        root = prime_recording([task("a"), assistant([{"type": "text", "text": "Answer: 3"}])],
                               said=("Working.", "Answer: 7"))

        # when
        answer = oolong_eval.prime_answer(root)

        # then
        assert answer == ("Answer: 7", None), answer

    def test_tells_no_answer_of_a_run_recording_nothing(self):
        # given
        root = Path(tempfile.mkdtemp())

        # when
        answer = oolong_eval.prime_answer(root)

        # then
        assert answer == (None, "no answer"), answer
        assert oolong_eval.prime_totals(root)["total_tokens"] is None

    def test_tells_the_source_dataset_fetched_by_a_subagent(self):
        # given
        root = prime_recording([
            task("Find the labels."),
            assistant([{"type": "toolCall", "name": "python_repl", "arguments": {
                "code": "from datasets import load_dataset\nds = load_dataset('nyu-mll/multi_nli')"
            }}]),
        ])

        # when
        leaked = oolong_eval.leaked(root, oolong_eval.PRIME_AGENT)

        # then
        assert leaked

    def test_tells_the_labeled_corpus_downloaded(self):
        # given
        root = prime_recording(code=[
            'r = await bash("curl -sO --max-time 60 https://cogcomp.seas.upenn.edu/Data/QA/QC/train_5500.label")'
        ])

        # when
        leaked = oolong_eval.leaked(root, oolong_eval.PRIME_AGENT)

        # then
        assert leaked

    def test_does_not_tell_the_context_read(self):
        # given
        root = prime_recording(code=["print(open('context.txt').read()[:500])"])

        # when
        leaked = oolong_eval.leaked(root, oolong_eval.PRIME_AGENT)

        # then
        assert not leaked

    def test_registers_the_endpoint_without_writing_the_key(self):
        # given
        environ = {"OPENAI_MODEL": "some-model", "OPENAI_BASE_URL": "https://example.com/v1",
                   "OPENAI_API_KEY": "secret", "ANNIE_MAX_TOKENS": "32000"}

        # when
        provider = oolong_eval.prime_models(environ)["providers"][oolong_eval.PRIME_PROVIDER]

        # then
        assert provider["baseUrl"] == "https://example.com/v1"
        assert provider["models"][0]["id"] == "some-model"
        assert provider["models"][0]["maxTokens"] == 32000
        assert "secret" not in json.dumps(provider)


class Upstream(BaseHTTPRequestHandler):
    """An endpoint of a model telling what was asked of it, as a completion, or a stream of them when asked to."""
    asked = 0

    def log_message(self, *args):
        pass

    def do_POST(self):
        Upstream.asked += 1
        body = self.rfile.read(int(self.headers["Content-Length"]))
        if self.path.endswith("/missing"):
            self.send_error(404)
            return
        said = {"path": self.path, "key": self.headers["Authorization"], "body": body.decode()}
        if json.loads(body).get("stream"):
            events = [{"choices": [{"delta": {"content": "a"}}], "usage": None},
                      {"choices": [], "usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120}}]
            self.respond("text/event-stream", "".join(f"data: {json.dumps(e)}\n\n" for e in events) + "data: [DONE]\n\n")
        else:
            usage = {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
            self.respond("application/json", json.dumps(said | {"usage": usage}))

    def respond(self, content_type, text):
        said = text.encode()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(said)))
        self.end_headers()
        self.wfile.write(said)


def post(url, body):
    request = urllib.request.Request(url, data=body, headers={"Authorization": "Bearer withheld"})
    return urllib.request.urlopen(request, timeout=10)


class IsolationTest(unittest.TestCase):

    def setUp(self):
        self.upstream = HTTPServer(("127.0.0.1", 0), Upstream)
        threading.Thread(target=self.upstream.serve_forever, daemon=True).start()
        self.addCleanup(self.upstream.server_close)
        self.addCleanup(self.upstream.shutdown)

    def endpoint(self):
        endpoint = oolong_eval.Endpoint(f"http://127.0.0.1:{self.upstream.server_port}/v1", key="secret", model="m")
        self.addCleanup(endpoint.close)
        return endpoint

    def test_forwards_requests_to_the_endpoint_with_the_key_withheld_from_the_agent(self):
        # given
        endpoint = self.endpoint()

        # when
        with post(f"{endpoint.url}/chat/completions", b'{"model": "m"}') as response:
            said = json.loads(response.read())

        # then
        assert endpoint.url.startswith("http://127.0.0.1:") and endpoint.url.endswith("/v1")
        assert said["path"] == "/v1/chat/completions"
        assert said["key"] == "Bearer secret"
        assert said["body"] == '{"model": "m"}'

    def test_refuses_any_model_but_the_evaluated_one(self):
        # given
        endpoint = self.endpoint()
        asked = Upstream.asked

        # when
        with self.assertRaises(urllib.error.HTTPError) as raised:
            post(f"{endpoint.url}/chat/completions", b'{"model": "glm-5.3-flash"}')

        # then
        assert raised.exception.code == 403
        assert Upstream.asked == asked
        raised.exception.close()

    def test_counts_the_tokens_of_every_response_streamed_or_not(self):
        # given
        endpoint = self.endpoint()

        # when
        with post(f"{endpoint.url}/chat/completions", b'{"model": "m"}') as response:
            response.read()
        with post(f"{endpoint.url}/chat/completions", b'{"model": "m", "stream": true}') as response:
            streamed = response.read().decode()

        # then
        assert "data: [DONE]" in streamed
        assert endpoint.usage == {"requests": 2, "input": 110, "output": 25, "total": 135}, endpoint.usage

    def test_passes_the_agents_own_login_through_when_the_evaluation_has_no_key(self):
        # given
        endpoint = oolong_eval.Endpoint(f"http://127.0.0.1:{self.upstream.server_port}", key=None,
                                        model=oolong_eval.claude_code_models("claude-sonnet-5-5"))
        self.addCleanup(endpoint.close)

        # when
        with post(f"{endpoint.url}/v1/messages", b'{"model": "claude-sonnet-5-5"}') as response:
            said = json.loads(response.read())
        with post(f"{endpoint.url}/v1/messages", b'{"model": "claude-haiku-5-5"}') as response:
            background = json.loads(response.read())
        with self.assertRaises(urllib.error.HTTPError) as raised:
            post(f"{endpoint.url}/v1/messages", b'{"model": "claude-opus-5-5"}')

        # then
        assert said["key"] == "Bearer withheld"
        assert background["path"] == "/v1/messages"
        assert raised.exception.code == 403
        raised.exception.close()

    def test_refuses_the_tools_of_the_model_provider_reaching_the_web(self):
        # given
        endpoint = self.endpoint()
        asked = Upstream.asked

        # when
        with self.assertRaises(urllib.error.HTTPError) as raised:
            post(f"{endpoint.url}/messages",
                 b'{"model": "m", "tools": [{"type": "web_search_20260209", "name": "web_search"}]}')

        # then
        assert raised.exception.code == 403
        assert Upstream.asked == asked
        raised.exception.close()

    def test_counts_the_cached_input_of_an_anthropic_stream(self):
        # given
        events = [
            {"type": "message_start", "message": {"usage": {
                "input_tokens": 5, "cache_read_input_tokens": 9000, "cache_creation_input_tokens": 400, "output_tokens": 1}}},
            {"type": "message_delta", "usage": {"output_tokens": 70}},
        ]
        stream = "".join(f"event: {e['type']}\ndata: {json.dumps(e)}\n\n" for e in events).encode()

        # when
        used = oolong_eval.Endpoint.usage_of(stream)

        # then
        assert used == {"input": 9405, "output": 70, "total": 9475}, used

    def test_forwards_the_failure_of_the_endpoint(self):
        # given
        endpoint = self.endpoint()

        # when
        with self.assertRaises(urllib.error.HTTPError) as raised:
            post(f"{endpoint.url}/missing", b'{"model": "m"}')

        # then
        assert raised.exception.code == 404
        raised.exception.close()

    @unittest.skipUnless(shutil.which(oolong_eval.SANDBOX), "needs sandbox-exec of macOS")
    def test_withholds_the_home_directory_but_for_what_the_agent_needs_to_run(self):
        # given
        home = Path(tempfile.mkdtemp()).resolve()
        (home / ".zsh_history").write_text("expected entailment")
        (home / "tool").mkdir()
        (home / "tool" / "bin").write_text("tool")
        (home / "tool" / "evals").mkdir()
        (home / "tool" / "evals" / "labels.json").write_text("gold")
        (home / "tool" / "evals" / "run").mkdir()
        (home / "tool" / "evals" / "run" / "transcript.jsonl").write_text("recorded")
        sandbox = oolong_eval.sandboxed(
            [home / "tool" / "evals"], [home / "tool" / "evals" / "run"], home=home, needed=[home / "tool"]
        )

        # when
        def read(path):
            return subprocess.run(sandbox + ["cat", str(path)], capture_output=True, text=True).stdout

        # then
        assert read(home / ".zsh_history") == ""
        # what creating a directory in it needs, e.g. Prime Agent the virtual environment of its REPL
        assert subprocess.run(sandbox + ["stat", str(home / "tool")], capture_output=True).returncode == 0
        assert subprocess.run(sandbox + ["ls", str(home)], capture_output=True).returncode != 0
        assert read(home / "tool" / "bin") == "tool"
        assert read(home / "tool" / "evals" / "labels.json") == ""
        assert read(home / "tool" / "evals" / "run" / "transcript.jsonl") == "recorded"

    @unittest.skipUnless(shutil.which(oolong_eval.SANDBOX), "needs sandbox-exec of macOS")
    def test_hides_what_the_agent_may_write_but_not_read(self):
        # given
        home = Path(tempfile.mkdtemp()).resolve()
        (home / ".claude" / "projects" / "earlier").mkdir(parents=True)
        (home / ".claude" / "projects" / "earlier" / "session.jsonl").write_text("161028 pairs")
        (home / ".claude" / "projects" / "own").mkdir()
        (home / ".claude" / "settings.json").write_text("{}")
        sandbox = oolong_eval.sandboxed([], [home / ".claude" / "projects" / "own"], home=home,
                                        needed=[home / ".claude"], hidden=[home / ".claude" / "projects"])

        # when
        def read(path):
            return subprocess.run(sandbox + ["cat", str(path)], capture_output=True, text=True).stdout

        wrote = subprocess.run(sandbox + ["sh", "-c", f'echo log > {home}/.claude/projects/new.log'], capture_output=True)
        own = subprocess.run(sandbox + ["sh", "-c", f'echo mine > {home}/.claude/projects/own/s.jsonl && cat {home}/.claude/projects/own/s.jsonl'],
                             capture_output=True, text=True)

        # then
        assert read(home / ".claude" / "settings.json") == "{}"
        assert read(home / ".claude" / "projects" / "earlier" / "session.jsonl") == ""
        assert wrote.returncode == 0
        assert own.stdout == "mine\n", own.stderr

    @unittest.skipUnless(shutil.which(oolong_eval.SANDBOX), "needs sandbox-exec of macOS")
    def test_keeps_a_run_from_what_others_left_in_the_temporary_directories(self):
        # given
        left = Path(tempfile.mkdtemp(dir=oolong_eval.SHARED_TEMP))
        self.addCleanup(shutil.rmtree, left)
        (left / "train.label").write_text("gold")
        original = Path(tempfile.mkdtemp()) / "context.txt"
        original.write_text("Date: Oct 01, 2023 || User: 1 || Instance: a\n")

        # when
        with oolong_eval.placed(original) as context:
            sandbox = oolong_eval.sandboxed(oolong_eval.temporary_directories(), [context.parent])
            env = oolong_eval.temporary(os.environ, context.parent)
            read = subprocess.run(sandbox + ["cat", str(left / "train.label")], capture_output=True, text=True)
            wrote = subprocess.run(sandbox + ["sh", "-c", 'echo mine > "$TMPDIR/x" && cat "$TMPDIR/x"'],
                                   env=env, capture_output=True, text=True)
            littered = subprocess.run(sandbox + ["sh", "-c", f'echo litter > {left}/litter.txt'], capture_output=True)

        # then
        assert read.returncode != 0 and "gold" not in read.stdout
        assert wrote.stdout == "mine\n", wrote.stderr
        assert littered.returncode != 0 and not (left / "litter.txt").exists()
        assert f"-Djava.io.tmpdir={context.parent / 'tmp'}" in env["JAVA_TOOL_OPTIONS"]
        assert not context.parent.exists()

    @unittest.skipUnless(shutil.which(oolong_eval.SANDBOX), "needs sandbox-exec of macOS")
    def test_withholds_directories_and_the_network_beyond_this_computer(self):
        # given
        withheld = Path(tempfile.mkdtemp()).resolve()
        (withheld / "labels.json").write_text("gold")
        (withheld / "recording").mkdir()
        (withheld / "recording" / "transcript.jsonl").write_text("recorded")
        sandbox = oolong_eval.sandboxed([withheld], [withheld / "recording"])
        local = f"http://127.0.0.1:{self.upstream.server_port}/v1/chat/completions"

        # when
        read = subprocess.run(sandbox + ["cat", str(withheld / "labels.json")], capture_output=True, text=True)
        own = subprocess.run(sandbox + ["cat", str(withheld / "recording" / "transcript.jsonl")],
                             capture_output=True, text=True)
        here = subprocess.run(sandbox + ["curl", "-s", "-m", "5", "-d", "{}", local], capture_output=True, text=True)
        beyond = subprocess.run(sandbox + ["curl", "-s", "-m", "5", "https://example.com"], capture_output=True)

        # then
        assert read.returncode != 0 and "gold" not in read.stdout
        assert own.stdout == "recorded"
        assert here.returncode == 0 and "/v1/chat/completions" in here.stdout
        assert beyond.returncode != 0


class SolvePairsTest(unittest.TestCase):

    def test_carries_an_elided_quantifier_over(self):
        # given
        rows = [
            ["Jan 01, 2023", "1", "a", "entity"],
            ["Jan 01, 2023", "1", "b", "abbreviation"],
            ["Jan 01, 2023", "2", "c", "entity"],
            ["Jan 01, 2023", "3", "d", "entity"],
            ["Jan 01, 2023", "3", "e", "entity"],
        ]
        question = (
            "In the above data, list all pairs of user IDs (no duplicate pairs, list lower ID first) "
            "such that one user has at least one instance with entity and one with abbreviation, "
            "and the other user has exactly one instance with entity. Each of the questions can be labelled"
        )

        # when
        pairs = oolong_eval.solve_pairs(question, rows)

        # then
        assert pairs == {(1, 2)}, pairs


class LeakedTest(unittest.TestCase):

    def test_tells_the_labels_of_a_set_read(self):
        # given
        root = recording("", "")
        (next(root.iterdir()) / "022-2-self-intent.kts").write_text(
            'val dir = "/evals/oolong/data/sets/512k/tasks/multinli-timeline-least_freq"\n'
            'agency.execute(listOf("python3", "-c", py, "$dir/labels.json"))\n'
        )

        # when
        leaked = oolong_eval.leaked(root)

        # then
        assert leaked

    def test_tells_the_source_dataset_fetched(self):
        # given
        root = recording("", "")
        (next(root.iterdir()) / "012-2-self-intent.kts").write_text(
            'val url = "https://huggingface.co/datasets/nyu-mll/multi_nli/resolve/main/data/train.parquet"\n'
        )

        # when
        leaked = oolong_eval.leaked(root)

        # then
        assert leaked

    def test_does_not_tell_the_context_read(self):
        # given
        root = recording("", "")
        (next(root.iterdir()) / "004-2-self-intent.kts").write_text(
            'val lines = java.io.File("/tmp/context-x1y2/context.txt").readLines()\n'
        )

        # when
        leaked = oolong_eval.leaked(root)

        # then
        assert not leaked


class PlacedTest(unittest.TestCase):

    def test_places_a_copy_of_the_context_where_no_path_leads_to_the_gold_answers(self):
        # given
        original = Path(tempfile.mkdtemp()) / "multinli-timeline-least_freq.txt"
        original.write_text("Date: Oct 01, 2023 || User: 1 || Instance: a\n")

        # when
        with oolong_eval.placed(original) as copy:
            text = copy.read_text()
            placed = copy

        # then
        assert text == original.read_text()
        assert oolong_eval.ROOT not in placed.parents
        assert placed.name == "context.txt"
        assert not placed.parent.exists()


class AnnieEnvironmentTest(unittest.TestCase):

    def test_runs_the_python_of_the_build_where_jep_is_installed(self):
        # given
        recording = Path(tempfile.mkdtemp())

        # when
        env = oolong_eval.annie_environment({"VIRTUAL_ENV": "/elsewhere"}, recording, 1000, "high")

        # then
        assert env["VIRTUAL_ENV"] == str(oolong_eval.ROOT / ".venv")
        assert env["ANNIE_COGITATIONS_DIR"] == str(recording)
        assert env["ANNIE_TOKEN_BUDGET"] == "1000"
        assert env["ANNIE_EFFORT"] == "high"


class AwakeTest(unittest.TestCase):

    @unittest.skipUnless(shutil.which(oolong_eval.CAFFEINATE), "needs caffeinate of macOS")
    def test_keeps_this_computer_awake_while_the_evaluation_runs(self):
        # when
        awake = oolong_eval.awake()
        self.addCleanup(awake.wait)
        self.addCleanup(awake.terminate)

        # then
        assert awake.poll() is None
        assert awake.args == [oolong_eval.CAFFEINATE, "-i", "-w", str(os.getpid())]


class ClaudeCodeTest(unittest.TestCase):

    def recording(self, *events):
        recording = Path(tempfile.mkdtemp())
        (recording / "transcript.jsonl").write_text("".join(json.dumps(e) + "\n" for e in events))
        return recording

    @staticmethod
    def assistant(id, *content, parent=None):
        return {"type": "assistant", "parent_tool_use_id": parent, "message": {"id": id, "content": list(content)}}

    @staticmethod
    def result(tool_use_id, text, parent=None):
        return {"type": "user", "parent_tool_use_id": parent, "message": {"content": [
            {"type": "tool_result", "tool_use_id": tool_use_id, "content": [{"type": "text", "text": text}]}]}}

    def test_reads_the_totals_the_subagents_and_the_code_of_a_run(self):
        # given
        recording = self.recording(
            {"type": "system", "subtype": "init", "model": "claude-sonnet-5-5"},
            self.assistant("msg_1", {"type": "thinking", "thinking": "split"},
                           {"type": "tool_use", "id": "t1", "name": "Bash", "input": {"command": "wc -l context.txt"}}),
            self.result("t1", "3190 context.txt"),
            self.assistant("msg_2", {"type": "tool_use", "id": "t2", "name": "Agent",
                                     "input": {"description": "label", "prompt": "1: What is a tonne ?"}}),
            self.assistant("msg_3", {"type": "text", "text": "1: description"}, parent="t2"),
            self.result("t2", "1: description and abstract concept"),
            self.assistant("msg_4", {"type": "text", "text": "Done."}),
            {"type": "result", "result": "Done.", "num_turns": 3, "is_error": False,
             "modelUsage": {"claude-sonnet-5-5": {"inputTokens": 10, "outputTokens": 20,
                                                  "cacheReadInputTokens": 300, "cacheCreationInputTokens": 40},
                            "claude-haiku-5-5": {"inputTokens": 1, "outputTokens": 2,
                                                 "cacheReadInputTokens": 0, "cacheCreationInputTokens": 0}}},
        )

        # when
        totals = oolong_eval.claude_code_totals(recording)
        exchanges = list(oolong_eval.claude_code_exchanges(recording))
        code = oolong_eval.claude_code_code(recording)

        # then
        assert totals["inferences"] == 4
        assert totals["invoked"] == 1
        assert totals["tokens"] == {"input": 11, "cache_read": 300, "cache_creation": 40, "output": 22}, totals
        assert totals["total_tokens"] == 373
        assert exchanges == [("1: What is a tonne ?", "1: description and abstract concept")]
        assert code == ["wc -l context.txt", "1: What is a tonne ?"]
        assert oolong_eval.claude_code_answer(recording) == ("Done.", None)
        assert not oolong_eval.leaked(recording, oolong_eval.CLAUDE_CODE)

    def test_tells_a_run_reaching_for_the_labels(self):
        # given
        recording = self.recording(self.assistant("msg_1", {"type": "tool_use", "id": "t1", "name": "Bash", "input": {
            "command": "curl -sO https://raw.githubusercontent.com/x/trec/train_5500.label"}}))

        # then
        assert oolong_eval.leaked(recording, oolong_eval.CLAUDE_CODE)

    def test_names_the_project_of_its_sessions_after_the_directory(self):
        # then
        assert oolong_eval.claude_code_project(Path("/private/tmp/context-a_b.c")) \
               == Path.home() / ".claude" / "projects" / "-private-tmp-context-a-b-c"


class KeptTest(unittest.TestCase):

    def test_keeps_what_the_agent_wrote_beside_the_context(self):
        # given
        directory, recording = Path(tempfile.mkdtemp()), Path(tempfile.mkdtemp())
        (directory / "context.txt").write_text("Date: Oct 01, 2023 || User: 1 || Instance: a\n")
        (directory / "labels.json").write_text('{"0": "entity"}')
        (directory / "chunks").mkdir()
        (directory / "chunks" / "chunk_0.txt").write_text("0\ta\n")

        # when
        oolong_eval.keep(directory, recording)

        # then
        kept = sorted(str(p.relative_to(recording)) for p in recording.rglob("*") if p.is_file())
        assert kept == ["workspace/chunks/chunk_0.txt", "workspace/labels.json"], kept


if __name__ == "__main__":
    unittest.main()
