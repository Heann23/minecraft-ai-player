"""Immutable, raw-backed on-policy buffers. A count alone is never resumable experience."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

from checkpoint_store import durable_replace, encoded

SCHEMA = "minecraft-paper-pending-rollout/v1"
MAX_BYTES = 512 * 1024 * 1024
MAX_ROWS = 4096


def counters(state, trained):
    actual_trained = state.get("trainedEnvironmentSteps", trained)
    collected = state.get("collectedEnvironmentSteps", state.get("environmentSteps", trained))
    pending = state["pendingRolloutTransitions"]
    if type(actual_trained) is not int or type(collected) is not int or actual_trained != trained \
            or collected != trained + pending or pending > MAX_ROWS:
        raise ValueError("session collection/training/pending counters do not match the exact checkpoint")
    return collected


def raw_rows(reference):
    if not isinstance(reference, dict) or set(reference) != {"path", "offset", "bytes", "sha256"}:
        raise ValueError("invalid pending raw reference")
    path = Path(reference["path"])
    offset, size = reference["offset"], reference["bytes"]
    if not path.is_absolute() or path.name != "paper-rollouts.jsonl" or path.is_symlink() \
            or path.parent.is_symlink() or not path.is_file() or type(offset) is not int or offset < 0 \
            or type(size) is not int or not 0 < size <= MAX_BYTES or path.stat().st_size < offset + size:
        raise ValueError("pending raw evidence missing or unsafe")
    with path.open("rb") as stream:
        stream.seek(offset)
        data = stream.read(size)
    if len(data) != size or not data.endswith(b"\n") or hashlib.sha256(data).hexdigest() != reference["sha256"]:
        raise ValueError("pending raw evidence integrity mismatch")
    rows = [json.loads(line) for line in data.splitlines()]
    if not 0 < len(rows) <= MAX_ROWS:
        raise ValueError("pending raw evidence row budget exceeded")
    return rows


def publish(directory, state, rows, raw_path, raw_offset, raw_bytes):
    if not rows:
        return None
    if len(rows) > MAX_ROWS or type(raw_offset) is not int or raw_offset < 0 \
            or type(raw_bytes) is not int or not 0 < raw_bytes <= MAX_BYTES:
        raise ValueError("pending raw window exceeds its budget")
    raw_path = Path(raw_path).absolute()
    with raw_path.open("rb") as stream:
        stream.seek(raw_offset)
        data = stream.read(raw_bytes)
    raw = {"path": str(raw_path), "offset": raw_offset, "bytes": raw_bytes,
           "sha256": hashlib.sha256(data).hexdigest()}
    if raw_rows(raw) != rows:
        raise ValueError("pending rows differ from their actual raw window")
    payload = {"schema": SCHEMA, "brainId": state["brainId"], "contract": state["contract"],
               "checkpointSha256": state["checkpointSha256"], "policyVersion": state["policyVersion"],
               "trainedEnvironmentSteps": state["trainedEnvironmentSteps"],
               "collectedEnvironmentSteps": state["collectedEnvironmentSteps"], "raw": raw, "rows": rows}
    data = encoded(payload)
    if len(data) > MAX_BYTES:
        raise ValueError("pending snapshot byte budget exceeded")
    digest = hashlib.sha256(data).hexdigest()
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / ("pending-" + digest + ".json")
    if path.exists() or path.is_symlink():
        if path.is_symlink() or not path.is_file() or path.stat().st_size != len(data) \
                or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError("immutable pending snapshot changed; original preserved")
    else:
        durable_replace(path, data)
    return {"schema": SCHEMA, "path": str(path.absolute()), "sha256": digest, "count": len(rows), "bytes": len(data)}


def load(state, trained, validate):
    counters(state, trained)
    count = state["pendingRolloutTransitions"]
    reference = state.get("pendingRollout")
    if count == 0:
        if reference is not None:
            raise ValueError("empty pending buffer has an unexpected reference")
        return []
    if reference is None:
        raise ValueError("pending rollout evidence missing; explicit legacy migration required")
    if not isinstance(reference, dict) or set(reference) != {"schema", "path", "sha256", "count", "bytes"} \
            or reference["schema"] != SCHEMA or reference["count"] != count:
        raise ValueError("invalid pending snapshot reference")
    path = Path(reference["path"])
    if not path.is_absolute() or path.is_symlink() or path.parent.is_symlink() or not path.is_file() \
            or type(reference["bytes"]) is not int or not 0 < reference["bytes"] <= MAX_BYTES \
            or path.stat().st_size != reference["bytes"]:
        raise ValueError("pending snapshot missing or unsafe")
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != reference["sha256"]:
        raise ValueError("pending snapshot integrity mismatch")
    payload = json.loads(data)
    if not isinstance(payload, dict) or set(payload) != {"schema", "brainId", "contract", "checkpointSha256",
            "policyVersion", "trainedEnvironmentSteps", "collectedEnvironmentSteps", "raw", "rows"} \
            or payload["schema"] != SCHEMA \
            or any(payload[key] != state[key] for key in ("brainId", "contract", "checkpointSha256", "policyVersion")) \
            or payload["trainedEnvironmentSteps"] != trained \
            or payload["collectedEnvironmentSteps"] != counters(state, trained):
        raise ValueError("pending snapshot belongs to another brain, policy or checkpoint")
    rows = payload["rows"]
    if not isinstance(rows, list) or len(rows) != count or raw_rows(payload["raw"]) != rows:
        raise ValueError("pending snapshot differs from its raw evidence")
    return validate_rows(state, rows, validate)


def validate_rows(state, rows, validate):
    if not isinstance(rows, list) or len(rows) != state["pendingRolloutTransitions"] or not 0 < len(rows) <= MAX_ROWS:
        raise ValueError("pending row count mismatch")
    seen = set()
    for row in rows:
        validate(row)
        identity = (row["session"], row["sequence"])
        if row["modelVersion"] != state["policyVersion"] or identity in seen:
            raise ValueError("pending rows are duplicated or from another behavior policy")
        seen.add(identity)
    return rows
