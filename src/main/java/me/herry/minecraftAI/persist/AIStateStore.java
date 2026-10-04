package me.herry.minecraftAI.persist;

import java.util.List;

/**
 * AI 상태를 저장하고 불러오는 곳. 지금은 YAML 파일(YamlStateStore) 하나뿐이지만,
 * 나머지 코드는 이 인터페이스만 알기 때문에 JSON 이나 SQLite 로 바꿔도 다른 곳을 고칠 필요가 없다.
 */
public interface AIStateStore {
    // 저장되어 있는 모든 AI. 읽을 수 없는 것은 건너뛴다.
    List<AISnapshot> loadAll();

    /**
     * 저장할 내용을 지금(메인 스레드에서) 굳히고, 실제로 디스크에 쓰는 작업을 돌려준다.
     * 돌려받은 작업은 다른 스레드에서 실행해도 된다. 서버가 꺼질 때는 그 자리에서 바로 실행한다.
     */
    Runnable prepareSave(AISnapshot snapshot);

    void delete(String name);
}
