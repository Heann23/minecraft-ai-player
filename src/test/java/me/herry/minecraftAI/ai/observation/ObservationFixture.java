package me.herry.minecraftAI.ai.observation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 시험에서 Observation 을 쉽게 만들기 위한 도구. 필요한 값만 바꾸고 build() 한다.
 * 바꾸지 않은 값은 "지상의 낮, 다치지 않았고, 아무것도 없고, 아무것도 모르는" 상태다.
 */
public final class ObservationFixture {
    public final Map<String, Integer> items = new HashMap<>();
    public int plankEquivalent;
    public int stone;
    public int coal;
    public int food;

    public double x;
    public double y = 64.0;
    public double z;
    public String dimension = PlayerState.OVERWORLD;
    public String healthState = PlayerState.HEALTH_OK;
    public boolean hungry;
    public boolean inLava;
    public boolean drowning;

    public boolean night;
    public boolean thundering;
    public boolean hostileNearby;
    public String combat = EnvironmentState.NO_COMBAT;
    public boolean allyNeedsHelp;

    public boolean tableAvailable;
    public boolean furnaceAvailable;
    public boolean shelterBuilt;
    public boolean netherPortalBuilt;
    public boolean dragonDefeated;

    public boolean homeKnown;
    public double homeDistance;
    public boolean insideHome;
    public boolean canSleep;
    public boolean knowsLootChest;

    public ObservationFixture item(String material, int count) {
        items.put(material, count);
        return this;
    }

    public Observation build() {
        PlayerState player = new PlayerState(x, y, z, 0.0F, 0.0F, dimension, 20.0, 20.0, healthState, 20, 5.0F, hungry, 300, 300,
                0.0, true, false, inLava, false, false, false, drowning, Map.of(), List.of());
        InventoryState inventory = new InventoryState(items, 0, 36, 0, plankEquivalent, stone, coal, food, 0, false,
                "NONE", "NONE", "NONE");
        EnvironmentState environment = new EnvironmentState(1000L, night, false, thundering, "plains", 15, false, false, false,
                false, hostileNearby, combat, allyNeedsHelp, 0, 0, 0, List.of());
        ProgressState progress = new ProgressState("EARLY_SURVIVAL", "CRAFTING_TABLE", "WOOD", List.of(), tableAvailable,
                furnaceAvailable, shelterBuilt, netherPortalBuilt, false, false, dragonDefeated);
        MemoryState memory = new MemoryState(homeKnown, homeDistance, insideHome, shelterBuilt, false, canSleep, false, false,
                false, false, knowsLootChest, false, Map.of(), 0, "");
        CurrentTaskState task = new CurrentTaskState("IDLE", "Legacy(IDLE)", "", "None", "(계획 없음)", false, 0);
        return new Observation(Observation.SCHEMA_VERSION, 0L, player, inventory, environment, progress, memory, task);
    }
}
