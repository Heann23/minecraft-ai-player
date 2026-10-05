package me.herry.minecraftAI.ai.comm;

import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import me.herry.minecraftAI.ai.AIController;
import me.herry.minecraftAI.ai.AIState;
import me.herry.minecraftAI.ai.brain.DecisionTrace;
import me.herry.minecraftAI.ai.team.Phrases;

/** One named AI and at most 41 inventory slots. Never searches blocks, chunks or other players. */
public final class GameStateReader {
    private final AIController controller;
    private final BooleanSupplier mainThread;
    public GameStateReader(AIController controller, BooleanSupplier mainThread) {
        this.controller = java.util.Objects.requireNonNull(controller); this.mainThread = java.util.Objects.requireNonNull(mainThread);
    }
    public GameStateSnapshot read(String target) {
        if (!mainThread.getAsBoolean()) throw new IllegalStateException("game state requires main thread");
        var ai = controller.get(target);
        if (ai == null) return null;
        var player = ai.getPlayer();
        if (!player.isOnline()) return null;
        var position = ai.getPosition(); var state = ai.getState();
        boolean running = state == AIState.RUNNING;
        var goal = ai.getCurrentGoal(); var trace = ai.getDecision();
        var items = new TreeMap<String, Integer>(); var inventory = player.getInventory();
        for (int slot = 0; slot < Math.min(41, inventory.getSize()); slot++) {
            var item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir() && item.getAmount() > 0)
                items.merge(item.getType().name(), item.getAmount(), Math::addExact);
        }
        return new GameStateSnapshot(ai.getName(), state.name(), running ? goal.name() : "NONE",
                running ? Phrases.goalActivity(goal) + " 중" : state == AIState.DEAD ? "리스폰을 기다리는 중" : "자율 행동 중지",
                running ? ai.getCurrentActionName() : "None",
                running && trace != null && trace.goal() == goal
                        ? trace.origin() == DecisionTrace.Origin.AUTONOMOUS ? trace.reason() : "지정된 목표를 따르는 중이에요" : "",
                player.getWorld().getEnvironment().name(), position.x(), position.y(), position.z(), player.getHealth(), player.getFoodLevel(), items);
    }
}
