package me.herry.minecraftAI.ai.observation;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A main-thread snapshot of body, effects and exact item choices. It does not grant action permission or change v1 experience. */
public record SurvivalObservation(int schemaVersion, long aiTick, long serverTick, String worldId, Body body,
                                  List<Effect> activeEffects, List<Item> storage, Map<String, Item> equipment,
                                  ActiveUse activeUse, boolean inventoryComplete) {
    public static final int SCHEMA_VERSION = 1;

    public SurvivalObservation {
        if (schemaVersion != SCHEMA_VERSION || aiTick < 0 || serverTick < 0 || serverTick > 0xffff_ffffL)
            throw new IllegalArgumentException("invalid survival snapshot clock/schema");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(body, "body");
        activeEffects = List.copyOf(activeEffects);
        storage = List.copyOf(storage);
        equipment = Map.copyOf(equipment);
        Objects.requireNonNull(activeUse, "activeUse");
        if (storage.size() > 36 || storage.stream().map(Item::slot).distinct().count() != storage.size()
                || storage.stream().anyMatch(item -> item.slot() < 0 || item.slot() >= 36)
                || inventoryComplete && storage.size() != 36)
            throw new IllegalArgumentException("storage completeness/slot mismatch");
    }

    public enum Eligibility { AVAILABLE, BLOCKED, UNKNOWN, UNSUPPORTED, NOT_CONSUMABLE }

    public record Body(double health, double maxHealth, double absorption, int foodLevel,
                       double saturation, double exhaustion, int remainingAir, int maximumAir) {
        public Body {
            nonnegative(health, "health"); nonnegative(maxHealth, "maxHealth"); nonnegative(absorption, "absorption");
            nonnegative(saturation, "saturation"); nonnegative(exhaustion, "exhaustion");
            if (foodLevel < 0 || foodLevel > 20 || maximumAir < 0) throw new IllegalArgumentException("invalid food/air");
            // Remaining air may be negative during the actual drowning cycle.
        }
    }

    /** Active effects use remaining duration; item effects use their described prospective duration. Amplifiers are zero-based. */
    public record Effect(String key, int amplifier, int durationTicks, boolean infinite, boolean instant,
                         double probability, String category, boolean durationKnown) {
        public Effect {
            Objects.requireNonNull(key, "key"); Objects.requireNonNull(category, "category");
            if (key.isBlank() || amplifier < 0 || !Double.isFinite(probability) || probability < 0 || probability > 1
                    || durationTicks < -1 || infinite && durationTicks != -1)
                throw new IllegalArgumentException("invalid effect description");
        }
    }

    /** slot -1 means an equipment-only view. Empty fingerprint means no exact component identity was captured. */
    public record Item(int slot, String material, int amount, String fingerprint, int damage, int maxDamage,
                       Consumption consumption) {
        public Item {
            Objects.requireNonNull(material, "material"); Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(consumption, "consumption");
            if (slot < -1 || slot >= 36 || amount < 0 || damage < -1 || maxDamage < -1)
                throw new IllegalArgumentException("invalid item state");
        }
    }

    /** useTicks=-1 is UNKNOWN. Effects and side effects describe possibilities, never already applied benefits. */
    public record Consumption(Eligibility eligibility, String animation, int useTicks, boolean safeFood,
                              int nutrition, double saturation, boolean canAlwaysEat, List<Effect> effects,
                              List<String> removedEffects, boolean clearsEffects, boolean randomTeleport,
                              boolean effectsKnown, String remainderMaterial, String reason) {
        public Consumption {
            Objects.requireNonNull(eligibility, "eligibility"); Objects.requireNonNull(animation, "animation");
            Objects.requireNonNull(remainderMaterial, "remainderMaterial"); Objects.requireNonNull(reason, "reason");
            effects = List.copyOf(effects); removedEffects = List.copyOf(removedEffects);
            if (useTicks < -1 || nutrition < 0 || !Double.isFinite(saturation) || saturation < 0)
                throw new IllegalArgumentException("invalid consumption properties");
            if (eligibility == Eligibility.AVAILABLE && (useTicks < 0 || !effectsKnown))
                throw new IllegalArgumentException("unknown timing/effects cannot be available");
        }
    }

    public record ActiveUse(boolean active, String hand, String material, String fingerprint,
                            int usedTicks, int remainingTicks) {
        public ActiveUse {
            Objects.requireNonNull(hand, "hand"); Objects.requireNonNull(material, "material");
            Objects.requireNonNull(fingerprint, "fingerprint");
            if (usedTicks < 0 || remainingTicks < 0) throw new IllegalArgumentException("invalid active use timing");
        }
    }

    private static void nonnegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException(name + " must be finite/nonnegative");
    }
}
