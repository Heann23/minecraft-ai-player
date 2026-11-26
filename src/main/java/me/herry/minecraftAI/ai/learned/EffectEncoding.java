package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.observation.SurvivalObservation;

import java.util.List;

/** Stable effect identities, not per-item advice: the policy receives actual strength/time/probability separately. */
public final class EffectEncoding {
    public static final List<String> KEYS = List.of(
            "speed", "slowness", "haste", "mining_fatigue", "strength", "instant_health", "instant_damage",
            "jump_boost", "nausea", "regeneration", "resistance", "fire_resistance", "water_breathing", "invisibility",
            "blindness", "night_vision", "hunger", "weakness", "poison", "wither", "health_boost", "absorption",
            "saturation", "glowing", "levitation", "luck", "unluck", "slow_falling", "conduit_power", "dolphins_grace",
            "bad_omen", "hero_of_the_village", "darkness", "trial_omen", "raid_omen", "wind_charged", "weaving", "oozing",
            "infested", "breath_of_the_nautilus");
    private EffectEncoding() {}

    public static void encode(double[] features, List<SurvivalObservation.Effect> effects) {
        if (features.length != PolicyFrame.ACTION_SIZE) throw new IllegalArgumentException("effect input shape mismatch");
        for (var effect : effects) {
            int index = effect.key().startsWith("minecraft:") ? KEYS.indexOf(effect.key().substring(10)) : -1;
            if (index < 0) { features[62] = 1; continue; }
            double sign = effect.category().equals("HARMFUL") ? -1 : 1;
            features[64 + index] += sign * Math.min(16, (effect.amplifier() + 1) / 8.0);
            features[128 + index] = effect.infinite() ? 16 : Math.clamp(effect.durationTicks() / 1200.0, 0, 16);
            features[192 + index] = effect.probability();
        }
        for (int i = 64; i < features.length; i++) features[i] = Math.clamp(features[i], -16, 16);
    }
}
