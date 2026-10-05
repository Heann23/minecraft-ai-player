package me.herry.minecraftAI.ai.team;

import me.herry.minecraftAI.ai.AIState;
import me.herry.minecraftAI.ai.comm.Intent;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.Milestone;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.perception.ThreatType;
import org.jetbrains.annotations.Nullable;

/**
 * AI 가 채팅으로 하는 말. 문장을 한곳에 모아 두어서 말투를 바꾸거나 다른 언어로 옮기기 쉽게 한다.
 * Bukkit 에 의존하지 않는 순수한 문자열 조립이다.
 */
public final class Phrases {
    private Phrases() {
    }

    // 목표가 바뀔 때 하는 말. 굳이 알릴 필요가 없는 목표는 null.
    public static @Nullable String goalAnnouncement(GoalType goal) {
        return switch (goal) {
            case COLLECT_WOOD -> "나무를 캐러 갈게요.";
            case FIND_WOOD -> "주변에 나무가 안 보여요. 찾아볼게요.";
            case CRAFT_WORKBENCH -> "작업대를 만들어서 놓을게요.";
            case CRAFT_TORCH -> "횃불을 만들게요.";
            case MINE_STONE -> "돌을 캐러 갑니다.";
            case MINE_COAL -> "석탄을 캘게요.";
            case FIND_IRON -> "철을 찾으러 땅속으로 내려갈게요.";
            case MINE_IRON -> "철 광석을 캘게요.";
            case MINE_DIAMOND -> "다이아몬드다! 캐러 갈게요.";
            case GATHER_FLINT -> "부싯돌이 나올 때까지 자갈을 캘게요.";
            case FILL_BUCKET -> "양동이에 물을 떠 올게요.";
            case SMELT_IRON -> "화로에서 철을 제련할게요.";
            case PACK_UP_TABLE -> "작업대는 챙겨 갈게요.";
            case PACK_UP_FURNACE -> "화로는 챙겨 갈게요.";
            case CLEAN_INVENTORY -> "가방이 꽉 찼어요. 필요 없는 건 버릴게요.";
            case LOOT_CHEST -> "전리품 상자가 있어요! 열어 볼게요.";
            case STOCK_FOOD -> "먹을 것을 모아 둘게요.";
            case FIGHT_HOSTILE -> "몬스터다! 제가 상대할게요.";
            case ASSIST_ALLY -> "제가 도와줄게요!";
            case ESCAPE_DANGER -> "위험해요, 일단 피할게요!";
            case SURVIVE -> "체력이 얼마 없어요. 잠깐 쉬면서 회복할게요.";
            case RETURN_HOME -> "밤이라 집으로 돌아갈게요.";
            case SLEEP -> "침대에서 자고 올게요.";
            case FIND_DIAMOND -> "다이아몬드를 찾으러 더 깊이 내려갈게요.";
            case BUILD_SHELTER -> "집을 지을게요.";
            case STORE_ITEMS -> "가방이 차서 집 상자에 넣어 두고 올게요.";
            case FETCH_ITEMS -> "필요한 재료가 집 상자에 있어요. 꺼내 올게요.";
            // 무엇을 만드는지, 음식을 어떻게 구하는지는 계획을 세울 때 구체적으로 말한다.
            case IDLE, PICKUP_ITEMS, EXPLORE, CRAFT_TOOL, CRAFT_WORK_TOOL, FIND_FOOD, SHARE_FOOD, COOK_FOOD -> null;
            // 화로에 넣어 둔 것을 꺼내러 가는 것은 굽기 시작할 때 이미 말한 일의 마무리다.
            case TEND_FURNACE -> null;
        };
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
            case SMELT_IRON -> "철을 제련하는";
            case COOK_FOOD -> "고기를 굽는";
            case PACK_UP_TABLE -> "작업대를 챙기는";
            case TEND_FURNACE -> "화로에 넣어 둔 것을 챙기는";
            case PACK_UP_FURNACE -> "화로를 챙기는";
            case EXPLORE -> "주변을 탐험하는";
        };
    }

    public static String status(AIState state, GoalType goal, int health, int food) {
        if (state == AIState.STOPPED) return "저는 지금 멈춰 있어요. /ai start 로 움직이게 해 주세요.";
        if (state == AIState.DEAD) return "방금 죽어서 다시 태어나길 기다리고 있어요...";
        return "저는 지금 " + goalActivity(goal) + " 중이에요. (체력 " + health + ", 허기 " + food + ")";
    }

    // 좌표는 채팅에 적지 않는다. 위치는 듣는 AI 의 기억에 직접 전달되므로 문장에는 필요 없고, 읽기에만 불편하다.
    public static String found(MemoryType type) {
        return resourceName(type) + " 찾았어요! 위치 알려 줄게요.";
    }

    public static String placed(MemoryType type) {
        String what = type == MemoryType.FURNACE ? "화로를" : "작업대를";
        return what + " 놓았어요. 같이 써요!";
    }

    public static String thanks(String name) {
        return "고마워요, " + name + "! 그쪽으로 가 볼게요.";
    }

    public static String help(ThreatType attacker) {
        return "도와주세요! " + mobName(attacker) + "한테 공격받고 있어요!";
    }

    public static String onMyWay(String name) {
        return "지금 갈게요, " + name + "!";
    }

    public static String askFood() {
        return "배가 고픈데 먹을 게 없어요. 나눠 줄 수 있는 분 있나요?";
    }

    public static String willShareFood(String name) {
        return "제가 나눠 줄게요, " + name + "! 조금만 기다려요.";
    }

    public static String hereIsFood(String name) {
        return "여기요, " + name + ". 드세요!";
    }

    public static String goForage() {
        return "나눠 줄 사람이 없네요. 먹을 것을 구하러 다녀올게요.";
    }

    public static String pluggingWater() {
        return "물이 흘러들어 와요. 물길을 막을게요.";
    }

    public static String cooking() {
        return "고기를 화로에 구워 먹을게요.";
    }

    public static String bridging() {
        return "건너갈 길이 없네요. 블록으로 다리를 놓을게요.";
    }

    public static String takingRefuge() {
        return "몬스터가 너무 많아요. 파고 들어가서 숨을게요.";
    }

    public static String climbingOut() {
        return "나갈 길이 없어서 블록을 쌓고 올라갈게요.";
    }

    public static String lootedChest(int items) {
        return items > 0 ? "상자에서 쓸 만한 걸 챙겼어요!" : "상자가 비어 있네요.";
    }

    public static String hello(int teamSize) {
        return teamSize > 1 ? "안녕하세요! 저도 같이 할게요." : "안녕하세요! 지금부터 움직일게요.";
    }

    public static String died() {
        return "으악... 죽었어요. 금방 다시 올게요.";
    }

    public static String respawned() {
        return "돌아왔어요! 처음부터 다시 준비할게요.";
    }

    // 지금 무엇을 만들려는지 구체적으로 말한다.
    public static String willCraft(Milestone milestone) {
        return object(milestone.label()) + " 만들게요.";
    }

    // 낱말 뒤에 목적격 조사(을/를)를 붙인다. 마지막 글자에 받침이 있으면 "을", 없으면 "를"이다.
    public static String object(String noun) {
        return noun + (endsWithConsonant(noun) ? "을" : "를");
    }

    // 낱말 뒤에 보조사(은/는)를 붙인다.
    public static String topic(String noun) {
        return noun + (endsWithConsonant(noun) ? "은" : "는");
    }

    private static boolean endsWithConsonant(String word) {
        if (word.isEmpty()) return false;
        char last = word.charAt(word.length() - 1);
        // 한글 음절은 0xAC00 부터 (초성 19) x (중성 21) x (종성 28) 순서로 놓여 있다. 종성 번호가 0 이면 받침이 없다.
        if (last < 0xAC00 || last > 0xD7A3) return false;
        return (last - 0xAC00) % 28 != 0;
    }

    public static String notAllowed() {
        return "죄송해요, 그건 관리자만 시킬 수 있어요.";
    }

    // 조사를 붙이지 않은 자원 이름
    public static String subjectName(Intent.Subject subject) {
        return switch (subject) {
            case WOOD -> "나무";
            case STONE -> "돌";
            case COAL -> "석탄";
            case IRON -> "철";
            case DIAMOND -> "다이아몬드";
            case FOOD -> "음식";
            case NONE -> "그것";
        };
    }

    public static String shelterSite() {
        return "여기에 집을 지을게요.";
    }

    public static String shelterDone() {
        return "집을 다 지었어요! 이제 여기가 거점이에요.";
    }

    public static String stored(int stacks) {
        return "상자에 " + stacks + "묶음 넣어 뒀어요.";
    }

    public static String fetched() {
        return "상자에서 필요한 재료를 꺼냈어요.";
    }

    public static String goodMorning() {
        return "잘 잤어요. 다시 움직일게요.";
    }

    // 조사(을/를)까지 붙인 자원 이름
    private static String resourceName(MemoryType type) {
        return switch (type) {
            case TREE -> "나무를";
            case STONE -> "돌을";
            case IRON_ORE -> "철 광석을";
            case COAL_ORE -> "석탄을";
            case DIAMOND_ORE -> "다이아몬드를";
            case WORKBENCH -> "작업대를";
            case FURNACE -> "화로를";
            case LOOT_CHEST -> "전리품 상자를";
            default -> "쓸 만한 것을";
        };
    }

    private static String mobName(ThreatType type) {
        return switch (type) {
            case ZOMBIE -> "좀비";
            case SKELETON -> "스켈레톤";
            case SPIDER -> "거미";
            case CREEPER -> "크리퍼";
            default -> "몬스터";
        };
    }
}
