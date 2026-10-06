"""Offline BC contracts executed by required CI."""
import unittest

from train_goal_bc import agreement, predict, train


class GoalBCTest(unittest.TestCase):
    def test_separable_teacher_examples_learn_supported_choices(self):
        samples = [{"x": [1.0, 0.0], "labelId": 0}, {"x": [0.0, 1.0], "labelId": 1}]
        model = train(samples, 3, 2)
        self.assertEqual(0, predict(model, [1.0, 0.0]))
        self.assertEqual(1, predict(model, [0.0, 1.0]))
        self.assertEqual([0, 1], model["supportedGoalIds"])

    def test_training_is_deterministic_and_does_not_mutate_inputs(self):
        samples = [{"x": [1.0], "labelId": 1}, {"x": [0.0], "labelId": 0}]
        first = train(samples, 2, 1)
        second = train(samples, 2, 1)
        self.assertEqual(first, second)
        self.assertEqual([1.0], samples[0]["x"])

    def test_empty_training_is_rejected(self):
        with self.assertRaises(ValueError):
            train([], 2, 1)

    def test_empty_evaluation_is_unavailable_and_unseen_label_counts_as_error(self):
        self.assertIsNone(agreement([], [])["accuracy"])
        result = agreement([{"labelId": 2}], [0])
        self.assertEqual(0, result["accuracy"])
        self.assertEqual(1, result["perGoal"]["2"]["examples"])


if __name__ == "__main__":
    unittest.main()
