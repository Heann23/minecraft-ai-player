package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIBody;
import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.combat.ShieldRules;
import me.herry.minecraftAI.ai.combat.TargetRules;
import me.herry.minecraftAI.ai.inventory.HandPolicy;
import me.herry.minecraftAI.ai.memory.MemorySystem;
import me.herry.minecraftAI.ai.navigation.BlockClass;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

/**
 * 대상에게 다가가서 죽을 때까지 공격한다. 몬스터와의 전투와 동물 사냥에 함께 쓴다.
 */
public final class AttackEntityAction extends AbstractAction implements PrimitiveAction {
    private static final int TIMEOUT = 600;
    private static final double ATTACK_RANGE = 3.0;
    private static final double GIVE_UP_DISTANCE = 40.0;
    // 싸우는 동안 유지하려는 거리의 범위
    private static final double KEEP_MIN = 2.0;
    private static final double KEEP_MAX = 2.7;
    private static final double STEP_BACK_CHECK = 1.0;
    // 다가갈 길이 없을 때 상대가 사거리 안으로 오기를 기다리는 시간
    private static final int MAX_HOLD_TICKS = 100;
    private static final float FACING_TOLERANCE = 25.0F;
    // 공격 쿨타임이 거의 다 찼을 때만 휘둘러야 피해가 제대로 들어간다.
    private static final float READY_COOLDOWN = 0.9F;
    private static final int MAX_TICKS_WITHOUT_HIT = 100;
    // 크리퍼는 7칸 넘게 떨어지면 부풀기를 멈춘다.
    private static final double CREEPER_SAFE_DISTANCE = 7.5;
    private static final float BACK_AWAY_FACING = 45.0F;
    // 방패를 든 동안에는 평소의 5분의 1 속도로만 걸을 수 있다 (실제 플레이어와 같다).
    private static final float BLOCKING_PACE = 0.2F;

    // 싸우는 도중에 더 가까이에서 때린 몬스터가 있으면 그쪽으로 바뀐다.
    private LivingEntity target;
    private final HandPolicy.Purpose purpose;
    private final boolean exactTarget;
    private EntityChaser chaser;
    private int ticksWithoutHit;
    private boolean holding;
    private int holdTicks;
    private boolean shielding;
    // 상대가 활을 당기는 것을 마지막으로 본 틱. 본 적이 없으면 음수.
    private long lastDrawTick = -1L;
    private boolean backingAway;
    private long startTick;

    public AttackEntityAction(LivingEntity target) {
        this(target, HandPolicy.Purpose.FIGHT);
    }

    public AttackEntityAction(LivingEntity target, HandPolicy.Purpose purpose) {
        this(target, purpose, false);
    }

    private AttackEntityAction(LivingEntity target, HandPolicy.Purpose purpose, boolean exactTarget) {
        super("AttackEntity", TIMEOUT);
        this.target = target;
        this.purpose = purpose;
        this.exactTarget = exactTarget;
        this.chaser = newChaser();
    }

    /** Keeps the chosen entity identity even when another monster attacks during execution. */
    public static AttackEntityAction exactTarget(LivingEntity target) {
        return new AttackEntityAction(target, HandPolicy.Purpose.FIGHT, true);
    }

    // 몬스터는 급하게 쫓고, 사냥감은 허기를 아끼며 쫓는다.
    private EntityChaser newChaser() {
        return new EntityChaser(purpose == HandPolicy.Purpose.FIGHT);
    }

