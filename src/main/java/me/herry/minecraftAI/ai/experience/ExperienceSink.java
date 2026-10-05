package me.herry.minecraftAI.ai.experience;

/**
 * 기록이 가는 곳. 서버 메인 스레드에서 부르므로 구현은 오래 걸리는 일(파일 쓰기)을 여기서 하지 않고,
 * 받지 못할 때는 막히거나 예외를 내는 대신 그 기록을 버린다. 기록 때문에 AI 가 멈추면 안 된다.
 */
public interface ExperienceSink {
    // 아무것도 남기지 않는다 (기록을 꺼 두었을 때).
    ExperienceSink NONE = new ExperienceSink() {
        @Override
        public void append(String ai, String episodeId, Object record) {
        }

        @Override
        public void endEpisode(String ai, String episodeId) {
        }
    };

    /**
     * @param ai        AI 의 이름. 에피소드를 AI 별로 나눠 두는 데 쓴다.
     * @param episodeId 같은 에피소드의 기록은 같은 곳에 순서대로 남는다.
     * @param record    Experience 의 record 하나
     */
    void append(String ai, String episodeId, Object record);

    // 그 에피소드에는 더 남길 것이 없다.
    void endEpisode(String ai, String episodeId);
}
