"""Train a small offline Goal behavior-cloning candidate. Never deploys it."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import random

from export_experience import MAX_BYTES, MAX_LINE, split_for, write_json
from goal_features import contract_hash
from prepare_features import read_json

RECIPE = {"epochs": 80, "learningRate": 0.05, "randomSeed": 777,
          "labelPolicy": "SUCCEEDED only", "classWeight": "inverse square root frequency",
          "model": "linear softmax", "selection": "fixed recipe; no validation/test tuning"}


def logits(model: dict, x: list[float]) -> list[float]:
    return [bias + sum(weight * value for weight, value in zip(weights, x))
            for weights, bias in zip(model["weights"], model["biases"])]


def predict(model: dict, x: list[float]) -> int:
    scores = logits(model, x)
    # Unobserved Goals are unsupported; Java integration would need Teacher fallback.
    return max(model["supportedGoalIds"], key=lambda goal: scores[goal])


def train(samples: list[dict], goal_count: int, feature_count: int) -> dict:
    if not samples:
        raise ValueError("no successful training examples")
    frequencies = Counter(row["labelId"] for row in samples)
    supported = sorted(frequencies)
    model = {"weights": [[0.0] * feature_count for _ in range(goal_count)],
             "biases": [0.0] * goal_count, "supportedGoalIds": supported}
    rng = random.Random(RECIPE["randomSeed"])
    order = list(samples)
    for _ in range(RECIPE["epochs"]):
        rng.shuffle(order)
        for row in order:
            scores = logits(model, row["x"])
            maximum = max(scores[goal] for goal in supported)
            exponentials = {goal: math.exp(scores[goal] - maximum) for goal in supported}
            total = sum(exponentials.values())
            rate = RECIPE["learningRate"] / math.sqrt(frequencies[row["labelId"]])
            for goal in supported:
                gradient = (exponentials[goal] / total - float(goal == row["labelId"])) * rate
                model["biases"][goal] -= gradient
                weights = model["weights"][goal]
                for index, value in enumerate(row["x"]):
                    weights[index] -= gradient * value
    return model


def agreement(samples: list[dict], predictions: list[int]) -> dict:
    total = Counter(row["labelId"] for row in samples)
    correct = Counter(row["labelId"] for row, prediction in zip(samples, predictions) if row["labelId"] == prediction)
    return {"examples": len(samples), "accuracy": sum(correct.values()) / len(samples) if samples else None,
            "macroAccuracy": sum(correct[goal] / count for goal, count in total.items()) / len(total) if total else None,
            "perGoal": {str(goal): {"examples": count, "correct": correct[goal]} for goal, count in total.items()}}


def evaluate(model: dict, samples: list[dict], manifest: dict, majority: int) -> dict:
    prior = [(index, field["name"].split("=", 1)[1]) for index, field in enumerate(manifest["features"])
             if field["name"].startswith("task.goal=")]
    previous = [next(manifest["goals"].index(goal) for index, goal in prior if row["x"][index] == 1) for row in samples]
    predictions = [predict(model, row["x"]) for row in samples]
    successful = [index for index, row in enumerate(samples) if row["result"]["outcome"] == "SUCCEEDED"]
    return {"allTeacherLabels": agreement(samples, predictions),
            "successfulTeacherLabels": agreement([samples[index] for index in successful], [predictions[index] for index in successful]),
            "majorityBaseline": agreement(samples, [majority] * len(samples)),
            "repeatPreviousGoalBaseline": agreement(samples, previous),
            "unsupportedLabels": sum(row["labelId"] not in model["supportedGoalIds"] for row in samples)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("features", type=Path)
    parser.add_argument("--output", required=True, type=Path, help="New local candidate folder")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output directory already exists")
    try:
        manifest = read_json((args.features / "feature-contract.json").read_text(encoding="utf-8"))
        report = read_json((args.features / "feature-report.json").read_text(encoding="utf-8"))
        if (manifest.get("formatVersion") != 1 or manifest.get("observationSchema") != 1
                or report.get("errors") != [] or report.get("featureContractSHA256") != contract_hash(manifest)):
            raise ValueError("feature contract/report mismatch")
        goals, width = manifest["goals"], manifest["size"]
        if not goals or type(width) is not int or width != len(manifest["features"]):
            raise ValueError("invalid model dimensions")
        data, hashes, identities = {}, {}, set()
        for split in ("train", "validation", "test"):
            path = args.features / f"{split}.jsonl"
            if path.stat().st_size > MAX_BYTES:
                raise ValueError("feature split exceeds input limit")
            raw = path.read_bytes()
            hashes[split] = hashlib.sha256(raw).hexdigest()
            samples = []
            for line in raw.decode("utf-8").splitlines():
                if len(line.encode()) > MAX_LINE:
                    raise ValueError("feature line exceeds input limit")
                row = read_json(line)
                if not isinstance(row, dict):
                    raise ValueError("feature row must be an object")
                x, label, seed = row.get("x"), row.get("labelId"), row.get("worldSeed")
                if (not isinstance(x, list) or len(x) != width or any(type(value) not in (int, float)
                        or not math.isfinite(value) or not 0 <= value <= 1 for value in x)
                        or type(label) is not int or not 0 <= label < len(goals)
                        or type(seed) is not int or split_for(seed) != split):
                    raise ValueError("invalid feature, label or seed split")
                identity = (row.get("sourceSHA256"), row.get("episodeKey"), row.get("decisionId"))
                if identity in identities or any(not isinstance(value, str) or len(value) != 64 for value in identity[:2]) or type(identity[2]) is not int:
                    raise ValueError("invalid or duplicate feature identity")
                identities.add(identity)
                outcome = row.get("result")
                if not isinstance(outcome, dict) or outcome.get("outcome") not in {"SUCCEEDED", "FAILED", "NO_PLAN", "INTERRUPTED"}:
                    raise ValueError("invalid outcome metadata")
                samples.append(row)
            if len(samples) != report.get("splitCounts", {}).get(split):
                raise ValueError("feature split count differs from report")
            data[split] = samples
        training = [row for row in data["train"] if row["result"]["outcome"] == "SUCCEEDED"]
        model = train(training, len(goals), width)
        frequencies = Counter(row["labelId"] for row in training)
        majority = max(sorted(frequencies), key=frequencies.get)
        model.update(formatVersion=1, role="goal_selection", featureContractSHA256=contract_hash(manifest),
                     goalSourceSHA256=manifest["goalSourceSHA256"], goals=goals, recipe=RECIPE)
        evaluations = {split: evaluate(model, samples, manifest, majority) for split, samples in data.items()}
    except (OSError, ValueError, TypeError, KeyError, StopIteration) as exc:
        parser.error(str(exc))
    args.output.mkdir(parents=True)
    write_json(args.output / "candidate.json", model)
    result = {"formatVersion": 1, "role": "goal_selection", "deployable": False,
              "candidateSHA256": hashlib.sha256((args.output / "candidate.json").read_bytes()).hexdigest(),
              "featureContractSHA256": contract_hash(manifest), "goalSourceSHA256": manifest["goalSourceSHA256"],
              "featureInputSHA256": hashes, "recipe": RECIPE, "trainingExamples": len(training),
              "excludedTrainingOutcomes": dict(Counter(row["result"]["outcome"] for row in data["train"] if row["result"]["outcome"] != "SUCCEEDED")),
              "trainingSeedCount": len({row["worldSeed"] for row in training}),
              "supportedGoals": [goals[goal] for goal in model["supportedGoalIds"]],
              "unsupportedGoals": [goal for index, goal in enumerate(goals) if index not in frequencies],
              "teacherAgreement": evaluations,
              "limitations": ["Offline Teacher imitation; agreement is not survival/task success",
                               "Small collected dataset; no broad world generalization claim",
                               "No ONNX export, Java inference, Shadow or execution authority",
                               "Unsupported Goals require Teacher fallback; candidate is never auto-promoted"]}
    write_json(args.output / "evaluation.json", result)
    print(json.dumps({"trainingExamples": len(training), "supportedGoals": result["supportedGoals"],
                      "accuracy": {split: value["allTeacherLabels"]["accuracy"] for split, value in evaluations.items()},
                      "deployable": False}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
