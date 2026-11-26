package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIBody;
import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.memory.MemorySystem;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.navigation.NavigationSystem;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * 주변에 떨어진 아이템으로 걸어가서 줍는다. 아이템은 가까이 가면 자동으로 주워진다.
 */
public final class PickupItemAction extends AbstractAction implements PrimitiveAction {
    private static final int TIMEOUT = 400;
    private static final int PER_ITEM_TICKS = 100;
    private static final long UNREACHABLE_TTL = 1200L;
    static final long EXACT_UNREACHABLE_TTL = 200L;
    private static final int DIRECT_WALK_TIMEOUT = 40;
    // 이 거리 안의 아이템이 블록에 막혀 있으면 그 블록을 캐서 길을 낸다.
    private static final double CLEAR_RANGE = 4.5;
    private static final int MAX_CLEARS = 3;
    private static final double EXACT_RADIUS = 0.5;
    // 아이템은 머리 위 반 칸, 옆으로 한 칸까지 주워지므로 이 정도까지만 다가가도 주울 수 있는 경우가 많다.
    private static final double LOOSE_RADIUS = 2.3;
    public static final double EXACT_PICKUP_RANGE = 8;

    private final double radius;
    private final boolean requireItem;
    private final boolean allowClearing;
    private final PrimitiveCommand.PickupExact selected;
    private Item current;
    private int itemTicks;
    private boolean walkingDirectly;
    private int directWalkTicks;
    private boolean looseApproach;
    private boolean foundAny;
    private int itemsBefore = -1;
    private BreakBlockAction clearing;
    private int clears;

    /**
     * @param requireItem true 면 주울 아이템이 하나도 없을 때 실패로 처리한다.
     *                    아이템을 주우려고 세운 계획이 헛돌았다는 것을 알려서 같은 목표가 반복되지 않게 한다.
     */
    public PickupItemAction(double radius, boolean requireItem) {
        this(radius, requireItem, true, null);
    }

    private PickupItemAction(double radius, boolean requireItem, boolean allowClearing, PrimitiveCommand.PickupExact selected) {
        super("PickupItem", TIMEOUT);
        this.radius = radius;
        this.requireItem = requireItem;
        this.allowClearing = allowClearing;
        this.selected = selected;
    }

    /** Controlled pickup never invents mining steps to reach a drop. */
    public static PickupItemAction direct(double radius, boolean requireItem) {
        return new PickupItemAction(radius, requireItem, false, null);
    }

    public static PickupItemAction exact(PrimitiveCommand.PickupExact selected) {
        return new PickupItemAction(EXACT_PICKUP_RANGE, true, false, selected);
    }

    public static boolean isUnreachable(AIPlayer ai, BlockPoint block) {
        return isUnreachable(ai.getMemory(), ai.getWorldId(), block, ai.getTicks());
    }

    static boolean isUnreachable(MemorySystem memory, UUID worldId, BlockPoint block, long tick) {
        return memory.contains(MemoryType.UNREACHABLE, worldId, block, tick);
    }

    static void markUnreachable(MemorySystem memory, UUID worldId, BlockPoint block, long tick, boolean allowClearing) {
        memory.remember(MemoryType.UNREACHABLE, worldId, block, tick, allowClearing ? UNREACHABLE_TTL : EXACT_UNREACHABLE_TTL);
    }

    public static String checkExact(AIPlayer ai, PrimitiveCommand.PickupExact selection) {
        if (!selection.world().equals(ai.getWorldId())) return "drop world changed";
        if (!(Bukkit.getEntity(selection.entityId()) instanceof Item item) || !matches(ai, item, selection))
            return "selected drop unavailable";
        var player = ai.getPlayer();
        if (!isInRange(player.getLocation(), item.getLocation(), EXACT_PICKUP_RANGE)
                || !PerceptionSystem.canSee(player, item)) return "drop outside visible pickup range";
        if (isUnreachable(ai, Positions.of(item.getLocation()))) return "drop temporarily unreachable";
        return ai.getBody().canStoreDrop(item) ? "" : "no compatible inventory space";
    }