    @Override
    protected void onStart(AIPlayer ai) {
        startTick = ai.getTicks();
        int weaponSlot = ai.getInventory().bestWeaponSlot(purpose);
        // 쓸 무기가 없으면 맨손으로 친다. 들고 있던 곡괭이로 치면 칠 때마다 내구도가 2 씩 줄어든다.
        if (weaponSlot >= 0) ai.getInventory().equipSlot(weaponSlot);
        else ai.getInventory().restHand();
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (exactTarget && !ai.getBody().isUsable()) {
            fail("body unavailable");
            return;
        }
        if (target.isDead()) {
            succeed();
            return;
        }
        if (!target.isValid() || !target.getWorld().equals(player.getWorld())) {
            fail("target disappeared");
            return;
        }
        if (Positions.distance(player.getLocation(), target.getLocation()) > GIVE_UP_DISTANCE) {
            fail("target too far");
            return;
        }
        // 아직 손이 닿지 않는 상대를 쫓거나 기다리는 동안 더 가까운 몬스터에게 맞았으면 그쪽으로 돌아선다.
        // 행동을 실패로 끝내고 다시 계획하게 하면, 둘러싸였을 때 맞을 때마다 실패가 쌓여서 한 대도 치지 못한다.
        if (!exactTarget && purpose == HandPolicy.Purpose.FIGHT) {
            LivingEntity attacker = closerAttacker(ai, player);
            if (attacker != null) switchTo(ai, player, attacker);
        }

        // 터지려는 크리퍼나 활을 당기는 스켈레톤 앞에서는 치는 것을 멈추고 방패를 든다. 지나가면 방패를 내리고 다시 친다.
        if (hasShield(player)) {
            String danger = dangerToBlock(ai, player);
            if (danger != null) {
                raiseShield(ai, player, danger);
                return;
            }
            lowerShield(player);
        } else if (target instanceof Creeper creeper) {
            // 방패가 없으면 부푸는 크리퍼에게서 뒷걸음으로 물러났다가, 부풀기를 멈추면 다시 다가가서 친다.
            if (creeper.getFuseTicks() > 0) {
                backAway(ai, player);
                return;
            }
            backingAway = false;
        }

        if (holding) {
            holdGround(ai, player);
            return;
        }

        EntityChaser.Result result = chaser.tick(ai, target, ATTACK_RANGE);
        if (result == EntityChaser.Result.UNREACHABLE) {
            // 다가갈 길이 없으면(구덩이 안 등) 쫓아가기를 그만두고, 상대가 오기를 기다렸다가 맞받아친다.
            holding = true;
            ai.getNavigation().stop();
        } else if (result == EntityChaser.Result.IN_RANGE) {
            fight(ai, player);
        }
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        lowerShield(ai.getPlayer());
        ai.getNavigation().stop();
        if (exactTarget) ai.getBody().clearInputs();
    }

    // 이 행동을 시작한(또는 상대를 바꾼) 뒤에 나를 때린 몬스터 가운데, 지금 상대 대신 상대해야 할 것. 없으면 null.
    private @Nullable LivingEntity closerAttacker(AIPlayer ai, Player player) {
        MemorySystem.AttackRecord attack = ai.getMemory().getLastAttack().orElse(null);
        if (attack == null || attack.tick() <= startTick || attack.attacker().equals(target.getUniqueId())) return null;
        if (!(Bukkit.getEntity(attack.attacker()) instanceof LivingEntity attacker) || !(attacker instanceof Enemy)) return null;
        if (!attacker.isValid() || attacker.isDead() || !attacker.getWorld().equals(player.getWorld())) return null;
        double current = Positions.distance(player.getLocation(), target.getLocation());
        double other = Positions.distance(player.getLocation(), attacker.getLocation());
        return TargetRules.shouldSwitch(current, other, ATTACK_RANGE) ? attacker : null;
    }

    private void switchTo(AIPlayer ai, Player player, LivingEntity attacker) {
        // 기다리던 상대는 다가갈 길이 없었던 것이므로, 다음에 다시 고르지 않도록 적어 둔다.
        if (holding) ai.getCombatMemory().markUnreachable(target.getUniqueId(), ai.getTicks());
        lowerShield(player);
        ai.getNavigation().stop();
        ai.debug("Turning to the " + attacker.getType() + " that hit me");
        target = attacker;
        chaser = newChaser();
        holding = false;
        holdTicks = 0;
        ticksWithoutHit = 0;
        backingAway = false;
        lastDrawTick = -1L;
        startTick = ai.getTicks();
    }

    private static boolean hasShield(Player player) {
        return player.getInventory().getItemInOffHand().getType() == Material.SHIELD;
    }

