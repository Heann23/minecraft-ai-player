package me.herry.minecraftAI.ai;

import me.herry.minecraftAI.ai.combat.CombatSystem;
import me.herry.minecraftAI.ai.crafting.CraftingSystem;
import me.herry.minecraftAI.ai.perf.WorkBudget;
import me.herry.minecraftAI.ai.plan.Planner;
import me.herry.minecraftAI.ai.survival.SurvivalSystem;
import me.herry.minecraftAI.ai.team.TeamChat;
import me.herry.minecraftAI.config.AIConfig;

/**
 * 모든 AI 가 함께 쓰는 것들. AIController 가 하나 만들어서 AI 를 생성할 때마다 넘겨준다.
 */
public record AIServices(AIConfig config, AIDebugger debugger, CraftingSystem crafting, CombatSystem combat,
                         SurvivalSystem survival, Planner planner, TeamChat team, WorkBudget budget) {
}
