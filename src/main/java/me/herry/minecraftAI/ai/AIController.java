package me.herry.minecraftAI.ai;

import me.herry.minecraftAI.ai.combat.CombatSystem;
import me.herry.minecraftAI.ai.comm.CommunicationHub;
import me.herry.minecraftAI.ai.comm.IncomingMessage;
import me.herry.minecraftAI.ai.comm.MessageSource;
import me.herry.minecraftAI.ai.crafting.CraftingSystem;
import me.herry.minecraftAI.ai.experience.Experience;
import me.herry.minecraftAI.ai.experience.ExperienceSink;
import me.herry.minecraftAI.ai.experience.JsonlExperienceWriter;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.perf.WorkBudget;
import me.herry.minecraftAI.ai.plan.Planner;
import me.herry.minecraftAI.ai.survival.SurvivalSystem;
import me.herry.minecraftAI.ai.team.TeamChat;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.ai.world.PortalTracker;
import me.herry.minecraftAI.config.AIConfig;
import me.herry.minecraftAI.nms.FakePlayerBody;
import me.herry.minecraftAI.persist.AISnapshot;
import me.herry.minecraftAI.persist.AIStateStore;
import me.herry.minecraftAI.view.InventoryViews;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * 모든 AI 플레이어의 생성, 제거, 틱 실행, 저장과 복원을 관리한다.
 */
public final class AIController {
    public enum SpawnResult { OK, INVALID_NAME, NAME_TAKEN, LIMIT_REACHED, FAILED }

    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    // 죽은 자리는 10분 동안 위험한 곳으로 기억한다.
    private static final long DEATH_PLACE_TTL = 12000L;
    // 사람의 말로 AI 에게 일을 시킬 수 있는 권한
    public static final String COMMAND_PERMISSION = "minecraftai.admin";

    private static final class Entry {
        final AIPlayer ai;
        final FakePlayerBody body;
        // 우리가 직접 제거하는 중인지. 제거 과정에서 발생하는 퇴장 이벤트를 구분하기 위한 표시다.
        boolean removing;

        Entry(AIPlayer ai, FakePlayerBody body) {
            this.ai = ai;
            this.body = body;
        }
    }

    private final JavaPlugin plugin;
    private final AIConfig config;
    private final CraftingSystem crafting = new CraftingSystem();
    private final CommunicationHub hub;
    private final TeamChat team;
    private final WorkBudget budget;
    private final AIServices services;
    private final me.herry.minecraftAI.ai.learned.PpoBridge trainingBridge;
    private final SkinFetcher skins;
    private final InventoryViews views = new InventoryViews();
    private final @Nullable AIStateStore store;
    // 학습용 기록을 파일로 쓰는 곳. 기록을 꺼 두었으면 null.
    private final @Nullable JsonlExperienceWriter experience;

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    // 틱을 도는 중에 목록이 바뀌어도 안전하도록, 생성/제거 때마다 새로 만드는 복사본을 순회한다.
    private List<Entry> tickList = List.of();
    private @Nullable BukkitTask tickTask;
    private long serverTicks;
    private long lastSaveTick;
    private long policyControlGeneration;

    /**
     * @param store AI 상태를 저장할 곳. null 이면 저장하지 않는다.
     */
    public AIController(JavaPlugin plugin, AIConfig config, AIDebugger debugger, CommunicationHub hub, @Nullable AIStateStore store) {
        this(plugin, config, debugger, hub, store, null);
    }

