package me.herry.minecraftAI.ai.primitive;

import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.ActionStatus;
import me.herry.minecraftAI.ai.action.BreakBlockAction;
import me.herry.minecraftAI.ai.action.CollectFurnaceAction;
import me.herry.minecraftAI.ai.action.DropJunkAction;
import me.herry.minecraftAI.ai.action.EatFoodAction;
import me.herry.minecraftAI.ai.action.ExploreAreaAction;
import me.herry.minecraftAI.ai.action.LootChestAction;
import me.herry.minecraftAI.ai.action.SleepAction;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 기존 행동이 기본 행동(PrimitiveAction)으로서 알려 주는 종류와 대상.
 */
class PrimitiveActionTest {
    private static final BlockPoint POS = new BlockPoint(3, 64, -7);

    private static void check(PrimitiveAction action, PrimitiveType type, PrimitiveTarget target) {
        assertEquals(type, action.getPrimitiveType(), action.getName());
        assertEquals(target, action.getTarget(), action.getName());
        // 만든 것은 기존 행동 그대로라서 실행 전 상태와 이름도 그대로다.
        assertEquals(ActionStatus.READY, action.getStatus());
    }

    @Test
    void factoryMakesTheExistingActions() {
        check(Primitives.moveWithinReach(POS), PrimitiveType.MOVE_TO, new PrimitiveTarget.Block(POS));
        check(Primitives.moveTo(POS, 2.0), PrimitiveType.MOVE_TO, new PrimitiveTarget.Block(POS));
        check(Primitives.lookAt(1.5, 65.0, 2.5), PrimitiveType.LOOK_AT, new PrimitiveTarget.Point(1.5, 65.0, 2.5));
        check(Primitives.lookAt(POS), PrimitiveType.LOOK_AT, new PrimitiveTarget.Point(3.5, 64.5, -6.5));
        check(Primitives.jump(), PrimitiveType.JUMP, PrimitiveTarget.NONE);
        check(Primitives.breakBlock(POS), PrimitiveType.BREAK_BLOCK, new PrimitiveTarget.Block(POS));
        check(Primitives.eat(), PrimitiveType.USE_ITEM, PrimitiveTarget.NONE);
        check(Primitives.pickup(6.0), PrimitiveType.PICKUP_ITEM, new PrimitiveTarget.Area(6.0));
        check(Primitives.dropJunk(), PrimitiveType.DROP_ITEM, PrimitiveTarget.NONE);
        check(Primitives.selectSlot(4), PrimitiveType.SELECT_SLOT, new PrimitiveTarget.Slot(4));
        check(Primitives.waitTicks(40), PrimitiveType.WAIT, new PrimitiveTarget.Ticks(40));

        assertInstanceOf(BreakBlockAction.class, Primitives.breakBlock(POS));
        assertInstanceOf(EatFoodAction.class, Primitives.eat());
        assertInstanceOf(DropJunkAction.class, Primitives.dropJunk());
        assertEquals("BreakBlock", Primitives.breakBlock(POS).getName());
    }

    // 상자, 화로, 침대를 쓰는 행동은 팩토리에는 없지만 종류와 대상은 똑같이 알려 준다.
    @Test
    void blockInteractionsReportTheirBlock() {
        check(new LootChestAction(POS), PrimitiveType.INTERACT_BLOCK, new PrimitiveTarget.Block(POS));
        check(new CollectFurnaceAction(POS), PrimitiveType.INTERACT_BLOCK, new PrimitiveTarget.Block(POS));
        check(new SleepAction(POS), PrimitiveType.INTERACT_BLOCK, new PrimitiveTarget.Block(POS));
    }

    // 여러 종류의 행동을 섞어서 하는 것(탐험 등)은 기본 행동이 아니다.
    @Test
    void compositeActionsAreNotPrimitives() {
        Action explore = new ExploreAreaAction(5.0, 10.0);
        assertFalse(explore instanceof PrimitiveAction);
    }

    @Test
    void noTargetIsOneSharedValue() {
        assertSame(PrimitiveTarget.NONE, Primitives.jump().getTarget());
        assertEquals(new PrimitiveTarget.None(), PrimitiveTarget.NONE);
    }
}
