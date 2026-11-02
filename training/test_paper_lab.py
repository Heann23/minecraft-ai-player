"""Plan boundary fixtures only. These tests never start Paper, train a model or claim survival performance."""
import argparse
import json
import queue
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from paper_lab import read_plan, validate_options, summarize_game_log, validate_plan_extension, read_scenario, confirm_model_save, confirm_model_command, reserve_random_seed, requested_objective, objective_acknowledgement


class SeedPlanTests(unittest.TestCase):
    def test_wrong_source_is_rejected_before_seed_reservation_or_runtime_work(self):
        from paper_lab import run
        with mock.patch("paper_lab.subprocess.check_output", return_value="actual-head\n"), \
                mock.patch("paper_lab.reserve_random_seed") as reserve, \
                mock.patch("paper_lab.require_runtime_space") as runtime:
            with self.assertRaisesRegex(ValueError, "source commit"):
                run(argparse.Namespace(source_commit="different-head"))
            reserve.assert_not_called()
            runtime.assert_not_called()

    def test_completion_watcher_ignores_old_goal_and_waits_for_a_complete_accepted_row(self):
        from paper_lab import ObjectiveCompletionWatcher
        goal = {"id": "new-goal", "world": "current-world", "item": "DIRT", "count": 1}
        def row(identity, sequence):
            return {"session": "session", "sequence": sequence, "taskCompletion": {
                "objectiveId": identity, "world": goal["world"], "item": "DIRT", "required": 1,
                "beforeOwned": 0, "afterOwned": 1}}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "rows.jsonl"
            watcher = ObjectiveCompletionWatcher()
            self.assertFalse(watcher.poll(path, goal))
            path.write_text(json.dumps(row("previous-world-goal", 0)) + "\n" + json.dumps(row(goal["id"], 1)))
            self.assertFalse(watcher.poll(path, goal))
            with path.open("a") as stream:
                stream.write("\n")
            self.assertTrue(watcher.poll(path, goal))
            self.assertEqual(watcher.match["sequence"], 1)
            self.assertEqual(watcher.match["completion"]["objectiveId"], goal["id"])
            path.write_text("")
            with self.assertRaises(ValueError):
                watcher.poll(path, goal)

    def test_java_inference_seed_is_explicit_bounded_and_never_a_training_override(self):
        from paper_lab import inference_seed_config
        config = "policy:\n  inference-seed: null\n  mask-objective-input: false\n"
        self.assertEqual(inference_seed_config(config, argparse.Namespace(mode="evaluate")), config)
        for seed in (-(2**63), 0, 2**63-1):
            changed = inference_seed_config(config, argparse.Namespace(mode="evaluate", inference_seed=seed))
            self.assertEqual(changed, config.replace("inference-seed: null", "inference-seed: " + str(seed)))
        for mode, seed in (("train", 0), ("evaluate", True), ("evaluate", 2**63), ("evaluate", -(2**63)-1)):
            with self.assertRaises(ValueError):
                inference_seed_config(config, argparse.Namespace(mode=mode, inference_seed=seed))
        with self.assertRaises(ValueError):
            inference_seed_config("policy:\n", argparse.Namespace(mode="evaluate", inference_seed=0))

    def test_goal_opportunity_distinguishes_distant_sight_retained_candidate_and_missing_evidence(self):
        prefix = "[AI:RLLearner] GoalSample world=00000000-0000-0000-0000-000000000001 goal=00000000-0000-0000-0000-000000000002"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "console.log"
            path.write_text("\n".join(prefix + f" tick={tick} item=OAK_LOG required=1 owned=0 matched=true" + evidence
                for tick, evidence in ((10, ""),
                    (20, " visibleRays=1 candidates=0 freshCandidates=0 chosenTarget=false"),
                    (40, " visibleRays=0 candidates=1 freshCandidates=0 chosenTarget=true"))))
            summary = summarize_game_log(path)["goalObservationSummaries"][0]
            self.assertEqual(summary["samples"], 3)
            self.assertEqual(summary["opportunitySamples"], 2)
            self.assertEqual(summary["firstObservedTargetTick"], 20)
            self.assertEqual(summary["firstTargetCandidateTick"], 40)
            self.assertEqual(summary["freshTargetCandidateFrames"], 0)
            self.assertEqual(summary["chosenTargetFrames"], 1)

    def test_goal_samples_preserve_brief_achievement_after_inventory_loss_and_world_mismatch(self):
        world = "00000000-0000-0000-0000-000000000001"
        goal = "00000000-0000-0000-0000-000000000002"
        prefix = "[AI:RLLearner] GoalSample world=" + world + " goal=" + goal
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "console.log"
            path.write_text("\n".join(prefix + f" tick={tick} item=OAK_LOG required=1 owned={owned} matched={matched}"
                for tick, owned, matched in ((10, 99, "false"), (20, 0, "true"), (40, 1, "true"), (70, 0, "true"))))
            summary = summarize_game_log(path)["goalObservationSummaries"][0]
            self.assertEqual(summary["maxOwned"], 1)
            self.assertEqual(summary["firstObservedAchievedTick"], 40)
            self.assertEqual(summary["maxObservationGapTicks"], 30)
            self.assertEqual(summary["worldMismatchSamples"], 1)

    def test_objective_masking_is_explicit_and_does_not_change_reward_or_backend_settings(self):
        from paper_lab import objective_input_config
        config = "policy:\n  mask-objective-input: false\n  allow-training: true\n  mode: training\n"
        self.assertEqual(objective_input_config(config, False), config)
        self.assertEqual(objective_input_config(config, True), config.replace("mask-objective-input: false", "mask-objective-input: true"))
        for malformed in ("policy:\n", config + "  mask-objective-input: false\n"):
            with self.assertRaises(ValueError):
                objective_input_config(malformed, True)

    def test_objective_requires_both_exact_fields_and_matching_server_admission(self):
        args = argparse.Namespace(objective_item="OAK_LOG", objective_count=4)
        goal = requested_objective(args)
        self.assertEqual(goal, {"item": "OAK_LOG", "count": 4})
        identity = "00000000-0000-0000-0000-000000000001"
        line = "[INFO] [AIObjective] name=RLLearner item=OAK_LOG count=4 id=" + identity + " world=" + identity
        self.assertEqual(objective_acknowledgement(line, goal)["id"], identity)
        for invalid in (line.replace("OAK_LOG", "OAK_PLANKS"), line.replace("count=4", "count=5"),
                        line.replace("RLLearner", "Other"), "작업 목표 기록을 변경했습니다."):
            self.assertIsNone(objective_acknowledgement(invalid, goal))
        for item, count in (("OAK_LOG", None), (None, 4), ("#WOOD", 4), ("OAK_LOG", 0), ("OAK_LOG;stop", 4)):
            with self.assertRaises(ValueError):
                requested_objective(argparse.Namespace(objective_item=item, objective_count=count))
        self.assertIsNone(requested_objective(argparse.Namespace()))

    def test_random_campaign_rejects_reuse_across_runs_and_partitions_and_preserves_base(self):
        base = {"schema": "minecraft-paper-seed-plan/v1", "trainSeeds": ["1"], "evaluationSeeds": ["2"]}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            plan, training = reserve_random_seed(root, base, "train", draw=mock.Mock(side_effect=[1, 2, 3]))
            self.assertEqual(training, "3")
            self.assertIn("3", plan["trainSeeds"])
            plan, evaluation = reserve_random_seed(root, base, "evaluate", draw=mock.Mock(side_effect=[3, 2**64-1]))
            self.assertEqual(evaluation, "-1")
            self.assertIn("3", plan["trainSeeds"])
            self.assertIn("-1", plan["evaluationSeeds"])
            self.assertFalse(set(plan["trainSeeds"]) & set(plan["evaluationSeeds"]))
            self.assertEqual(base["trainSeeds"], ["1"])

    def test_corrupt_or_locked_campaign_is_never_reinitialized_or_unlocked(self):
        base = {"schema": "minecraft-paper-seed-plan/v1", "trainSeeds": ["1"], "evaluationSeeds": ["2"]}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reserve_random_seed(root, base, "train", draw=lambda _: 3)
            record = root / "seed-campaign.json"
            record.write_text("corrupt")
            draw = mock.Mock(return_value=4)
            with self.assertRaises(ValueError):
                reserve_random_seed(root, base, "train", draw=draw)
            draw.assert_not_called()
            self.assertEqual(record.read_text(), "corrupt")
            lock = root / "seed-campaign.lock"
            lock.write_text("another owner")
            with self.assertRaises(FileExistsError):
                reserve_random_seed(root, base, "train", draw=draw)
            self.assertEqual(lock.read_text(), "another owner")

    def test_seed_reassignment_and_collision_exhaustion_preserve_campaign(self):
        base = {"schema": "minecraft-paper-seed-plan/v1", "trainSeeds": ["1"], "evaluationSeeds": ["2"]}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reserve_random_seed(root, base, "train", draw=lambda _: 2**63)
            record = root / "seed-campaign.json"
            previous = record.read_bytes()
            with self.assertRaises(ValueError):
                reserve_random_seed(root, dict(base, evaluationSeeds=["2", str(-(2**63))]), "evaluate")
            self.assertEqual(record.read_bytes(), previous)
            with self.assertRaisesRegex(ValueError, "collision"):
                reserve_random_seed(root, base, "train", draw=lambda _: 1)
            self.assertEqual(record.read_bytes(), previous)

    def test_final_model_save_requires_the_matching_asynchronous_acknowledgement(self):
        process = mock.Mock()
        process.poll.return_value = None
        send = mock.Mock()
        replies = queue.Queue()
        replies.put("현재 두뇌를 'other' 이름으로 저장했습니다.")
        replies.put("현재 두뇌를 'normal' 이름으로 저장했습니다.")
        self.assertTrue(confirm_model_save(process, replies, send, "normal"))
        send.assert_called_once_with("ai model save normal")
        self.assertFalse(confirm_model_save(process, queue.Queue(), send, "normal", timeout=0))
        replies.put("모델 명령을 확인하지 못했습니다.")
        self.assertFalse(confirm_model_save(process, replies, send, "normal"))
        replies.put("새 초기 두뇌를 \x1b[97m'fresh' 이름으로 등록했습니다. 현재 두뇌는 유지됩니다.")
        self.assertTrue(confirm_model_command(process, replies, send, "new", "fresh"))
        with self.assertRaises(ValueError):
            confirm_model_command(process, replies, send, "give", "fresh")

    def test_control_scenario_cannot_inject_world_commands_or_change_their_order(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "scenario.json"
            valid = [{"atSeconds": 5, "command": "learning", "enabled": False}, {"atSeconds": 5, "command": "stop"}]
            path.write_text(json.dumps({"schema": "minecraft-brain-control-scenario/v1", "events": valid}))
            self.assertEqual(read_scenario(path), valid)
            for invalid in ([{"atSeconds": 5, "command": "give"}], list(reversed([{**valid[0], "atSeconds": 4}, valid[1]]))):
                path.write_text(json.dumps({"schema": "minecraft-brain-control-scenario/v1", "events": invalid}))
                with self.assertRaises(ValueError):
                    read_scenario(path)
    def test_campaign_can_add_worlds_without_reassigning_or_removing_prior_partitions(self):
        parent = {"trainSeeds": ["1"], "evaluationSeeds": ["2"]}
        validate_plan_extension(parent, {"trainSeeds": ["1", "3"], "evaluationSeeds": ["2", "4"]})
        for changed in ({"trainSeeds": ["1", "2"], "evaluationSeeds": ["4"]},
                        {"trainSeeds": ["3"], "evaluationSeeds": ["2"]}):
            with self.assertRaisesRegex(ValueError, "remove or reassign"):
                validate_plan_extension(parent, changed)

    def test_partial_diagnostics_preserve_failures_without_inventing_task_completion(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "fixture.log"
            self.assertFalse(summarize_game_log(path)["available"])
            path.write_text("Model " + "0"*64 + " selected equip:0@600\n"
                            "Model action ended: MoveTo FAILED blocked\n"
                            "위치: -3, 63, 7 (world)\n체력: 18\n허기: 19\n"
                            "[AI:RLLearner] Died\nPPO request deferred\n", encoding="utf-8")
            observed = summarize_game_log(path)
            self.assertEqual(observed["modelChoiceCounts"], {"equip": 1})
            self.assertEqual(observed["inputOutcomeCounts"], {"MoveTo:FAILED": 1})
            self.assertEqual(observed["recordedDeaths"], 1)
            self.assertEqual(observed["sampledHealthCeilings"], [18])
            self.assertIsNone(observed["finalInventoryNbt"])
            self.assertFalse(observed["allWorldAutonomyProven"])

    def test_seed_partition_rejects_leakage_duplicates_and_ambiguous_numeric_seeds(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "plan.json"
            for train, evaluation in ((["1"], ["1"]), (["1", "1"], ["2"]),
                                      ([1], ["2"]), (["01"], ["2"]), (["-0"], ["2"]),
                                      ([str(2**63)], ["2"]), ([], ["2"])):
                path.write_text(json.dumps({"schema": "minecraft-paper-seed-plan/v1", "trainSeeds": train,
                                            "evaluationSeeds": evaluation}))
                with self.assertRaises(ValueError):
                    read_plan(path)
            expected = {"schema": "minecraft-paper-seed-plan/v1", "trainSeeds": [str(-(2**63)), "0"],
                        "evaluationSeeds": [str(2**63-1)]}
            path.write_text(json.dumps(expected))
            self.assertEqual(expected, read_plan(path))

    def test_evaluation_seed_cannot_start_a_training_process_and_actor_cannot_resume_optimizer(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            plan = directory / "plan.json"
            plan.write_text(json.dumps({"schema": "minecraft-paper-seed-plan/v1", "trainSeeds": ["1"], "evaluationSeeds": ["2"]}))
            artifact = directory / "runtime.fixture"
            artifact.write_text("fixture only")
            eula = directory / "eula.txt"
            eula.write_text("eula=true\n")
            args = argparse.Namespace(plan=plan, mode="train", world_seed="2", port=25601, seconds=120,
                    steps=32, model=None, resume=None, source_commit="0"*40, paper=artifact, plugin=artifact,
                    java=artifact, eula=eula)
            with self.assertRaisesRegex(ValueError, "not assigned"):
                validate_options(args)
            args.world_seed = "1"
            self.assertIsNotNone(validate_options(args))
            args.save_model_at_end, args.model_catalog, args.scenario = "normal", artifact, artifact
            with self.assertRaisesRegex(ValueError, "prepared scenarios are excluded"):
                validate_options(args)
            args.save_model_at_end, args.scenario = None, None
            args.start_time = 24000
            with self.assertRaisesRegex(ValueError, "initial world time"):
                validate_options(args)
            args.start_time = 18000
            self.assertIsNotNone(validate_options(args))
            args.model = artifact
            with self.assertRaisesRegex(ValueError, "actors cannot resume"):
                validate_options(args)
            args.mode, args.world_seed, args.resume = "evaluate", "2", artifact
            with self.assertRaisesRegex(ValueError, "cannot resume/update"):
                validate_options(args)


if __name__ == "__main__":
    unittest.main()
