"""Train exclusively from acknowledged Paper game transitions via the local development bridge.

Does not start a server, reset worlds, summon players, run Teacher policies or manufacture game experience.
"""
from __future__ import annotations

import argparse
import copy
import os
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import math
import secrets
from pathlib import Path
import time
import uuid

# Keep game sampling responsive while a background PPO update uses a separate parameter snapshot.
for variable in ("OPENBLAS_NUM_THREADS", "OMP_NUM_THREADS", "MKL_NUM_THREADS"):
    os.environ.setdefault(variable, "1")

from rl_actor import ActorCritic, CONTRACT, STATE_SIZE, inputs
import checkpoint_store
import brain_session
import model_catalog

SCHEMA = "minecraft-paper-ppo/v2"
CONTROL_SCHEMA = "minecraft-brain-control/v1"
MAX_PACKET = 4 * 1024 * 1024


def write_atomic(path: Path, value: dict):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")), encoding="utf-8")
    temporary.replace(path)


def validate_transition(row: dict):
    required = {"session", "sequence", "frame", "index", "logProbability", "value", "modelVersion", "reward",
                "elapsedTicks", "terminated", "truncated", "appliedTick", "nextWorld", "nextState"}
    if set(row) != required:
        raise ValueError("transition schema mismatch; Teacher labels are not accepted")
    uuid.UUID(row["session"])
    uuid.UUID(row["nextWorld"])
    if not isinstance(row["appliedTick"], int) or not row["frame"]["serverTick"] <= row["appliedTick"] <= 0xffff_ffff:
        raise ValueError("invalid actual application tick")
    if len(row["nextState"]) != STATE_SIZE or any(not isinstance(x, (int, float)) or not math.isfinite(x) or abs(x) > 16 for x in row["nextState"]):
        raise ValueError("invalid actual next state")
    inputs(row["frame"])
    if not isinstance(row["sequence"], int) or row["sequence"] < 0 or not isinstance(row["modelVersion"], int) or row["modelVersion"] < 0:
        raise ValueError("invalid sequence/model version")
    if not isinstance(row["index"], int) or not 0 <= row["index"] < len(row["frame"]["options"]):
        raise ValueError("invalid actual executed candidate")
    if not isinstance(row["elapsedTicks"], int) or not 0 <= row["elapsedTicks"] <= 2400:
        raise ValueError("invalid elapsed game ticks")
    for key in ("logProbability", "value", "reward"):
        if not isinstance(row[key], (int, float)) or not math.isfinite(row[key]):
            raise ValueError("invalid transition scalar")
    if row["logProbability"] > 0 or abs(row["reward"]) > 100 or not isinstance(row["terminated"], bool) or not isinstance(row["truncated"], bool):
        raise ValueError("invalid transition conditions")


def resume_checkpoint(path: Path, *, upgrade_context=False, allow_initial=False):
    if path.is_dir():
        failures = []
        allow_initial_recovery = allow_initial and not checkpoint_store.has_trained_history(path)
        for candidate in checkpoint_store.candidates(path):
            try:
                model, parent = resume_checkpoint(candidate, upgrade_context=upgrade_context, allow_initial=allow_initial_recovery)
                parent["recoveredCheckpoint"] = str(candidate.relative_to(path))
                print("Verified checkpoint recovery:", parent["recoveredCheckpoint"], flush=True)
                return model, parent
            except (OSError, ValueError, KeyError, TypeError) as failure:
                failures.append(type(failure).__name__)
        raise ValueError("no complete compatible checkpoint with valid raw provenance; originals preserved: " + ",".join(failures))
    if not path.is_file() or path.stat().st_size > 16 * 1024 * 1024:
        raise ValueError("missing/oversized PPO checkpoint")
    encoded = path.read_bytes()
    checkpoint_hash = hashlib.sha256(encoded).hexdigest()
    report = json.loads((path.parent / "training-report.json").read_text(encoding="utf-8"))
    checkpoint = json.loads(encoded)
    initial = checkpoint.get("initializationOnly", False)
    if type(initial) is not bool or initial != report.get("initializationOnly", False) or initial and not allow_initial:
        raise ValueError("initial state is not a trained/pretrained checkpoint")
    if checkpoint_hash != report.get("checkpointSha256") or checkpoint.get("environment") != "minecraft-paper" \
            or checkpoint.get("schema") != "minecraft-paper-checkpoint/v1" \
            or checkpoint.get("run") != report.get("run") \
            or checkpoint.get("environmentSteps") != report.get("environmentSteps") \
            or type(checkpoint.get("environmentSteps")) is not int \
            or (checkpoint["environmentSteps"] != 0 if initial else checkpoint["environmentSteps"] <= 0):
        raise ValueError("checkpoint provenance mismatch")
    # Verify the exact raw prefix used by this update, including all failed outcomes in that prefix.
    prefix_bytes = report["rolloutBytesUsed"]
    if type(prefix_bytes) is not int or (prefix_bytes != 0 if initial else prefix_bytes <= 0):
        raise ValueError("invalid rollout prefix")
    remaining, digest = prefix_bytes, hashlib.sha256()
    raw_path = path.parent / "paper-rollouts.jsonl"
    if "rolloutFile" in report:
        if report["rolloutFile"] != "../../paper-rollouts.jsonl" or path.parent.parent.name != "checkpoints":
            raise ValueError("invalid bundle rollout source")
        raw_path = path.parent.parent.parent / "paper-rollouts.jsonl"
    with raw_path.open("rb") as raw:
        while remaining:
            block = raw.read(min(remaining, 1024 * 1024))
            if not block:
                raise ValueError("incomplete rollout provenance")
            digest.update(block); remaining -= len(block)
    if digest.hexdigest() != checkpoint.get("rolloutSha256") or digest.hexdigest() != report.get("rolloutSha256"):
        raise ValueError("rollout provenance mismatch")
    if upgrade_context:
        model = ActorCritic.upgrade_legacy_context(checkpoint["optimizer"])
    else:
        model = ActorCritic.from_optimizer_state(checkpoint["optimizer"], allow_initial=initial)
    if initial and (model.updates != 0 or model.version != 0):
        raise ValueError("initial state contains learned counters")
    if model.version != report.get("modelVersion") or model.updates != report.get("gradientUpdates"):
        raise ValueError("optimizer/report version mismatch")
    origin = checkpoint.get("brainId") or (checkpoint.get("parents") or [{"trainingRun": checkpoint["run"]}])[0]["trainingRun"]
    uuid.UUID(origin)
    return model, {"checkpointSha256": checkpoint_hash, "trainingRun": checkpoint["run"], "contextUpgrade": upgrade_context,
                   "brainId": origin,
                   "initializationOnly": initial,
                   "checkpointPath": str(path.resolve()),
                   "environmentSteps": checkpoint["environmentSteps"], "lineage": checkpoint.get("parents", []),
                   "notForAutonomousTraining": model_catalog.restricted_training_use(path)}


