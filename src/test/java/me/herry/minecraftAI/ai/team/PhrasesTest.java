package me.herry.minecraftAI.ai.team;

import me.herry.minecraftAI.ai.goal.GoalType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PhrasesTest {
    @Test
    void everyGoalHasAnActivityDescription() {
        // 새 목표를 추가하고 문장을 빠뜨리면 여기서 걸린다.
        for (GoalType goal : GoalType.values()) {
            assertFalse(Phrases.goalActivity(goal).isBlank(), goal.name());
        }
    }

    // 조사는 앞 낱말의 받침에 따라 달라진다.
    @Test
    void particlesFollowTheFinalConsonant() {
        assertEquals("철 검을", Phrases.object("철 검"));
        assertEquals("방패를", Phrases.object("방패"));
        assertEquals("집을", Phrases.object("집"));
    }
}
