package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.navigation.BlockClass;

/**
 * 양동이의 물을 부어도 되는지 판단하는 순수 규칙.
 */
public final class BucketRules {
    private BucketRules() {
    }

    /**
     * 받침 블록의 윗면에 물을 부을 수 있는지. 붓는 칸은 비어 있어야 하고 받침은 단단해야 한다.
     *
     * @param cell    물이 생길 칸
     * @param support 그 아래의, 바라보고 누를 블록
     */
    public static boolean canPourInto(BlockClass cell, BlockClass support) {
        return cell == BlockClass.OPEN && support == BlockClass.SOLID;
    }

    // 네더에서는 물이 붓자마자 증발해서 물만 잃는다.
    public static boolean evaporates(boolean nether) {
        return nether;
    }
}
