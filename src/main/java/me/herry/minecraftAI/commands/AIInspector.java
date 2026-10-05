package me.herry.minecraftAI.commands;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.brain.DecisionExplainer;
import me.herry.minecraftAI.ai.brain.DecisionLog;
import me.herry.minecraftAI.ai.brain.DecisionTrace;
import me.herry.minecraftAI.ai.brain.Directive;
import me.herry.minecraftAI.ai.build.BuildJob;
import me.herry.minecraftAI.ai.experience.ExperienceRecorder;
import me.herry.minecraftAI.ai.experience.JsonlExperienceWriter;
import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.observation.Observation;
import me.herry.minecraftAI.ai.perf.TickProfiler;
import me.herry.minecraftAI.ai.perf.WorkBudget;
import me.herry.minecraftAI.ai.policy.ShadowRunner;
import me.herry.minecraftAI.ai.world.Base;
import me.herry.minecraftAI.ai.world.WorldModel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * AI 의 속을 들여다보는 디버그 명령의 출력 내용을 만든다 (/ai why, brain, plan, memory, perf).
 * 문자열만 만들고 보내는 일은 AICommand 가 한다.
 */
final class AIInspector {
    private static final int HISTORY_LINES = 6;
    private static final int FAILURE_LINES = 5;

    private AIInspector() {
    }

    // 지금 하는 일과 그 이유 (최종/장기/중기/단기 목표, 현재 행동, 이유)
    static List<String> why(AIPlayer ai) {
        List<String> lines = new ArrayList<>(DecisionExplainer.describe(ai.getDecision(), ai.getCurrentActionName()));
        Directive directive = ai.getDirective();
        if (directive != null) {
            long left = Math.max(0L, directive.expiresAt() - ai.getTicks());
            lines.add("부탁받은 일: " + directive.describe() + " (남은 시간 " + left / 20 + "초)");
        }
        return lines;
    }

    // 후보 목표의 점수, 최근에 목표가 바뀐 기록, 최근 실패와 포기한 목표
    static List<String> brain(AIPlayer ai) {
        List<String> lines = new ArrayList<>();
        DecisionTrace trace = ai.getDecision();
        lines.add("-- 후보 목표 (점수 순) --");
        List<String> candidates = DecisionExplainer.candidates(trace);
        lines.addAll(candidates.isEmpty() ? List.of("(아직 판단하지 않음)") : candidates);

        DecisionLog log = ai.getDecisionLog();
        lines.add("-- 최근 목표 변경 --");
        List<DecisionTrace> history = log.history();
        for (DecisionTrace past : history.subList(Math.max(0, history.size() - HISTORY_LINES), history.size())) {
            lines.add(ago(ai, past.tick()) + " " + past.previousGoal() + " -> " + past.goal() + " : " + past.reason());
        }

        lines.add("-- 최근 실패 --");
        List<DecisionLog.Failure> failures = log.getFailures();
        for (DecisionLog.Failure failure : failures.subList(Math.max(0, failures.size() - FAILURE_LINES), failures.size())) {
            lines.add(ago(ai, failure.tick()) + " " + failure.goal() + " / " + failure.action() + " : " + failure.reason());
        }
        for (DecisionLog.GiveUp giveUp : log.getGiveUps()) {
            lines.add(ago(ai, giveUp.tick()) + " " + giveUp.goal() + " 을(를) " + giveUp.restTicks() / 20 + "초 쉬기로 함 (" + giveUp.lastReason() + ")");
        }
        return lines;
    }

    static List<String> plan(AIPlayer ai) {
        String skill = ai.getJournal().skill();
        return List.of("목표: " + ai.getCurrentGoal(), "목표 명세: " + ai.getJournal().goalSpec().describe(),
                "스킬: " + (skill.isEmpty() ? "(없음)" : skill), "계획: " + ai.describePlan(), "현재 행동: " + ai.getCurrentActionName());
    }

    // 마지막으로 판단할 때 남긴 관측 스냅샷. 학습 쪽으로 넘어갈 값이 실제와 맞는지 눈으로 확인할 때 쓴다.
    static List<String> observe(AIPlayer ai) {
        Observation observation = ai.getJournal().observation();
        if (observation == null) return List.of("(아직 판단하지 않음)");
        Goal spec = ai.getJournal().goalSpec();
        return List.of("버전 " + observation.schemaVersion() + ", 틱 " + observation.tick(),
                "몸: " + observation.player(), "가방: " + observation.inventory(), "환경: " + observation.environment(),
                "진행: " + observation.progress(), "기억: " + observation.memory(), "하던 일: " + observation.task(),
                "목표 명세 " + spec.describe() + " 완료 조건: " + (spec.isAchieved(observation) ? "채워짐" : "아직"));
    }

