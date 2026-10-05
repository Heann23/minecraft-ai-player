package me.herry.minecraftAI.ai;

import me.herry.minecraftAI.ai.combat.CombatSystem;
import me.herry.minecraftAI.ai.crafting.CraftingSystem;
import me.herry.minecraftAI.ai.experience.ExperienceSink;
import me.herry.minecraftAI.ai.perf.WorkBudget;
import me.herry.minecraftAI.ai.plan.Planner;
import me.herry.minecraftAI.ai.survival.SurvivalSystem;
import me.herry.minecraftAI.ai.team.TeamChat;
import me.herry.minecraftAI.config.AIConfig;

import java.util.function.Consumer;

/**
 * 모든 AI 가 함께 쓰는 것들. AIController 가 하나 만들어서 AI 를 생성할 때마다 넘겨준다.
 *
 * @param experience    학습용 기록이 가는 곳. 기록을 꺼 두었으면 ExperienceSink.NONE
 * @param pluginVersion 기록에 남길 플러그인 버전
 * @param warn          디버그가 꺼져 있어도 콘솔에 남겨야 하는 경고를 받는다
 */
public record AIServices(AIConfig config, AIDebugger debugger, CraftingSystem crafting, CombatSystem combat,
                         SurvivalSystem survival, Planner planner, TeamChat team, WorkBudget budget,
                         ExperienceSink experience, String pluginVersion, Consumer<String> warn) {
}