class PaperTrainer:
    def __init__(self, output: Path, *, seed: int, rollout_size: int, maximum: int, max_bytes: int,
                 resume: Path | None = None, upgrade_context=False, learning_enabled=True, start_state=None,
                 restore_state: dict | None = None, session_save_seconds: int = 30, model_catalog_path: Path | None = None):
        if not 32 <= rollout_size <= 4096 or maximum < rollout_size or max_bytes < MAX_PACKET:
            raise ValueError("invalid training budget")
        if upgrade_context and resume is None:
            raise ValueError("context upgrade requires an actual trained legacy checkpoint")
        start_state = start_state or ("fresh" if resume is None else "resume")
        if start_state not in {"fresh", "pretrained", "resume"} or (start_state == "fresh") != (resume is None):
            raise ValueError("starting weights must match the explicitly selected fresh/pretrained/resume source")
        if type(learning_enabled) is not bool:
            raise ValueError("learning state must be an explicit boolean")
        if not 1 <= session_save_seconds <= 3600:
            raise ValueError("session save interval must be 1..3600 seconds")
        model, parent = (ActorCritic(seed), None) if resume is None else resume_checkpoint(
                resume, upgrade_context=upgrade_context, allow_initial=start_state != "pretrained")
        checkpoint_rng = copy.deepcopy(model.rng.bit_generator.state)
        if restore_state is not None:
            brain_session.validate(restore_state)
            if parent is None or parent["brainId"] != restore_state["brainId"] \
                    or parent["checkpointSha256"] != restore_state["checkpointSha256"] \
                    or model.version != restore_state["policyVersion"] or model.updates != restore_state["gradientUpdates"] \
                    or restore_state.get("contextUpgrade", False) != upgrade_context:
                raise ValueError("session/model identity mismatch; no reset or hidden model replacement")
            model.rng.bit_generator.state = restore_state["rng"]
        output.mkdir(parents=True, exist_ok=False)  # A new run never overwrites another model or provenance.
        self.output = output
        self.session_alias = None
        self.model_name = restore_state.get("modelName") if restore_state else None
        self.catalog = None if model_catalog_path is None else model_catalog.ModelCatalog(model_catalog_path)
        self.restricted_training_use = bool(parent is not None and parent["notForAutonomousTraining"]
                or restore_state is not None and restore_state.get("notForAutonomousTraining", False)
                or model_catalog.restricted_training_use(output / "optimizer-checkpoint.json"))
        write_atomic(output / "catalog-use.json", {"notForAutonomousTraining": self.restricted_training_use})
        self.run = str(uuid.uuid4())
        self.brain_id = self.run if parent is None else parent["brainId"]
        self.training_start_state = start_state
        self.learning_enabled = learning_enabled
        self.decisions = 0
        self.saved_model_version = -1 if parent is None else model.version
        self.saved_checkpoint_sha = None if parent is None else parent["checkpointSha256"]
        self.checkpoint_reference = None if parent is None else parent["checkpointPath"]
        self.checkpoint_upgrade = False if parent is None else parent["contextUpgrade"]
        self.session_save_seconds = session_save_seconds
        self.last_session_save = 0.0
        self.model, self.parent = model, parent
        self.saved_checkpoint_rng = checkpoint_rng
        self.parent_steps = 0 if parent is None else parent["environmentSteps"]
        self.parents = [] if parent is None else [*parent["lineage"], {key: parent[key] for key in ("checkpointSha256", "trainingRun", "environmentSteps", "contextUpgrade")}]
        self.rollout_size, self.maximum, self.max_bytes = rollout_size, maximum, max_bytes
        self.rows = []
        self.worker = ThreadPoolExecutor(max_workers=1, thread_name_prefix="paper-ppo-update")
        self.training = None
        self.training_metadata = None
        self.discarded_during_update = 0
        self.last_sequence = {}
        self.steps = self.ticks = self.bytes = self.discarded_off_policy = 0
        self.digest = hashlib.sha256()
        self.raw = (output / "paper-rollouts.jsonl").open("ab")
        self.discarded_raw = (output / "discarded-transitions.jsonl").open("ab")
        self.discarded_bytes = 0
        self.discard_reasons = {}
        self.episode_raw = (output / "episode-outcomes.jsonl").open("ab")
        self.episodes = {}
        self.completed_episodes = self.death_episodes = self.episode_bytes = 0
        if self.model.updates == 0 and parent is None:
            self.save_initial_state()
        write_atomic(output / "run.json", {"schema": SCHEMA, "contract": CONTRACT, "algorithm": "ppo",
                     "brainId": self.brain_id, "trainingStartState": self.training_start_state,
                     "learningEnabled": self.learning_enabled,
                     "environment": "minecraft-paper", "run": self.run, "seed": seed,
                     "maxTransitions": maximum, "rolloutSize": rollout_size, "teacherLabels": False,
                     "initialWeights": "random" if parent is None else "verified-initial-state" if parent.get("initializationOnly") else "verified-ppo-checkpoint", "parents": self.parents,
                     "resumeRecovery": None if parent is None else parent.get("recoveredCheckpoint"),
                     "gammaPerServerTick": 0.99997, "gaeLambda": 0.95,
                     "ppoClip": 0.2, "epochsPerUpdate": 4, "trained": False, "evaluationComplete": False})
        self.persist_session()

    def persist_session(self):
        if self.checkpoint_reference is None or self.saved_model_version != self.model.version:
            raise RuntimeError("active brain has no matching durable checkpoint; session not replaced")
        state = {"schema": brain_session.SCHEMA,
                "brainId": self.brain_id, "checkpoint": self.checkpoint_reference, "checkpointSha256": self.saved_checkpoint_sha,
                "policyVersion": self.model.version, "gradientUpdates": self.model.updates, "learningEnabled": self.learning_enabled,
                "environmentSteps": self.parent_steps + self.steps,
                "rng": self.model.rng.bit_generator.state, "initializationOnly": self.model.updates == 0,
                "pendingRolloutTransitions": len(self.rows), "startState": self.training_start_state, "contract": CONTRACT,
                "contextUpgrade": self.checkpoint_upgrade, "notForAutonomousTraining": self.restricted_training_use,
                "modelName": self.model_name}
        brain_session.save(self.output / "runtime-session.json", state)
        if self.session_alias is not None:
            brain_session.save(self.session_alias, state)
        self.last_session_save = time.monotonic()

    def control(self, packet):
        if packet.get("operation") in {"model-list", "model-info", "model-save", "model-new"}:
            return self.model_control(packet)
        if set(packet) != {"schema", "id", "operation", "brainId", "enabled"} \
                or packet["schema"] != CONTROL_SCHEMA or packet["operation"] != "learning":
            raise ValueError("unsupported brain control request")
        uuid.UUID(packet["id"])
        if packet["brainId"] != self.brain_id or type(packet["enabled"]) is not bool:
            raise ValueError("brain identity/learning flag mismatch")
        # The caller drains its active transition first. Finish an already submitted optimizer before acknowledging pause.
        self.poll_training(wait=True)
        previous = self.learning_enabled
        self.learning_enabled = packet["enabled"]
        try:
            self.persist_session()  # Durable mode first; failure is never acknowledged as a completed switch.
        except Exception:
            self.learning_enabled = previous
            raise
        weights_hash = hashlib.sha256(checkpoint_store.encoded({key: value.tolist() for key, value in self.model.params.items()})).hexdigest()
        return {"schema": CONTROL_SCHEMA, "id": packet["id"], "ok": True, "weightsSha256": weights_hash,
                "status": self.status()}

    def model_control(self, packet):
        if set(packet) != {"schema", "id", "operation", "brainId", "name"} or packet["schema"] != CONTROL_SCHEMA:
            raise ValueError("unsupported model control request")
        uuid.UUID(packet["id"])
        if packet["brainId"] != self.brain_id:
            raise ValueError("brain identity mismatch")
        if self.catalog is None:
            raise ValueError("model catalog is not configured for this backend")
        operation = packet["operation"]
        name = packet["name"]
        if operation == "model-list":
            if name != "":
                raise ValueError("list must not select a model")
            entries = self.catalog.list(limit=33)
            summaries, has_more = entries[:32], len(entries) > 32
        elif operation in {"model-info", "model-save", "model-new"}:
            model_catalog.model_name(name)
            if operation == "model-new":
                if self.restricted_training_use or model_catalog.restricted_training_use(self.output / "optimizer-checkpoint.json"):
                    raise ValueError("prepared scenario initialization cannot enter the autonomous model catalog")
                initial_output = self.output / "initial-models" / (name + "-" + uuid.uuid4().hex)
                try:
                    revision = self.catalog.create_initial(name, initial_output, seed=secrets.randbits(63), learning_enabled=False)
                except FileExistsError as failure:
                    raise ValueError("another catalog writer is active; current brain is unchanged") from failure
            elif operation == "model-save":
                if self.restricted_training_use or model_catalog.restricted_training_use(self.output / "optimizer-checkpoint.json"):
                    raise ValueError("prepared scenario snapshots cannot enter the autonomous model catalog")
                self.poll_training(wait=True)
                self.persist_session()
                try:
                    revision = self.catalog.save(name, brain_session.load(self.output / "runtime-session.json"))
                except FileExistsError as failure:
                    raise ValueError("another catalog writer is active; model was not replaced") from failure
            else:
                revision = self.catalog.load(name)
            summaries = [{"name": name, "valid": True, **{key: revision["session"][key] for key in
                         ("brainId", "policyVersion", "gradientUpdates", "learningEnabled", "initializationOnly")}}]
            has_more = False
        else:
            raise ValueError("unsupported model operation")
        weights_hash = hashlib.sha256(checkpoint_store.encoded({key: value.tolist() for key, value in self.model.params.items()})).hexdigest()
        return {"schema": CONTROL_SCHEMA, "id": packet["id"], "ok": True, "operation": operation, "name": name,
                "weightsSha256": weights_hash, "status": self.status(), "models": summaries, "hasMore": has_more}

    def status(self):
        return {"modelVersion": self.model.version, "environmentSteps": self.parent_steps + self.steps,
                "metadata": {"brainId": self.brain_id, "trainingStartState": self.training_start_state,
                             "initializationOnly": self.model.updates == 0,
                             "gradientUpdates": self.model.updates, "learningEnabled": self.learning_enabled,
                             "saveState": "updating" if self.training is not None else "saved" if self.saved_model_version == self.model.version else "not-saved",
                             "checkpointSha256": self.saved_checkpoint_sha}}

    def save_initial_state(self):
        empty_hash = hashlib.sha256(b"").hexdigest()
        initial = {"schema": "minecraft-paper-checkpoint/v1", "environment": "minecraft-paper", "initializationOnly": True,
                   "run": self.run, "brainId": self.brain_id, "environmentSteps": 0, "rolloutSha256": empty_hash,
                   "parents": self.parents, "optimizer": self.model.optimizer_state(allow_initial=True)}
        report = {"run": self.run, "brainId": self.brain_id, "environment": "minecraft-paper", "initializationOnly": True,
                  "environmentSteps": 0, "runEnvironmentSteps": 0, "gradientUpdates": 0, "modelVersion": 0,
                  "rolloutBytesUsed": 0, "rolloutSha256": empty_hash, "learningEnabled": self.learning_enabled,
                  "trainingStartState": self.training_start_state, "evaluationComplete": False, "allWorldAutonomyProven": False}
        self.raw.flush(); os.fsync(self.raw.fileno())
        bundle = checkpoint_store.publish(self.output, self.model.initial_artifact(brain_id=self.brain_id, training_run=self.run), initial, report)
        self.checkpoint_reference = str((bundle / "optimizer-checkpoint.json").resolve())
        self.checkpoint_upgrade = False
        self.saved_model_version = 0
        self.saved_checkpoint_sha = hashlib.sha256(checkpoint_store.encoded(initial)).hexdigest()
        self.saved_checkpoint_rng = copy.deepcopy(self.model.rng.bit_generator.state)

    def handle(self, packet: dict) -> dict:
        self.poll_training()
        if packet.get("schema") != SCHEMA or packet.get("environment") != "minecraft-paper":
            raise ValueError("only actual Paper bridge schema accepted")
        if set(packet) - {"schema", "environment", "id", "frame", "transitions", "brainId", "controlEpoch"}:
            raise ValueError("unexpected query data")
        if packet.get("brainId") is not None and packet["brainId"] != self.brain_id:
            raise ValueError("query belongs to another brain")
        if packet.get("controlEpoch") is not None:
            uuid.UUID(packet["controlEpoch"])
        uuid.UUID(packet["id"])
        frame = packet["frame"]
        inputs(frame)
        uuid.UUID(frame["world"])
        if not isinstance(frame["serverTick"], int) or not 0 <= frame["serverTick"] <= 0xffff_ffff:
            raise ValueError("invalid captured game clock")
        transitions = packet.get("transitions", [])
        if not isinstance(transitions, list) or len(transitions) > 16:
            raise ValueError("invalid transition batch")
        for previous in transitions:
            validate_transition(previous)
            session, sequence = previous["session"], previous["sequence"]
            if sequence > self.last_sequence.get(session, -1):
                self.last_sequence[session] = sequence
                self.observe_episode(previous)
                if not self.learning_enabled:
                    self.preserve_discard(previous, "learning-disabled")
                elif previous["modelVersion"] != self.model.version:
                    self.discarded_off_policy += 1
                    self.preserve_discard(previous, "off-policy-version")
                elif self.training is not None:
                    self.discarded_during_update += 1
                    self.preserve_discard(previous, "optimizer-in-progress")
                elif self.steps < self.maximum:
                    next_value, _ = self.model.value(previous["nextState"])
                    row = dict(previous)
                    # A cross-world boundary is not bootstrapped from an unrelated world's body state.
                    row["nextValue"] = 0 if row["terminated"] or row["nextWorld"] != row["frame"]["world"] else next_value
                    encoded = (json.dumps(row, ensure_ascii=False, allow_nan=False, separators=(",", ":")) + "\n").encode()
                    if self.bytes + self.discarded_bytes + self.episode_bytes + len(encoded) > self.max_bytes:
                        raise RuntimeError("raw Paper rollout byte budget exhausted")
                    self.raw.write(encoded); self.raw.flush(); self.digest.update(encoded); self.bytes += len(encoded)
                    self.rows.append(row); self.steps += 1; self.ticks += row["elapsedTicks"]
                    if len(self.rows) >= self.rollout_size or self.steps == self.maximum:
                        rows = self.rows
                        self.rows = []
                        self.training_metadata = {"steps": self.steps, "ticks": self.ticks, "bytes": self.bytes,
                                                  "sha256": self.digest.hexdigest()}
                        self.training = self.worker.submit(self.fit_snapshot, copy.deepcopy(self.model), rows)
                else:
                    self.preserve_discard(previous, "transition-budget-complete")
        result = self.model.sample(frame)
        self.decisions += 1
        if time.monotonic() - self.last_session_save >= self.session_save_seconds:
            self.persist_session()
        return {"schema": SCHEMA, "id": packet["id"], **result, **self.status()}

    def preserve_discard(self, transition, reason):
        encoded = (json.dumps({"reason": reason, "activePolicyVersion": self.model.version, "transition": transition},
                    ensure_ascii=False, allow_nan=False, separators=(",", ":")) + "\n").encode()
        if self.bytes + self.discarded_bytes + self.episode_bytes + len(encoded) > self.max_bytes:
            raise RuntimeError("Paper evidence byte budget exhausted; excluded outcomes are not silently deleted")
        self.discarded_raw.write(encoded); self.discarded_raw.flush()
        self.discarded_bytes += len(encoded)
        self.discard_reasons[reason] = self.discard_reasons.get(reason, 0) + 1

    def observe_episode(self, row):
        session, sequence = row["session"], row["sequence"]
        episode = self.episodes.setdefault(session, {"schema": "minecraft-rl-episode-summary/v1", "session": session,
            "firstSequence": sequence, "lastSequence": sequence-1, "observedTransitions": 0, "missingSequenceCount": sequence,
            "actualServerTicks": 0, "rewardSum": 0.0, "minimumHealthRatio": 1.0, "policyVersions": [],
            "terminated": False, "truncated": False, "episodeEnded": False, "allWorldAutonomyProven": False})
        episode["missingSequenceCount"] += max(0, sequence - episode["lastSequence"] - 1)
        episode["lastSequence"] = sequence
        episode["observedTransitions"] += 1
        episode["actualServerTicks"] += row["elapsedTicks"]
        episode["rewardSum"] += row["reward"]
        episode["minimumHealthRatio"] = min(episode["minimumHealthRatio"], row["frame"]["state"][0], row["nextState"][0])
        if row["modelVersion"] not in episode["policyVersions"]:
            episode["policyVersions"].append(row["modelVersion"])
        if row["terminated"] or row["truncated"]:
            episode.update({"terminated": row["terminated"], "truncated": row["truncated"], "episodeEnded": True})
            self.write_episode(episode)
            self.completed_episodes += 1
            self.death_episodes += row["terminated"]
            del self.episodes[session]

    def write_episode(self, episode):
        episode["completeObservedSequence"] = episode["episodeEnded"] and episode["missingSequenceCount"] == 0
        episode["scope"] = "observed-unique-transitions-not-only-ppo-used"
        encoded = (json.dumps(episode, ensure_ascii=False, allow_nan=False, separators=(",", ":")) + "\n").encode()
        if self.bytes + self.discarded_bytes + self.episode_bytes + len(encoded) > self.max_bytes:
            raise RuntimeError("Paper episode evidence byte budget exhausted")
        self.episode_raw.write(encoded); self.episode_raw.flush(); self.episode_bytes += len(encoded)

    @staticmethod
    def fit_snapshot(model, rows):
        return model, model.fit(rows)

    def poll_training(self, wait=False):
        if self.training is None or not wait and not self.training.done():
            return
        future = self.training
        self.training = None
        try:
            self.model, stats = future.result()
        except Exception as error:
            raise RuntimeError("PPO update failed; no new trained artifact published") from error
        self.save(stats, self.training_metadata)
        self.training_metadata = None

    def save(self, stats: dict, metadata: dict):
        total_steps = self.parent_steps + metadata["steps"]
        artifact = self.model.artifact(environment_steps=total_steps, training_run=self.run, rollout_sha256=metadata["sha256"])
        artifact["parents"] = self.parents
        artifact.update({"brainId": self.brain_id, "trainingStartState": self.training_start_state,
                         "modelVersion": self.model.version, "gradientUpdates": self.model.updates})
        checkpoint = {"schema": "minecraft-paper-checkpoint/v1", "environment": "minecraft-paper",
                     "run": self.run, "environmentSteps": total_steps, "rolloutSha256": metadata["sha256"],
                     "parents": self.parents, "optimizer": self.model.optimizer_state(), "brainId": self.brain_id}
        report = {"run": self.run, "environment": "minecraft-paper",
                     "brainId": self.brain_id, "trainingStartState": self.training_start_state,
                     "environmentSteps": total_steps, "runEnvironmentSteps": metadata["steps"], "parents": self.parents,
                     "actualServerTicks": metadata["ticks"], "gradientUpdates": self.model.updates,
                     "modelVersion": self.model.version, "rolloutSha256": metadata["sha256"], "rolloutBytesUsed": metadata["bytes"],
                     "discardedOffPolicy": self.discarded_off_policy,
                     "discardedDuringUpdate": self.discarded_during_update,
                     "discardedEvidenceBytes": self.discarded_bytes, "discardReasons": dict(self.discard_reasons),
                     "observedCompletedEpisodes": self.completed_episodes, "observedDeathEpisodes": self.death_episodes,
                     "openObservedEpisodes": len(self.episodes),
                     "teacherLabels": False, "initializationOnly": False, "evaluationComplete": False,
                     "allWorldAutonomyProven": False, "trainingMetrics": stats}
        self.raw.flush(); os.fsync(self.raw.fileno())
        bundle = checkpoint_store.publish(self.output, artifact, checkpoint, report)
        self.checkpoint_reference = str((bundle / "optimizer-checkpoint.json").resolve())
        self.checkpoint_upgrade = False
        self.saved_model_version = self.model.version
        self.saved_checkpoint_sha = hashlib.sha256(checkpoint_store.encoded(checkpoint)).hexdigest()
        self.persist_session()

    def flush_episodes(self):
        for episode in self.episodes.values():
            self.write_episode(episode)
        self.episodes.clear()

    def close(self, *, publish_session=True):
        # An incomplete on-policy buffer is preserved as raw evidence, never relabeled as a trained update.
        try:
            self.poll_training(wait=True)
            if publish_session:
                if self.model.updates == 0 and self.model.rng.bit_generator.state != self.saved_checkpoint_rng:
                    self.save_initial_state()  # Preserve the current RNG stream without claiming training.
                self.persist_session()
        finally:
            self.worker.shutdown(wait=True)
            try:
                self.flush_episodes()
            finally:
                self.raw.close()
                self.discarded_raw.close()
                self.episode_raw.close()


