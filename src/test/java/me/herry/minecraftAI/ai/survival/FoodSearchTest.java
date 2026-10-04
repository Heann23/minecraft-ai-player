package me.herry.minecraftAI.ai.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodSearchTest {
    private final FoodSearch search = new FoodSearch();

    @Test
    void keepsLookingForAWhile() {
        search.onSearching(1000L);
        search.onSearching(1000L + FoodSearch.SEARCH_LIMIT - 1);
        assertFalse(search.isExhausted(1000L + FoodSearch.SEARCH_LIMIT - 1));
    }

    // 사냥감이 없는 곳에서 끝없이 찾아다니기만 하면 다른 일을 영영 하지 못한다.
    @Test
    void givesUpAfterSearchingTooLong() {
        search.onSearching(1000L);
        long later = 1000L + FoodSearch.SEARCH_LIMIT;
        search.onSearching(later);
        assertTrue(search.isExhausted(later));

        // 한동안 쉰 뒤에는 다시 찾아볼 수 있다.
        assertFalse(search.isExhausted(later + FoodSearch.REST));
    }

    @Test
    void findingPreyStartsTheClockAgain() {
        search.onSearching(1000L);
        search.onPreyFound();
        long later = 1000L + FoodSearch.SEARCH_LIMIT;
        search.onSearching(later);
        assertFalse(search.isExhausted(later));
    }
}
