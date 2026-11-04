package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.observation.SurvivalCapture;
import net.minecraft.world.inventory.ContainerInput;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.UUID;

/** Exact observed inventory operation. Vanilla menu slots decide compatibility and removal permissions. */
final class ExactEquipment {
    record Stack(String material, int amount, String fingerprint, double damageFraction, java.util.Map<String, Integer> enchantments) {
        Stack(String material, int amount, String fingerprint, double damageFraction) {
            this(material, amount, fingerprint, damageFraction, java.util.Map.of());
        }
        Stack {
            Objects.requireNonNull(material); Objects.requireNonNull(fingerprint);
            enchantments = java.util.Map.copyOf(enchantments);
            if (amount < 0 || !Double.isFinite(damageFraction) || damageFraction < 0 || damageFraction > 1
                    || !(amount == 0 ? fingerprint.isEmpty() : fingerprint.matches("[0-9a-f]{64}")))
                throw new IllegalArgumentException("invalid observed equipment stack");
        }
    }
    record Choice(UUID world, int source, int destination, int held, Stack from, Stack to) {
        Choice {
            Objects.requireNonNull(world); Objects.requireNonNull(from); Objects.requireNonNull(to);
            if (menuSlot(source) < 0 || menuSlot(destination) < 0 || source == destination || held < 0 || held > 8
                    || !(destination == held || destination >= 36)) throw new IllegalArgumentException("invalid equipment slots");
        }
        boolean selectHotbar() { return destination == held && source < 9; }
    }
    record Amounts(int source, int destination) {}

    private ExactEquipment() {}

    static int menuSlot(int inventorySlot) {
        if (inventorySlot >= 0 && inventorySlot < 9) return 36 + inventorySlot;
        if (inventorySlot >= 9 && inventorySlot < 36) return inventorySlot;
        if (inventorySlot >= 36 && inventorySlot < 40) return 44 - inventorySlot;
        return inventorySlot == 40 ? 45 : -1;
    }

    static Amounts amounts(int source, int destination, int capacity) {
        if (source < 0 || destination < 0 || capacity <= 0 || source == 0 && destination == 0) return null;
        if (source == 0) return new Amounts(destination, 0);
        if (destination > 0) return source <= capacity ? new Amounts(destination, source) : null;
        int moved = Math.min(source, capacity);
        return new Amounts(source - moved, moved);
    }

    static Stack read(Player player, int slot) {
        var item = player.getInventory().getItem(slot);
        if (item == null || item.isEmpty()) return new Stack("AIR", 0, "", 0);
        Integer damage = item.getData(io.papermc.paper.datacomponent.DataComponentTypes.DAMAGE);
        Integer maximum = item.getData(io.papermc.paper.datacomponent.DataComponentTypes.MAX_DAMAGE);
        var enchantments = new java.util.HashMap<String, Integer>();
        item.getEnchantments().forEach((key, level) -> enchantments.put(key.getKey().toString(), level));
        return new Stack(item.getType().name(), item.getAmount(), SurvivalCapture.fingerprint(item),
                maximum == null || maximum <= 0 ? 0 : Math.clamp((damage == null ? 0 : damage) / (double) maximum, 0, 1), enchantments);
    }

    static boolean matches(Player player, Choice choice) {
        return player.getWorld().getUID().equals(choice.world()) && player.getInventory().getHeldItemSlot() == choice.held()
                && same(read(player, choice.source()), choice.from()) && same(read(player, choice.destination()), choice.to());
    }

    private static boolean same(Stack left, Stack right) {
        return left.amount() == right.amount() && left.fingerprint().equals(right.fingerprint());
    }

    static boolean allowed(Player player, Choice choice) {
        return matches(player, choice) && offered(player, choice);
    }

