package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.goal.Milestone;
import me.herry.minecraftAI.ai.goal.ProgressFacts;
import me.herry.minecraftAI.ai.goal.Stage;
import me.herry.minecraftAI.ai.inventory.ArmorSlot;
import me.herry.minecraftAI.ai.inventory.ArmorTier;
import me.herry.minecraftAI.ai.inventory.ToolTier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressionTest {
    // 철 장비를 만들기 직전까지 갖춘 상태: 돌 도구, 화로, 집, 작업대.
    private static ProgressFacts stoneAge() {
        ProgressFacts facts = new ProgressFacts();
        facts.tableAvailable = true;
        facts.furnaceAvailable = true;
        facts.shelterBuilt = true;
        facts.pickaxe = ToolTier.STONE;
        facts.axe = ToolTier.STONE;
        facts.sword = ToolTier.STONE;
        return facts;
    }

    private static ProgressFacts ironAge() {
        ProgressFacts facts = stoneAge();
        facts.pickaxe = ToolTier.IRON;
        facts.sword = ToolTier.IRON;
        for (ArmorSlot slot : ArmorSlot.values()) facts.setArmor(slot, ArmorTier.IRON);
        facts.setCount("SHIELD", 1);
        facts.setCount("BUCKET", 1);
        return facts;
    }

    @Test
    void startsWithCraftingTable() {
        ProgressFacts facts = new ProgressFacts();
        assertEquals(Milestone.CRAFTING_TABLE, Progression.next(facts));
        assertEquals(Stage.EARLY_SURVIVAL, Progression.stageOf(facts));

        facts.tableAvailable = true;
        assertEquals(Milestone.WOODEN_PICKAXE, Progression.next(facts));
    }

    // 버그 재현: 예전에는 "흉갑 부위에 무엇이든 있으면" 철 흉갑을 갖춘 것으로 쳐서,
    // 가죽 흉갑을 주우면 철 흉갑을 영영 만들지 않았다.
    @Test
    void leatherArmorDoesNotCountAsIronArmor() {
        ProgressFacts facts = stoneAge();
        facts.pickaxe = ToolTier.IRON;
        facts.sword = ToolTier.IRON;
        facts.setCount("SHIELD", 1);
        facts.setArmor(ArmorSlot.CHEST, ArmorTier.LEATHER);

        assertFalse(Milestone.IRON_CHESTPLATE.isAchieved(facts));
        assertEquals(Milestone.IRON_CHESTPLATE, Progression.next(facts));

        // 사슬 갑옷도 철보다 낮은 등급이다.
        facts.setArmor(ArmorSlot.CHEST, ArmorTier.CHAINMAIL);
        assertEquals(Milestone.IRON_CHESTPLATE, Progression.next(facts));

        facts.setArmor(ArmorSlot.CHEST, ArmorTier.IRON);
        assertEquals(Milestone.IRON_LEGGINGS, Progression.next(facts));
    }

    // 방패는 철 하나로 만들 수 있고 화살과 크리퍼의 폭발을 막아 준다. 철이 많이 드는 갑옷보다 먼저 만든다.
    @Test
    void shieldComesRightAfterTheIronSword() {
        ProgressFacts facts = stoneAge();
        facts.pickaxe = ToolTier.IRON;
        facts.sword = ToolTier.IRON;
        assertEquals(Milestone.SHIELD, Progression.next(facts));

        facts.setCount("SHIELD", 1);
        assertEquals(Milestone.IRON_CHESTPLATE, Progression.next(facts));
    }

    @Test
    void betterArmorSatisfiesIronMilestone() {
        ProgressFacts facts = stoneAge();
        facts.pickaxe = ToolTier.IRON;
        facts.sword = ToolTier.IRON;
        facts.setArmor(ArmorSlot.CHEST, ArmorTier.DIAMOND);
        assertTrue(Milestone.IRON_CHESTPLATE.isAchieved(facts));
    }

    @Test
    void craftingTableComesBackOnlyWhenSomethingIsLeftToCraft() {
        ProgressFacts facts = stoneAge();
        facts.tableAvailable = false;
        // 철 곡괭이를 만들려면 작업대가 필요하다.
        assertEquals(Milestone.CRAFTING_TABLE, Progression.next(facts));

        // 모으는 일(흑요석)이 남았을 때는 작업대를 다시 만들지 않는다.
        ProgressFacts gathering = ironAge();
        gathering.pickaxe = ToolTier.DIAMOND;
        gathering.setCount("FLINT_AND_STEEL", 1);
        gathering.setCount("WATER_BUCKET", 1);
        gathering.tableAvailable = false;
        assertEquals(Milestone.OBSIDIAN, Progression.next(gathering));
    }

    @Test
    void shelterComesAfterStoneToolsAndBeforeIron() {
        ProgressFacts facts = stoneAge();
        facts.shelterBuilt = false;
        assertEquals(Milestone.SHELTER, Progression.next(facts));
        assertEquals(Stage.EARLY_SURVIVAL, Progression.stageOf(facts));

        facts.shelterBuilt = true;
        assertEquals(Milestone.IRON_PICKAXE, Progression.next(facts));
        assertEquals(Stage.IRON_AGE, Progression.stageOf(facts));
    }

    // 집처럼 없어도 되는 항목은 계속 막히면 미뤄 두고 다음 단계로 넘어갈 수 있다.
    @Test
    void optionalMilestoneCanBeDeferred() {
        ProgressFacts facts = stoneAge();
        facts.shelterBuilt = false;
        facts.defer(Milestone.SHELTER);
        assertEquals(Milestone.IRON_PICKAXE, Progression.next(facts));

        // 곡괭이처럼 꼭 필요한 것은 미룰 수 없다.
        facts.defer(Milestone.IRON_PICKAXE);
        assertFalse(facts.isDeferred(Milestone.IRON_PICKAXE));
        assertEquals(Milestone.IRON_PICKAXE, Progression.next(facts));
    }

    // 장비를 잃으면(사망 등) 잃은 지점부터 다시 진행한다.
    @Test
    void losingGearRegressesToTheMissingMilestone() {
        ProgressFacts facts = ironAge();
        assertEquals(Milestone.DIAMOND_PICKAXE, Progression.next(facts));

        facts.pickaxe = ToolTier.NONE;
        assertEquals(Milestone.WOODEN_PICKAXE, Progression.next(facts));
    }

    // 회귀: 집에 화로를 들여놓고 철을 찾으러 내려갔더니, 집에서 멀어졌다는 이유로 화로를 또 만들었다.
    // 한 번은 그걸 만들려고 집의 작업대까지 되돌아갔다.
    @Test
    void furnaceAtHomeCountsEvenWhenFarFromHome() {
        ProgressFacts facts = stoneAge();
        facts.furnaceAvailable = false;
        assertEquals(Milestone.FURNACE, Progression.next(facts));

        facts.furnaceAtHome = true;
        assertEquals(Milestone.IRON_PICKAXE, Progression.next(facts));
    }

    // 땅속에서 곡괭이가 부서졌을 때 돌과 막대가 있으면 돌 곡괭이를 바로 만든다.
    // 나무 곡괭이부터 다시 만들려고 나무를 찾으러 지상까지 올라가지 않는다.
    @Test
    void replacesABrokenPickaxeWithStoneWhenTheMaterialsAreAtHand() {
        ProgressFacts facts = stoneAge();
        facts.pickaxe = ToolTier.NONE;
        assertEquals(Milestone.WOODEN_PICKAXE, Progression.next(facts));

        facts.setCount("COBBLESTONE", 3);
        assertEquals(Milestone.WOODEN_PICKAXE, Progression.next(facts));

        facts.setCount("STICK", 2);
        assertEquals(Milestone.STONE_PICKAXE, Progression.next(facts));

        // 막대 대신 막대를 만들 판자가 있어도 된다. 깊은 곳의 돌(심층암 조약돌)도 돌 도구 재료다.
        facts.setCount("STICK", 0);
        facts.setCount("COBBLESTONE", 0);
        facts.setCount("COBBLED_DEEPSLATE", 3);
        facts.setCount("SPRUCE_PLANKS", 2);
        assertEquals(Milestone.STONE_PICKAXE, Progression.next(facts));
    }

    // 초반 생존부터 클리어까지의 순서가 끊기지 않고 이어지는지 확인한다.
    @Test
    void progressionRunsAllTheWayToTheDragon() {
        ProgressFacts facts = ironAge();
        assertEquals(Stage.DIAMOND_AGE, Progression.stageOf(facts));

        facts.pickaxe = ToolTier.DIAMOND;
        assertEquals(Milestone.FLINT_AND_STEEL, Progression.next(facts));
        assertEquals(Stage.NETHER_ENTRY, Progression.stageOf(facts));

        facts.setCount("FLINT_AND_STEEL", 1);
        assertEquals(Milestone.WATER_BUCKET, Progression.next(facts));
        facts.setCount("WATER_BUCKET", 1);
        assertEquals(Milestone.OBSIDIAN, Progression.next(facts));
        facts.setCount("OBSIDIAN", Milestone.PORTAL_OBSIDIAN);
        facts.setCount("WATER_BUCKET", 0);
        assertEquals(Milestone.NETHER_PORTAL, Progression.next(facts));

        // 포탈을 만들고 나면 흑요석과 부싯돌은 더 필요 없다.
        facts.netherPortalBuilt = true;
        facts.setCount("OBSIDIAN", 0);
        facts.setCount("FLINT_AND_STEEL", 0);
        assertEquals(Milestone.BLAZE_RODS, Progression.next(facts));
        assertEquals(Stage.NETHER, Progression.stageOf(facts));

        facts.setCount("BLAZE_ROD", 6);
        assertEquals(Milestone.ENDER_PEARLS, Progression.next(facts));
        facts.setCount("ENDER_PEARL", Milestone.EYES_NEEDED);
        assertEquals(Milestone.EYES_OF_ENDER, Progression.next(facts));

        // 눈을 만들면 재료(막대, 진주)는 사라지지만 진행은 되돌아가지 않는다.
        facts.setCount("BLAZE_ROD", 0);
        facts.setCount("ENDER_PEARL", 0);
        facts.setCount("ENDER_EYE", Milestone.EYES_NEEDED);
        assertEquals(Milestone.STRONGHOLD, Progression.next(facts));
        assertEquals(Stage.STRONGHOLD, Progression.stageOf(facts));

        facts.strongholdFound = true;
        assertEquals(Milestone.END_PORTAL, Progression.next(facts));
        // 눈을 포탈 틀에 다 끼우면 손에는 남지 않는다.
        facts.endPortalReady = true;
        facts.setCount("ENDER_EYE", 0);
        assertEquals(Milestone.END_GEAR, Progression.next(facts));
        assertEquals(Stage.END_PREPARATION, Progression.stageOf(facts));

        facts.setCount("BOW", 1);
        facts.setCount("ARROW", Milestone.ARROWS_NEEDED);
        assertEquals(Milestone.ENDER_DRAGON, Progression.next(facts));
        assertEquals(Stage.DRAGON_FIGHT, Progression.stageOf(facts));

        facts.dragonDefeated = true;
        assertNull(Progression.next(facts));
        assertEquals(Stage.CLEARED, Progression.stageOf(facts));
    }

    @Test
    void clearedStaysClearedEvenAfterLosingGear() {
        ProgressFacts facts = new ProgressFacts();
        facts.dragonDefeated = true;
        assertNull(Progression.next(facts));
        assertEquals(Stage.CLEARED, Progression.stageOf(facts));
    }

    // 스스로 해낼 수 있는 범위가 어디까지인지 정직하게 표시되어 있어야 한다.
    @Test
    void automationFrontierIsTheWaterBucket() {
        assertTrue(Milestone.DIAMOND_PICKAXE.isAutomated());
        assertTrue(Milestone.SHELTER.isAutomated());
        assertTrue(Milestone.FLINT_AND_STEEL.isAutomated());
        assertTrue(Milestone.WATER_BUCKET.isAutomated());
        assertFalse(Milestone.OBSIDIAN.isAutomated());
        assertFalse(Milestone.ENDER_DRAGON.isAutomated());
    }
}
