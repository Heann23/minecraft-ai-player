package me.herry.minecraftAI.ai.perception;

import me.herry.minecraftAI.ai.AIBody;
import me.herry.minecraftAI.ai.memory.MemorySystem;
import me.herry.minecraftAI.ai.navigation.BlockClass;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.perf.WorkBudget;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.config.AIConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Piglin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Zombie;
import org.bukkit.util.Vector;

import java.util.Comparator;

/**
 * 주변 환경을 감지해서 Perception 을 채운다.
 * 엔티티와 발 주변 위험은 판단 주기마다, 넓은 범위의 블록은 BlockScanner 가 여러 틱에 나눠서 확인한다.
 */
public final class PerceptionSystem {
    private static final int HAZARD_RADIUS = 2;
    private static final int CLIFF_DEPTH = 4;
    private static final double DROWNING_AIR_RATIO = 0.4;

    private final AIBody body;
    private final AIConfig config;
    private final BlockScanner scanner;
    private final Perception perception = new Perception();

    public PerceptionSystem(AIBody body, AIConfig config, WorkBudget budget) {
        this.body = body;
        this.config = config;
        this.scanner = new BlockScanner(config, budget);
    }

    public Perception getPerception() {
        return perception;
    }

    /**
     * 자신의 상태, 주변 엔티티, 발 주변 위험을 새로 읽는다.
     */
    public Perception update() {
        Player player = body.getPlayer();
        Location location = player.getLocation();
        World world = player.getWorld();

        perception.location = location;
        perception.world = world;
        perception.time = world.getTime();
        perception.night = world.getEnvironment() == World.Environment.NORMAL && perception.time >= 13000 && perception.time < 23000;
        perception.storm = world.hasStorm();
        perception.thundering = world.isThundering();

        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        perception.health = player.getHealth();
        perception.maxHealth = maxHealth != null ? maxHealth.getValue() : 20.0;
        perception.food = player.getFoodLevel();
        perception.saturation = player.getSaturation();
        perception.air = player.getRemainingAir();
        perception.maxAir = player.getMaximumAir();
        perception.onFire = player.getFireTicks() > 0;
        perception.inWater = player.isInWater();
        perception.inLava = player.isInLava();
        perception.heldItem = player.getInventory().getItemInMainHand();

        scanEntities(player, location);
        scanHazards(world, location);
        // 침대에 누워 있을 때는 몸의 위치가 침대 위로 올라가서 머리 칸이 지붕에 걸린다. 묻힌 것이 아니다.
        if (player.isSleeping()) {
            perception.suffocating = false;
            perception.standingInDanger = false;
        }
        buildThreats(player);
        return perception;
    }

    /**
     * 넓은 범위의 블록 검색을 한 틱 분량만큼 진행한다. 한 바퀴가 끝났으면 true.
     */
    public boolean tickBlockScan(MemorySystem memory, long now) {
        boolean finished = scanner.tick(body.getPlayer(), memory, now);
        if (finished) perception.nearbyBlockCount = scanner.getLastFound();
        return finished;
    }

    /**
     * 블록을 캐서 빈칸이 생긴 직후에, 그 빈칸에 맞닿아 새로 드러난 광석을 바로 기억한다.
     * 주변 블록 검색은 몇 초마다 한 번씩만 돌기 때문에, 이렇게 해야 광맥을 이어서 캘 수 있다.
     */
    public void noticeOpened(MemorySystem memory, BlockPoint opened, long now) {
        BlockScanner.noticeOpened(body.getPlayer(), memory, opened, now);
    }

    /**
     * AI 의 눈에서 엔티티가 보이는지. 빛이 통과하지 않는 블록 너머의 엔티티는 보이지 않는다.
     */
    public static boolean canSee(Player player, Entity entity) {
        if (!entity.getWorld().equals(player.getWorld())) return false;
        Location eye = player.getEyeLocation();
        Vector center = entity.getBoundingBox().getCenter();
        return Visibility.canSeePoint(opacityOf(player.getWorld()), eye.getX(), eye.getY(), eye.getZ(),
                center.getX(), center.getY(), center.getZ());
    }

    // 로드되지 않은 칸은 볼 수 없는 것으로 친다. 청크를 읽느라 서버가 멈추지 않게 하기 위해서다.
    public static Visibility.Opacity opacityOf(World world) {
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();
        return (x, y, z) -> {
            if (y < minHeight || y >= maxHeight) return false;
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return true;
            return world.getBlockAt(x, y, z).getType().isOccluding();
        };
    }

    public void reset() {
        scanner.reset();
        perception.world = null;
        perception.location = null;
        perception.heldItem = null;
        perception.hostiles.clear();
        perception.animals.clear();
        perception.players.clear();
        perception.drops.clear();
        perception.threats.clear();
    }

