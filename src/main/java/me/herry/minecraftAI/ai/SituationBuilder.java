package me.herry.minecraftAI.ai;

import me.herry.minecraftAI.ai.action.DropJunkAction;
import me.herry.minecraftAI.ai.action.PickupItemAction;
import me.herry.minecraftAI.ai.inventory.JunkPolicy;
import me.herry.minecraftAI.ai.combat.CombatMemory;
import me.herry.minecraftAI.ai.combat.CombatSystem;
import me.herry.minecraftAI.ai.combat.TargetRules;
import me.herry.minecraftAI.ai.crafting.CraftingSystem;
import me.herry.minecraftAI.ai.goal.GoalSystem;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.Milestone;
import me.herry.minecraftAI.ai.goal.ProgressFacts;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.inventory.InventorySystem;
import me.herry.minecraftAI.ai.inventory.ToolTier;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.perception.Perception;
import me.herry.minecraftAI.ai.perception.Threat;
import me.herry.minecraftAI.ai.perception.ThreatType;
import me.herry.minecraftAI.ai.plan.BuildPlans;
import me.herry.minecraftAI.ai.plan.CraftPlans;
import me.herry.minecraftAI.ai.plan.FurnacePlans;
import me.herry.minecraftAI.ai.plan.GatherPlans;
import me.herry.minecraftAI.ai.plan.HomePlans;
import me.herry.minecraftAI.ai.plan.ObsidianPlans;
import me.herry.minecraftAI.ai.plan.PortalPlans;
import me.herry.minecraftAI.ai.plan.Progression;
import me.herry.minecraftAI.ai.plan.RefugePlans;
import me.herry.minecraftAI.ai.plan.ResourceLocator;
import me.herry.minecraftAI.ai.plan.SurvivalPlans;
import me.herry.minecraftAI.ai.plan.TerrainPlans;
import me.herry.minecraftAI.ai.plan.TreeJob;
import me.herry.minecraftAI.ai.survival.SurvivalSystem;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 인식 결과, 인벤토리, 기억을 모아서 목표 선택에 쓰는 Situation 으로 요약한다.
 * Bukkit 객체를 읽는 일은 여기서 끝내고, 목표 선택은 요약된 값만 가지고 한다.
 */
final class SituationBuilder {
    private static final long FLEE_COMMIT_TICKS = 200L;
    // 도망친 뒤 이 시간 동안은 전력이 이 배수만큼 앞설 때만 다시 싸운다.
    private static final long REENGAGE_WINDOW = 1200L;
    private static final double REENGAGE_MARGIN = 1.5;
    private static final long SEEN_PRUNE_INTERVAL = 200L;
    private static final double CORNERED_RANGE = 4.0;
    private static final long CORNERED_WINDOW = 300L;
    private static final long STAND_GROUND_TICKS = 300L;
    // 달아나기 시작한 뒤에도 이 시간 안에 이만큼 맞았으면 벗어나지 못하고 있는 것이다.
    private static final long BEATEN_WINDOW = 100L;
    private static final int BEATEN_HITS = 3;
    // 이 거리 안에 있는 동료는 함께 싸우는 전력으로 친다.
    private static final double ALLY_RANGE = 16.0;
    // 크리퍼에게서 물러날 자리가 없었으면 이 시간 동안은 치고 물러나기를 하지 않고 달아난다.
    private static final long RETREAT_BLOCKED_WINDOW = 400L;

    private SituationBuilder() {
    }

