package me.herry.minecraftAI.ai.survival;

/**
 * 사냥감을 찾아다닌 시간을 센다. 한참 찾아도 없으면 그만두고, 얼마 뒤에 다시 찾아볼 수 있게 한다.
 * 동물이 없는 지역에서 음식을 구하려고 끝없이 돌아다니는 것을 막는다.
 */
public final class FoodSearch {
    // 이만큼 찾아다녀도 사냥감이 없으면 그만둔다 (2분).
    public static final long SEARCH_LIMIT = 2400L;
    // 그만둔 뒤에 이만큼 지나면 다시 찾아볼 수 있다 (10분).
    public static final long REST = 12000L;

    // 찾기 시작한 시각. 찾고 있지 않으면 -1.
    private long startedAt = -1L;
    private long restUntil;

    // 사냥감이 보이지 않아서 찾아다니는 중일 때 부른다.
    public void onSearching(long now) {
        if (startedAt < 0) startedAt = now;
        if (now - startedAt >= SEARCH_LIMIT) {
            restUntil = now + REST;
            startedAt = -1L;
        }
    }

    public void onPreyFound() {
        startedAt = -1L;
    }

    // 찾기를 그만두고 쉬는 중인지
    public boolean isExhausted(long now) {
        return now < restUntil;
    }
}
