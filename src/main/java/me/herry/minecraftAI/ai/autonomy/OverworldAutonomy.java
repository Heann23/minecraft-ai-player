package me.herry.minecraftAI.ai.autonomy;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.plan.FarmingPlans;
import me.herry.minecraftAI.ai.plan.IrrigationPlans;
import me.herry.minecraftAI.ai.farming.CropRules;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Per-player, budgeted receding-horizon planning; no model, LLM, world writes or extra action lane. */
public final class OverworldAutonomy {
    public record Decision(UUID world, AutonomyGoal objective, ActionModel action,
                           OverworldDomain.Operation operation, PlanningState before, String reason,
                           List<String> forecast) {
        public Decision { forecast = List.copyOf(forecast); }
        public String key() { return world + ":" + objective.id() + ":" + action.id(); }
    }
    private record Failure(int count, long until) {}
    private record Search(AutonomyGoal goal, ForwardSearch search, SupportSearch support) {
        boolean pending() { return search.status() == ForwardSearch.Status.SEARCHING
                || search.status() != ForwardSearch.Status.SOLVED && support.status() == SupportSearch.Status.SEARCHING; }
    }
    private static final int EXPANSIONS_PER_SEARCH = 16;
    private static final int MAX_FAILURES = 128;
    private final Map<String, Failure> failures = new LinkedHashMap<>();
    private OverworldDomain.Snapshot snapshot;
    private OverworldDomain.Catalog catalog;
    private final List<Search> searches = new ArrayList<>();
    private int cursor;
    private long capturedAt;
    private String status = "관측 대기";
    private boolean farmSurveyPending;

    public @Nullable Decision choose(AIPlayer ai, Situation situation) {
        if (ai.getWorkBudget().isExhausted()) { status = "계획 예산 대기"; return null; }
        if (catalog == null || catalog.revision() != ai.getCrafting().revision()) {
            catalog = new OverworldDomain.Catalog(ai.getCrafting().revision());
            invalidate();
        }
        if (!catalog.complete()) {
            catalog.advance(ai);
            status = "등록된 제작·제련 조건 읽는 중";
            if (!catalog.complete()) return null;
        }
        PlanningState actual = OverworldDomain.observe(ai, situation);
        farmSurveyPending = FarmingPlans.searching(ai) || IrrigationPlans.searching(ai);
        boolean retryDue = failures.values().stream().anyMatch(f -> f.until() > capturedAt && f.until() <= ai.getTicks());
        if (snapshot == null || !snapshot.world().equals(ai.getWorldId()) || !actual.equals(snapshot.state())
                || retryDue || ai.getTicks() - capturedAt >= 1200) {
            snapshot = OverworldDomain.capture(ai, situation, catalog, actual);
            capturedAt = ai.getTicks();
            searches.clear();
            cursor = 0;
            failures.entrySet().removeIf(e -> e.getValue().until() + 12000 <= ai.getTicks());
            List<ActionModel> available = snapshot.actions().stream()
                    .filter(a -> { Failure f = failures.get(failureKey(ai, a.id())); return f == null || f.until() <= ai.getTicks(); }).toList();
            for (AutonomyGoal goal : OverworldObjectives.goals(snapshot.state())) {
                searches.add(new Search(goal, new ForwardSearch(snapshot.state(), available, goal),
                        new SupportSearch(snapshot.state(), available, goal)));
            }
        }
        Decision best = null;
        double bestValue = -1;
        int remaining = searches.size();
        while (remaining-- > 0 && !ai.getWorkBudget().isExhausted()) {
            Search candidate = searches.get(cursor++ % searches.size());
            if (candidate.search().status() == ForwardSearch.Status.SEARCHING) {
                candidate.search().advance(EXPANSIONS_PER_SEARCH, ai.getWorkBudget()::isExhausted);
            }
            ActionModel first;
            double cost;
            List<String> forecast;
            boolean partial;
            if (candidate.search().status() == ForwardSearch.Status.SOLVED && !candidate.search().plan().isEmpty()) {
                first = candidate.search().plan().getFirst();
                cost = candidate.search().cost();
                forecast = candidate.search().plan().stream().map(ActionModel::id).toList();
                partial = false;
            } else if (candidate.search().status() != ForwardSearch.Status.SEARCHING) {
                candidate.support().advance(EXPANSIONS_PER_SEARCH, ai.getWorkBudget()::isExhausted);
                if (candidate.support().status() != SupportSearch.Status.READY || candidate.support().choices().isEmpty()) continue;
                var support = candidate.support().choices().getFirst();
                first = support.action();
                cost = support.cost();
                forecast = support.dependencyPath();
                partial = true;
            } else continue;
            // Recheck against actual facts: a forecast is not permission to execute a missing prerequisite.
            if (!actual.satisfies(first.preconditions())) continue;
            double value = candidate.goal().value() / (1.0 + cost);
            if (value > bestValue) {
                bestValue = value;
                String reason = candidate.goal().id() + " 조건을 위해 " + first.id()
                        + "을 선택했어요. " + (partial ? "전체 경로를 확정하지 못해 필요한 선행 조건부터 진행해요. " : "")
                        + "관측된 조건과 예상 비용 " + String.format(java.util.Locale.ROOT, "%.1f", cost)
                        + "을 비교했고, 실행 후 실제 결과를 다시 확인해요";
                best = new Decision(snapshot.world(), candidate.goal(), first, snapshot.operations().get(first.id()),
                        actual, reason, forecast);
            }
        }
        status = best != null ? "실행 후보 준비" : searches.stream().anyMatch(Search::pending)
                ? "조건·효과와 필요한 선행 작업 계산 중"
                : searches.stream().anyMatch(s -> s.search().status() == ForwardSearch.Status.LIMIT_REACHED
                        || s.support().status() == SupportSearch.Status.LIMIT_REACHED)
                ? "계산 한도: 월드 불가능 판정 아님"
                : "현재 관측·지원 행동에서 경로 미확인: 자원 발견/접근 필요";
        if (best == null && snapshot != null && !snapshot.limits().isEmpty()) status += " (후보 범위 제한 있음)";
        return best;
    }