class BrainRuntime:
    """One active trainer. Explicit load stages a verified checkpoint before atomically publishing its session."""
    def __init__(self, trainer):
        self.active = trainer
        self.root = trainer.output
        self.maximum = trainer.maximum
        self.max_bytes = trainer.max_bytes
        self.steps = self.decisions = self.bytes = self.switches = 0
        self.require_identity = False
        self.control_epoch = None

    def __getattr__(self, name):
        return getattr(self.active, name)

    @staticmethod
    def counters(trainer):
        return trainer.steps, trainer.decisions, trainer.bytes + trainer.discarded_bytes + trainer.episode_bytes

    def account(self, trainer, before):
        after = self.counters(trainer)
        self.steps += after[0]-before[0]
        self.decisions += after[1]-before[1]
        self.bytes += after[2]-before[2]

    def handle(self, packet):
        if self.require_identity and (packet.get("brainId") != self.active.brain_id or packet.get("controlEpoch") != self.control_epoch):
            if packet.get("transitions"):
                evidence = (json.dumps({"reason": "brain-or-load-epoch-mismatch", "query": packet},
                                      allow_nan=False, separators=(",", ":"))+"\n").encode()
                if self.bytes + len(evidence) > self.max_bytes:
                    raise RuntimeError("rejected query evidence budget exhausted")
                with (self.root / "rejected-brain-queries.jsonl").open("ab") as rejected:
                    rejected.write(evidence); rejected.flush(); os.fsync(rejected.fileno())
                self.bytes += len(evidence)
            raise ValueError("stale or unidentified query after model load")
        trainer = self.active
        before = self.counters(trainer)
        try:
            return trainer.handle(packet)
        finally:
            self.account(trainer, before)

    def control(self, packet):
        if packet.get("operation") != "model-load":
            return self.active.control(packet)
        if set(packet) != {"schema", "id", "operation", "brainId", "name"} or packet["schema"] != CONTROL_SCHEMA:
            raise ValueError("invalid model load request")
        uuid.UUID(packet["id"])
        old = self.active
        if packet["brainId"] != old.brain_id or old.catalog is None:
            raise ValueError("load source brain/catalog mismatch; active model retained")
        if self.switches >= 128:
            raise ValueError("runtime model switch budget exhausted")
        revision = old.catalog.load(packet["name"])  # Full state, weights, optimizer and raw-prefix verification first.
        restored = dict(revision["session"], modelName=revision["name"])
        old.poll_training(wait=True)
        before = self.counters(old)
        old.flush_episodes()
        self.account(old, before)
        old.persist_session()
        remaining, remaining_bytes = self.maximum-self.steps, self.max_bytes-self.bytes
        if remaining < old.rollout_size or remaining_bytes < MAX_PACKET:
            raise ValueError("remaining runtime budget cannot admit a model load")
        output = self.root / "model-loads" / ("load-" + uuid.uuid4().hex)
        replacement = PaperTrainer(output, seed=777, rollout_size=old.rollout_size, maximum=remaining,
                max_bytes=remaining_bytes, resume=Path(restored["checkpoint"]), start_state="resume",
                upgrade_context=restored.get("contextUpgrade", False), restore_state=restored,
                learning_enabled=old.learning_enabled, session_save_seconds=old.session_save_seconds,
                model_catalog_path=old.catalog.root)
        # Stage writes only its own run. The single authoritative runtime session is published last.
        replacement.session_alias = self.root / "runtime-session.json"
        try:
            replacement.persist_session()
        except (OSError, ValueError, RuntimeError) as failure:
            replacement.session_alias = None
            replacement.close(publish_session=False)
            raise ValueError("new model session was not committed; active model retained") from failure
        self.active = replacement
        self.require_identity = True
        self.control_epoch = packet["id"]
        self.switches += 1
        try:
            return self.finish_load(old, replacement, packet)
        except (OSError, ValueError, KeyError, TypeError) as failure:
            raise RuntimeError("model session committed but completion failed; stop and restore the durable session") from failure

    def finish_load(self, old, replacement, packet):
        # The old primary output is also the authoritative alias. Retirement must not republish its old session.
        old.session_alias = None
        old.close(publish_session=False)
        with (self.root / "model-switches.jsonl").open("a", encoding="utf-8") as journal:
            journal.write(json.dumps({"id": packet["id"], "previousBrainId": old.brain_id,
                "brainId": replacement.brain_id, "modelName": packet["name"], "checkpointSha256": replacement.saved_checkpoint_sha,
                "runtimeTransitions": self.steps, "runtimeDecisions": self.decisions, "learningEnabled": replacement.learning_enabled})+"\n")
            journal.flush(); os.fsync(journal.fileno())
        weights_hash = hashlib.sha256(checkpoint_store.encoded({key: value.tolist() for key, value in replacement.model.params.items()})).hexdigest()
        summary = {"name": packet["name"], "valid": True, "brainId": replacement.brain_id,
                   "policyVersion": replacement.model.version, "gradientUpdates": replacement.model.updates,
                   "learningEnabled": replacement.learning_enabled, "initializationOnly": replacement.model.updates == 0}
        return {"schema": CONTROL_SCHEMA, "id": packet["id"], "ok": True, "operation": "model-load", "name": packet["name"],
                "previousBrainId": old.brain_id, "status": replacement.status(), "weightsSha256": weights_hash,
                "models": [summary], "hasMore": False}

    def close(self):
        self.active.close()


