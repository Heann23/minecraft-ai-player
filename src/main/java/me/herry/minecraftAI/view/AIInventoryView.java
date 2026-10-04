package me.herry.minecraftAI.view;

import me.herry.minecraftAI.ai.AIPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * AI 의 인벤토리를 보여 주는 읽기 전용 창.
 * 실제 인벤토리를 그대로 열면 보는 사람이 아이템을 꺼내 갈 수 있으므로, 복사본을 보여 주고 주기적으로 새로 고친다.
 *
 * 배치(5줄): 1~3줄 = 가방(인벤토리 9~35번 칸), 4줄 = 핫바(0~8번 칸), 5줄 = 투구, 흉갑, 레깅스, 부츠, 왼손, 빈칸, 상태 정보.
 */
public final class AIInventoryView implements InventoryHolder {
    private static final int SIZE = 45;
    private static final int STORAGE_START = 9;
    private static final int STORAGE_SIZE = 36;
    private static final int HOTBAR_SIZE = 9;
    private static final int HOTBAR_ROW = 27;
    private static final int ARMOR_ROW = 36;
    private static final int INFO_SLOT = 44;

    private final AIPlayer ai;
    private final Inventory inventory;

    AIInventoryView(AIPlayer ai) {
        this.ai = ai;
        this.inventory = Bukkit.createInventory(this, SIZE, Component.text(ai.getName() + " 의 인벤토리"));
    }

    public AIPlayer getAI() {
        return ai;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    // AI 의 현재 인벤토리를 다시 복사해 온다.
    void refresh() {
        Player player = ai.getPlayer();
        PlayerInventory source = player.getInventory();
        for (int slot = STORAGE_START; slot < STORAGE_SIZE; slot++) inventory.setItem(slot - STORAGE_START, copy(source.getItem(slot)));
        for (int slot = 0; slot < HOTBAR_SIZE; slot++) inventory.setItem(HOTBAR_ROW + slot, copy(source.getItem(slot)));

        inventory.setItem(ARMOR_ROW, copy(source.getHelmet()));
        inventory.setItem(ARMOR_ROW + 1, copy(source.getChestplate()));
        inventory.setItem(ARMOR_ROW + 2, copy(source.getLeggings()));
        inventory.setItem(ARMOR_ROW + 3, copy(source.getBoots()));
        inventory.setItem(ARMOR_ROW + 4, copy(source.getItemInOffHand()));
        for (int slot = ARMOR_ROW + 5; slot < INFO_SLOT; slot++) inventory.setItem(slot, filler());
        inventory.setItem(INFO_SLOT, info(player, source.getHeldItemSlot()));
    }

    private static ItemStack copy(ItemStack item) {
        return item == null || item.isEmpty() ? null : item.clone();
    }

    private static ItemStack filler() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        pane.editMeta(meta -> meta.displayName(Component.text(" ")));
        return pane;
    }

    private ItemStack info(Player player, int heldSlot) {
        ItemStack paper = new ItemStack(Material.PAPER);
        paper.editMeta(meta -> {
            meta.displayName(plain(ai.getName() + " 의 상태", NamedTextColor.YELLOW));
            meta.lore(List.of(
                    line("상태", ai.getState().name()),
                    line("목표", ai.getCurrentGoal().name()),
                    line("행동", ai.getCurrentActionName()),
                    line("체력", String.valueOf((int) Math.ceil(player.getHealth()))),
                    line("허기", String.valueOf(player.getFoodLevel())),
                    line("손에 든 칸", "핫바 " + (heldSlot + 1) + "번")
            ));
        });
        return paper;
    }

    private static Component line(String label, String value) {
        return plain(label + ": ", NamedTextColor.GRAY).append(plain(value, NamedTextColor.WHITE));
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
