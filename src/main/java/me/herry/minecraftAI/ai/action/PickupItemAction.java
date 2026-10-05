package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIBody;
import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.navigation.NavigationSystem;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.plan.TerrainPlans;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * 주변에 떨어진 아이템으로 걸어가서 줍는다. 아이템은 가까이 가면 자동으로 주워진다.
 */
public final class PickupItemAction extends AbstractAction implements PrimitiveAction {
    private static final int TIMEOUT = 400;
    private static final int PER_ITEM_TICKS = 100;
    private static final long UNREACHABLE_TTL = 1200L;
    // 이 거리 안의 아이템이 블록에 막혀 있으면 그 블록을 캐서 길을 낸다.
    private static final double CLEAR_RANGE = 4.5;
    private static final int MAX_CLEARS = 3;
    // 한 아이템 때문에 긴 굴을 새로 파지 않는다. 기존 길 내기로 한 단씩, 최대 세 번만 접근한다.
    private static final int MAX_ACCESS_STEPS = 3;
    private static final int REPATH_INTERVAL = 10;
    private static final double EXACT_RADIUS = 0.5;
    // 아이템은 머리 위 반 칸, 옆으로 한 칸까지 주워지므로 이 정도까지만 다가가도 주울 수 있는 경우가 많다.
    private static final double LOOSE_RADIUS = 2.3;

    private final double radius;
    private final boolean requireItem;
    private Item current;
    private int itemTicks;
    private boolean walkingDirectly;
    private boolean looseApproach;
    private boolean foundAny;
    private Material watchedMaterial;
    private int watchedCount;
    private int collectedItems;
    private String abandonedReason;
    private BlockPoint pathTarget;
    private int lastRepath;
    private BreakBlockAction clearing;
    private int clears;
    private List<Action> access = List.of();
    private int accessIndex;
    private int accessSteps;

    /**
     * @param requireItem true 면 처음부터 주울 아이템이 없을 때 실패로 처리한다.
     *                    남아 있는 아이템까지 접근을 포기하고 하나도 얻지 못한 경우에는 항상 실패한다.
     *                    다른 플레이어가 주웠거나 사라진 아이템은 접근 실패로 세지 않는다.
     */
    public PickupItemAction(double radius, boolean requireItem) {
        super("PickupItem", TIMEOUT);
        this.radius = radius;
        this.requireItem = requireItem;
    }

    /**
     * 이 행동이 주울 수 있는 범위 안에 아이템이 있는지. 목표를 고를 때와 실제로 주울 때 같은 기준을 써야 한다.
     */
    public static boolean isInRange(Location player, Location item, double radius) {
        if (!Positions.sameWorld(player, item)) return false;
        return Math.abs(item.getX() - player.getX()) <= radius
                && Math.abs(item.getY() - player.getY()) <= radius
                && Math.abs(item.getZ() - player.getZ()) <= radius;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        ai.getBody().inputSprint(false);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        noteCollection(ai);
        if (ai.getInventory().isFull()) {
            succeed();
            return;
        }

        if (current == null || !current.isValid() || !current.getWorld().equals(player.getWorld())) {
            if (current != null) ai.debug("Dropped " + watchedMaterial + " is no longer available");
            cancelAccess(ai);
            current = findNearest(ai, player);
            if (current == null) {
                if (collectedItems == 0 && abandonedReason != null) fail("no items collected: " + abandonedReason);
                else if (requireItem && !foundAny) fail("nothing to pick up");
                else succeed();
                return;
            }
            foundAny = true;
            clears = 0;
            accessSteps = 0;
            watchedMaterial = current.getItemStack().getType();
            watchedCount = countOwned(ai, watchedMaterial);
            approach(ai);
        }

        if (!access.isEmpty()) {
            Action action = access.get(accessIndex);
            action.update(ai);
            if (action.getStatus() == ActionStatus.FAILED) {
                String reason = action.getFailReason();
                cancelAccess(ai);
                giveUp(ai, "access blocked (" + reason + ")");
            } else if (action.getStatus() == ActionStatus.SUCCESS && ++accessIndex == access.size()) {
                access = List.of();
                accessIndex = 0;
                approach(ai);
            }
            return;
        }

        // 아이템 앞을 막은 블록을 치우는 중이면 그것부터 끝낸다.
        if (clearing != null) {
            clearing.update(ai);
            if (clearing.getStatus() == ActionStatus.SUCCESS) {
                clearing = null;
                approach(ai);
            } else if (clearing.getStatus() == ActionStatus.FAILED) {
                String reason = clearing.getFailReason();
                clearing = null;
                giveUp(ai, "blocked (" + reason + ")");
            }
            return;
        }

        // 드롭은 떨어지거나 물에 떠밀려 움직인다. 처음 본 빈칸까지 가는 경로를 계속 따라가지 않는다.
        BlockPoint itemPosition = Positions.of(current.getLocation());
        if (!itemPosition.equals(pathTarget) && getElapsed() - lastRepath >= REPATH_INTERVAL) {
            ai.debug("Dropped item moved to " + itemPosition + ", updating the path");
            approach(ai);
        }

        if (++itemTicks > PER_ITEM_TICKS) {
            if (!clearWay(ai, player)) giveUp(ai, "took too long");
            return;
        }
        if (walkingDirectly) {
            if (ai.getBody().isBlockedHorizontally() && itemTicks > REPATH_INTERVAL && clearWay(ai, player)) return;
            walkTo(ai, player, current.getLocation());
            return;
        }

        NavigationSystem navigation = ai.getNavigation();
        navigation.tick();
        if (navigation.getState() == NavigationSystem.State.ARRIVED) {
            walkingDirectly = true;
        } else if (navigation.getState() == NavigationSystem.State.FAILED) {
            // 아이템이 있는 칸에 설 수 없으면 (블록 틈, 반블록 위 등) 최대한 가까이 가서 직접 걸어 본다.
            if (looseApproach) {
                String reason = navigation.getFailReason();
                if (!clearWay(ai, player)) giveUp(ai, "no path (" + reason + ")");
            } else {
                looseApproach = true;
                navigation.navigateTo(PathGoal.arrive(Positions.of(current.getLocation()), LOOSE_RADIUS));
            }
        }
    }

