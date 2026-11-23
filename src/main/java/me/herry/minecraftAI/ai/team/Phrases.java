package me.herry.minecraftAI.ai.team;

import me.herry.minecraftAI.ai.goal.GoalType;

/** 관리자 진단에 표시하는 목표 설명. */
public final class Phrases {
    private Phrases() {
    }

    // 현재 하는 일을 "~하는 중" 꼴로 나타낸 말.
    public static String goalActivity(GoalType goal) {
        return switch (goal) {
            case IDLE -> "잠깐 쉬는";
            case ESCAPE_DANGER -> "위험을 피하는";
            case SURVIVE -> "체력을 회복하는";
            case FIGHT_HOSTILE -> "몬스터와 싸우는";
            case ASSIST_ALLY -> "동료를 도와 싸우는";
            case FIND_FOOD -> "먹을 것을 구하는";
            case SHARE_FOOD -> "동료에게 음식을 가져다주는";
            case STOCK_FOOD -> "식량을 모으는";
            case RETURN_HOME -> "집으로 돌아가는";
            case SLEEP -> "침대에서 자는";
            case FIND_DIAMOND -> "다이아몬드를 찾는";
            case BUILD_SHELTER -> "집을 짓는";
            case STORE_ITEMS -> "집 상자에 물건을 넣는";
            case FETCH_ITEMS -> "집 상자에서 재료를 꺼내는";
            case LOOT_CHEST -> "전리품 상자를 여는";
            case PICKUP_ITEMS -> "떨어진 아이템을 줍는";
            case CLEAN_INVENTORY -> "가방을 정리하는";
            case FIND_WOOD -> "나무를 찾는";
            case COLLECT_WOOD -> "나무를 캐는";
            case CRAFT_WORKBENCH -> "작업대를 만드는";
            case CRAFT_TOOL, CRAFT_WORK_TOOL -> "장비를 만드는";
            case CRAFT_TORCH -> "횃불을 만드는";
            case MINE_STONE -> "돌을 캐는";
            case MINE_COAL -> "석탄을 캐는";
            case FIND_IRON -> "철을 찾는";
            case MINE_IRON -> "철을 캐는";
            case MINE_DIAMOND -> "다이아몬드를 캐는";
            case GATHER_FLINT -> "부싯돌을 구하는";
            case FILL_BUCKET -> "물을 뜨는";
            case GATHER_OBSIDIAN -> "흑요석을 캐는";
            case BUILD_PORTAL -> "네더 포탈을 짓는";
            case ENTER_NETHER -> "네더로 들어가는";
            case LEAVE_NETHER -> "오버월드로 돌아가는";
            case SMELT_IRON -> "철을 제련하는";
            case COOK_FOOD -> "고기를 굽는";
            case PACK_UP_TABLE -> "작업대를 챙기는";
            case TEND_FURNACE -> "화로에 넣어 둔 것을 챙기는";
            case PACK_UP_FURNACE -> "화로를 챙기는";
            case EXPLORE -> "주변을 탐험하는";
        };
    }

    // 낱말 뒤에 목적격 조사(을/를)를 붙인다. 마지막 글자에 받침이 있으면 "을", 없으면 "를"이다.
    public static String object(String noun) {
        return noun + (endsWithConsonant(noun) ? "을" : "를");
    }

    private static boolean endsWithConsonant(String word) {
        if (word.isEmpty()) return false;
        char last = word.charAt(word.length() - 1);
        // 한글 음절은 0xAC00 부터 (초성 19) x (중성 21) x (종성 28) 순서로 놓여 있다. 종성 번호가 0 이면 받침이 없다.
        if (last < 0xAC00 || last > 0xD7A3) return false;
        return (last - 0xAC00) % 28 != 0;
    }
}
