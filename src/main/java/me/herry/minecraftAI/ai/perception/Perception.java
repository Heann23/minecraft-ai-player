package me.herry.minecraftAI.ai.perception;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 한 시점에 AI 가 인식한 주변 상황. PerceptionSystem 이 채우고 나머지 시스템은 읽기만 한다.
 * 엔티티 목록은 가까운 순서로 정렬되어 있다. 엔티티는 다음 판단 때까지 사라질 수 있으므로 쓰기 전에 isValid() 를 확인해야 한다.
 */
public final class Perception {
    Location location;
    World world;
    long time;
    boolean night;
    boolean storm;
    boolean thundering;

    double health;
    double maxHealth;
    int food;
    float saturation;
    int air;
    int maxAir;
    boolean onFire;
    boolean inWater;
    boolean inLava;
    boolean standingInDanger;
    boolean suffocating;
    boolean lavaNearby;
    boolean cliffAhead;
    ItemStack heldItem;

    final List<LivingEntity> hostiles = new ArrayList<>();
    final List<LivingEntity> animals = new ArrayList<>();
    final List<Player> players = new ArrayList<>();
    final List<Item> drops = new ArrayList<>();
    final List<Threat> threats = new ArrayList<>();
    int nearbyBlockCount;

    public Location getLocation() {
        return location;
    }

    public float getYaw() {
        return location.getYaw();
    }

    public float getPitch() {
        return location.getPitch();
    }

    public World getWorld() {
        return world;
    }

    public long getTime() {
        return time;
    }

    public boolean isNight() {
        return night;
    }

    public boolean isStorm() {
        return storm;
    }

    public boolean isThundering() {
        return thundering;
    }

    public double getHealth() {
        return health;
    }

    public double getMaxHealth() {
        return maxHealth;
    }

    public int getFood() {
        return food;
    }

    public float getSaturation() {
        return saturation;
    }

    public int getAir() {
        return air;
    }

    public int getMaxAir() {
        return maxAir;
    }

    public boolean isOnFire() {
        return onFire;
    }

    public boolean isInWater() {
        return inWater;
    }

    public boolean isInLava() {
        return inLava;
    }

    // 불, 선인장, 마그마 블록처럼 서 있기만 해도 피해를 입는 칸에 있는지
    public boolean isStandingInDanger() {
        return standingInDanger;
    }

    // 머리가 블록 안에 묻혀 있는지 (떨어진 모래나 자갈 등)
    public boolean isSuffocating() {
        return suffocating;
    }

    public boolean isLavaNearby() {
        return lavaNearby;
    }

    public boolean isCliffAhead() {
        return cliffAhead;
    }

    public boolean isDrowning() {
        return hasThreat(ThreatType.DROWNING);
    }

    public ItemStack getHeldItem() {
        return heldItem;
    }

    public List<LivingEntity> getHostiles() {
        return hostiles;
    }

    public List<LivingEntity> getAnimals() {
        return animals;
    }

    public List<Player> getPlayers() {
        return players;
    }

    public List<Item> getDrops() {
        return drops;
    }

    public List<Threat> getThreats() {
        return threats;
    }

    // 마지막 주변 블록 검색에서 찾은 관심 블록 수 (나무, 돌, 광물 등). 위치는 MemorySystem 에 기록된다.
    public int getNearbyBlockCount() {
        return nearbyBlockCount;
    }

    public boolean hasThreat(ThreatType type) {
        for (Threat threat : threats) {
            if (threat.type() == type) return true;
        }
        return false;
    }
}
