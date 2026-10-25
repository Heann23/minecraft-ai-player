package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.*;
import me.herry.minecraftAI.ai.crafting.RecipeOption;
import me.herry.minecraftAI.ai.observation.SurvivalCapture;
import me.herry.minecraftAI.ai.observation.SurvivalObservation;
import me.herry.minecraftAI.ai.plan.Progression;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand;
import me.herry.minecraftAI.ai.primitive.control.ToolChoice;
import me.herry.minecraftAI.ai.primitive.runtime.PrimitiveAdapter;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Bukkit;
import org.bukkit.Material;

import java.util.*;

/** Enumerates supported concrete inputs. No Goal score, utility, progression order, best-food/tool or Teacher selection. */
public final class PolicyCandidates {
    public record Candidate(String id, PrimitiveCommand command, RecipeOption recipe, SurvivalObservation.Item consumable,
                            PolicyFrame.Option encoded) {
        public Action create(AIPlayer ai) {
            if (recipe != null) return new CraftItemAction(Material.valueOf(recipe.result()), recipe.resultAmount(), recipe.id());
            if (consumable != null) return new ConsumeExactAction(ai.getWorldId(), consumable);
            if (command instanceof PrimitiveCommand.Attack hit) return new StrikeExactAction(ai.getWorldId(), hit.entityId());
            return PrimitiveAdapter.create(ai, command);
        }
    }
    public record Capture(PolicyFrame frame, List<Candidate> candidates, SurvivalObservation.Body body, Map<String, Integer> stock) {
        public Capture { candidates = List.copyOf(candidates); stock = Map.copyOf(stock); }
    }
    private final List<RecipeOption> recipes = new ArrayList<>();
    private final Set<String> recipeIds = new HashSet<>();
    private int recipeCursor;
    private long recipeRevision = Long.MIN_VALUE;

