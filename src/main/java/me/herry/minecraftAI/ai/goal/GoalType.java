package me.herry.minecraftAI.ai.goal;

/**
 * AI 가 지금 당장 하려는 일. "단기 목표"(와 긴급 목표)에 해당한다.
 * 그 위의 중기 목표는 Milestone, 장기 목표는 Stage 이고, 최종 목표는 엔더 드래곤 처치다.
 *
 * 새 목표를 추가하려면 여기에 항목을 넣고 GoalSystem 에 점수 계산식을, Planner 에 행동 계획을,
 * Phrases 와 GoalReasons 에 문장을 등록하면 된다.
 */
public enum GoalType {
    IDLE,

    // 긴급 목표: 당장 죽을 수 있는 상황. 하던 일을 끊고 바로 시작한다.
    ESCAPE_DANGER,
    SURVIVE,
    FIGHT_HOSTILE,
    // 도움을 청한 동료 AI 를 공격하는 몬스터와 싸운다 (AI 가 여럿일 때만)
    ASSIST_ALLY,

    // 높은 우선순위
    FIND_FOOD,
    RETURN_HOME,
    // 집의 침대에서 자서 밤을 넘기고 리스폰 지점을 정한다
    SLEEP,
    // 배고픈 동료에게 음식을 가져다준다 (AI 가 여럿일 때만)
    SHARE_FOOD,

    // 일반 우선순위: 생존 기반을 갖춰 나가는 일
    // 구조물의 전리품 상자를 열어 내용물을 챙긴다
    LOOT_CHEST,
    // 가방이 거의 찼을 때 쓰지 않는 아이템을 버려서 빈칸을 만든다
    CLEAN_INVENTORY,
    // 가방이 차 가면 집의 상자에 당장 쓰지 않는 것을 넣어 둔다
    STORE_ITEMS,
    PICKUP_ITEMS,
    FIND_WOOD,
    COLLECT_WOOD,
    CRAFT_WORKBENCH,
    CRAFT_TOOL,
    // 좋은 곡괭이를 아끼려고, 굴을 팔 때 막 쓸 돌 곡괭이를 하나 만들어 둔다
    CRAFT_WORK_TOOL,
    // 직접 놓은 작업대를 다 쓴 뒤에 다시 캐서 들고 다닌다
    PACK_UP_TABLE,
    CRAFT_TORCH,
    MINE_STONE,
    MINE_COAL,
    FIND_IRON,
    MINE_IRON,
    // 다이아몬드가 나오는 깊이까지 내려가서 굴을 판다 (철 곡괭이 이상 필요)
    FIND_DIAMOND,
    // 보이는 다이아몬드 광석을 캔다 (철 곡괭이 이상 필요)
    MINE_DIAMOND,
    // 자갈을 캐서 부싯돌을 얻는다. 아는 자갈이 없으면 가진 자갈을 놓고 다시 캐고, 그것도 없으면 찾으러 다닌다
    GATHER_FLINT,
    // 빈 양동이로 물을 뜬다. 아는 물이 없으면 지상에서 찾는다
    FILL_BUCKET,
    // 용암 호수에 물을 부어 흑요석을 만들고 캔다. 아는 호수가 없으면 깊은 땅속에서 찾는다
    GATHER_OBSIDIAN,
    // 집 근처에 네더 포탈의 틀을 짓고 부싯돌과 부시로 불을 붙인다
    BUILD_PORTAL,
    // 아는 네더 포탈로 걸어 들어가서 네더로 넘어간다
    ENTER_NETHER,
    // 네더에서 할 수 있는 일이 없으면 포탈로 오버월드에 돌아온다
    LEAVE_NETHER,
    SMELT_IRON,
    // 날고기를 화로에 구워서 더 배부른 음식으로 만든다
    COOK_FOOD,
    // 화로에 넣어 둔 것이 다 구워지면 가서 꺼낸다. 굽는 동안 화로에서 멀어졌으면 돌아가서 곁에서 기다린다
    TEND_FURNACE,
    // 직접 놓은 화로를 다 쓴 뒤에 다시 캐서 들고 다닌다
    PACK_UP_FURNACE,
    // 다음 일을 하러 떠나기 전에 음식을 넉넉히 모아 둔다
    STOCK_FOOD,
    // 벽과 지붕이 있는 집을 짓고 작업대, 화로, 상자, 침대를 들인다
    BUILD_SHELTER,
    // 필요한 재료가 집 상자에 있으면 새로 구하러 가지 않고 꺼내 온다
    FETCH_ITEMS,
    EXPLORE
}
