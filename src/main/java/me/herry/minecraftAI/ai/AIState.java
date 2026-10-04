package me.herry.minecraftAI.ai;

public enum AIState {
    // 생성만 되어 있고 스스로 행동하지 않는 상태
    STOPPED,
    // 자율 행동 중
    RUNNING,
    // 죽어서 리스폰을 기다리는 상태
    DEAD;

    /**
     * 이 상태로 저장된 AI 를 서버를 다시 켠 뒤에 스스로 움직이게 해야 하는지.
     *
     * @param resumeAfterRespawn 죽어 있는 동안에만 의미가 있다. 죽기 전에 움직이고 있었고 그 뒤로 멈추라는 말을 듣지 않았는지.
     */
    public boolean runsAfterRestore(boolean resumeAfterRespawn) {
        return this == RUNNING || (this == DEAD && resumeAfterRespawn);
    }
}