    // 아이템을 향해 처음부터 다시 다가간다.
    private void approach(AIPlayer ai) {
        itemTicks = 0;
        walkingDirectly = false;
        looseApproach = false;
        pathTarget = Positions.of(current.getLocation());
        lastRepath = getElapsed();
        ai.debug("Approaching dropped " + current.getItemStack().getType() + " at " + pathTarget);
        // 아이템이 있는 바로 그 칸까지 간다. 옆 칸이나 한 칸 위에서 멈추면 아이템이 주워지는 범위에 들지 않을 수 있다.
        ai.getNavigation().navigateTo(PathGoal.arrive(pathTarget, EXACT_RADIUS));
    }

    /**
     * 가까이 있는 아이템까지 길이 없으면, 아이템 쪽을 막고 있는 블록을 캐서 길을 낸다.
     * 치울 블록이 없거나 이미 여러 번 치웠으면 false.
     */
    private boolean clearWay(AIPlayer ai, Player player) {
        // 몬스터를 피해 숨은 지 얼마 안 됐고 몬스터가 아직 주변에 있으면, 아이템 하나 때문에 벽을 캐지 않는다.
        // 막아 둔 블록을 캐서 숨은 자리를 몬스터 쪽으로 연 일이 있었다.
        if (ai.getCombatMemory().isWaryAfterRefuge(ai.getTicks()) && !ai.getPerception().getHostiles().isEmpty()) return false;
        Location eye = player.getEyeLocation();
        Location item = current.getLocation().add(0.0, 0.25, 0.0);
        Vector direction = item.toVector().subtract(eye.toVector());
        double distance = direction.length();
        if (clears < MAX_CLEARS && distance >= 0.5 && distance <= CLEAR_RANGE) {
            RayTraceResult hit = player.getWorld().rayTraceBlocks(eye, direction.normalize(), distance, FluidCollisionMode.NEVER, true);
            if (hit != null && hit.getHitBlock() != null && canClear(ai, hit.getHitBlock())) {
                clears++;
                ai.getNavigation().stop();
                ai.debug("Breaking " + hit.getHitBlock().getType() + " to reach a dropped item");
                clearing = new BreakBlockAction(Positions.of(hit.getHitBlock()));
                return true;
            }
        }

        // 눈에는 보여도 발 앞의 낮은 벽이나 막힌 몸통 때문에 닿지 않을 수 있다.
        // 새 탐색을 만들지 않고 기존 계단·굴·다리 계획의 안전 검사와 실제 행동을 한 단씩 사용한다.
        if (accessSteps >= MAX_ACCESS_STEPS || !ai.getBody().isGrounded()
                || !isInRange(player.getLocation(), current.getLocation(), radius)) return false;
        List<Action> step = TerrainPlans.stepToward(ai, Positions.of(current.getLocation()), false);
        if (step.isEmpty()) return false;
        for (Action action : step) {
            if (action instanceof BreakBlockAction breaking && breaking.getTarget() instanceof PrimitiveTarget.Block target
                    && !canClear(ai, Positions.block(player.getWorld(), target.pos()))) return false;
        }
        accessSteps++;
        ai.getNavigation().stop();
        access = step;
        accessIndex = 0;
        ai.debug("Making one step toward a dropped item at " + Positions.of(current.getLocation()));
        return true;
    }