    static Situation build(AIPlayer ai, GoalType currentGoal) {
        Perception perception = ai.getPerception();
        InventorySystem inventory = ai.getInventory();
        SurvivalSystem survival = ai.getSurvival();
        Situation situation = new Situation();
        situation.currentGoal = currentGoal;

        situation.health = perception.getHealth();
        situation.maxHealth = perception.getMaxHealth();
        situation.food = perception.getFood();
        situation.healthState = survival.healthState(situation.health);
        situation.shouldEat = survival.shouldEat(situation.food, situation.health, situation.maxHealth);
        situation.starving = survival.isStarving(situation.food);
        situation.canRegenerate = survival.canRegenerate(situation.food);

        situation.inLava = perception.isInLava();
        situation.standingInDanger = perception.isStandingInDanger();
        situation.suffocating = perception.isSuffocating();
        situation.allyNeedsHelp = ai.getAssistTarget() != null;
        situation.allyWantsFood = ai.getTeam().foodRequesterFor(ai) != null;
        situation.foodOnTheWay = ai.getTeam().isFoodOnTheWay(ai);
        situation.drowning = perception.isDrowning();

        // 사방이 막힌 자리에 들어가 있으면 몬스터가 닿지 못한다.
        situation.sealedIn = RefugePlans.isHidden(ai);
        if (ai.getCombatMemory().updateSealedIn(situation.sealedIn)) {
            ai.debug(situation.sealedIn ? "Sealed in at " + ai.getPosition() : "No longer sealed in");
        }
        double weaponPower = inventory.weaponPower();
        // 갑옷이 좋으면 더 많은 상대를 감당할 수 있고, 굶주렸으면 덜 감당한다.
        AttributeInstance armor = ai.getPlayer().getAttribute(Attribute.ARMOR);
        double readiness = CombatSystem.readiness(armor == null ? 0.0 : armor.getValue(), situation.food);
        // 가까이 있는 동료의 수만큼 감당할 수 있는 상대가 늘어난다 (동료가 있을 때만).
        int allies = ai.getTeam().countNearbyAllies(ai, ALLY_RANGE);
        situation.underground = TerrainPlans.needsToClimb(ai);
        fillCombat(ai, perception, situation, weaponPower * readiness * (1 + allies), weaponPower);

        situation.night = perception.isNight();
        situation.nightPolicy = survival.nightPolicy(weaponPower);

        situation.hasFood = inventory.hasFood();
        situation.foodCount = inventory.foodCount();
        // 배가 고파지기 시작하면 먼저 동료에게 나눠 달라고 청해 본다 (동료가 있을 때만).
        boolean hungry = situation.shouldEat && !situation.hasFood;
        if (hungry && !situation.foodOnTheWay) ai.getTeam().requestFood(ai);
        situation.preyNearby = SurvivalPlans.nearestPrey(ai) != null;
        situation.surfaceTooLate = SurvivalPlans.tooLateToSurface(ai);
        situation.foodSearchExhausted = ai.getFoodSearch().isExhausted(ai.getTicks());
        TreeJob tree = ai.getTreeJob();
        // 발판을 한 칸 캐고 떨어지는 중에도 아직 발판 위에 있는 것으로 본다.
        situation.climbing = tree != null && (tree.pillarUnder(ai.getPosition()) != null || !ai.getBody().isGrounded() && tree.hasPillar());
        situation.treeUnfinished = tree != null && (tree.hasLogs() || situation.climbing);
        // 나무 위 발판에 서 있을 때는 땅에 떨어진 아이템을 주우러 가지 않는다. 내려온 뒤에 줍는다.
        situation.dropsNearby = !situation.climbing && hasReachableDrop(ai, perception);
        situation.inventoryFull = inventory.isFull();
        situation.emptySlots = inventory.emptySlots();
        situation.junkSlots = JunkPolicy.junkSlots(ai.getPlayer().getInventory()).size();
        situation.rawFood = inventory.count(InventorySystem::isRawFood);
        situation.readyFood = situation.foodCount - situation.rawFood;
        situation.canCook = situation.rawFood > 0 && CraftPlans.canCook(ai);
        situation.ownTableNearby = CraftPlans.ownTableNearby(ai) != null;
        FurnacePlans.assess(ai, situation);

        fillProgress(ai, inventory, situation);
        // 거점과 상자에 대한 판단은 "지금 무엇이 부족한지"를 알아야 하므로 진행 상황 다음에 채운다.
        HomePlans.assess(ai, situation);
        return situation;
    }