    public AIController(JavaPlugin plugin, AIConfig config, AIDebugger debugger, CommunicationHub hub, @Nullable AIStateStore store,
                        @Nullable java.nio.file.Path managedChannel) {
        this.plugin = plugin;
        this.config = config;
        this.hub = hub;
        this.store = store;
        this.team = new TeamChat(this::getAll, hub);
        this.budget = new WorkBudget(config.tickBudgetNanos);
        this.experience = !config.recordExperience ? null : new JsonlExperienceWriter(
                plugin.getDataFolder().toPath().resolve("training-data"), config.experienceMaxBytes, plugin.getLogger()::warning);
        this.trainingBridge = loadTrainingBridge(managedChannel);
        this.services = new AIServices(config, debugger, crafting,
                new CombatSystem(config.engageRange, config.criticalHealth),
                new SurvivalSystem(config.lowHealth, config.criticalHealth, config.eatBelow),
                new Planner(), team, budget, experience == null ? ExperienceSink.NONE : experience,
                plugin.getPluginMeta().getVersion(), plugin.getLogger()::warning, loadLearnedPolicy(), trainingBridge);
        this.skins = new SkinFetcher(plugin.getLogger());
        crafting.warmUp();
    }

    private me.herry.minecraftAI.ai.learned.PpoBridge loadTrainingBridge(@Nullable java.nio.file.Path managedChannel) {
        if (!config.trainingControl) return null;
        try {
            var bridge = new me.herry.minecraftAI.ai.learned.PpoBridge(managedChannel == null
                    ? plugin.getDataFolder().toPath().resolve(config.trainingChannel) : managedChannel);
            bridge.setAvailable(!config.managedBackend);
            return bridge;
        }
        catch (java.io.IOException failure) {
            plugin.getLogger().warning("Training bridge unavailable; no Teacher fallback: " + failure.getMessage()); return null;
        }
    }

    private me.herry.minecraftAI.ai.learned.PolicyWeights loadLearnedPolicy() {
        if (!config.learnedControl || config.trainingControl) return null;
        try {
            var model = me.herry.minecraftAI.ai.learned.PolicyWeights.load(plugin.getDataFolder().toPath().resolve(config.learnedModelFile));
            plugin.getLogger().info("Learned policy: " + model.sha256() + ", Paper environment steps=" + model.environmentSteps());
            return model;
        } catch (java.io.IOException | RuntimeException failure) {
            plugin.getLogger().warning("Learned policy unavailable; autonomous execution will stay idle. No Teacher fallback. " + failure.getMessage());
            return null;
        }
    }

    // 이 이름으로 AI 를 만들 수 있는지 확인한다. 만들 수 있으면 OK.
    public SpawnResult checkSpawn(String name, Location location) {
        if (!VALID_NAME.matcher(name).matches()) return SpawnResult.INVALID_NAME;
        if (entries.containsKey(key(name)) || Bukkit.getPlayerExact(name) != null) return SpawnResult.NAME_TAKEN;
        if (entries.size() >= config.maxCount) return SpawnResult.LIMIT_REACHED;
        if (location.getWorld() == null) return SpawnResult.FAILED;
        return SpawnResult.OK;
    }

    /**
     * @param skin 입힐 스킨. null 이면 기본 스킨이다.
     */
    public SpawnResult spawn(String name, Location location, @Nullable AISkin skin) {
        SpawnResult check = checkSpawn(name, location);
        if (check != SpawnResult.OK) return check;

        FakePlayerBody body;
        try {
            body = FakePlayerBody.spawn(name, Positions.standingSpot(location), config.viewDistance, skin);
        } catch (RuntimeException | LinkageError e) {
            // 서버 내부 코드가 바뀐 버전에서 실행하면 여기서 실패할 수 있다.
            plugin.getLogger().log(Level.SEVERE, "Failed to spawn AI player " + name, e);
            return SpawnResult.FAILED;
        }

        AIPlayer ai = new AIPlayer(name, body, services, skin);
        entries.put(key(name), new Entry(ai, body));
        hub.join(new Conversation(ai));
        refreshTickList();
        return SpawnResult.OK;
    }

