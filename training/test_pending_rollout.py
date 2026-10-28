"""Numerical persistence fixtures only; never autonomous Paper training or capability evidence."""
import copy
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest import mock
import uuid

import brain_session
import model_catalog
import pending_rollout
from rl_actor import ActorCritic
from train_paper_ppo import PaperTrainer, verify_runtime_session
from test_rl_actor import frame, row


def send(trainer, session, sequence, *, sample=None):
    sample = row(trainer.model, session=session, sequence=sequence) if sample is None else dict(sample)
    sample.pop("nextValue", None)
    return trainer.handle({"schema": "minecraft-paper-ppo/v2", "environment": "minecraft-paper",
                           "id": str(uuid.uuid4()), "frame": frame(), "transitions": [sample]})


class PendingRolloutTests(unittest.TestCase):
    def prepared(self, root):
        trainer = PaperTrainer(root / "source", seed=7, rollout_size=32, maximum=64, max_bytes=16*1024*1024)
        session = str(uuid.uuid4())
        for sequence in range(32):
            send(trainer, session, sequence)
        trainer.poll_training(wait=True)
        for sequence in range(32, 50):
            send(trainer, session, sequence)
        trainer.close()
        return brain_session.load(root / "source/runtime-session.json")

    def test_off_resume_preserves_pending_rng_counters_and_name_then_trains_each_row_once(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            state = self.prepared(root)
            self.assertEqual((state["environmentSteps"], state["trainedEnvironmentSteps"], state["pendingRolloutTransitions"]), (50, 32, 18))
            catalog = model_catalog.ModelCatalog(root / "catalog")
            state = dict(state, modelName="brain")
            catalog.save("brain", state)
            resumed = PaperTrainer(root / "resumed", seed=99, rollout_size=32, maximum=64, max_bytes=16*1024*1024,
                    resume=Path(state["checkpoint"]), restore_state=state, learning_enabled=False, model_catalog_path=catalog.root)
            try:
                original = json.loads(Path(state["checkpoint"]).read_bytes())["optimizer"]
                self.assertEqual({k:v.tolist() for k,v in resumed.model.params.items()}, original["params"])
                self.assertEqual(resumed.model.rng.bit_generator.state, state["rng"])
                resumed.persist_session()
                off = catalog.load("brain")["session"]
                self.assertFalse(off["learningEnabled"])
                self.assertEqual((off["environmentSteps"], off["trainedEnvironmentSteps"], off["pendingRolloutTransitions"]), (50, 32, 18))
                duplicate = copy.deepcopy(resumed.rows[-1])
                send(resumed, duplicate["session"], duplicate["sequence"], sample=duplicate)
                self.assertEqual(len(resumed.rows), 18)
                resumed.control({"schema": "minecraft-brain-control/v1", "id": str(uuid.uuid4()),
                                 "operation": "learning", "brainId": resumed.brain_id, "enabled": True})
                new_session = str(uuid.uuid4())
                for sequence in range(14):
                    send(resumed, new_session, sequence)
                resumed.poll_training(wait=True)
                learned = brain_session.load(root / "resumed/runtime-session.json")
                self.assertEqual((learned["environmentSteps"], learned["trainedEnvironmentSteps"], learned["pendingRolloutTransitions"]), (64, 64, 0))
                report = json.loads((root / "resumed/training-report.json").read_text())
                self.assertEqual((report["restoredPendingTransitions"], report["newCollectedEnvironmentSteps"]), (18, 14))
            finally:
                resumed.close()

    def test_legacy_pending_needs_explicit_exact_suffix_migration_and_corruption_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            state = self.prepared(root)
            legacy = {k:v for k,v in state.items() if k not in {"pendingRollout", "collectedEnvironmentSteps", "trainedEnvironmentSteps"}}
            old = root / "legacy.json"
            brain_session.save(old, legacy)
            with self.assertRaisesRegex(ValueError, "explicit legacy migration"):
                verify_runtime_session(legacy)
            migrated = model_catalog.migrate_pending_session(old, root / "migration/session.json", name="brain")
            self.assertEqual(brain_session.load(old), legacy)
            self.assertEqual((migrated["environmentSteps"], migrated["trainedEnvironmentSteps"]), (50, 32))
            verify_runtime_session(migrated)
            snapshot = Path(migrated["pendingRollout"]["path"])
            snapshot.write_bytes(snapshot.read_bytes() + b"!")
            with self.assertRaises(ValueError):
                verify_runtime_session(migrated)

    def test_automatic_session_save_is_deferred_while_a_buffer_is_in_the_optimizer(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            trainer = PaperTrainer(root / "run", seed=7, rollout_size=32, maximum=64, max_bytes=16*1024*1024)
            entered, release = threading.Event(), threading.Event()
            original = ActorCritic.fit
            def delayed(model, rows):
                entered.set()
                if not release.wait(5):
                    raise RuntimeError("fixture optimizer not released")
                return original(model, rows)
            try:
                before = (root / "run/runtime-session.json").read_bytes()
                with mock.patch.object(ActorCritic, "fit", delayed):
                    session = str(uuid.uuid4())
                    for sequence in range(32):
                        send(trainer, session, sequence)
                    self.assertTrue(entered.wait(2))
                    self.assertFalse(trainer.persist_session())
                    self.assertEqual((root / "run/runtime-session.json").read_bytes(), before)
                    release.set()
                    trainer.poll_training(wait=True)
                final = brain_session.load(root / "run/runtime-session.json")
                self.assertEqual((final["trainedEnvironmentSteps"], final["pendingRolloutTransitions"]), (32, 0))
            finally:
                release.set()
                trainer.close()


if __name__ == "__main__":
    unittest.main()
