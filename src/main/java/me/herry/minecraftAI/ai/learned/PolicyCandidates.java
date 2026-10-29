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
                            PolicyFrame.Option encoded, int commitmentTicks, String inputId, ExactEquipment.Choice equipment, MovementInput movement) {
        public Candidate(String id, PrimitiveCommand command, RecipeOption recipe, SurvivalObservation.Item consumable,
                         PolicyFrame.Option encoded, int commitmentTicks, String inputId, ExactEquipment.Choice equipment) {
            this(id, command, recipe, consumable, encoded, commitmentTicks, inputId, equipment, null);
        }
        public Candidate(String id, PrimitiveCommand command, RecipeOption recipe, SurvivalObservation.Item consumable, PolicyFrame.Option encoded) {
            this(id, command, recipe, consumable, encoded, 0, id, null);
        }
        public Candidate(String id, PrimitiveCommand command, RecipeOption recipe, SurvivalObservation.Item consumable,
                         PolicyFrame.Option encoded, int commitmentTicks, String inputId) {
            this(id, command, recipe, consumable, encoded, commitmentTicks, inputId, null);
        }
        public PrimitiveType type() {
            return movement != null ? PrimitiveType.MOVE_INPUT : equipment != null ? PrimitiveType.EQUIP_ITEM : recipe != null ? PrimitiveType.CRAFT_ITEM
                    : consumable != null ? PrimitiveType.USE_ITEM : command.type();
        }
        /** A hold is an upper execution bound, not permission to hide the world from the policy for that long. */
        public int reconsiderAfterTicks() {
            return Math.max(1, Math.min(20, commitmentTicks));
        }
        public Candidate committed(int ticks) {
            double[] features = encoded.features(); features[30] = ticks / 600.0;
            String choice = inputId + "@" + ticks;
            // Changing a finite key hold must replace the old Action rather than keep its old deadline.
            return new Candidate(choice, command, recipe, consumable, new PolicyFrame.Option(choice, features), ticks,
                    movement == null ? inputId : choice, equipment, movement);
        }
        public Action create(AIPlayer ai) {
            if (movement != null) return new MovementInputAction(movement, commitmentTicks);
            if (equipment != null) return new EquipExactAction(equipment);
            if (recipe != null) return new VanillaCraftAction(Material.valueOf(recipe.result()), recipe.id());
            if (consumable != null) return new ConsumeExactAction(ai.getWorldId(), consumable);
            if (command instanceof PrimitiveCommand.Attack hit) return new StrikeExactAction(ai.getWorldId(), hit.entityId());
            return PrimitiveAdapter.create(ai, command);
        }
    }
    public record Capture(PolicyFrame frame, List<Candidate> candidates, SurvivalObservation.Body body, Map<String, Integer> stock) {
        public Capture { candidates = List.copyOf(candidates); stock = Map.copyOf(stock); }
    }
    private int recipeCursor;
    private long recipeRevision = Long.MIN_VALUE;
    private final CandidateDiscovery discovery = new CandidateDiscovery();
    private int recipeOptions;
    private int recipesChecked;
    private boolean recipeScanComplete;
    private int miningCursor;
    private int equipmentCursor;
    private int consumableCursor;
    private PolicyFrame.ConsumptionScan consumptionScan;
    private int placementCursor;
    private int movementCursor;
    private int movementInputCursor;
    private static final List<BlockPoint> MOVE_OFFSETS = movementOffsets();

    public record Observed(me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot snapshot,
                           SurvivalObservation.Body body, Map<String, Integer> stock, double[] features) {
        public Observed { stock = Map.copyOf(stock); features = features.clone(); }
        @Override public double[] features() { return features.clone(); }
    }

    public static Observed observe(AIPlayer ai, Candidate active, int activeTicks) {
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
            String activeKind = active.type().name();
            body[40 + bucket(activeKind, 7)] = 1;
            body[47] = Math.min(1, activeTicks / 200.0);
        }
        body[32 + bucket(p.getInventory().getItemInMainHand().getType().name(), 8)] = 1;
        for (var item : state.inventory()) body[48 + bucket(item.material(), 16)] += item.amount() / 64.0;
        LocalPolicyContext.capture(ai, body);
        EquipmentPolicyContext.capture(p, body);
        MotionPolicyContext.capture(p, survival, body);
        for (int i = 0; i < body.length; i++) body[i] = Math.clamp(body[i], -16, 16);
        Map<String, Integer> counts = new HashMap<>();
        for (var item : state.inventory()) if (item.amount() > 0) counts.merge(item.material(), item.amount(), Integer::sum);
        // Ownership rewards include worn/off-hand items; moving them back to storage is not a discovery.
        // Crafting availability still uses its separate storage-only snapshot below.
        for (int slot = 36; slot <= 40; slot++) {
            var item = p.getInventory().getItem(slot);
            if (item != null && !item.isEmpty()) counts.merge(item.getType().name(), item.getAmount(), Integer::sum);
        }
        TaskPolicyContext.encode(body, ai.getPolicyTaskMemory().observe(ai.getWorldId(), counts), ai.getTicks());
        return new Observed(state, survival, counts, body);
    }

    public Capture capture(AIPlayer ai, Candidate active, int activeTicks) {
        var observed = observe(ai, active, activeTicks);
        var state = observed.snapshot();
        var body = observed.features();
        Map<String, Candidate> found = new LinkedHashMap<>();
        // Waiting is always a legal input choice, without awarding it any hand-written priority.
        add(ai, found, "wait:5", new PrimitiveCommand.Wait(5), null, null, 0, 0, 0, "", false);
        if (active != null) found.putIfAbsent(active.id(), active);
        recipeOptions = recipesChecked = -1;
        recipeScanComplete = false;
        consumptionScan = null;
        Map<String, Integer> generated = new LinkedHashMap<>();
        var visit = discovery.visit(ai.getWorkBudget()::isExhausted, source -> {
            int before = found.size();
            switch (source) {
                case DIRECT_MOVE -> movementInputs(ai, found);
                case TARGET_MOVE -> movementTargets(ai, found);
                case LOOK -> look(ai, found);
                case JUMP -> add(ai, found, "jump", new PrimitiveCommand.Jump(), null, null, 0, 0, 0, "", true);
                case EQUIPMENT -> equipment(ai, found);
                case MINE -> mining(ai, state.inventory(), found);
                case CONSUME -> consumables(ai, state.inventory(), found);
                case PLACE -> placement(ai, state.inventory(), found);
                case ATTACK -> attacks(ai, found);
                case PICKUP -> add(ai, found, "pickup:4", new PrimitiveCommand.Pickup(4), null, null, 0, 0, 0, "", true);
                case CRAFT -> crafting(ai, found);
            }
            generated.put(source.name(), found.size() - before);
        });
        List<Candidate> expanded = new ArrayList<>();
        for (Candidate candidate : found.values()) {
            if (candidate.commitmentTicks() > 0) {
                double[] retained = candidate.encoded().features(); retained[55] = 1;
                expanded.add(new Candidate(candidate.id(), candidate.command(), candidate.recipe(), candidate.consumable(),
                        new PolicyFrame.Option(candidate.id(), retained), candidate.commitmentTicks(), candidate.inputId(), candidate.equipment(), candidate.movement()));
            } else if (candidate.equipment() != null) expanded.add(candidate.committed(5));
            else for (int ticks : new int[]{5, 20, 80, 600}) expanded.add(candidate.committed(ticks));
        }
        var candidates = selectFair(expanded, active == null ? "" : active.id());
        var evidence = new PolicyFrame.Discovery("candidate-discovery/v1", visit.sources().stream().map(Enum::name).toList(),
                generated, visit.budgetExhausted(), recipeOptions, recipesChecked, recipeScanComplete, consumptionScan);
        // Direct keys and budgeted registries are partial observations, even after every source was visited.
        return new Capture(new PolicyFrame(ai.getWorldId(), Integer.toUnsignedLong(Bukkit.getCurrentTick()), body,
                candidates.stream().map(Candidate::encoded).toList(), false, evidence,
                ai.getPolicyTaskMemory().observe(ai.getWorldId(), observed.stock())), candidates, observed.body(), observed.stock());
    }

    private void movementTargets(AIPlayer ai, Map<String, Candidate> found) {
        for (var delta : movementOffsets(movementCursor)) {
            if (ai.getWorkBudget().isExhausted()) break;
            BlockPoint target = ai.getPosition().offset(delta.x(), delta.y(), delta.z());
            add(ai, found, "move:" + target.encode(), new PrimitiveCommand.MoveTo(target, 0.5), null, null,
                    delta.x(), delta.y(), delta.z(), "", true);
        }
        movementCursor = Math.floorMod(movementCursor + 1, MOVE_OFFSETS.size());
    }

    private void look(AIPlayer ai, Map<String, Candidate> found) {
        var p = ai.getPlayer();
        for (int yaw : new int[]{-90, -45, 45, 90}) {
            double angle = Math.toRadians(p.getLocation().getYaw() + yaw);
            var eye = p.getEyeLocation();
            var target = new PrimitiveTarget.Point(eye.getX() - Math.sin(angle) * 4, eye.getY(), eye.getZ() + Math.cos(angle) * 4);
            add(ai, found, "look:" + yaw, new PrimitiveCommand.LookAt(target), null, null, -Math.sin(angle) * 4, 0, Math.cos(angle) * 4, "", true);
        }
    }

    private void mining(AIPlayer ai, List<me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot> inventory,
                        Map<String, Candidate> found) {
        var p = ai.getPlayer();
        List<BlockPoint> targets = new ArrayList<>();
        var feet = ai.getPosition();
        boolean rayLoaded = true;
        for (int dx : new int[]{-5, 5}) for (int dz : new int[]{-5, 5})
            rayLoaded &= p.getWorld().isChunkLoaded((feet.x() + dx) >> 4, (feet.z() + dz) >> 4);
        var ray = rayLoaded ? p.getTargetBlockExact(4) : null;
        if (ray != null) targets.add(Positions.of(ray));
        // Inspect only local visible cells. Unloaded/hidden cells never become material observations.
        targets.addAll(nearbyTargets(feet));
        var inspected = PrimitiveAdapter.inspect(ai, targets.stream().distinct().limit(24).toList());
        var tools = miningSlots(inventory);
        var blocks = inspected.blocks().stream().filter(block -> block.visible() && block.loaded()).toList();
        int mined = 0, attempted = 0, pairs = tools.size() * blocks.size();
        while (pairs > 0 && attempted < Math.min(pairs, 64) && mined < 32 && !ai.getWorkBudget().isExhausted()) {
            int pair = Math.floorMod(miningCursor + attempted++, pairs);
            var slot = tools.get(pair % tools.size());
            var block = blocks.get(pair / tools.size());
            ToolChoice tool = new ToolChoice(slot.slot(), slot.material(), slot.enchantments());
            var delta = block.point().offset(-ai.getPosition().x(), -ai.getPosition().y(), -ai.getPosition().z());
            String id = "mine:" + block.point().encode() + ":" + slot.slot();
            if (add(ai, found, id, new PrimitiveCommand.BreakBlock(block.point(), tool), null, null,
                    delta.x(), delta.y(), delta.z(), block.material() + ":" + slot.material(), true)) mined++;
        }
        miningCursor = pairs == 0 ? 0 : Math.floorMod(miningCursor + attempted, pairs);
    }

    private void consumables(AIPlayer ai, List<me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot> inventory,
                             Map<String, Candidate> found) {
        int inspected = 0;
        int snapshotSlots = (int) inventory.stream().filter(slot -> slot.slot() < 36).count();
        int scanned = 0, empty = 0, nonConsumable = 0, hunger = 0, blocked = 0, unknown = 0, unsupported = 0, available = 0;
        while (inspected < inventory.size() && !ai.getWorkBudget().isExhausted()) {
            var slot = inventory.get(Math.floorMod(consumableCursor++, inventory.size())); inspected++;
            if (slot.slot() >= 36) continue;
            scanned++;
            if (slot.amount() == 0) { empty++; continue; }
            var item = SurvivalCapture.item(ai, slot.slot());
            var use = item.consumption();
            if (item.amount() == 0) empty++;
            else if (ConsumeExactAction.supported(item)) {
                available++;
                add(ai, found, "consume:" + slot.slot(), null, null, item, 0, 0, 0, item.material(), false);
            } else switch (use.eligibility()) {
                case NOT_CONSUMABLE -> nonConsumable++;
                case BLOCKED -> { if (use.reason().equals("requires hunger")) hunger++; else blocked++; }
                case UNKNOWN -> unknown++;
                case UNSUPPORTED, AVAILABLE -> unsupported++;
            }
        }
        consumptionScan = new PolicyFrame.ConsumptionScan(snapshotSlots, scanned, empty, nonConsumable,
                hunger, blocked, unknown, unsupported, available);
    }

    private void placement(AIPlayer ai, List<me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot> inventory,
                           Map<String, Candidate> found) {
        int placed = 0;
        int inspected = 0;
        while (inspected < inventory.size() && placed < 12 && !ai.getWorkBudget().isExhausted()) {
            var slot = inventory.get(Math.floorMod(placementCursor++, inventory.size())); inspected++;
            if (slot.slot() >= 36 || slot.amount() == 0) continue;
            Material material = Material.matchMaterial(slot.material());
            if (material != null && material.isBlock() && placed < 12) {
                for (int[] direction : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                    BlockPoint target = ai.getPosition().offset(direction[0], 0, direction[1]);
                    if (add(ai, found, "place:" + target.encode() + ":" + material, new PrimitiveCommand.PlaceBlock(target, material.name()),
                            null, null, direction[0], 0, direction[1], material.name(), true)) placed++;
                }
            }
        }
    }

    private void attacks(AIPlayer ai, Map<String, Candidate> found) {
        var p = ai.getPlayer();
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
    }

    private void crafting(AIPlayer ai, Map<String, Candidate> found) {
        if (recipeRevision != ai.getCrafting().revision()) { recipeCursor = 0; recipeRevision = ai.getCrafting().revision(); }
        int crafted = 0;
        var stock = ai.getInventory().snapshot();
        var recipes = ai.getCrafting().recipeOptionsUsing(stock);
        recipeOptions = recipes.size(); recipesChecked = 0;
        Boolean table = null;
        while (recipesChecked < Math.min(64, recipes.size()) && crafted < 16 && !ai.getWorkBudget().isExhausted()) {
            var recipe = recipes.get(Math.floorMod(recipeCursor++, recipes.size())); recipesChecked++;
            Material result = Material.matchMaterial(recipe.result());
            if (recipe.needsTable() && table == null) table = Progression.nearbyTable(ai) != null;
            if (result == null || recipe.needsTable() && !table || ai.getCrafting().singleStep(result, recipe.id(), stock) == null) continue;
            if (add(ai, found, "craft:" + recipe.id(), null, recipe, null, 0, 0, 0, recipe.id(), false)) crafted++;
        }
        recipeScanComplete = recipesChecked == recipes.size();
    }

    private boolean movementInputs(AIPlayer ai, Map<String, Candidate> found) {
        if (ai.getWorkBudget().isExhausted()) return false;
        for (var input : MovementInput.choices(ai.getWorldId(), ai.getPlayer().getLocation().getYaw(), movementInputCursor, 24)) {
            if (ai.getWorkBudget().isExhausted()) break;
            double[] features = new double[PolicyFrame.ACTION_SIZE];
            features[PrimitiveType.MOVE_INPUT.ordinal()] = 1;
            var direction = input.heading();
            features[24] = direction.x(); features[25] = direction.y(); features[26] = direction.z();
            features[27] = input.forward(); features[28] = input.jump() ? 1 : 0;
            features[29] = input.sneak() ? 1 : 0; features[56] = input.strafe();
            features[57] = input.sprint() ? 1 : 0; features[63] = 1;
            String id = input.identity();
            found.putIfAbsent(id, new Candidate(id, null, null, null, new PolicyFrame.Option(id, features), 0, id, null, input));
        }
        movementInputCursor = (movementInputCursor + 1) % MovementInput.CHOICES;
        return false; // A rotating subset never claims that unoffered key inputs do not exist.
    }

    private void equipment(AIPlayer ai, Map<String, Candidate> found) {
        var player = ai.getPlayer();
        int held = player.getInventory().getHeldItemSlot();
        Map<Integer, ExactEquipment.Stack> targets = new LinkedHashMap<>();
        for (int slot : new int[]{held, 40, 39, 38, 37, 36}) {
            if (ai.getWorkBudget().isExhausted()) return;
            targets.put(slot, ExactEquipment.read(player, slot));
        }
        int inspected = 0;
        while (inspected < 8 && !ai.getWorkBudget().isExhausted()) {
            int source = Math.floorMod(equipmentCursor++, 41); inspected++;
            var from = targets.containsKey(source) ? targets.get(source) : ExactEquipment.read(player, source);
            if (source >= 36 && from.amount() == 0) continue;
            for (var target : targets.entrySet()) {
                if (ai.getWorkBudget().isExhausted() || found.size() >= 512) return;
                if (source == target.getKey()) continue;
                var choice = new ExactEquipment.Choice(ai.getWorldId(), source, target.getKey(), held, from, target.getValue());
                if (!ExactEquipment.offered(player, choice)) continue;
                String id = "equip-exact:" + source + ":" + target.getKey();
                double[] features = new double[PolicyFrame.ACTION_SIZE];
                features[PrimitiveType.EQUIP_ITEM.ordinal()] = 1;
                features[27] = Math.min(16, from.amount() / 64.0); features[28] = Math.min(16, target.getValue().amount() / 64.0);
                features[29] = source / 40.0; features[56] = from.damageFraction();
                features[58] = target.getValue().amount() == 0 ? 1 : 0; features[60] = from.amount() == 0 ? 1 : 0;
                features[59] = from.enchantments().getOrDefault("minecraft:binding_curse", 0) > 0 ? 1 : 0;
                features[61] = choice.selectHotbar() ? 1 : 0; features[62] = target.getKey() / 40.0; features[63] = 1;
                if (from.amount() > 0) features[32 + bucket(from.material(), 16)] = 1;
                if (target.getValue().amount() > 0) features[64 + bucket(target.getValue().material(), 16)] = 1;
                from.enchantments().forEach((key, level) -> {
                    int index = 80 + bucket(key, 16);
                    features[index] = Math.clamp(features[index] + level / 8.0, 0, 16);
                });
                found.putIfAbsent(id, new Candidate(id, null, null, null, new PolicyFrame.Option(id, features), 0, id, choice));
            }
        }
    }

    private static List<BlockPoint> movementOffsets() {
        List<BlockPoint> offsets = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            for (int stride : new int[]{1, 2}) for (int dy : new int[]{0, 1, -1})
                offsets.add(new BlockPoint(dx * stride, dy, dz * stride));
        }
        return List.copyOf(offsets);
    }

    /** Rotate presentation, not action preference; even a truncated candidate budget sees every direction over time. */
    static List<BlockPoint> movementOffsets(int cursor) {
        List<BlockPoint> result = new ArrayList<>(MOVE_OFFSETS.size());
        for (int i = 0; i < MOVE_OFFSETS.size(); i++) result.add(MOVE_OFFSETS.get(Math.floorMod(cursor + i, MOVE_OFFSETS.size())));
        return List.copyOf(result);
    }

    static List<BlockPoint> nearbyTargets(BlockPoint feet) {
        var points = new ArrayList<BlockPoint>();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
            for (int dy = 0; dy <= 2; dy++) points.add(feet.offset(dx, dy, dz));
        // Geometric proximity, never material desirability; the old prefix inspected almost only negative X.
        points.sort(Comparator.comparingInt(point -> {
            int dx = point.x() - feet.x(), dy = point.y() - feet.y() - 1, dz = point.z() - feet.z();
            return dx * dx + dy * dy + dz * dz;
        }));
        return List.copyOf(points);
    }

    static List<me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot> miningSlots(
            List<me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot> inventory) {
        var slots = new ArrayList<me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot>();
        boolean empty = false;
        for (var slot : inventory) {
            if (slot.slot() >= 36) continue;
            if (slot.amount() == 0) { if (empty) continue; empty = true; }
            // Occupied slots retain their exact component identity. No best-tool preference or material merging.
            slots.add(slot);
        }
        return List.copyOf(slots);
    }

    private boolean add(AIPlayer ai, Map<String, Candidate> found, String id, PrimitiveCommand command, RecipeOption recipe,
                        SurvivalObservation.Item consumable, double dx, double dy, double dz, String identity, boolean check) {
        if (found.size() >= 512 || found.containsKey(id)) return false;
        if (check && (ai.getWorkBudget().isExhausted() || !PrimitiveAdapter.check(ai, command).allowed())) return false;
        double[] features = new double[PolicyFrame.ACTION_SIZE];
        PrimitiveType type = recipe != null ? PrimitiveType.CRAFT_ITEM : consumable != null ? PrimitiveType.USE_ITEM : command.type();
        features[type.ordinal()] = 1;
        features[24] = Math.clamp(dx / 16, -1, 1); features[25] = Math.clamp(dy / 16, -1, 1); features[26] = Math.clamp(dz / 16, -1, 1);
        features[32 + bucket(identity, 16)] = 1;
        String semantic = recipe != null ? recipe.result() : consumable != null ? consumable.consumption().remainderMaterial()
                : command instanceof PrimitiveCommand.BreakBlock mine ? mine.tool().material()
                : command instanceof PrimitiveCommand.Equip equip ? equip.tool().material() : "";
        if (!semantic.isBlank()) features[48 + bucket(semantic, 7)] = 1;
        if (consumable != null) {
            var use = consumable.consumption();
            features[27] = Math.min(16, consumable.amount() / 64.0); features[28] = use.useTicks() / 200.0;
            features[29] = consumable.slot() / 35.0; features[31] = use.nutrition() / 20.0;
            features[57] = Math.clamp(use.saturation() / 20.0, 0, 16); features[58] = use.clearsEffects() ? 1 : 0;
            features[59] = use.randomTeleport() ? 1 : 0; features[60] = use.canAlwaysEat() ? 1 : 0;
            features[61] = use.removedEffects().isEmpty() ? 0 : 1; features[63] = 1;
            EffectEncoding.encode(features, use.effects());
        }
        if (command instanceof PrimitiveCommand.BreakBlock mine) {
            var tool = ai.getPlayer().getInventory().getItem(mine.tool().slot());
            if (tool != null && !tool.isEmpty()) {
                Integer damage = tool.getData(io.papermc.paper.datacomponent.DataComponentTypes.DAMAGE);
                Integer maximum = tool.getData(io.papermc.paper.datacomponent.DataComponentTypes.MAX_DAMAGE);
                features[56] = maximum == null || maximum <= 0 ? 0 : Math.clamp((damage == null ? 0 : damage) / (double) maximum, 0, 1);
            }
            var actualTool = tool == null ? org.bukkit.inventory.ItemStack.empty() : tool;
            features[57] = Math.clamp(Positions.block(ai.getPlayer().getWorld(), mine.target()).getBlockData().getDestroySpeed(actualTool, true) / 32.0, 0, 16);
        }
        if (recipe != null) features[27] = recipe.resultAmount() / 64.0;
        found.put(id, new Candidate(id, command, recipe, consumable, new PolicyFrame.Option(id, features)));
        return true;
    }

    static List<Candidate> selectFair(List<Candidate> all, String activeId) {
        List<Candidate> selected = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Candidate candidate : all) if (candidate.id().equals(activeId) && ids.add(candidate.id())) selected.add(candidate);
        Map<PrimitiveType, ArrayDeque<Candidate>> groups = new EnumMap<>(PrimitiveType.class);
        for (Candidate candidate : all) {
            PrimitiveType type = candidate.type();
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
