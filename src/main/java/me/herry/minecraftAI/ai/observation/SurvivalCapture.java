package me.herry.minecraftAI.ai.observation;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.consumable.ConsumeEffect;
import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.inventory.InventorySystem;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static me.herry.minecraftAI.ai.observation.SurvivalObservation.*;

/** Copies actual Paper components on the server thread. Described eligibility is not execution authority. */
public final class SurvivalCapture {
    private static final int MAX_EFFECTS = 64;

    private SurvivalCapture() {}

    public static SurvivalObservation capture(AIPlayer ai) {
        requireMainThread();
        Player player = ai.getPlayer();
        var maximum = player.getAttribute(Attribute.MAX_HEALTH);
        Body body = new Body(player.getHealth(), maximum == null ? player.getHealth() : maximum.getValue(),
                player.getAbsorptionAmount(), player.getFoodLevel(), player.getSaturation(), player.getExhaustion(),
                player.getRemainingAir(), player.getMaximumAir());
        List<Effect> active = player.getActivePotionEffects().stream().map(effect -> effect(effect, 1)).toList();
        List<Item> storage = new ArrayList<>();
        for (int slot = 0; slot < 36 && !ai.getWorkBudget().isExhausted(); slot++)
            storage.add(item(player, slot, player.getInventory().getItem(slot)));
        Map<String, Item> equipment = new LinkedHashMap<>();
        var inventory = player.getInventory();
        // Omitted equipment is UNKNOWN, not an empty slot, if the caller's tick budget was consumed.
        if (!ai.getWorkBudget().isExhausted()) equipment.put("MAIN_HAND", item(player, -1, inventory.getItemInMainHand()));
        if (!ai.getWorkBudget().isExhausted()) equipment.put("OFF_HAND", item(player, -1, inventory.getItemInOffHand()));
        if (!ai.getWorkBudget().isExhausted()) equipment.put("HEAD", item(player, -1, inventory.getHelmet()));
        if (!ai.getWorkBudget().isExhausted()) equipment.put("CHEST", item(player, -1, inventory.getChestplate()));
        if (!ai.getWorkBudget().isExhausted()) equipment.put("LEGS", item(player, -1, inventory.getLeggings()));
        if (!ai.getWorkBudget().isExhausted()) equipment.put("FEET", item(player, -1, inventory.getBoots()));
        boolean using = player.hasActiveItem();
        ItemStack held = using ? player.getActiveItem() : null;
        ActiveUse use = new ActiveUse(using, using ? player.getActiveItemHand().name() : "NONE",
                held == null || held.isEmpty() ? "AIR" : held.getType().name(),
                using && !ai.getWorkBudget().isExhausted() ? fingerprint(held) : "",
                using ? Math.max(0, player.getActiveItemUsedTime()) : 0,
                using ? Math.max(0, player.getActiveItemRemainingTime()) : 0);
        return new SurvivalObservation(SCHEMA_VERSION, ai.getTicks(), Integer.toUnsignedLong(Bukkit.getCurrentTick()),
                player.getWorld().getUID().toString(), body, active, storage, equipment, use, storage.size() == 36);
    }

