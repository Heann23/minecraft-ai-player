package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.util.BlockPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

/** One explicit recipe, using actual grid/result slots. No inventory material exchange or recursive crafting. */
public final class VanillaCraftAction extends AbstractAction {
    private final Material target;
    private final String recipeId;
    private java.util.UUID world;
    private Recipe recipe;
    private BlockPoint table;
    private AbstractCraftingMenu menu;
    private RecipeChoice[] layout;
    private ItemStack[] expected;
    private ItemStack[] inventorySnapshot;
    private int[] sources;
    private int cell, phase, searchBudget;
    private boolean taken;

    public VanillaCraftAction(Material target, String recipeId) {
        super("VanillaCraft", 200); this.target = target; this.recipeId = recipeId;
    }
    @Override protected void onStart(AIPlayer ai) {
        var selected = ai.getCrafting().singleStep(target, recipeId, ai.getInventory().snapshot());
        if (selected == null || !ai.getBody().isUsable()) { fail("selected recipe unavailable"); return; }
        recipe = selected.recipe(); world = ai.getWorldId();
        var handle = ((CraftPlayer) ai.getPlayer()).getHandle();
        if (handle.containerMenu != handle.inventoryMenu || !handle.inventoryMenu.getCarried().isEmpty()
                || handle.inventoryMenu.getInputGridSlots().stream().anyMatch(slot -> !slot.getItem().isEmpty())) {
            fail("existing menu, cursor or crafting grid is occupied"); return;
        }
        ai.getNavigation().stop(); ai.getBody().clearInputs();
        if (selected.needsTable()) {
            table = NearbyBlocks.find(ai.getPlayer(), Material.CRAFTING_TABLE, 4);
            if (table == null) { fail("no reachable crafting table"); return; }
        } else initialize(ai, handle.inventoryMenu);
    }
    private void initialize(AIPlayer ai, AbstractCraftingMenu opened) {
        menu = opened;
        if (!menu.getCarried().isEmpty() || menu.getInputGridSlots().stream().anyMatch(slot -> !slot.getItem().isEmpty())) {
            // Never take ownership of another actor's grid or cursor.
            menu = null; fail("crafting grid occupied"); return;
        }
        layout = layout(recipe, menu.getGridWidth(), menu.getGridHeight());
        if (layout == null) { fail("unsupported recipe layout"); return; }
        sources = new int[layout.length]; expected = new ItemStack[layout.length];
        int[] remaining = new int[36];
        inventorySnapshot = new ItemStack[36];
        for (int slot = 0; slot < 36; slot++) {
            var stack = ai.getPlayer().getInventory().getItem(slot);
            remaining[slot] = stack == null ? 0 : stack.getAmount();
            inventorySnapshot[slot] = stack == null ? null : stack.clone();
        }
        searchBudget = 4096;
        if (!assign(ai, 0, remaining)) fail("ingredient allocation unavailable or search budget exhausted");
    }
    private boolean assign(AIPlayer ai, int at, int[] remaining) {
        if (--searchBudget < 0 || ai.getWorkBudget().isExhausted()) return false;
        if (at == layout.length) return true;
        if (layout[at] == null) return assign(ai, at + 1, remaining);
        for (int slot = 0; slot < 36 && searchBudget >= 0; slot++) {
            var stack = inventorySnapshot[slot];
            if (remaining[slot] <= 0 || stack == null || !layout[at].test(stack)) continue;
            remaining[slot]--; sources[at] = slot; expected[at] = stack.clone(); expected[at].setAmount(1);
            if (assign(ai, at + 1, remaining)) return true;
            remaining[slot]++;
        }
        return false;
    }
    static RecipeChoice[] layout(Recipe recipe, int width, int height) {
        var result = new RecipeChoice[width * height];
        if (recipe instanceof ShapedRecipe shaped) {
            var shape = shaped.getShape();
            if (shape.length > height) return null;
            var choices = shaped.getChoiceMap();
            for (int y = 0; y < shape.length; y++) {
                if (shape[y].length() > width) return null;
                for (int x = 0; x < shape[y].length(); x++) result[y * width + x] = choices.get(shape[y].charAt(x));
            }
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            if (shapeless.getChoiceList().size() > result.length) return null;
            for (int i = 0; i < shapeless.getChoiceList().size(); i++) result[i] = shapeless.getChoiceList().get(i);
        } else return null;
        return result;
    }
    private int storageSlot(net.minecraft.server.level.ServerPlayer handle, int slot) {
        return menu.slots.stream().filter(s -> s.container == handle.getInventory() && s.getContainerSlot() == slot)
                .findFirst().orElseThrow(() -> new IllegalStateException("missing storage menu slot")).index;
    }
    @Override protected void onTick(AIPlayer ai) {
        if (!ai.getBody().isUsable() || !ai.getWorldId().equals(world)) { fail("body or world changed"); return; }
        var player = ai.getPlayer(); var handle = ((CraftPlayer) player).getHandle();
        if (table != null) {
            if (!NearbyBlocks.is(player.getWorld(), table, Material.CRAFTING_TABLE)) { fail("crafting table disappeared"); return; }
            ai.getBody().lookAt(table.x() + .5, table.y() + 1, table.z() + .5);
        }
        if (menu == null) {
            var pos = new BlockPos(table.x(), table.y(), table.z());
            if (!handle.isWithinBlockInteractionRange(pos, 0)) { fail("crafting table outside interaction range"); return; }
            var hit = player.rayTraceBlocks(handle.blockInteractionRange());
            if (hit == null || hit.getHitBlock() == null || !hit.getHitBlock().getLocation().equals(
                    new org.bukkit.Location(player.getWorld(), table.x(), table.y(), table.z()))) return;
            handle.gameMode.useItemOn(handle, handle.level(), handle.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(new Vec3(table.x() + .5, table.y() + 1, table.z() + .5), Direction.UP, pos, false));
            if (!(handle.containerMenu instanceof CraftingMenu opened)) { fail("vanilla workbench interaction denied"); return; }
            initialize(ai, opened); return;
        }
        if (handle.containerMenu != menu || !menu.stillValid(handle)) { fail("crafting menu invalidated"); return; }
        while (cell < layout.length && layout[cell] == null) cell++;
        if (cell < layout.length) {
            int source = storageSlot(handle, sources[cell]);
            int destination = menu.getInputGridSlots().get(cell).index;
            if (phase == 0) {
                var actual = CraftItemStack.asBukkitCopy(menu.getSlot(source).getItem());
                if (!menu.getCarried().isEmpty() || !actual.isSimilar(expected[cell]) || !menu.getSlot(destination).getItem().isEmpty()) {
                    fail("ingredient or grid changed"); return;
                }
                menu.clicked(source, 0, ContainerInput.PICKUP, handle);
                if (!CraftItemStack.asBukkitCopy(menu.getCarried()).isSimilar(expected[cell])) { fail("ingredient pickup denied"); return; }
                phase = 1;
            } else if (phase == 1) {
                menu.clicked(destination, 1, ContainerInput.PICKUP, handle);
                var placed = CraftItemStack.asBukkitCopy(menu.getSlot(destination).getItem());
                if (placed.getAmount() != 1 || !placed.isSimilar(expected[cell])) { fail("ingredient placement denied"); return; }
                phase = 2;
            } else {
                if (!menu.getCarried().isEmpty()) menu.clicked(source, 0, ContainerInput.PICKUP, handle);
                if (!menu.getCarried().isEmpty()) { fail("ingredient return denied"); return; }
                cell++; phase = 0;
            }
        } else if (!taken) {
            var used = menu.resultSlots.getRecipeUsed();
            var output = CraftItemStack.asBukkitCopy(menu.getResultSlot().getItem());
            if (used == null || !(used.toBukkitRecipe() instanceof Keyed keyed) || !keyed.getKey().toString().equals(recipeId)
                    || !output.isSimilar(recipe.getResult()) || output.getAmount() != recipe.getResult().getAmount()
                    || !menu.getCarried().isEmpty()) { fail("selected vanilla result mismatch"); return; }
            menu.clicked(menu.getResultSlot().index, 0, ContainerInput.PICKUP, handle);
            if (!CraftItemStack.asBukkitCopy(menu.getCarried()).equals(output)) { fail("result pickup denied or changed"); return; }
            taken = true;
            ai.debug("Vanilla crafted result taken: recipe=" + recipeId + " material=" + output.getType() + " amount=" + output.getAmount());
        } else if (menu.getCarried().isEmpty()) succeed();
        else {
            boolean placed = false;
            for (int slot = 0; slot < 36; slot++) {
                int targetSlot = storageSlot(handle, slot); var targetStack = menu.getSlot(targetSlot).getItem();
                if (targetStack.isEmpty() || net.minecraft.world.item.ItemStack.isSameItemSameComponents(targetStack, menu.getCarried())
                        && targetStack.getCount() < menu.getSlot(targetSlot).getMaxStackSize(targetStack)) {
                    menu.clicked(targetSlot, 0, ContainerInput.PICKUP, handle); placed = true; break;
                }
            }
            if (!placed) fail("crafted output cannot fit in storage");
        }
        menu.broadcastChanges();
    }
    @Override protected void onEnd(AIPlayer ai) {
        var handle = ((CraftPlayer) ai.getPlayer()).getHandle();
        if (menu != null && handle.containerMenu == menu) handle.closeContainer();
        ai.getBody().clearInputs();
    }
}
