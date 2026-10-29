"""Shared candidate actor/critic and PPO. No Teacher labels, goal IDs or hand-written action scores."""
from __future__ import annotations

import math
import copy
import numpy as np

CONTRACT = "minecraft-primitive-candidates/v6"
STATE_SIZE, ACTION_SIZE, MAX_OPTIONS = 400, 256, 128
INPUT_SIZE = STATE_SIZE + ACTION_SIZE


def inputs(frame: dict) -> tuple[np.ndarray, np.ndarray]:
    state = np.asarray(frame["state"], dtype=np.float64)
    options = frame["options"]
    if state.shape != (STATE_SIZE,) or not 1 <= len(options) <= MAX_OPTIONS:
        raise ValueError("frame shape mismatch")
    identities = [entry["id"] for entry in options]
    if len(set(identities)) != len(identities):
        raise ValueError("duplicate candidate")
    actions = np.asarray([entry["features"] for entry in options], dtype=np.float64)
    if actions.shape != (len(options), ACTION_SIZE):
        raise ValueError("candidate feature shape mismatch")
    if not np.isfinite(state).all() or not np.isfinite(actions).all() or np.abs(state).max() > 16 or np.abs(actions).max() > 16:
        raise ValueError("invalid features")
    return state, np.concatenate((np.broadcast_to(state, (len(options), STATE_SIZE)), actions), axis=1)