    /** Includes names, durability and all components; quantity is a separate choice/precondition. */
    public static String fingerprint(ItemStack stack) {
        requireMainThread();
        if (stack == null || stack.isEmpty()) return "";
        ItemStack normalized = stack.clone();
        normalized.setAmount(1);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized.serializeAsBytes()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static Item item(Player player, int slot, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return new Item(slot, "AIR", 0, "", -1, -1, nonConsumable());
        Integer damage = stack.getData(DataComponentTypes.DAMAGE);
        Integer maximum = stack.getData(DataComponentTypes.MAX_DAMAGE);
        return new Item(slot, stack.getType().name(), stack.getAmount(), fingerprint(stack),
                damage == null ? -1 : damage, maximum == null ? -1 : maximum, consumption(player, stack));
    }

    private static Consumption nonConsumable() {
        return new Consumption(Eligibility.NOT_CONSUMABLE, "NONE", -1, false, 0, 0, false,
                List.of(), List.of(), false, false, true, "", "no consumable component");
    }

    private static Consumption consumption(Player player, ItemStack stack) {
        var component = stack.getData(DataComponentTypes.CONSUMABLE);
        if (component == null) return nonConsumable();
        int ticks = useTicks(component.consumeSeconds());
        var food = stack.getData(DataComponentTypes.FOOD);
        List<Effect> effects = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        boolean clear = false, teleport = false, known = true;
        int count = 0;
        for (ConsumeEffect consume : component.consumeEffects()) {
            if (++count > MAX_EFFECTS) { known = false; break; }
            if (consume instanceof ConsumeEffect.ApplyStatusEffects apply) {
                for (PotionEffect potion : apply.effects()) {
                    if (effects.size() >= MAX_EFFECTS) { known = false; break; }
                    effects.add(effect(potion, apply.probability()));
                }
            } else if (consume instanceof ConsumeEffect.RemoveStatusEffects remove) {
                for (var key : remove.removeEffects()) {
                    if (removed.size() >= MAX_EFFECTS) { known = false; break; }
                    removed.add(key.key().asString());
                }
            } else if (consume instanceof ConsumeEffect.ClearAllStatusEffects) clear = true;
            else if (consume instanceof ConsumeEffect.TeleportRandomly) teleport = true;
            else if (!(consume instanceof ConsumeEffect.PlaySound)) known = false;
        }
        // These item types apply additional components after normal consumption. Never infer effects from their name.
        var potion = stack.getData(DataComponentTypes.POTION_CONTENTS);
        if (potion != null) {
            if (stack.getType() == org.bukkit.Material.POTION) {
                Float scale = stack.getData(DataComponentTypes.POTION_DURATION_SCALE);
                for (PotionEffect described : potion.allEffects()) {
                    if (effects.size() >= MAX_EFFECTS) { known = false; break; }
                    Effect raw = effect(described, 1);
                    // Scaling implementation and instant-effect magnitude are not guessed here.
                    boolean durationKnown = scale == null || Float.compare(scale, 1.0F) == 0 || raw.infinite() || raw.instant();
                    effects.add(new Effect(raw.key(), raw.amplifier(), durationKnown ? raw.durationTicks() : -1,
                            raw.infinite(), raw.instant(), raw.probability(), raw.category(), durationKnown));
                    known &= durationKnown;
                }
            } else known = false;
        }
        if (stack.hasData(DataComponentTypes.SUSPICIOUS_STEW_EFFECTS)) known = false;
        var remainder = stack.getData(DataComponentTypes.USE_REMAINDER);
        String container = remainder == null ? "" : remainder.transformInto().getType().name();
        Eligibility eligibility;
        String reason;
        if (ticks < 0 || !known) { eligibility = Eligibility.UNKNOWN; reason = "timing or additional effects not fully described"; }
        else if (player.isDead() || player.isSleeping() || player.hasActiveItem() || player.hasCooldown(stack)) {
            eligibility = Eligibility.BLOCKED; reason = "body, active hand use or item cooldown";
        } else if (food != null && player.getFoodLevel() >= 20 && !food.canAlwaysEat()) {
            eligibility = Eligibility.BLOCKED; reason = "requires hunger";
        } else { eligibility = Eligibility.AVAILABLE; reason = "component permits consumption; executor must recheck world, slot and safety"; }
        boolean safe = food != null && InventorySystem.isSafeFood(stack.getType()) && known && !clear && !teleport
                && removed.isEmpty() && effects.stream().allMatch(effect -> effect.category().equals("BENEFICIAL"));
        return new Consumption(eligibility, component.animation().name(), ticks, safe,
                food == null ? 0 : food.nutrition(), food == null ? 0 : Math.max(0, food.saturation()),
                food != null && food.canAlwaysEat(), effects, removed, clear, teleport, known, container, reason);
    }

    static int useTicks(float seconds) {
        double ticks = Math.ceil(seconds * 20.0F);
        return !Float.isFinite(seconds) || seconds < 0 || ticks > Integer.MAX_VALUE ? -1 : (int) ticks;
    }

    private static Effect effect(PotionEffect effect, double probability) {
        return new Effect(effect.getType().getKey().toString(), effect.getAmplifier(), effect.getDuration(),
                effect.isInfinite(), effect.getType().isInstant(), probability,
                effect.getType().getEffectCategory().name(), true);
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("survival capture requires the server main thread");
    }
}
