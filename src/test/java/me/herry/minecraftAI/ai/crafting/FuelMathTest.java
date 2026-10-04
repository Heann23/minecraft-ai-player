package me.herry.minecraftAI.ai.crafting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FuelMathTest {
    @Test
    void countsTheFuelNeeded() {
        // 나무 하나로 1.5개를 굽는다.
        assertEquals(2, FuelMath.fuelFor(3, FuelMath.ITEMS_PER_WOOD));
        assertEquals(1, FuelMath.fuelFor(1, FuelMath.ITEMS_PER_WOOD));
        // 석탄 하나로 8개를 굽는다.
        assertEquals(1, FuelMath.fuelFor(8, FuelMath.ITEMS_PER_COAL));
        assertEquals(2, FuelMath.fuelFor(9, FuelMath.ITEMS_PER_COAL));
    }

    // 회귀: 판자 1개로 고기 3개를 넣어서 다 구워지지 않았고, 시간 초과가 될 때까지 화로 앞에서 기다렸다.
    @Test
    void smeltsOnlyWhatTheFuelCanFinish() {
        assertEquals(1, FuelMath.itemsFor(1, FuelMath.ITEMS_PER_WOOD));
        assertEquals(3, FuelMath.itemsFor(2, FuelMath.ITEMS_PER_WOOD));
        assertEquals(8, FuelMath.itemsFor(1, FuelMath.ITEMS_PER_COAL));
        assertEquals(0, FuelMath.itemsFor(0, FuelMath.ITEMS_PER_COAL));
    }

    // 사용자가 본 문제: 원목을 그대로 연료로 써서 한 번에 한두 개씩만 구웠다. 판자로 바꾸면 네 배를 굽는다.
    @Test
    void turnsLogsIntoPlanksBeforeBurningThem() {
        // 고기 8개에는 판자 6개가 든다. 판자가 없으면 원목 2개로 8개를 만든다 (원목 하나에 4개씩).
        assertEquals(6, FuelMath.fuelFor(8, FuelMath.ITEMS_PER_WOOD));
        assertEquals(8, FuelMath.planksToCraft(6, 0));
        // 가진 판자가 있으면 모자란 만큼만 만든다.
        assertEquals(4, FuelMath.planksToCraft(6, 2));
        assertEquals(4, FuelMath.planksToCraft(6, 5));
        assertEquals(0, FuelMath.planksToCraft(6, 6));
        assertEquals(0, FuelMath.planksToCraft(6, 20));
    }
}
