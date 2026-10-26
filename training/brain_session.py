"""Value-only brain runtime state. World position, inventory and AI entity lifecycle are intentionally separate."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import uuid

from checkpoint_store import durable_replace, encoded

SCHEMA = "minecraft-brain-runtime-session/v1"


def validate(state):
    if state.get("schema") != SCHEMA:
        raise ValueError("incompatible brain session")
    uuid.UUID(state["brainId"])
    if not isinstance(state["checkpoint"], str) or len(state["checkpoint"]) > 4096 or not Path(state["checkpoint"]).is_absolute():
        raise ValueError("brain session needs an exact checkpoint reference")
    if not re.fullmatch(r"[0-9a-f]{64}", state["checkpointSha256"]):
        raise ValueError("invalid brain checkpoint identity")
    for key in ("policyVersion", "gradientUpdates", "pendingRolloutTransitions"):
        if type(state[key]) is not int or state[key] < 0:
            raise ValueError("invalid brain session counter")
    for key in ("learningEnabled", "initializationOnly"):
        if type(state[key]) is not bool:
            raise ValueError("invalid brain session flag")
    if not isinstance(state["rng"], dict):
        raise ValueError("invalid brain random state")
    return state


def save(path: Path, state: dict):
    validate(state)
    payload = encoded(state)
    durable_replace(path, encoded({"payload": state, "sha256": hashlib.sha256(payload).hexdigest()}))


def load(path: Path):
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 65536:
        raise ValueError("brain session missing or unsafe; no automatic initialization")
    envelope = json.loads(path.read_bytes())
    if set(envelope) != {"payload", "sha256"} or hashlib.sha256(encoded(envelope["payload"])).hexdigest() != envelope["sha256"]:
        raise ValueError("brain session integrity mismatch; original preserved")
    return validate(envelope["payload"])
