package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.*;
import me.herry.minecraftAI.ai.crafting.RecipeOption;
import me.herry.minecraftAI.ai.observation.SurvivalCapture;
import me.herry.minecraftAI.ai.observation.SurvivalObservation;
import me.herry.minecraftAI.ai.perception.EnderEyeTrack;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.perception.Visibility;
import me.herry.minecraftAI.ai.perf.WorkBudget;
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
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.BooleanSupplier;

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
        /** Reconsider within20 ticks; finite movement uses its hold, other executors keep their own timeout. */
        public int reconsiderAfterTicks() {
            if (command instanceof PrimitiveCommand.ShootBow) return ShootBowAction.ACTION_TICKS;
            return Math.max(1, Math.min(20, commitmentTicks));
        }
        public Candidate committed(int ticks) {
            double[] features = encoded.features(); features[30] = ticks / 600.0;
            String choice = inputId + "@" + ticks;
            // Changing a finite key hold must replace the old Action rather than keep its old deadline.
            return new Candidate(choice, command, recipe, consumable, new PolicyFrame.Option(choice, features, encoded.blockEvidence()), ticks,
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
    private int furnaceStorageCursor;
    private int portalObservationCursor;
    private long recipeRevision = Long.MIN_VALUE;
    private final CandidateDiscovery discovery = new CandidateDiscovery();
    private final CandidateActionContext candidateContext = new CandidateActionContext();
    private int recipeOptions;
    private int recipesChecked;
    private boolean recipeScanComplete;
    private int miningCursor;
    private int miningObservationCursor;
    private PolicyFrame.MiningScan miningScan;
    private int equipmentCursor;
    private int consumableCursor;
    private PolicyFrame.ConsumptionScan consumptionScan;
    private int placementCursor;
    private int movementCursor;
    private int movementInputCursor;
    private int pickupCursor;
    private static final List<BlockPoint> MOVE_OFFSETS = movementOffsets();

    /** Candidate generation and workstation features share the same local reach check. */
    private static final class TableAccess implements BooleanSupplier {
        private final Player player;
        private final WorkBudget budget;
        private Boolean available;

        TableAccess(AIPlayer ai) {
            player = ai.getPlayer();
            budget = ai.getWorkBudget();
        }

        @Override public boolean getAsBoolean() {
            if (available == null) available = NearbyBlocks.find(player, Material.CRAFTING_TABLE, 4) != null;
            return available;
        }

        boolean knownMissing() {
            return available != null ? !available : !budget.isExhausted() && !getAsBoolean();
        }
    }

    public record Observed(me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot snapshot,
                           SurvivalObservation.Body body, Map<String, Integer> stock, double[] features,
                           PolicyFrame.SightObservation sight, EnderEyeTrack.Observation eye) {
        public Observed(me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot snapshot,
                        SurvivalObservation.Body body, Map<String, Integer> stock, double[] features) {
            this(snapshot, body, stock, features, null, null);
        }
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
        TaskPolicyContext.encode(body, ai.getPolicyTaskMemory().observe(ai.getWorldId(), counts), ai.getTicks(), ai.getWorldId());
        if (ai.getConfig().maskObjectiveInput) TaskPolicyContext.maskObjective(body);
        var sight = DirectionalPolicyContext.capture(ai, body);
        ExecutionPolicyContext.encode(body, ai.getLearnedExecution(),
                ai.getPolicyTaskMemory().observe(ai.getWorldId(), counts), ai.getWorldId());
        var eye = ai.getPerceptionSystem().observeThrownEye();
        if (eye != null) {
            var position = p.getEyeLocation();
            body[406] = 1;
            body[407] = eye.visible() ? 1 : 0;
            body[408] = eye.age() / (double) EnderEyeTrack.MEMORY_TICKS;
            body[409] = Math.clamp((eye.x() - position.getX()) / 32, -1, 1);
            body[410] = Math.clamp((eye.y() - position.getY()) / 32, -1, 1);
            body[411] = Math.clamp((eye.z() - position.getZ()) / 32, -1, 1);
            body[412] = Math.clamp(eye.dx() / 16, -1, 1);
            body[413] = Math.clamp(eye.dy() / 16, -1, 1);
            body[414] = Math.clamp(eye.dz() / 16, -1, 1);
        }
        return new Observed(state, survival, counts, body, sight, eye);
    }

    public Capture capture(AIPlayer ai, Candidate active, int activeTicks) {
        var observed = observe(ai, active, activeTicks);
        var state = observed.snapshot();
        var body = observed.features();
        var taskView = ai.getPolicyTaskMemory().observe(ai.getWorldId(), observed.stock());
        Map<String, Integer> storageStock = new HashMap<>();
        List<CandidateActionContext.SlotAmount> storageSlots = new ArrayList<>();
        for (var slot : state.inventory()) {
            if (slot.slot() >= 36 || slot.amount() <= 0) continue;
            storageStock.merge(slot.material(), slot.amount(), Integer::sum);
            storageSlots.add(new CandidateActionContext.SlotAmount(slot.slot(), slot.material(), slot.amount()));
        }
        var actionContext = candidateContext.capture(ai.getCrafting(), observed.stock(), storageStock, storageSlots,
                taskView, !ai.getConfig().maskObjectiveInput);
        var tableAccess = new TableAccess(ai);
        Map<String, Candidate> found = new LinkedHashMap<>();
        // Waiting is always a legal input choice, without awarding it any hand-written priority.
        add(ai, found, "wait:5", new PrimitiveCommand.Wait(5), null, null, 0, 0, 0, "", false, actionContext);
        if (active != null) found.putIfAbsent(active.id(), active);
        recipeOptions = recipesChecked = -1;
        recipeScanComplete = false;
        consumptionScan = null;
        miningScan = null;
        Map<String, Integer> generated = new LinkedHashMap<>();
        var visit = discovery.visit(ai.getWorkBudget()::isExhausted, source -> {
            int before = found.size();
            switch (source) {
                case DIRECT_MOVE -> movementInputs(ai, found);
                case TARGET_MOVE -> movementTargets(ai, found, actionContext);
                case LOOK -> look(ai, found, actionContext);
                case JUMP -> add(ai, found, "jump", new PrimitiveCommand.Jump(), null, null, 0, 0, 0, "", true, actionContext);
                case EQUIPMENT -> equipment(ai, found, actionContext);
                case MINE -> mining(ai, state.inventory(), observed.sight(), found, actionContext);
                case CONSUME -> consumables(ai, state.inventory(), found, actionContext);
                case PLACE -> placement(ai, state.inventory(), found, actionContext);
                case ATTACK -> attacks(ai, found, actionContext);
                case PICKUP -> pickups(ai, found, actionContext);
                case CRAFT -> crafting(ai, found, actionContext, tableAccess);
                case FURNACE -> furnaceTransfers(ai, observed.sight(), found, actionContext);
                case IGNITE -> portalIgnitions(ai, observed.sight(), found, actionContext);
                case PORTAL -> portalEntries(ai, found, actionContext);
                case ENDER_EYE -> enderEyes(ai, observed.sight(), observed.eye(), found, actionContext);
                case VISIBLE_APPROACH -> visibleApproaches(ai, observed.sight(), found, actionContext);
            }
            generated.put(source.name(), found.size() - before);
        });
        List<Candidate> expanded = new ArrayList<>();
        for (Candidate candidate : found.values()) {
            if (candidate.commitmentTicks() > 0) {
                double[] retained = candidate.encoded().features(); retained[55] = 1;
                expanded.add(new Candidate(candidate.id(), candidate.command(), candidate.recipe(), candidate.consumable(),
                        new PolicyFrame.Option(candidate.id(), retained, candidate.encoded().blockEvidence()), candidate.commitmentTicks(), candidate.inputId(), candidate.equipment(), candidate.movement()));
                expanded.addAll(compatibleReconsiderations(candidate));
            } else if (candidate.command() instanceof PrimitiveCommand.ShootBow) expanded.add(candidate.committed(ShootBowAction.ACTION_TICKS));
            else if (candidate.equipment() != null) expanded.add(candidate.committed(5));
            else for (int ticks : new int[]{5, 20, 80, 600}) expanded.add(candidate.committed(ticks));
        }
        var candidates = selectFair(expanded, active == null ? "" : active.id());
        var feet = ai.getPosition();
        var evidence = new PolicyFrame.Discovery("candidate-discovery/v1", visit.sources().stream().map(Enum::name).toList(),
                generated, visit.budgetExhausted(), recipeOptions, recipesChecked, recipeScanComplete, consumptionScan, miningScan);
        // Direct keys and budgeted registries are partial observations, even after every source was visited.
        return new Capture(new PolicyFrame(ai.getWorldId(), Integer.toUnsignedLong(Bukkit.getCurrentTick()), body,
                candidates.stream().map(candidate -> option(candidate, feet, observed.eye(), actionContext, tableAccess)).toList(), false, evidence,
                taskView, observed.sight()), candidates, observed.body(), observed.stock());
    }

    private void movementTargets(AIPlayer ai, Map<String, Candidate> found, CandidateActionContext.Context actionContext) {
        for (var delta : movementOffsets(movementCursor)) {
            if (ai.getWorkBudget().isExhausted()) break;
            BlockPoint target = ai.getPosition().offset(delta.x(), delta.y(), delta.z());
            add(ai, found, "move:" + target.encode(), new PrimitiveCommand.MoveTo(target, 0.5), null, null,
                    delta.x(), delta.y(), delta.z(), "", true, actionContext);
        }
        movementCursor = Math.floorMod(movementCursor + 1, MOVE_OFFSETS.size());
    }

    private void visibleApproaches(AIPlayer ai, PolicyFrame.SightObservation sight, Map<String, Candidate> found,
                                   CandidateActionContext.Context actionContext) {
        if (sight == null) return;
        var origin = ai.getPosition();
        var opacity = PerceptionSystem.opacityOf(ai.getPlayer().getWorld());
        var visited = new HashSet<BlockPoint>();
        for (var ray : sight.rays()) {
            if (ai.getWorkBudget().isExhausted()) return;
            var observed = ray.block();
            if (observed == null || ray.distance() <= PrimitiveAdapter.BLOCK_REACH) continue;
            var material = Material.getMaterial(observed.material());
            if (material == null || !material.isSolid()) continue;
            var block = new BlockPoint(observed.x(), observed.y(), observed.z());
            if (!visited.add(block)) continue;
            double dx = block.x() - origin.x();
            double dz = block.z() - origin.z();
            double distance = Math.hypot(dx, dz);
            if (distance <= PrimitiveAdapter.BLOCK_REACH) continue;
            // Stop short of the observed face. The move executor still plans and walks the actual route.
            double fraction = (distance - 2.5) / distance;
            int standX = origin.x() + (int) Math.round(dx * fraction);
            int standZ = origin.z() + (int) Math.round(dz * fraction);
            int sideX = (int) Math.round(-dz / distance);
            int sideZ = (int) Math.round(dx / distance);
            boolean offered = false;
            for (int side : new int[]{0, -1, 1}) {
                for (int dy : new int[]{0, 1, -1}) {
                    if (ai.getWorkBudget().isExhausted()) return;
                    var stand = new BlockPoint(standX + side * sideX, origin.y() + dy, standZ + side * sideZ);
                    if (!visibleStandingPoint(sight, opacity, stand)) continue;
                    if (add(ai, found, "approach:" + block.encode() + ":" + stand.encode(), new PrimitiveCommand.MoveTo(stand, 0.5),
                            null, null, stand.x() - origin.x(), dy, stand.z() - origin.z(), observed.material(), true, actionContext)) {
                        offered = true;
                        break;
                    }
                }
                if (offered) break;
            }
        }
    }

    private static boolean visibleStandingPoint(PolicyFrame.SightObservation sight, Visibility.Opacity opacity, BlockPoint stand) {
        return Visibility.canSeePoint(opacity, sight.eyeX(), sight.eyeY(), sight.eyeZ(),
                stand.x() + 0.5, stand.y() + 0.25, stand.z() + 0.5);
    }

    private void look(AIPlayer ai, Map<String, Candidate> found, CandidateActionContext.Context actionContext) {
        var p = ai.getPlayer();
        for (int yaw : new int[]{-90, -45, 45, 90}) {
            double angle = Math.toRadians(p.getLocation().getYaw() + yaw);
            var eye = p.getEyeLocation();
            var target = new PrimitiveTarget.Point(eye.getX() - Math.sin(angle) * 4, eye.getY(), eye.getZ() + Math.cos(angle) * 4);
            add(ai, found, "look:" + yaw, new PrimitiveCommand.LookAt(target), null, null, -Math.sin(angle) * 4, 0, Math.cos(angle) * 4, "", true, actionContext);
        }
    }

    private void pickups(AIPlayer ai, Map<String, Candidate> found, CandidateActionContext.Context actionContext) {
        var drops = ai.getPerception().getDrops();
        var origin = ai.getPlayer().getLocation();
        int offered = 0;
        for (int scanned = 0; scanned < Math.min(16, drops.size()) && offered < 6 && !ai.getWorkBudget().isExhausted(); scanned++) {
            var drop = drops.get(Math.floorMod(pickupCursor++, drops.size()));
            var at = drop.getLocation();
            var material = drop.getItemStack().getType().name();
            var command = new PrimitiveCommand.PickupExact(ai.getWorldId(), drop.getUniqueId(), material);
            if (add(ai, found, "pickup-exact:" + drop.getUniqueId(), command, null, null, at.getX() - origin.getX(),
                    at.getY() - origin.getY(), at.getZ() - origin.getZ(), material, true, actionContext)) offered++;
        }
        add(ai, found, "pickup:4", new PrimitiveCommand.Pickup(4), null, null, 0, 0, 0, "", true, actionContext);
    }

    private void mining(AIPlayer ai, List<me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot> inventory,
                        PolicyFrame.SightObservation sight, Map<String, Candidate> found, CandidateActionContext.Context actionContext) {
        var p = ai.getPlayer();
        var feet = ai.getPosition();
        boolean rayLoaded = true;
        for (int dx : new int[]{-5, 5}) for (int dz : new int[]{-5, 5})
            rayLoaded &= p.getWorld().isChunkLoaded((feet.x() + dx) >> 4, (feet.z() + dz) >> 4);
        var ray = rayLoaded ? p.getTargetBlockExact(4) : null;
        // Keep the actual crosshair, then rotate geometric observation coverage. A static nearest
        // prefix otherwise never inspects many reachable cells while standing at the same location.
        var targets = miningObservationTargets(feet, ray == null ? null : Positions.of(ray), miningObservationCursor,
                reachableSightTargets(sight));
        int previousCursor = miningObservationCursor;
        // Inspect only local visible cells. Unloaded/hidden cells never become material observations.
        var inspected = PrimitiveAdapter.inspect(ai, targets);
        miningObservationCursor = nextMiningObservationCursor(feet, previousCursor,
                inspected.blocks().stream().map(block -> block.point()).toList());
        miningScan = new PolicyFrame.MiningScan("mining-window-scan/v1", previousCursor, miningObservationCursor,
                targets.size(), inspected.blocks().size(), (int) inspected.blocks().stream().filter(block -> block.visible() && block.loaded()).count(),
                inspected.blocks().size() == targets.size());
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
                    delta.x(), delta.y(), delta.z(), block.material() + ":" + slot.material(), true, actionContext)) mined++;
        }
        miningCursor = pairs == 0 ? 0 : Math.floorMod(miningCursor + attempted, pairs);
    }

    private void consumables(AIPlayer ai, List<me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot> inventory,
                             Map<String, Candidate> found, CandidateActionContext.Context actionContext) {
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
                add(ai, found, "consume:" + slot.slot(), null, null, item, 0, 0, 0, item.material(), false, actionContext);
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
                           Map<String, Candidate> found, CandidateActionContext.Context actionContext) {
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
                            null, null, direction[0], 0, direction[1], material.name(), true, actionContext)) placed++;
                }
            }
        }
    }

    private void attacks(AIPlayer ai, Map<String, Candidate> found, CandidateActionContext.Context actionContext) {
        var p = ai.getPlayer();
        var origin = p.getLocation();
        if (ai.getBody().canShootBow()) {
            for (var crystal : ai.getPerception().getEndCrystals()) {
                if (ai.getWorkBudget().isExhausted()) break;
                addBow(ai, found, actionContext, origin, crystal, crystal, crystal.getType().name());
            }
        }
        var targets = new LinkedHashMap<UUID, org.bukkit.entity.Entity>();
        for (var entity : ai.getPerception().getEndCrystals()) targets.putIfAbsent(entity.getUniqueId(), entity);
        for (var entity : ai.getPerception().getHostiles()) targets.putIfAbsent(entity.getUniqueId(), entity);
        for (var entity : ai.getPerception().getAnimals()) targets.putIfAbsent(entity.getUniqueId(), entity);
        for (var target : targets.values()) {
            if (ai.getWorkBudget().isExhausted()) break;
            Entity contact = target;
            String identity = target.getType().name();
            if (target instanceof EnderDragon dragon) {
                contact = ai.getBody().dragonHead(dragon);
                identity = "ENDER_DRAGON_HEAD:" + dragon.getPhase().name();
                if (ai.getBody().canShootBow()) addBow(ai, found, actionContext, origin, dragon, contact, identity);
            }
            if (!StrikeExactAction.canReach(ai, target)) continue;
            var at = contact.getLocation();
            add(ai, found, "attack:" + target.getUniqueId(), new PrimitiveCommand.Attack(target.getUniqueId()), null, null,
                    at.getX() - origin.getX(), at.getY() - origin.getY(), at.getZ() - origin.getZ(), identity, true, actionContext);
        }
    }

    private void addBow(AIPlayer ai, Map<String, Candidate> found, CandidateActionContext.Context actionContext,
                        org.bukkit.Location origin, Entity target, Entity contact, String identity) {
        var at = contact.getLocation();
        var shot = new PrimitiveCommand.ShootBow(ai.getWorldId(), target.getUniqueId());
        add(ai, found, "shoot-bow:" + target.getUniqueId(), shot, null, null, at.getX() - origin.getX(),
                at.getY() - origin.getY(), at.getZ() - origin.getZ(), identity, true, actionContext);
    }

    private void crafting(AIPlayer ai, Map<String, Candidate> found, CandidateActionContext.Context actionContext,
                          TableAccess tableAccess) {
        if (recipeRevision != ai.getCrafting().revision()) { recipeCursor = 0; recipeRevision = ai.getCrafting().revision(); }
        int crafted = 0;
        var stock = ai.getInventory().snapshot();
        var recipes = ai.getCrafting().feasibleRecipeOptions(stock, tableAccess);
        recipeOptions = recipes.size(); recipesChecked = 0;
        while (recipesChecked < Math.min(64, recipes.size()) && crafted < 16 && !ai.getWorkBudget().isExhausted()) {
            var recipe = recipes.get(Math.floorMod(recipeCursor++, recipes.size())); recipesChecked++;
            Material result = Material.matchMaterial(recipe.result());
            if (result == null || ai.getCrafting().singleStep(result, recipe.id(), stock) == null) continue;
            if (add(ai, found, "craft:" + recipe.id(), null, recipe, null, 0, 0, 0, recipe.id(), false, actionContext)) crafted++;
        }
        recipeScanComplete = recipesChecked == recipes.size();
    }

    private void furnaceTransfers(AIPlayer ai, PolicyFrame.SightObservation sight, Map<String, Candidate> found,
                                  CandidateActionContext.Context actionContext) {
        if (sight == null) return;
        var targets = new LinkedHashSet<BlockPoint>();
        for (var ray : sight.rays()) {
            var block = ray.block();
            if (block != null && "FURNACE".equals(block.material()))
                targets.add(new BlockPoint(block.x(), block.y(), block.z()));
        }
        if (targets.isEmpty()) return;
        int start = furnaceStorageCursor;
        furnaceStorageCursor = (furnaceStorageCursor + 8) % 36;
        var player = ai.getPlayer();
        var world = player.getWorld();
        var eye = player.getEyeLocation();
        for (var target : targets) {
            if (ai.getWorkBudget().isExhausted()) return;
            if (eye.distanceSquared(Positions.center(world, target)) > PrimitiveAdapter.BLOCK_REACH * PrimitiveAdapter.BLOCK_REACH) continue;
            var furnace = Furnaces.inventory(world, target);
            if (furnace == null) continue;
            var output = furnace.getResult();
            if (output != null && !output.isEmpty())
                addFurnaceTransfer(ai, found, actionContext, new PrimitiveCommand.FurnaceTransfer(ai.getWorldId(), target,
                        PrimitiveCommand.FurnaceSlot.RESULT, -1, output.getType().name()));
            for (int offset = 0; offset < 8 && !ai.getWorkBudget().isExhausted(); offset++) {
                int slot = (start + offset) % 36;
                var item = player.getInventory().getItem(slot);
                if (item == null || item.isEmpty()) continue;
                for (var destination : List.of(PrimitiveCommand.FurnaceSlot.INGREDIENT, PrimitiveCommand.FurnaceSlot.FUEL))
                    addFurnaceTransfer(ai, found, actionContext, new PrimitiveCommand.FurnaceTransfer(ai.getWorldId(),
                            target, destination, slot, item.getType().name()));
            }
        }
    }

    private void addFurnaceTransfer(AIPlayer ai, Map<String, Candidate> found, CandidateActionContext.Context actionContext,
                                    PrimitiveCommand.FurnaceTransfer transfer) {
        var offset = transfer.target().offset(-ai.getPosition().x(), -ai.getPosition().y(), -ai.getPosition().z());
        String type = "furnace-" + transfer.slot().name().toLowerCase(Locale.ROOT);
        add(ai, found, type + ":" + transfer.target().encode() + ":" + transfer.inventorySlot() + ":" + transfer.material(),
                transfer, null, null, offset.x(), offset.y(), offset.z(), type, true, actionContext);
    }

    private void portalIgnitions(AIPlayer ai, PolicyFrame.SightObservation sight, Map<String, Candidate> found,
                                 CandidateActionContext.Context actionContext) {
        if (sight == null || ai.getPlayer().getInventory().getItemInMainHand().getType() != Material.FLINT_AND_STEEL) return;
        var feet = ai.getPosition();
        for (var ray : sight.rays()) {
            if (ai.getWorkBudget().isExhausted()) return;
            var observed = ray.block();
            if (observed == null || !observed.material().equals("OBSIDIAN")) continue;
            var floor = new BlockPoint(observed.x(), observed.y(), observed.z());
            add(ai, found, "ignite-portal:" + floor.encode(), new PrimitiveCommand.IgnitePortal(ai.getWorldId(), floor),
                    null, null, floor.x() - feet.x(), floor.y() - feet.y(), floor.z() - feet.z(), "ignite-portal", true, actionContext);
        }
    }

    private void portalEntries(AIPlayer ai, Map<String, Candidate> found, CandidateActionContext.Context actionContext) {
        var feet = ai.getPosition();
        var world = ai.getPlayer().getWorld();
        if (world.getEnvironment() != org.bukkit.World.Environment.NORMAL && world.getEnvironment() != org.bukkit.World.Environment.NETHER) return;
        // Portal blocks are passable. A ray that sees through one is not evidence it is absent.
        var targets = miningObservationTargets(feet.offset(0, -1, 0), feet, portalObservationCursor);
        int inspected = 0;
        for (var target : targets) {
            if (ai.getWorkBudget().isExhausted()) break;
            inspected++;
            if (target.y() < world.getMinHeight() || target.y() >= world.getMaxHeight() || !Positions.isLoaded(world, target)) continue;
            var material = Positions.block(world, target).getType();
            PrimitiveCommand command;
            String kind;
            if (material == Material.NETHER_PORTAL) {
                var below = target.offset(0, -1, 0);
                if (!target.equals(feet) && below.y() >= world.getMinHeight()
                        && Positions.block(world, below).getType() == Material.NETHER_PORTAL) continue;
                command = new PrimitiveCommand.EnterNetherPortal(ai.getWorldId(), target);
                kind = "enter-nether-portal";
            } else if (material == Material.END_PORTAL && world.getEnvironment() == org.bukkit.World.Environment.NORMAL) {
                command = new PrimitiveCommand.EnterEndPortal(ai.getWorldId(), target);
                kind = "enter-end-portal";
            } else continue;
            add(ai, found, kind + ":" + target.encode(), command, null, null,
                    target.x() - feet.x(), target.y() - feet.y(), target.z() - feet.z(), kind, true, actionContext);
        }
        // The standing cell is prepended so a return portal remains discoverable immediately.
        portalObservationCursor = nextMiningObservationCursor(feet.offset(0, -1, 0), portalObservationCursor, targets.subList(0, inspected));
    }

    private void enderEyes(AIPlayer ai, PolicyFrame.SightObservation sight, EnderEyeTrack.Observation eye, Map<String, Candidate> found,
                           CandidateActionContext.Context actionContext) {
        var player = ai.getPlayer();
        if (eye != null) {
            var from = player.getEyeLocation();
            var target = new PrimitiveTarget.Point(eye.x(), eye.y(), eye.z());
            add(ai, found, "look:thrown-eye", new PrimitiveCommand.LookAt(target), null, null,
                    eye.x() - from.getX(), eye.y() - from.getY(), eye.z() - from.getZ(), "thrown-eye", true, actionContext);
            eyeBearingMoves(ai, found, eye, actionContext);
        }
        if (player.getWorld().getEnvironment() != org.bukkit.World.Environment.NORMAL
                || player.getInventory().getItemInMainHand().getType() != Material.ENDER_EYE) return;
        add(ai, found, "throw-ender-eye", new PrimitiveCommand.ThrowEnderEye(ai.getWorldId()), null, null,
                0, 0, 0, "throw-ender-eye", true, actionContext);
        if (sight == null) return;
        var feet = ai.getPosition();
        for (var ray : sight.rays()) {
            if (ai.getWorkBudget().isExhausted()) break;
            var block = ray.block();
            if (block == null || !block.material().equals("END_PORTAL_FRAME")) continue;
            var frame = new BlockPoint(block.x(), block.y(), block.z());
            add(ai, found, "socket-ender-eye:" + frame.encode(), new PrimitiveCommand.SocketEnderEye(ai.getWorldId(), frame), null, null,
                    frame.x() - feet.x(), frame.y() - feet.y(), frame.z() - feet.z(), "socket-ender-eye", true, actionContext);
        }
    }

    private void eyeBearingMoves(AIPlayer ai, Map<String, Candidate> found, EnderEyeTrack.Observation eye,
                                 CandidateActionContext.Context actionContext) {
        double length = Math.hypot(eye.dx(), eye.dz());
        if (length < 2) return; // Require observed flight beyond the immediate release.
        var feet = ai.getPosition();
        var view = ai.getPlayer().getEyeLocation();
        var opacity = PerceptionSystem.opacityOf(view.getWorld());
        for (int stride : new int[]{4, 8, 12}) {
            int dx = (int) Math.round(stride * eye.dx() / length);
            int dz = (int) Math.round(stride * eye.dz() / length);
            for (int dy : new int[]{0, 1, -1}) {
                if (ai.getWorkBudget().isExhausted()) return;
                var target = feet.offset(dx, dy, dz);
                if (!Visibility.canSeePoint(opacity, view.getX(), view.getY(), view.getZ(),
                        target.x() + 0.5, target.y() + 0.25, target.z() + 0.5)) continue;
                add(ai, found, "move-eye:" + target.encode(), new PrimitiveCommand.MoveTo(target, 0.5),
                        null, null, dx, dy, dz, "thrown-eye", true, actionContext);
            }
        }
    }

    private static PolicyFrame.Option option(Candidate candidate, BlockPoint feet, EnderEyeTrack.Observation eye,
                                             CandidateActionContext.Context context, TableAccess tableAccess) {
        double[] features = candidate.encoded().features();
        var block = candidate.encoded().blockEvidence();
        String material = candidate.recipe() != null ? candidate.recipe().result() : block != null ? block.material()
                : candidate.command() instanceof PrimitiveCommand.PickupExact pickup ? pickup.material()
                : candidate.command() instanceof PrimitiveCommand.FurnaceTransfer transfer
                        && transfer.slot() == PrimitiveCommand.FurnaceSlot.RESULT ? transfer.material() : null;
        if (material != null && context.indirectIngredients().contains(material))
            features[CandidateActionContext.OBJECTIVE_INDIRECT_INGREDIENT_MATCH] = 1;
        boolean tableMissing = context.requiresCraftingTable() && tableAccess.knownMissing();
        if (context.requiresCraftingTable()) {
            String table = Material.CRAFTING_TABLE.name();
            boolean acquiresTable = table.equals(material) && context.stock().getOrDefault(table, 0) == 0;
            boolean placesTable = candidate.command() instanceof PrimitiveCommand.PlaceBlock place && table.equals(place.material());
            if ((acquiresTable || placesTable) && tableMissing) {
                if (acquiresTable) features[CandidateActionContext.ACQUIRES_REQUIRED_CRAFTING_TABLE] = 1;
                if (placesTable) features[CandidateActionContext.PLACES_REQUIRED_CRAFTING_TABLE] = 1;
            }
        }
        int pickupAmount = candidate.command() instanceof PrimitiveCommand.PickupExact
                ? Math.max(1, (int) Math.round(features[27] * 64.0)) : 0;
        var delta = CandidateActionContext.materialDelta(candidate.type(), candidate.recipe(), candidate.command(),
                candidate.consumable(), pickupAmount, context.storageStock(), context.storageSlots());
        CandidateActionContext.encodeMaterialImpact(features, delta, context, tableMissing);
        if (eye != null && candidate.command() instanceof PrimitiveCommand.MoveTo move) {
            double dx = move.target().x() - feet.x();
            double dz = move.target().z() - feet.z();
            double displacement = Math.hypot(dx, dz);
            double flight = Math.hypot(eye.dx(), eye.dz());
            if (flight >= 2 && displacement > 0) {
                features[281] = 1;
                features[282] = Math.clamp((dx * eye.dx() + dz * eye.dz()) / (displacement * flight), -1, 1);
            }
        }
        return new PolicyFrame.Option(candidate.id(), features, block, candidate.inputId());
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

    private void equipment(AIPlayer ai, Map<String, Candidate> found, CandidateActionContext.Context actionContext) {
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
                CandidateActionContext.encode(features, PrimitiveType.EQUIP_ITEM,
                        from.amount() > 0 ? from.material() : null, null, actionContext);
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

    static List<BlockPoint> miningObservationTargets(BlockPoint feet, BlockPoint crosshair, int cursor) {
        return miningObservationTargets(feet, crosshair, cursor, List.of());
    }

    static List<BlockPoint> miningObservationTargets(BlockPoint feet, BlockPoint crosshair, int cursor,
                                                     List<BlockPoint> sightTargets) {
        var nearby = nearbyTargets(feet);
        var targets = new LinkedHashSet<BlockPoint>();
        if (crosshair != null) targets.add(crosshair);
        // A visible block inside the local volume can still fall outside this tick's rotating scan.
        var visibleTargets = sightTargets.stream().distinct().iterator();
        for (int offset = 0; offset < nearby.size() && targets.size() < 24; offset++) {
            targets.add(nearby.get(Math.floorMod(cursor + offset, nearby.size())));
            if (targets.size() < 24 && visibleTargets.hasNext()) targets.add(visibleTargets.next());
        }
        return List.copyOf(targets);
    }

    private static List<BlockPoint> reachableSightTargets(PolicyFrame.SightObservation sight) {
        if (sight == null) return List.of();
        List<BlockPoint> result = new ArrayList<>();
        for (var ray : sight.rays()) {
            var block = ray.block();
            if (block == null) continue;
            double dx = block.x() + 0.5 - sight.eyeX();
            double dy = block.y() + 0.5 - sight.eyeY();
            double dz = block.z() + 0.5 - sight.eyeZ();
            if (dx * dx + dy * dy + dz * dz <= PrimitiveAdapter.BLOCK_REACH * PrimitiveAdapter.BLOCK_REACH)
                result.add(new BlockPoint(block.x(), block.y(), block.z()));
        }
        return result;
    }

    static int nextMiningObservationCursor(BlockPoint feet, int cursor, List<BlockPoint> inspected) {
        var nearby = nearbyTargets(feet);
        var processed = new HashSet<>(inspected);
        int advanced = 0;
        while (advanced < nearby.size() && processed.contains(nearby.get(Math.floorMod(cursor + advanced, nearby.size())))) advanced++;
        return Math.floorMod(cursor + advanced, nearby.size());
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
                        SurvivalObservation.Item consumable, double dx, double dy, double dz, String identity, boolean check,
                        CandidateActionContext.Context actionContext) {
        if (found.size() >= 512 || found.containsKey(id)) return false;
        if (check && (ai.getWorkBudget().isExhausted() || !PrimitiveAdapter.check(ai, command).allowed())) return false;
        double[] features = new double[PolicyFrame.ACTION_SIZE];
        PrimitiveType type = recipe != null ? PrimitiveType.CRAFT_ITEM : consumable != null ? PrimitiveType.USE_ITEM : command.type();
        features[type.ordinal()] = 1;
        if (id.startsWith("approach:")) features[284] = 1;
        double range = command instanceof PrimitiveCommand.ShootBow ? ShootBowAction.RANGE : 16;
        features[24] = Math.clamp(dx / range, -1, 1); features[25] = Math.clamp(dy / range, -1, 1); features[26] = Math.clamp(dz / range, -1, 1);
        features[32 + bucket(identity, 16)] = 1;
        String semantic = recipe != null ? recipe.result() : consumable != null ? consumable.consumption().remainderMaterial()
                : command instanceof PrimitiveCommand.BreakBlock mine ? mine.tool().material()
                : command instanceof PrimitiveCommand.FurnaceTransfer transfer ? transfer.material()
                : command instanceof PrimitiveCommand.IgnitePortal ? "FLINT_AND_STEEL"
                : command instanceof PrimitiveCommand.EnterNetherPortal ? "NETHER_PORTAL"
                : command instanceof PrimitiveCommand.EnterEndPortal ? "END_PORTAL"
                : command instanceof PrimitiveCommand.ThrowEnderEye || command instanceof PrimitiveCommand.SocketEnderEye ? "ENDER_EYE"
                : command instanceof PrimitiveCommand.ShootBow ? "BOW"
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
        PolicyFrame.BlockEvidence blockEvidence = null;
        if (command instanceof PrimitiveCommand.BreakBlock mine) {
            var observedBlock = Positions.block(ai.getPlayer().getWorld(), mine.target());
            blockEvidence = new PolicyFrame.BlockEvidence(observedBlock.getType().name(), ai.getWorldId(),
                    mine.target().x(), mine.target().y(), mine.target().z(), Integer.toUnsignedLong(Bukkit.getCurrentTick()));
            var tool = ai.getPlayer().getInventory().getItem(mine.tool().slot());
            if (tool != null && !tool.isEmpty()) {
                Integer damage = tool.getData(io.papermc.paper.datacomponent.DataComponentTypes.DAMAGE);
                Integer maximum = tool.getData(io.papermc.paper.datacomponent.DataComponentTypes.MAX_DAMAGE);
                features[56] = maximum == null || maximum <= 0 ? 0 : Math.clamp((damage == null ? 0 : damage) / (double) maximum, 0, 1);
            }
            var actualTool = tool == null ? org.bukkit.inventory.ItemStack.empty() : tool;
            features[57] = Math.clamp(observedBlock.getBlockData().getDestroySpeed(actualTool, true) / 32.0, 0, 16);
        }
        if (recipe != null) features[27] = recipe.resultAmount() / 64.0;
        if (command instanceof PrimitiveCommand.PickupExact pickup && Bukkit.getEntity(pickup.entityId()) instanceof org.bukkit.entity.Item drop) {
            features[27] = Math.min(16, drop.getItemStack().getAmount() / 64.0);
            features[285] = 1;
        }
        if (command instanceof PrimitiveCommand.FurnaceTransfer transfer) features[29] = transfer.inventorySlot() / 35.0;
        String directMaterial = recipe != null ? recipe.result()
                : blockEvidence != null ? blockEvidence.material()
                : command instanceof PrimitiveCommand.PlaceBlock place ? place.material()
                : command instanceof PrimitiveCommand.FurnaceTransfer transfer ? transfer.material()
                : command instanceof PrimitiveCommand.PickupExact pickup ? pickup.material()
                : consumable != null ? consumable.material() : null;
        boolean takesObservedItem = command instanceof PrimitiveCommand.PickupExact
                || command instanceof PrimitiveCommand.FurnaceTransfer transfer
                && transfer.slot() == PrimitiveCommand.FurnaceSlot.RESULT;
        CandidateActionContext.encode(features, type, directMaterial, recipe, actionContext, takesObservedItem);
        found.put(id, new Candidate(id, command, recipe, consumable, new PolicyFrame.Option(id, features, blockEvidence)));
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

    /** Keep execution identity and observation age; finite movement/equipment retain their existing bound. */
    static List<Candidate> compatibleReconsiderations(Candidate active) {
        if (active.movement() != null || active.equipment() != null || active.command() instanceof PrimitiveCommand.ShootBow) return List.of();
        List<Candidate> result = new ArrayList<>();
        for (int ticks : new int[]{5, 20, 80, 600}) {
            var variant = active.committed(ticks);
            if (variant.id().equals(active.id())) continue;
            double[] features = variant.encoded().features();features[55] = 0;
            result.add(new Candidate(variant.id(), variant.command(), variant.recipe(), variant.consumable(),
                    new PolicyFrame.Option(variant.id(), features, variant.encoded().blockEvidence()), variant.commitmentTicks(),
                    variant.inputId(), variant.equipment(), variant.movement()));
        }
        return List.copyOf(result);
    }
}
