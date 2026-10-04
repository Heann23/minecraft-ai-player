package me.herry.minecraftAI.ai;

import me.herry.minecraftAI.ai.combat.CombatSystem;
import me.herry.minecraftAI.ai.comm.CommunicationHub;
import me.herry.minecraftAI.ai.comm.IncomingMessage;
import me.herry.minecraftAI.ai.comm.MessageSource;
import me.herry.minecraftAI.ai.crafting.CraftingSystem;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.perf.WorkBudget;
import me.herry.minecraftAI.ai.plan.Planner;
import me.herry.minecraftAI.ai.survival.SurvivalSystem;
import me.herry.minecraftAI.ai.team.TeamChat;
import me.herry.minecraftAI.ai.util.Positions;
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
    private final SkinFetcher skins;
    private final InventoryViews views = new InventoryViews();
    private final @Nullable AIStateStore store;

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    // 틱을 도는 중에 목록이 바뀌어도 안전하도록, 생성/제거 때마다 새로 만드는 복사본을 순회한다.
    private List<Entry> tickList = List.of();
    private @Nullable BukkitTask tickTask;
    private long serverTicks;
    private long lastSaveTick;

    /**
     * @param store AI 상태를 저장할 곳. null 이면 저장하지 않는다.
     */
    public AIController(JavaPlugin plugin, AIConfig config, AIDebugger debugger, CommunicationHub hub, @Nullable AIStateStore store) {
        this.plugin = plugin;
        this.config = config;
        this.hub = hub;
        this.store = store;
        this.team = new TeamChat(this::getAll, hub);
        this.budget = new WorkBudget(config.tickBudgetNanos);
        this.services = new AIServices(config, debugger, crafting,
                new CombatSystem(config.engageRange, config.criticalHealth),
                new SurvivalSystem(config.lowHealth, config.criticalHealth, config.eatBelow),
                new Planner(), team, budget);
        this.skins = new SkinFetcher(plugin.getLogger());
        crafting.warmUp();
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
        if (entry != null) entry.ai.onWorldChanged();
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
        if (snapshot.running) ai.start();
        plugin.getLogger().info("Restored AI player " + snapshot.name + (snapshot.running ? " (running)" : " (stopped)"));
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
     * 플러그인이 꺼질 때 호출된다. 상태를 저장하고, 모든 AI 를 내보내고, 틱 작업을 멈춘다.
     */
    public void shutdown() {
        saveAll(false, "shutdown");
        List<Entry> all = new ArrayList<>(entries.values());
        entries.clear();
        views.closeEverything();
        for (Entry entry : all) discard(entry);
        refreshTickList();
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