    private static void fillCombat(AIPlayer ai, Perception perception, Situation situation, double weaponPower, double rawWeaponPower) {
        List<CombatSystem.Hostile> hostiles = new ArrayList<>();
        // 보이는지와 상관없이 교전 범위 안에서 감지된 몬스터 전부. 땅속에서 싸우러 나설지 정할 때 쓴다.
        List<CombatSystem.Hostile> around = new ArrayList<>();
        CombatMemory combatMemory = ai.getCombatMemory();
        long now = ai.getTicks();
        double nearest = Double.MAX_VALUE;
        ThreatType nearestType = null;
        boolean shotFromOutOfReach = false;
        for (Threat threat : perception.getThreats()) {
            if (!threat.type().isHostileMob() || threat.entity() == null) continue;
            if (threat.distance() <= ai.getConfig().engageRange) {
                around.add(new CombatSystem.Hostile(threat.type(), threat.distance(), threat.targetingMe()));
            }
            // 벽 너머나 땅속 동굴에 있어서 보이지 않고 AI 를 노리지도 않는 몬스터는 상대하지 않는다.
            // 다만 방금까지 보이던 몬스터는 잠깐 가려져도 계속 상대로 친다.
            UUID id = threat.entity().getUniqueId();
            if (threat.targetingMe() || isVisible(ai, threat)) combatMemory.markSeen(id, now);
            else if (!combatMemory.seenWithin(id, now, CombatMemory.SEEN_TICKS)) continue;
            // 숨은 자리 안에서는 누구도 닿지 못한다. 다가갈 길이 없었던 상대도 이쪽으로 오지 못하므로 상대로 치지 않는다.
            // 다만 닿지 않는 곳에서 활을 쏘는 상대는 맞설 수 없으니 피해야 한다.
            if (situation.sealedIn) continue;
            if (TargetRules.isOutOfReach(combatMemory.isUnreachable(id, now), threat.distance())) {
                if (threat.type() == ThreatType.SKELETON && threat.targetingMe()) shotFromOutOfReach = true;
                continue;
            }
            hostiles.add(new CombatSystem.Hostile(threat.type(), threat.distance(), threat.targetingMe()));
            if (threat.distance() <= ai.getConfig().fleeDistance) situation.hostileNearby = true;
            if (threat.distance() < nearest) {
                nearest = threat.distance();
                nearestType = threat.type();
            }
        }

        if (now % SEEN_PRUNE_INTERVAL == 0) combatMemory.prune(now, CombatMemory.SEEN_TICKS);

        // 왼손에 방패를 들고 있으면 크리퍼의 폭발을 막을 수 있다. 방패가 없어도 쓸 만한 무기가 있으면 치고 물러나기를 되풀이한다.
        boolean canFaceBlast = ai.getPlayer().getInventory().getItemInOffHand().getType() == Material.SHIELD
                || CombatSystem.canHitAndRun(rawWeaponPower, combatMemory.retreatBlockedWithin(now, RETREAT_BLOCKED_WINDOW));
        // 이미 상대하던 중이면 교전 범위를 조금 넓게 본다. 경계에 걸친 몬스터 때문에 판단이 매번 뒤바뀌지 않게 한다.
        boolean engagedBefore = combatMemory.wasEngaged();
        CombatSystem.Decision fightAssessment = ai.getCombat().decide(situation.health, situation.maxHealth, weaponPower, hostiles, canFaceBlast, engagedBefore);
        CombatSystem.Decision decision = fightAssessment;
        // 도망치던 중에 체력이 조금 회복됐다고 바로 다시 덤비면, 다가갔다가 맞고 물러나기를 반복하게 된다.
        // 한번 도망쳤으면 얼마 동안은 물러나 있고, 그 뒤에도 확실히 이길 수 있을 때만 다시 싸운다.
        if (decision == CombatSystem.Decision.FIGHT) {
            if (combatMemory.isCommittedToFlee(now)) {
                decision = CombatSystem.Decision.FLEE;
            } else if (combatMemory.fledWithin(now, REENGAGE_WINDOW)) {
                decision = ai.getCombat().decide(situation.health, situation.maxHealth, weaponPower / REENGAGE_MARGIN, hostiles, canFaceBlast, engagedBefore);
            }
        }
        // 땅속에서는 보이는 한두 마리를 잡으러 나서면 그 주변의 몬스터가 모두 이쪽을 보고 몰려온다.
        // 보이지 않는 것까지 합쳐서 감당할 수 없으면 나서지 않고, 몬스터가 아직 멀리 있을 때 물러나 숨는다.
        boolean outnumbered = fightAssessment == CombatSystem.Decision.FIGHT && situation.underground
                && ai.getCombat().isOutnumbered(situation.health, situation.maxHealth, weaponPower, around);
        if (outnumbered) decision = CombatSystem.Decision.FLEE;
        if (shotFromOutOfReach) decision = CombatSystem.Decision.FLEE;
        if (decision == CombatSystem.Decision.FLEE) combatMemory.onFlee(now, FLEE_COMMIT_TICKS);
        combatMemory.trackFleeing(decision == CombatSystem.Decision.FLEE, now);
        // 도망치려 했지만 벗어나지 못했고 적이 바로 옆에 있으면, 맞기만 하느니 맞서 싸운다.
        // 달아나는 중에도 계속 맞고 있으면(숨으려고 판 구덩이에 좀비가 따라 들어온 경우 등) 벗어나지 못한 것으로 본다.
        // 한번 맞서기로 했으면 적이 한 걸음 멀어졌다고 다시 도망치지 않고 얼마 동안은 계속 싸운다.
        boolean stuckFleeing = ai.getMemory().countRecentFailures("RunAway", now, CORNERED_WINDOW) > 0
                || combatMemory.hitsWhileFleeing(now, BEATEN_WINDOW) >= BEATEN_HITS;
        // 지금 전력으로 감당할 수 없으면, 이전의 궁지 대응 기억으로 도주 판단을 뒤집지 않는다.
        boolean canStandGround = CombatSystem.canStandGround(fightAssessment, outnumbered, shotFromOutOfReach, hostiles);
        boolean cornered = nearest <= CORNERED_RANGE && canStandGround && stuckFleeing;
        if (decision == CombatSystem.Decision.FLEE && cornered) combatMemory.onStandGround(now, STAND_GROUND_TICKS);
        if (decision == CombatSystem.Decision.FLEE && canStandGround && combatMemory.isStandingGround(now)) {
            decision = CombatSystem.Decision.FIGHT;
        }
        situation.combat = decision;

        if (combatMemory.updateLastDecision(decision)) {
            ai.debug("Combat decision: " + decision + " (hostiles=" + hostiles.size()
                    + (hostiles.isEmpty() ? "" : ", nearest=" + nearestType + " at " + (int) nearest)
                    + ", health=" + (int) situation.health + ", weapon=" + weaponPower
                    + (outnumbered ? ", outnumbered by " + around.size() + " around" : "") + ")");
        }
    }

