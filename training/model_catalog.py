"""Named references to verified brain snapshots. Never starts Minecraft or trains a policy."""
from __future__ import annotations

import argparse
from contextlib import contextmanager, redirect_stdout
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import uuid

import brain_session
from checkpoint_store import durable_replace, encoded

SCHEMA = "minecraft-brain-catalog/v1"


def model_name(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}", value) \
            or value == "selected-model" or value.startswith("revision-") \
            or value.upper() in {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(10)), *(f"LPT{i}" for i in range(10))}:
        raise ValueError("model name must be 1..64 lowercase letters/digits/_/- and not a device name")
    return value


def read_record(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 131072:
        raise ValueError("missing or unsafe catalog record; no automatic replacement")
    envelope = json.loads(path.read_bytes())
    if set(envelope) != {"payload", "sha256"} \
            or hashlib.sha256(encoded(envelope["payload"])).hexdigest() != envelope["sha256"]:
        raise ValueError("catalog record integrity mismatch; original preserved")
    return envelope["payload"]


def write_record(path, payload):
    durable_replace(path, encoded({"payload": payload, "sha256": hashlib.sha256(encoded(payload)).hexdigest()}))


def restricted_training_use(checkpoint):
    checkpoint = Path(checkpoint)
    run = checkpoint.parent.parent.parent if checkpoint.parent.parent.name == "checkpoints" else checkpoint.parent
    # A plugin-owned run is nested below server/plugins/<plugin>/brain-runtime, unlike the external lab backend.
    for ancestor in [run, *list(run.parents)[:7]]:
        for name in ("catalog-use.json", "manifest.json", "result.json"):
            path = ancestor / name
            if path.is_file():
                if path.is_symlink() or path.stat().st_size > 131072:
                    raise ValueError("unsafe model-use provenance")
                value = json.loads(path.read_bytes())
                if value.get("notForAutonomousTraining") or value.get("preparedScenario"):
                    return True
    return False


def verify_state(state):
    # Reuse the same actor/critic loader, raw-prefix provenance and exact restoration checks as the runtime.
    from train_paper_ppo import verify_runtime_session
    verify_runtime_session(state)
    # Prepared control cases have genuine transitions but are not autonomous training sources.
    if state.get("notForAutonomousTraining") or restricted_training_use(state["checkpoint"]):
        raise ValueError("prepared scenario models cannot enter the autonomous model catalog")
    return state


def optimizer_identity(state):
    optimizer = json.loads(Path(state["checkpoint"]).read_bytes())["optimizer"]
    return hashlib.sha256(encoded({key: value for key, value in optimizer.items() if key != "rng"})).hexdigest()


def migrate_context_session(source: Path, destination: Path, *, name=None):
    """Explicit new session, original exact checkpoint/RNG retained. Does not train or fabricate a checkpoint."""
    from train_paper_ppo import CONTRACT
    if destination.exists() or destination.is_symlink():
        raise ValueError("migration requires a new output; existing session preserved")
    state = brain_session.load(source)
    if state.get("contract") not in {"minecraft-primitive-candidates/v1", "minecraft-primitive-candidates/v2", "minecraft-primitive-candidates/v3", "minecraft-primitive-candidates/v4", "minecraft-primitive-candidates/v5"} \
            or state["initializationOnly"] or state["pendingRolloutTransitions"] != 0:
        raise ValueError("migration requires a saved trained legacy brain without pending transitions")
    migrated = dict(state, contract=CONTRACT, contextUpgrade=True,
                    modelName=state.get("modelName") if name is None else model_name(name))
    verify_state(migrated)  # Raw provenance, full optimizer, counters, identity and saved RNG, before any write.
    destination.parent.mkdir(parents=True, exist_ok=True)
    brain_session.save(destination, migrated, recoverable=True)
    return migrated


def migrate_pending_session(source: Path, destination: Path, *, name=None):
    """Explicit legacy suffix admission, not a new update. Never changes the old session, raw or catalog."""
    import pending_rollout
    from train_paper_ppo import _session_checkpoint, validate_pending_row, CONTRACT
    if destination.exists() or destination.is_symlink():
        raise ValueError("migration output must be new; original session preserved")
    state = brain_session.load(source)
    if state.get("contract") != CONTRACT or state.get("contextUpgrade", False) \
            or state["pendingRolloutTransitions"] <= 0 or state.get("pendingRollout") is not None:
        raise ValueError("migration requires a legacy pending count in the current input contract")
    _, parent = _session_checkpoint(state)
    if state.get("notForAutonomousTraining") or restricted_training_use(state["checkpoint"]):
        raise ValueError("prepared pending evidence cannot enter the autonomous catalog")
    trained = parent["environmentSteps"]
    collected = pending_rollout.counters(state, trained)
    checkpoint = Path(state["checkpoint"])
    report = json.loads((checkpoint.parent / "training-report.json").read_bytes())
    raw = checkpoint.parent / "paper-rollouts.jsonl"
    if "rolloutFile" in report:
        # _session_checkpoint already verified this exact bundle path and the trained prefix.
        raw = checkpoint.parent.parent.parent / "paper-rollouts.jsonl"
    offset = report["rolloutBytesUsed"]
    size = raw.stat().st_size - offset
    if raw.is_symlink() or raw.parent.is_symlink() or not 0 < size <= pending_rollout.MAX_BYTES:
        raise ValueError("legacy pending suffix missing or exceeds its byte budget")
    with raw.open("rb") as stream:
        stream.seek(offset)
        data = stream.read(size)
    reference = {"path": str(raw.absolute()), "offset": offset, "bytes": size,
                 "sha256": hashlib.sha256(data).hexdigest()}
    rows = pending_rollout.raw_rows(reference)
    pending_rollout.validate_rows(state, rows, validate_pending_row)
    migrated = dict(state, trainedEnvironmentSteps=trained, collectedEnvironmentSteps=collected,
                    environmentSteps=collected, modelName=state.get("modelName") if name is None else model_name(name),
                    pendingMigration={"sourceSessionSha256": hashlib.sha256(source.read_bytes()).hexdigest(), "raw": reference})
    migrated["pendingRollout"] = pending_rollout.publish(destination.parent / "pending-rollouts", migrated, rows,
                                                        raw, offset, size)
    verify_state(migrated)
    destination.parent.mkdir(parents=True, exist_ok=True)
    brain_session.save(destination, migrated, recoverable=True)
    return migrated


class ModelCatalog:
    def __init__(self, root: Path):
        self.root = root.absolute()
        if self.root.is_symlink():
            raise ValueError("catalog root cannot be a symlink")

    @contextmanager
    def locked(self):
        self.root.mkdir(parents=True, exist_ok=True)
        lock = self.root / "catalog.lock"
        descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        try:
            os.write(descriptor, encoded({"pid": os.getpid()}))
            yield
        finally:
            os.close(descriptor)
            lock.unlink()  # An interrupted writer leaves a visible lock; never guess it is safe to remove.

    def load(self, name, *, verify=True):
        name = model_name(name)
        pointer = read_record(self.root / (name + ".json"))
        if pointer.get("schema") != SCHEMA or pointer.get("name") != name \
                or not re.fullmatch(r"revision-[0-9a-f]{32}\.json", pointer.get("revision", "")):
            raise ValueError("invalid model reference")
        revision = read_record(self.root / pointer["revision"])
        if revision.get("schema") != SCHEMA or revision.get("name") != name \
                or hashlib.sha256(encoded(revision)).hexdigest() != pointer.get("revisionSha256"):
            raise ValueError("model revision identity mismatch")
        state = revision["session"]
        brain_session.validate(state)
        if verify:
            verify_state(state)
        return revision

    def save(self, name, state, *, require_existing=False):
        name = model_name(name)
        # Copy through JSON: a caller cannot later mutate a stored session/RNG reference.
        state = verify_state(json.loads(encoded(state)))
        with self.locked():
            if require_existing and not (self.root / (name + ".json")).exists():
                raise ValueError("automatic refresh requires an existing named model; reference not recreated")
            if (self.root / (name + ".json")).exists():
                previous = self.load(name)
                old = previous["session"]
                if old["brainId"] != state["brainId"] or old["policyVersion"] > state["policyVersion"] \
                        or old["gradientUpdates"] > state["gradientUpdates"] \
                        or old.get("environmentSteps", 0) > state.get("environmentSteps", 0) \
                        or old.get("trainedEnvironmentSteps", 0) > state.get("trainedEnvironmentSteps", 0) \
                        or old["policyVersion"] == state["policyVersion"] and optimizer_identity(old) != optimizer_identity(state):
                    raise ValueError("name already belongs to another brain or newer/different weights; choose another name")
                if old == state:
                    return previous  # Idle persistence does not create unlimited identical revisions.
            revision = {"schema": SCHEMA, "name": name, "session": state}
            filename = "revision-" + uuid.uuid4().hex + ".json"
            write_record(self.root / filename, revision)
            write_record(self.root / (name + ".json"), {"schema": SCHEMA, "name": name, "revision": filename,
                         "revisionSha256": hashlib.sha256(encoded(revision)).hexdigest()})
        return revision

    def select(self, name):
        with self.locked():
            revision = self.load(name)
            write_record(self.root / "selected-model.json", {"schema": SCHEMA, "name": name,
                         "brainId": revision["session"]["brainId"]})
        return revision

    def create_initial(self, name, output, *, seed, learning_enabled=False):
        name = model_name(name)
        target = self.root / (name + ".json")
        if target.exists() or target.is_symlink():
            raise ValueError("new model requires an unused name; existing record preserved")
        from train_paper_ppo import PaperTrainer
        trainer = PaperTrainer(output, seed=seed, rollout_size=32, maximum=32, max_bytes=4*1024*1024,
                               start_state="fresh", learning_enabled=learning_enabled)
        trainer.close()  # Shared architecture, zero decisions and zero gradient updates.
        return self.save(name, brain_session.load(output / "runtime-session.json"))

    def selected(self):
        selection = read_record(self.root / "selected-model.json")
        if selection.get("schema") != SCHEMA:
            raise ValueError("invalid model selection")
        revision = self.load(selection["name"])
        if revision["session"]["brainId"] != selection.get("brainId"):
            raise ValueError("selected brain identity changed")
        return revision

    def list(self, *, limit=None):
        if limit is not None and (type(limit) is not int or not 1 <= limit <= 10000):
            raise ValueError("invalid catalog list limit")
        if not self.root.exists():
            return []
        # Immutable history grows during automatic snapshots; the budget counts named models, not revisions.
        paths = sorted(path for path in self.root.glob("*.json")
                       if path.name != "selected-model.json" and not path.name.startswith("revision-"))
        if len(paths) > 10000:
            raise ValueError("catalog scan exceeds budget")
        result = []
        for path in paths:
            if path.name == "selected-model.json" or path.name.startswith("revision-"):
                continue
            try:
                revision = self.load(path.stem)
                state = revision["session"]
                result.append({"name": path.stem, "valid": True, **{key: state[key] for key in
                              ("brainId", "policyVersion", "gradientUpdates", "learningEnabled", "initializationOnly")}})
            except (OSError, ValueError, KeyError, TypeError) as failure:
                result.append({"name": path.stem, "valid": False, "error": type(failure).__name__})
            if limit is not None and len(result) >= limit:
                break
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True)
    commands = parser.add_subparsers(dest="operation", required=True)
    commands.add_parser("list")
    commands.add_parser("selected")
    for command in ("info", "select", "save", "restore", "new", "import", "migrate-context", "migrate-pending"):
        sub = commands.add_parser(command)
        sub.add_argument("name", type=model_name)
        if command in {"save", "migrate-context", "migrate-pending"}:
            sub.add_argument("--session", type=Path, required=True)
        if command == "import":
            sub.add_argument("--checkpoint", type=Path, required=True)
            sub.add_argument("--learning", choices=("on", "off"), required=True)
        if command in ("restore", "new", "migrate-context", "migrate-pending"):
            sub.add_argument("--output", type=Path, required=True)
        if command == "new":
            sub.add_argument("--seed", type=int, required=True)
            sub.add_argument("--learning", choices=("on", "off"), required=True)
    args = parser.parse_args()
    catalog = ModelCatalog(args.catalog)
    if args.operation == "list":
        result = catalog.list()
    elif args.operation == "selected":
        result = catalog.selected()
    elif args.operation == "info":
        result = catalog.load(args.name)
    elif args.operation == "select":
        result = catalog.select(args.name)
    elif args.operation == "save":
        result = catalog.save(args.name, brain_session.load(args.session))
    elif args.operation in {"migrate-context", "migrate-pending"}:
        # A different catalog/name can preserve the entire previous contract and its selection.
        target = catalog.root / (args.name + ".json")
        if target.exists() or target.is_symlink():
            raise ValueError("migration requires an unused catalog name; existing model preserved")
        migration = migrate_context_session if args.operation == "migrate-context" else migrate_pending_session
        state = migration(args.session, args.output, name=args.name)
        result = catalog.save(args.name, state)
    elif args.operation == "import":
        from train_paper_ppo import resume_checkpoint, CONTRACT
        with redirect_stdout(sys.stderr):  # Recovery diagnostics must not corrupt the CLI's JSON result.
            model, parent = resume_checkpoint(args.checkpoint)  # Imported models must have real updates.
        result = catalog.save(args.name, {"schema": brain_session.SCHEMA, "brainId": parent["brainId"],
            "checkpoint": parent["checkpointPath"], "checkpointSha256": parent["checkpointSha256"],
            "policyVersion": model.version, "gradientUpdates": model.updates,
            "learningEnabled": args.learning == "on", "rng": model.rng.bit_generator.state,
            "initializationOnly": False, "pendingRolloutTransitions": 0, "startState": "pretrained",
            "contract": CONTRACT, "contextUpgrade": False})
    elif args.operation == "restore":
        if args.output.exists():
            raise ValueError("restore output must be new; existing state is never overwritten")
        result = catalog.load(args.name)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        brain_session.save(args.output, result["session"])
    else:
        result = catalog.create_initial(args.name, args.output, seed=args.seed, learning_enabled=args.learning == "on")
    print(json.dumps(result, ensure_ascii=False, allow_nan=False))


if __name__ == "__main__":
    main()
