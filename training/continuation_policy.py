"""Versioned temporal choice law, independent of materials, goals and rewards.

Continue competes with the *mean* exponential switch score, then switching uses
the ordinary conditional categorical distribution. This changes the prior and
must be explicitly admitted as a different actor architecture. It is neither
forced continuation nor evidence that continuation has already been learned.
"""
import math
import numpy as np

LEGACY_SELECTION = "continue-switch-mean/v1"
SELECTION = "continue-switch-mean/v2"
SUPPORTED = (LEGACY_SELECTION, SELECTION)

def compatible_indices(frame, selection=SELECTION):
    if frame["state"][400] != 1 or frame["state"][401] != 1:
        return []
    retained = [i for i, option in enumerate(frame["options"]) if option["features"][55] == 1]
    if len(retained) > 1:
        raise ValueError("ambiguous active execution candidate")
    if not retained or len(frame['options']) == 1:
        return []
    compatible = retained
    if selection == SELECTION:
        identities = [o.get("inputId") for o in frame["options"]]
        if any(not isinstance(v, str) or not v or len(v) > 256 for v in identities):
            raise ValueError("temporal v2 requires exact execution identities")
        compatible = [i for i, value in enumerate(identities) if value == identities[retained[0]]]
    elif selection != LEGACY_SELECTION:
        raise ValueError("unsupported temporal selection law")
    return compatible if len(compatible) != len(frame['options']) else []


def scores(frame, logits, selection=SELECTION):
    compatible = compatible_indices(frame, selection)
    if not compatible:
        return logits
    result = np.array(logits, copy=True)
    # Algebraic form of the two-stage law. This is a fixed architecture term,
    # not a resource/action utility. Softmax derivatives remain unchanged.
    result[compatible] += math.log((len(logits) - len(compatible)) / len(compatible))
    return result


def relative_entropy(frame, probabilities, selection=SELECTION):
    """Entropy relative to the versioned neutral continue/switch prior.

    H(p)-sum_g P(g)log(n_g) = log(2)-KL(p || q), q_i=1/(2*n_g).
    Return the score and gradient of its negative w.r.t. categorical logits.
    No material, task, outcome or proposed correct action enters this objective.
    """
    logs = np.log(np.maximum(probabilities, 1e-300))
    entropy = -float(probabilities @ logs)
    sizes = np.zeros(len(probabilities))
    compatible = compatible_indices(frame, selection)
    if compatible:
        sizes.fill(math.log(len(probabilities) - len(compatible)))
        sizes[compatible] = math.log(len(compatible))
    mean_size = float(probabilities @ sizes)
    return entropy - mean_size, probabilities * (logs + entropy + sizes - mean_size)
