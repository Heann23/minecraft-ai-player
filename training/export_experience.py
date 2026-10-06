"""Audit Experience v1 episodes and export autonomous Goal-selection examples.

Developer tool only. Does not train a model or change the running plugin.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import re

OUTCOMES = {"SUCCEEDED", "FAILED", "INTERRUPTED", "NO_PLAN"}
END_REASONS = {"COMPLETED", "DEATH", "STOPPED", "REMOVED", "SHUTDOWN", "ERROR"}
ORIGINS = {"AUTONOMOUS", "REQUESTED", "FORCED"}
SECTIONS = ("player", "inventory", "environment", "progress", "memory", "task")
MAX_BYTES = 64 * 1024 * 1024
MAX_LINE = 2 * 1024 * 1024


def load_goals(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8-sig")
    text = re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)
    match = re.search(r"enum\s+GoalType\s*\{(.*?)\}", text, re.S)
    if not match:
        raise ValueError("GoalType enum declaration missing")
    names = [part.strip() for part in match.group(1).split(",")]
    if not names or any(not re.fullmatch(r"[A-Z][A-Z_]*", name) for name in names):
        raise ValueError("Unsupported GoalType declaration")
    if len(names) != len(set(names)):
        raise ValueError("Duplicate GoalType IDs")
    return names


def split_for(seed: int) -> str:
    # Same seed stays together, including respawns, reruns and different AI names.
    bucket = int(hashlib.sha256(f"seed:{seed}".encode()).hexdigest()[:8], 16) % 100
    return "train" if bucket < 70 else "validation" if bucket < 85 else "test"


def integer(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def finite_number(token: str) -> float:
    value = float(token)
    if not math.isfinite(value):
        raise ValueError("non-finite JSON number")
    return value


def audit(path: Path, goals: set[str]) -> tuple[dict, list[dict]]:
    if path.stat().st_size > MAX_BYTES:
        return {"sourceSHA256": None, "bytes": path.stat().st_size,
                "errors": ["episode exceeds 64 MiB input limit"], "counts": {}, "excluded": {}}, []
    with path.open("rb") as stream:
        raw = stream.read(MAX_BYTES + 1)
    digest = hashlib.sha256(raw).hexdigest()
    report = {"sourceSHA256": digest, "bytes": len(raw), "errors": [], "counts": {}, "excluded": {}}
    errors = report["errors"]
    records = []
    if len(raw) > MAX_BYTES:
        errors.append("episode exceeds 64 MiB input limit")
        return report, []
    try:
        for line_no, line in enumerate(raw.decode("utf-8-sig").splitlines(), 1):
            if not line.strip():
                continue
            if len(line.encode("utf-8")) > MAX_LINE:
                raise ValueError(f"line {line_no}: exceeds 2 MiB")
            value = json.loads(line, parse_float=finite_number,
                               parse_constant=lambda token: (_ for _ in ()).throw(ValueError(token)))
            if not isinstance(value, dict):
                raise ValueError(f"line {line_no}: expected object")
            records.append(value)
    except (ValueError, UnicodeError) as exc:
        errors.append(f"invalid JSONL: {exc}")
        return report, []
    report["counts"] = dict(Counter(row.get("type", "missing") for row in records))
    if not records or records[0].get("type") != "episode_start" or records[-1].get("type") != "episode_end":
        errors.append("episode boundaries missing or out of order")
        return report, []
    start, end = records[0], records[-1]
    episode = start.get("episodeId")
    if not isinstance(episode, str) or not episode:
        errors.append("episodeId missing")
    if start.get("schemaVersion") != 1 or start.get("observationVersion") != 1:
        errors.append("unsupported Experience or Observation schema")
    if start.get("reason") not in {"START", "RESPAWN"}:
        errors.append("unknown episode start reason")
    if not isinstance(start.get("pluginVersion"), str) or not start["pluginVersion"]:
        errors.append("plugin version missing")
    seed = start.get("worldSeed")
    if not isinstance(seed, int) or isinstance(seed, bool):
        errors.append("world seed missing")
    if end.get("reason") not in END_REASONS:
        errors.append("unknown episode end reason")
    report.update(pluginVersion=start.get("pluginVersion"), worldSeed=seed, endReason=end.get("reason"))
    decisions, outcomes = {}, {}
    action_indices = set()
    for row in records:
        kind = row.get("type")
        if row.get("episodeId") != episode:
            errors.append("record belongs to another episode")
        if kind == "episode_start" and row is not start or kind == "episode_end" and row is not end:
            errors.append("multiple episode boundaries")
        if kind == "decision":
            number = row.get("decisionId")
            if not integer(number) or number != len(decisions):
                errors.append("decision IDs must start at zero and be contiguous")
                continue
            decisions[number] = row
            observation = row.get("observation")
            if not isinstance(observation, dict) or observation.get("schemaVersion") != 1:
                errors.append(f"decision {number}: missing or unsupported observation")
                continue
            if any(not isinstance(observation.get(section), dict) for section in SECTIONS):
                errors.append(f"decision {number}: observation sections missing")
            tick = row.get("tick")
            if not integer(tick) or not integer(observation.get("tick")) or observation["tick"] > tick:
                errors.append(f"decision {number}: invalid observation timing")
            teacher, executed = row.get("teacher"), row.get("executed")
            if not isinstance(teacher, dict) or teacher.get("goal") not in goals or teacher.get("origin") not in ORIGINS:
                errors.append(f"decision {number}: invalid Teacher label")
            if not isinstance(executed, dict) or not isinstance(executed.get("actions"), list):
                errors.append(f"decision {number}: executed plan missing")
            elif any(not isinstance(action, dict) or not isinstance(action.get("name"), str)
                     for action in executed["actions"]):
                errors.append(f"decision {number}: malformed planned action")
        elif kind in {"action", "outcome"}:
            number = row.get("decisionId")
            if not integer(number) or number not in decisions:
                errors.append(f"{kind}: orphan decision reference")
                continue
            decision = decisions[number]
            if kind == "outcome":
                if number in outcomes or row.get("outcome") not in OUTCOMES:
                    errors.append(f"decision {number}: duplicate or invalid outcome")
                outcomes[number] = row
                if not integer(row.get("tick")) or row["tick"] < decision.get("tick", 0):
                    errors.append(f"decision {number}: outcome precedes decision")
            else:
                index = row.get("index")
                executed = decision.get("executed")
                planned = executed.get("actions", []) if isinstance(executed, dict) else []
                if not isinstance(planned, list):
                    planned = []
                if not integer(index) or index >= len(planned) or (number, index) in action_indices:
                    errors.append(f"decision {number}: invalid or duplicate action index")
                else:
                    action_indices.add((number, index))
                    if not isinstance(planned[index], dict) or row.get("name") != planned[index].get("name"):
                        errors.append(f"decision {number}: action name mismatch")
                if row.get("status") not in {"SUCCESS", "FAILED"}:
                    errors.append(f"decision {number}: invalid action result")
                if not integer(row.get("startTick")) or not integer(row.get("endTick")) or row["endTick"] < row["startTick"]:
                    errors.append(f"decision {number}: invalid action timing")
        elif kind not in {"episode_start", "episode_end", "event"}:
            errors.append("unknown record type")
    if end.get("decisions") != len(decisions):
        errors.append("episode decision count mismatch")
    if set(decisions) != set(outcomes):
        errors.append("decision outcomes missing")
    report["errors"] = sorted(set(errors))
    if errors:
        return report, []
    report["skillPlanCounts"] = dict(Counter(row["executed"].get("skill", "") for row in decisions.values()))
    report["primitivePlanCounts"] = dict(Counter(action.get("primitive") or "COMPOSITE"
                                                for row in decisions.values() for action in row["executed"]["actions"]))
    samples, excluded = [], Counter()
    for number, row in decisions.items():
        teacher, executed = row["teacher"], row["executed"]
        if teacher["origin"] != "AUTONOMOUS":
            excluded[teacher["origin"]] += 1
            continue
        if executed.get("escaping"):
            excluded["ESCAPE_RECOVERY_PLAN"] += 1
            continue
        if teacher["goal"] != executed.get("goal"):
            excluded["TEACHER_EXECUTION_MISMATCH"] += 1
            continue
        outcome = outcomes[number]
        # Observation is the only input. Teacher scores, executed actions, shadow,
        # outcomes and future observations never enter the model feature input.
        samples.append({"sourceSHA256": digest, "episodeKey": hashlib.sha256(episode.encode()).hexdigest(),
                        "decisionId": number, "worldSeed": seed, "split": split_for(seed),
                        "observation": row["observation"], "label": teacher["goal"],
                        "result": {"outcome": outcome["outcome"], "goalAchieved": outcome.get("goalAchieved"),
                                   "episodeEnd": end["reason"]}})
    report["excluded"] = dict(excluded)
    report["examples"] = len(samples)
    return report, samples


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", type=Path, help="Explicit JSONL files or episode directories")
    parser.add_argument("--goals", required=True, type=Path, help="Matching plugin GoalType.java")
    parser.add_argument("--output", required=True, type=Path, help="New output directory; never overwrites")
    args = parser.parse_args()
    goals = load_goals(args.goals)
    goal_source_hash = hashlib.sha256(args.goals.read_bytes()).hexdigest()
    paths = set()
    for source in args.inputs:
        if not source.exists():
            parser.error(f"input does not exist: {source}")
        paths.update(source.rglob("*.jsonl") if source.is_dir() else [source])
    if not paths:
        parser.error("no JSONL episodes found")
    if args.output.exists():
        parser.error("output directory already exists")
    reports, samples, hashes = [], [], set()
    for path in sorted(paths):
        report, rows = audit(path, set(goals))
        if report["sourceSHA256"] is not None and report["sourceSHA256"] in hashes:
            continue
        hashes.add(report["sourceSHA256"])
        reports.append(report)
        samples.extend(rows)
    args.output.mkdir(parents=True)
    for split in ("train", "validation", "test"):
        with (args.output / f"{split}.jsonl").open("w", encoding="utf-8") as stream:
            for sample in samples:
                if sample["split"] == split:
                    stream.write(json.dumps(sample, ensure_ascii=False, allow_nan=False) + "\n")
    failures = sum(bool(report["errors"]) for report in reports)
    summary = {"formatVersion": 1, "experienceSchema": 1, "observationSchema": 1,
               "goalSourceSHA256": goal_source_hash,
               "inputEpisodes": len(reports), "rejectedEpisodes": failures, "examples": len(samples),
               "splitCounts": dict(Counter(row["split"] for row in samples)),
               "goalCounts": dict(Counter(row["label"] for row in samples)),
               "outcomeCounts": dict(Counter(row["result"]["outcome"] for row in samples)),
               "seedCount": len({row["worldSeed"] for row in samples}), "episodes": reports,
               "skillPlanCounts": dict(sum((Counter(report.get("skillPlanCounts", {})) for report in reports), Counter())),
               "primitivePlanCounts": dict(sum((Counter(report.get("primitivePlanCounts", {})) for report in reports), Counter())),
               "readyForTraining": False,
               "limitations": ["No normalized feature contract or trained model yet",
                               "Coverage and held-out seed evaluation required before training",
                               "Failed Teacher choices remain labeled and must be reviewed"]}
    write_json(args.output / "audit.json", summary)
    write_json(args.output / "goal-ids.json", {"formatVersion": 1, "sourceSHA256": goal_source_hash, "goals": goals})
    print(json.dumps({key: summary[key] for key in ("inputEpisodes", "rejectedEpisodes", "examples", "splitCounts", "seedCount")}, ensure_ascii=False))
    return 2 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
