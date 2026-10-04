package me.herry.minecraftAI.events;

import me.herry.minecraftAI.ai.AIController;
import me.herry.minecraftAI.ai.AIDebugger;
import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.commands.AICommand;
import me.herry.minecraftAI.view.InventoryViews;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldSaveEvent;

/**
 * AI 플레이어에게 일어난 일(공격받음, 죽음, 퇴장, 월드 이동)을 AI 쪽에 알려 준다.
 * 진짜 플레이어에 대한 이벤트는 건드리지 않는다.
 */
public class AIPlayerListener implements Listener {
    private final AIController controller;
    private final AIDebugger debugger;

    public AIPlayerListener(AIController controller, AIDebugger debugger) {
        this.controller = controller;
        this.debugger = debugger;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        AIPlayer ai = controller.getByEntity(event.getEntity());
        if (ai == null) return;

        // 화살에 맞았으면 화살이 아니라 쏜 쪽을 공격자로 기억한다.
        Entity attacker = event.getDamager();
        if (attacker instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) attacker = shooter;
        // 무엇에게 얼마나 맞았는지 남겨 둔다. 전투 판단이 맞았는지 나중에 로그로 따져 볼 수 있다.
        ai.debug("Hit by " + attacker.getType() + " for " + String.format("%.1f", event.getFinalDamage()));
        ai.onAttacked(attacker);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        controller.handleDeath(event.getEntity());
    }

    // 가짜 플레이어는 접속을 끊으라는 패킷을 받을 클라이언트가 없어서 킥을 당해도 서버에 그대로 남는다.
    // 킥이나 밴을 당하면 직접 제거해서 진짜 플레이어가 킥당했을 때와 같은 결과가 되게 한다.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        controller.handleKick(event.getPlayer());
    }

    // 사람이 채팅에서 AI 의 이름을 부르면 AI 가 대답한다. 채팅 이벤트는 다른 스레드에서 오므로 메인 스레드로 넘긴다.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        controller.handlePlayerChat(event.getPlayer().getUniqueId(), event.getPlayer().getName(), message);
    }

    // 데이터팩을 다시 불러오면 레시피가 바뀔 수 있다.
    @EventHandler
    public void onResourcesReloaded(ServerResourcesReloadedEvent event) {
        controller.handleRecipesChanged();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldSave(WorldSaveEvent event) {
        controller.handleWorldSave();
    }

    // 도구가 부서지면 그 뒤의 진행이 크게 느려진다. 왜 느려졌는지 나중에 찾을 수 있게 로그에 남긴다.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemBreak(PlayerItemBreakEvent event) {
        AIPlayer ai = controller.getByEntity(event.getPlayer());
        if (ai != null) ai.debug("Tool broke: " + event.getBrokenItem().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        controller.handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        controller.handleWorldChange(event.getPlayer());
    }

    // 관리자가 AI 를 우클릭하면 AI 의 인벤토리를 보여 준다. 손이 두 개라 이벤트가 두 번 오므로 주로 쓰는 손만 처리한다.
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        AIPlayer ai = controller.getByEntity(event.getRightClicked());
        Player viewer = event.getPlayer();
        if (ai == null || controller.getByEntity(viewer) != null || !viewer.hasPermission(AICommand.PERMISSION)) return;
        event.setCancelled(true);
        controller.openInventory(viewer, ai);
    }

    // AI 인벤토리 창은 보기만 할 수 있다. 아이템을 꺼내거나 넣지 못하게 막는다.
    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (InventoryViews.isView(event.getView().getTopInventory())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (InventoryViews.isView(event.getView().getTopInventory())) event.setCancelled(true);
    }
}