    private static boolean matches(AIPlayer ai, Item item, PrimitiveCommand.PickupExact selection) {
        var owner = item.getOwner();
        return item.isValid() && item.getWorld().equals(ai.getPlayer().getWorld())
                && item.getItemStack().getType().name().equals(selection.material()) && !DropJunkAction.isDiscarded(item)
                && (owner == null || owner.equals(ai.getPlayer().getUniqueId()));
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
        itemsBefore = ai.getInventory().count(material -> true);
        if (selected != null) {
            String blocked = checkExact(ai, selected);
            if (!blocked.isEmpty()) { fail(blocked); return; }
            current = (Item) Bukkit.getEntity(selected.entityId());
            foundAny = true;
            ai.getBody().watchItemPickup(selected.entityId());
            approach(ai);
        }
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (selected != null) {
            if (!selected.world().equals(ai.getWorldId()) || !ai.getBody().isUsable()) {
                fail("pickup world or body changed"); return;
            }
            if (ai.getBody().pickedUpWatchedItem()) { succeed(); return; }
            if (!matches(ai, current, selected) || !ai.getBody().canStoreDrop(current)) {
                fail("selected drop no longer available"); return;
            }
            // A drop can fall into a different cell while navigation is still approaching it.
            var goal = ai.getNavigation().getGoal();
            if (!walkingDirectly && goal != null && !goal.target().equals(Positions.of(current.getLocation()))) {
                ai.getNavigation().navigateTo(PathGoal.arrive(
                        Positions.of(current.getLocation()), looseApproach ? LOOSE_RADIUS : EXACT_RADIUS));
            }
        } else if (ai.getInventory().isFull()) {
            if (!allowClearing && ai.getInventory().count(material -> true) <= itemsBefore) fail("inventory full without collecting an item");
            else succeed();
            return;
        }

        if (current == null || !current.isValid() || !current.getWorld().equals(player.getWorld())) {
            current = findNearest(ai, player);
            if (current == null) {
                if (!allowClearing && requireItem && ai.getInventory().count(material -> true) <= itemsBefore) fail("no item collected");
                else if (requireItem && !foundAny) fail("nothing to pick up");
                else succeed();
                return;
            }
            foundAny = true;
            clears = 0;
            if (clearing != null) {
                clearing.cancel(ai);
                clearing = null;
            }
            approach(ai);
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

        if (++itemTicks > PER_ITEM_TICKS) {
            if (!clearWay(ai, player)) giveUp(ai, "took too long");
            return;
        }
        if (walkingDirectly) {
            if (++directWalkTicks > DIRECT_WALK_TIMEOUT) {
                if (!clearWay(ai, player)) giveUp(ai, "took too long");
                return;
            }
            walkTo(ai, player, current.getLocation());
            return;
        }

        NavigationSystem navigation = ai.getNavigation();
        navigation.tick();
        if (navigation.getState() == NavigationSystem.State.ARRIVED) {
            walkingDirectly = true;
            directWalkTicks = 0;
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
        directWalkTicks = 0;
        looseApproach = false;
        // 아이템이 있는 바로 그 칸까지 간다. 옆 칸이나 한 칸 위에서 멈추면 아이템이 주워지는 범위에 들지 않을 수 있다.
        ai.getNavigation().navigateTo(PathGoal.arrive(Positions.of(current.getLocation()), EXACT_RADIUS));
    }

    /**
     * 가까이 있는 아이템까지 길이 없으면, 아이템 쪽을 막고 있는 블록을 캐서 길을 낸다.
     * 치울 블록이 없거나 이미 여러 번 치웠으면 false.
     */
    private boolean clearWay(AIPlayer ai, Player player) {
        if (!allowClearing) return false;
        if (clears >= MAX_CLEARS) return false;
        // 몬스터를 피해 숨은 지 얼마 안 됐고 몬스터가 아직 주변에 있으면, 아이템 하나 때문에 벽을 캐지 않는다.
        // 막아 둔 블록을 캐서 숨은 자리를 몬스터 쪽으로 연 일이 있었다.
        if (ai.getCombatMemory().isWaryAfterRefuge(ai.getTicks()) && !ai.getPerception().getHostiles().isEmpty()) return false;
        Location eye = player.getEyeLocation();
        Location item = current.getLocation().add(0.0, 0.25, 0.0);
        Vector direction = item.toVector().subtract(eye.toVector());
        double distance = direction.length();
        if (distance < 0.5 || distance > CLEAR_RANGE) return false;

        RayTraceResult hit = player.getWorld().rayTraceBlocks(eye, direction.normalize(), distance, FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitBlock() == null) return false;
        // 돌처럼 맞는 도구가 있어야 하는 블록을 맨손으로 깨려면 몇 초씩 걸린다. 아이템 하나 때문에 그럴 가치는 없다.
        Block obstacle = hit.getHitBlock();
        if (ai.getInventory().bestToolSlot(obstacle) < 0 && !obstacle.isPreferredTool(ItemStack.empty())) return false;

        clears++;
        ai.getNavigation().stop();
        ai.debug("Breaking " + hit.getHitBlock().getType() + " to reach a dropped item");
        clearing = new BreakBlockAction(Positions.of(hit.getHitBlock()));
        return true;
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        if (selected != null) ai.getBody().watchItemPickup(null);
        if (clearing != null) clearing.cancel(ai);
        ai.getNavigation().stop();
        if (selected != null && getStatus() == ActionStatus.SUCCESS) {
            ai.debug("Collected selected drop: entity=" + selected.entityId() + " material=" + selected.material());
        } else if (selected == null && foundAny && itemsBefore >= 0) {
            ai.debug("Picked up " + (ai.getInventory().count(material -> true) - itemsBefore) + " item(s)");
        }
    }

    // 닿을 수 없는 아이템(나무 위 등)은 잠시 기억해 두고 다시 주우러 가지 않는다.
    private void giveUp(AIPlayer ai, String reason) {
        BlockPoint block = Positions.of(current.getLocation());
        ai.debug("Could not pick up " + current.getItemStack().getType() + " at " + block + ": " + reason);
        markUnreachable(ai.getMemory(), ai.getWorldId(), block, ai.getTicks(), allowClearing);
        ai.getNavigation().stop();
        current = null;
        if (!allowClearing) fail(reason);
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
        body.inputMove(close ? 0.0F : 1.0F, 0.0F);
        body.inputJump(player.isInWater());
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.PICKUP_ITEM;
    }

    @Override
    public PrimitiveTarget getTarget() {
        return selected == null ? new PrimitiveTarget.Area(radius)
                : new PrimitiveTarget.Entity("ITEM", selected.entityId().toString());
    }
}
