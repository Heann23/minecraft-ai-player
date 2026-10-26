"""Immutable PPO generations and a final atomic pointer. No training, model selection or world access."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import uuid

SCHEMA = "minecraft-paper-checkpoint-bundle/v1"
FILES = ("minecraft-rl-actor.json", "optimizer-checkpoint.json", "training-report.json")


def encoded(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")


def durable_replace(path: Path, data: bytes):
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    with temporary.open("xb") as stream:
        stream.write(data); stream.flush(); os.fsync(stream.fileno())
    os.replace(temporary, path)


def publish(root: Path, artifact: dict, checkpoint: dict, report: dict):
    generations = root / "checkpoints"
    generations.mkdir(exist_ok=True)
    temporary = generations / (".pending-" + uuid.uuid4().hex)
    temporary.mkdir()
    actor_bytes, checkpoint_bytes = encoded(artifact), encoded(checkpoint)
    report = dict(report, actorSha256=hashlib.sha256(actor_bytes).hexdigest(),
                  checkpointSha256=hashlib.sha256(checkpoint_bytes).hexdigest())
    bundle_report = dict(report, rolloutFile="../../paper-rollouts.jsonl")
    payloads = dict(zip(FILES, (actor_bytes, checkpoint_bytes, encoded(bundle_report))))
    for name, data in payloads.items():
        durable_replace(temporary / name, data)
    manifest = {"schema": SCHEMA, "run": checkpoint["run"], "modelVersion": report["modelVersion"],
                "contract": artifact["contract"], "files": {name: hashlib.sha256(data).hexdigest() for name, data in payloads.items()}}
    manifest_bytes = encoded(manifest)
    durable_replace(temporary / "manifest.json", manifest_bytes)
    name = "version-" + str(report["modelVersion"]).zfill(12) + "-" + uuid.uuid4().hex
    destination = generations / name
    os.replace(temporary, destination)
    # No member of an earlier generation is ever overwritten. The pointer is committed only after all members.
    durable_replace(root / "latest-checkpoint.json", encoded({"schema": SCHEMA, "bundle": "checkpoints/" + name,
                    "manifestSha256": hashlib.sha256(manifest_bytes).hexdigest()}))
    # Compatibility aliases are deliberately non-authoritative. Directory recovery reads the immutable bundle.
    for name in FILES:
        durable_replace(root / name, encoded(report) if name == "training-report.json" else payloads[name])
    return destination


def verified_checkpoint(directory: Path, expected_manifest: str | None = None):
    if directory.is_symlink() or directory.parent.name != "checkpoints" \
            or not re.fullmatch(r"version-[0-9]{12}-[0-9a-f]{32}", directory.name):
        raise ValueError("invalid checkpoint generation directory")
    manifest_path = directory / "manifest.json"
    if manifest_path.is_symlink() or manifest_path.stat().st_size > 65536:
        raise ValueError("invalid checkpoint manifest")
    data = manifest_path.read_bytes()
    if expected_manifest and hashlib.sha256(data).hexdigest() != expected_manifest:
        raise ValueError("checkpoint pointer/manifest hash mismatch")
    manifest = json.loads(data)
    if manifest.get("schema") != SCHEMA or set(manifest.get("files", {})) != set(FILES):
        raise ValueError("checkpoint bundle schema mismatch")
    for name in FILES:
        path = directory / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size > 16*1024*1024 \
                or hashlib.sha256(path.read_bytes()).hexdigest() != manifest["files"][name]:
            raise ValueError("checkpoint bundle member hash mismatch")
    return directory / "optimizer-checkpoint.json"


def candidates(root: Path):
    """Newest complete candidates, excluding pending/unsafe paths. Raw-prefix validation remains the caller's job."""
    found = set()
    pointer = root / "latest-checkpoint.json"
    if pointer.is_file():
        try:
            if pointer.is_symlink() or pointer.stat().st_size > 65536:
                raise ValueError("invalid checkpoint pointer")
            value = json.loads(pointer.read_bytes())
            if value.get("schema") != SCHEMA or not re.fullmatch(r"checkpoints/version-[0-9]{12}-[0-9a-f]{32}", value.get("bundle", "")):
                raise ValueError("invalid checkpoint pointer target")
            target = root / value["bundle"]
            if target.resolve().parent.parent != root.resolve():
                raise ValueError("checkpoint target escapes run directory")
            checkpoint = verified_checkpoint(target, value["manifestSha256"])
            found.add(checkpoint)
            yield checkpoint
        except (OSError, ValueError, KeyError, TypeError):
            pass  # Recovery is explicit at the directory API; an invalid pointer is never trusted.
    generations = root / "checkpoints"
    if generations.is_dir() and not generations.is_symlink():
        paths = sorted(generations.glob("version-*"), reverse=True)
        if len(paths) > 10000:
            raise ValueError("checkpoint generation scan exceeds recovery budget")
        for path in paths:
            try:
                candidate = verified_checkpoint(path)
                if candidate not in found:
                    found.add(candidate)
                    yield candidate
            except (OSError, ValueError, KeyError, TypeError):
                continue
    if not found and not pointer.exists() and not generations.exists() and (root / "optimizer-checkpoint.json").is_file():
        yield root / "optimizer-checkpoint.json"  # Legacy run, still subject to full existing verification.
