"""Frozen Observation v1 input contract for an offline Goal selection model."""
from __future__ import annotations

import hashlib
import json
import math

NUMBERS = {
    "player": {"health": 40, "maxHealth": 40, "food": 20, "saturation": 20, "armor": 20},
    "inventory": {"emptySlots": 36, "junkSlots": 36, "plankEquivalent": 64,
                  "stone": 64, "coal": 64, "food": 32, "rawFood": 32},
    "environment": {"lightLevel": 15, "hostileCount": 16, "animalCount": 16, "dropCount": 16},
    "memory": {"recentFailures": 16},
    "task": {"restCount": 16},
}
BOOLS = {
    "player": "hungry grounded inWater inLava onFire standingInDanger suffocating drowning".split(),
    "inventory": ["hasFuel"],
    "environment": "night storm thundering underground sealedIn lavaNearby cliffAhead hostileNearby allyNeedsHelp".split(),
    "progress": "tableAvailable furnaceAvailable shelterBuilt netherPortalBuilt strongholdFound endPortalReady dragonDefeated".split(),
    "memory": "homeKnown insideHome homeSheltered chestAvailable canSleep knowsTree knowsCoal knowsIron knowsDiamond knowsLootChest furnaceBusy".split(),
    "task": ["escaping"],
}
TIERS = "NONE WOOD GOLD STONE COPPER IRON DIAMOND NETHERITE".split()
CATEGORIES = {
    "player.dimension": "OVERWORLD NETHER THE_END CUSTOM".split(),
    "environment.combatDecision": "NONE FIGHT FLEE".split(),
    "progress.need": "NONE WOOD STONE IRON DIAMOND FLINT OTHER".split(),
    "progress.stage": "EARLY_SURVIVAL IRON_AGE DIAMOND_AGE NETHER_ENTRY NETHER ENDER_PEARLS EYES_OF_ENDER STRONGHOLD END_PREPARATION DRAGON_FIGHT CLEARED".split(),
    **{f"inventory.{name}Tier": TIERS for name in ("pickaxe", "axe", "sword")},
}
ITEMS = "RAW_IRON IRON_INGOT DIAMOND STICK CRAFTING_TABLE FURNACE WOODEN_PICKAXE STONE_PICKAXE COPPER_PICKAXE IRON_PICKAXE DIAMOND_PICKAXE NETHERITE_PICKAXE IRON_SWORD SHIELD BUCKET WATER_BUCKET OBSIDIAN FLINT FLINT_AND_STEEL TORCH".split()


def number(value: object, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f"{name}: finite numeric value required")
    return float(value)


def boolean(value: object, name: str) -> float:
    if not isinstance(value, bool):
        raise ValueError(f"{name}: boolean required")
    return float(value)


def contract(goal_ids: dict) -> dict:
    goals = goal_ids.get("goals")
    source = goal_ids.get("sourceSHA256")
    if (goal_ids.get("formatVersion") != 1 or not isinstance(goals, list) or not goals
            or any(not isinstance(goal, str) or not goal for goal in goals)
            or len(set(goals)) != len(goals)
            or not isinstance(source, str) or len(source) != 64
            or any(char not in "0123456789abcdef" for char in source)):
        raise ValueError("invalid Goal ID contract")
    categories = {**CATEGORIES, "task.goal": goals}
    features = []
    for section, fields in NUMBERS.items():
        features.extend({"name": f"{section}.{key}", "kind": "scaled", "scale": scale}
                        for key, scale in fields.items())
    features.extend({"name": name, "kind": "ratio"} for name in ("player.healthRatio", "player.airRatio"))
    for section, fields in BOOLS.items():
        features.extend({"name": f"{section}.{key}", "kind": "boolean"} for key in fields)
    features.append({"name": "memory.homeDistance", "kind": "masked_scaled", "scale": 256})
    for field, values in categories.items():
        features.extend({"name": f"{field}={value}", "kind": "one_hot"} for value in values)
    features.extend({"name": f"inventory.itemCounts.{item}", "kind": "optional_count", "scale": 64}
                    for item in ITEMS)
    return {"formatVersion": 1, "observationSchema": 1, "role": "goal_selection",
            "goalSourceSHA256": source, "goals": goals, "categories": categories,
            "features": features, "size": len(features),
            "normalization": "fixed constants; clip scaled values and ratios to [0,1]; no dataset fitting",
            "missing": "item count key absent: zero; unknown home: distance zero with homeKnown false; all other selected fields required",
            "excluded": ["absolute coordinates", "tick/time", "AI identity", "seed", "Teacher scores",
                         "future decisions/outcomes", "current decision plan", "free text", "biome", "entity details", "equipment/effects"]}


def contract_hash(manifest: dict) -> str:
    return hashlib.sha256(json.dumps(manifest, sort_keys=True, separators=(",", ":"),
                                    allow_nan=False).encode()).hexdigest()


def encode(observation: dict, manifest: dict) -> list[float]:
    if not isinstance(observation, dict) or type(observation.get("schemaVersion")) is not int or observation["schemaVersion"] != 1:
        raise ValueError("unsupported Observation schema")
    sections = {}
    for section in ("player", "inventory", "environment", "progress", "memory", "task"):
        value = observation.get(section)
        if not isinstance(value, dict):
            raise ValueError(f"{section}: object required")
        sections[section] = value
    values = []
    clip = lambda value: min(1.0, max(0.0, value))
    for section, fields in NUMBERS.items():
        for key, scale in fields.items():
            value = number(sections[section].get(key), f"{section}.{key}")
            if value < 0:
                raise ValueError(f"{section}.{key}: negative value")
            values.append(clip(value / scale))
    player = sections["player"]
    for current, maximum in (("health", "maxHealth"), ("air", "maxAir")):
        denominator = number(player.get(maximum), f"player.{maximum}")
        if denominator <= 0:
            raise ValueError(f"player.{maximum}: must be positive")
        values.append(clip(number(player.get(current), f"player.{current}") / denominator))
    for section, fields in BOOLS.items():
        for key in fields:
            values.append(boolean(sections[section].get(key), f"{section}.{key}"))
    memory = sections["memory"]
    distance = number(memory.get("homeDistance"), "memory.homeDistance") if memory["homeKnown"] else 0
    if distance < 0:
        raise ValueError("memory.homeDistance: negative value")
    values.append(clip(distance / 256))
    for field, categories in manifest["categories"].items():
        section, key = field.split(".")
        value = sections[section].get(key)
        if value not in categories:
            raise ValueError(f"{field}: unsupported category {value!r}")
        values.extend(float(value == category) for category in categories)
    counts = sections["inventory"].get("itemCounts")
    if not isinstance(counts, dict):
        raise ValueError("inventory.itemCounts: object required")
    for key, value in counts.items():
        if not isinstance(key, str) or type(value) is not int or value < 0:
            raise ValueError("inventory.itemCounts: nonnegative integer counts required")
    values.extend(clip(counts.get(item, 0) / 64) for item in ITEMS)
    if len(values) != manifest["size"]:
        raise ValueError("feature contract size mismatch")
    return values
