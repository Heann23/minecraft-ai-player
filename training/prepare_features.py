"""Convert audited, seed-separated Goal examples to frozen numeric features."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

from export_experience import MAX_LINE, finite_number, split_for, write_json
from goal_features import contract, contract_hash, encode


def read_json(text: str):
    return json.loads(text, parse_float=finite_number,
                      parse_constant=lambda token: (_ for _ in ()).throw(ValueError(token)))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path, help="export_experience output directory")
    parser.add_argument("--output", required=True, type=Path, help="New output directory")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output directory already exists")
    try:
        audit = read_json((args.dataset / "audit.json").read_text(encoding="utf-8"))
        ids = read_json((args.dataset / "goal-ids.json").read_text(encoding="utf-8"))
        manifest = contract(ids)
        if (audit.get("formatVersion") != 1 or audit.get("observationSchema") != 1
                or audit.get("experienceSchema") != 1 or audit.get("rejectedEpisodes") != 0
                or audit.get("goalSourceSHA256") != manifest["goalSourceSHA256"]):
            raise ValueError("dataset audit/schema/Goal IDs mismatch or rejected episodes")
        rows = {split: [] for split in ("train", "validation", "test")}
        errors, hashes, seeds, identities = [], {}, {}, set()
        for split in rows:
            path = args.dataset / f"{split}.jsonl"
            digest = hashlib.sha256()
            for line_no, raw in enumerate(path.open("rb"), 1):
                digest.update(raw)
                try:
                    if len(raw) > MAX_LINE:
                        raise ValueError("line exceeds input limit")
                    row = read_json(raw.decode("utf-8"))
                    if not isinstance(row, dict):
                        raise ValueError("row object required")
                    seed = row.get("worldSeed")
                    if type(seed) is not int or row.get("split") != split or split_for(seed) != split:
                        raise ValueError("seed split mismatch")
                    identity = (row.get("sourceSHA256"), row.get("episodeKey"), row.get("decisionId"))
                    if (any(not isinstance(value, str) or len(value) != 64 for value in identity[:2])
                            or type(identity[2]) is not int or identity[2] < 0 or identity in identities):
                        raise ValueError("invalid or duplicate sample identity")
                    identities.add(identity)
                    label = row.get("label")
                    if label not in manifest["goals"]:
                        raise ValueError("unknown Goal label")
                    result = row.get("result")
                    if not isinstance(result, dict) or result.get("outcome") not in {"SUCCEEDED", "FAILED", "INTERRUPTED", "NO_PLAN"}:
                        raise ValueError("result metadata missing")
                    features = encode(row.get("observation"), manifest)
                    seeds.setdefault(seed, split)
                    rows[split].append({"x": features, "labelId": manifest["goals"].index(label),
                                        "sourceSHA256": identity[0], "episodeKey": identity[1],
                                        "decisionId": identity[2], "worldSeed": seed, "result": result})
                except (ValueError, UnicodeError, TypeError) as exc:
                    errors.append({"split": split, "line": line_no, "error": str(exc)})
            hashes[split] = digest.hexdigest()
        count = sum(map(len, rows.values()))
        if count != audit.get("examples"):
            errors.append({"error": "accepted example count differs from audit"})
    except (OSError, ValueError, TypeError, KeyError) as exc:
        parser.error(str(exc))
    args.output.mkdir(parents=True)
    # Fail closed: an invalid dataset has a diagnostic report, never partial training rows.
    usable = count > 0 and not errors
    for split, samples in rows.items():
        with (args.output / f"{split}.jsonl").open("w", encoding="utf-8") as stream:
            if usable:
                for sample in samples:
                    stream.write(json.dumps(sample, allow_nan=False) + "\n")
    write_json(args.output / "feature-contract.json", manifest)
    report = {"formatVersion": 1, "featureContractSHA256": contract_hash(manifest),
              "goalSourceSHA256": manifest["goalSourceSHA256"], "inputSHA256": hashes,
              "featureCount": manifest["size"], "acceptedExamples": count if usable else 0,
              "splitCounts": {split: len(samples) if usable else 0 for split, samples in rows.items()},
              "seedCounts": dict(Counter(seeds.values())), "errors": errors,
              "goalCounts": {split: dict(Counter(manifest["goals"][sample["labelId"]] for sample in samples))
                             for split, samples in rows.items()},
              "readyForTraining": False,
              "limitations": ["No trained weights or runtime inference", "Coverage and baseline evaluation still required",
                               "Failed/interrupted labels retained as metadata; trainer must choose its label policy"]}
    write_json(args.output / "feature-report.json", report)
    print(json.dumps({key: report[key] for key in ("featureCount", "acceptedExamples", "splitCounts", "errors")}))
    return 0 if usable else 2


if __name__ == "__main__":
    raise SystemExit(main())
