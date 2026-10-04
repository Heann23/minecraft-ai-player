package me.herry.minecraftAI.ai;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.jetbrains.annotations.Nullable;

/**
 * AI 의 닉네임 위에 현재 목표를 띄우는 글자.
 * 글자 엔티티를 플레이어에 태우면 순간이동이나 포탈 이용이 막히는 부작용이 있어서,
 * 태우지 않고 매 틱 머리 위로 옮겨 준다. 서버에 저장되지 않는 엔티티라서 서버가 꺼지면 함께 사라진다.
 */
final class GoalLabel {
    // 닉네임이 머리 위 약 2.3칸 높이에 그려지므로 그보다 조금 위에 둔다.
    private static final double HEIGHT = 2.6;
    // 위치가 틱마다 끊겨 보이지 않도록 클라이언트가 이 틱 수에 걸쳐 부드럽게 옮긴다.
    private static final int SMOOTHING_TICKS = 2;

    private @Nullable TextDisplay display;
    private String lastText = "";

    void update(Player player, String text, NamedTextColor color) {
        Location location = player.getLocation().add(0.0, HEIGHT, 0.0);
        location.setYaw(0.0F);
        location.setPitch(0.0F);

        // 월드를 옮겼거나 엔티티가 사라졌으면 새로 만든다.
        if (display == null || !display.isValid() || !display.getWorld().equals(player.getWorld())) {
            remove();
            display = player.getWorld().spawn(location, TextDisplay.class, created -> {
                created.setPersistent(false);
                created.setBillboard(Display.Billboard.CENTER);
                created.setTeleportDuration(SMOOTHING_TICKS);
                created.setShadowed(true);
                created.text(Component.text(text, color));
            });
            lastText = text;
            return;
        }

        display.teleport(location);
        if (!text.equals(lastText)) {
            display.text(Component.text(text, color));
            lastText = text;
        }
    }

    void remove() {
        if (display != null && display.isValid()) display.remove();
        display = null;
        lastText = "";
    }
}