    /** Caller has just captured its snapshots on this same server thread. Execution also checks exact fingerprints. */
    static boolean offered(Player player, Choice choice) {
        if (choice.from().amount() == 0 && choice.to().amount() == 0
                || choice.from().amount() > 0 && choice.from().fingerprint().equals(choice.to().fingerprint())) return false;
        var handle = ((CraftPlayer) player).getHandle();
        var menu = handle.inventoryMenu;
        if (handle.containerMenu != menu || !menu.getCarried().isEmpty() || player.hasActiveItem()) return false;
        if (choice.selectHotbar()) return true;
        var source = menu.getSlot(menuSlot(choice.source()));
        var target = menu.getSlot(menuSlot(choice.destination()));
        var from = source.getItem(); var to = target.getItem();
        if (!from.isEmpty() && from.getCount() > source.getMaxStackSize(from)
                || !to.isEmpty() && to.getCount() > target.getMaxStackSize(to)) return false;
        if (!from.isEmpty() && (!source.mayPickup(handle) || !target.mayPlace(from))) return false;
        if (!to.isEmpty() && (!target.mayPickup(handle) || !source.mayPlace(to) || to.getCount() > source.getMaxStackSize(to))) return false;
        return amounts(from.getCount(), to.getCount(), from.isEmpty() ? 64 : target.getMaxStackSize(from)) != null;
    }

    static boolean apply(Player player, Choice choice) {
        if (!allowed(player, choice)) return false;
        if (choice.selectHotbar()) {
            player.getInventory().setHeldItemSlot(choice.source());
            return player.getInventory().getHeldItemSlot() == choice.source()
                    && same(read(player, choice.source()), choice.from()) && same(read(player, choice.destination()), choice.to());
        }
        var handle = ((CraftPlayer) player).getHandle();
        var menu = handle.inventoryMenu;
        var source = menu.getSlot(menuSlot(choice.source()));
        var target = menu.getSlot(menuSlot(choice.destination()));
        var expected = amounts(choice.from().amount(), choice.to().amount(),
                source.getItem().isEmpty() ? 64 : target.getMaxStackSize(source.getItem()));
        try {
            if (choice.from().amount() > 0) menu.clicked(menuSlot(choice.source()), 0, ContainerInput.PICKUP, handle);
            menu.clicked(menuSlot(choice.destination()), 0, ContainerInput.PICKUP, handle);
        } finally {
            // Return the remaining stack or the replaced item through the same real inventory slot.
            if (!menu.getCarried().isEmpty()) menu.clicked(menuSlot(choice.source()), 0, ContainerInput.PICKUP, handle);
            menu.broadcastChanges();
        }
        var afterSource = read(player, choice.source()); var afterTarget = read(player, choice.destination());
        String sourceFingerprint = choice.to().amount() > 0 ? choice.to().fingerprint() : choice.from().fingerprint();
        String targetFingerprint = choice.from().fingerprint();
        return menu.getCarried().isEmpty() && expected != null
                && afterSource.amount() == expected.source() && afterTarget.amount() == expected.destination()
                && (afterSource.amount() == 0 || afterSource.fingerprint().equals(sourceFingerprint))
                && (afterTarget.amount() == 0 || afterTarget.fingerprint().equals(targetFingerprint));
    }

    /** Move only the selected stack to the active hand through vanilla hotbar swap semantics. */
    static boolean selectForUse(Player player, int slot, Stack expected) {
        if (slot < 0 || slot >= 36 || expected.amount() == 0 || !same(read(player, slot), expected)) return false;
        var handle = ((CraftPlayer) player).getHandle();
        var menu = handle.inventoryMenu;
        if (handle.containerMenu != menu || !menu.getCarried().isEmpty() || player.hasActiveItem()) return false;
        if (slot < 9) {
            player.getInventory().setHeldItemSlot(slot);
            return player.getInventory().getHeldItemSlot() == slot && same(read(player, slot), expected);
        }
        int held = player.getInventory().getHeldItemSlot();
        var previousHand = read(player, held);
        // SWAP preserves whole stacks, including identical fingerprints with different counts.
        // The real menu decides permissions; denied/partial transfers never become consumption success.
        menu.clicked(menuSlot(slot), held, ContainerInput.SWAP, handle);
        menu.broadcastChanges();
        return menu.getCarried().isEmpty() && player.getInventory().getHeldItemSlot() == held
                && same(read(player, held), expected) && same(read(player, slot), previousHand);
    }
}
