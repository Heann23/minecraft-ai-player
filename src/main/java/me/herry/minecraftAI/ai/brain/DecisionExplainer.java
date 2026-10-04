package me.herry.minecraftAI.ai.brain;

import me.herry.minecraftAI.ai.goal.Stage;
import me.herry.minecraftAI.ai.team.Phrases;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 판단 기록을 사람이 읽는 설명으로 바꾼다. "/ai why" 와 채팅의 "왜 그거 하고 있어?" 가 같은 설명을 쓴다.
 */
public final class DecisionExplainer {
    public static final String FINAL_GOAL = "엔더 드래곤 처치";

    private DecisionExplainer() {
    }

    /**
     * 최종/장기/중기/단기 목표와 현재 행동, 이유를 한 줄씩 돌려준다.
     *
     * @param action 지금 실행 중인 행동의 이름
     */
    public static List<String> describe(@Nullable DecisionTrace trace, String action) {
        List<String> lines = new ArrayList<>();
        if (trace == null) {
            lines.add("아직 아무 판단도 하지 않았어요.");
            return lines;
        }
        lines.add("최종 목표: " + FINAL_GOAL);
        lines.add("장기 목표: " + trace.stage().label());
        if (trace.stage() != Stage.CLEARED) {
            lines.add("중기 목표: " + (trace.milestone() == null ? "없음" : trace.milestone().label() + " 마련하기"));
        }
        lines.add("단기 목표: " + Phrases.goalActivity(trace.goal()) + " 중" + originNote(trace.origin()));
        lines.add("현재 행동: " + action);
        lines.add("이유: " + trace.reason());
        return lines;
    }

    // 채팅으로 대답할 때 쓰는 짧은 한두 문장짜리 설명
    public static String brief(@Nullable DecisionTrace trace) {
        if (trace == null) return "아직 아무것도 정하지 않았어요.";
        String goal = trace.stage() == Stage.CLEARED ? "" : " 지금은 '" + trace.stage().label() + "' 단계예요.";
        return trace.reason() + "." + goal;
    }

    // 고려했던 후보들을 점수와 함께 보여 준다 (디버그용).
    public static List<String> candidates(@Nullable DecisionTrace trace) {
        List<String> lines = new ArrayList<>();
        if (trace == null) return lines;
        for (DecisionTrace.Candidate candidate : trace.candidates()) {
            String mark = candidate.goal() == trace.goal() ? " <- 선택" : candidate.resting() ? " (계속 실패해서 쉬는 중)" : "";
            lines.add(String.format(Locale.ROOT, "%s %.0f%s", candidate.goal().name(), candidate.score(), mark));
        }
        return lines;
    }

    private static String originNote(DecisionTrace.Origin origin) {
        return switch (origin) {
            case AUTONOMOUS -> "";
            case REQUESTED -> " (부탁받은 일)";
            case FORCED -> " (관리자가 고정)";
        };
    }
}
