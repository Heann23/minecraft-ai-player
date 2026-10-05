package me.herry.minecraftAI.ai.crafting;

/**
 * 화로 연료 계산. 아이템 하나를 굽는 데 10초가 걸리고, 석탄은 80초, 나무(판자, 원목)는 15초 동안 탄다.
 * Bukkit 에 의존하지 않는다.
 */
public final class FuelMath {
    public static final double ITEMS_PER_COAL = 8.0;
    public static final double ITEMS_PER_WOOD = 1.5;
    // 원목 하나로 만드는 판자의 수. 원목과 판자는 타는 시간이 같아서, 판자로 바꿔서 넣으면 네 배를 굽는다.
    public static final int PLANKS_PER_LOG = 4;

    private FuelMath() {
    }

    // 판자가 owned 개 있을 때 needed 개를 채우려면 새로 만들어야 하는 판자의 수. 원목 하나에 4개씩 나오므로 4의 배수다.
    public static int planksToCraft(int needed, int owned) {
        int lacking = needed - owned;
        if (lacking <= 0) return 0;
        return (lacking + PLANKS_PER_LOG - 1) / PLANKS_PER_LOG * PLANKS_PER_LOG;
    }

    // items 개를 다 굽는 데 필요한 연료의 개수
    public static int fuelFor(int items, double itemsPerFuel) {
        return (int) Math.ceil(items / itemsPerFuel);
    }

    // 연료 fuel 개로 끝까지 구울 수 있는 아이템의 개수
    public static int itemsFor(int fuel, double itemsPerFuel) {
        return (int) Math.floor(fuel * itemsPerFuel);
    }
}
