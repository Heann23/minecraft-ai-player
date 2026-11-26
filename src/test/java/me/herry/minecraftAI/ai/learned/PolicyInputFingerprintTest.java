package me.herry.minecraftAI.ai.learned;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PolicyInputFingerprintTest {
    private PolicyFrame frame(double[] state, List<PolicyFrame.Option> options) {
        return new PolicyFrame(UUID.randomUUID(), 10, state, options, false);
    }
    @Test void objectiveOnlyDifferenceDoesNotHideOtherStateChanges() {
        double[] state = new double[PolicyFrame.STATE_SIZE];
        var options = List.of(new PolicyFrame.Option("choice", new double[PolicyFrame.ACTION_SIZE]));
        var before = PolicyInputFingerprint.of(frame(state, options));
        state[216] = 1;
        var goal = PolicyInputFingerprint.of(frame(state, options));
        assertNotEquals(before.state(), goal.state());
        assertEquals(before.stateOutsideObjective(), goal.stateOutsideObjective());
        state[403] = .4;
        assertNotEquals(goal.stateOutsideObjective(), PolicyInputFingerprint.of(frame(state, options)).stateOutsideObjective());
    }
    @Test void groupingAndOrderAffectIdentityButNonNeuralWorldMetadataDoesNot() {
        double[] state = new double[PolicyFrame.STATE_SIZE], features = new double[PolicyFrame.ACTION_SIZE];
        var first = new PolicyFrame.Option("a", features, null, "same");
        var second = new PolicyFrame.Option("b", features, null, "other");
        var original = PolicyInputFingerprint.of(frame(state, List.of(first, second)));
        assertEquals(original, PolicyInputFingerprint.of(frame(state, List.of(first, second))));
        assertNotEquals(original.orderedCandidates(), PolicyInputFingerprint.of(frame(state, List.of(second, first))).orderedCandidates());
        var changedGroup = new PolicyFrame.Option("a", features, null, "other");
        assertNotEquals(original.orderedCandidates(), PolicyInputFingerprint.of(frame(state, List.of(changedGroup, second))).orderedCandidates());
    }
}
