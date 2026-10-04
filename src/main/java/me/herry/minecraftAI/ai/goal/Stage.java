package me.herry.minecraftAI.ai.goal;

/**
 * 엔더 드래곤을 잡기까지의 큰 단계. AI 의 "장기 목표"에 해당한다.
 * 단계는 직접 저장하지 않고, 아직 이루지 못한 첫 Milestone 이 속한 단계로 그때그때 계산한다.
 */
public enum Stage {
    EARLY_SURVIVAL("초반 생존 기반 갖추기"),
    IRON_AGE("철 장비 갖추기"),
    DIAMOND_AGE("다이아몬드 곡괭이 만들기"),
    NETHER_ENTRY("네더 포탈 만들기"),
    NETHER("네더 요새에서 블레이즈 막대 구하기"),
    ENDER_PEARLS("엔더 진주 모으기"),
    EYES_OF_ENDER("엔더의 눈 만들기"),
    STRONGHOLD("요새와 엔드 포탈 찾기"),
    END_PREPARATION("엔드 진입 준비"),
    DRAGON_FIGHT("엔더 드래곤 처치"),
    CLEARED("클리어");

    private final String label;

    Stage(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