def exchange_request(request: Path, channel: Path, trainer: PaperTrainer, pending: dict):
    """Windows can temporarily deny rename/unlink. Retry the SAME result; never resample/relearn on cleanup failure."""
    handling = False
    try:
        if request not in pending:
            if len(pending) >= 1024:
                raise RuntimeError("Paper response retry budget exhausted")
            if request.is_symlink() or request.stat().st_size > MAX_PACKET:
                raise ValueError("invalid request file")
            packet = json.loads(request.read_text(encoding="utf-8"))
            if request.name != str(uuid.UUID(packet["id"])) + ".request.json":
                raise ValueError("request identity mismatch")
            handling = True
            result = trainer.handle(packet)
            handling = False
            pending[request] = {"response": result, "published": False}
        saved = pending[request]
        if not saved["published"] and request.exists():
            write_atomic(channel / (request.stem.removesuffix(".request") + ".response.json"), saved["response"])
            saved["published"] = True
        request.unlink(missing_ok=True)
        pending.pop(request, None)
    except FileNotFoundError:
        pending.pop(request, None)  # Expired Java request; never restart it or apply its old response.
    except PermissionError:
        if handling:
            raise  # A trainer/checkpoint storage failure is not a transient transport cleanup failure.
        # Bounded file retry on the next iteration; any computed result stays immutable in pending.
    except (ValueError, KeyError, TypeError) as error:
        print("Rejected Paper packet:", type(error).__name__, str(error)[:160], flush=True)
        pending[request] = {"response": None, "published": True}
        try:
            request.unlink(missing_ok=True)
            pending.pop(request, None)
        except PermissionError:
            pass


