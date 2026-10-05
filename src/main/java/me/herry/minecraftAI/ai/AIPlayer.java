package me.herry.minecraftAI.ai;

import me.herry.minecraftAI.ai.brain.DecisionLog;
import me.herry.minecraftAI.ai.brain.DecisionTrace;
import me.herry.minecraftAI.ai.brain.Directive;
import me.herry.minecraftAI.ai.build.BuildJob;
import me.herry.minecraftAI.ai.combat.CombatMemory;
import me.herry.minecraftAI.ai.combat.CombatSystem;
import me.herry.minecraftAI.ai.crafting.CraftingSystem;
import me.herry.minecraftAI.ai.experience.Experience;
import me.herry.minecraftAI.ai.crafting.FurnaceJob;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.Milestone;
import me.herry.minecraftAI.ai.goal.Situation;

import me.herry.minecraftAI.ai.inventory.InventorySystem;
import me.herry.minecraftAI.ai.memory.MemorySystem;
import me.herry.minecraftAI.ai.navigation.NavigationSystem;

import me.herry.minecraftAI.ai.perception.Perception;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.perf.TickProfiler;
import me.herry.minecraftAI.ai.plan.TreeJob;
import me.herry.minecraftAI.ai.survival.FoodSearch;
import me.herry.minecraftAI.ai.survival.LightingSystem;
import me.herry.minecraftAI.ai.survival.SurvivalSystem;
import me.herry.minecraftAI.ai.team.Phrases;
import me.herry.minecraftAI.ai.team.TeamChat;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.ai.world.WorldModel;
import me.herry.minecraftAI.config.AIConfig;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * AI 플레이어 하나의 상태와, 그 AI 가 쓰는 시스템들을 묶어서 가지고 있다.
 * 행동(Action)과 계획(Planner)은 이 객체를 통해 필요한 시스템에 접근한다.
 */
public final class AIPlayer {
    private final String name;
    private final AIBody body;
    private final AIServices services;
    private final @Nullable AISkin skin;

    private final MemorySystem memory = new MemorySystem();
    private final WorldModel worldModel = new WorldModel();
    private final CombatMemory combatMemory = new CombatMemory();
    private final GoalLabel label = new GoalLabel();
    private final LightingSystem lighting = new LightingSystem();
    private final TickProfiler profiler = new TickProfiler();
    private final PerceptionSystem perception;
    private final NavigationSystem navigation;
    private final InventorySystem inventory;
    private final AIBrain brain;
    // 계속 실패해서 한동안 미뤄 둔 중기 목표와, 다시 시도할 시각(AI 틱)
    private final Map<Milestone, Long> deferredUntil = new EnumMap<>(Milestone.class);

    private AIState state = AIState.STOPPED;
    private boolean resumeAfterRespawn;
    private long ticks;
    private BlockFace digDirection;
    private @Nullable TreeJob treeJob;
    private @Nullable BuildJob buildJob;
    private @Nullable FurnaceJob furnaceJob;
    private long furnaceBlockedSince = -1L;
    private LivingEntity assistTarget;
    private long assistUntil;
    private long sleepRetryAfter;
    private final FoodSearch foodSearch = new FoodSearch();

    public AIPlayer(String name, AIBody body, AIServices services, @Nullable AISkin skin) {
        this.name = name;
        this.body = body;
        this.services = services;
        this.skin = skin;
        this.perception = new PerceptionSystem(body, services.config(), services.budget());
        this.inventory = new InventorySystem(body);
        this.navigation = new NavigationSystem(body, services.config(), this::debug, services.budget(), profiler, inventory);
        this.brain = new AIBrain(this, services);
    }

    /**
     * 서버 틱마다 한 번 호출된다. 자율 행동 중이고 몸체가 살아 있을 때만 판단하고 움직인다.
     */
    public void tick() {
        updateLabel();
        if (state != AIState.RUNNING || !body.isUsable()) return;
        brain.tick();
        ticks++;
    }

