"""Persistence fixtures only. No Paper server, game training or competence evidence."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import brain_session
import model_catalog
from train_paper_ppo import PaperTrainer


class ModelCatalogTests(unittest.TestCase):
    def test_automatic_refresh_never_recreates_missing_name_or_rewinds_environment_steps(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = self.initial(root / "source")
            catalog = model_catalog.ModelCatalog(root / "catalog")
            with self.assertRaises(ValueError):
                catalog.save("missing", state, require_existing=True)
            self.assertFalse((catalog.root / "missing.json").exists())
            catalog.save("brain", state)
            # A genuinely collected pending row is not an invented counter on an initial checkpoint.
            from test_rl_actor import frame, row
            import uuid
            continued = PaperTrainer(root / "continued", seed=99, rollout_size=32, maximum=32, max_bytes=4*1024*1024,
                                     resume=Path(state["checkpoint"]), restore_state=state, learning_enabled=True)
            sample = row(continued.model, session=str(uuid.uuid4()))
            sample.pop("nextValue")
            continued.handle({"schema": "minecraft-paper-ppo/v2", "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                              "frame": frame(), "transitions": [sample]})
            continued.close()
            catalog.save("brain", brain_session.load(root / "continued/runtime-session.json"))
            before = (catalog.root / "brain.json").read_bytes()
            with self.assertRaises(ValueError):
                catalog.save("brain", state, require_existing=True)
            self.assertEqual((catalog.root / "brain.json").read_bytes(), before)

    def initial(self, output, *, seed=7):
        trainer = PaperTrainer(output, seed=seed, rollout_size=32, maximum=32, max_bytes=4*1024*1024,
                               start_state="fresh", learning_enabled=False)
        trainer.close()
        return brain_session.load(output / "runtime-session.json")

    def test_named_restore_preserves_identity_weights_rng_and_learning_off_without_training(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = self.initial(root / "original")
            catalog = model_catalog.ModelCatalog(root / "catalog")
            catalog.save("fresh-off", state)
            catalog.select("fresh-off")
            restored = catalog.selected()["session"]
            self.assertEqual(restored, state)
            trainer = PaperTrainer(root / "resumed", seed=99, rollout_size=32, maximum=32, max_bytes=4*1024*1024,
                    start_state="resume", resume=Path(restored["checkpoint"]), restore_state=restored,
                    learning_enabled=restored["learningEnabled"])
            try:
                self.assertEqual(trainer.brain_id, state["brainId"])
                self.assertFalse(trainer.learning_enabled)
                self.assertEqual(trainer.model.updates, 0)
                self.assertEqual(trainer.model.rng.bit_generator.state, state["rng"])
                self.assertEqual(trainer.parent["checkpointSha256"], state["checkpointSha256"])
            finally:
                trainer.close()
            catalog.save("fresh-off", brain_session.load(root / "resumed" / "runtime-session.json"))
            self.assertEqual(catalog.selected()["session"]["brainId"], state["brainId"])

    def test_a_name_cannot_replace_another_brain_and_corrupt_selection_never_initializes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = self.initial(root / "first")
            other = self.initial(root / "other", seed=9)
            catalog = model_catalog.ModelCatalog(root / "catalog")
            catalog.save("brain", state)
            with self.assertRaises(ValueError):
                catalog.save("brain", other)
            catalog.select("brain")
            (catalog.root / "selected-model.json").write_text("{}")
            with self.assertRaises(ValueError):
                catalog.selected()
            self.assertEqual(catalog.load("brain")["session"], state)

    def test_interrupted_pointer_publish_keeps_previous_named_revision(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = self.initial(root / "source")
            catalog = model_catalog.ModelCatalog(root / "catalog")
            catalog.save("brain", state)
            changed = dict(state, learningEnabled=True)
            original = model_catalog.write_record
            def fail_pointer(path, value):
                if path.name == "brain.json":
                    raise OSError("interrupted pointer")
                original(path, value)
            with mock.patch.object(model_catalog, "write_record", fail_pointer):
                with self.assertRaises(OSError):
                    catalog.save("brain", changed)
            self.assertFalse(catalog.load("brain")["session"]["learningEnabled"])
            self.assertFalse((catalog.root / "catalog.lock").exists())

    def test_prepared_scenario_checkpoint_and_its_descendants_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = self.initial(root / "scenario" / "training")
            (root / "scenario" / "manifest.json").write_text(json.dumps({"preparedScenario": True}))
            catalog = model_catalog.ModelCatalog(root / "catalog")
            with self.assertRaises(ValueError):
                catalog.save("scenario", state)
            trainer = PaperTrainer(root / "child", seed=9, rollout_size=32, maximum=32, max_bytes=4*1024*1024,
                    start_state="resume", resume=Path(state["checkpoint"]), learning_enabled=False)
            trainer.close()
            with self.assertRaises(ValueError):
                catalog.save("child", brain_session.load(root / "child" / "runtime-session.json"))

    def test_corrupt_checkpoint_is_listed_as_invalid_and_not_loaded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = self.initial(root / "source")
            catalog = model_catalog.ModelCatalog(root / "catalog")
            catalog.save("brain", state)
            Path(state["checkpoint"]).write_text("corrupt")
            self.assertFalse(catalog.list()[0]["valid"])
            with self.assertRaises((ValueError, KeyError)):
                catalog.load("brain")

    def test_names_and_existing_writer_lock_are_not_bypassed(self):
        for name in ("../brain", "BRAIN", "con", "revision-test", "selected-model", "a"*65):
            with self.assertRaises(ValueError):
                model_catalog.model_name(name)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = self.initial(root / "source")
            catalog = model_catalog.ModelCatalog(root / "catalog")
            catalog.root.mkdir()
            (catalog.root / "catalog.lock").write_text("active writer")
            with self.assertRaises(FileExistsError):
                catalog.save("brain", state)
            self.assertEqual((catalog.root / "catalog.lock").read_text(), "active writer")

    def test_connected_commands_save_same_brain_and_do_not_change_learning_or_rng(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            trainer = PaperTrainer(root / "source", seed=7, rollout_size=32, maximum=32, max_bytes=4*1024*1024,
                                   start_state="fresh", learning_enabled=False, model_catalog_path=root / "catalog")
            try:
                import uuid
                before = json.dumps(trainer.model.rng.bit_generator.state, sort_keys=True)
                def command(operation, name="", brain=None):
                    return trainer.control({"schema": "minecraft-brain-control/v1", "id": str(uuid.uuid4()),
                        "operation": operation, "name": name, "brainId": brain or trainer.brain_id})
                saved = command("model-save", "current")
                self.assertTrue(saved["ok"])
                self.assertEqual(saved["models"][0]["brainId"], trainer.brain_id)
                self.assertFalse(saved["models"][0]["learningEnabled"])
                self.assertEqual(command("model-list")["models"][0]["name"], "current")
                self.assertEqual(command("model-info", "current")["models"], saved["models"])
                trainer.catalog.select("current")
                selected_before = (trainer.catalog.root / "selected-model.json").read_bytes()
                created = command("model-new", "new-candidate")
                fresh = created["models"][0]
                self.assertNotEqual(fresh["brainId"], trainer.brain_id)
                self.assertTrue(fresh["initializationOnly"])
                self.assertEqual(fresh["policyVersion"], 0)
                self.assertEqual(fresh["gradientUpdates"], 0)
                self.assertFalse(fresh["learningEnabled"])
                self.assertEqual(created["weightsSha256"], saved["weightsSha256"])
                self.assertEqual((trainer.catalog.root / "selected-model.json").read_bytes(), selected_before)
                with self.assertRaises(ValueError):
                    command("model-new", "current")
                self.assertEqual(json.dumps(trainer.model.rng.bit_generator.state, sort_keys=True), before)
                self.assertEqual(trainer.model.updates, 0)
                with self.assertRaises(ValueError):
                    command("model-save", "wrong", str(uuid.uuid4()))
                (root / "manifest.json").write_text(json.dumps({"preparedScenario": True}))
                with self.assertRaises(ValueError):
                    command("model-save", "prepared")
                with self.assertRaises(ValueError):
                    command("model-new", "prepared-new")
                self.assertFalse((root / "catalog/prepared.json").exists())
            finally:
                trainer.close()

    def test_nested_plugin_runs_and_restored_session_flags_cannot_hide_prepared_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            nested = root / "lab/server/plugins/MinecraftAI/brain-runtime/run-fixture"
            state = self.initial(nested)
            (root / "lab/manifest.json").write_text(json.dumps({"preparedScenario": True}))
            self.assertTrue(model_catalog.restricted_training_use(state["checkpoint"]))
            with self.assertRaises(ValueError):
                model_catalog.ModelCatalog(root / "catalog").save("nested", state)
            normal = self.initial(root / "normal")
            marked = dict(normal, notForAutonomousTraining=True)
            trainer = PaperTrainer(root / "restored", seed=9, rollout_size=32, maximum=32, max_bytes=4*1024*1024,
                    start_state="resume", resume=Path(normal["checkpoint"]), restore_state=marked, learning_enabled=False)
            try:
                self.assertTrue(trainer.restricted_training_use)
            finally:
                trainer.close()
            with self.assertRaises(ValueError):
                model_catalog.ModelCatalog(root / "catalog").save("restored", brain_session.load(root / "restored/runtime-session.json"))


if __name__ == "__main__":
    unittest.main()