def exchange_control(request: Path, channel: Path, trainer: PaperTrainer, pending: dict):
    handling = False
    try:
        if request not in pending:
            if len(pending) >= 64:
                raise RuntimeError("brain control retry budget exhausted")
            if request.is_symlink() or request.stat().st_size > 16384:
                raise ValueError("control request size/retry budget exceeded")
            packet = json.loads(request.read_text(encoding="utf-8"))
            if request.name != str(uuid.UUID(packet["id"])) + ".control.json":
                raise ValueError("control identity mismatch")
            handling = True
            try:
                result = trainer.control(packet)
            except ValueError as failure:
                result = {"schema": CONTROL_SCHEMA, "id": packet["id"], "ok": False, "error": str(failure)[:160]}
            handling = False
            pending[request] = {"response": result, "published": False}
        saved = pending[request]
        if not saved["published"] and request.exists():
            write_atomic(channel / (request.stem.removesuffix(".control") + ".control-response.json"), saved["response"])
            saved["published"] = True
        request.unlink(missing_ok=True); pending.pop(request, None)
    except FileNotFoundError:
        pending.pop(request, None)
    except PermissionError:
        if handling:
            raise
    except (ValueError, KeyError, TypeError):
        # Invalid controls never select another model or affect world actions.
        try:
            request.unlink(missing_ok=True); pending.pop(request, None)
        except PermissionError:
            pending[request] = {"response": None, "published": True}


