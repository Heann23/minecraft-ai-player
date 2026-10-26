"""Numerical/contract fixtures only. These tests are not Minecraft training or performance evidence."""
import math
import json
from pathlib import Path
import tempfile
import unittest
import uuid
import threading
from unittest import mock

import numpy as np

from rl_actor import ActorCritic, inputs, generalized_advantages, STATE_SIZE, ACTION_SIZE
from train_paper_ppo import PaperTrainer, SCHEMA, resume_checkpoint


def frame():
    state = [0.0] * STATE_SIZE
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
    def test_explicit_context_upgrade_preserves_legacy_actor_critic_and_adam_without_inventing_learning(self):
        model = ActorCritic(7, hidden=4)
        model.fit([row(model)], epochs=1)
        legacy = model.optimizer_state()
        legacy["contract"] = "minecraft-primitive-candidates/v1"
        for group in ("params", "first", "second"):
            for key in ("actor_w", "value_w"):
                value = np.asarray(legacy[group][key])
                legacy[group][key] = np.concatenate((value[:, :64], value[:, STATE_SIZE:]), axis=1).tolist()
        with self.assertRaises(ValueError):
            ActorCritic.from_optimizer_state(legacy)
        upgraded = ActorCritic.upgrade_legacy_context(legacy)
        observed = frame()
        observed["state"] = np.linspace(-0.3, 0.3, STATE_SIZE).tolist()
        old_state = np.asarray(observed["state"][:64])
        old_x = np.concatenate((np.tile(old_state, (2, 1)), [option["features"] for option in observed["options"]]), axis=1)
        p = {key: np.asarray(value) for key, value in legacy["params"].items()}
        logits = np.tanh(old_x @ p["actor_w"].T + p["actor_b"]) @ p["actor_out"] + p["actor_bias"][0]
        expected = np.exp(logits-logits.max()); expected /= expected.sum()
        expected_value = np.tanh(p["value_w"] @ old_state + p["value_b"]) @ p["value_out"] + p["value_bias"][0]
        probabilities, value, _ = upgraded.evaluate(observed)
        np.testing.assert_allclose(expected, probabilities, atol=1e-14)
        self.assertAlmostEqual(float(expected_value), value, places=12)
        for group in ("params", "first", "second"):
            np.testing.assert_array_equal(getattr(upgraded, group)["actor_w"][:, 64:STATE_SIZE], 0)
            np.testing.assert_array_equal(getattr(upgraded, group)["value_w"][:, 64:STATE_SIZE], 0)
        self.assertEqual(upgraded.version, model.version)
        self.assertEqual(upgraded.updates, model.updates)
        self.assertEqual(upgraded.rng.bit_generator.state, model.rng.bit_generator.state)

    def test_checkpoint_retains_critic_adam_counters_and_random_stream_for_exact_continuation(self):
        model = ActorCritic(7, hidden=4)
        model.fit([row(model)], epochs=1)
        restored = ActorCritic.from_optimizer_state(model.optimizer_state())
        for group in ("params", "first", "second"):
            for key, value in getattr(model, group).items():
                np.testing.assert_array_equal(value, getattr(restored, group)[key])
        self.assertEqual(model.sample(frame()), restored.sample(frame()))
        following = [row(model)]
        model.fit(following, epochs=1)
        restored.fit(following, epochs=1)
        self.assertEqual(model.version, restored.version)
        self.assertEqual(model.updates, restored.updates)
        for key, value in model.params.items():
            np.testing.assert_array_equal(value, restored.params[key])
        broken = model.optimizer_state()
        broken["second"]["actor_bias"][0] = -1
        with self.assertRaises(ValueError):
            ActorCritic.from_optimizer_state(broken)

    def test_actor_and_value_gradients_match_numerical_derivatives(self):
        model = ActorCritic(7, hidden=4)
        rows = [row(model)]
        advantages, returns = np.array([0.7]), np.array([0.8])
        gradient, _ = model.gradients(rows, advantages, returns)
        for key, index in (("actor_w", (0, STATE_SIZE)), ("actor_b", (0,)), ("actor_out", (0,)),
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
    def test_resume_checks_raw_prefix_and_checkpoint_identity_without_reusing_previous_rows(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "first"
            trainer = PaperTrainer(output, seed=7, rollout_size=32, maximum=32, max_bytes=16 * 1024 * 1024)
            try:
                for sequence in range(32):
                    transition = row(trainer.model, session=str(uuid.UUID(int=5)), sequence=sequence)
                    transition.pop("nextValue")
                    trainer.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                    "frame": frame(), "transitions": [transition]})
                trainer.poll_training(wait=True)
            finally:
                trainer.close()
            checkpoint = output / "optimizer-checkpoint.json"
            continued = PaperTrainer(Path(temporary) / "second", seed=99, rollout_size=32, maximum=32,
                                     max_bytes=16 * 1024 * 1024, resume=checkpoint)
            try:
                self.assertEqual(continued.model.version, 1)
                self.assertEqual(continued.parent_steps, 32)
                self.assertEqual(continued.steps, 0)
                self.assertEqual(continued.rows, [])
                self.assertFalse((continued.output / "minecraft-rl-actor.json").exists())
                self.assertEqual(continued.parents[-1]["trainingRun"], trainer.run)
            finally:
                continued.close()
            with self.assertRaises((ValueError, KeyError)):
                resume_checkpoint(output / "minecraft-rl-actor.json")
            raw = output / "paper-rollouts.jsonl"
            original = raw.read_bytes()
            raw.write_bytes(b"!" + original[1:])
            with self.assertRaisesRegex(ValueError, "rollout provenance mismatch"):
                resume_checkpoint(checkpoint)
            raw.write_bytes(original)
            corrupted = json.loads(checkpoint.read_text())
            corrupted["environmentSteps"] += 1
            checkpoint.write_text(json.dumps(corrupted))
            with self.assertRaisesRegex(ValueError, "checkpoint provenance mismatch"):
                resume_checkpoint(checkpoint)

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
