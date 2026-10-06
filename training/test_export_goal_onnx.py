"""Export boundaries without optional ONNX dependencies; run in required CI."""
import copy
import hashlib
import json
import unittest

from export_goal_onnx import validate_candidate
from goal_features import contract_hash


class GoalONNXBoundariesTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {"formatVersion": 1, "observationSchema": 1, "role": "goal_selection",
                         "goals": ["IDLE", "FIND_WOOD"], "size": 1,
                         "features": [{"name": "example"}], "goalSourceSHA256": "a" * 64}
        self.model = {"formatVersion": 1, "role": "goal_selection", "featureContractSHA256": contract_hash(self.manifest),
                      "goalSourceSHA256": "a" * 64, "goals": ["IDLE", "FIND_WOOD"],
                      "recipe": {"model": "linear softmax"}, "supportedGoalIds": [1],
                      "weights": [[0.0], [1.0]], "biases": [0.0, 0.5]}

    def check(self, model=None, manifest=None, evaluation_changes=None):
        model = model if model is not None else self.model
        manifest = manifest if manifest is not None else self.manifest
        raw = json.dumps(model).encode()
        evaluation = {"candidateSHA256": hashlib.sha256(raw).hexdigest(),
                      "goalSourceSHA256": "a" * 64,
                      "featureContractSHA256": contract_hash(self.manifest), "deployable": False,
                      **(evaluation_changes or {})}
        return validate_candidate(model, manifest, evaluation, raw)

    def test_valid_candidate_preserves_exact_contract(self):
        self.check()

    def test_changed_goal_order_or_feature_contract_is_rejected(self):
        changed = copy.deepcopy(self.manifest)
        changed["goals"].reverse()
        with self.assertRaises(ValueError):
            self.check(manifest=changed)

    def test_tampered_candidate_or_deployment_claim_is_rejected(self):
        for changes in ({"candidateSHA256": "0" * 64}, {"deployable": True}):
            with self.assertRaises(ValueError):
                self.check(evaluation_changes=changes)

    def test_wrong_dimensions_nonfinite_or_duplicate_supported_ids_are_rejected(self):
        for field, bad in (("weights", [[1.0, 2.0], [1.0]]), ("biases", [float("nan"), 1]),
                           ("supportedGoalIds", [1, 1])):
            model = copy.deepcopy(self.model)
            model[field] = bad
            with self.assertRaises(ValueError):
                self.check(model=model)


if __name__ == "__main__":
    unittest.main()
