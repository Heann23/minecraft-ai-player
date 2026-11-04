"""Value-only brain runtime state. World position, inventory and AI entity lifecycle are intentionally separate."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import sys
import uuid

from checkpoint_store import durable_replace, encoded

SCHEMA = "minecraft-brain-runtime-session/v1"


def validate(state):
    if not isinstance(state, dict) or state.get("schema") != SCHEMA:
        raise ValueError("incompatible brain session")
    uuid.UUID(state["brainId"])
    if not isinstance(state["checkpoint"], str) or len(state["checkpoint"]) > 4096 or not Path(state["checkpoint"]).is_absolute():
        raise ValueError("brain session needs an exact checkpoint reference")
    if not re.fullmatch(r"[0-9a-f]{64}", state["checkpointSha256"]):
        raise ValueError("invalid brain checkpoint identity")
    for key in ("policyVersion", "gradientUpdates", "pendingRolloutTransitions"):
        if type(state[key]) is not int or state[key] < 0:
            raise ValueError("invalid brain session counter")
    if "environmentSteps" in state and (type(state["environmentSteps"]) is not int or state["environmentSteps"] < 0):
        raise ValueError("invalid brain session environment count")
    for key in ("collectedEnvironmentSteps", "trainedEnvironmentSteps"):
        if key in state and (type(state[key]) is not int or state[key] < 0):
            raise ValueError("invalid explicit brain transition counter")
    if "collectedEnvironmentSteps" in state and state.get("environmentSteps") != state["collectedEnvironmentSteps"]:
        raise ValueError("session collection counters disagree")
    if "trainedEnvironmentSteps" in state and "collectedEnvironmentSteps" in state \
            and state["trainedEnvironmentSteps"] + state["pendingRolloutTransitions"] != state["collectedEnvironmentSteps"]:
        raise ValueError("session trained/pending/collection counters disagree")
    for key in ("learningEnabled", "initializationOnly"):
        if type(state[key]) is not bool:
            raise ValueError("invalid brain session flag")
    if not isinstance(state["rng"], dict):
        raise ValueError("invalid brain random state")
    return state


def save(path: Path, state: dict, *, recoverable=False):
    validate(state)
    payload = encoded(state)
    data = encoded({"payload": state, "sha256": hashlib.sha256(payload).hexdigest()})
    if recoverable:
        # A staged copy is NOT a committed session. Recovery requires the later matching witness.
        durable_replace(path.with_name(path.name + ".recovery-state.json"), data)
    durable_replace(path, data)
    if recoverable:
        witness = {"schema": "minecraft-brain-session-recovery/v1", "session": path.name,
                   "sessionSha256": hashlib.sha256(data).hexdigest()}
        try:
            durable_replace(path.with_name(path.name + ".recovery-commit.json"),
                            encoded({"payload": witness, "sha256": hashlib.sha256(encoded(witness)).hexdigest()}))
        except OSError as failure:
            # The primary is already committed. Do not report its mode/save as rolled back.
            print("Session saved but recovery witness unavailable:", type(failure).__name__, file=sys.stderr, flush=True)


def load(path: Path):
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 65536:
        raise ValueError("brain session missing or unsafe; no automatic initialization")
    envelope = json.loads(path.read_bytes())
    if not isinstance(envelope, dict) or set(envelope) != {"payload", "sha256"} or hashlib.sha256(encoded(envelope["payload"])).hexdigest() != envelope["sha256"]:
        raise ValueError("brain session integrity mismatch; original preserved")
    return validate(envelope["payload"])


def restore(path: Path, *, verify):
    """Strict primary first, then its exact committed redundant copy. Never scans other brains/runs."""
    if path.is_symlink() or path.parent.is_symlink():
        raise ValueError("unsafe session path; recovery refused")
    try:
        state = load(path)
        verify(state)
        return state, None
    except (OSError, ValueError, KeyError, TypeError) as failure:
        primary_error = type(failure).__name__
    witness_path = path.with_name(path.name + ".recovery-commit.json")
    backup = path.with_name(path.name + ".recovery-state.json")
    if witness_path.is_symlink() or not witness_path.is_file() or witness_path.stat().st_size > 4096:
        raise ValueError("session invalid and no committed recovery witness; no initialization")
    record = json.loads(witness_path.read_bytes())
    if not isinstance(record, dict) or set(record) != {"payload", "sha256"} \
            or hashlib.sha256(encoded(record["payload"])).hexdigest() != record["sha256"]:
        raise ValueError("recovery witness integrity mismatch")
    witness = record["payload"]
    if not isinstance(witness, dict) or witness.get("schema") != "minecraft-brain-session-recovery/v1" or witness.get("session") != path.name \
            or backup.is_symlink() or not backup.is_file() or backup.stat().st_size > 65536:
        raise ValueError("unsafe recovery state")
    digest = hashlib.sha256(backup.read_bytes()).hexdigest()
    if digest != witness.get("sessionSha256"):
        raise ValueError("recovery copy was not committed; no older/different model selected")
    state = load(backup)
    verify(state)  # Full optimizer, identity, raw-prefix and RNG validation supplied by the runtime.
    return state, {"source": str(path.absolute()), "recoveredFrom": str(backup.absolute()),
                   "sessionSha256": digest, "primaryFailure": primary_error,
                   "brainId": state["brainId"], "checkpointSha256": state["checkpointSha256"]}
