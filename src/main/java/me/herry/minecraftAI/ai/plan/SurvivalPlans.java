package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.AttackEntityAction;
import me.herry.minecraftAI.ai.action.BreakBlockAction;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.bukkit.entity.Player;
import me.herry.minecraftAI.ai.action.EatFoodAction;
import me.herry.minecraftAI.ai.action.EscapeHazardAction;
import me.herry.minecraftAI.ai.action.ExploreAreaAction;
import me.herry.minecraftAI.ai.action.GiveFoodAction;
import me.herry.minecraftAI.ai.action.PickupItemAction;
import me.herry.minecraftAI.ai.action.RunAwayAction;
import me.herry.minecraftAI.ai.action.WaitAction;
import me.herry.minecraftAI.ai.combat.CombatMemory;
import me.herry.minecraftAI.ai.combat.TargetRules;
import me.herry.minecraftAI.ai.memory.MemorySystem;
import me.herry.minecraftAI.ai.perception.Threat;
import me.herry.minecraftAI.ai.inventory.HandPolicy;
import me.herry.minecraftAI.ai.perception.Perception;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sheep;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 위험 대응, 전투, 음식처럼 생존과 직결된 목표의 행동 계획.
 */
public final class SurvivalPlans {
    private static final int REST_TICKS = 60;
    private static final double DROP_RADIUS = 8.0;

    private SurvivalPlans() {
    }

    static List<Action> escapeDanger(AIPlayer ai) {
        Perception perception = ai.getPerception();
        // 모래나 자갈에 묻혔으면 머리를 덮은 블록부터 캐서 숨을 쉴 수 있게 한다.
        if (perception.isSuffocating()) {
            Player player = ai.getPlayer();
            List<Action> actions = new ArrayList<>();
            BlockPoint feet = ai.getPosition();
            for (BlockPoint cell : new BlockPoint[]{feet.offset(0, 1, 0), feet}) {
                if (PerceptionSystem.isBurying(Positions.block(player.getWorld(), cell).getType())) actions.add(new BreakBlockAction(cell));
            }
            return actions;
        }
        if (perception.isInLava() || perception.isStandingInDanger() || perception.isDrowning()) {
            return List.of(new EscapeHazardAction());
        }

        LivingEntity hostile = nearestHostile(ai);
        if (hostile == null) return List.of();
        // 땅속이거나 방금 달아나지 못했으면, 달아나는 대신 파고 들어가서 숨는다.
        List<Action> refuge = RefugePlans.digIn(ai, hostile);
        if (!refuge.isEmpty()) return refuge;
        return List.of(new RunAwayAction(hostile, hostile.getLocation(), ai.getConfig().fleeDistance));
    }

    static List<Action> survive(AIPlayer ai) {
        List<Action> actions = new ArrayList<>();
        // 숨은 자리 안에서는 달아날 필요가 없다. 그 안에서 먹고 쉰다.
        LivingEntity hostile = RefugePlans.isHidden(ai) ? null : nearestHostile(ai);
        if (hostile != null && Positions.distance(hostile.getLocation(), ai.getPlayer().getLocation()) < ai.getConfig().fleeDistance) {
            actions.add(new RunAwayAction(hostile, hostile.getLocation(), ai.getConfig().fleeDistance));
        }
        if (ai.getInventory().hasFood() && ai.getPlayer().getFoodLevel() < 20) actions.add(new EatFoodAction());
        actions.add(new WaitAction(REST_TICKS));
        return actions;
    }

    static List<Action> fightHostile(AIPlayer ai) {
        LivingEntity hostile = pickTarget(ai);
        return hostile == null ? List.of() : List.of(new AttackEntityAction(hostile));
    }