    /**
     * 학습용 기록과 후보 정책의 상태: 남기고 있는지, 지금 에피소드, 남긴 양, 후보가 기존 규칙과 얼마나 같은 판단을 했는지.
     *
     * @param writer 기록을 파일로 쓰는 곳. 기록을 꺼 두었으면 null
     */
    static List<String> learn(AIPlayer ai, @Nullable JsonlExperienceWriter writer) {
        ExperienceRecorder recorder = ai.getJournal().recorder();
        List<String> lines = new ArrayList<>();
        if (writer == null) {
            lines.add("기록: 꺼짐 (config.yml 의 learning.record)");
        } else {
            lines.add("기록: 켜짐" + (writer.isFull() ? " (크기 제한에 닿아서 더 남기지 않는 중)" : "")
                    + ", 에피소드 " + (recorder.episodeId() == null ? "없음" : recorder.episodeId()));
            lines.add("남긴 판단 " + recorder.decisionCount() + "개, 파일에 쓴 줄 " + writer.writtenCount() + "개, 버린 줄 " + writer.droppedCount() + "개");
        }
        ShadowRunner shadow = recorder.shadow();
        if (shadow == null) {
            lines.add("후보 정책: 없음 (config.yml 의 learning.shadow-policy)");
        } else {
            ShadowRunner.Stats stats = shadow.stats();
            lines.add("후보 정책: " + shadow.label() + (stats.enabled() ? "" : " (오류나 지연이 이어져서 꺼짐)") + ", 실행은 기존 규칙만 한다");
            lines.add("스스로 고른 판단 " + stats.compared() + "개 중 " + stats.agreed() + "개 일치 ("
                    + String.format(Locale.ROOT, "%.1f", stats.agreement() * 100.0) + "%), 후보 오류 " + stats.failed() + "개");
        }
        return lines;
    }

    // 거점, 포탈, 상자 내용물, 종류별 기억 개수
    static List<String> memory(AIPlayer ai) {
        List<String> lines = new ArrayList<>();
        WorldModel model = ai.getWorldModel();
        UUID world = ai.getWorldId();
        Base home = model.getHome();
        if (home == null) {
            lines.add("거점: 없음");
        } else {
            lines.add("거점: " + home.center() + (home.isSheltered() ? " (집 완성)" : " (임시)") + (home.world().equals(world) ? "" : " [다른 월드]"));
            lines.add("  작업대 " + point(home.workbench()) + ", 화로 " + point(home.furnace()) + ", 침대 " + point(home.bed())
                    + ", 문 " + point(home.entrance()) + ", 상자 " + home.chests().size() + "개");
        }
        BuildJob job = ai.getBuildJob();
        if (job != null) lines.add("짓는 중: " + job.blueprint().name() + " @ " + job.origin());
        for (WorldModel.Portal portal : model.getPortals()) lines.add("포탈(" + portal.kind() + "): " + portal.pos());
        if (model.getStronghold() != null) lines.add("요새: " + model.getStronghold().pos());
        lines.add("탐험한 청크: " + model.exploredCount(world) + "개, 죽은 곳: " + model.getDeaths().size() + "곳");

        for (Map.Entry<WorldModel.Place, Map<String, Integer>> entry : model.getStorage().entrySet()) {
            int total = entry.getValue().values().stream().mapToInt(Integer::intValue).sum();
            lines.add("상자 " + entry.getKey().pos() + ": " + entry.getValue().size() + "종류 " + total + "개 " + summary(entry.getValue()));
        }

        StringBuilder counts = new StringBuilder("기억: ");
        for (MemoryType type : MemoryType.values()) {
            int count = ai.getMemory().count(type, world, ai.getTicks());
            if (count > 0) counts.append(type.name()).append('=').append(count).append(' ');
        }
        lines.add(counts.toString().trim());
        return lines;
    }

    // 구간별로 AI 가 틱마다 쓰는 시간
    static List<String> perf(AIPlayer ai, WorkBudget budget) {
        List<String> lines = new ArrayList<>();
        TickProfiler profiler = ai.getProfiler();
        lines.add("틱당 걸린 시간 (최근 평균 / 최근 최대), 측정 " + profiler.getTicks() + "틱");
        for (TickProfiler.Section section : TickProfiler.Section.values()) {
            TickProfiler.Stats stats = profiler.stats(section);
            lines.add(String.format(Locale.ROOT, "  %s: %.3f ms / %.3f ms", section.label(), stats.averageMicros() / 1000.0, stats.maxMicros() / 1000.0));
        }
        if (budget.isLimited()) {
            double share = budget.getTicks() == 0 ? 0.0 : 100.0 * budget.getExhaustedTicks() / budget.getTicks();
            lines.add(String.format(Locale.ROOT, "시간 예산: 틱당 %.1f ms, 예산을 다 써서 일을 미룬 틱 %.2f%% (%d / %d)",
                    budget.getBudgetNanos() / 1_000_000.0, share, budget.getExhaustedTicks(), budget.getTicks()));
        } else {
            lines.add("시간 예산: 제한 없음 (개수 제한만 적용)");
        }
        return lines;
    }

    private static String ago(AIPlayer ai, long tick) {
        return "[" + Math.max(0L, ai.getTicks() - tick) / 20 + "초 전]";
    }

    private static String point(Object point) {
        return point == null ? "없음" : point.toString();
    }

    // 많이 든 것부터 몇 가지만 보여 준다.
    private static String summary(Map<String, Integer> contents) {
        return contents.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(4)
                .map(entry -> entry.getKey().toLowerCase(Locale.ROOT) + " x" + entry.getValue())
                .reduce((a, b) -> a + ", " + b)
                .map(text -> "(" + text + ")")
                .orElse("");
    }
}
