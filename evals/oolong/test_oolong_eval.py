# python3 -m unittest discover evals/oolong
import tempfile
import unittest
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


if __name__ == "__main__":
    unittest.main()