    /**
     * 싸울 상대를 고른다. 가장 가까운 몬스터가 벽 너머나 다른 층에 있을 수 있으므로, 전투 판단이 상대로 친 몬스터
     * (보이거나 나를 노리는 것) 가운데서 손이 닿는 것, 방금 나를 때린 것, 가까운 것의 순서로 고른다.
     */
    private static @Nullable LivingEntity pickTarget(AIPlayer ai) {
        long now = ai.getTicks();
        CombatMemory combatMemory = ai.getCombatMemory();
        UUID attacker = ai.getMemory().getLastAttack()
                .filter(attack -> now - attack.tick() <= CombatMemory.SEEN_TICKS).map(MemorySystem.AttackRecord::attacker).orElse(null);
        List<LivingEntity> entities = new ArrayList<>();
        List<TargetRules.Candidate> candidates = new ArrayList<>();
        for (Threat threat : ai.getPerception().getThreats()) {
            LivingEntity entity = threat.entity();
            if (entity == null || !threat.type().isHostileMob() || !isUsable(ai, entity)) continue;
            UUID id = entity.getUniqueId();
            boolean engaged = threat.targetingMe() || combatMemory.seenWithin(id, now, CombatMemory.SEEN_TICKS);
            entities.add(entity);
            candidates.add(new TargetRules.Candidate(threat.distance(), engaged, id.equals(attacker), combatMemory.isUnreachable(id, now)));
        }
        int picked = TargetRules.pick(candidates);
        return picked < 0 ? null : entities.get(picked);
    }

    static List<Action> assistAlly(AIPlayer ai) {
        LivingEntity target = ai.getAssistTarget();
        return target == null ? List.of() : List.of(new AttackEntityAction(target));
    }

    // 음식을 나눠 주기로 한 동료에게 가져다준다.
    static List<Action> shareFood(AIPlayer ai) {
        AIPlayer requester = ai.getTeam().foodRequesterFor(ai);
        return requester == null ? List.of() : List.of(new GiveFoodAction(requester));
    }

    // 먹을 것을 넉넉히 모아 둔다. 사냥감이 보이면 잡고, 없으면 찾아다닌다.
    static List<Action> stockFood(AIPlayer ai) {
        // 찾아다닌 시간을 센다. 한참 찾아도 없으면 당분간 찾지 않고 하던 일을 한다 (동물이 없는 지역일 수 있다).
        if (nearestPrey(ai) != null) {
            ai.getFoodSearch().onPreyFound();
        } else {
            ai.getFoodSearch().onSearching(ai.getTicks());
            if (ai.getFoodSearch().isExhausted(ai.getTicks())) {
                ai.debug("No animals found after a long search, going on without more food");
                return List.of();
            }
        }
        return hunt(ai);
    }

    static List<Action> findFood(AIPlayer ai) {
        if (ai.getInventory().hasFood() && ai.getPlayer().getFoodLevel() < 20) return List.of(new EatFoodAction());
        return hunt(ai);
    }

    /**
     * 보이는 사냥감을 잡는다. 사냥감이 없을 때 동굴이나 깊은 굴 안이면 먼저 지상으로 올라가고(지나온 굴, 계단, 블록 쌓기 순),
     * 지상이면 돌아다니며 찾는다.
     */
    private static List<Action> hunt(AIPlayer ai) {
        LivingEntity prey = nearestPrey(ai);
        if (prey != null) {
            return List.of(new AttackEntityAction(prey, HandPolicy.Purpose.HUNT), new PickupItemAction(DROP_RADIUS, false));
        }
        if (TerrainPlans.needsToClimb(ai)) {
            List<Action> climb = TerrainPlans.climbOut(ai);
            if (!climb.isEmpty()) return climb;
        }
        return List.of(new ExploreAreaAction(16.0, 28.0));
    }

    private static @Nullable LivingEntity nearestHostile(AIPlayer ai) {
        for (LivingEntity hostile : ai.getPerception().getHostiles()) {
            if (isUsable(ai, hostile)) return hostile;
        }
        return null;
    }

    // 가장 가까운, 눈에 보이는 사냥감. 벽이나 땅 너머에 있는 동물은 보이지 않으므로 고르지 않는다.
    public static @Nullable LivingEntity nearestPrey(AIPlayer ai) {
        for (LivingEntity animal : ai.getPerception().getAnimals()) {
            if (isUsable(ai, animal) && isPrey(animal) && PerceptionSystem.canSee(ai.getPlayer(), animal)) return animal;
        }
        return null;
    }

    // 인식 이후 판단까지 사이에 죽거나 사라졌을 수 있으므로 다시 확인한다.
    private static boolean isUsable(AIPlayer ai, LivingEntity entity) {
        return entity.isValid() && !entity.isDead() && entity.getWorld().equals(ai.getPlayer().getWorld());
    }

    private static boolean isPrey(LivingEntity entity) {
        return entity instanceof Cow || entity instanceof Pig || entity instanceof Sheep
                || entity instanceof Chicken || entity instanceof Rabbit;
    }
}
