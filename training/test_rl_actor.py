"""Numerical/contract fixtures only. These tests are not Minecraft training or performance evidence."""
import math
from pathlib import Path
import tempfile
import unittest
import uuid
import threading
from unittest import mock

import numpy as np

from rl_actor import ActorCritic, inputs, generalized_advantages, STATE_SIZE, ACTION_SIZE
from train_paper_ppo import PaperTrainer, SCHEMA


def frame():
    state = [0.0] * 64
    state[0] = 0.3
    actions = []
    for name, value in (("left", -0.5), ("right", 0.5)):
        features = [0.0] * ACTION_SIZE
        features[0] = value
        actions.append({"id": name, "features": features})
    return {"world": str(uuid.UUID(int=1)), "serverTick": 10, "state": state, "options": actions, "complete": True}


def row(model, session="a", sequence=0, reward=1, terminal=True):
    observed = frame()
    probabilities, value, _ = model.evaluate(observed)
    return {"session": session, "sequence": sequence, "frame": observed, "index": 1,
            "logProbability": math.log(float(probabilities[1])), "value": value,
            "modelVersion": model.version, "reward": reward, "elapsedTicks": 5,
            "terminated": terminal, "truncated": False, "nextValue": 0,
            "appliedTick": observed["serverTick"], "nextWorld": observed["world"], "nextState": observed["state"]}


class ActorTests(unittest.TestCase):
    def test_actor_and_value_gradients_match_numerical_derivatives(self):
        model = ActorCritic(7, hidden=4)
        rows = [row(model)]
        advantages, returns = np.array([0.7]), np.array([0.8])
        gradient, _ = model.gradients(rows, advantages, returns)
        for key, index in (("actor_w", (0, 64)), ("actor_b", (0,)), ("actor_out", (0,)),
                           ("value_w", (0, 0)), ("value_out", (0,))):
            original = model.params[key][index]
            epsilon = 1e-6
            model.params[key][index] = original + epsilon
            _, plus = model.gradients(rows, advantages, returns)
            model.params[key][index] = original - epsilon
            _, minus = model.gradients(rows, advantages, returns)
            model.params[key][index] = original
            numerical = (plus["loss"] - minus["loss"]) / (2 * epsilon)
            self.assertAlmostEqual(numerical, gradient[key][index], places=5)

    def test_positive_advantage_can_change_the_action_probability_without_teacher_labels(self):
        model = ActorCritic(7, hidden=4)
        before = model.evaluate(frame())[0][1]
        model.fit([row(model)], epochs=4, minibatch=1)
        self.assertGreater(model.evaluate(frame())[0][1], before)
        self.assertEqual(model.version, 1)
        self.assertGreater(model.updates, 0)

    def test_initialization_and_old_behavior_versions_are_not_accepted_as_new_training(self):
        model = ActorCritic(7, hidden=4)
        with self.assertRaises(ValueError):
            model.artifact(environment_steps=1, training_run="fixture", rollout_sha256="0" * 64)
        old = row(model)
        model.fit([old], epochs=1)
        with self.assertRaises(ValueError):
            model.fit([old], epochs=1)

    def test_interleaved_environments_and_gaps_do_not_leak_advantages(self):
        base = {"value": 0, "nextValue": 0, "elapsedTicks": 1, "terminated": False, "truncated": False}
        rows = [{**base, "session": "a", "sequence": 0, "reward": 1},
                {**base, "session": "b", "sequence": 0, "reward": 10},
                {**base, "session": "a", "sequence": 1, "reward": 2, "terminated": True}]
        advantages, _ = generalized_advantages(rows, gamma_tick=1, gae_lambda=0.95)
        np.testing.assert_allclose(advantages, [2.9, 10, 2])
        rows[-1]["sequence"] = 2
        advantages, _ = generalized_advantages(rows, gamma_tick=1, gae_lambda=0.95)
        np.testing.assert_allclose(advantages, [1, 10, 2])

    def test_invalid_or_future_label_features_are_not_actor_inputs(self):
        observed = frame()
        state, x = inputs(observed)
        self.assertEqual(x.shape, (2, STATE_SIZE + ACTION_SIZE))
        observed["options"][1]["id"] = "left"
        with self.assertRaises(ValueError):
            inputs(observed)
        observed = frame()
        observed["state"][0] = float("nan")
        with self.assertRaises(ValueError):
            inputs(observed)


class BridgeProtocolTests(unittest.TestCase):
    def test_optimizer_wait_does_not_block_sampling_or_mutate_the_active_policy(self):
        with tempfile.TemporaryDirectory() as temporary:
            trainer = PaperTrainer(Path(temporary) / "run", seed=7, rollout_size=32, maximum=32, max_bytes=16 * 1024 * 1024)
            entered, release = threading.Event(), threading.Event()
            original = ActorCritic.fit

            def delayed(model, rows):
                entered.set()
                if not release.wait(5):
                    raise RuntimeError("numerical fixture update not released")
                return original(model, rows, epochs=1)

            try:
                with mock.patch.object(ActorCritic, "fit", delayed):
                    session = str(uuid.uuid4())
                    before = trainer.model.params["actor_w"].copy()
                    for sequence in range(32):
                        transition = row(trainer.model, session=session, sequence=sequence)
                        transition.pop("nextValue")
                        trainer.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                        "frame": frame(), "transitions": [transition]})
                    self.assertTrue(entered.wait(2))
                    self.assertIsNotNone(trainer.training)
                    # Sampling remains on a frozen parameter snapshot while the optimizer is deliberately blocked.
                    answer = trainer.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                             "frame": frame(), "transitions": []})
                    self.assertEqual(answer["modelVersion"], 0)
                    np.testing.assert_array_equal(before, trainer.model.params["actor_w"])
                    self.assertFalse((trainer.output / "minecraft-rl-actor.json").exists())
                    release.set()
                    trainer.poll_training(wait=True)
                    self.assertEqual(trainer.model.version, 1)
                    # This artifact is a temporary numerical/protocol fixture, never actual Minecraft learning evidence.
                    self.assertTrue((trainer.output / "minecraft-rl-actor.json").exists())
            finally:
                release.set()
                trainer.close()

    def test_no_artifact_is_created_while_waiting_and_wrong_environment_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "run"
            trainer = PaperTrainer(directory, seed=7, rollout_size=32, maximum=32, max_bytes=8 * 1024 * 1024)
            try:
                self.assertFalse((directory / "minecraft-rl-actor.json").exists())
                with self.assertRaises(ValueError):
                    trainer.handle({"schema": SCHEMA, "environment": "toy", "id": str(uuid.uuid4()), "frame": frame()})
                self.assertEqual(trainer.steps, 0)
            finally:
                trainer.close()

    def test_duplicate_acknowledgements_do_not_become_duplicate_game_experience(self):
        with tempfile.TemporaryDirectory() as temporary:
            trainer = PaperTrainer(Path(temporary) / "run", seed=7, rollout_size=32, maximum=32, max_bytes=8 * 1024 * 1024)
            try:
                transition = row(trainer.model, session=str(uuid.uuid4()))
                transition.pop("nextValue")
                packet = {"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()), "frame": frame(), "transitions": [transition]}
                trainer.handle(packet)
                packet["id"] = str(uuid.uuid4())
                trainer.handle(packet)
                self.assertEqual(trainer.steps, 1)  # Protocol fixture, not actual Paper gameplay.
                self.assertFalse((trainer.output / "minecraft-rl-actor.json").exists())
            finally:
                trainer.close()


if __name__ == "__main__":
    unittest.main()
