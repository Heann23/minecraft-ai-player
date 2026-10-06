"""Export a frozen offline BC candidate and measure CPU inference parity."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import platform
import statistics
import time

from export_experience import MAX_BYTES, MAX_LINE, write_json
from goal_features import contract_hash
from prepare_features import read_json
from train_goal_bc import logits, predict


def validate_candidate(model: dict, manifest: dict, evaluation: dict, raw: bytes) -> None:
    if any(not isinstance(value, dict) for value in (model, manifest, evaluation)):
        raise ValueError("candidate, contract and evaluation must be objects")
    digest = contract_hash(manifest)
    goals, width = manifest.get("goals"), manifest.get("size")
    if (manifest.get("formatVersion") != 1 or manifest.get("observationSchema") != 1
            or manifest.get("role") != "goal_selection"
            or not isinstance(goals, list) or not goals or type(width) is not int or width <= 0
            or any(not isinstance(goal, str) or not goal for goal in goals) or len(set(goals)) != len(goals)
            or not isinstance(manifest.get("features"), list)
            or len(manifest.get("features", [])) != width
            or model.get("formatVersion") != 1 or model.get("role") != "goal_selection"
            or model.get("featureContractSHA256") != digest or evaluation.get("featureContractSHA256") != digest
            or model.get("goalSourceSHA256") != manifest.get("goalSourceSHA256")
            or evaluation.get("goalSourceSHA256") != manifest.get("goalSourceSHA256")
            or model.get("goals") != goals or not isinstance(model.get("recipe"), dict)
            or model["recipe"].get("model") != "linear softmax"
            or evaluation.get("candidateSHA256") != hashlib.sha256(raw).hexdigest()
            or evaluation.get("deployable") is not False):
        raise ValueError("candidate/evaluation/input contract mismatch")
    supported = model.get("supportedGoalIds")
    if (not isinstance(supported, list) or not supported
            or any(type(index) is not int or not 0 <= index < len(goals) for index in supported)
            or supported != sorted(set(supported))):
        raise ValueError("invalid supported Goal IDs")
    weights, biases = model.get("weights"), model.get("biases")
    if not isinstance(weights, list) or len(weights) != len(goals) or not isinstance(biases, list) or len(biases) != len(goals):
        raise ValueError("invalid model dimensions")
    for row in weights:
        if not isinstance(row, list) or len(row) != width:
            raise ValueError("invalid weight dimensions")
    if any(type(value) not in (int, float) or not math.isfinite(value) or abs(value) > 1e5
           for value in [*biases, *(value for row in weights for value in row)]):
        raise ValueError("invalid or excessively large model values")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("candidate", type=Path, help="train_goal_bc output folder")
    parser.add_argument("--features", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path, help="New local model bundle folder")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output directory already exists")
    try:
        import numpy as np
        import onnx
        import onnxruntime as ort
        from onnx import TensorProto, helper, numpy_helper
    except ImportError as exc:
        parser.error(f"optional developer dependencies missing; install requirements-onnx.txt: {exc}")
    try:
        raw = (args.candidate / "candidate.json").read_bytes()
        if len(raw) > MAX_BYTES:
            raise ValueError("candidate exceeds size limit")
        model = read_json(raw.decode("utf-8"))
        manifest = read_json((args.features / "feature-contract.json").read_text(encoding="utf-8"))
        evaluation = read_json((args.candidate / "evaluation.json").read_text(encoding="utf-8"))
        validate_candidate(model, manifest, evaluation, raw)
        samples, hashes = [], {}
        for split in ("train", "validation", "test"):
            path = args.features / f"{split}.jsonl"
            if path.stat().st_size > MAX_BYTES:
                raise ValueError("feature split exceeds size limit")
            content = path.read_bytes()
            hashes[split] = hashlib.sha256(content).hexdigest()
            if hashes[split] != evaluation.get("featureInputSHA256", {}).get(split):
                raise ValueError("parity data differs from frozen training/evaluation data")
            for line in content.decode("utf-8").splitlines():
                if len(line.encode()) > MAX_LINE:
                    raise ValueError("parity line exceeds limit")
                row = read_json(line)
                x = row.get("x")
                if (not isinstance(x, list) or len(x) != manifest["size"]
                        or any(type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value <= 1 for value in x)):
                    raise ValueError("invalid parity feature")
                samples.append(x)
        if not samples:
            raise ValueError("no parity examples")
        matrix = np.asarray(model["weights"], dtype=np.float32).T.copy()
        bias = np.asarray(model["biases"], dtype=np.float32)
        supported = model["supportedGoalIds"]
        for goal in range(len(model["goals"])):
            if goal not in supported:
                matrix[:, goal] = 0
                bias[goal] = -1e9
        graph = helper.make_graph(
            [helper.make_node("MatMul", ["observation", "weights"], ["linear"]),
             helper.make_node("Add", ["linear", "bias"], ["logits"]),
             helper.make_node("ArgMax", ["logits"], ["goal_id"], axis=1, keepdims=0, select_last_index=0)],
            "minecraft_goal_bc_candidate",
            [helper.make_tensor_value_info("observation", TensorProto.FLOAT, [None, manifest["size"]])],
            [helper.make_tensor_value_info("logits", TensorProto.FLOAT, [None, len(model["goals"])]),
             helper.make_tensor_value_info("goal_id", TensorProto.INT64, [None])],
            [numpy_helper.from_array(matrix, "weights"), numpy_helper.from_array(bias, "bias")])
        exported = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)], producer_name="MinecraftAI offline training")
        exported.ir_version = 8
        onnx.checker.check_model(exported)
        serialized = exported.SerializeToString()
        options = ort.SessionOptions()
        options.intra_op_num_threads = 1
        options.inter_op_num_threads = 1
        with_session = ort.InferenceSession(serialized, sess_options=options, providers=["CPUExecutionProvider"])
        inputs = np.asarray(samples, dtype=np.float32)
        actual_scores, actual_ids = with_session.run(["logits", "goal_id"], {"observation": inputs})
        expected_ids = [predict(model, x) for x in samples]
        mismatches = sum(int(actual) != expected for actual, expected in zip(actual_ids, expected_ids))
        maximum_error = max(abs(float(actual_scores[index, goal]) - logits(model, x)[goal])
                            for index, x in enumerate(samples) for goal in supported)
        durations = []
        for _ in range(50):
            start = time.perf_counter_ns()
            with_session.run(["logits", "goal_id"], {"observation": inputs[:1]})
            durations.append((time.perf_counter_ns() - start) / 1e6)
        parity = mismatches == 0 and math.isfinite(maximum_error) and maximum_error <= 1e-4
    except (OSError, ValueError, TypeError, KeyError, IndexError, onnx.checker.ValidationError) as exc:
        parser.error(str(exc))
    args.output.mkdir(parents=True)
    (args.output / "goal-candidate.onnx").write_bytes(serialized)
    write_json(args.output / "feature-contract.json", manifest)
    result = {"formatVersion": 1, "modelVersion": "goal-bc-candidate-1", "role": "goal_selection",
              "deployable": False, "executionAuthority": "none", "onnxSHA256": hashlib.sha256(serialized).hexdigest(),
              "candidateSHA256": evaluation["candidateSHA256"], "featureContractSHA256": contract_hash(manifest),
              "goalSourceSHA256": manifest["goalSourceSHA256"], "goals": manifest["goals"],
              "supportedGoalIds": supported, "opset": 13, "irVersion": 8,
              "input": {"name": "observation", "type": "float32", "shape": ["batch", manifest["size"]]},
              "outputs": {"logits": {"type": "float32", "shape": ["batch", len(model["goals"])]},
                          "goal_id": {"type": "int64", "shape": ["batch"]}},
              "exportToolSHA256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "trainingRecipe": model["recipe"],
              "unsupportedGoalMask": -1e9, "featureInputSHA256": hashes,
              "parity": {"passed": parity, "examples": len(samples), "goalIdMismatches": mismatches,
                         "maximumSupportedLogitError": maximum_error, "absoluteTolerance": 1e-4},
              "runtime": {"os": platform.system(), "machine": platform.machine(), "python": platform.python_version(),
                          "onnx": onnx.__version__, "onnxruntime": ort.__version__, "numpy": np.__version__,
                          "providers": with_session.get_providers(), "threads": 1,
                          "singleExampleMedianMs": statistics.median(durations),
                          "singleExampleP95Ms": sorted(durations)[math.ceil(0.95 * len(durations)) - 1]},
              "limitations": ["Parity is format equivalence, not improved policy quality",
                               "Python CPU inference only; Java/Paper integration and fallback remain unverified",
                               "Offline candidate not approved for deployment; no automatic promotion"]}
    write_json(args.output / "model-manifest.json", result)
    print(json.dumps({"parity": result["parity"], "onnxSHA256": result["onnxSHA256"], "deployable": False}))
    return 0 if parity else 2


if __name__ == "__main__":
    raise SystemExit(main())