    private void scanEntities(Player player, Location location) {
        perception.hostiles.clear();
        perception.animals.clear();
        perception.players.clear();
        perception.drops.clear();

        double range = config.entityRange;
        for (Entity entity : player.getNearbyEntities(range, range, range)) {
            if (!entity.isValid() || entity.isDead()) continue;
            if (entity instanceof Item item) {
                perception.drops.add(item);
            } else if (entity instanceof Player other) {
                perception.players.add(other);
            } else if (entity instanceof LivingEntity living) {
                if (isHostile(living, player)) perception.hostiles.add(living);
                else if (living instanceof Animals) perception.animals.add(living);
            }
        }

        // getNearbyEntities 는 같은 월드의 엔티티만 돌려주므로 여기서는 월드 비교 없이 거리를 잴 수 있다.
        Comparator<Entity> byDistance = Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(location));
        perception.hostiles.sort(byDistance);
        perception.animals.sort(byDistance);
        perception.players.sort(byDistance);
        perception.drops.sort(byDistance);
    }

    // 엔더맨이나 피글린처럼 평소에는 중립인 몬스터는 AI 를 노리고 있을 때만 적으로 본다.
    private static boolean isHostile(LivingEntity entity, Player player) {
        if (!(entity instanceof Enemy)) return false;
        if (entity instanceof Enderman || entity instanceof PigZombie || entity instanceof Piglin) {
            return isTargeting(entity, player);
        }
        return true;
    }

    private static boolean isTargeting(LivingEntity entity, Player player) {
        return entity instanceof Mob mob && player.equals(mob.getTarget());
    }

    private void scanHazards(World world, Location location) {
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();

        perception.lavaNearby = false;
        for (int dx = -HAZARD_RADIUS; dx <= HAZARD_RADIUS && !perception.lavaNearby; dx++) {
            for (int dz = -HAZARD_RADIUS; dz <= HAZARD_RADIUS && !perception.lavaNearby; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (typeAt(world, x + dx, y + dy, z + dz) == Material.LAVA) {
                        perception.lavaNearby = true;
                        break;
                    }
                }
            }
        }

        BlockClass feet = BukkitTerrainView.classify(typeAt(world, x, y, z));
        BlockClass below = BukkitTerrainView.classify(typeAt(world, x, y - 1, z));
        perception.standingInDanger = feet == BlockClass.DANGER || (below == BlockClass.DANGER && body.isGrounded());
        // 모래나 자갈이 떨어져서 몸이 블록 안에 묻히면 질식 피해를 입고, 서 있을 공간이 없어 움직이지도 못한다.
        // 머리 칸만 막혀도 엎드린 자세가 되어 걸을 수 없으므로 발 칸과 머리 칸을 모두 본다.
        BlockPoint cell = Positions.feet(location);
        perception.suffocating = isBurying(typeAt(world, cell.x(), cell.y(), cell.z()))
                || isBurying(typeAt(world, cell.x(), cell.y() + 1, cell.z()));
        perception.cliffAhead = isCliffAhead(world, location, x, y, z);
    }

    // 바라보는 방향으로 두 칸 앞까지 살펴서 깊은 낭떠러지가 있는지 확인한다.
    private boolean isCliffAhead(World world, Location location, int x, int y, int z) {
        double yaw = Math.toRadians(location.getYaw());
        double dirX = -Math.sin(yaw);
        double dirZ = Math.cos(yaw);
        for (int step = 1; step <= 2; step++) {
            int ax = (int) Math.floor(location.getX() + dirX * step);
            int az = (int) Math.floor(location.getZ() + dirZ * step);
            if (ax == x && az == z) continue;
            if (BukkitTerrainView.classify(typeAt(world, ax, y, az)) != BlockClass.OPEN) return false;

            int depth = 0;
            while (depth < CLIFF_DEPTH && BukkitTerrainView.classify(typeAt(world, ax, y - 1 - depth, az)) == BlockClass.OPEN) depth++;
            if (depth >= CLIFF_DEPTH) return true;
        }
        return false;
    }

    // 몸이 들어가 있으면 안 되는 꽉 찬 블록인지. 다락문이나 유리처럼 딛고 서거나 겹쳐도 질식하지 않는 블록은 제외한다.
    public static boolean isBurying(Material material) {
        return material.isOccluding();
    }

    // 로드되지 않은 청크는 건드리지 않고 공기로 취급한다.
    private static Material typeAt(World world, int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) return Material.AIR;
        return world.getBlockAt(x, y, z).getType();
    }

    private void buildThreats(Player player) {
        perception.threats.clear();
        Location location = perception.location;
        for (LivingEntity hostile : perception.hostiles) {
            double distance = hostile.getLocation().distance(location);
            perception.threats.add(new Threat(threatTypeOf(hostile), hostile, distance, isTargeting(hostile, player)));
        }

        if (perception.inLava || perception.lavaNearby) perception.threats.add(Threat.of(ThreatType.LAVA, perception.inLava ? 0.0 : HAZARD_RADIUS));
        if (perception.onFire || perception.standingInDanger) perception.threats.add(Threat.of(ThreatType.FIRE, 0.0));
        if (perception.cliffAhead) perception.threats.add(Threat.of(ThreatType.CLIFF, 2.0));
        if (perception.air < perception.maxAir * DROWNING_AIR_RATIO) perception.threats.add(Threat.of(ThreatType.DROWNING, 0.0));
        if (perception.health <= config.lowHealth) perception.threats.add(Threat.of(ThreatType.LOW_HEALTH, 0.0));
        if (perception.night) perception.threats.add(Threat.of(ThreatType.NIGHT, 0.0));
    }

    public static ThreatType threatTypeOf(LivingEntity entity) {
        if (entity instanceof Creeper) return ThreatType.CREEPER;
        if (entity instanceof Zombie) return ThreatType.ZOMBIE;
        if (entity instanceof AbstractSkeleton) return ThreatType.SKELETON;
        if (entity instanceof Spider) return ThreatType.SPIDER;
        return ThreatType.OTHER_HOSTILE;
    }
}
