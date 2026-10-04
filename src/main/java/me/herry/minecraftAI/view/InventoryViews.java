package me.herry.minecraftAI.view;

import me.herry.minecraftAI.ai.AIPlayer;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * 열려 있는 AI 인벤토리 창들을 관리한다. 창이 열려 있는 동안 내용을 주기적으로 새로 고친다.
 */
public final class InventoryViews {
    private static final int REFRESH_INTERVAL = 10;

    private final List<AIInventoryView> open = new ArrayList<>();
    private int ticks;

    public void open(Player viewer, AIPlayer ai) {
        AIInventoryView view = new AIInventoryView(ai);
        view.refresh();
        viewer.openInventory(view.getInventory());
        open.add(view);
    }

    // 매 틱 호출된다. 닫힌 창은 목록에서 빼고, 열린 창은 정해진 간격마다 새로 고친다.
    public void tick() {
        if (open.isEmpty() || ++ticks % REFRESH_INTERVAL != 0) return;
        open.removeIf(view -> view.getInventory().getViewers().isEmpty());
        for (AIInventoryView view : open) {
            if (view.getAI().getBody().isUsable()) view.refresh();
        }
    }

    // AI 가 제거될 때 그 AI 의 창을 보고 있던 사람들의 창을 닫는다.
    public void closeAll(AIPlayer ai) {
        List<AIInventoryView> closing = new ArrayList<>();
        for (AIInventoryView view : open) {
            if (view.getAI() == ai) closing.add(view);
        }
        open.removeAll(closing);
        for (AIInventoryView view : closing) {
            for (HumanEntity viewer : new ArrayList<>(view.getInventory().getViewers())) viewer.closeInventory();
        }
    }

    public void closeEverything() {
        List<AIInventoryView> closing = new ArrayList<>(open);
        open.clear();
        for (AIInventoryView view : closing) {
            for (HumanEntity viewer : new ArrayList<>(view.getInventory().getViewers())) viewer.closeInventory();
        }
    }

    // 이 창이 AI 인벤토리 보기 창인지. 이 창에서는 아이템을 옮길 수 없다.
    public static boolean isView(Inventory inventory) {
        return inventory.getHolder(false) instanceof AIInventoryView;
    }
}
