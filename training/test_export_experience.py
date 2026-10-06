"""Contracts for developer dataset boundaries; run by CI, not local gameplay."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

from export_experience import audit


def episode(origins=("AUTONOMOUS",)):
    rows = [{"type": "episode_start", "schemaVersion": 1, "observationVersion": 1,
             "episodeId": "bot-example", "pluginVersion": "fixture", "worldSeed": 123, "reason": "START"}]
    for index, origin in enumerate(origins):
        rows.append({"type": "decision", "episodeId": "bot-example", "decisionId": index,
                     "tick": index * 20,
                     "observation": {"schemaVersion": 1, "tick": index * 20,
                                     **{section: {} for section in ("player", "inventory", "environment", "progress", "memory", "task")}},
                     "teacher": {"goal": "IDLE", "origin": origin, "score": 999},
                     "shadow": {"goal": "FUTURE_LABEL"},
                     "executed": {"goal": "IDLE", "escaping": False, "actions": [{"name": "Wait"}]}})
        rows.append({"type": "action", "episodeId": "bot-example", "decisionId": index,
                     "index": 0, "name": "Wait", "startTick": index * 20,
                     "endTick": index * 20 + 10, "status": "SUCCESS"})
        rows.append({"type": "outcome", "episodeId": "bot-example", "decisionId": index,
                     "tick": index * 20 + 10, "outcome": "SUCCEEDED", "goalAchieved": False})
    rows.append({"type": "episode_end", "episodeId": "bot-example", "reason": "STOPPED", "decisions": len(origins)})
    return rows


class ExportBoundariesTest(unittest.TestCase):
    def run_audit(self, rows):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "example.jsonl"
            source.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
            return audit(source, {"IDLE"})

    def test_requested_and_forced_do_not_become_teacher_examples(self):
        report, examples = self.run_audit(episode(("AUTONOMOUS", "REQUESTED", "FORCED")))
        self.assertEqual([], report["errors"])
        self.assertEqual(1, len(examples))
        self.assertEqual({"REQUESTED": 1, "FORCED": 1}, report["excluded"])
        self.assertEqual({"schemaVersion", "tick", "player", "inventory", "environment", "progress", "memory", "task"}, set(examples[0]["observation"]))
        self.assertNotIn("teacher", examples[0])
        self.assertNotIn("shadow", examples[0])
        self.assertNotIn("executed", examples[0])

    def test_failed_teacher_choice_is_not_rewritten_as_success(self):
        rows = episode()
        rows[2]["status"] = "FAILED"
        rows[3]["outcome"] = "FAILED"
        report, examples = self.run_audit(rows)
        self.assertEqual([], report["errors"])
        self.assertEqual("FAILED", examples[0]["result"]["outcome"])
        self.assertEqual("IDLE", examples[0]["label"])

    def test_missing_outcome_rejects_whole_episode(self):
        rows = episode()
        rows.pop(3)
        report, examples = self.run_audit(rows)
        self.assertIn("decision outcomes missing", report["errors"])
        self.assertEqual([], examples)

    def test_open_or_unsupported_episode_is_not_training_data(self):
        for rows in (episode()[:-1], episode()):
            rows = copy.deepcopy(rows)
            rows[0]["schemaVersion"] = 2
            report, examples = self.run_audit(rows)
            self.assertTrue(report["errors"])
            self.assertEqual([], examples)

    def test_malformed_plan_is_reported_without_crashing(self):
        rows = episode()
        rows[1]["executed"] = None
        report, examples = self.run_audit(rows)
        self.assertTrue(report["errors"])
        self.assertEqual([], examples)

    def test_same_seed_different_episode_names_stays_in_one_partition(self):
        first_report, first = self.run_audit(episode())
        rows = episode()
        for row in rows:
            row["episodeId"] = "different-ai-respawn"
        second_report, second = self.run_audit(rows)
        self.assertEqual([], first_report["errors"] + second_report["errors"])
        self.assertEqual(first[0]["split"], second[0]["split"])
        self.assertNotEqual(first[0]["episodeKey"], second[0]["episodeKey"])


if __name__ == "__main__":
    unittest.main()
