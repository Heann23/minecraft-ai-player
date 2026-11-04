"""Temporary persistence fixtures only; not Minecraft models, game training or performance evidence."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import checkpoint_store as store


class CheckpointStoreTests(unittest.TestCase):
    def payload(self, version):
        return ({"contract": "fixture", "value": version}, {"run": "fixture", "value": version}, {"modelVersion": version})

    def test_pointer_write_failure_preserves_the_previous_generation_and_ignores_pending_directories(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = store.publish(root, *self.payload(1))
            pointer = (root / "latest-checkpoint.json").read_bytes()
            original = store.durable_replace
            def interrupted(path, data):
                if path.name == "latest-checkpoint.json":
                    raise OSError("fixture interrupted pointer commit")
                original(path, data)
            with mock.patch.object(store, "durable_replace", interrupted):
                with self.assertRaises(OSError):
                    store.publish(root, *self.payload(2))
            self.assertEqual(pointer, (root / "latest-checkpoint.json").read_bytes())
            (root / "checkpoints/.pending-fixture").mkdir()
            self.assertEqual(next(store.candidates(root)), first / "optimizer-checkpoint.json")
            self.assertEqual(json.loads((first / "optimizer-checkpoint.json").read_text())["value"], 1)

    def test_corrupt_latest_member_or_pointer_recovers_only_complete_verified_generations(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = store.publish(root, *self.payload(1))
            second = store.publish(root, *self.payload(2))
            (second / "minecraft-rl-actor.json").write_text("corrupted")
            self.assertEqual(list(store.candidates(root)), [first / "optimizer-checkpoint.json"])
            (root / "latest-checkpoint.json").write_text(json.dumps({"schema": store.SCHEMA,
                "bundle": "../../outside", "manifestSha256": "0"*64}))
            self.assertEqual(list(store.candidates(root)), [first / "optimizer-checkpoint.json"])
            (first / "training-report.json").write_text("corrupted")
            self.assertEqual(list(store.candidates(root)), [])
            # Broken bundles are preserved for diagnosis and are never silently repaired/reset.
            self.assertTrue(first.exists()); self.assertTrue(second.exists())


if __name__ == "__main__":
    unittest.main()