def serve(channel: Path, trainer: PaperTrainer, stop_file: Path | None = None, max_decisions: int | None = None):
    if max_decisions is not None and (type(max_decisions) is not int or max_decisions < 1):
        raise ValueError("max-decisions must be positive")
    channel.mkdir(parents=True, exist_ok=True)
    pending = {}
    controls = {}
    max_decisions = max_decisions or max(1000, trainer.maximum * 16)
    try:
        while trainer.steps < trainer.maximum or trainer.training is not None:
            if trainer.decisions >= max_decisions:
                break
            if stop_file is not None and stop_file.is_file():
                break
            trainer.poll_training()
            for disappeared in [path for path in controls if not path.exists()]:
                controls.pop(disappeared, None)
            for request in sorted(channel.glob("*.control.json")):
                exchange_control(request, channel, trainer, controls)
            for disappeared in [path for path in pending if not path.exists()]:
                pending.pop(disappeared, None)
            for request in sorted(channel.glob("*.request.json")):
                exchange_request(request, channel, trainer, pending)
                if trainer.steps >= trainer.maximum:
                    break
            time.sleep(0.01)
    finally:
        trainer.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--channel", type=Path, required=True, help="dedicated plugin development bridge directory")
    parser.add_argument("--output", type=Path, required=True, help="new private training-run directory")
    parser.add_argument("--max-transitions", type=int, required=True, help="explicit finite training budget")
    parser.add_argument("--rollout-size", type=int, default=512)
    parser.add_argument("--max-megabytes", type=int, default=512)
    parser.add_argument("--seed", type=int, default=777)
    parser.add_argument("--resume", type=Path, help="verified optimizer checkpoint; never a Teacher/BC or deployment actor")
    parser.add_argument("--upgrade-context", action="store_true", help="explicit v1->v2 zero-influence context expansion while resuming")
    parser.add_argument("--stop-file", type=Path, help="explicit cooperative shutdown request; incomplete buffers stay raw")
    parser.add_argument("--start-state", choices=("fresh", "pretrained", "resume"), help="weight source, independent of learning")
    parser.add_argument("--inference-only", action="store_true", help="same actor/critic, fixed weights; never retroactively trains these transitions")
    parser.add_argument("--max-decisions", type=int, help="finite sampling budget, including inference without updates")
    parser.add_argument("--restore-session", type=Path, help="restore exact saved brain/learning flag/RNG; invalid state never initializes a new model")
    parser.add_argument("--session-save-seconds", type=int, default=30)
    parser.add_argument("--catalog", type=Path, help="private named model catalog; read-only during this run")
    parser.add_argument("--restore-model", help="restore a named model, or the catalog selection when omitted")
    parser.add_argument("--model-catalog", type=Path, help="explicit private catalog for connected in-game list/info/save")
    args = parser.parse_args()
    if args.max_decisions is not None and args.max_decisions < 1:
        raise ValueError("max-decisions must be positive")
    restored = None
    if args.restore_model and not args.catalog:
        raise ValueError("restore-model requires a catalog")
    if args.catalog:
        if args.restore_session or args.resume or args.start_state not in (None, "resume"):
            raise ValueError("catalog restore cannot be combined with another weight selection")
        catalog = model_catalog.ModelCatalog(args.catalog)
        revision = catalog.load(args.restore_model) if args.restore_model else catalog.selected()
        restored = dict(revision["session"], modelName=revision["name"])
        args.resume = Path(restored["checkpoint"])
        args.start_state = "resume"
        args.upgrade_context = restored.get("contextUpgrade", False)
    if args.restore_session:
        if args.resume is not None or args.start_state not in (None, "resume"):
            raise ValueError("session restore cannot be combined with another weight selection")
        restored = brain_session.load(args.restore_session)
        args.resume = Path(restored["checkpoint"])
        args.start_state = "resume"
        args.upgrade_context = restored.get("contextUpgrade", False)
    trainer = PaperTrainer(args.output, seed=args.seed, rollout_size=args.rollout_size,
                           maximum=args.max_transitions, max_bytes=args.max_megabytes * 1024 * 1024,
                           resume=args.resume, upgrade_context=args.upgrade_context,
                           start_state=args.start_state,
                           learning_enabled=False if args.inference_only else restored["learningEnabled"] if restored else True,
                           restore_state=restored, session_save_seconds=args.session_save_seconds,
                           model_catalog_path=args.model_catalog)
    print("Waiting for real Paper transitions. No Teacher or synthetic game environment. Run:", trainer.run, flush=True)
    serve(args.channel, BrainRuntime(trainer), args.stop_file, args.max_decisions)


if __name__ == "__main__":
    main()
