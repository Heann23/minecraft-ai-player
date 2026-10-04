package me.herry.minecraftAI.persist;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AI 의 몸 상태(인벤토리, 체력, 허기, 경험치, 리스폰 지점)를 저장하고 되돌린다.
 * 가짜 플레이어는 접속할 때 서버의 playerdata 를 읽지 않아서 매번 빈 인벤토리로 시작하므로, 플러그인이 직접 보관한다.
 * 저장해 둔 것을 되돌리는 것일 뿐, 없던 아이템을 만들어 주지는 않는다.
 */
public final class BodyState {
    private BodyState() {
    }

    public static Map<String, Object> capture(Player player) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("health", player.getHealth());
        state.put("food", player.getFoodLevel());
        state.put("saturation", (double) player.getSaturation());
        state.put("level", player.getLevel());
        state.put("exp", (double) player.getExp());
        putInventoryAndRespawn(state, player);
        return state;
    }

    /**
     * 죽어서 리스폰을 기다리는 동안의 몸 상태. 체력과 허기, 경험치는 되살아나면 새로 정해지므로 저장하지 않는다
     * (죽은 몸의 체력 0 을 저장하면 되살렸을 때 빈사 상태가 된다). 인벤토리는 그때 실제로 가진 것을 저장한다.
     * 보통은 죽으면서 다 떨어뜨려 비어 있고, 인벤토리 유지 규칙이 켜져 있으면 그대로 남아 있다.
     */
    public static Map<String, Object> captureDead(Player player) {
        Map<String, Object> state = new LinkedHashMap<>();
        putInventoryAndRespawn(state, player);
        return state;
    }

    private static void putInventoryAndRespawn(Map<String, Object> state, Player player) {
        state.put("heldSlot", player.getInventory().getHeldItemSlot());

        // getContents() 는 가방 36칸, 갑옷 4칸, 왼손 1칸 순서다. 칸 번호와 함께 저장해서 같은 자리에 되돌린다.
        List<String> items = new ArrayList<>();
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.isEmpty()) continue;
            items.add(slot + ":" + Base64.getEncoder().encodeToString(item.serializeAsBytes()));
        }
        state.put("items", items);

        Location respawn = player.getRespawnLocation();
        if (respawn != null && respawn.getWorld() != null) {
            state.put("respawn", respawn.getWorld().getUID() + "|" + respawn.getBlockX() + "," + respawn.getBlockY() + "," + respawn.getBlockZ());
        }
    }

    public static void apply(Player player, Map<String, Object> state, Logger logger) {
        if (state.isEmpty()) return;
        PlayerInventory inventory = player.getInventory();
        if (state.get("items") instanceof List<?> items) {
            inventory.clear();
            int size = inventory.getContents().length;
            for (Object line : items) {
                String text = String.valueOf(line);
                int colon = text.indexOf(':');
                if (colon <= 0) continue;
                try {
                    int slot = Integer.parseInt(text.substring(0, colon));
                    if (slot < 0 || slot >= size) continue;
                    inventory.setItem(slot, ItemStack.deserializeBytes(Base64.getDecoder().decode(text.substring(colon + 1))));
                } catch (RuntimeException e) {
                    // 버전이 바뀌어 읽을 수 없게 된 아이템 하나 때문에 나머지를 잃지 않도록 그 칸만 건너뛴다.
                    logger.log(Level.WARNING, "Could not restore an item of " + player.getName(), e);
                }
            }
        }
        if (state.get("heldSlot") instanceof Number held && held.intValue() >= 0 && held.intValue() < 9) {
            inventory.setHeldItemSlot(held.intValue());
        }

        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double max = maxHealth != null ? maxHealth.getValue() : 20.0;
        if (state.get("health") instanceof Number health) player.setHealth(Math.max(1.0, Math.min(max, health.doubleValue())));
        if (state.get("food") instanceof Number food) player.setFoodLevel(Math.max(0, Math.min(20, food.intValue())));
        if (state.get("saturation") instanceof Number saturation) player.setSaturation(saturation.floatValue());
        if (state.get("level") instanceof Number level) player.setLevel(Math.max(0, level.intValue()));
        if (state.get("exp") instanceof Number exp) player.setExp(Math.max(0.0F, Math.min(1.0F, exp.floatValue())));

        if (state.get("respawn") instanceof String respawn) restoreRespawn(player, respawn);
    }

    private static void restoreRespawn(Player player, String text) {
        String[] parts = text.split("\\|");
        if (parts.length != 2) return;
        try {
            World world = Bukkit.getWorld(UUID.fromString(parts[0]));
            String[] xyz = parts[1].split(",");
            if (world == null || xyz.length != 3) return;
            player.setRespawnLocation(new Location(world, Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])), true);
        } catch (IllegalArgumentException ignored) {
            // 저장된 리스폰 지점을 읽을 수 없으면 월드 스폰을 쓴다.
        }
    }
}