    public Capture capture(AIPlayer ai, Candidate active, int activeTicks) {
        var state = PrimitiveAdapter.capture(ai);
        double[] body = new double[PolicyFrame.STATE_SIZE];
        var p = ai.getPlayer();
        var survival = SurvivalCapture.body(p);
        body[0] = survival.health() / Math.max(1, survival.maxHealth()); body[1] = survival.foodLevel() / 20.0;
        body[2] = survival.saturation() / 20; body[3] = survival.remainingAir() / (double) Math.max(1, survival.maximumAir());
        body[4] = Math.min(1, survival.absorption() / 20); body[5] = p.isInLava() ? 1 : 0;
        body[6] = p.getFireTicks() > 0 ? 1 : 0; body[7] = p.isInWater() ? 1 : 0;
        body[8] = ai.getBody().isGrounded() ? 1 : 0; body[9] = p.getFreezeTicks() / (double) Math.max(1, p.getMaxFreezeTicks());
        body[10] = Math.clamp(p.getVelocity().getY() / 4, -1, 1); body[11] = Math.min(1, p.getFallDistance() / 16);
        body[12] = p.getLocation().getPitch() / 90; body[13] = Math.sin(p.getWorld().getTime() * 2 * Math.PI / 24000);
        body[14] = Math.cos(p.getWorld().getTime() * 2 * Math.PI / 24000); body[15] = p.getWorld().hasStorm() ? 1 : 0;
        for (var effect : SurvivalCapture.effects(p)) body[16 + bucket(effect.key(), 16)] += Math.min(1, (effect.amplifier() + 1) / 8.0);
        if (active != null) {
            body[40 + bucket(active.id(), 7)] = 1;
            body[47] = Math.min(1, activeTicks / 200.0);
        }
        body[32 + bucket(p.getInventory().getItemInMainHand().getType().name(), 8)] = 1;
        for (var item : state.inventory()) body[48 + bucket(item.material(), 16)] += item.amount() / 64.0;
        for (int i = 0; i < body.length; i++) body[i] = Math.clamp(body[i], -16, 16);
        Map<String, Candidate> found = new LinkedHashMap<>();
        // Waiting is always a legal input choice, without awarding it any hand-written priority.
        add(ai, found, "wait:5", new PrimitiveCommand.Wait(5), null, null, 0, 0, 0, "", false);
        if (active != null) found.putIfAbsent(active.id(), active);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            for (int dy : new int[]{0, 1, -1}) {
                BlockPoint target = ai.getPosition().offset(dx * 2, dy, dz * 2);
                add(ai, found, "move:" + target.encode(), new PrimitiveCommand.MoveTo(target, 0.5), null, null, dx * 2, dy, dz * 2, "", true);
            }
        }
        for (int yaw : new int[]{-90, -45, 45, 90}) {
            double angle = Math.toRadians(p.getLocation().getYaw() + yaw);
            var eye = p.getEyeLocation();
            var target = new PrimitiveTarget.Point(eye.getX() - Math.sin(angle) * 4, eye.getY(), eye.getZ() + Math.cos(angle) * 4);
            add(ai, found, "look:" + yaw, new PrimitiveCommand.LookAt(target), null, null, -Math.sin(angle) * 4, 0, Math.cos(angle) * 4, "", true);
        }
        add(ai, found, "jump", new PrimitiveCommand.Jump(), null, null, 0, 0, 0, "", true);
        List<BlockPoint> targets = new ArrayList<>();
        var feet = ai.getPosition();
        boolean rayLoaded = true;
        for (int dx : new int[]{-5, 5}) for (int dz : new int[]{-5, 5})
            rayLoaded &= p.getWorld().isChunkLoaded((feet.x() + dx) >> 4, (feet.z() + dz) >> 4);
        var ray = rayLoaded ? p.getTargetBlockExact(4) : null;
        if (ray != null) targets.add(Positions.of(ray));
        // Inspect only local visible cells. Unloaded/hidden cells never become material observations.
        for (int dx = -2; dx <= 2 && targets.size() < 24; dx++) for (int dz = -2; dz <= 2 && targets.size() < 24; dz++)
            for (int dy : new int[]{0, 1, 2}) if (targets.size() < 24) targets.add(ai.getPosition().offset(dx, dy, dz));
        var inspected = PrimitiveAdapter.inspect(ai, targets.stream().distinct().limit(24).toList());
        int mined = 0;
        for (var slot : state.inventory()) {
            if (slot.slot() >= 36 || mined >= 32) continue;
            ToolChoice tool = new ToolChoice(slot.slot(), slot.material(), slot.enchantments());
            for (var block : inspected.blocks()) {
                if (!block.visible() || !block.loaded() || mined >= 32) continue;
                var delta = block.point().offset(-ai.getPosition().x(), -ai.getPosition().y(), -ai.getPosition().z());
                String id = "mine:" + block.point().encode() + ":" + slot.slot();
                if (add(ai, found, id, new PrimitiveCommand.BreakBlock(block.point(), tool), null, null,
                        delta.x(), delta.y(), delta.z(), block.material() + ":" + slot.material(), true)) mined++;
            }
        }
        int equipped = 0, placed = 0;
        for (var slot : state.inventory()) {
            if (slot.slot() >= 36 || slot.amount() == 0 || ai.getWorkBudget().isExhausted()) continue;
            if (equipped++ < 12) add(ai, found, "equip:" + slot.slot(), new PrimitiveCommand.Equip(
                    new ToolChoice(slot.slot(), slot.material(), slot.enchantments())), null, null, 0, 0, 0, slot.material(), true);
            var item = SurvivalCapture.item(ai, slot.slot());
            if (ConsumeExactAction.supported(item)) add(ai, found, "consume:" + slot.slot(), null, null, item, 0, 0, 0, item.material(), false);
            Material material = Material.matchMaterial(slot.material());
            if (material != null && material.isBlock() && placed < 12) {
                for (int[] direction : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                    BlockPoint target = ai.getPosition().offset(direction[0], 0, direction[1]);
                    if (add(ai, found, "place:" + target.encode() + ":" + material, new PrimitiveCommand.PlaceBlock(target, material.name()),
                            null, null, direction[0], 0, direction[1], material.name(), true)) placed++;
                }
            }
        }
        var livingTargets = new LinkedHashMap<java.util.UUID, org.bukkit.entity.LivingEntity>();
        for (var entity : ai.getPerception().getHostiles()) livingTargets.putIfAbsent(entity.getUniqueId(), entity);
        for (var entity : ai.getPerception().getAnimals()) livingTargets.putIfAbsent(entity.getUniqueId(), entity);
        for (var hostile : livingTargets.values()) {
            if (ai.getWorkBudget().isExhausted()) break;
            if (!StrikeExactAction.canReach(ai, hostile)) continue;
            var at = hostile.getLocation();
            add(ai, found, "attack:" + hostile.getUniqueId(), new PrimitiveCommand.Attack(hostile.getUniqueId()), null, null,
                    at.getX() - p.getLocation().getX(), at.getY() - p.getLocation().getY(), at.getZ() - p.getLocation().getZ(), hostile.getType().name(), true);
        }
        add(ai, found, "pickup:4", new PrimitiveCommand.Pickup(4), null, null, 0, 0, 0, "", true);
        advanceRecipes(ai);
        int crafted = 0;
        var stock = ai.getInventory().snapshot();
        for (var recipe : recipes) {
            if (crafted >= 16 || ai.getWorkBudget().isExhausted()) break;
            Material result = Material.matchMaterial(recipe.result());
            if (result == null || recipe.needsTable() && Progression.nearbyTable(ai) == null
                    || ai.getCrafting().singleStep(result, recipe.id(), stock) == null) continue;
            if (add(ai, found, "craft:" + recipe.id(), null, recipe, null, 0, 0, 0, recipe.id(), false)) crafted++;
        }
        // Fair category interleaving prevents an inventory full of block variants from hiding every recipe.
        var candidates = selectFair(List.copyOf(found.values()), active == null ? "" : active.id());
        Map<String, Integer> stockCounts = new HashMap<>();
        for (var item : state.inventory()) if (item.amount() > 0) stockCounts.merge(item.material(), item.amount(), Integer::sum);
        return new Capture(new PolicyFrame(ai.getWorldId(), Integer.toUnsignedLong(Bukkit.getCurrentTick()), body,
                candidates.stream().map(Candidate::encoded).toList(), inspected.complete() && !ai.getWorkBudget().isExhausted()
                && recipeCursor >= Material.values().length && found.size() <= PolicyFrame.MAX_OPTIONS), candidates, survival, stockCounts);
    }

    private void advanceRecipes(AIPlayer ai) {
        if (recipeRevision != ai.getCrafting().revision()) { recipes.clear(); recipeIds.clear(); recipeCursor = 0; recipeRevision = ai.getCrafting().revision(); }
        int budget = 4;
        Material[] materials = Material.values();
        while (recipeCursor < materials.length && budget-- > 0 && !ai.getWorkBudget().isExhausted()) {
            Material target = materials[recipeCursor++];
            if (!target.isItem() || target.isAir()) continue;
            for (var option : ai.getCrafting().recipeOptions(target)) if (option.countPlannable() && !option.smelting() && recipeIds.add(option.id())) recipes.add(option);
        }
    }

    private boolean add(AIPlayer ai, Map<String, Candidate> found, String id, PrimitiveCommand command, RecipeOption recipe,
                        SurvivalObservation.Item consumable, double dx, double dy, double dz, String identity, boolean check) {
        if (found.size() >= 512 || found.containsKey(id)) return false;
        if (check && (ai.getWorkBudget().isExhausted() || !PrimitiveAdapter.check(ai, command).allowed())) return false;
        double[] features = new double[PolicyFrame.ACTION_SIZE];
        PrimitiveType type = recipe != null ? PrimitiveType.CRAFT_ITEM : consumable != null ? PrimitiveType.USE_ITEM : command.type();
        features[type.ordinal()] = 1;
        features[24] = Math.clamp(dx / 16, -1, 1); features[25] = Math.clamp(dy / 16, -1, 1); features[26] = Math.clamp(dz / 16, -1, 1);
        if (consumable != null) { features[27] = consumable.amount() / 64.0; features[28] = consumable.consumption().useTicks() / 200.0; features[29] = consumable.slot() / 35.0; }
        if (recipe != null) features[27] = recipe.resultAmount() / 64.0;
        features[32 + bucket(identity, 16)] = 1; features[48 + bucket(id, 16)] = 1;
        found.put(id, new Candidate(id, command, recipe, consumable, new PolicyFrame.Option(id, features)));
        return true;
    }

    static List<Candidate> selectFair(List<Candidate> all, String activeId) {
        List<Candidate> selected = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Candidate candidate : all) if (candidate.id().equals(activeId)) { selected.add(candidate); ids.add(candidate.id()); }
        Map<PrimitiveType, ArrayDeque<Candidate>> groups = new EnumMap<>(PrimitiveType.class);
        for (Candidate candidate : all) {
            PrimitiveType type = candidate.recipe() != null ? PrimitiveType.CRAFT_ITEM
                    : candidate.consumable() != null ? PrimitiveType.USE_ITEM : candidate.command().type();
            groups.computeIfAbsent(type, ignored -> new ArrayDeque<>()).add(candidate);
        }
        boolean remaining = true;
        while (selected.size() < PolicyFrame.MAX_OPTIONS && remaining) {
            remaining = false;
            for (var group : groups.values()) {
                if (group.isEmpty()) continue;
                remaining = true;
                Candidate candidate = group.removeFirst();
                if (ids.add(candidate.id())) selected.add(candidate);
                if (selected.size() == PolicyFrame.MAX_OPTIONS) break;
            }
        }
        return List.copyOf(selected);
    }

    private static int bucket(String text, int size) { return Math.floorMod(text.hashCode(), size); }
}
