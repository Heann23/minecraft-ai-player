package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.crafting.CraftingSystem;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 아이템을 제작한다. 중간 재료가 없으면 그것부터 순서대로 만든다 (예: 원목 -> 판자 -> 막대기 -> 곡괭이).
 */
public final class CraftItemAction extends AbstractAction implements PrimitiveAction {
    private static final int TIMEOUT = 200;
    // 제작 한 단계 사이의 간격. 한 틱에 전부 만들어 버리면 사람처럼 보이지 않는다.
    private static final int STEP_INTERVAL = 10;
    private static final int TABLE_RADIUS = 4;

    private final Material target;
    private final int amount;
    private final @Nullable String recipeId;
    private List<CraftingSystem.CraftStep> steps;
    private int stepIndex;
    private @Nullable BlockPoint table;

    public CraftItemAction(Material target, int amount) {
        this(target, amount, null);
    }

    public CraftItemAction(Material target, int amount, @Nullable String recipeId) {
        super("CraftItem", TIMEOUT);
        this.target = target;
        this.amount = amount;
        this.recipeId = recipeId;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        boolean needsTable;
        if (recipeId != null) {
            CraftingSystem.CraftStep selected = ai.getCrafting().singleStep(target, recipeId, ai.getInventory().snapshot());
            if (selected == null) { fail("selected recipe unavailable: " + recipeId); return; }
            steps = List.of(selected);
            needsTable = selected.needsTable();
        } else {
            CraftingSystem.CraftPlan plan = ai.getCrafting().plan(target, amount, ai.getInventory().snapshot());
            if (!plan.isFeasible()) { fail("missing materials for " + target); return; }
            steps = plan.steps();
            needsTable = plan.needsTable();
        }

        if (needsTable) {
            table = NearbyBlocks.find(ai.getPlayer(), Material.CRAFTING_TABLE, TABLE_RADIUS);
            if (table == null) {
                fail("no crafting table nearby");
                return;
            }
            // 주변을 뒤져서 찾은 작업대도 기억해 둔다.
            ai.getMemory().rememberPermanent(MemoryType.WORKBENCH, ai.getWorldId(), table, ai.getTicks());
        }
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (table != null) ai.getBody().lookAt(table.x() + 0.5, table.y() + 0.5, table.z() + 0.5);
        if (getElapsed() % STEP_INTERVAL != 0) return;

        CraftingSystem.CraftStep step = steps.get(stepIndex);
        if (step.needsTable() && !NearbyBlocks.is(player.getWorld(), table, Material.CRAFTING_TABLE)) {
            fail("crafting table disappeared");
            return;
        }
        if (!ai.getCrafting().craft(player, step)) {
            fail("missing materials for " + step.result());
            return;
        }

        player.swingMainHand();
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.4F, 1.2F);
        ai.debug("Crafted " + step.result() + " x" + step.recipe().getResult().getAmount() * step.times());
        if (++stepIndex >= steps.size()) {
            // Teacher keeps its old convenience; learned mode chooses an explicit equipment operation separately.
            if (!ai.getConfig().learnedControl) ai.getInventory().wearBestArmor();
            succeed();
        }
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.CRAFT_ITEM;
    }

    @Override
    public PrimitiveTarget getTarget() {
        return new PrimitiveTarget.Item(target.name(), amount);
    }
}
