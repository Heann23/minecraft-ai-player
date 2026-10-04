package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

/**
 * 인벤토리의 아이템을 손에 든다. Material 을 지정하지 않으면 가장 강한 무기를 든다.
 */
public final class EquipItemAction extends AbstractAction {
    private final @Nullable Material material;

    private EquipItemAction(@Nullable Material material) {
        super("EquipItem", 5);
        this.material = material;
    }

    public static EquipItemAction of(Material material) {
        return new EquipItemAction(material);
    }

    public static EquipItemAction bestWeapon() {
        return new EquipItemAction(null);
    }

    @Override
    protected void onStart(AIPlayer ai) {
        if (material == null) {
            // 무기가 없으면 맨손으로 싸우면 되므로 실패로 치지 않는다.
            int slot = ai.getInventory().bestWeaponSlot();
            if (slot >= 0) ai.getInventory().equipSlot(slot);
            succeed();
            return;
        }

        if (ai.getInventory().equip(material)) succeed();
        else fail("item not in inventory");
    }

    @Override
    protected void onTick(AIPlayer ai) {
    }
}