    private static boolean isVisible(AIPlayer ai, Threat threat) {
        LivingEntity entity = threat.entity();
        if (entity == null || !entity.isValid() || !entity.getWorld().equals(ai.getPlayer().getWorld())) return false;
        // 시야 확인은 블록을 따라 광선을 쏘는 계산이라, 교전 범위 밖의 몬스터에게까지 할 필요는 없다.
        if (threat.distance() > ai.getConfig().engageRange) return false;
        return ai.getPlayer().hasLineOfSight(entity);
    }

    // 주울 수 없다고 판단했던 아이템(나무 위 등)은 빼고 본다.
    private static boolean hasReachableDrop(AIPlayer ai, Perception perception) {
        for (Item drop : perception.getDrops()) {
            if (!drop.isValid() || !PickupItemAction.isInRange(perception.getLocation(), drop.getLocation(), GatherPlans.LOOSE_DROP_RADIUS)) continue;
            if (ai.getTeam().isTeammateCloser(ai, drop.getLocation()) || DropJunkAction.isDiscarded(drop)) continue;
            BlockPoint block = Positions.of(drop.getLocation());
            if (!ai.getMemory().contains(MemoryType.UNREACHABLE, ai.getWorldId(), block, ai.getTicks())) return true;
        }
        return false;
    }

