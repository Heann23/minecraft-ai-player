package me.herry.minecraftAI.ai.crafting;

/**
 * 화로 연료 계산. 아이템 하나를 굽는 데 10초가 걸리고, 석탄은 80초, 나무(판자, 원목)는 15초 동안 탄다.
 */
public final class FuelMath {
    public static final double ITEMS_PER_COAL = 8.0;
    public static final double ITEMS_PER_WOOD = 1.5;

    private FuelMath() {
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
