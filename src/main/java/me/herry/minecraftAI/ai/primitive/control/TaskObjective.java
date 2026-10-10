package me.herry.minecraftAI.ai.primitive.control;

import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;

import java.util.List;
import java.util.Objects;

/** Actual state objectives are independent of an action's SUCCESS label. */
public sealed interface TaskObjective {
    boolean isAchieved(PrimitiveSnapshot snapshot);

    record AcquireItem(String material, int totalCount) implements TaskObjective {
        public AcquireItem {
            material = ContractValues.material(material);
            ContractValues.range(totalCount, 1, 41 * 1024, "totalCount");
        }
        @Override public boolean isAchieved(PrimitiveSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot");
            return snapshot.alive() && snapshot.itemCount(material) >= totalCount;
        }
    }

    record ReachPoint(PrimitiveTarget.Point target, double radius) implements TaskObjective {
        public ReachPoint {
            target = ContractValues.point(target);
            ContractValues.finiteRange(radius, 0.1, 16, "radius");
        }
        @Override public boolean isAchieved(PrimitiveSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot");
            var pos = snapshot.position();
            double dx = pos.x() - target.x(), dy = pos.y() - target.y(), dz = pos.z() - target.z();
            return snapshot.alive() && dx * dx + dy * dy + dz * dz <= radius * radius;
        }
    }

    record All(List<TaskObjective> objectives) implements TaskObjective {
        public All {
            objectives = List.copyOf(Objects.requireNonNull(objectives, "objectives"));
            ContractValues.range(objectives.size(), 1, PrimitiveProgram.MAX_STEPS, "objective count");
            int nodes = 1;
            for (TaskObjective objective : objectives) nodes = Math.addExact(nodes, nodeCount(objective));
            if (nodes > 64) throw new IllegalArgumentException("objective tree has at most 64 nodes");
        }
        @Override public boolean isAchieved(PrimitiveSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot");
            return objectives.stream().allMatch(objective -> objective.isAchieved(snapshot));
        }
    }

    private static int nodeCount(TaskObjective objective) {
        if (!(objective instanceof All all)) return 1;
        int count = 1;
        for (TaskObjective child : all.objectives()) count = Math.addExact(count, nodeCount(child));
        return count;
    }
}