    private static void fillProgress(AIPlayer ai, InventorySystem inventory, Situation situation) {
        situation.hasPickaxe = inventory.bestTier(Tag.ITEMS_PICKAXES) != ToolTier.NONE;
        situation.plankEquivalent = inventory.plankEquivalent();
        situation.cobblestone = inventory.count(Tag.ITEMS_STONE_TOOL_MATERIALS);
        situation.rawIron = inventory.count(Material.RAW_IRON);
        situation.ironIngots = inventory.count(Material.IRON_INGOT);
        situation.hasFuel = inventory.hasFuel();
        situation.coal = inventory.count(Material.COAL) + inventory.count(Material.CHARCOAL);
        situation.torches = inventory.count(Material.TORCH);
        situation.knowsCoal = GatherPlans.knowsOre(ai, MemoryType.COAL_ORE);
        situation.knowsLootChest = ResourceLocator.locate(ai, MemoryType.LOOT_CHEST, GatherPlans.ORE_WALK_RANGE) != null;
        // 횃불은 가진 재료로 바로 만들 수 있을 때만 만든다.
        situation.canCraftTorch = situation.coal > 0 && situation.torches < GoalSystem.TORCH_MIN
                && ai.getCrafting().plan(Material.TORCH, 4, inventory.snapshot()).isFeasible();
        situation.knowsTree = ResourceLocator.knows(ai, MemoryType.TREE);
        situation.knowsIron = GatherPlans.knowsOre(ai, MemoryType.IRON_ORE);
        situation.canMineIron = inventory.bestTier(Tag.ITEMS_PICKAXES).isAtLeast(ToolTier.STONE);
        situation.coalVeinNearby = situation.hasPickaxe && GatherPlans.veinNearby(ai, MemoryType.COAL_ORE);
        situation.ironVeinNearby = situation.canMineIron && GatherPlans.veinNearby(ai, MemoryType.IRON_ORE);
        situation.canMineDiamond = inventory.bestTier(Tag.ITEMS_PICKAXES).isAtLeast(ToolTier.IRON);
        situation.knowsDiamond = situation.canMineDiamond && GatherPlans.knowsOre(ai, MemoryType.DIAMOND_ORE);
        situation.diamonds = inventory.count(Material.DIAMOND);
        situation.gravel = inventory.count(Material.GRAVEL);
        situation.knowsGravel = ResourceLocator.locate(ai, MemoryType.GRAVEL, GatherPlans.ORE_WALK_RANGE) != null;
        situation.emptyBucket = inventory.has(Material.BUCKET);
        situation.knowsWater = situation.emptyBucket
                && ResourceLocator.locate(ai, MemoryType.WATER_SOURCE, GatherPlans.ORE_WALK_RANGE) != null;
        situation.waterBucket = inventory.has(Material.WATER_BUCKET);
        situation.flintAndSteel = inventory.has(Material.FLINT_AND_STEEL);
        situation.inNether = ai.getPlayer().getWorld().getEnvironment() == World.Environment.NETHER;
        situation.knowsPortal = PortalPlans.knowsPortalHere(ai);
        situation.canMineObsidian = inventory.bestTier(Tag.ITEMS_PICKAXES).isAtLeast(ToolTier.DIAMOND);
        situation.obsidianWork = situation.canMineObsidian && ObsidianPlans.isWorking(ai);
        situation.knowsLava = situation.waterBucket && situation.canMineObsidian && ObsidianPlans.knowsLake(ai);
        // 좋은 곡괭이만 있고 막 쓸 돌 곡괭이가 없으면, 가진 재료로 만들 수 있을 때 하나 만든다.
        situation.workPickaxeWanted = situation.canMineDiamond && !inventory.hasWorkPickaxe()
                && ai.getCrafting().plan(Material.STONE_PICKAXE, 1, inventory.snapshot()).isFeasible();

        // 중기 목표(다음에 이룰 것)와 장기 목표(지금 속한 단계)를 정한다.
        BuildPlans.checkCompletion(ai);
        PortalPlans.checkCompletion(ai);
        ProgressFacts facts = Progression.facts(ai);
        situation.stage = Progression.stageOf(facts);
        fillMilestone(ai, inventory, situation, Progression.next(facts));
        // 땅속에서 밤을 나는 동안에는, 지상에 올라가야 하는 선택 항목(나무가 드는 방패, 집 짓기)을 건너뛰고 그다음 것을 준비한다.
        // 아침이 되거나 지상에 올라오면 건너뛴 항목으로 돌아간다.
        while (GoalSystem.leavesForMorning(situation)) {
            facts.defer(situation.nextMilestone);
            fillMilestone(ai, inventory, situation, Progression.next(facts));
        }
    }

    // 다음에 이룰 것과, 그것에 지금 부족한 재료를 채운다.
    private static void fillMilestone(AIPlayer ai, InventorySystem inventory, Situation situation, @Nullable Milestone milestone) {
        situation.nextMilestone = milestone;
        situation.need = Situation.Need.NONE;
        if (milestone == null) return;
        situation.ironNeeded = milestone.ironCost();
        situation.diamondsNeeded = milestone.diamondCost();

        if (milestone == Milestone.SHELTER) {
            BuildPlans.assess(ai, situation);
        } else if (milestone.kind() == Milestone.Kind.CRAFT) {
            CraftingSystem.CraftPlan plan = ai.getCrafting().plan(Progression.materialOf(milestone), 1, inventory.snapshot());
            situation.need = plan.isFeasible() ? Situation.Need.NONE : classifyNeed(plan.missing());
        } else {
            // 모으거나 월드에서 이뤄야 하는 일. 그 일을 하는 목표가 따로 맡는다.
            situation.need = Situation.Need.OTHER;
        }
    }

    // 나무가 없으면 돌 도구도 만들 수 없으므로 나무 부족을 먼저 본다.
    private static Situation.Need classifyNeed(Set<Material> missing) {
        boolean stone = false;
        boolean iron = false;
        boolean diamond = false;
        boolean flint = false;
        for (Material material : missing) {
            if (Tag.LOGS.isTagged(material) || Tag.PLANKS.isTagged(material) || material == Material.STICK) return Situation.Need.WOOD;
            if (Tag.ITEMS_STONE_TOOL_MATERIALS.isTagged(material) || material == Material.COBBLESTONE) stone = true;
            if (material == Material.IRON_INGOT) iron = true;
            if (material == Material.DIAMOND) diamond = true;
            if (material == Material.FLINT) flint = true;
        }
        if (stone) return Situation.Need.STONE;
        if (iron) return Situation.Need.IRON;
        if (diamond) return Situation.Need.DIAMOND;
        return flint ? Situation.Need.FLINT : Situation.Need.OTHER;
    }
}