class ActorCritic:
    def __init__(self, seed: int, hidden: int = 64):
        if not 1 <= hidden <= 256:
            raise ValueError("hidden size must be 1..256")
        self.rng = np.random.default_rng(seed)
        self.params = {
            "actor_w": self.rng.normal(0, 1 / math.sqrt(INPUT_SIZE), (hidden, INPUT_SIZE)),
            "actor_b": np.zeros(hidden),
            "actor_out": self.rng.normal(0, 0.01, hidden),
            "actor_bias": np.zeros(1),
            "value_w": self.rng.normal(0, 1 / math.sqrt(STATE_SIZE), (hidden, STATE_SIZE)),
            "value_b": np.zeros(hidden),
            "value_out": self.rng.normal(0, 1 / math.sqrt(hidden), hidden),
            "value_bias": np.zeros(1),
        }
        self.first = {key: np.zeros_like(value) for key, value in self.params.items()}
        self.second = {key: np.zeros_like(value) for key, value in self.params.items()}
        self.updates = 0
        self.version = 0

    def evaluate(self, frame: dict):
        state, x = inputs(frame)
        p = self.params
        hidden = np.tanh(x @ p["actor_w"].T + p["actor_b"])
        logits = hidden @ p["actor_out"] + p["actor_bias"][0]
        logits -= logits.max()
        probabilities = np.exp(logits)
        probabilities /= probabilities.sum()
        value, value_hidden = self.value(state)
        return probabilities, value, (state, x, hidden, value_hidden)

    def value(self, state):
        state = np.asarray(state, dtype=np.float64)
        if state.shape != (STATE_SIZE,) or not np.isfinite(state).all() or np.abs(state).max() > 16:
            raise ValueError("invalid actual next-state value input")
        hidden = np.tanh(self.params["value_w"] @ state + self.params["value_b"])
        return float(self.params["value_out"] @ hidden + self.params["value_bias"][0]), hidden

    def sample(self, frame: dict) -> dict:
        probabilities, value, _ = self.evaluate(frame)
        index = int(self.rng.choice(len(probabilities), p=probabilities))
        return {"index": index, "candidate": frame["options"][index]["id"],
                "logProbability": math.log(max(1e-300, float(probabilities[index]))),
                "value": value, "modelVersion": self.version}

    def gradients(self, rows: list[dict], advantages: np.ndarray, returns: np.ndarray,
                  clip: float = 0.2, entropy_weight: float = 0.01, value_weight: float = 0.5):
        if len(rows) != len(advantages) or len(rows) != len(returns) or not rows:
            raise ValueError("minibatch mismatch")
        gradients = {key: np.zeros_like(value) for key, value in self.params.items()}
        loss = entropy_sum = 0.0
        for row, advantage, target in zip(rows, advantages, returns):
            probabilities, value, (state, x, hidden, value_hidden) = self.evaluate(row["frame"])
            chosen = row["index"]
            if not 0 <= chosen < len(probabilities):
                raise ValueError("chosen index outside captured candidate set")
            logs = np.log(np.maximum(probabilities, 1e-300))
            entropy = -float(probabilities @ logs)
            ratio = math.exp(float(np.clip(logs[chosen] - row["logProbability"], -60, 60)))
            unclipped = ratio * advantage
            clipped = np.clip(ratio, 1 - clip, 1 + clip) * advantage
            loss += -min(unclipped, clipped) + value_weight * 0.5 * (value - target) ** 2 - entropy_weight * entropy
            entropy_sum += entropy
            coefficient = -advantage * ratio if (advantage >= 0 and ratio <= 1 + clip) or (advantage < 0 and ratio >= 1 - clip) else 0
            scores_gradient = -coefficient * probabilities
            scores_gradient[chosen] += coefficient
            scores_gradient += entropy_weight * probabilities * (logs + entropy)
            gradients["actor_out"] += hidden.T @ scores_gradient
            gradients["actor_bias"][0] += scores_gradient.sum()
            hidden_gradient = scores_gradient[:, None] * self.params["actor_out"][None, :] * (1 - hidden ** 2)
            gradients["actor_w"] += hidden_gradient.T @ x
            gradients["actor_b"] += hidden_gradient.sum(axis=0)
            value_gradient = value_weight * (value - target)
            gradients["value_out"] += value_gradient * value_hidden
            gradients["value_bias"][0] += value_gradient
            vh_gradient = value_gradient * self.params["value_out"] * (1 - value_hidden ** 2)
            gradients["value_w"] += np.outer(vh_gradient, state)
            gradients["value_b"] += vh_gradient
        for key in gradients:
            gradients[key] /= len(rows)
        return gradients, {"loss": float(loss / len(rows)), "entropy": float(entropy_sum / len(rows))}

    def apply(self, gradients: dict, rate: float = 3e-4, max_norm: float = 0.5):
        if not all(np.isfinite(g).all() for g in gradients.values()):
            raise ValueError("non-finite PPO gradient")
        norm = math.sqrt(sum(float(np.sum(g * g)) for g in gradients.values()))
        scale = min(1, max_norm / max(1e-12, norm))
        self.updates += 1
        for key, gradient in gradients.items():
            gradient = gradient * scale
            self.first[key] = 0.9 * self.first[key] + 0.1 * gradient
            self.second[key] = 0.999 * self.second[key] + 0.001 * gradient * gradient
            first = self.first[key] / (1 - 0.9 ** self.updates)
            second = self.second[key] / (1 - 0.999 ** self.updates)
            self.params[key] -= rate * first / (np.sqrt(second) + 1e-8)
        if not all(np.isfinite(value).all() for value in self.params.values()):
            raise ValueError("non-finite updated policy")

    def fit(self, rows: list[dict], epochs: int = 4, minibatch: int = 32):
        if not rows or any(row["modelVersion"] != self.version for row in rows):
            raise ValueError("PPO requires transitions from the current behavior-policy version")
        advantages, returns = generalized_advantages(rows)
        if len(rows) > 1:
            advantages = (advantages - advantages.mean()) / max(1e-8, float(advantages.std()))
        stats = []
        for _ in range(epochs):
            order = self.rng.permutation(len(rows))
            for start in range(0, len(rows), minibatch):
                indices = order[start:start + minibatch]
                gradient, report = self.gradients([rows[i] for i in indices], advantages[indices], returns[indices])
                self.apply(gradient)
                stats.append(report)
        self.version += 1
        return {key: float(np.mean([report[key] for report in stats])) for key in ("loss", "entropy")}

    def optimizer_state(self, *, allow_initial=False) -> dict:
        """Development checkpoint, including critic/Adam; the deployment actor alone cannot resume PPO."""
        if (self.updates <= 0 or self.version <= 0) and not (allow_initial and self.updates == 0 and self.version == 0):
            raise ValueError("initialization is not a trained checkpoint")
        return {"schema": "minecraft-ppo-optimizer/v1", "contract": CONTRACT,
                "updates": self.updates, "version": self.version,
                "params": {key: value.tolist() for key, value in self.params.items()},
                "first": {key: value.tolist() for key, value in self.first.items()},
                "second": {key: value.tolist() for key, value in self.second.items()},
                "rng": self.rng.bit_generator.state}

    @classmethod
    def upgrade_legacy_context(cls, state: dict):
        """Explicit legacy->v6 migration. New context has zero influence; no invented learning."""
        old_size = {"minecraft-primitive-candidates/v1": 64, "minecraft-primitive-candidates/v2": 128,
                    "minecraft-primitive-candidates/v3": 192, "minecraft-primitive-candidates/v4": 208, "minecraft-primitive-candidates/v5": 240}.get(state.get("contract"))
        if state.get("schema") != "minecraft-ppo-optimizer/v1" or old_size is None:
            raise ValueError("context upgrade requires a v1, v2, v3, v4 or v5 optimizer checkpoint")
        upgraded = copy.deepcopy(state)
        for group in ("params", "first", "second"):
            for key, old_columns in (("actor_w", old_size + ACTION_SIZE), ("value_w", old_size)):
                value = np.asarray(state[group][key], dtype=np.float64)
                if value.ndim != 2 or value.shape[1] != old_columns:
                    raise ValueError("invalid legacy context tensor")
                zeros = np.zeros((value.shape[0], STATE_SIZE - old_size))
                upgraded[group][key] = np.concatenate((value[:, :old_size], zeros, value[:, old_size:]), axis=1).tolist()
        upgraded["contract"] = CONTRACT
        return cls.from_optimizer_state(upgraded)

    @classmethod
    def from_optimizer_state(cls, state: dict, *, allow_initial=False):
        if set(state) != {"schema", "contract", "updates", "version", "params", "first", "second", "rng"} \
                or state["schema"] != "minecraft-ppo-optimizer/v1" or state["contract"] != CONTRACT:
            raise ValueError("incompatible optimizer checkpoint")
        for name in ("updates", "version"):
            if type(state[name]) is not int or not (0 if allow_initial else 1) <= state[name] <= 10**12:
                raise ValueError("invalid trained optimizer counters")
        if (state["updates"] == 0) != (state["version"] == 0):
            raise ValueError("initialization counters must both be zero")
        hidden = len(state["params"]["actor_b"])
        model = cls(0, hidden)
        for group in ("params", "first", "second"):
            if set(state[group]) != set(model.params):
                raise ValueError("optimizer parameter names mismatch")
            target = getattr(model, group)
            for name, template in model.params.items():
                value = np.asarray(state[group][name], dtype=np.float64)
                if value.shape != template.shape or not np.isfinite(value).all() or np.abs(value).max() > 1e6:
                    raise ValueError("invalid optimizer tensor")
                if group == "second" and (value < 0).any():
                    raise ValueError("negative Adam second moment")
                target[name] = value.copy()
        model.updates, model.version = state["updates"], state["version"]
        model.rng.bit_generator.state = state["rng"]
        return model

    def artifact(self, *, environment_steps: int, training_run: str, rollout_sha256: str) -> dict:
        if environment_steps <= 0 or self.updates == 0:
            raise ValueError("initialization is not a trained model")
        p = self.params
        return {"schema": "minecraft-rl-actor/v1", "contract": CONTRACT, "algorithm": "ppo",
                "environment": "minecraft-paper", "environmentSteps": environment_steps,
                "trainingRun": training_run, "rolloutSha256": rollout_sha256,
                "hiddenWeights": p["actor_w"].tolist(), "hiddenBias": p["actor_b"].tolist(),
                "outputWeights": p["actor_out"].tolist(), "outputBias": float(p["actor_bias"][0])}

    def initial_artifact(self, *, brain_id: str, training_run: str) -> dict:
        if self.updates != 0 or self.version != 0:
            raise ValueError("only an actual untrained initial state may use the initial-actor schema")
        p = self.params
        return {"schema": "minecraft-rl-initial-actor/v1", "contract": CONTRACT, "initializationOnly": True,
                "brainId": brain_id, "trainingRun": training_run, "environmentSteps": 0,
                "hiddenWeights": p["actor_w"].tolist(), "hiddenBias": p["actor_b"].tolist(),
                "outputWeights": p["actor_out"].tolist(), "outputBias": float(p["actor_bias"][0])}


