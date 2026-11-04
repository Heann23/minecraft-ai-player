package me.herry.minecraftAI.ai.learned;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand;
import static org.junit.jupiter.api.Assertions.*;

class PolicyBlockEvidenceTest {
    @Test void commitmentRetainsTheOriginalObservationWithoutChangingTheFeatureContract() {
        UUID world = UUID.randomUUID();
        var evidence = new PolicyFrame.BlockEvidence("OAK_LOG", world, 1, 64, 2, 10);
        var option = new PolicyFrame.Option("prepared", new double[PolicyFrame.ACTION_SIZE], evidence);
        var candidate = new PolicyCandidates.Candidate("prepared", new PrimitiveCommand.Wait(5), null, null, option);
        var committed = candidate.committed(80);
        assertEquals(evidence, committed.encoded().blockEvidence());
        assertEquals(10, committed.encoded().blockEvidence().observedTick());
        assertEquals(256, committed.encoded().features().length);
        assertEquals(80 / 600.0, committed.encoded().features()[30]);
        assertNull(new PolicyFrame.Option("legacy", new double[PolicyFrame.ACTION_SIZE]).blockEvidence());
        assertThrows(IllegalArgumentException.class,
                () -> new PolicyFrame.BlockEvidence("Teacher says mine", world, 1, 64, 2, 10));
    }
}
