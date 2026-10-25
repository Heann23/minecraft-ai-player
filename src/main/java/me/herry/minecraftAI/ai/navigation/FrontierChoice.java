package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Ranks already observed safe endpoints; it never invents a path or treats unseen terrain as traversable. */
public final class FrontierChoice {
    public record Candidate(BlockPoint target, boolean exploredChunk, int unexploredNeighbors,
                            int recentVisits, boolean failedTarget, int nearbyFailures,
                            double distance, double headingAlignment) {
        public Candidate {
            Objects.requireNonNull(target, "target");
            if (unexploredNeighbors < 0 || unexploredNeighbors > 8 || recentVisits < 0 || nearbyFailures < 0
                    || !Double.isFinite(distance) || distance < 0 || !Double.isFinite(headingAlignment)
                    || headingAlignment < -1 || headingAlignment > 1) throw new IllegalArgumentException("invalid frontier candidate");
        }
    }

    private FrontierChoice() {}

    public static Optional<Candidate> select(List<Candidate> candidates) {
        return candidates.stream().filter(candidate -> !candidate.failedTarget()).max(Comparator
                .comparingDouble(FrontierChoice::score)
                .thenComparingDouble(Candidate::distance));
    }

    private static double score(Candidate candidate) {
        return (candidate.exploredChunk() ? 0 : 120)
                + candidate.unexploredNeighbors() * 6.0
                - Math.min(12, candidate.recentVisits()) * 8.0
                - Math.min(8, candidate.nearbyFailures()) * 12.0
                - candidate.distance() * 0.1
                + candidate.headingAlignment() * 4.0;
    }

    /** Failed navigation is partial exploration only if it actually reached an initially unvisited target chunk. */
    public static boolean reachedNewFrontier(Candidate selected, BlockPoint start, BlockPoint current) {
        return !selected.exploredChunk() && start.distanceSq(current) >= 16.0
                && !sameChunk(start, current) && sameChunk(selected.target(), current);
    }

    private static boolean sameChunk(BlockPoint first, BlockPoint second) {
        return (first.x() >> 4) == (second.x() >> 4) && (first.z() >> 4) == (second.z() >> 4);
    }
}
