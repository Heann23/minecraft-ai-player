package me.herry.minecraftAI.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigRulesTest {
    private final List<String> warnings = new ArrayList<>();

    @Test
    void validRangesAreKeptWithoutWarning() {
        ConfigRules.CombatRanges ranges = ConfigRules.combatRanges(16, 24, 32, warnings::add);

        assertEquals(16.0, ranges.engageRange());
        assertEquals(24.0, ranges.fleeDistance());
        assertTrue(warnings.isEmpty());
    }

    // 버그 재현: 도망 거리가 교전 범위 이하이면 도망친 자리에서도 여전히 교전 범위 안이다.
    @Test
    void fleeDistanceIsRaisedAboveEngageRange() {
        ConfigRules.CombatRanges ranges = ConfigRules.combatRanges(16, 10, 32, warnings::add);

        assertEquals(16.0, ranges.engageRange());
        assertEquals(16.0 + ConfigRules.MIN_FLEE_MARGIN, ranges.fleeDistance());
        assertEquals(1, warnings.size());
    }

    @Test
    void equalRangesAreAlsoFixed() {
        ConfigRules.CombatRanges ranges = ConfigRules.combatRanges(20, 20, 32, warnings::add);

        assertTrue(ranges.fleeDistance() >= ranges.engageRange() + ConfigRules.MIN_FLEE_MARGIN);
    }

    @Test
    void engageRangeCannotExceedPerception() {
        ConfigRules.CombatRanges ranges = ConfigRules.combatRanges(40, 48, 24, warnings::add);

        assertEquals(24.0, ranges.engageRange());
        assertEquals(48.0, ranges.fleeDistance());
        assertEquals(1, warnings.size());
    }

    // 도망 거리를 한도까지 올려도 모자라면 교전 범위를 줄여서 관계를 맞춘다.
    @Test
    void engageRangeShrinksWhenFleeDistanceHitsItsLimit() {
        ConfigRules.CombatRanges ranges = ConfigRules.combatRanges(48, 20, 48, warnings::add);

        assertEquals(ConfigRules.MAX_FLEE_DISTANCE, ranges.fleeDistance());
        assertEquals(ConfigRules.MAX_FLEE_DISTANCE - ConfigRules.MIN_FLEE_MARGIN, ranges.engageRange());
        assertTrue(ranges.fleeDistance() >= ranges.engageRange() + ConfigRules.MIN_FLEE_MARGIN);
    }

    @Test
    void nodesPerTickIsCappedByPathLimit() {
        assertEquals(400, ConfigRules.nodesPerTick(400, 4000, warnings::add));
        assertTrue(warnings.isEmpty());

        assertEquals(200, ConfigRules.nodesPerTick(5000, 200, warnings::add));
        assertEquals(1, warnings.size());
    }

    @Test
    void criticalHealthCannotExceedLowHealth() {
        assertEquals(4, ConfigRules.criticalHealth(4, 8, warnings::add));
        assertTrue(warnings.isEmpty());

        assertEquals(6, ConfigRules.criticalHealth(10, 6, warnings::add));
        assertEquals(1, warnings.size());
    }

    // 정해진 이름 중 하나여야 하는 값: 대소문자와 앞뒤 공백은 봐주고, 목록에 없으면 기본값으로 바꾸면서 알린다.
    @Test
    void oneOfFallsBackAndWarnsOnUnknownNames() {
        List<String> warnings = new ArrayList<>();
        List<String> allowed = List.of("none", "repeat-last");
        assertEquals("repeat-last", ConfigRules.oneOf("learning.shadow-policy", " Repeat-Last ", allowed, "none", warnings::add));
        assertEquals("none", ConfigRules.oneOf("learning.shadow-policy", "none", allowed, "none", warnings::add));
        assertTrue(warnings.isEmpty());

        assertEquals("none", ConfigRules.oneOf("learning.shadow-policy", "repeat_last", allowed, "none", warnings::add));
        assertEquals("none", ConfigRules.oneOf("learning.shadow-policy", null, allowed, "none", warnings::add));
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("learning.shadow-policy"));
        assertTrue(warnings.get(0).contains("repeat_last"));
    }
}
