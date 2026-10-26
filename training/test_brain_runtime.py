"""Switch/persistence fixtures only. No Minecraft server or claims of training/game competence."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import uuid

import brain_session
from model_catalog import ModelCatalog
from train_paper_ppo import PaperTrainer, BrainRuntime, SCHEMA, resume_checkpoint, verify_runtime_session


class BrainRuntimeTests(unittest.TestCase):
    def test_named_pin_preserves_name_without_mutating_saved_mode_and_override_is_explicit(self):
        from paper_lab import named_session
        from train_paper_ppo import resolve_learning_mode
        original = {"learningEnabled": False, "modelName": "older-alias"}
        pinned = named_session({"name": "current", "session": original})
        self.assertEqual(pinned["modelName"], "current")
        self.assertEqual(original["modelName"], "older-alias")
        self.assertFalse(resolve_learning_mode(pinned))
        self.assertTrue(resolve_learning_mode(pinned, learning="on"))
        self.assertFalse(resolve_learning_mode({"learningEnabled": True}, learning="off"))
        self.assertFalse(resolve_learning_mode({"learningEnabled": True}, inference_only=True))
        with self.assertRaises(ValueError):
            resolve_learning_mode(pinned, learning="on", inference_only=True)

    def test_corrupt_or_missing_session_restores_exact_committed_state_without_rewriting_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = self.initial(root / "initial", 7)
            path = root / "runtime-session.json"
            brain_session.save(path, original, recoverable=True)
            path.write_text("damaged original")
            restored, recovery = brain_session.restore(path, verify=verify_runtime_session)
            self.assertEqual(restored, original)
            self.assertEqual(recovery["brainId"], original["brainId"])
            self.assertEqual(path.read_text(), "damaged original")
            path.unlink()
            missing, recovery = brain_session.restore(path, verify=verify_runtime_session)
            self.assertEqual(missing, original)
            self.assertFalse(path.exists())

    def test_failed_primary_publication_cannot_promote_staged_recovery_state(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = self.initial(root / "initial", 7)
            path = root / "runtime-session.json"
            brain_session.save(path, original, recoverable=True)
            replace = brain_session.durable_replace
            def interrupted(target, data):
                if target == path:
                    raise OSError("primary was not committed")
                replace(target, data)
            with mock.patch.object(brain_session, "durable_replace", interrupted):
                with self.assertRaises(OSError):
                    brain_session.save(path, dict(original, learningEnabled=True), recoverable=True)
            self.assertEqual(brain_session.load(path), original)
            path.write_text("corrupt")
            with self.assertRaisesRegex(ValueError, "not committed"):
                brain_session.restore(path, verify=verify_runtime_session)

    def test_committed_primary_survives_witness_failure_without_falsely_rolling_back_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = self.initial(root / "initial", 7)
            path = root / "runtime-session.json"
            brain_session.save(path, original, recoverable=True)
            replace = brain_session.durable_replace
            def interrupted(target, data):
                if target.name.endswith(".recovery-commit.json"):
                    raise OSError("witness write failed")
                replace(target, data)
            changed = dict(original, learningEnabled=True)
            with mock.patch.object(brain_session, "durable_replace", interrupted):
                brain_session.save(path, changed, recoverable=True)
            restored, recovery = brain_session.restore(path, verify=verify_runtime_session)
            self.assertEqual(restored, changed)
            self.assertIsNone(recovery)
            path.write_text("corrupt")
            with self.assertRaises(ValueError):
                brain_session.restore(path, verify=verify_runtime_session)

    def test_recovery_rejects_corrupt_checkpoint_and_never_selects_another_brain(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = self.initial(root / "initial", 7)
            other = self.initial(root / "other", 11)
            path = root / "runtime-session.json"
            brain_session.save(path, original, recoverable=True)
            path.write_text("corrupt")
            Path(original["checkpoint"]).write_text("damaged weights")
            with self.assertRaises((ValueError, KeyError)):
                brain_session.restore(path, verify=verify_runtime_session)
            verify_runtime_session(other)  # A valid unrelated model exists but is never chosen.

    def test_low_disk_space_rejects_runtime_copy_before_creating_output(self):
        from paper_lab import require_runtime_space
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "artifact.jar"
            artifact.write_bytes(b"fixture")
            with mock.patch("paper_lab.shutil.disk_usage", return_value=mock.Mock(free=0)):
                with self.assertRaisesRegex(ValueError, "no server started"):
                    require_runtime_space(root / "new-output", root, artifact, artifact)
            self.assertFalse((root / "new-output").exists())

    def test_idle_initial_session_resume_keeps_the_exact_checkpoint_without_a_duplicate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = self.initial(root / "initial", 7)
            resumed = PaperTrainer(root / "resumed", seed=99, rollout_size=32, maximum=64, max_bytes=8*1024*1024,
                    start_state="resume", resume=Path(original["checkpoint"]), restore_state=original, learning_enabled=False)
            resumed.close()
            restored = brain_session.load(root / "resumed/runtime-session.json")
            self.assertEqual(restored["checkpointSha256"], original["checkpointSha256"])
            self.assertEqual(restored["checkpoint"], original["checkpoint"])
            self.assertEqual(restored["rng"], original["rng"])
            self.assertFalse((root / "resumed/checkpoints").exists())

    def initial(self, output, seed):
        trainer = PaperTrainer(output, seed=seed, rollout_size=32, maximum=64, max_bytes=16*1024*1024,
                               start_state="fresh", learning_enabled=False)
        trainer.close()
        return brain_session.load(output / "runtime-session.json")

    def pair(self, root):
        first, second = self.initial(root / "first", 7), self.initial(root / "second", 11)
        catalog = ModelCatalog(root / "catalog")
        catalog.save("first", first)
        catalog.save("second", dict(second, learningEnabled=True))
        trainer = PaperTrainer(root / "active", seed=99, rollout_size=32, maximum=64, max_bytes=16*1024*1024,
                start_state="resume", resume=Path(first["checkpoint"]), restore_state=first, learning_enabled=False,
                model_catalog_path=catalog.root)
        return BrainRuntime(trainer), first, second

    def packet(self, runtime, name):
        return {"schema": "minecraft-brain-control/v1", "id": str(uuid.uuid4()), "operation": "model-load",
                "brainId": runtime.active.brain_id, "name": name}

    def test_named_active_mode_and_rng_refresh_after_commit_without_changing_selection(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runtime, first, second = self.pair(root)
            catalog = runtime.active.catalog
            catalog.select("first")
            selected = (catalog.root / "selected-model.json").read_bytes()
            try:
                runtime.control(self.packet(runtime, "second"))
                self.assertFalse(catalog.load("second")["session"]["learningEnabled"])
                active = runtime.active
                active.model.rng.random()
                active.persist_session()
                saved = catalog.load("second")["session"]
                self.assertEqual(saved["rng"], active.model.rng.bit_generator.state)
                self.assertEqual(saved, brain_session.load(root / "active/runtime-session.json"))
                revisions = len(list(catalog.root.glob("revision-*.json")))
                active.persist_session()
                self.assertEqual(len(list(catalog.root.glob("revision-*.json"))), revisions)
                self.assertEqual((catalog.root / "selected-model.json").read_bytes(), selected)
                self.assertEqual(active.catalog_sync_state, "saved")
            finally:
                runtime.close()

    def test_catalog_lock_failure_preserves_durable_runtime_mode_then_retries(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runtime, first, second = self.pair(root)
            try:
                runtime.control(self.packet(runtime, "second"))
                active = runtime.active
                lock = active.catalog.root / "catalog.lock"
                lock.write_text("another writer")
                response = active.control({"schema": "minecraft-brain-control/v1", "id": str(uuid.uuid4()),
                        "operation": "learning", "brainId": active.brain_id, "enabled": True})
                self.assertTrue(response["ok"])
                self.assertTrue(brain_session.load(root / "active/runtime-session.json")["learningEnabled"])
                self.assertEqual(active.catalog_sync_state, "failed")
                self.assertFalse(active.catalog.load("second")["session"]["learningEnabled"])
                lock.unlink()
                active.persist_session()
                self.assertTrue(active.catalog.load("second")["session"]["learningEnabled"])
                self.assertEqual(active.catalog_sync_state, "saved")
            finally:
                runtime.close()

    def test_prepared_runtime_never_refreshes_autonomous_catalog(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runtime, first, second = self.pair(root)
            try:
                runtime.control(self.packet(runtime, "second"))
                before = (runtime.catalog.root / "second.json").read_bytes()
                runtime.active.restricted_training_use = True
                runtime.active.model.rng.random()
                runtime.active.persist_session()
                self.assertEqual((runtime.catalog.root / "second.json").read_bytes(), before)
                self.assertEqual(runtime.active.catalog_sync_state, "excluded-prepared-scenario")
            finally:
                runtime.close()

    def test_selected_model_rng_and_learning_off_survive_primary_session_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runtime, first, second = self.pair(root)
            try:
                response = runtime.control(self.packet(runtime, "second"))
                self.assertEqual(response["previousBrainId"], first["brainId"])
                self.assertEqual(runtime.active.brain_id, second["brainId"])
                self.assertFalse(runtime.active.learning_enabled)
                reference, _ = resume_checkpoint(Path(second["checkpoint"]), allow_initial=True)
                self.assertEqual(runtime.active.model.optimizer_state(allow_initial=True), reference.optimizer_state(allow_initial=True))
                restored = brain_session.load(root / "active/runtime-session.json")
                self.assertEqual(restored["brainId"], second["brainId"])
                self.assertEqual(restored["rng"], second["rng"])
                self.assertEqual(restored["modelName"], "second")
                resumed = PaperTrainer(root / "restart", seed=123, rollout_size=32, maximum=64, max_bytes=8*1024*1024,
                        start_state="resume", resume=Path(restored["checkpoint"]), restore_state=restored,
                        learning_enabled=restored["learningEnabled"])
                try:
                    self.assertEqual(resumed.brain_id, second["brainId"])
                    self.assertEqual(resumed.model.rng.bit_generator.state, second["rng"])
                    self.assertFalse(resumed.learning_enabled)
                finally:
                    resumed.close()
            finally:
                runtime.close()
            self.assertEqual(brain_session.load(root / "active/runtime-session.json")["brainId"], second["brainId"])

    def test_failed_alias_publication_or_missing_model_preserves_the_old_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runtime, first, second = self.pair(root)
            old = runtime.active
            before = (root / "active/runtime-session.json").read_bytes()
            catalog_before = (old.catalog.root / "second.json").read_bytes()
            original = brain_session.save
            def interrupted(path, state, **options):
                if path == root / "active/runtime-session.json" and state["brainId"] == second["brainId"]:
                    raise OSError("interrupted active-session publication")
                original(path, state, **options)
            try:
                with self.assertRaises(ValueError):
                    runtime.control(self.packet(runtime, "missing"))
                with mock.patch.object(brain_session, "save", interrupted):
                    with self.assertRaises(ValueError):
                        runtime.control(self.packet(runtime, "second"))
                self.assertIs(runtime.active, old)
                self.assertEqual((root / "active/runtime-session.json").read_bytes(), before)
                self.assertEqual((old.catalog.root / "second.json").read_bytes(), catalog_before)
                self.assertFalse(runtime.require_identity)
            finally:
                runtime.close()

    def test_old_brain_or_old_epoch_cannot_sample_or_train_after_switch(self):
        from test_rl_actor import frame
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runtime, first, second = self.pair(root)
            try:
                load = self.packet(runtime, "second")
                runtime.control(load)
                query = {"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                         "frame": frame(), "transitions": [], "brainId": second["brainId"], "controlEpoch": load["id"]}
                runtime.handle(query)
                self.assertEqual(runtime.decisions, 1)
                runtime.control(self.packet(runtime, "second"))
                unchanged = json.dumps(runtime.active.model.rng.bit_generator.state, sort_keys=True)
                with self.assertRaises(ValueError):
                    runtime.handle(query)
                stale = dict(query, brainId=first["brainId"], transitions=[{"fixtureOldTransition": True}])
                with self.assertRaises(ValueError):
                    runtime.handle(stale)
                self.assertEqual(runtime.decisions, 1)
                self.assertEqual(runtime.steps, 0)
                self.assertEqual(runtime.active.model.updates, 0)
                self.assertEqual(json.dumps(runtime.active.model.rng.bit_generator.state, sort_keys=True), unchanged)
                evidence = json.loads((root / "active/rejected-brain-queries.jsonl").read_text())
                self.assertEqual(evidence["reason"], "brain-or-load-epoch-mismatch")
            finally:
                runtime.close()


if __name__ == "__main__":
    unittest.main()
