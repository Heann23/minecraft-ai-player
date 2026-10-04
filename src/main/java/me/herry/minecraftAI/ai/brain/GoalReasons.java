package me.herry.minecraftAI.ai.brain;

import me.herry.minecraftAI.ai.combat.CombatSystem;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.Milestone;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.team.Phrases;

/**
 * 목표를 고른 이유를 사람이 읽을 수 있는 문장으로 만든다. GoalSystem 의 점수식이 본 것과 같은 값(Situation)을 보고 설명한다.
 * Bukkit 에 의존하지 않는다.
 */
public final class GoalReasons {
    private GoalReasons() {
    }

    public static String explain(GoalType goal, Situation s) {
        String target = s.nextMilestone == null ? "다음 준비물" : s.nextMilestone.label();
        return switch (goal) {
            case IDLE -> "지금 할 수 있는 일이 없어서 잠깐 기다리고 있어요";
            case ESCAPE_DANGER -> escapeReason(s);
            case SURVIVE -> s.sealedIn ? "몬스터를 피해 숨은 자리에서 체력이 " + (int) Math.ceil(s.health) + " 에서 회복되기를 기다리고 있어요"
                    : "체력이 " + (int) Math.ceil(s.health) + " 밖에 안 남아서 싸움을 피하고 회복해야 해요";
            case FIGHT_HOSTILE -> "이길 수 있는 몬스터가 가까이 와서 먼저 처리하려고요";
            case ASSIST_ALLY -> "동료가 몬스터에게 공격받고 있어서요";
            case FIND_FOOD -> s.hasFood ? "허기가 " + s.food + " 까지 떨어져서 먹어야 해요"
                    : "허기가 " + s.food + " 인데 먹을 것이 없어서 구해야 해요";
            case RETURN_HOME -> "밤인데 장비가 약해서 집에서 아침을 기다리는 게 안전해요";
            case SLEEP -> "밤이라 침대에서 자고 리스폰 지점을 집으로 정해 두려고요";
            case SHARE_FOOD -> "배고픈 동료에게 음식을 나눠 주기로 해서요";
            case LOOT_CHEST -> "아직 아무도 열지 않은 전리품 상자를 발견해서요";
            case CLEAN_INVENTORY -> "가방 빈칸이 " + s.emptySlots + "칸뿐이라 쓰지 않는 것을 버려야 해요";
            case STORE_ITEMS -> "가방 빈칸이 " + s.emptySlots + "칸이고 당장 안 쓰는 것이 " + s.storableSlots + "칸 있어서 집 상자에 넣어 두려고요";
            case PICKUP_ITEMS -> "근처에 떨어진 아이템이 있어서요";
            case FETCH_ITEMS -> target + "에 필요한 재료가 집 상자에 있어서, 새로 구하는 대신 꺼내 오려고요";
            case FIND_WOOD -> target + "에 나무가 필요한데 아는 나무가 없어서 찾아야 해요";
            case COLLECT_WOOD -> s.treeUnfinished ? "베던 나무를 끝까지 베려고요" : target + "에 쓸 나무가 부족해서요";
            // 작업대가 필요할 때는 다음에 이룰 것이 작업대 자체로 잡혀 있어서, 무엇을 만들려는지는 따로 알 수 없다.
            case CRAFT_WORKBENCH -> "도구와 장비를 만들려면 가까이에 작업대가 있어야 해서요";
            case CRAFT_TOOL -> target + " 재료가 다 모여서 만들 차례예요";
            case CRAFT_WORK_TOOL -> "굴을 팔 때 쓸 돌 곡괭이가 없어서요. 좋은 곡괭이는 그것이 있어야만 캘 수 있는 데에 아껴 쓰려고요";
            case PACK_UP_TABLE -> "이 자리에서 더 만들 것이 없어서 작업대를 챙겨 가려고요";
            case CRAFT_TORCH -> "횃불이 " + s.torches + "개뿐이라 어두운 곳에 놓을 것을 만들어 두려고요";
            case MINE_STONE -> target + "에 쓸 돌이 부족해서요";
            case MINE_COAL -> s.coalVeinNearby && s.canMineIron ? "눈앞에 석탄 광맥이 있어서 다 캐려고요" : "횃불과 화로 연료로 쓸 석탄이 부족해서요";
            case FIND_IRON -> ironReason(s, target) + " 아는 철 광석이 없어서 땅속에서 찾아야 해요";
            case MINE_IRON -> s.ironVeinNearby ? "눈앞에 철 광맥이 있어서 다 캐려고요" : ironReason(s, target);
            case FIND_DIAMOND -> diamondReason(s, target) + " 다이아몬드가 나오는 깊이까지 내려가서 찾아야 해요";
            case MINE_DIAMOND -> "캘 수 있는 다이아몬드 광석을 발견해서요";
            case SMELT_IRON -> "철 원석이 " + s.rawIron + "개 모여서 주괴로 구울 차례예요";
            case COOK_FOOD -> "날고기 " + s.rawFood + "개를 구워서 더 든든한 음식으로 만들려고요";
            case STOCK_FOOD -> s.preyNearby ? "음식이 " + s.foodCount + "개뿐인데 사냥감이 보여서 미리 모아 두려고요"
                    : "땅속에는 사냥감이 없어서, 내려가기 전에 음식을 챙기려고요 (지금 " + s.foodCount + "개)";
            case BUILD_SHELTER -> s.shelterInProgress ? "짓던 집을 마저 지으려고요 (남은 블록 " + s.buildBlocksNeeded + "개)"
                    : "기본 도구를 갖췄으니, 땅속으로 내려가기 전에 돌아올 집을 지으려고요";
            case EXPLORE -> exploreReason(s);
        };
    }

    private static String escapeReason(Situation s) {
        if (s.inLava) return "용암에 빠져서 당장 빠져나가야 해요";
        if (s.standingInDanger) return "서 있기만 해도 다치는 곳이라 벗어나야 해요";
        if (s.suffocating) return "블록에 파묻혀서 숨을 쉴 수 없어요";
        if (s.drowning) return "숨이 얼마 안 남아서 물 밖으로 나가야 해요";
        if (s.combat == CombatSystem.Decision.FLEE) return "지금 장비와 체력으로는 감당하기 어려운 몬스터가 가까이 있어요";
        return "위험해서 피해야 해요";
    }

    private static String ironReason(Situation s, String target) {
        return target + "에 철이 " + s.ironNeeded + "개 필요한데 " + (s.rawIron + s.ironIngots) + "개뿐이라서요.";
    }

    private static String diamondReason(Situation s, String target) {
        return target + "에 다이아몬드가 " + s.diamondsNeeded + "개 필요한데 " + s.diamonds + "개뿐이라서요.";
    }

    private static String exploreReason(Situation s) {
        Milestone next = s.nextMilestone;
        if (next == null) return "해야 할 일을 다 마쳐서 주변을 둘러보고 있어요";
        if (!next.isAutomated()) {
            return "다음 단계인 '" + next.label() + "' 준비는 아직 제가 스스로 할 줄 모르는 일이라, 주변을 둘러보면서 기다리고 있어요";
        }
        return Phrases.object(next.label()) + " 준비해야 하는데 지금 있는 곳에서는 할 수 있는 일이 없어서 다른 곳을 찾아보고 있어요";
    }
}
