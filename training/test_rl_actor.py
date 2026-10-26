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
import brain_session

from rl_actor import ActorCritic, inputs, generalized_advantages, STATE_SIZE, ACTION_SIZE
from train_paper_ppo import PaperTrainer, SCHEMA, CONTROL_SCHEMA, resume_checkpoint, serve, exchange_request, validate_transition


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
    def test_reward_versions_preserve_legacy_rows_and_validate_actual_progress_event_metadata(self):
        model = ActorCritic(7, hidden=4)
        sample = row(model, session=str(uuid.uuid4()))
        sample.pop("nextValue")
        validate_transition(sample)
        sample.update(rewardContract="survival-progress/v2", objectiveEvents=["advancement:minecraft:story/mine_stone"])
        validate_transition(sample)
        for invalid in (dict(sample, rewardContract="survival-progress/v1"),
                        dict(sample, objectiveEvents=["Teacher says mine"]),
                        dict(sample, objectiveEvents=sample["objectiveEvents"] * 2)):
            with self.assertRaises(ValueError):
                validate_transition(invalid)

    def test_checkpoint_report_counts_only_the_reward_contracts_of_its_accepted_rollout(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "run"
            trainer = PaperTrainer(output, seed=7, rollout_size=32, maximum=32, max_bytes=4*1024*1024)
            session = str(uuid.uuid4())
            try:
                for start in (0, 16):
                    rows = []
                    for sequence in range(start, start+16):
                        sample = row(trainer.model, session=session, sequence=sequence)
                        sample.pop("nextValue")
                        sample.update(rewardContract="survival-progress/v2", objectiveEvents=[])
                        rows.append(sample)
                    trainer.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                    "frame": frame(), "transitions": rows})
            finally:
                trainer.close()
            report = json.loads((output / "training-report.json").read_bytes())
            self.assertEqual(report["rewardContracts"], {"survival-progress/v2": 32})

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

    def test_v2_upgrade_preserves_all_old_columns_and_moments_with_zero_new_gear_influence(self):
        model = ActorCritic(11, hidden=4)
        model.fit([row(model)], epochs=1)
        legacy = model.optimizer_state()
        legacy["contract"] = "minecraft-primitive-candidates/v2"
        for group in ("params", "first", "second"):
            for key in ("actor_w", "value_w"):
                values = np.asarray(legacy[group][key])
                legacy[group][key] = np.concatenate((values[:, :128], values[:, STATE_SIZE:]), axis=1).tolist()
        with self.assertRaises(ValueError):
            ActorCritic.from_optimizer_state(legacy)
        upgraded = ActorCritic.upgrade_legacy_context(legacy)
        observed = frame()
        base_probabilities, base_value, _ = upgraded.evaluate(observed)
        observed["state"][128:] = np.linspace(-1, 1, STATE_SIZE - 128).tolist()
        probabilities, value, _ = upgraded.evaluate(observed)
        np.testing.assert_array_equal(base_probabilities, probabilities)
        self.assertEqual(base_value, value)
        for group in ("params", "first", "second"):
            for key, old in legacy[group].items():
                actual = getattr(upgraded, group)[key]
                if key in ("actor_w", "value_w"):
                    np.testing.assert_array_equal(actual[:, 128:STATE_SIZE], 0)
                    actual = np.concatenate((actual[:, :128], actual[:, STATE_SIZE:]), axis=1)
                np.testing.assert_array_equal(actual, old)
        self.assertEqual(upgraded.rng.bit_generator.state, legacy["rng"])
        self.assertEqual((upgraded.version, upgraded.updates), (legacy["version"], legacy["updates"]))

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
    def test_learning_controls_preserve_the_same_weights_identity_and_durable_disabled_state(self):
        with tempfile.TemporaryDirectory() as temporary:
            trainer = PaperTrainer(Path(temporary) / "run", seed=7, rollout_size=32, maximum=32, max_bytes=16*1024*1024)
            def control(enabled, brain=None):
                return trainer.control({"schema": CONTROL_SCHEMA, "id": str(uuid.uuid4()), "operation": "learning",
                                        "brainId": brain or trainer.brain_id, "enabled": enabled})
            try:
                off = control(False)
                self.assertFalse(off["status"]["metadata"]["learningEnabled"])
                self.assertFalse(brain_session.load(trainer.output / "runtime-session.json")["learningEnabled"])
                trainer.handle({"schema": SCHEMA, "id": str(uuid.uuid4()), "environment": "minecraft-paper", "frame": frame()})
                on = control(True)
                self.assertTrue(on["status"]["metadata"]["learningEnabled"])
                self.assertEqual(off["weightsSha256"], on["weightsSha256"])
                self.assertEqual(off["status"]["metadata"]["brainId"], on["status"]["metadata"]["brainId"])
                self.assertEqual(trainer.model.updates, 0)
                with self.assertRaises(ValueError):
                    control(False, str(uuid.uuid4()))
                self.assertTrue(trainer.learning_enabled)
            finally:
                trainer.close()

    def test_session_restores_the_same_fresh_brain_rng_and_disabled_learning_without_world_data(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            trainer = PaperTrainer(root / "first", seed=7, rollout_size=32, maximum=32, max_bytes=16*1024*1024,
                                   learning_enabled=False)
            trainer.model.sample(frame())
            trainer.close()
            session_file = trainer.output / "runtime-session.json"
            session = brain_session.load(session_file)
            restored = PaperTrainer(root / "second", seed=99, rollout_size=32, maximum=32, max_bytes=16*1024*1024,
                                    resume=Path(session["checkpoint"]), learning_enabled=session["learningEnabled"], restore_state=session)
            try:
                self.assertEqual(restored.brain_id, trainer.brain_id)
                self.assertFalse(restored.learning_enabled)
                self.assertEqual(trainer.model.sample(frame()), restored.model.sample(frame()))
                self.assertNotIn("inventory", session)
                self.assertNotIn("position", session)
            finally:
                restored.close()
            corrupted = json.loads(session_file.read_text())
            corrupted["payload"]["learningEnabled"] = True
            session_file.write_text(json.dumps(corrupted))
            with self.assertRaisesRegex(ValueError, "integrity mismatch"):
                brain_session.load(session_file)

    def test_fresh_initial_state_can_resume_without_becoming_a_pretrained_model(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            trainer = PaperTrainer(base / "fresh", seed=7, rollout_size=32, maximum=32, max_bytes=16*1024*1024,
                                   learning_enabled=False, start_state="fresh")
            original = {key: value.copy() for key, value in trainer.model.params.items()}
            identity = trainer.brain_id
            trainer.close()
            self.assertFalse((base / "fresh/minecraft-rl-actor.json").exists())
            with self.assertRaises(ValueError):
                resume_checkpoint(base / "fresh")
            resumed = PaperTrainer(base / "resume", seed=99, rollout_size=32, maximum=32, max_bytes=16*1024*1024,
                                   resume=base / "fresh", learning_enabled=False)
            try:
                self.assertEqual(resumed.brain_id, identity)
                self.assertEqual(resumed.model.updates, 0)
                for key, value in original.items():
                    np.testing.assert_array_equal(value, resumed.model.params[key])
            finally:
                resumed.close()
            with self.assertRaises(ValueError):
                PaperTrainer(base / "pretrained", seed=99, rollout_size=32, maximum=32, max_bytes=16*1024*1024,
                             resume=base / "fresh", start_state="pretrained", learning_enabled=False)

    def test_fresh_inference_uses_the_same_network_without_updates_or_retroactive_learning(self):
        with tempfile.TemporaryDirectory() as temporary:
            trainer = PaperTrainer(Path(temporary) / "run", seed=7, rollout_size=32, maximum=32,
                                   max_bytes=16*1024*1024, start_state="fresh", learning_enabled=False)
            before = {key: value.copy() for key, value in trainer.model.params.items()}
            try:
                transition = row(trainer.model, session=str(uuid.uuid4()))
                transition.pop("nextValue")
                packet = {"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                          "frame": frame(), "transitions": [transition]}
                response = trainer.handle(packet)
                self.assertFalse(response["metadata"]["learningEnabled"])
                self.assertEqual(response["metadata"]["trainingStartState"], "fresh")
                self.assertEqual(trainer.steps, 0)
                self.assertEqual(trainer.model.updates, 0)
                self.assertEqual(trainer.model.version, 0)
                for key, value in before.items():
                    np.testing.assert_array_equal(value, trainer.model.params[key])
                self.assertEqual(trainer.discard_reasons, {"learning-disabled": 1})
                # Even if a later authorized controller enables learning, this old off-period row stays excluded.
                trainer.learning_enabled = True
                packet["id"] = str(uuid.uuid4())
                trainer.handle(packet)
                self.assertEqual(trainer.steps, 0)
                self.assertFalse((trainer.output / "minecraft-rl-actor.json").exists())
            finally:
                trainer.close()

    def test_checkpoint_permission_failure_is_not_hidden_as_a_transport_retry(self):
        with tempfile.TemporaryDirectory() as temporary:
            channel = Path(temporary)
            request = channel / (str(uuid.uuid4()) + ".request.json")
            request.write_text(json.dumps({"id": request.stem.removesuffix(".request")}))
            trainer = mock.Mock()
            trainer.handle.side_effect = PermissionError("checkpoint storage fixture")
            with self.assertRaises(PermissionError):
                exchange_request(request, channel, trainer, {})

    def test_windows_cleanup_lock_retries_the_same_response_without_learning_or_sampling_twice(self):
        with tempfile.TemporaryDirectory() as temporary:
            channel = Path(temporary)
            identity = str(uuid.uuid4())
            request = channel / (identity + ".request.json")
            request.write_text(json.dumps({"id": identity}))
            trainer = mock.Mock()
            trainer.handle.return_value = {"id": identity, "candidate": "fixture"}
            pending = {}
            unlink = Path.unlink
            calls = 0
            def locked(path, *args, **kwargs):
                nonlocal calls
                if path == request and calls == 0:
                    calls += 1
                    raise PermissionError("Windows sharing violation fixture")
                return unlink(path, *args, **kwargs)
            with mock.patch.object(Path, "unlink", locked):
                exchange_request(request, channel, trainer, pending)
                self.assertTrue(request.exists())
                response = (channel / (identity + ".response.json")).read_bytes()
                exchange_request(request, channel, trainer, pending)
            self.assertEqual(trainer.handle.call_count, 1)
            self.assertEqual(response, (channel / (identity + ".response.json")).read_bytes())
            self.assertEqual(pending, {})
            self.assertFalse(request.exists())

    def test_directory_recovery_checks_prior_raw_prefix_and_preserves_an_older_complete_optimizer(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "run"
            trainer = PaperTrainer(output, seed=7, rollout_size=32, maximum=64, max_bytes=16*1024*1024)
            try:
                for batch in range(2):
                    for number in range(32):
                        transition = row(trainer.model, session=str(uuid.UUID(int=5)), sequence=batch*32+number)
                        transition.pop("nextValue")
                        trainer.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                        "frame": frame(), "transitions": [transition]})
                    trainer.poll_training(wait=True)
            finally:
                trainer.close()
            pointer = json.loads((output / "latest-checkpoint.json").read_text())
            latest = output / pointer["bundle"]
            (latest / "minecraft-rl-actor.json").write_text("interrupted export fixture")
            model, parent = resume_checkpoint(output)
            self.assertEqual(model.version, 1)
            self.assertEqual(parent["environmentSteps"], 32)
            self.assertIn("checkpoints/version-", parent["recoveredCheckpoint"])
            self.assertTrue(latest.exists())
            older = output / parent["recoveredCheckpoint"]
            (older.parent / "minecraft-rl-actor.json").write_text("also corrupted fixture")
            with self.assertRaises(ValueError):
                resume_checkpoint(output, allow_initial=True)

    def test_episode_diagnostics_include_excluded_death_and_keep_unfinished_episodes_incomplete(self):
        with tempfile.TemporaryDirectory() as temporary:
            trainer = PaperTrainer(Path(temporary) / "run", seed=7, rollout_size=32, maximum=32, max_bytes=16*1024*1024)
            session = str(uuid.uuid4())
            try:
                first = row(trainer.model, session=session, sequence=0, terminal=False)
                first.pop("nextValue")
                second = row(trainer.model, session=session, sequence=2, reward=-10, terminal=True)
                second.pop("nextValue"); second["modelVersion"] = 1
                trainer.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                "frame": frame(), "transitions": [first, second]})
                record = json.loads((trainer.output / "episode-outcomes.jsonl").read_text())
                self.assertEqual(record["observedTransitions"], 2)
                self.assertEqual(record["missingSequenceCount"], 1)
                self.assertEqual(record["actualServerTicks"], 10)
                self.assertEqual(record["rewardSum"], -9)
                self.assertFalse(record["completeObservedSequence"])
                self.assertTrue(record["terminated"])
                self.assertEqual(trainer.steps, 1)
                self.assertEqual(trainer.death_episodes, 1)
                unfinished = row(trainer.model, session=str(uuid.uuid4()), terminal=False)
                unfinished.pop("nextValue")
                trainer.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                "frame": frame(), "transitions": [unfinished]})
            finally:
                trainer.close()
            records = [json.loads(line) for line in (trainer.output / "episode-outcomes.jsonl").read_text().splitlines()]
            self.assertEqual(len(records), 2)
            self.assertFalse(records[1]["episodeEnded"])
            self.assertFalse(records[1]["completeObservedSequence"])

    def test_explicit_stop_closes_the_trainer_without_inventing_an_update_from_an_incomplete_buffer(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            trainer = PaperTrainer(base / "run", seed=7, rollout_size=32, maximum=32, max_bytes=16*1024*1024)
            stopped = base / "stop.request"
            stopped.touch()
            serve(base / "channel", trainer, stopped)
            self.assertTrue(trainer.raw.closed)
            self.assertTrue(trainer.discarded_raw.closed)
            self.assertIsNone(trainer.training)
            self.assertFalse((trainer.output / "minecraft-rl-actor.json").exists())

    def test_off_policy_death_is_excluded_from_ppo_but_preserved_with_its_actual_outcome(self):
        with tempfile.TemporaryDirectory() as temporary:
            trainer = PaperTrainer(Path(temporary) / "run", seed=7, rollout_size=32, maximum=32, max_bytes=16*1024*1024)
            try:
                death = row(trainer.model, session=str(uuid.uuid4()), reward=-10, terminal=True)
                death.pop("nextValue")
                death["modelVersion"] = 1
                trainer.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                "frame": frame(), "transitions": [death]})
                self.assertEqual(trainer.steps, 0)
                self.assertEqual(trainer.rows, [])
                self.assertEqual(trainer.discarded_off_policy, 1)
                preserved = json.loads((trainer.output / "discarded-transitions.jsonl").read_text())
                self.assertEqual(preserved["transition"], death)
                self.assertEqual(preserved["reason"], "off-policy-version")
                self.assertEqual((trainer.output / "paper-rollouts.jsonl").stat().st_size, 0)
                self.assertFalse((trainer.output / "minecraft-rl-actor.json").exists())
            finally:
                trainer.close()
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
                self.assertEqual(continued.brain_id, trainer.brain_id)
                status = continued.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                           "frame": frame(), "transitions": []})
                self.assertEqual(status["metadata"]["brainId"], trainer.brain_id)
                self.assertEqual(status["metadata"]["trainingStartState"], "resume")
                self.assertEqual(status["environmentSteps"], 32)
                self.assertEqual(status["metadata"]["gradientUpdates"], trainer.model.updates)
                self.assertEqual(continued.steps, 0)
                self.assertEqual(continued.rows, [])
                self.assertFalse((continued.output / "minecraft-rl-actor.json").exists())
                self.assertEqual(continued.parents[-1]["trainingRun"], trainer.run)
            finally:
                continued.close()
            pretrained = PaperTrainer(Path(temporary) / "pretrained-inference", seed=99, rollout_size=32, maximum=32,
                                      max_bytes=16*1024*1024, resume=checkpoint, start_state="pretrained", learning_enabled=False)
            try:
                self.assertEqual(pretrained.brain_id, trainer.brain_id)
                self.assertEqual(pretrained.training_start_state, "pretrained")
                self.assertFalse(pretrained.learning_enabled)
                for key, value in trainer.model.params.items():
                    np.testing.assert_array_equal(value, pretrained.model.params[key])
                self.assertEqual(pretrained.model.updates, trainer.model.updates)
            finally:
                pretrained.close()
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
