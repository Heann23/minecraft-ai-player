"""Feature boundary contracts; CI only under the user's local validation policy."""
import copy
import unittest

from goal_features import BOOLS, CATEGORIES, NUMBERS, contract, contract_hash, encode


def observation():
    value = {"schemaVersion": 1, **{name: {} for name in ("player", "inventory", "environment", "progress", "memory", "task")}}
    for section, fields in NUMBERS.items():
        value[section].update({field: 0 for field in fields})
    for section, fields in BOOLS.items():
        value[section].update({field: False for field in fields})
    for name, categories in CATEGORIES.items():
        section, field = name.split(".")
        value[section][field] = categories[0]
    value["player"].update(health=10, maxHealth=20, air=150, maxAir=300)
    value["inventory"]["itemCounts"] = {}
    value["task"]["goal"] = "IDLE"
    return value


class GoalFeaturesTest(unittest.TestCase):
    def setUp(self):
        self.manifest = contract({"formatVersion": 1, "sourceSHA256": "a" * 64, "goals": ["IDLE", "FIND_WOOD"]})

    def test_coordinates_identity_and_future_results_cannot_change_input(self):
        first = observation()
        second = copy.deepcopy(first)
        second.update(tick=99999, worldSeed=12, teacher={"goal": "FIND_WOOD"}, outcome="SUCCEEDED")
        second["player"].update(x=10000, y=-60, z=99, yaw=90, pitch=25, name="private")
        second["task"].update(plan="future label", action="future action", skill="future skill")
        self.assertEqual(encode(first, self.manifest), encode(second, self.manifest))

    def test_missing_required_nonfinite_and_wrong_boolean_are_rejected(self):
        for section, field, bad in (("player", "health", None), ("player", "air", float("nan")),
                                    ("environment", "night", 1), ("memory", "recentFailures", -1)):
            value = observation()
            value[section][field] = bad
            with self.assertRaises(ValueError):
                encode(value, self.manifest)

    def test_unknown_home_is_zero_and_known_home_requires_distance(self):
        value = observation()
        baseline = encode(value, self.manifest)
        value["memory"]["homeDistance"] = None
        self.assertEqual(baseline, encode(value, self.manifest))
        value["memory"]["homeKnown"] = True
        with self.assertRaises(ValueError):
            encode(value, self.manifest)

    def test_counts_default_zero_but_invalid_counts_are_rejected(self):
        value = observation()
        value["inventory"]["itemCounts"] = {"STICK": 128}
        vector = encode(value, self.manifest)
        index = next(index for index, field in enumerate(self.manifest["features"]) if field["name"] == "inventory.itemCounts.STICK")
        self.assertEqual(1, vector[index])
        for count in (-1, 1.5, True):
            value["inventory"]["itemCounts"]["STICK"] = count
            with self.assertRaises(ValueError):
                encode(value, self.manifest)

    def test_schema_and_unknown_category_are_rejected(self):
        value = observation()
        value["schemaVersion"] = 2
        with self.assertRaises(ValueError):
            encode(value, self.manifest)
        value = observation()
        value["inventory"]["pickaxeTier"] = "NEW_TIER"
        with self.assertRaises(ValueError):
            encode(value, self.manifest)

    def test_goal_order_and_source_are_part_of_contract_hash(self):
        changed = contract({"formatVersion": 1, "sourceSHA256": "a" * 64, "goals": ["FIND_WOOD", "IDLE"]})
        self.assertNotEqual(contract_hash(self.manifest), contract_hash(changed))
        changed = copy.deepcopy(self.manifest)
        changed["goalSourceSHA256"] = "b" * 64
        self.assertNotEqual(contract_hash(self.manifest), contract_hash(changed))


if __name__ == "__main__":
    unittest.main()