    public boolean searching() { return catalog == null || !catalog.complete()
            || searches.stream().anyMatch(Search::pending) || farmSurveyPending; }
    public String status() { return status; }

    public boolean valid(Decision decision, AIPlayer ai, Situation situation) {
        if (!decision.world().equals(ai.getWorldId())) return false;
        PlanningState actual = OverworldDomain.observe(ai, situation);
        if (decision.objective().achieved(actual)) return false;
        Map<String, Integer> persistent = new HashMap<>(decision.action().preconditions());
        decision.action().consumed().keySet().forEach(persistent::remove);
        // Discovery facts may be omitted when perception yields to the tick budget. The action rechecks its target.
        persistent.keySet().removeIf(token -> token.startsWith("known:"));
        return actual.satisfies(persistent);
    }

    public AutonomyFeedback.Outcome finished(Decision decision, AIPlayer ai, Situation situation, boolean preparation) {
        PlanningState actual = OverworldDomain.observe(ai, situation);
        // The completed action's exact cell is inexpensive to recheck even if the broad survey yielded its budget.
        if (decision.operation().kind() == OverworldDomain.Kind.PLANT_CROP) {
            var soil = decision.operation().position();
            var world = ai.getPlayer().getWorld();
            if (decision.world().equals(world.getUID()) && soil != null && Positions.isLoaded(world, soil.offset(0, 1, 0))
                    && soil.distance(ai.getPosition()) <= 5.0 && Positions.block(world, soil).getType() == Material.FARMLAND
                    && Positions.block(world, soil.offset(0, 1, 0)).getType() == CropRules.crop(decision.operation().target())) {
                Map<String, Integer> proven = new HashMap<>(actual.quantities());
                proven.put(FarmPlanning.planted(decision.operation().target(), soil), 1);
                actual = new PlanningState(proven);
            }
        }
        if (decision.operation().kind() == OverworldDomain.Kind.IRRIGATE && decision.world().equals(ai.getWorldId())) {
            var cell = decision.operation().position();
            if (cell != null && cell.distance(ai.getPosition()) <= 5 && IrrigationPlans.observedSource(ai, cell)) {
                Map<String, Integer> proven = new HashMap<>(actual.quantities());
                proven.put(IrrigationPlanning.water(cell), 1);
                actual = new PlanningState(proven);
            }
        }
        var result = AutonomyFeedback.assess(decision.objective(), decision.action(), decision.before(), actual,
                decision.operation().exploratory());
        if (result == AutonomyFeedback.Outcome.NO_PROGRESS && !preparation) failed(decision, ai);
        else { failures.remove(failureKey(ai, decision.action().id())); invalidate(); }
        return result;
    }

    public void failed(Decision decision, AIPlayer ai) {
        String key = failureKey(ai, decision.action().id());
        Failure previous = failures.get(key);
        int count = previous == null ? 1 : Math.min(previous.count() + 1, 5);
        failures.put(key, new Failure(count, ai.getTicks() + 40L * (1L << count)));
        while (failures.size() > MAX_FAILURES) failures.remove(failures.keySet().iterator().next());
        invalidate();
    }

    private static String failureKey(AIPlayer ai, String action) {
        var position = ai.getPosition();
        return ai.getWorldId() + ":" + action + ":" + (position.x() >> 4) + ":" + (position.y() >> 3) + ":" + (position.z() >> 4);
    }

    public void invalidate() { snapshot = null; searches.clear(); cursor = 0; }
    public void reset() { invalidate(); catalog = null; farmSurveyPending = false; failures.clear(); status = "새 관측 대기"; }
}
