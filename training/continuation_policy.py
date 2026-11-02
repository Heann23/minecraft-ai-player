"""Versioned temporal choice law, independent of materials, goals and rewards.

Continue competes with the *mean* exponential switch score, then switching uses
the ordinary conditional categorical distribution. This changes the prior and
must be explicitly admitted as a different actor architecture. It is neither
forced continuation nor evidence that continuation has already been learned.
"""
import math
import numpy as np

SELECTION = "continue-switch-mean/v1"

def scores(frame, logits):
    if frame["state"][400] != 1 or frame["state"][401] != 1:
        return logits
    retained = [i for i, option in enumerate(frame["options"]) if option["features"][55] == 1]
    if len(retained) > 1:
        raise ValueError("ambiguous active execution candidate")
    if not retained or len(logits) == 1:
        return logits
    result = np.array(logits, copy=True)
    # Algebraic form of the two-stage law. This is a fixed architecture term,
    # not a resource/action utility. Softmax derivatives remain unchanged.
    result[retained[0]] += math.log(len(logits) - 1)
    return result
