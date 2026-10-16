package me.herry.minecraftAI.ai.primitive.control;

import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.util.BlockPoint;

import java.util.Map;
import java.util.Objects;

/** Pure input checks; world and item availability are checked by the runtime adapter. */
final class ContractValues {
    private ContractValues() {}

    static String text(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be nonblank and at most " + maxLength + " characters");
        }
        return value;
    }

    static String material(String value) {
        text(value, "material", 96);
        if (!value.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("material must use its stable uppercase name");
        }
        return value;
    }

    static int range(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be in " + minimum + ".." + maximum);
        }
        return value;
    }

    static double finiteRange(double value, double minimum, double maximum, String name) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be finite and in " + minimum + ".." + maximum);
        }
        return value;
    }

    static PrimitiveTarget.Point point(PrimitiveTarget.Point value) {
        Objects.requireNonNull(value, "point");
        finiteRange(value.x(), -30_000_000, 30_000_000, "x");
        finiteRange(value.y(), -30_000_000, 30_000_000, "y");
        finiteRange(value.z(), -30_000_000, 30_000_000, "z");
        return value;
    }

    static BlockPoint block(BlockPoint value) {
        Objects.requireNonNull(value, "target");
        range(value.x(), -30_000_000, 30_000_000, "x");
        range(value.y(), -30_000_000, 30_000_000, "y");
        range(value.z(), -30_000_000, 30_000_000, "z");
        return value;
    }

    static Map<String, Integer> enchantments(Map<String, Integer> value) {
        Objects.requireNonNull(value, "enchantments");
        if (value.size() > 32) throw new IllegalArgumentException("at most 32 enchantments are supported");
        for (var entry : value.entrySet()) {
            text(entry.getKey(), "enchantment", 128);
            if (!entry.getKey().matches("[a-z0-9._/-]+:[a-z0-9._/-]+")) {
                throw new IllegalArgumentException("enchantments must use namespaced keys");
            }
            range(Objects.requireNonNull(entry.getValue(), "enchantment level"), 1, 255, "enchantment level");
        }
        return Map.copyOf(value);
    }
}