    // 지금 방패로 막아야 하는 것의 이름. 막을 것이 없으면 null.
    private @Nullable String dangerToBlock(AIPlayer ai, Player player) {
        if (target instanceof Creeper creeper) return ShieldRules.blocksBlast(creeper.getFuseTicks()) ? "creeper" : null;
        if (!(target instanceof AbstractSkeleton)) return null;

        long now = ai.getTicks();
        // 벽 너머에서 당기고 있는 활은 쏘지 못하므로 막을 필요가 없다.
        boolean drawing = target.isHandRaised() && player.hasLineOfSight(target);
        if (drawing) lastDrawTick = now;
        long sinceDrawing = lastDrawTick < 0L ? Long.MAX_VALUE : now - lastDrawTick;
        return ShieldRules.blocksArrow(drawing, target.getActiveItemUsedTime(), sinceDrawing) ? "arrow" : null;
    }

    /**
     * 상대를 바라보고 방패를 든다. 방패는 바라보는 쪽에서 오는 폭발과 화살을 막아 준다.
     * 화살을 막을 때는 방패를 든 채로 천천히 다가간다. 서 있기만 하면 상대가 활을 당길 때마다 발이 묶여서,
     * 스파이더 조키처럼 빠르게 움직이는 상대와의 거리가 줄지 않는다. 터지려는 크리퍼에게는 다가가지 않는다.
     */
    private void raiseShield(AIPlayer ai, Player player, String danger) {
        AIBody body = ai.getBody();
        ai.getNavigation().stop();
        Vector center = target.getBoundingBox().getCenter();
        body.lookAt(center.getX(), center.getY(), center.getZ());
        Location location = player.getLocation();
        boolean advance = !(target instanceof Creeper) && location.distance(target.getLocation()) > KEEP_MAX
                && body.isFacing(center.getX(), center.getY(), center.getZ(), FACING_TOLERANCE) && canStep(player, location, true);
        body.inputMove(advance ? BLOCKING_PACE : 0.0F, 0.0F);
        body.inputSprint(false);
        body.inputJump(player.isInWater());
        if (!shielding) {
            player.startUsingItem(EquipmentSlot.OFF_HAND);
            shielding = true;
            ai.debug("Raising the shield against the " + danger);
        }
    }

    private void lowerShield(Player player) {
        if (!shielding) return;
        player.clearActiveItem();
        shielding = false;
    }

    private void holdGround(AIPlayer ai, Player player) {
        if (Positions.distance(player.getLocation(), target.getLocation()) <= ATTACK_RANGE) {
            holdTicks = 0;
            fight(ai, player);
            return;
        }
        if (++holdTicks > MAX_HOLD_TICKS) {
            // 같은 상대를 다시 고르지 않도록 적어 둔다. 고르지 않으면 닿지 않는 상대만 바라보다가 다른 몬스터에게 맞는다.
            ai.getCombatMemory().markUnreachable(target.getUniqueId(), ai.getTicks());
            fail("unreachable");
            return;
        }
        Vector center = target.getBoundingBox().getCenter();
        ai.getBody().lookAt(center.getX(), center.getY(), center.getZ());
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputJump(player.isInWater());
    }

    private void fight(AIPlayer ai, Player player) {
        AIBody body = ai.getBody();
        Vector center = target.getBoundingBox().getCenter();
        body.lookAt(center.getX(), center.getY(), center.getZ());

        Location location = player.getLocation();
        double distance = location.distance(target.getLocation());
        // 플레이어의 공격 거리(3칸)가 몬스터보다 길다. 그 차이를 살려서, 너무 붙으면 한 걸음 물러나고 멀어지면 다가가서
        // 상대의 공격은 닿지 않고 내 공격만 닿는 거리를 유지한다. 두 기준 사이에서는 움직이지 않아서 앞뒤로 떨지 않는다.
        float forward = 0.0F;
        if (distance > KEEP_MAX) forward = 1.0F;
        else if (distance < KEEP_MIN && canStep(player, location, false)) forward = -1.0F;
        body.inputMove(forward, 0.0F);
        body.inputSprint(false);
        body.inputJump(player.isInWater());

        if (strikeIfReady(body, player, center)) {
            ticksWithoutHit = 0;
        } else if (++ticksWithoutHit > MAX_TICKS_WITHOUT_HIT) {
            // 가까이 있지만 벽 너머라 때릴 수 없는 상대를 붙잡고 서 있지 않는다.
            fail("cannot hit target");
        }
    }

