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
from pathlib import Path
import time
import uuid

# Keep game sampling responsive while a background PPO update uses a separate parameter snapshot.
for variable in ("OPENBLAS_NUM_THREADS", "OMP_NUM_THREADS", "MKL_NUM_THREADS"):
    os.environ.setdefault(variable, "1")

from rl_actor import ActorCritic, CONTRACT, inputs

SCHEMA = "minecraft-paper-ppo/v1"
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
    if len(row["nextState"]) != 64 or any(not isinstance(x, (int, float)) or not math.isfinite(x) or abs(x) > 16 for x in row["nextState"]):
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


class PaperTrainer:
    def __init__(self, output: Path, *, seed: int, rollout_size: int, maximum: int, max_bytes: int):
        if not 32 <= rollout_size <= 4096 or maximum < rollout_size or max_bytes < MAX_PACKET:
            raise ValueError("invalid training budget")
        output.mkdir(parents=True, exist_ok=False)  # A new run never overwrites another model or provenance.
        self.output = output
        self.run = str(uuid.uuid4())
        self.model = ActorCritic(seed)
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
        write_atomic(output / "run.json", {"schema": SCHEMA, "contract": CONTRACT, "algorithm": "ppo",
                     "environment": "minecraft-paper", "run": self.run, "seed": seed,
                     "maxTransitions": maximum, "rolloutSize": rollout_size, "teacherLabels": False,
                     "initialWeights": "random", "gammaPerServerTick": 0.99997, "gaeLambda": 0.95,
                     "ppoClip": 0.2, "epochsPerUpdate": 4, "trained": False, "evaluationComplete": False})

    def handle(self, packet: dict) -> dict:
        self.poll_training()
        if packet.get("schema") != SCHEMA or packet.get("environment") != "minecraft-paper":
            raise ValueError("only actual Paper bridge schema accepted")
        if set(packet) - {"schema", "environment", "id", "frame", "transitions"}:
            raise ValueError("unexpected query data")
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
                if previous["modelVersion"] != self.model.version:
                    self.discarded_off_policy += 1
                elif self.training is not None:
                    self.discarded_during_update += 1
                elif self.steps < self.maximum:
                    next_value, _ = self.model.value(previous["nextState"])
                    row = dict(previous)
                    # A cross-world boundary is not bootstrapped from an unrelated world's body state.
                    row["nextValue"] = 0 if row["terminated"] or row["nextWorld"] != row["frame"]["world"] else next_value
                    encoded = (json.dumps(row, ensure_ascii=False, allow_nan=False, separators=(",", ":")) + "\n").encode()
                    if self.bytes + len(encoded) > self.max_bytes:
                        raise RuntimeError("raw Paper rollout byte budget exhausted")
                    self.raw.write(encoded); self.raw.flush(); self.digest.update(encoded); self.bytes += len(encoded)
                    self.rows.append(row); self.steps += 1; self.ticks += row["elapsedTicks"]
                    if len(self.rows) >= self.rollout_size or self.steps == self.maximum:
                        rows = self.rows
                        self.rows = []
                        self.training_metadata = {"steps": self.steps, "ticks": self.ticks, "bytes": self.bytes,
                                                  "sha256": self.digest.hexdigest()}
                        self.training = self.worker.submit(self.fit_snapshot, copy.deepcopy(self.model), rows)
        result = self.model.sample(frame)
        return {"schema": SCHEMA, "id": packet["id"], **result, "environmentSteps": self.steps}

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
        artifact = self.model.artifact(environment_steps=metadata["steps"], training_run=self.run, rollout_sha256=metadata["sha256"])
        path = self.output / "minecraft-rl-actor.json"
        write_atomic(path, artifact)
        write_atomic(self.output / "training-report.json", {"run": self.run, "environment": "minecraft-paper",
                     "environmentSteps": metadata["steps"], "actualServerTicks": metadata["ticks"], "gradientUpdates": self.model.updates,
                     "modelVersion": self.model.version, "rolloutSha256": metadata["sha256"], "rolloutBytesUsed": metadata["bytes"],
                     "actorSha256": hashlib.sha256(path.read_bytes()).hexdigest(), "discardedOffPolicy": self.discarded_off_policy,
                     "discardedDuringUpdate": self.discarded_during_update,
                     "teacherLabels": False, "initializationOnly": False, "evaluationComplete": False,
                     "allWorldAutonomyProven": False, "trainingMetrics": stats})

    def close(self):
        # An incomplete on-policy buffer is preserved as raw evidence, never relabeled as a trained update.
        try:
            self.poll_training(wait=True)
        finally:
            self.worker.shutdown(wait=True)
            self.raw.close()


def serve(channel: Path, trainer: PaperTrainer):
    channel.mkdir(parents=True, exist_ok=True)
    try:
        while trainer.steps < trainer.maximum or trainer.training is not None:
            trainer.poll_training()
            for request in sorted(channel.glob("*.request.json")):
                try:
                    if request.is_symlink() or request.stat().st_size > MAX_PACKET:
                        raise ValueError("invalid request file")
                    packet = json.loads(request.read_text(encoding="utf-8"))
                    if request.name != str(uuid.UUID(packet["id"])) + ".request.json":
                        raise ValueError("request identity mismatch")
                    result = trainer.handle(packet)
                    # The server thread validates the same identity, candidate set, generation and expiry again.
                    if request.exists():
                        write_atomic(channel / (packet["id"] + ".response.json"), result)
                    request.unlink(missing_ok=True)
                except FileNotFoundError:
                    pass  # Java's bounded request expired; it must not be restarted or executed by Python.
                except (ValueError, KeyError, TypeError) as error:
                    print("Rejected Paper packet:", type(error).__name__, str(error)[:160], flush=True)
                    request.unlink(missing_ok=True)
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
    args = parser.parse_args()
    trainer = PaperTrainer(args.output, seed=args.seed, rollout_size=args.rollout_size,
                           maximum=args.max_transitions, max_bytes=args.max_megabytes * 1024 * 1024)
    print("Waiting for real Paper transitions. No Teacher or synthetic game environment. Run:", trainer.run, flush=True)
    serve(args.channel, trainer)


if __name__ == "__main__":
    main()