    // 닉네임 위의 목표 표시를 머리 위로 옮기고 내용을 갱신한다. 멈춰 있을 때도 따라다녀야 하므로 매 틱 호출한다.
    private void updateLabel() {
        if (!services.config().showGoalLabel) return;
        if (!body.isUsable()) {
            label.remove();
            return;
        }
        if (state == AIState.RUNNING) {
            String text = brain.getCurrentGoal().name() + (brain.getForcedGoal() != null ? " (고정)" : "");
            label.update(body.getPlayer(), text, NamedTextColor.YELLOW);
        } else {
            label.update(body.getPlayer(), state.name(), NamedTextColor.GRAY);
        }
    }

    // AI 가 서버에서 제거될 때 호출한다.
    public void dispose() {
        try {
            shutdown();
        } finally {
            label.remove();
        }
    }

    public boolean start() {
        if (state != AIState.STOPPED) return false;
        state = AIState.RUNNING;
        debug("Autonomous behavior started");
        brain.getJournal().beginEpisode(Experience.StartReason.START);
        getTeam().say(this, Phrases.hello(getTeam().teamSize()), true);
        return true;
    }

    public boolean stop() {
        if (state == AIState.DEAD) {
            // 리스폰 후에 다시 움직이지 않도록 예약만 취소한다.
            resumeAfterRespawn = false;
            return true;
        }
        if (state != AIState.RUNNING) return false;
        brain.getJournal().endEpisode(Experience.EndReason.STOPPED);
        brain.reset();
        // 멈춰 있는 동안 주변 엔티티나 월드에 대한 참조를 들고 있지 않는다.
        perception.reset();
        state = AIState.STOPPED;
        debug("Autonomous behavior stopped");
        return true;
    }

    // 서버를 다시 켠 뒤에 스스로 움직이기 시작해야 하는지. 저장할 때 쓴다.
    public boolean runsAfterRestore() {
        return state.runsAfterRestore(resumeAfterRespawn);
    }

    public void onDeath() {
        if (state == AIState.DEAD) return;
        resumeAfterRespawn = state == AIState.RUNNING;
        // 죽은 자리는 오래 기억한다. 떨어뜨린 아이템을 찾으러 가거나 위험한 곳을 피하는 데 쓴다.
        worldModel.recordDeath(getWorldId(), getPosition());
        brain.getJournal().endEpisode(Experience.EndReason.DEATH);
        brain.reset();
        treeJob = null;
        state = AIState.DEAD;
        debug("Died");
        getTeam().say(this, Phrases.died(), true);
    }

    public void onRespawn() {
        if (state != AIState.DEAD) return;
        perception.reset();
        combatMemory.reset();
        state = resumeAfterRespawn ? AIState.RUNNING : AIState.STOPPED;
        debug("Respawned");
        if (state != AIState.RUNNING) return;
        brain.getJournal().beginEpisode(Experience.StartReason.RESPAWN);
        getTeam().say(this, Phrases.respawned(), true);
    }

    public void onAttacked(Entity attacker) {
        memory.recordAttack(getWorldId(), getPosition(), attacker.getUniqueId(), ticks);
        if (attacker instanceof Enemy) combatMemory.onHit(ticks);
        brain.onAttacked();
        // 몬스터에게 맞았으면 근처의 동료에게 도움을 청한다 (동료가 있을 때만).
        if (state == AIState.RUNNING && attacker instanceof LivingEntity living && attacker instanceof Enemy) {
            getTeam().requestHelp(this, living);
        }
    }

    // 동료가 도움을 청한 상대를 일정 시간 동안 공격 대상으로 삼는다.
    public void setAssistTarget(LivingEntity target, long durationTicks) {
        assistTarget = target;
        assistUntil = ticks + durationTicks;
    }

    // 도와서 싸울 상대. 시간이 지났거나 상대가 죽었거나 다른 월드에 있으면 null.
    public @Nullable LivingEntity getAssistTarget() {
        if (assistTarget == null) return null;
        boolean usable = ticks < assistUntil && assistTarget.isValid() && !assistTarget.isDead()
                && assistTarget.getWorld().equals(body.getPlayer().getWorld());
        if (!usable) assistTarget = null;
        return assistTarget;
    }

    public LightingSystem getLighting() {
        return lighting;
    }

    public TeamChat getTeam() {
        return services.team();
    }

