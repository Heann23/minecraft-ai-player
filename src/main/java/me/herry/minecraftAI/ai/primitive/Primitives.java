package me.herry.minecraftAI.ai.primitive;

import me.herry.minecraftAI.ai.action.AttackEntityAction;
import me.herry.minecraftAI.ai.action.BreakBlockAction;
import me.herry.minecraftAI.ai.action.CraftItemAction;
import me.herry.minecraftAI.ai.action.DropJunkAction;
import me.herry.minecraftAI.ai.action.EatFoodAction;
import me.herry.minecraftAI.ai.action.EquipItemAction;
import me.herry.minecraftAI.ai.action.JumpAction;
import me.herry.minecraftAI.ai.action.LookAtAction;
import me.herry.minecraftAI.ai.action.MoveToAction;
import me.herry.minecraftAI.ai.action.PickupItemAction;
import me.herry.minecraftAI.ai.action.PlaceBlockAction;
import me.herry.minecraftAI.ai.action.SelectHotbarSlotAction;
import me.herry.minecraftAI.ai.action.WaitAction;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;

/**
 * 기본 행동을 종류별로 만드는 곳. 새 스킬은 개별 Action 클래스를 몰라도 여기 있는 것만 조합해서 계획을 세울 수 있다.
 * 만들어지는 것은 기존 Action 그대로다. 감싸지 않으므로 실행 루프와 기존 계획 함수가 보는 것과 같은 객체다.
 *
 * 상자, 화로, 침대를 쓰는 행동(INTERACT_BLOCK)은 어느 블록을 어떻게 쓸지에 따라 받는 값이 달라서 여기에 두지 않았다.
 * 그 행동들도 PrimitiveAction 이므로 종류와 대상은 똑같이 읽을 수 있다.
 */
public final class Primitives {
    // 블록을 캐거나 놓을 때 손이 닿는 거리 (눈에서 블록 중심까지)
    private static final double REACH = 4.0;

    private Primitives() {
    }

    // 그 칸에 손이 닿는 곳까지 걸어간다.
    public static PrimitiveAction moveWithinReach(BlockPoint target) {
        return new MoveToAction(PathGoal.reach(target, REACH), false);
    }

    // 그 칸에서 radius 안으로 걸어 들어간다.
    public static PrimitiveAction moveTo(BlockPoint target, double radius) {
        return new MoveToAction(PathGoal.arrive(target, radius), false);
    }

    public static PrimitiveAction lookAt(BlockPoint block) {
        return LookAtAction.block(block);
    }

    public static PrimitiveAction lookAt(double x, double y, double z) {
        return new LookAtAction(x, y, z);
    }

    public static PrimitiveAction jump() {
        return new JumpAction();
    }

    public static PrimitiveAction breakBlock(BlockPoint target) {
        return new BreakBlockAction(target);
    }

    // 서 있는 곳 가까이의 빈칸에 놓는다. 놓을 칸을 정해서 놓는 것은 집 짓기(BuildBlockAction)만 한다.
    public static PrimitiveAction placeBlock(Material material) {
        return new PlaceBlockAction(material, null);
    }

    // 가진 음식 중 가장 좋은 것을 먹는다. 아이템을 쓰는 행동은 지금 이것뿐이다.
    public static PrimitiveAction eat() {
        return new EatFoodAction();
    }

    public static PrimitiveAction attack(LivingEntity target) {
        return new AttackEntityAction(target);
    }

    // radius 안에 떨어져 있는 아이템을 줍는다.
    public static PrimitiveAction pickup(double radius) {
        return new PickupItemAction(radius, false);
    }

    // 쓰지 않는 아이템을 버려서 가방에 빈칸을 만든다.
    public static PrimitiveAction dropJunk() {
        return new DropJunkAction();
    }

    public static PrimitiveAction equip(Material material) {
        return EquipItemAction.of(material);
    }

    public static PrimitiveAction selectSlot(int slot) {
        return new SelectHotbarSlotAction(slot);
    }

    public static PrimitiveAction craft(Material item, int amount) {
        return new CraftItemAction(item, amount);
    }

    public static PrimitiveAction waitTicks(int ticks) {
        return new WaitAction(ticks);
    }
}
