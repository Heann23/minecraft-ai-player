package me.herry.minecraftAI.ai.navigation;

/**
 * 경로 탐색이 지형을 읽는 유일한 통로.
 * 탐색 알고리즘이 Bukkit API 를 직접 만지지 않게 해서 서버 없이도 테스트할 수 있다.
 */
public interface TerrainView {
    BlockClass classify(int x, int y, int z);
}