    // 월드가 바뀌면 진행 중이던 경로와 계획의 좌표가 의미를 잃는다.
    public void onWorldChanged() {
        if (state == AIState.RUNNING) brain.reset();
        // 차원을 넘으라고 시킨 일은 넘어온 것으로 끝났다. 그대로 두면 넘어온 자리에서 같은 목표를 계속 붙들고 있는다.
        GoalType forced = brain.getForcedGoal();
        if (forced == GoalType.ENTER_NETHER || forced == GoalType.LEAVE_NETHER) brain.setForcedGoal(null);
        perception.reset();
        digDirection = null;
        treeJob = null;
    }

    /**
     * 블록을 하나 캔 직후에 호출된다. 원목이면 그 나무를 끝까지 베는 작업을 시작하거나 이어 가고,
     * 캐서 생긴 빈칸 옆에 새로 드러난 광석을 기억한다(광맥을 따라 캐기 위해).
     */
    public void onBlockBroken(BlockPoint pos, Material type) {
        perception.noticeOpened(memory, pos, ticks);
        if (!Tag.LOGS.isTagged(type)) return;

        World world = body.getPlayer().getWorld();
        if (treeJob != null && treeJob.world().equals(world.getUID()) && treeJob.contains(pos)) {
            treeJob.onLogBroken(pos, ticks);
        } else if (treeJob == null || !treeJob.hasLogs()) {
            TreeJob started = TreeJob.start(world, pos, ticks);
            if (started != null) {
                treeJob = started;
                debug("Felling the whole tree at " + pos);
            }
        }
    }

    /**
     * 베던 나무. 다 베었거나 오래 손대지 못했으면 null. 쌓아 올린 블록 위에 서 있는 동안에는 내려올 때까지 남겨 둔다.
     */
    public @Nullable TreeJob getTreeJob() {
        if (treeJob == null) return null;
        World world = body.getPlayer().getWorld();
        if (!treeJob.world().equals(world.getUID())) {
            treeJob = null;
            return null;
        }
        treeJob.refresh(world);
        // 발판을 캐고 한 칸 떨어지는 중에는 발 위치가 발판과 맞지 않으므로 판단을 미룬다.
        if (!body.isGrounded()) return treeJob;
        BlockPoint feet = getPosition();
        if (treeJob.isStale(ticks, feet)) treeJob.abandonLogs();
        if (!treeJob.hasLogs() && treeJob.pillarUnder(feet) == null) treeJob = null;
        return treeJob;
    }

    // 짓고 있는 건물. 없으면 null.
    public @Nullable BuildJob getBuildJob() {
        return buildJob;
    }

    public void setBuildJob(@Nullable BuildJob buildJob) {
        this.buildJob = buildJob;
    }

    // 화로에 넣어 두고 온 것. 없으면 null.
    public @Nullable FurnaceJob getFurnaceJob() {
        return furnaceJob;
    }

    public void setFurnaceJob(@Nullable FurnaceJob furnaceJob) {
        this.furnaceJob = furnaceJob;
        furnaceBlockedSince = -1L;
    }

    // 넣어 둔 화로까지 길을 낼 수 없게 된 시각. 막혀 있지 않으면 음수.
    public long getFurnaceBlockedSince() {
        return furnaceBlockedSince;
    }

    public void setFurnaceBlockedSince(long furnaceBlockedSince) {
        this.furnaceBlockedSince = furnaceBlockedSince;
    }

    // 제거되기 전에 진행 중인 행동과 이동 입력을 정리한다.
    public void shutdown() {
        try {
            // 서버가 꺼지는 것이면 AIController 가 먼저 그 까닭으로 닫아 두었다. 그 밖에는 제거된 것이다.
            brain.getJournal().endEpisode(Experience.EndReason.REMOVED);
            brain.reset();
        } finally {
            state = AIState.STOPPED;
        }
    }

    public void debug(String message) {
        services.debugger().log(name, message);
    }

    public String getName() {
        return name;
    }

    public @Nullable AISkin getSkin() {
        return skin;
    }

    public AIBody getBody() {
        return body;
    }

    public Player getPlayer() {
        return body.getPlayer();
    }

    public UUID getWorldId() {
        return body.getPlayer().getWorld().getUID();
    }

