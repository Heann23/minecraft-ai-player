package me.herry.minecraftAI.ai.skill;

import me.herry.minecraftAI.ai.combat.CombatSystem;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.goal.model.AcquireGoal;
import me.herry.minecraftAI.ai.goal.model.BuildGoal;
import me.herry.minecraftAI.ai.goal.model.CraftGoal;
import me.herry.minecraftAI.ai.goal.model.DefeatGoal;
import me.herry.minecraftAI.ai.goal.model.ExploreGoal;
import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.goal.model.InteractGoal;
import me.herry.minecraftAI.ai.goal.model.ReachGoal;
import me.herry.minecraftAI.ai.plan.Planner;
import org.jetbrains.annotations.Nullable;

/**
 * 기존 목표 하나와 그대로 대응하는 범용 목표를 그 목표의 계획으로 수행한다.
 * (거점으로 돌아가기, 집 짓기, 몬스터 물리치기, 잠자기, 전리품 상자, 철 제련, 작업대와 횃불, 탐험)
 *
 * 여기에 없는 것은 아직 수행할 방법이 없는 목표다. 좌표나 다른 차원으로 가기, 네더 포탈 짓기,
 * 종류를 정한 몬스터 잡기가 그렇다. 그런 목표는 맡는 스킬이 없어서 계획이 서지 않는다.
 */
public final class DirectRouteSkill extends RoutedSkill {
    public DirectRouteSkill(Planner planner) {
        super("DirectRoute", planner);
    }

    @Override
    protected boolean handles(Goal goal) {
        return switch (goal) {
            case ReachGoal reach -> reach.place() == ReachGoal.Place.HOME;
            case BuildGoal build -> build.structure() == BuildGoal.Structure.SHELTER;
            case DefeatGoal defeat -> defeat.target().equals(DefeatGoal.HOSTILE) || defeat.target().equals(DefeatGoal.ALLY_THREAT);
            case InteractGoal ignored -> true;
            case ExploreGoal ignored -> true;
            case AcquireGoal acquire -> acquire.item().equals("IRON_INGOT") || acquire.item().equals("FLINT")
                    || acquire.item().equals("WATER_BUCKET");
            case CraftGoal craft -> craft.item().equals(CraftGoal.CRAFTING_TABLE) || craft.item().equals("TORCH");
            default -> false;
        };
    }

    @Override
    public @Nullable GoalType route(Goal goal, Situation s) {
        return switch (goal) {
            case ReachGoal reach -> reach.place() == ReachGoal.Place.HOME && s.homeKnown ? GoalType.RETURN_HOME : null;
            case BuildGoal build -> build.structure() == BuildGoal.Structure.SHELTER && (s.canBuildHere || s.shelterInProgress)
                    ? GoalType.BUILD_SHELTER : null;
            case DefeatGoal defeat -> switch (defeat.target()) {
                case DefeatGoal.HOSTILE -> s.combat == CombatSystem.Decision.FIGHT ? GoalType.FIGHT_HOSTILE : null;
                case DefeatGoal.ALLY_THREAT -> s.allyNeedsHelp ? GoalType.ASSIST_ALLY : null;
                default -> null;
            };
            case InteractGoal interact -> switch (interact.interaction()) {
                case SLEEP -> s.canSleep ? GoalType.SLEEP : null;
                case LOOT_CHEST -> s.knowsLootChest ? GoalType.LOOT_CHEST : null;
            };
            case ExploreGoal ignored -> GoalType.EXPLORE;
            // 캐 놓은 원석이 있어야 구울 수 있다. 원석부터 구하는 것은 목표를 나누는 쪽의 일이다.
            // 부싯돌은 자갈을 캐서 얻는다. 자갈이 없으면 찾으러 다니는 것까지 그 목표의 계획이 한다.
            // 물은 빈 양동이가 있어야 뜬다. 물을 찾으러 다니는 것까지 그 목표의 계획이 한다.
            case AcquireGoal acquire -> acquire.item().equals("FLINT") ? GoalType.GATHER_FLINT
                    : acquire.item().equals("WATER_BUCKET") ? (s.emptyBucket ? GoalType.FILL_BUCKET : null)
                    : acquire.item().equals("IRON_INGOT") && s.rawIron > 0 ? GoalType.SMELT_IRON : null;
            case CraftGoal craft -> switch (craft.item()) {
                // 작업대는 만들어서 놓는 데까지 한다. 놓여 있어야 쓸 수 있다.
                case CraftGoal.CRAFTING_TABLE -> GoalType.CRAFT_WORKBENCH;
                case "TORCH" -> s.canCraftTorch ? GoalType.CRAFT_TORCH : null;
                default -> null;
            };
            default -> null;
        };
    }
}
