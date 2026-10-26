"""Switch/persistence fixtures only. No Minecraft server or claims of training/game competence."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import uuid

import brain_session
from model_catalog import ModelCatalog
from train_paper_ppo import PaperTrainer, BrainRuntime, SCHEMA, resume_checkpoint


class BrainRuntimeTests(unittest.TestCase):
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
            original = brain_session.save
            def interrupted(path, state):
                if path == root / "active/runtime-session.json" and state["brainId"] == second["brainId"]:
                    raise OSError("interrupted active-session publication")
                original(path, state)
            try:
                with self.assertRaises(ValueError):
                    runtime.control(self.packet(runtime, "missing"))
                with mock.patch.object(brain_session, "save", interrupted):
                    with self.assertRaises(ValueError):
                        runtime.control(self.packet(runtime, "second"))
                self.assertIs(runtime.active, old)
                self.assertEqual((root / "active/runtime-session.json").read_bytes(), before)
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