    public BlockPoint getPosition() {
        return Positions.feet(body.getPlayer().getLocation());
    }

    public AIConfig getConfig() {
        return services.config();
    }

    public AIState getState() {
        return state;
    }

    public long getTicks() {
        return ticks;
    }

    // 저장해 둔 상태를 되살릴 때만 쓴다. 기억의 만료 시각이 AI 틱 기준이라 이어서 세야 한다.
    void restoreTicks(long ticks) {
        this.ticks = Math.max(0L, ticks);
    }

    public MemorySystem getMemory() {
        return memory;
    }

    public WorldModel getWorldModel() {
        return worldModel;
    }

    public TickProfiler getProfiler() {
        return profiler;
    }

    public PerceptionSystem getPerceptionSystem() {
        return perception;
    }

    public Perception getPerception() {
        return perception.getPerception();
    }

    public NavigationSystem getNavigation() {
        return navigation;
    }

    public InventorySystem getInventory() {
        return inventory;
    }

    public CraftingSystem getCrafting() {
        return services.crafting();
    }

    public CombatSystem getCombat() {
        return services.combat();
    }

    public SurvivalSystem getSurvival() {
        return services.survival();
    }

    public GoalType getCurrentGoal() {
        return brain.getCurrentGoal();
    }

    public @Nullable GoalType getForcedGoal() {
        return brain.getForcedGoal();
    }

    // null 을 넘기면 다시 스스로 목표를 고른다.
    public void setForcedGoal(@Nullable GoalType goal) {
        brain.setForcedGoal(goal);
    }

    public @Nullable Directive getDirective() {
        return brain.getDirective();
    }

    // 사람이 대화로 부탁한 일. null 이면 부탁을 취소한다.
    public void setDirective(@Nullable Directive directive) {
        brain.setDirective(directive);
    }

    public String getCurrentActionName() {
        return brain.getCurrentActionName();
    }

    // 현재 계획의 행동 목록. 실행 중인 행동은 대괄호로 표시된다.
    public String describePlan() {
        return brain.describePlan();
    }

    public boolean isEscaping() {
        return brain.isEscaping();
    }

    // 목표 명세, 계획을 세운 스킬, 마지막 관측, 학습용 기록
    public DecisionJournal getJournal() {
        return brain.getJournal();
    }

    // 지금 하고 있는 일과 그 이유. 아직 한 번도 판단하지 않았으면 null.
    public @Nullable DecisionTrace getDecision() {
        return brain.currentTrace();
    }

    public DecisionLog getDecisionLog() {
        return brain.getLog();
    }

    // 마지막으로 판단할 때 본 상황 요약. 아직 판단한 적이 없으면 null.
    public @Nullable Situation getLastSituation() {
        return brain.getLastSituation();
    }

    /**
     * 없어도 되는 중기 목표를 한동안 미뤄 둔다. 그동안은 그 뒤의 단계를 먼저 진행한다.
     */
    public void deferMilestone(Milestone milestone, long durationTicks) {
        if (milestone.isOptional()) deferredUntil.put(milestone, ticks + durationTicks);
    }

    public boolean isDeferred(Milestone milestone) {
        Long until = deferredUntil.get(milestone);
        if (until == null) return false;
        if (ticks < until) return true;
        deferredUntil.remove(milestone);
        return false;
    }

    // 이 시각(AI 틱) 전에는 다시 자러 가지 않는다. 방금 잤거나, 잘 수 없었던 밤에 침대 앞을 맴돌지 않게 한다.
    public long getSleepRetryAfter() {
        return sleepRetryAfter;
    }

    public void setSleepRetryAfter(long tick) {
        sleepRetryAfter = tick;
    }

    // 사냥감을 찾아다닌 시간. 한참 찾아도 없으면 당분간 찾지 않는다.
    public FoodSearch getFoodSearch() {
        return foodSearch;
    }

    public CombatMemory getCombatMemory() {
        return combatMemory;
    }

    // 돌을 찾아 계단식으로 파 내려갈 때 유지하는 방향
    public @Nullable BlockFace getDigDirection() {
        return digDirection;
    }

    public void setDigDirection(@Nullable BlockFace digDirection) {
        this.digDirection = digDirection;
    }
}
