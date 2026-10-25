package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.AbstractAction;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.ActionStatus;
import me.herry.minecraftAI.ai.action.BreakBlockAction;
import me.herry.minecraftAI.ai.action.MoveToAction;
import me.herry.minecraftAI.ai.action.PickupItemAction;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.perception.Visibility;
import me.herry.minecraftAI.ai.primitive.control.ToolChoice;
import me.herry.minecraftAI.ai.survival.SeedSources;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Seed acquisition opportunities from actual observed plants, using normal mining and pickup. */
public final class SeedGatheringPlans {
    private static final double SOURCE_RANGE = 24.0;
    private static final double REACH = 3.75;
    private static final int MAX_CANDIDATES = 4;
    private static final int STORAGE_SIZE = 36;

    private SeedGatheringPlans() {}

    public static boolean isSource(Material material) { return SeedSources.isSource(material); }

    public static List<BlockPoint> candidates(AIPlayer ai) {
        if (ai.getWorkBudget().isExhausted() || safeHand(ai) == null) return List.of();
        var world = ai.getPlayer().getWorld();
        var eye = ai.getPlayer().getEyeLocation();
        var opacity = PerceptionSystem.opacityOf(world);
        List<BlockPoint> candidates = new ArrayList<>();
        for (var entry : ai.getMemory().all(MemoryType.SEED_SOURCE, world.getUID(), ai.getTicks())) {
            if (ai.getWorkBudget().isExhausted()) break;
            BlockPoint target = entry.pos();
            if (target.distance(ai.getPosition()) > SOURCE_RANGE || !Positions.isLoaded(world, target)) continue;
            if (!isSource(Positions.block(world, target).getType())) {
                ai.getMemory().forget(MemoryType.SEED_SOURCE, world.getUID(), target);
                continue;
            }
            if (ai.getMemory().contains(MemoryType.UNREACHABLE, world.getUID(), target, ai.getTicks())
                    || ai.getTeam().isClaimedByOther(ai, world.getUID(), target)) continue;
            if (!Visibility.canSeeBlock(opacity, eye.getX(), eye.getY(), eye.getZ(), target.x(), target.y(), target.z())) continue;
            candidates.add(target);
        }
        candidates.sort(Comparator.comparingDouble(target -> target.distanceSq(ai.getPosition())));
        return candidates.stream().limit(MAX_CANDIDATES).toList();
    }

    public static List<Action> gather(AIPlayer ai, BlockPoint target) {
        var world = ai.getPlayer().getWorld();
        if (ai.getWorkBudget().isExhausted() || target.distance(ai.getPosition()) > SOURCE_RANGE
                || !Positions.isLoaded(world, target)) return List.of();
        Material source = Positions.block(world, target).getType();
        if (!isSource(source) || ai.getTeam().isClaimedByOther(ai, world.getUID(), target)
                || ai.getMemory().contains(MemoryType.UNREACHABLE, world.getUID(), target, ai.getTicks())) return List.of();
        var eye = ai.getPlayer().getEyeLocation();
        if (!Visibility.canSeeBlock(PerceptionSystem.opacityOf(world), eye.getX(), eye.getY(), eye.getZ(),
                target.x(), target.y(), target.z())) return List.of();
        ToolChoice hand = safeHand(ai);
        if (hand == null) return List.of();
        ai.getTeam().claim(ai, target);
        return List.of(new MoveToAction(PathGoal.reach(target, REACH), false),
                new GatherSeedSourceAction(world.getUID(), target, source, hand), new PickupItemAction(6.0, false));
    }

    /** Prefer empty or non-wearing stacks; never silently let the mining policy substitute shears/Silk Touch. */
    private static ToolChoice safeHand(AIPlayer ai) {
        var inventory = ai.getPlayer().getInventory();
        ToolChoice fallback = null;
        ToolChoice nonWearing = null;
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty()) return new ToolChoice(slot, "AIR", Map.of());
            Map<String, Integer> enchantments = new HashMap<>();
            item.getEnchantments().forEach((enchantment, level) -> enchantments.put(enchantment.getKey().toString(), level));
            if (!SeedSources.allowsHand(item.getType(), enchantments)) continue;
            ToolChoice choice = new ToolChoice(slot, item.getType().name(), enchantments);
            if (fallback == null) fallback = choice;
            if (nonWearing == null && item.getType().getMaxDurability() == 0) nonWearing = choice;
        }
        return nonWearing != null ? nonWearing : fallback;
    }

    /** Source disappearance is not proof that this actor obtained seeds. Pickup and inventory observation remain separate. */
    private static final class GatherSeedSourceAction extends AbstractAction {
        private final UUID world;
        private final BlockPoint target;
        private final Material expected;
        private final ToolChoice hand;
        private Action mining;

        private GatherSeedSourceAction(UUID world, BlockPoint target, Material expected, ToolChoice hand) {
            super("GatherSeedSource", 200);
            this.world = world;
            this.target = target;
            this.expected = expected;
            this.hand = hand;
        }

        @Override protected void onStart(AIPlayer ai) {
            if (!world.equals(ai.getWorldId()) || !ai.getBody().isUsable()
                    || !Positions.isLoaded(ai.getPlayer().getWorld(), target)) {
                fail("seed source world unavailable"); return;
            }
            if (!isSource(expected) || Positions.block(ai.getPlayer().getWorld(), target).getType() != expected) {
                fail("seed source changed"); return;
            }
            Material material = Material.matchMaterial(hand.material());
            if (material == null || !SeedSources.allowsHand(material, hand.enchantments())) {
                fail("seed gathering hand unavailable"); return;
            }
            if (ai.getTeam().isClaimedByOther(ai, world, target)) { fail("seed source claimed"); return; }
            ai.getBody().inputMove(0.0F, 0.0F);
            ai.getBody().inputSprint(false);
            mining = BreakBlockAction.withTool(target, hand);
        }

        @Override protected void onTick(AIPlayer ai) {
            if (!world.equals(ai.getWorldId()) || !ai.getBody().isUsable()) {
                fail("seed source world changed or body unavailable"); return;
            }
            mining.update(ai);
            if (mining.getStatus() == ActionStatus.SUCCESS) {
                ai.getMemory().forget(MemoryType.SEED_SOURCE, world, target);
                succeed();
            } else if (mining.getStatus() == ActionStatus.FAILED) fail(mining.getFailReason());
        }

        @Override protected void onEnd(AIPlayer ai) {
            if (mining != null && mining.getStatus() == ActionStatus.RUNNING) mining.cancel(ai);
            ai.getBody().inputJump(false);
            ai.getBody().inputSneak(false);
        }
    }
}
