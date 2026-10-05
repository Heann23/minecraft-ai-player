package me.herry.minecraftAI.ai.experience;

import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.observation.Observation;
import me.herry.minecraftAI.ai.observation.ObservationFixture;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 기록을 JSON 한 줄로 바꾸는 규칙.
 */
class JsonLinesTest {
    private record Sample(String name, int count, double ratio, boolean on, GoalType goal, List<String> tags, Object nothing) {
    }

    @Test
    void recordBecomesAnObjectInComponentOrder() {
        String json = JsonLines.toJson(new Sample("a", 3, 0.5, true, GoalType.MINE_IRON, List.of("x", "y"), null));
        assertEquals("{\"name\":\"a\",\"count\":3,\"ratio\":0.5,\"on\":true,\"goal\":\"MINE_IRON\",\"tags\":[\"x\",\"y\"],\"nothing\":null}", json);
    }

    // 한 줄에 기록 하나이므로 줄바꿈이 그대로 들어가면 안 된다.
    @Test
    void stringsAreEscapedAndStayOnOneLine() {
        String json = JsonLines.toJson(Map.of("text", "say \"hi\"\nnext\\line\ttab\u0001"));
        assertEquals("{\"text\":\"say \\\"hi\\\"\\nnext\\\\line\\ttab\\u0001\"}", json);
        assertFalse(json.contains("\n"));
        // 한글은 그대로 둔다.
        assertEquals("\"나무 곡괭이\"", JsonLines.toJson("나무 곡괭이"));
    }

    // JSON 에 없는 숫자는 null 로 적는다. 관리자가 고정한 목표의 점수(아주 큰 값)는 숫자 그대로다.
    @Test
    void numbersThatJsonCannotHoldBecomeNull() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("nan", Double.NaN);
        values.put("inf", Double.POSITIVE_INFINITY);
        values.put("max", Double.MAX_VALUE);
        values.put("float", 1.5F);
        values.put("long", 9_000_000_000L);
        assertEquals("{\"nan\":null,\"inf\":null,\"max\":1.7976931348623157E308,\"float\":1.5,\"long\":9000000000}", JsonLines.toJson(values));
    }

    // 종류가 여럿인 값(기본 행동의 대상)은 어느 것인지 알 수 있어야 한다.
    @Test
    void sealedVariantsCarryTheirKind() {
        assertEquals("{\"kind\":\"Block\",\"pos\":{\"x\":1,\"y\":64,\"z\":-2}}", JsonLines.toJson(new PrimitiveTarget.Block(new BlockPoint(1, 64, -2))));
        assertEquals("{\"kind\":\"None\"}", JsonLines.toJson(PrimitiveTarget.NONE));
        assertEquals("{\"kind\":\"Item\",\"item\":\"TORCH\",\"amount\":4}", JsonLines.toJson(new PrimitiveTarget.Item("TORCH", 4)));
    }

    @Test
    void observationIsWritable() {
        ObservationFixture fixture = new ObservationFixture().item("RAW_IRON", 3);
        fixture.homeKnown = true;
        Observation observation = fixture.build();
        String json = JsonLines.toJson(observation);
        assertTrue(json.startsWith("{\"schemaVersion\":" + Observation.SCHEMA_VERSION + ",\"tick\":0,\"player\":{\"x\":0.0,"), json);
        assertTrue(json.contains("\"itemCounts\":{\"RAW_IRON\":3}"), json);
        assertTrue(json.contains("\"homeKnown\":true"), json);
        assertTrue(json.contains("\"task\":{\"goal\":\"IDLE\""), json);
        assertFalse(json.contains("\n"));
    }

    // 값이 아닌 것(월드 객체 등)이 섞여 들어오면 조용히 이상한 글자를 쓰지 않고 바로 알린다.
    @Test
    void refusesThingsThatAreNotPlainValues() {
        assertThrows(IllegalArgumentException.class, () -> JsonLines.toJson(new Object()));
        assertThrows(IllegalArgumentException.class, () -> JsonLines.toJson(List.of(new StringBuilder("x"))));
    }
}