    private static boolean canClear(AIPlayer ai, Block obstacle) {
        // 부족한 도구로 돌을 맨손 채굴하거나 기반암을 계속 두드리지 않는다.
        return obstacle.getType().getHardness() >= 0.0F
                && (ai.getInventory().bestToolSlot(obstacle) >= 0 || obstacle.isPreferredTool(ItemStack.empty()));
    }

    private void cancelAccess(AIPlayer ai) {
        if (clearing != null) clearing.cancel(ai);
        clearing = null;
        if (!access.isEmpty()) access.get(accessIndex).cancel(ai);
        access = List.of();
        accessIndex = 0;
        ai.getNavigation().stop();
    }

    private void noteCollection(AIPlayer ai) {
        if (watchedMaterial == null) return;
        int owned = countOwned(ai, watchedMaterial);
        // 횃불 설치·음식 사용·장비 교체가 전체 개수를 줄여도 실제로 얻은 종류의 증가분은 남긴다.
        if (owned > watchedCount) collectedItems += owned - watchedCount;
        watchedCount = owned;
    }

    private static int countOwned(AIPlayer ai, Material material) {
        int count = 0;
        for (ItemStack stack : ai.getPlayer().getInventory().getContents()) {
            if (stack != null && !stack.isEmpty() && stack.getType() == material) count += stack.getAmount();
        }
        return count;
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        noteCollection(ai);
        cancelAccess(ai);
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputJump(false);
        ai.getBody().inputSneak(false);
        if (foundAny) ai.debug("Picked up " + collectedItems + " item(s)");
    }

    // 닿을 수 없는 아이템(나무 위 등)은 잠시 기억해 두고 다시 주우러 가지 않는다.
    private void giveUp(AIPlayer ai, String reason) {
        ai.debug("Could not pick up " + current.getItemStack().getType() + " at " + Positions.of(current.getLocation()) + ": " + reason);
        ai.getMemory().remember(MemoryType.UNREACHABLE, ai.getWorldId(), Positions.of(current.getLocation()), ai.getTicks(), UNREACHABLE_TTL);
        abandonedReason = reason;
        cancelAccess(ai);
        current = null;
        watchedMaterial = null;
    }

    private Item findNearest(AIPlayer ai, Player player) {
        Location location = player.getLocation();
        Item nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof Item item) || !item.isValid() || !isInRange(location, item.getLocation(), radius)) continue;
            if (DropJunkAction.isDiscarded(item)) continue;
            BlockPoint block = Positions.of(item.getLocation());
            if (ai.getMemory().contains(MemoryType.UNREACHABLE, ai.getWorldId(), block, ai.getTicks())) continue;
            if (ai.getTeam().isTeammateCloser(ai, item.getLocation())) continue;
            double distance = item.getLocation().distanceSquared(location);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = item;
            }
        }
        return nearest;
    }

    // 경로의 끝에서 아이템까지 남은 한두 걸음을 직접 걷는다.
    private void walkTo(AIPlayer ai, Player player, Location target) {
        AIBody body = ai.getBody();
        Location location = player.getLocation();
        double dx = target.getX() - location.getX();
        double dz = target.getZ() - location.getZ();
        boolean close = dx * dx + dz * dz < 0.09;
        if (!close) body.inputLook(Positions.yawTo(dx, dz), 0.0F);
        boolean facing = close || Math.abs(Positions.angleDifference(player.getLocation().getYaw(), Positions.yawTo(dx, dz))) < 50.0F;
        body.inputMove(close || !facing ? 0.0F : 1.0F, 0.0F);
        body.inputJump(player.isInWater() || !close && facing && body.isGrounded() && body.isBlockedHorizontally());
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.PICKUP_ITEM;
    }

    @Override
    public PrimitiveTarget getTarget() {
        return new PrimitiveTarget.Area(radius);
    }
}
