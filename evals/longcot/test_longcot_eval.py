# python3 -m unittest discover evals/longcot
import unittest
from collections import Counter

import longcot_eval


def questions(**sizes):
    """Questions of the templates in the given numbers, e.g. `questions(HM=50, MCM=25)`."""
    return [{"question_id": f"{name}_{i}", "problem": {"template": name}, "prompt": "", "answer": None}
            for name, size in sizes.items() for i in range(size)]


class SampleTest(unittest.TestCase):

    def test_spreads_over_templates_in_proportion_to_their_sizes(self):
        # when
        sampled = longcot_eval.sample(questions(HM=50, DistMem=25, MCM=25), 8)

        # then
        assert Counter(longcot_eval.template(q) for q in sampled) == {"HM": 4, "DistMem": 2, "MCM": 2}

    def test_covers_every_template_when_there_are_as_many_tasks(self):
        # when
        sampled = longcot_eval.sample(questions(BlocksWorld=10, Dungeon=15, Sokoban=10, Sudoku=10), 4)

        # then
        assert {longcot_eval.template(q) for q in sampled} == {"BlocksWorld", "Dungeon", "Sokoban", "Sudoku"}

    def test_takes_the_same_tasks_for_the_same_seed_whatever_the_order(self):
        # given
        given = questions(easy1=50, easy2=50)

        # when
        first = longcot_eval.sample(given, 10, seed=3)
        reversed_order = longcot_eval.sample(list(reversed(given)), 10, seed=3)
        other_seed = longcot_eval.sample(given, 10, seed=4)

        # then
        assert first == reversed_order
        assert first != other_seed

    def test_a_smaller_sample_is_part_of_a_larger_one(self):
        # given
        given = questions(linear=23, dag=17, backtracking=22)

        # when
        small = longcot_eval.sample(given, 5)
        large = longcot_eval.sample(given, 10)

        # then
        assert all(q in large for q in small)


class TaskNameTest(unittest.TestCase):

    def test_names_a_task_by_its_domain_template_and_id(self):
        # when
        name = longcot_eval.task_name("chess", {"question_id": "101", "problem": {"template": "uci_to_fen"}})

        # then
        assert name == "chess-uci_to_fen-101"

    def test_does_not_repeat_the_template_an_id_begins_with(self):
        # when
        name = longcot_eval.task_name("cs", {"question_id": "HM_easy_1", "problem": {"template": "HM"}})

        # then
        assert name == "cs-HM_easy_1"


class EstimateTest(unittest.TestCase):

    def test_weighs_domain_means_by_the_domain_sizes(self):
        # given
        results = [
            {"domain": "logic", "score": 1.0},
            {"domain": "logic", "score": 0.0},
            {"domain": "math", "score": 1.0},
        ]

        # when
        estimated = longcot_eval.estimate(results, {"logic": 100, "math": 300})

        # then
        assert estimated == (0.5 * 100 + 1.0 * 300) / 400

    def test_leaves_out_domains_not_run(self):
        # when
        estimated = longcot_eval.estimate([{"domain": "cs", "score": 1.0}], {"cs": 100, "chess": 100})

        # then
        assert estimated == 1.0


class EnunciatedTest(unittest.TestCase):

    def test_reads_the_text_of_the_last_enunciation(self):
        # given
        stdout = (
            "Enunciation(actant=self, content=[Text(text=draft, contentType=text/plain)])\n"
            "Enunciation(actant=self, content=[Text(text=[1, 2, 3], contentType=text/plain)])\n"
        )

        # when
        output, impediment = longcot_eval.enunciated(stdout)

        # then
        assert output == "[1, 2, 3]"
        assert impediment is None


if __name__ == "__main__":
    unittest.main()