    // 조준이 맞고 공격 쿨타임이 찼으면 친다. 쳤으면 true.
    private boolean strikeIfReady(AIBody body, Player player, Vector center) {
        boolean aimed = body.isFacing(center.getX(), center.getY(), center.getZ(), FACING_TOLERANCE);
        if (!aimed || player.getAttackCooldown() < READY_COOLDOWN || !player.hasLineOfSight(target)) return false;
        player.swingMainHand();
        player.attack(target);
        player.resetCooldown();
        return true;
    }

    /**
     * 상대를 바라본 채 뒷걸음으로 물러난다. 부풀던 크리퍼는 7칸 넘게 떨어지면 부풀기를 멈추고 다시 걸어온다.
     * 물러날 자리가 없으면 뒷걸음으로는 벗어날 수 없으므로 실패로 끝내고, 돌아서서 달아나도록 전투 판단에 알린다.
     */
    private void backAway(AIPlayer ai, Player player) {
        AIBody body = ai.getBody();
        ai.getNavigation().stop();
        Vector center = target.getBoundingBox().getCenter();
        body.lookAt(center.getX(), center.getY(), center.getZ());
        body.inputSprint(false);
        body.inputJump(player.isInWater());
        if (!backingAway) {
            backingAway = true;
            ai.debug("Backing away from the creeper");
        }

        Location location = player.getLocation();
        double distance = location.distance(target.getLocation());
        // 물러나기 시작할 때 손이 닿으면 한 대 치고 물러난다. 맞은 크리퍼는 뒤로 밀려난다.
        if (distance <= ATTACK_RANGE) strikeIfReady(body, player, center);
        if (distance > CREEPER_SAFE_DISTANCE) {
            body.inputMove(0.0F, 0.0F);
            return;
        }
        if (!canStep(player, location, false)) {
            ai.getCombatMemory().onRetreatBlocked(ai.getTicks());
            fail("no room to back away");
            return;
        }
        // 몸이 상대 쪽으로 돌아선 뒤에 뒷걸음을 쳐야 상대에게서 멀어진다.
        boolean facing = body.isFacing(center.getX(), center.getY(), center.getZ(), BACK_AWAY_FACING);
        body.inputMove(facing ? -1.0F : 0.0F, 0.0F);
    }

    // 상대 쪽으로(toward) 또는 반대쪽으로 한 걸음 옮길 자리가 안전한지 (막혀 있지 않고, 낭떠러지나 용암이 아닌지) 확인한다.
    private boolean canStep(Player player, Location location, boolean toward) {
        Vector away = location.toVector().subtract(target.getLocation().toVector()).setY(0.0);
        if (away.lengthSquared() < 1.0E-6) return false;
        away.normalize().multiply(toward ? -STEP_BACK_CHECK : STEP_BACK_CHECK);
        int x = (int) Math.floor(location.getX() + away.getX());
        int z = (int) Math.floor(location.getZ() + away.getZ());
        int y = Positions.feet(location).y();
        World world = player.getWorld();
        if (y - 1 < world.getMinHeight() || y + 1 >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) return false;

        return BukkitTerrainView.classify(world.getBlockAt(x, y, z).getType()) == BlockClass.OPEN
                && BukkitTerrainView.classify(world.getBlockAt(x, y + 1, z).getType()) == BlockClass.OPEN
                && BukkitTerrainView.classify(world.getBlockAt(x, y - 1, z).getType()) == BlockClass.SOLID;
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.ATTACK;
    }

    @Override
    public PrimitiveTarget getTarget() {
        return new PrimitiveTarget.Entity(target.getType().name(), target.getUniqueId().toString());
    }
}
