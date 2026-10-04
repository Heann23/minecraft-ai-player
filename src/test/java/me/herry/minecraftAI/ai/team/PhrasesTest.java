package me.herry.minecraftAI.ai.team;

import me.herry.minecraftAI.ai.AIState;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.Milestone;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.perception.ThreatType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhrasesTest {
    @Test
    void everyGoalHasAnActivityDescription() {
        // 새 목표를 추가하고 문장을 빠뜨리면 여기서 걸린다.
        for (GoalType goal : GoalType.values()) {
            assertFalse(Phrases.goalActivity(goal).isBlank(), goal.name());
            Phrases.goalAnnouncement(goal);
        }
    }

    @Test
    void quietGoalsAreNotAnnounced() {
        assertNull(Phrases.goalAnnouncement(GoalType.PICKUP_ITEMS));
        assertNull(Phrases.goalAnnouncement(GoalType.IDLE));
    }

    @Test
    void foundMessageHasNoCoordinates() {
        assertEquals("철 광석을 찾았어요! 위치 알려 줄게요.", Phrases.found(MemoryType.IRON_ORE));
        assertFalse(Phrases.found(MemoryType.TREE).matches(".*[0-9].*"));
        assertFalse(Phrases.placed(MemoryType.WORKBENCH).matches(".*[0-9].*"));
    }

    @Test
    void craftingMessageNamesTheItem() {
        assertEquals("돌 곡괭이를 만들게요.", Phrases.willCraft(Milestone.STONE_PICKAXE));
        assertNull(Phrases.goalAnnouncement(GoalType.CRAFT_TOOL));
    }

    // 조사는 앞 낱말의 받침에 따라 달라진다.
    @Test
    void particlesFollowTheFinalConsonant() {
        assertEquals("철 검을", Phrases.object("철 검"));
        assertEquals("방패를", Phrases.object("방패"));
        assertEquals("집을", Phrases.object("집"));
        assertEquals("철은", Phrases.topic("철"));
        assertEquals("나무는", Phrases.topic("나무"));
        // 모든 중기 목표의 이름으로 자연스러운 문장이 만들어져야 한다.
        for (Milestone milestone : Milestone.values()) {
            String line = Phrases.willCraft(milestone);
            assertTrue(line.startsWith(milestone.label()), line);
            assertTrue(line.endsWith("만들게요."), line);
        }
    }

    @Test
    void helpMessageNamesTheAttacker() {
        assertEquals("도와주세요! 좀비한테 공격받고 있어요!", Phrases.help(ThreatType.ZOMBIE));
        assertTrue(Phrases.help(ThreatType.OTHER_HOSTILE).contains("몬스터"));
    }

    @Test
    void statusDependsOnState() {
        assertEquals("저는 지금 나무를 캐는 중이에요. (체력 20, 허기 18)", Phrases.status(AIState.RUNNING, GoalType.COLLECT_WOOD, 20, 18));
        assertTrue(Phrases.status(AIState.STOPPED, GoalType.IDLE, 20, 20).contains("멈춰"));
    }
}