def generalized_advantages(rows: list[dict], gamma_tick: float = 0.99997, gae_lambda: float = 0.95):
    """SMDP discount uses actual elapsed server ticks; gaps/episodes never share an advantage tail."""
    advantages = np.zeros(len(rows), dtype=np.float64)
    tails = {}
    for index in reversed(range(len(rows))):
        row = rows[index]
        ticks = row["elapsedTicks"]
        if not isinstance(ticks, int) or not 0 <= ticks <= 2400:
            raise ValueError("invalid actual elapsed ticks")
        discount = gamma_tick ** ticks
        terminal = row["terminated"]
        next_value = 0 if terminal else row["nextValue"]
        next_sequence, following_advantage = tails.get(row["session"], (-1, 0.0))
        contiguous = next_sequence == row["sequence"] + 1
        delta = row["reward"] + discount * next_value - row["value"]
        carry = delta + (discount * gae_lambda * following_advantage if contiguous and not terminal and not row.get("truncated", False) else 0)
        advantages[index] = carry
        tails[row["session"]] = (row["sequence"], carry)
    returns = advantages + np.asarray([row["value"] for row in rows])
    if not np.isfinite(advantages).all() or not np.isfinite(returns).all():
        raise ValueError("non-finite rollout returns")
    return advantages, returns