    /**
     * 다른 마인크래프트 계정의 스킨을 입혀서 AI 를 만든다.
     * 스킨 정보는 모장 서버에서 받아 와야 해서 시간이 걸리므로, 받아 오는 일은 다른 스레드에서 하고 생성은 메인 스레드에서 한다.
     *
     * @param callback 생성 결과와, 스킨을 실제로 찾았는지 여부를 메인 스레드에서 돌려받는다.
     */
    public void spawnWithSkin(String name, Location location, String skinOwner, BiConsumer<SpawnResult, Boolean> callback) {
        skins.fetch(skinOwner).whenComplete((found, error) -> {
            AISkin skin = error == null ? found : null;
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(spawn(name, location, skin), skin != null));
        });
    }

    /**
     * 사람의 채팅을 AI 들에게 전한다. 채팅 이벤트는 다른 스레드에서 오므로 메인 스레드로 넘겨서 처리한다.
     * 일을 시킬 권한이 있는지도 메인 스레드에서 확인한다.
     */
    public void handlePlayerChat(UUID senderId, String senderName, String message) {
        if (entries.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player sender = Bukkit.getPlayer(senderId);
            // AI 끼리 주고받는 말에는 반응하지 않는다.
            if (sender == null || getByEntity(sender) != null) return;
            hub.receive(IncomingMessage.inGame(senderName, message, sender.hasPermission(COMMAND_PERMISSION)));
        });
    }

    /**
     * 관리자 명령(/ai say)으로 AI 한 명에게 직접 말을 건다. 명령을 쓸 수 있는 사람이므로 일을 시킬 수도 있다.
     */
    public void tell(AIPlayer ai, String senderName, String message) {
        hub.receive(new IncomingMessage(MessageSource.SYSTEM, senderName, ai.getName() + " " + message, false, true));
    }

    /**
     * 관리자가 AI 를 제거한다. 저장해 둔 상태도 함께 지워서, 서버를 재시작해도 되살아나지 않는다.
     */
    public boolean remove(String name) {
        Entry entry = entries.remove(key(name));
        if (entry == null) return false;
        discard(entry);
        if (store != null) store.delete(entry.ai.getName());
        refreshTickList();
        return true;
    }

    public @Nullable AIPlayer get(String name) {
        Entry entry = entries.get(key(name));
        return entry == null ? null : entry.ai;
    }

    public List<AIPlayer> getAll() {
        List<AIPlayer> result = new ArrayList<>(entries.size());
        for (Entry entry : entries.values()) result.add(entry.ai);
        return result;
    }

    public @Nullable AIPlayer getByEntity(Entity entity) {
        if (!(entity instanceof Player player)) return null;
        Entry entry = entries.get(key(player.getName()));
        // 이름이 같은 진짜 플레이어와 혼동하지 않도록 UUID 까지 확인한다.
        return entry != null && sameId(entry, player.getUniqueId()) ? entry.ai : null;
    }

    public int count() {
        return entries.size();
    }

    // 학습용 기록을 쓰는 곳. 기록을 꺼 두었으면 null.
    public @Nullable JsonlExperienceWriter getExperienceWriter() {
        return experience;
    }

    public WorkBudget getBudget() {
        return budget;
    }

    /**
     * AI 의 인벤토리를 읽기 전용 창으로 연다. 창은 열려 있는 동안 계속 새로 고쳐진다.
     */
    public void openInventory(Player viewer, AIPlayer ai) {
        views.open(viewer, ai);
    }

    /**
     * AI 가 죽었을 때 호출된다. 잠시 뒤에 리스폰 지점에서 되살린다.
     */
    public void handleDeath(Player player) {
        Entry entry = entryOf(player);
        if (entry == null) return;

        AIPlayer ai = entry.ai;
        ai.getMemory().remember(MemoryType.DANGER_PLACE, player.getWorld().getUID(), Positions.of(player.getLocation()), ai.getTicks(), DEATH_PLACE_TTL);
        ai.onDeath();
        // 가진 것을 떨어뜨린 직후에 저장해 둔다. 죽기 전의 저장이 남아 있으면, 서버가 갑자기 꺼졌다 켜졌을 때
        // 바닥에 떨어진 아이템과 되살린 인벤토리에 같은 아이템이 둘 다 있게 된다.
        // 사망 이벤트가 끝난 뒤에야 인벤토리가 비워지므로 다음 틱에 저장한다.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (entries.get(key(ai.getName())) == entry) save(entry, true, "death");
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> respawn(entry), config.respawnDelay);
    }

    // 명령어가 아닌 다른 이유(킥 등)로 AI 가 서버에서 나갔을 때 남은 상태를 정리한다.
    public void handleQuit(Player player) {
        Entry entry = entryOf(player);
        if (entry == null || entry.removing) return;
        entries.remove(key(player.getName()));
        views.closeAll(entry.ai);
        team.forget(entry.ai.getName());
        hub.leave(entry.ai.getName());
        entry.ai.dispose();
        if (store != null) store.delete(entry.ai.getName());
        refreshTickList();
        plugin.getLogger().info("AI player " + player.getName() + " left the server and was unregistered.");
    }

    // 킥 이벤트가 처리되는 도중에 플레이어를 내보내면 안 되므로 다음 틱에 제거한다.
    public void handleKick(Player player) {
        Entry entry = entryOf(player);
        if (entry == null || entry.removing) return;
        String name = entry.ai.getName();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (entries.get(key(name)) == entry && remove(name)) {
                plugin.getLogger().info("AI player " + name + " was kicked and has been removed.");
            }
        });
    }

    public void handleRecipesChanged() {
        crafting.clearCache();
    }

    public void handleWorldChange(Player player) {
        Entry entry = entryOf(player);
        if (entry == null) return;
        entry.ai.onWorldChanged();
        PortalTracker.onArrival(entry.ai);
    }

    /**
     * 저장해 둔 AI 들을 되살린다. 서버가 켜진 뒤(월드가 모두 로드된 뒤)에 한 번 호출한다.
     *
     * @return 되살린 AI 의 수
     */
    public int restoreAll() {
        if (store == null) return 0;
        int restored = 0;
        for (AISnapshot snapshot : store.loadAll()) {
            if (restore(snapshot)) restored++;
        }
        return restored;
    }

    private boolean restore(AISnapshot snapshot) {
        World world = snapshot.world == null ? null : Bukkit.getWorld(snapshot.world);
        Location location;
        if (world != null) {
            location = new Location(world, snapshot.x, snapshot.y, snapshot.z, snapshot.yaw, snapshot.pitch);
        } else {
            // 저장할 때 있던 월드가 없어졌으면 기본 월드의 스폰 지점에서 되살린다.
            List<World> worlds = Bukkit.getWorlds();
            if (worlds.isEmpty()) return false;
            location = worlds.getFirst().getSpawnLocation();
            plugin.getLogger().warning("World of AI player " + snapshot.name + " no longer exists, restoring at the default spawn.");
        }

        boolean hasSkin = snapshot.skinValue != null && snapshot.skinSignature != null;
        AISkin skin = hasSkin ? new AISkin(snapshot.skinValue, snapshot.skinSignature) : null;
        SpawnResult result = spawn(snapshot.name, location, skin);
        if (result != SpawnResult.OK) {
            plugin.getLogger().warning("Could not restore AI player " + snapshot.name + ": " + result);
            return false;
        }
        AIPlayer ai = get(snapshot.name);
        if (ai == null) return false;
        try {
            AISnapshots.restore(ai, snapshot, plugin.getLogger());
        } catch (RuntimeException e) {
            // 저장 내용 일부를 읽지 못해도 AI 자체는 살려 둔다.
            plugin.getLogger().log(Level.WARNING, "Could not fully restore the state of AI player " + snapshot.name, e);
        }
        boolean restarted = snapshot.running && ai.start();
        plugin.getLogger().info("Restored AI player " + snapshot.name + (restarted ? " (running)" : " (stopped)"));
        if (snapshot.running && !restarted) plugin.getLogger().warning("Saved AI could not resume; no policy was substituted: " + snapshot.name);
        return true;
    }

    /**
     * 모든 AI 의 상태를 저장한다.
     *
     * @param async true 면 디스크에 쓰는 일만 다른 스레드에서 한다. 서버가 꺼지는 중에는 false 로 불러야 한다.
     */
    public void saveAll(boolean async) {
        saveAll(async, "requested");
    }

    private void saveAll(boolean async, String why) {
        if (store == null) return;
        lastSaveTick = serverTicks;
        for (Entry entry : entries.values()) save(entry, async, why);
    }

    private void save(Entry entry, boolean async, String why) {
        if (store == null || entry.removing || entry.body.isRemoved()) return;
        try {
            Runnable write = store.prepareSave(AISnapshots.capture(entry.ai));
            if (async) Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
            else write.run();
            entry.ai.debug("State saved (" + why + ")");
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save AI player " + entry.ai.getName(), e);
        }
    }

    /**
     * 서버가 월드를 저장할 때 AI 상태도 함께 저장한다. 따로따로 저장하면 그 사이에 옮긴 아이템이
     * 서버가 갑자기 꺼졌을 때 양쪽에 다 남거나(복제) 양쪽에서 다 사라질 수 있다.
     * 월드마다 이벤트가 한 번씩 오므로 같은 틱에는 한 번만 저장한다.
     */
    public void handleWorldSave() {
        if (entries.isEmpty() || lastSaveTick == serverTicks) return;
        saveAll(true, "world save");
    }

    /**
     * 공유 두뇌의 실행 전이를 확인한 뒤 같은 가중치의 학습 여부만 비동기로 변경한다.
     */
    public void changeLearning(boolean enabled, BiConsumer<Boolean, String> completed) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("brain control must start on server thread");
        if (enabled && !config.allowPolicyTraining) {
            completed.accept(false, "현재 설정은 고정 추론만 허용합니다. 두뇌나 가중치를 바꾸지 않았습니다."); return;
        }
        if (trainingBridge == null) {
            boolean alreadyOff = !enabled && services.learnedPolicy() != null;
            completed.accept(alreadyOff, alreadyOff ? "현재 두뇌는 이미 고정 가중치 추론 상태입니다."
                    : "현재 두뇌의 학습 연결이 준비되지 않았습니다. 모델을 초기화하거나 다른 모델로 바꾸지 않았습니다.");
            return;
        }
        var identities = entries.values().stream().map(entry -> entry.ai.connectedBrainId()).filter(java.util.Objects::nonNull).distinct().toList();
        if (identities.size() != 1) {
            completed.accept(false, "같은 두뇌의 상태 응답을 먼저 확인해야 합니다. /ai stats 로 연결 상태를 확인하세요.");
            return;
        }
        if (!trainingBridge.beginTransition()) { completed.accept(false, "다른 두뇌 설정 변경을 확인 중입니다."); return; }
        long generation = ++policyControlGeneration;
        var drains = new ArrayList<java.util.concurrent.CompletableFuture<Void>>();
        try {
            for (Entry entry : entries.values()) drains.add(entry.ai.prepareLearningChange());
        } catch (RuntimeException failure) {
            trainingBridge.endTransition(); completed.accept(false, "현재 행동을 안전하게 정리하지 못해 설정을 바꾸지 않았습니다."); return;
        }
        java.util.concurrent.CompletableFuture.allOf(drains.toArray(java.util.concurrent.CompletableFuture[]::new))
                .thenCompose(ignored -> trainingBridge.setLearning(identities.getFirst(), enabled))
                .whenComplete((result, failure) -> {
                    if (!plugin.isEnabled()) { trainingBridge.endTransition(); return; }
                    try {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (generation != policyControlGeneration) return;
                            if (failure == null) for (Entry entry : entries.values()) entry.ai.acceptLearningChange(result.status());
                            trainingBridge.endTransition();
                            if (failure != null) {
                                plugin.getLogger().log(Level.WARNING, "Brain learning change not confirmed; no model initialization", failure);
                                completed.accept(false, "학습 상태 변경을 확인하지 못했습니다. 두뇌를 초기화하지 않았으며 다음 상태 응답을 확인해야 합니다.");
                            } else {
                                plugin.getLogger().info("Brain " + identities.getFirst() + " learning=" + enabled + " weights=" + result.weightsSha256());
                                completed.accept(true, "같은 두뇌의 학습을 " + (enabled ? "켰습니다." : "껐습니다. 고정 가중치로 추론합니다."));
                            }
                        });
                    } catch (RuntimeException stopped) {
                        trainingBridge.endTransition();
                        plugin.getLogger().warning("Server stopped before brain state acknowledgement could be delivered");
                    }
                });
    }

    public void manageModel(String operation, String name, BiConsumer<Boolean, List<String>> completed) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("model control must start on server thread");
        if (trainingBridge == null) {
            completed.accept(false, List.of("모델 관리 백엔드가 연결되지 않았습니다. 현재 모델은 유지됩니다.")); return;
        }
        if (!java.util.Set.of("list", "info", "save").contains(operation)
                || (operation.equals("list") ? !name.isEmpty() : !me.herry.minecraftAI.ai.learned.PpoBridge.validModelName(name))) {
            completed.accept(false, List.of("올바른 모델 이름과 관리 명령을 입력하세요.")); return;
        }
        var identities = entries.values().stream().map(entry -> entry.ai.connectedBrainId())
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (identities.size() != 1) {
            completed.accept(false, List.of("/ai stats 로 같은 두뇌의 연결 상태를 먼저 확인하세요.")); return;
        }
        boolean changing = operation.equals("save");
        if (changing ? !trainingBridge.beginTransition() : trainingBridge.isTransitioning()) {
            completed.accept(false, List.of("다른 두뇌 설정 변경을 확인 중입니다.")); return;
        }
        long generation = changing ? ++policyControlGeneration : policyControlGeneration;
        var drains = new ArrayList<java.util.concurrent.CompletableFuture<Void>>();
        try {
            // Inspection does not cancel an active action. Saving first closes its acknowledged transition.
            if (changing) for (Entry entry : entries.values()) drains.add(entry.ai.prepareLearningChange());
        } catch (RuntimeException failure) {
            if (changing) trainingBridge.endTransition();
            completed.accept(false, List.of("현재 행동을 정리하지 못해 모델을 저장하지 않았습니다.")); return;
        }
        java.util.concurrent.CompletableFuture.allOf(drains.toArray(java.util.concurrent.CompletableFuture[]::new))
                .thenCompose(ignored -> trainingBridge.modelCommand(identities.getFirst(), operation, name))
                .whenComplete((result, failure) -> {
                    if (!plugin.isEnabled()) { if (changing) trainingBridge.endTransition(); return; }
                    try {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (generation != policyControlGeneration) {
                                completed.accept(false, List.of("조회 중 두뇌 설정이 바뀌었습니다. 다시 조회하세요.")); return;
                            }
                            if (changing && failure == null) for (Entry entry : entries.values()) entry.ai.acceptLearningChange(result.status());
                            if (changing) trainingBridge.endTransition();
                            if (failure != null) {
                                plugin.getLogger().log(Level.WARNING, "Model command not confirmed; active brain retained", failure);
                                completed.accept(false, List.of("모델 명령을 확인하지 못했습니다. 목록 설정·원본·이름 충돌 또는 시험용 자료 제한을 확인하세요.",
                                        "현재 두뇌를 교체하거나 AI를 다시 시작하지 않았습니다."));
                                return;
                            }
                            var lines = new ArrayList<String>();
                            lines.add(operation.equals("save") ? "현재 두뇌를 '" + name + "' 이름으로 저장했습니다." : "저장된 모델 " + result.models().size() + "개");
                            for (var model : result.models()) {
                                if (!model.valid()) { lines.add(model.name() + ": 원본 누락 또는 손상 — 사용 불가"); continue; }
                                lines.add(model.name() + " | ID " + model.brainId() + " | 버전 " + model.policyVersion()
                                        + " | 갱신 " + model.gradientUpdates() + " | 학습 " + (model.learningEnabled() ? "켜짐" : "꺼짐")
                                        + (model.initializationOnly() ? " | 학습 전 초기 상태" : ""));
                            }
                            if (result.hasMore()) lines.add("목록은 32개까지 표시합니다. 나머지는 이름으로 조회하세요.");
                            completed.accept(true, List.copyOf(lines));
                        });
                    } catch (RuntimeException stopped) {
                        if (changing) trainingBridge.endTransition();
                        plugin.getLogger().warning("Server stopped before model acknowledgement could be delivered");
                    }
                });
    }

    public void setBackendAvailable(boolean available) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("backend lifecycle must return to server thread");
        if (trainingBridge != null) trainingBridge.setAvailable(available);
        if (!available) for (Entry entry : entries.values()) entry.ai.stop();
    }

    public void shutdown() {
        policyControlGeneration++;
        saveAll(false, "shutdown");
        List<Entry> all = new ArrayList<>(entries.values());
        entries.clear();
        views.closeEverything();
        for (Entry entry : all) {
            // 서버가 꺼져서 끊긴 것은 제거된 것이나 죽은 것과 구분해서 남긴다.
            entry.ai.getJournal().endEpisode(Experience.EndReason.SHUTDOWN);
            discard(entry);
        }
        refreshTickList();
        // 남은 기록을 다 쓰고 파일을 닫는다. 오래 걸리면 기다리지 않는다 (서버 종료를 붙잡지 않는다).
        if (experience != null) experience.close();
        if (trainingBridge != null) trainingBridge.close();
    }

    private void respawn(Entry entry) {
        // 리스폰을 기다리는 사이에 제거되었을 수 있다.
        if (entries.get(key(entry.ai.getName())) != entry || entry.body.isRemoved()) return;
        try {
            entry.body.respawn();
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to respawn AI player " + entry.ai.getName(), e);
            return;
        }
        if (entry.body.isUsable()) entry.ai.onRespawn();
    }

    private void discard(Entry entry) {
        entry.removing = true;
        views.closeAll(entry.ai);
        team.forget(entry.ai.getName());
        hub.leave(entry.ai.getName());
        try {
            entry.ai.dispose();
        } finally {
            entry.body.remove();
        }
    }

    // AI 가 하나도 없으면 틱 작업도 남겨 두지 않는다.
    private void refreshTickList() {
        tickList = List.copyOf(entries.values());
        if (tickList.isEmpty()) {
            if (tickTask != null) tickTask.cancel();
            tickTask = null;
        } else if (tickTask == null) {
            tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
        }
    }

    private void tickAll() {
        views.tick();
        // 이번 서버 틱에 모든 AI 가 함께 쓸 시간 예산을 새로 잰다.
        budget.beginTick();
        for (Entry entry : tickList) {
            if (entry.removing) continue;
            try {
                entry.ai.tick();
            } catch (RuntimeException e) {
                // 예외가 매 틱 반복되어 콘솔을 뒤덮지 않도록 해당 AI 의 자율 행동을 멈춘다.
                plugin.getLogger().log(Level.SEVERE, "AI player " + entry.ai.getName() + " stopped because of an error", e);
                try {
                    entry.ai.getJournal().endEpisode(Experience.EndReason.ERROR);
                    entry.ai.shutdown();
                } catch (RuntimeException cleanupError) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to clean up AI player " + entry.ai.getName(), cleanupError);
                }
            }
        }
        // 서버가 갑자기 꺼져도 잃는 것이 적도록 주기적으로 저장한다. 보통은 월드가 저장될 때 함께 저장되고,
        // 월드 자동 저장이 꺼져 있는 서버에서만 이 주기가 쓰인다.
        if (++serverTicks - lastSaveTick >= config.autosaveInterval) saveAll(true, "autosave");
    }

    private @Nullable Entry entryOf(Player player) {
        Entry entry = entries.get(key(player.getName()));
        return entry != null && sameId(entry, player.getUniqueId()) ? entry : null;
    }

    private static boolean sameId(Entry entry, UUID id) {
        return entry.body.getPlayer().getUniqueId().equals(id);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
