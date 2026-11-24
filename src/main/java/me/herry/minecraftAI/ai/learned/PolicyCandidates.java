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

/**
 * Enumerates supported concrete inputs. No Goal score, utility, progression
 * order, best-food/tool or Teacher selection.
 */
public final class PolicyCandidates {
    private static final List<Integer> RECONSIDERATION_TICKS = List.of(5, 20, 80, 600);
    private static final List<Integer> MINING_RECONSIDERATION_TICKS = List.of(5, 20, 80);
    private static final List<Integer> MOVE_RECONSIDERATION_TICKS = List.of(20, 80);
    private static final List<Integer> INPUT_RECONSIDERATION_TICKS = List.of(5, 20);
    static final int MOVEMENT_INPUT_CHOICES_PER_FRAME = 12;
    public record Candidate(String id, PrimitiveCommand command, RecipeOption recipe,
            SurvivalObservation.Item consumable,
            PolicyFrame.Option encoded, int commitmentTicks, String inputId, ExactEquipment.Choice equipment,
            MovementInput movement) {
        public Candidate(String id, PrimitiveCommand command, RecipeOption recipe, SurvivalObservation.Item consumable,
                PolicyFrame.Option encoded, int commitmentTicks, String inputId, ExactEquipment.Choice equipment) {
            this(id, command, recipe, consumable, encoded, commitmentTicks, inputId, equipment, null);
        }

        public Candidate(String id, PrimitiveCommand command, RecipeOption recipe, SurvivalObservation.Item consumable,
                PolicyFrame.Option encoded) {
            this(id, command, recipe, consumable, encoded, 0, id, null);
        }

        public Candidate(String id, PrimitiveCommand command, RecipeOption recipe, SurvivalObservation.Item consumable,
                PolicyFrame.Option encoded, int commitmentTicks, String inputId) {
            this(id, command, recipe, consumable, encoded, commitmentTicks, inputId, null);
        }

        public PrimitiveType type() {
            return movement != null ? PrimitiveType.MOVE_INPUT
                    : equipment != null ? PrimitiveType.EQUIP_ITEM
                            : recipe != null ? PrimitiveType.CRAFT_ITEM
                                    : consumable != null ? PrimitiveType.USE_ITEM : command.type();
        }

        /**
         * Mining may hold the input across several observations. New hazards can
         * still bring reconsideration forward in the learned execution lane.
         */
        public int reconsiderAfterTicks() {
            if (command instanceof PrimitiveCommand.ShootBow)
                return ShootBowAction.ACTION_TICKS;
            if (recipe != null)
                return VanillaCraftAction.ACTION_TICKS;
            int maxDelay = command instanceof PrimitiveCommand.BreakBlock ? 80 : 20;
            return Math.max(1, Math.min(maxDelay, commitmentTicks));
        }

        public Candidate committed(int ticks) {
            double[] features = encoded.features();
            features[30] = ticks / 600.0;
            String choice = inputId + "@" + ticks;
            // Changing a finite key hold must replace the old Action rather than keep its
            // old deadline.
            return new Candidate(choice, command, recipe, consumable,
                    new PolicyFrame.Option(choice, features, encoded.blockEvidence()), ticks,
                    movement == null ? inputId : choice, equipment, movement);
        }

        public Action create(AIPlayer ai) {
            if (movement != null)
                return new MovementInputAction(movement, commitmentTicks);
            if (equipment != null)
                return new EquipExactAction(equipment);
            if (recipe != null)
                return new VanillaCraftAction(Material.valueOf(recipe.result()), recipe.id());
            if (consumable != null)
                return new ConsumeExactAction(ai.getWorldId(), consumable);
            if (command instanceof PrimitiveCommand.Attack hit)
                return new StrikeExactAction(ai.getWorldId(), hit.entityId());
            return PrimitiveAdapter.create(ai, command);
        }
    }

    public record Capture(PolicyFrame frame, List<Candidate> candidates, SurvivalObservation.Body body,
            Map<String, Integer> stock) {
        public Capture {
            candidates = List.copyOf(candidates);
            stock = Map.copyOf(stock);
        }
    }

    private int recipeCursor;
    private int furnaceStorageCursor;
    private int portalObservationCursor;
    private long recipeRevision = Long.MIN_VALUE;
    private final CandidateDiscovery discovery = new CandidateDiscovery();
    private final CandidateActionContext candidateContext = new CandidateActionContext();
    private final Map<Material, CandidateActionContext.MaterialProperties> materialPropertiesCache =
            new EnumMap<>(Material.class);
    private CandidateActionContext.HarvestCapabilities ownedHarvestCaps =
            CandidateActionContext.HarvestCapabilities.NONE;
    private long materialPropertiesRevision = Long.MIN_VALUE;
    private int recipeOptions;
    private int recipesChecked;
    private boolean recipeScanComplete;
    private int miningCursor;
    private int floorMiningCursor;
    private int miningObservationCursor;
    private PolicyFrame.MiningScan miningScan;
    private int equipmentCursor;
    private int consumableCursor;
    private PolicyFrame.ConsumptionScan consumptionScan;
    private int placementCursor;
    private int placementOffsetCursor;
    private int bucketCursor;
    private int dropCursor;
    private int interactCursor;
    private int furnaceScanCursor;
    private int igniteCursor;
    private int eyeFrameCursor;
    private int movementCursor;
    private int approachRayCursor;
    private int movementInputCursor;
    private int pickupCursor;
    static final int NEARBY_TARGET_COUNT = 114;
    private static final List<BlockPoint> MOVE_OFFSETS = movementOffsets();
    private static final List<BlockPoint> PLACE_OFFSETS = placementOffsets();

    /**
     * Candidate generation and workstation features share the same local reach
     * check.
     */
    private static final class TableAccess implements BooleanSupplier {
        private final Player player;
        private final WorkBudget budget;
        private Boolean available;

        TableAccess(AIPlayer ai) {
            player = ai.getPlayer();
            budget = ai.getWorkBudget();
        }

        @Override
        public boolean getAsBoolean() {
            if (available == null)
                available = NearbyBlocks.findInteractable(player, Material.CRAFTING_TABLE, 4) != null;
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

        public Observed {
            stock = Map.copyOf(stock);
            features = features.clone();
        }

        @Override
        public double[] features() {
            return features.clone();
        }
    }

    public static Observed observe(AIPlayer ai, Candidate active, int activeTicks) {
        var state = PrimitiveAdapter.capture(ai);
        double[] body = new double[PolicyFrame.STATE_SIZE];
        var p = ai.getPlayer();
        var survival = SurvivalCapture.body(p);
        body[0] = survival.health() / Math.max(1, survival.maxHealth());
        body[1] = survival.foodLevel() / 20.0;
        body[2] = survival.saturation() / 20;
        body[3] = survival.remainingAir() / (double) Math.max(1, survival.maximumAir());
        body[4] = Math.min(1, survival.absorption() / 20);
        body[5] = p.isInLava() ? 1 : 0;
        body[6] = p.getFireTicks() > 0 ? 1 : 0;
        body[7] = p.isInWater() ? 1 : 0;
        body[8] = ai.getBody().isGrounded() ? 1 : 0;
        body[9] = p.getFreezeTicks() / (double) Math.max(1, p.getMaxFreezeTicks());
        body[10] = Math.clamp(p.getVelocity().getY() / 4, -1, 1);
        body[11] = Math.min(1, p.getFallDistance() / 16);
        body[12] = p.getLocation().getPitch() / 90;
        body[13] = Math.sin(p.getWorld().getTime() * 2 * Math.PI / 24000);
        body[14] = Math.cos(p.getWorld().getTime() * 2 * Math.PI / 24000);
        body[15] = p.getWorld().hasStorm() ? 1 : 0;
        for (var effect : SurvivalCapture.effects(p))
            body[16 + bucket(effect.key(), 16)] += Math.min(1, (effect.amplifier() + 1) / 8.0);
        if (active != null) {
            String activeKind = active.type().name();
            body[40 + bucket(activeKind, 7)] = 1;
            body[47] = Math.min(1, activeTicks / 200.0);
        }
        body[32 + bucket(p.getInventory().getItemInMainHand().getType().name(), 8)] = 1;
        for (var item : state.inventory())
            body[48 + bucket(item.material(), 16)] += item.amount() / 64.0;
        LocalPolicyContext.capture(ai, body);
        EquipmentPolicyContext.capture(p, body);
        MotionPolicyContext.capture(p, survival, body);
        for (int i = 0; i < body.length; i++)
            body[i] = Math.clamp(body[i], -16, 16);
        Map<String, Integer> counts = new HashMap<>();
        for (var item : state.inventory())
            if (item.amount() > 0)
                counts.merge(item.material(), item.amount(), Integer::sum);
        // Ownership rewards include worn/off-hand items; moving them back to storage is
        // not a discovery.
        // Crafting availability still uses its separate storage-only snapshot below.
        for (int slot = 36; slot <= 40; slot++) {
            var item = p.getInventory().getItem(slot);
            if (item != null && !item.isEmpty())
                counts.merge(item.getType().name(), item.getAmount(), Integer::sum);
        }
        TaskPolicyContext.encode(body, ai.getPolicyTaskMemory().observe(ai.getWorldId(), counts), ai.getTicks(),
                ai.getWorldId());
        if (ai.getConfig().maskObjectiveInput)
            TaskPolicyContext.maskObjective(body);
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
        WorldPolicyContext.capture(ai, body);
        return new Observed(state, survival, counts, body, sight, eye);
    }

    public Capture capture(AIPlayer ai, Candidate active, int activeTicks) {
        var observed = observe(ai, active, activeTicks);
        ownedHarvestCaps = computeOwnedHarvestCapabilities(ai, observed.stock());
        var state = observed.snapshot();
        var body = observed.features();
        var taskView = ai.getPolicyTaskMemory().observe(ai.getWorldId(), observed.stock());
        Map<String, Integer> storageStock = new HashMap<>();
        List<CandidateActionContext.SlotAmount> storageSlots = new ArrayList<>();
        for (var slot : state.inventory()) {
            if (slot.slot() >= 36 || slot.amount() <= 0)
                continue;
            storageStock.merge(slot.material(), slot.amount(), Integer::sum);
            storageSlots.add(new CandidateActionContext.SlotAmount(slot.slot(), slot.material(), slot.amount()));
        }
        var actionContext = candidateContext.capture(ai.getCrafting(), observed.stock(), storageStock, storageSlots,
                taskView, !ai.getConfig().maskObjectiveInput);
        var tableAccess = new TableAccess(ai);
        Map<String, Candidate> found = new LinkedHashMap<>();
        // Waiting is always a legal input choice, without awarding it any hand-written
        // priority.
        add(ai, found, "wait:5", new PrimitiveCommand.Wait(5), null, null, 0, 0, 0, "", false, actionContext);
        if (active != null)
            found.putIfAbsent(active.id(), active);
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
                case JUMP ->
                    add(ai, found, "jump", new PrimitiveCommand.Jump(), null, null, 0, 0, 0, "", true, actionContext);
                case EQUIPMENT -> equipment(ai, found, actionContext);
                case MINE -> mining(ai, state.inventory(), observed.sight(), found, actionContext);
                case CONSUME -> consumables(ai, state.inventory(), found, actionContext);
                case PLACE -> placement(ai, state.inventory(), found, actionContext);
                case ATTACK -> attacks(ai, found, actionContext);
                case PICKUP -> pickups(ai, found, actionContext);
                case CRAFT -> crafting(ai, found, actionContext, tableAccess);
                case FURNACE -> furnaceTransfers(ai, observed.sight(), found, actionContext);
                case IGNITE -> portalIgnitions(ai, observed.sight(), found, actionContext);
                case BUCKET -> buckets(ai, observed.sight(), found, actionContext);
                case DROP -> drops(ai, state.inventory(), found, actionContext);
                case INTERACT -> interactions(ai, observed.sight(), found, actionContext);
                case PORTAL -> portalEntries(ai, found, actionContext);
                case ENDER_EYE -> enderEyes(ai, observed.sight(), observed.eye(), found, actionContext);
                case VISIBLE_APPROACH -> visibleApproaches(ai, observed.sight(), found, actionContext);
            }
            generated.put(source.name(), found.size() - before);
        });
        List<Candidate> expanded = new ArrayList<>();
        for (Candidate candidate : found.values()) {
            if (candidate.commitmentTicks() > 0) {
                double[] retained = candidate.encoded().features();
                retained[55] = 1;
                expanded.add(new Candidate(candidate.id(), candidate.command(), candidate.recipe(),
                        candidate.consumable(),
                        new PolicyFrame.Option(candidate.id(), retained, candidate.encoded().blockEvidence()),
                        candidate.commitmentTicks(), candidate.inputId(), candidate.equipment(), candidate.movement()));
                expanded.addAll(compatibleReconsiderations(candidate));
            } else if (candidate.recipe() != null)
                expanded.add(candidate.committed(VanillaCraftAction.ACTION_TICKS));
            else if (candidate.command() instanceof PrimitiveCommand.ShootBow)
                expanded.add(candidate.committed(ShootBowAction.ACTION_TICKS));
            else if (candidate.command() instanceof PrimitiveCommand.ShieldBlock shield)
                expanded.add(candidate.committed(shield.holdTicks()));
            else if (candidate.command() instanceof PrimitiveCommand.UseBed)
                expanded.add(candidate.committed(ExactUseBedAction.ACTION_TICKS));
            else if (candidate.command() instanceof PrimitiveCommand.LootChest
                    || candidate.command() instanceof PrimitiveCommand.FurnaceTransfer
                    || candidate.command() instanceof PrimitiveCommand.IgnitePortal
                    || candidate.command() instanceof PrimitiveCommand.FillBucket
                    || candidate.command() instanceof PrimitiveCommand.PourBucket
                    || candidate.command() instanceof PrimitiveCommand.ThrowEnderEye
                    || candidate.command() instanceof PrimitiveCommand.SocketEnderEye
                    || candidate.command() instanceof PrimitiveCommand.LookAt
                    || candidate.command() instanceof PrimitiveCommand.Jump
                    || candidate.command() instanceof PrimitiveCommand.PlaceBlock)
                expanded.add(candidate.committed(20));
            else if (candidate.equipment() != null || candidate.command() instanceof PrimitiveCommand.DropExact
                    || candidate.command() instanceof PrimitiveCommand.ToggleBlock)
                expanded.add(candidate.committed(5));
            else
                for (int ticks : reconsiderationTicks(candidate))
                    expanded.add(candidate.committed(ticks));
        }
        var candidates = selectFair(expanded, active == null ? "" : active.id());
        var feet = ai.getPosition();
        var evidence = new PolicyFrame.Discovery("candidate-discovery/v1",
                visit.sources().stream().map(Enum::name).toList(),
                generated, visit.budgetExhausted(), recipeOptions, recipesChecked, recipeScanComplete, consumptionScan,
                miningScan);
        // Direct keys and budgeted registries are partial observations, even after
        // every source was visited.
        boolean tableReachable = body[WorldPolicyContext.TABLE_REACHABLE] == 1.0;
        boolean furnaceReachable = body[WorldPolicyContext.FURNACE_REACHABLE] == 1.0;
        return new Capture(new PolicyFrame(ai.getWorldId(), Integer.toUnsignedLong(Bukkit.getCurrentTick()), body,
                candidates.stream()
                        .map(candidate -> option(ai, candidate, feet, observed.eye(), actionContext, tableAccess,
                                tableReachable, furnaceReachable))
                        .toList(),
                false, evidence,
                taskView, observed.sight()), candidates, observed.body(), observed.stock());
    }

    static final int MAX_LOCAL_MOVE_CANDIDATES = 2;
    static final int MAX_VISIBLE_APPROACH_CANDIDATES = 4;

    static int[] approachElevationCandidates(int originY, int blockY) {
        List<Integer> ys = new ArrayList<>(6);
        for (int candidate : new int[] { originY, originY + 1, originY - 1, blockY, blockY - 1, blockY + 1 }) {
            if (Math.abs(candidate - originY) <= 6 && !ys.contains(candidate))
                ys.add(candidate);
        }
        return ys.stream().mapToInt(Integer::intValue).toArray();
    }

    private void movementTargets(AIPlayer ai, Map<String, Candidate> found,
            CandidateActionContext.Context actionContext) {
        var offsets = movementOffsets(movementCursor);
        int offered = 0;
        int attempted = 0;
        int limit = Math.min(offsets.size(), 16);
        while (attempted < limit && offered < MAX_LOCAL_MOVE_CANDIDATES && !ai.getWorkBudget().isExhausted()) {
            var delta = offsets.get(attempted++);
            BlockPoint target = ai.getPosition().offset(delta.x(), delta.y(), delta.z());
            if (add(ai, found, "move:" + target.encode(), new PrimitiveCommand.MoveTo(target, 0.5), null, null,
                    delta.x(), delta.y(), delta.z(), "", true, actionContext))
                offered++;
        }
        movementCursor = nextCoprimeCursor(movementCursor, attempted, MOVE_OFFSETS.size());
    }

    private void visibleApproaches(AIPlayer ai, PolicyFrame.SightObservation sight, Map<String, Candidate> found,
            CandidateActionContext.Context actionContext) {
        if (sight == null)
            return;
        var rays = sight.rays();
        int rayCount = rays.size();
        if (rayCount == 0)
            return;
        int startRay = Math.floorMod(approachRayCursor++, rayCount);
        var origin = ai.getPosition();
        var world = ai.getPlayer().getWorld();
        var opacity = PerceptionSystem.opacityOf(world);
        var visited = new HashSet<BlockPoint>();
        int offeredCount = 0;

        for (int step = 0; step < rayCount && offeredCount < MAX_VISIBLE_APPROACH_CANDIDATES
                && !ai.getWorkBudget().isExhausted(); step++) {
            var ray = rays.get((startRay + step) % rayCount);
            var observed = ray.block();
            if (observed != null) {
                if (ray.distance() <= PrimitiveAdapter.BLOCK_REACH)
                    continue;
                var material = Material.getMaterial(observed.material());
                if (material == null || !material.isSolid())
                    continue;
                var block = new BlockPoint(observed.x(), observed.y(), observed.z());
                if (!visited.add(block))
                    continue;
                double dx = block.x() - origin.x();
                double dz = block.z() - origin.z();
                double distance = Math.hypot(dx, dz);
                if (distance <= PrimitiveAdapter.BLOCK_REACH)
                    continue;
                // Stop short of the observed face. The move executor still plans and walks the
                // actual route.
                double fraction = (distance - 2.5) / distance;
                int standX = origin.x() + (int) Math.round(dx * fraction);
                int standZ = origin.z() + (int) Math.round(dz * fraction);
                int sideX = (int) Math.round(-dz / distance);
                int sideZ = (int) Math.round(dx / distance);
                int[] elevations = approachElevationCandidates(origin.y(), block.y());
                boolean offered = false;
                for (int side : new int[] { 0, -1, 1 }) {
                    for (int y : elevations) {
                        if (ai.getWorkBudget().isExhausted())
                            return;
                        if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight())
                            continue;
                        var stand = new BlockPoint(standX + side * sideX, y, standZ + side * sideZ);
                        if (!Positions.isLoaded(world, stand) || !visibleStandingPoint(sight, opacity, stand))
                            continue;
                        if (add(ai, found, "approach:" + block.encode() + ":" + stand.encode(),
                                new PrimitiveCommand.MoveTo(stand, 0.5),
                                null, null, stand.x() - origin.x(), y - origin.y(), stand.z() - origin.z(),
                                observed.material(), true, actionContext)) {
                            offeredCount++;
                            offered = true;
                            break;
                        }
                    }
                    if (offered)
                        break;
                }
            } else if ("CLEAR".equals(ray.status())) {
                double rad = Math.toRadians(sight.yaw() + DirectionalPolicyContext.rayAngle(ray.index()));
                double ux = -Math.sin(rad);
                double uz = Math.cos(rad);
                boolean offered = false;
                for (int stride : new int[] { 10, 6 }) {
                    int standX = origin.x() + (int) Math.round(ux * stride);
                    int standZ = origin.z() + (int) Math.round(uz * stride);
                    if (!world.isChunkLoaded(standX >> 4, standZ >> 4))
                        continue;
                    for (int dy : new int[] { 0, 1, -1, 2, -2, 3, -3 }) {
                        if (ai.getWorkBudget().isExhausted())
                            return;
                        int y = origin.y() + dy;
                        if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight())
                            continue;
                        var stand = new BlockPoint(standX, y, standZ);
                        if (!visibleStandingPoint(sight, opacity, stand))
                            continue;
                        if (add(ai, found, "approach:clear:" + ray.index() + ":" + stand.encode(),
                                new PrimitiveCommand.MoveTo(stand, 0.5),
                                null, null, stand.x() - origin.x(), dy, stand.z() - origin.z(),
                                "HORIZON", true, actionContext)) {
                            offeredCount++;
                            offered = true;
                            break;
                        }
                    }
                    if (offered)
                        break;
                }
            }
        }
    }

    private static boolean visibleStandingPoint(PolicyFrame.SightObservation sight, Visibility.Opacity opacity,
            BlockPoint stand) {
        return Visibility.canSeePoint(opacity, sight.eyeX(), sight.eyeY(), sight.eyeZ(),
                stand.x() + 0.5, stand.y() + 0.25, stand.z() + 0.5);
    }

    static final int[] LOOK_YAW_OFFSETS = { -90, -45, 0, 45, 90 };

    private void look(AIPlayer ai, Map<String, Candidate> found, CandidateActionContext.Context actionContext) {
        var p = ai.getPlayer();
        for (int yaw : LOOK_YAW_OFFSETS) {
            double angle = Math.toRadians(p.getLocation().getYaw() + yaw);
            var eye = p.getEyeLocation();
            var target = new PrimitiveTarget.Point(eye.getX() - Math.sin(angle) * 4, eye.getY(),
                    eye.getZ() + Math.cos(angle) * 4);
            add(ai, found, "look:" + yaw, new PrimitiveCommand.LookAt(target), null, null, -Math.sin(angle) * 4, 0,
                    Math.cos(angle) * 4, "", true, actionContext);
        }
    }

    private void pickups(AIPlayer ai, Map<String, Candidate> found, CandidateActionContext.Context actionContext) {
        var drops = ai.getPerception().getDrops();
        var origin = ai.getPlayer().getLocation();
        int offered = 0;
        for (int scanned = 0; scanned < Math.min(16, drops.size()) && offered < 6
                && !ai.getWorkBudget().isExhausted(); scanned++) {
            var drop = drops.get(Math.floorMod(pickupCursor++, drops.size()));
            var at = drop.getLocation();
            var material = drop.getItemStack().getType().name();
            var command = new PrimitiveCommand.PickupExact(ai.getWorldId(), drop.getUniqueId(), material);
            if (add(ai, found, "pickup-exact:" + drop.getUniqueId(), command, null, null, at.getX() - origin.getX(),
                    at.getY() - origin.getY(), at.getZ() - origin.getZ(), material, true, actionContext))
                offered++;
        }
        add(ai, found, "pickup:4", new PrimitiveCommand.Pickup(4), null, null, 0, 0, 0, "", true, actionContext);
    }

    private void mining(AIPlayer ai,
            List<me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot> inventory,
            PolicyFrame.SightObservation sight, Map<String, Candidate> found,
            CandidateActionContext.Context actionContext) {
        var p = ai.getPlayer();
        var feet = ai.getPosition();
        boolean rayLoaded = true;
        for (int dx : new int[] { -5, 5 })
            for (int dz : new int[] { -5, 5 })
                rayLoaded &= p.getWorld().isChunkLoaded((feet.x() + dx) >> 4, (feet.z() + dz) >> 4);
        var ray = rayLoaded ? p.getTargetBlockExact(4) : null;
        // Keep the actual crosshair, then rotate geometric observation coverage. A
        // static nearest
        // prefix otherwise never inspects many reachable cells while standing at the
        // same location.
        var targets = miningObservationTargets(feet, ray == null ? null : Positions.of(ray), miningObservationCursor,
                reachableSightTargets(sight));
        int previousCursor = miningObservationCursor;
        // Inspect only local visible cells. Unloaded/hidden cells never become material
        // observations.
        var inspected = PrimitiveAdapter.inspect(ai, targets);
        miningObservationCursor = nextMiningObservationCursor(feet, previousCursor,
                inspected.blocks().stream().map(block -> block.point()).toList());
        miningScan = new PolicyFrame.MiningScan("mining-window-scan/v1", previousCursor, miningObservationCursor,
                targets.size(), inspected.blocks().size(),
                (int) inspected.blocks().stream().filter(block -> block.visible() && block.loaded()).count(),
                inspected.blocks().size() == targets.size());
        var tools = miningSlots(inventory);
        var blocks = inspected.blocks().stream()
                .filter(block -> block.visible() && block.loaded()
                        && !block.material().equals("AIR") && !block.material().equals("CAVE_AIR")
                        && !block.material().equals("VOID_AIR"))
                .toList();
        var aboveFloor = blocks.stream().filter(block -> block.point().y() >= feet.y()).toList();
        var belowFloor = blocks.stream().filter(block -> block.point().y() < feet.y()).toList();
        // Pure geometric 2:1 balance across eye/horizon blocks and floor cells,
        // spreading distinct blocks before pairing more tools with the same block.
        int abovePairs = tools.size() * aboveFloor.size();
        int belowPairs = tools.size() * belowFloor.size();
        int aboveLimit = Math.min(abovePairs, 36);
        int belowLimit = Math.min(belowPairs, 24);
        int aboveOffered = 0, aboveAttempted = 0;
        int belowOffered = 0, belowAttempted = 0;
        while (!ai.getWorkBudget().isExhausted()
                && ((aboveOffered < MAX_ABOVE_FLOOR_MINING_CANDIDATES && aboveAttempted < aboveLimit)
                        || (belowOffered < MAX_BELOW_FLOOR_MINING_CANDIDATES && belowAttempted < belowLimit))) {
            int stepAdded = 0;
            while (stepAdded < 2 && aboveOffered < MAX_ABOVE_FLOOR_MINING_CANDIDATES
                    && aboveAttempted < aboveLimit && !ai.getWorkBudget().isExhausted()) {
                int pair = Math.floorMod(miningCursor + aboveAttempted++, abovePairs);
                if (offerMiningCandidate(ai, found, actionContext, feet, aboveFloor, tools, pair)) {
                    aboveOffered++;
                    stepAdded++;
                }
            }
            int floorAdded = 0;
            while (floorAdded < 1 && belowOffered < MAX_BELOW_FLOOR_MINING_CANDIDATES
                    && belowAttempted < belowLimit && !ai.getWorkBudget().isExhausted()) {
                int pair = Math.floorMod(floorMiningCursor + belowAttempted++, belowPairs);
                if (offerMiningCandidate(ai, found, actionContext, feet, belowFloor, tools, pair)) {
                    belowOffered++;
                    floorAdded++;
                }
            }
        }
        miningCursor = abovePairs == 0 ? 0 : nextCoprimeCursor(miningCursor, aboveAttempted, abovePairs);
        floorMiningCursor = belowPairs == 0 ? 0 : nextCoprimeCursor(floorMiningCursor, belowAttempted, belowPairs);
    }

    static final int MAX_ABOVE_FLOOR_MINING_CANDIDATES = 8;
    static final int MAX_BELOW_FLOOR_MINING_CANDIDATES = 8;

    static int miningBlockIndex(int pairIndex, int blockCount) {
        if (pairIndex < 0 || blockCount <= 0)
            throw new IllegalArgumentException("invalid mining pair index");
        return pairIndex % blockCount;
    }

    static int miningToolIndex(int pairIndex, int blockCount, int toolCount) {
        if (pairIndex < 0 || blockCount <= 0 || toolCount <= 0)
            throw new IllegalArgumentException("invalid mining tool index");
        int norm = Math.floorMod(pairIndex, blockCount * toolCount);
        int b = norm % blockCount;
        int q = norm / blockCount;
        return (q + b) % toolCount;
    }

    private boolean offerMiningCandidate(AIPlayer ai, Map<String, Candidate> found,
            CandidateActionContext.Context actionContext, BlockPoint feet,
            List<me.herry.minecraftAI.ai.primitive.runtime.PrimitiveContext.BlockView> targets,
            List<me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot> tools, int pair) {
        var block = targets.get(miningBlockIndex(pair, targets.size()));
        var slot = tools.get(miningToolIndex(pair, targets.size(), tools.size()));
        ToolChoice tool = new ToolChoice(slot.slot(), slot.material(), slot.enchantments());
        var delta = block.point().offset(-feet.x(), -feet.y(), -feet.z());
        String id = "mine:" + block.point().encode() + ":" + slot.slot();
        return add(ai, found, id, new PrimitiveCommand.BreakBlock(block.point(), tool), null, null,
                delta.x(), delta.y(), delta.z(), block.material() + ":" + slot.material(), true, actionContext);
    }

    private void consumables(AIPlayer ai,
            List<me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot> inventory,
            Map<String, Candidate> found, CandidateActionContext.Context actionContext) {
        int inspected = 0;
        int snapshotSlots = (int) inventory.stream().filter(slot -> slot.slot() < 36).count();
        int scanned = 0, empty = 0, nonConsumable = 0, hunger = 0, blocked = 0, unknown = 0, unsupported = 0,
                available = 0;
        while (inspected < inventory.size() && !ai.getWorkBudget().isExhausted()) {
            var slot = inventory.get(Math.floorMod(consumableCursor++, inventory.size()));
            inspected++;
            if (slot.slot() >= 36)
                continue;
            scanned++;
            if (slot.amount() == 0) {
                empty++;
                continue;
            }
            var item = SurvivalCapture.item(ai, slot.slot());
            var use = item.consumption();
            if (item.amount() == 0)
                empty++;
            else if (ConsumeExactAction.supported(item)) {
                available++;
                add(ai, found, "consume:" + slot.slot(), null, null, item, 0, 0, 0, item.material(), false,
                        actionContext);
            } else
                switch (use.eligibility()) {
                    case NOT_CONSUMABLE -> nonConsumable++;
                    case BLOCKED -> {
                        if (use.reason().equals("requires hunger"))
                            hunger++;
                        else
                            blocked++;
                    }
                    case UNKNOWN -> unknown++;
                    case UNSUPPORTED, AVAILABLE -> unsupported++;
                }
        }
        consumptionScan = new PolicyFrame.ConsumptionScan(snapshotSlots, scanned, empty, nonConsumable,
                hunger, blocked, unknown, unsupported, available);
    }

    private void placement(AIPlayer ai,
            List<me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot> inventory,
            Map<String, Candidate> found, CandidateActionContext.Context actionContext) {
        int placed = 0;
        int inspected = 0;
        var world = ai.getPlayer().getWorld();
        var window = placementOffsets(placementOffsetCursor, 8);
        Map<BlockPoint, Boolean> cellOpen = new HashMap<>();
        int nextCursor = placementCursor;
        boolean advancedCursor = false;
        while (inspected < inventory.size() && placed < 12 && !ai.getWorkBudget().isExhausted()) {
            int idx = Math.floorMod(placementCursor + inspected++, inventory.size());
            var slot = inventory.get(idx);
            if (slot.slot() >= 36 || slot.amount() == 0)
                continue;
            Material material = Material.matchMaterial(slot.material());
            if (material != null && material.isBlock() && material.isOccluding() && !material.hasGravity()) {
                if (!advancedCursor) {
                    nextCursor = Math.floorMod(idx + 1, inventory.size());
                    advancedCursor = true;
                }
                int slotPlaced = 0;
                for (var delta : window) {
                    if (placed >= 12 || slotPlaced >= 2 || ai.getWorkBudget().isExhausted())
                        break;
                    BlockPoint target = ai.getPosition().offset(delta.x(), delta.y(), delta.z());
                    boolean open = cellOpen.computeIfAbsent(target,
                            point -> point.y() > world.getMinHeight() && point.y() + 1 < world.getMaxHeight()
                                    && Positions.isLoaded(world, point)
                                    && Positions.block(world, point).getType().isAir());
                    if (!open)
                        continue;
                    if (add(ai, found, "place:" + target.encode() + ":" + material,
                            new PrimitiveCommand.PlaceBlock(target, material.name()),
                            null, null, delta.x(), delta.y(), delta.z(), material.name(), true, actionContext)) {
                        placed++;
                        slotPlaced++;
                    }
                }
            }
        }
        placementCursor = nextCursor;
        placementOffsetCursor = Math.floorMod(placementOffsetCursor + 1, Math.max(1, PLACE_OFFSETS.size()));
    }

    private void attacks(AIPlayer ai, Map<String, Candidate> found, CandidateActionContext.Context actionContext) {
        var p = ai.getPlayer();
        var origin = p.getLocation();
        if (ai.getBody().canShootBow()) {
            for (var crystal : ai.getPerception().getEndCrystals()) {
                if (ai.getWorkBudget().isExhausted())
                    break;
                addBow(ai, found, actionContext, origin, crystal, crystal, crystal.getType().name());
            }
            for (var hostile : ai.getPerception().getHostiles()) {
                if (ai.getWorkBudget().isExhausted())
                    break;
                if (hostile instanceof org.bukkit.entity.Enemy && !(hostile instanceof EnderDragon)) {
                    addBow(ai, found, actionContext, origin, hostile, hostile, hostile.getType().name());
                }
            }
        }
        var inv = p.getInventory();
        if (inv.getItemInMainHand().getType() == Material.SHIELD) {
            add(ai, found, "shield-block:main", new PrimitiveCommand.ShieldBlock(ai.getWorldId(), false, 20),
                    null, null, 0, 0, 0, "shield-block:main", true, actionContext);
        }
        if (inv.getItemInOffHand().getType() == Material.SHIELD) {
            add(ai, found, "shield-block:off", new PrimitiveCommand.ShieldBlock(ai.getWorldId(), true, 20),
                    null, null, 0, 0, 0, "shield-block:off", true, actionContext);
        }
        var targets = new LinkedHashMap<UUID, org.bukkit.entity.Entity>();
        for (var entity : ai.getPerception().getEndCrystals())
            targets.putIfAbsent(entity.getUniqueId(), entity);
        for (var entity : ai.getPerception().getHostiles())
            targets.putIfAbsent(entity.getUniqueId(), entity);
        for (var entity : ai.getPerception().getAnimals())
            targets.putIfAbsent(entity.getUniqueId(), entity);
        for (var target : targets.values()) {
            if (ai.getWorkBudget().isExhausted())
                break;
            Entity contact = target;
            String identity = target.getType().name();
            if (target instanceof EnderDragon dragon) {
                contact = ai.getBody().dragonHead(dragon);
                identity = "ENDER_DRAGON_HEAD:" + dragon.getPhase().name();
                if (ai.getBody().canShootBow())
                    addBow(ai, found, actionContext, origin, dragon, contact, identity);
            }
            if (!StrikeExactAction.canReach(ai, target))
                continue;
            var at = contact.getLocation();
            add(ai, found, "attack:" + target.getUniqueId(), new PrimitiveCommand.Attack(target.getUniqueId()), null,
                    null,
                    at.getX() - origin.getX(), at.getY() - origin.getY(), at.getZ() - origin.getZ(), identity, true,
                    actionContext);
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
        if (recipeRevision != ai.getCrafting().revision()) {
            recipeCursor = 0;
            recipeRevision = ai.getCrafting().revision();
        }
        int crafted = 0;
        var stock = ai.getInventory().snapshot();
        var recipes = ai.getCrafting().feasibleRecipeOptions(stock, tableAccess);
        recipeOptions = recipes.size();
        recipesChecked = 0;
        while (recipesChecked < Math.min(64, recipes.size()) && crafted < 24 && !ai.getWorkBudget().isExhausted()) {
            var recipe = recipes.get(Math.floorMod(recipeCursor++, recipes.size()));
            recipesChecked++;
            Material result = Material.matchMaterial(recipe.result());
            if (result == null || ai.getCrafting().singleStep(result, recipe.id(), stock) == null)
                continue;
            if (add(ai, found, "craft:" + recipe.id(), null, recipe, null, 0, 0, 0, recipe.id(), false, actionContext))
                crafted++;
        }
        recipeScanComplete = recipesChecked == recipes.size();
    }

    private void furnaceTransfers(AIPlayer ai, PolicyFrame.SightObservation sight, Map<String, Candidate> found,
            CandidateActionContext.Context actionContext) {
        var player = ai.getPlayer();
        var world = player.getWorld();
        var feet = ai.getPosition();
        var targets = new LinkedHashSet<BlockPoint>();
        if (sight != null) {
            for (var ray : sight.rays()) {
                var block = ray.block();
                if (block != null && "FURNACE".equals(block.material()))
                    targets.add(new BlockPoint(block.x(), block.y(), block.z()));
            }
        }
        var nearby = nearbyTargets(feet);
        for (int checked = 0; checked < 24 && targets.size() < 4 && !ai.getWorkBudget().isExhausted(); checked++) {
            var cell = nearby.get(Math.floorMod(furnaceScanCursor++, nearby.size()));
            if (cell.y() < world.getMinHeight() || cell.y() >= world.getMaxHeight() || !Positions.isLoaded(world, cell))
                continue;
            if (Positions.block(world, cell).getType() == Material.FURNACE)
                targets.add(cell);
        }
        if (targets.isEmpty())
            return;
        int start = furnaceStorageCursor;
        furnaceStorageCursor = (furnaceStorageCursor + 8) % 36;
        var eye = player.getEyeLocation();
        for (var target : targets) {
            if (ai.getWorkBudget().isExhausted())
                return;
            if (eye.distanceSquared(Positions.center(world, target)) > PrimitiveAdapter.BLOCK_REACH
                    * PrimitiveAdapter.BLOCK_REACH)
                continue;
            var furnace = Furnaces.inventory(world, target);
            if (furnace == null)
                continue;
            var output = furnace.getResult();
            if (output != null && !output.isEmpty())
                addFurnaceTransfer(ai, found, actionContext,
                        new PrimitiveCommand.FurnaceTransfer(ai.getWorldId(), target,
                                PrimitiveCommand.FurnaceSlot.RESULT, -1, output.getType().name()));
            for (int offset = 0; offset < 8 && !ai.getWorkBudget().isExhausted(); offset++) {
                int slot = (start + offset) % 36;
                var item = player.getInventory().getItem(slot);
                if (item == null || item.isEmpty())
                    continue;
                for (var destination : List.of(PrimitiveCommand.FurnaceSlot.INGREDIENT,
                        PrimitiveCommand.FurnaceSlot.FUEL))
                    addFurnaceTransfer(ai, found, actionContext, new PrimitiveCommand.FurnaceTransfer(ai.getWorldId(),
                            target, destination, slot, item.getType().name()));
            }
        }
    }

    private void addFurnaceTransfer(AIPlayer ai, Map<String, Candidate> found,
            CandidateActionContext.Context actionContext,
            PrimitiveCommand.FurnaceTransfer transfer) {
        var offset = transfer.target().offset(-ai.getPosition().x(), -ai.getPosition().y(), -ai.getPosition().z());
        String type = "furnace-" + transfer.slot().name().toLowerCase(Locale.ROOT);
        add(ai, found,
                type + ":" + transfer.target().encode() + ":" + transfer.inventorySlot() + ":" + transfer.material(),
                transfer, null, null, offset.x(), offset.y(), offset.z(), type, true, actionContext);
    }

    private void portalIgnitions(AIPlayer ai, PolicyFrame.SightObservation sight, Map<String, Candidate> found,
            CandidateActionContext.Context actionContext) {
        if (ai.getPlayer().getInventory().getItemInMainHand().getType() != Material.FLINT_AND_STEEL)
            return;
        var feet = ai.getPosition();
        var world = ai.getPlayer().getWorld();
        int offered = 0;
        if (sight != null) {
            for (var ray : sight.rays()) {
                if (offered >= 6 || ai.getWorkBudget().isExhausted())
                    return;
                var observed = ray.block();
                if (observed == null || !observed.material().equals("OBSIDIAN"))
                    continue;
                var floor = new BlockPoint(observed.x(), observed.y(), observed.z());
                if (add(ai, found, "ignite-portal:" + floor.encode(),
                        new PrimitiveCommand.IgnitePortal(ai.getWorldId(), floor),
                        null, null, floor.x() - feet.x(), floor.y() - feet.y(), floor.z() - feet.z(), "ignite-portal",
                        true, actionContext)) {
                    offered++;
                }
            }
        }
        var nearby = nearbyTargets(feet);
        for (int checked = 0; checked < 24 && offered < 6 && !ai.getWorkBudget().isExhausted(); checked++) {
            var floor = nearby.get(Math.floorMod(igniteCursor++, nearby.size()));
            if (floor.y() < world.getMinHeight() || floor.y() + 1 >= world.getMaxHeight()
                    || !Positions.isLoaded(world, floor))
                continue;
            if (Positions.block(world, floor).getType() != Material.OBSIDIAN)
                continue;
            if (add(ai, found, "ignite-portal:" + floor.encode(),
                    new PrimitiveCommand.IgnitePortal(ai.getWorldId(), floor),
                    null, null, floor.x() - feet.x(), floor.y() - feet.y(), floor.z() - feet.z(), "ignite-portal",
                    true, actionContext)) {
                offered++;
            }
        }
    }

    private void buckets(AIPlayer ai, PolicyFrame.SightObservation sight, Map<String, Candidate> found,
            CandidateActionContext.Context actionContext) {
        Material held = ai.getPlayer().getInventory().getItemInMainHand().getType();
        if (held != Material.BUCKET && held != Material.WATER_BUCKET && held != Material.LAVA_BUCKET)
            return;
        var feet = ai.getPosition();
        var world = ai.getPlayer().getWorld();
        var targets = nearbyTargets(feet);
        int offered = 0;
        if (held == Material.BUCKET) {
            for (int checked = 0; checked < 24 && offered < 6 && !ai.getWorkBudget().isExhausted(); checked++) {
                var cell = targets.get(Math.floorMod(bucketCursor++, targets.size()));
                if (cell.y() < world.getMinHeight() || cell.y() >= world.getMaxHeight()
                        || !Positions.isLoaded(world, cell))
                    continue;
                var block = Positions.block(world, cell);
                if (!FillBucketAction.isFillableSource(block))
                    continue;
                String fluid = block.getType().name();
                if (add(ai, found, "fill-bucket:" + cell.encode() + ":" + fluid,
                        new PrimitiveCommand.FillBucket(ai.getWorldId(), cell, fluid), null, null,
                        cell.x() - feet.x(), cell.y() - feet.y(), cell.z() - feet.z(), "fill-bucket:" + fluid, true,
                        actionContext)) {
                    offered++;
                }
            }
            return;
        }
        String bucketName = held.name();
        if (sight != null) {
            for (var ray : sight.rays()) {
                if (offered >= 6 || ai.getWorkBudget().isExhausted())
                    return;
                var observed = ray.block();
                if (observed == null)
                    continue;
                var support = new BlockPoint(observed.x(), observed.y(), observed.z());
                if (add(ai, found, "pour-bucket:" + support.encode() + ":" + bucketName,
                        new PrimitiveCommand.PourBucket(ai.getWorldId(), support, bucketName), null, null,
                        support.x() - feet.x(), support.y() - feet.y(), support.z() - feet.z(),
                        "pour-bucket:" + bucketName, true, actionContext)) {
                    offered++;
                }
            }
        }
        for (int checked = 0; checked < 24 && offered < 6 && !ai.getWorkBudget().isExhausted(); checked++) {
            var support = targets.get(Math.floorMod(bucketCursor++, targets.size()));
            if (support.y() < world.getMinHeight() || support.y() + 1 >= world.getMaxHeight()
                    || !Positions.isLoaded(world, support))
                continue;
            if (!Positions.block(world, support).getType().isSolid())
                continue;
            if (add(ai, found, "pour-bucket:" + support.encode() + ":" + bucketName,
                    new PrimitiveCommand.PourBucket(ai.getWorldId(), support, bucketName), null, null,
                    support.x() - feet.x(), support.y() - feet.y(), support.z() - feet.z(),
                    "pour-bucket:" + bucketName, true, actionContext)) {
                offered++;
            }
        }
    }

    private void drops(AIPlayer ai,
            List<me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot> inventory,
            Map<String, Candidate> found, CandidateActionContext.Context actionContext) {
        int offered = 0;
        int inspected = 0;
        int nextCursor = dropCursor;
        boolean advanced = false;
        while (inspected < inventory.size() && offered < 4 && !ai.getWorkBudget().isExhausted()) {
            int idx = Math.floorMod(dropCursor + inspected++, inventory.size());
            var slot = inventory.get(idx);
            if (slot.slot() >= 36 || slot.amount() <= 0)
                continue;
            if (!advanced) {
                nextCursor = Math.floorMod(idx + 1, inventory.size());
                advanced = true;
            }
            var cmd = new PrimitiveCommand.DropExact(ai.getWorldId(), slot.slot(), slot.material());
            if (add(ai, found, "drop-exact:" + slot.slot() + ":" + slot.material(), cmd, null, null, 0, 0, 0,
                    slot.material(), true, actionContext)) {
                offered++;
            }
        }
        dropCursor = nextCursor;
    }

    private void interactions(AIPlayer ai, PolicyFrame.SightObservation sight, Map<String, Candidate> found,
            CandidateActionContext.Context actionContext) {
        var feet = ai.getPosition();
        var world = ai.getPlayer().getWorld();
        int offered = 0;
        if (sight != null) {
            for (var ray : sight.rays()) {
                if (offered >= 6 || ai.getWorkBudget().isExhausted())
                    return;
                var observed = ray.block();
                if (observed == null)
                    continue;
                if (offerInteraction(ai, world, feet, new BlockPoint(observed.x(), observed.y(), observed.z()), found,
                        actionContext)) {
                    offered++;
                }
            }
        }
        var targets = nearbyTargets(feet);
        for (int checked = 0; checked < 24 && offered < 6 && !ai.getWorkBudget().isExhausted(); checked++) {
            var cell = targets.get(Math.floorMod(interactCursor++, targets.size()));
            if (offerInteraction(ai, world, feet, cell, found, actionContext)) {
                offered++;
            }
        }
    }

    private boolean offerInteraction(AIPlayer ai, org.bukkit.World world, BlockPoint feet, BlockPoint cell,
            Map<String, Candidate> found, CandidateActionContext.Context actionContext) {
        if (cell.y() < world.getMinHeight() || cell.y() >= world.getMaxHeight() || !Positions.isLoaded(world, cell))
            return false;
        var block = Positions.block(world, cell);
        Material type = block.getType();
        if (type.isAir())
            return false;
        if (ExactLootChestAction.isLootableContainer(type)) {
            return add(ai, found, "loot-chest:" + cell.encode(),
                    new PrimitiveCommand.LootChest(ai.getWorldId(), cell), null, null,
                    cell.x() - feet.x(), cell.y() - feet.y(), cell.z() - feet.z(), type.name(), true, actionContext);
        }
        if (org.bukkit.Tag.BEDS.isTagged(type)) {
            return add(ai, found, "use-bed:" + cell.encode(),
                    new PrimitiveCommand.UseBed(ai.getWorldId(), cell), null, null,
                    cell.x() - feet.x(), cell.y() - feet.y(), cell.z() - feet.z(), type.name(), true, actionContext);
        }
        if (ToggleBlockAction.isToggleable(block)) {
            if (block.getBlockData() instanceof org.bukkit.block.data.type.Door door
                    && door.getHalf() == org.bukkit.block.data.Bisected.Half.TOP)
                return false;
            return add(ai, found, "toggle-block:" + cell.encode(),
                    new PrimitiveCommand.ToggleBlock(ai.getWorldId(), cell), null, null,
                    cell.x() - feet.x(), cell.y() - feet.y(), cell.z() - feet.z(), type.name(), true, actionContext);
        }
        return false;
    }

    private void portalEntries(AIPlayer ai, Map<String, Candidate> found,
            CandidateActionContext.Context actionContext) {
        var feet = ai.getPosition();
        var world = ai.getPlayer().getWorld();
        if (world.getEnvironment() != org.bukkit.World.Environment.NORMAL
                && world.getEnvironment() != org.bukkit.World.Environment.NETHER)
            return;
        // Portal blocks are passable. A ray that sees through one is not evidence it is
        // absent.
        var targets = miningObservationTargets(feet.offset(0, -1, 0), feet, portalObservationCursor);
        int inspected = 0;
        for (var target : targets) {
            if (ai.getWorkBudget().isExhausted())
                break;
            inspected++;
            if (target.y() < world.getMinHeight() || target.y() >= world.getMaxHeight()
                    || !Positions.isLoaded(world, target))
                continue;
            var material = Positions.block(world, target).getType();
            PrimitiveCommand command;
            String kind;
            if (material == Material.NETHER_PORTAL) {
                var below = target.offset(0, -1, 0);
                if (!target.equals(feet) && below.y() >= world.getMinHeight()
                        && Positions.block(world, below).getType() == Material.NETHER_PORTAL)
                    continue;
                command = new PrimitiveCommand.EnterNetherPortal(ai.getWorldId(), target);
                kind = "enter-nether-portal";
            } else if (material == Material.END_PORTAL
                    && world.getEnvironment() == org.bukkit.World.Environment.NORMAL) {
                command = new PrimitiveCommand.EnterEndPortal(ai.getWorldId(), target);
                kind = "enter-end-portal";
            } else
                continue;
            add(ai, found, kind + ":" + target.encode(), command, null, null,
                    target.x() - feet.x(), target.y() - feet.y(), target.z() - feet.z(), kind, true, actionContext);
        }
        // The standing cell is prepended so a return portal remains discoverable
        // immediately.
        portalObservationCursor = nextMiningObservationCursor(feet.offset(0, -1, 0), portalObservationCursor,
                targets.subList(0, inspected));
    }

    private void enderEyes(AIPlayer ai, PolicyFrame.SightObservation sight, EnderEyeTrack.Observation eye,
            Map<String, Candidate> found,
            CandidateActionContext.Context actionContext) {
        var player = ai.getPlayer();
        if (eye != null) {
            var from = player.getEyeLocation();
            var target = new PrimitiveTarget.Point(eye.x(), eye.y(), eye.z());
            add(ai, found, "look:thrown-eye", new PrimitiveCommand.LookAt(target), null, null,
                    eye.x() - from.getX(), eye.y() - from.getY(), eye.z() - from.getZ(), "thrown-eye", true,
                    actionContext);
            eyeBearingMoves(ai, found, eye, actionContext);
        }
        if (player.getWorld().getEnvironment() != org.bukkit.World.Environment.NORMAL
                || player.getInventory().getItemInMainHand().getType() != Material.ENDER_EYE)
            return;
        add(ai, found, "throw-ender-eye", new PrimitiveCommand.ThrowEnderEye(ai.getWorldId()), null, null,
                0, 0, 0, "throw-ender-eye", true, actionContext);
        var feet = ai.getPosition();
        var world = player.getWorld();
        int offered = 0;
        if (sight != null) {
            for (var ray : sight.rays()) {
                if (offered >= 6 || ai.getWorkBudget().isExhausted())
                    return;
                var block = ray.block();
                if (block == null || !block.material().equals("END_PORTAL_FRAME"))
                    continue;
                var frame = new BlockPoint(block.x(), block.y(), block.z());
                if (add(ai, found, "socket-ender-eye:" + frame.encode(),
                        new PrimitiveCommand.SocketEnderEye(ai.getWorldId(), frame), null, null,
                        frame.x() - feet.x(), frame.y() - feet.y(), frame.z() - feet.z(), "socket-ender-eye", true,
                        actionContext)) {
                    offered++;
                }
            }
        }
        var nearby = nearbyTargets(feet);
        for (int checked = 0; checked < 24 && offered < 6 && !ai.getWorkBudget().isExhausted(); checked++) {
            var frame = nearby.get(Math.floorMod(eyeFrameCursor++, nearby.size()));
            if (frame.y() < world.getMinHeight() || frame.y() >= world.getMaxHeight()
                    || !Positions.isLoaded(world, frame))
                continue;
            if (Positions.block(world, frame).getType() != Material.END_PORTAL_FRAME)
                continue;
            if (add(ai, found, "socket-ender-eye:" + frame.encode(),
                    new PrimitiveCommand.SocketEnderEye(ai.getWorldId(), frame), null, null,
                    frame.x() - feet.x(), frame.y() - feet.y(), frame.z() - feet.z(), "socket-ender-eye", true,
                    actionContext)) {
                offered++;
            }
        }
    }

    private void eyeBearingMoves(AIPlayer ai, Map<String, Candidate> found, EnderEyeTrack.Observation eye,
            CandidateActionContext.Context actionContext) {
        double length = Math.hypot(eye.dx(), eye.dz());
        if (length < 2)
            return; // Require observed flight beyond the immediate release.
        var feet = ai.getPosition();
        var view = ai.getPlayer().getEyeLocation();
        var opacity = PerceptionSystem.opacityOf(view.getWorld());
        for (int stride : new int[] { 4, 8, 12 }) {
            int dx = (int) Math.round(stride * eye.dx() / length);
            int dz = (int) Math.round(stride * eye.dz() / length);
            for (int dy : new int[] { 0, 1, -1 }) {
                if (ai.getWorkBudget().isExhausted())
                    return;
                var target = feet.offset(dx, dy, dz);
                if (!Visibility.canSeePoint(opacity, view.getX(), view.getY(), view.getZ(),
                        target.x() + 0.5, target.y() + 0.25, target.z() + 0.5))
                    continue;
                add(ai, found, "move-eye:" + target.encode(), new PrimitiveCommand.MoveTo(target, 0.5),
                        null, null, dx, dy, dz, "thrown-eye", true, actionContext);
            }
        }
    }

    private static PolicyFrame.Option option(AIPlayer ai, Candidate candidate, BlockPoint feet,
            EnderEyeTrack.Observation eye,
            CandidateActionContext.Context context, TableAccess tableAccess,
            boolean tableReachable, boolean furnaceReachable) {
        double[] features = candidate.encoded().features();
        var block = candidate.encoded().blockEvidence();
        String material = candidate.recipe() != null ? candidate.recipe().result()
                : block != null ? block.material()
                        : candidate.command() instanceof PrimitiveCommand.PickupExact pickup ? pickup.material()
                                : candidate.command() instanceof PrimitiveCommand.FurnaceTransfer transfer
                                        && transfer.slot() == PrimitiveCommand.FurnaceSlot.RESULT ? transfer.material()
                                                : null;
        if (material != null && context.indirectIngredients().contains(material))
            features[CandidateActionContext.OBJECTIVE_INDIRECT_INGREDIENT_MATCH] = 1;
        boolean tableMissing = context.requiresCraftingTable() && tableAccess.knownMissing();
        if (context.requiresCraftingTable()) {
            String table = Material.CRAFTING_TABLE.name();
            boolean acquiresTable = table.equals(material) && context.stock().getOrDefault(table, 0) == 0;
            boolean placesTable = candidate.command() instanceof PrimitiveCommand.PlaceBlock place
                    && table.equals(place.material());
            if ((acquiresTable || placesTable) && tableMissing) {
                if (acquiresTable)
                    features[CandidateActionContext.ACQUIRES_REQUIRED_CRAFTING_TABLE] = 1;
                if (placesTable)
                    features[CandidateActionContext.PLACES_REQUIRED_CRAFTING_TABLE] = 1;
            }
        }
        int pickupAmount = candidate.command() instanceof PrimitiveCommand.PickupExact
                ? Math.max(1, (int) Math.round(features[27] * 64.0))
                : 0;
        String smeltingOutput = null;
        if (candidate.command() instanceof PrimitiveCommand.FurnaceTransfer transfer
                && transfer.slot() == PrimitiveCommand.FurnaceSlot.INGREDIENT) {
            Material input = Material.getMaterial(transfer.material());
            Material output = input == null ? null : ai.getCrafting().smeltingResult(input);
            if (output != null)
                smeltingOutput = output.name();
        }
        var delta = CandidateActionContext.materialDelta(candidate.type(), candidate.recipe(), candidate.command(),
                candidate.consumable(), pickupAmount, context.storageStock(), context.storageSlots());
        CandidateActionContext.encodeMaterialImpact(features, delta, context, tableMissing, smeltingOutput);
        String workstationMaterial = candidate.recipe() != null ? candidate.recipe().result()
                : candidate.command() instanceof PrimitiveCommand.PlaceBlock place ? place.material()
                        : block != null ? block.material() : null;
        boolean ownsTable = context.stock().getOrDefault(Material.CRAFTING_TABLE.name(), 0) > 0;
        boolean ownsFurnace = context.stock().getOrDefault(Material.FURNACE.name(), 0) > 0;
        CandidateActionContext.encodeWorkstationContext(features, candidate.type(), workstationMaterial,
                tableReachable, ownsTable, furnaceReachable, ownsFurnace);
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
        if (ai.getWorkBudget().isExhausted())
            return false;
        for (var input : MovementInput.choices(ai.getWorldId(), ai.getPlayer().getLocation().getYaw(),
                movementInputCursor, MOVEMENT_INPUT_CHOICES_PER_FRAME)) {
            if (ai.getWorkBudget().isExhausted())
                break;
            double[] features = new double[PolicyFrame.ACTION_SIZE];
            features[PrimitiveType.MOVE_INPUT.ordinal()] = 1;
            var direction = input.heading();
            features[24] = direction.x();
            features[25] = direction.y();
            features[26] = direction.z();
            features[27] = input.forward();
            features[28] = input.jump() ? 1 : 0;
            features[29] = input.sneak() ? 1 : 0;
            features[56] = input.strafe();
            features[57] = input.sprint() ? 1 : 0;
            features[63] = 1;
            String id = input.identity();
            found.putIfAbsent(id,
                    new Candidate(id, null, null, null, new PolicyFrame.Option(id, features), 0, id, null, input));
        }
        movementInputCursor = (movementInputCursor + 1) % MovementInput.CHOICES;
        return false; // A rotating subset never claims that unoffered key inputs do not exist.
    }

    static List<Integer> equipmentSourceSlots(int cursor, int held, int[] amounts, int limit) {
        if (amounts.length != 41 || limit < 0)
            throw new IllegalArgumentException("invalid equipment slot scan");
        List<Integer> selected = new ArrayList<>(limit);
        boolean emptyHotbar = false;
        boolean emptyStorage = false;
        for (int step = 0; step < 41 && selected.size() < limit; step++) {
            int slot = Math.floorMod(cursor + step, 41);
            if (amounts[slot] > 0) {
                selected.add(slot);
                continue;
            }
            if (slot >= 36)
                continue;
            if (slot < 9) {
                if (slot == held || emptyHotbar)
                    continue;
                emptyHotbar = true;
            } else {
                if (emptyStorage)
                    continue;
                emptyStorage = true;
            }
            selected.add(slot);
        }
        return List.copyOf(selected);
    }

    static final int MAX_EQUIPMENT_CANDIDATES = 2;

    static int equipmentPairIndex(int cursor, int step, int pairs) {
        if (pairs <= 0 || step < 0)
            throw new IllegalArgumentException("invalid equipment pair index");
        return Math.floorMod(cursor + step, pairs);
    }

    static int nextCoprimeCursor(int cursor, int attempted, int pairs) {
        if (pairs <= 1)
            return 0;
        int stride = Math.max(1, attempted);
        while (gcd(stride, pairs) != 1)
            stride++;
        return Math.floorMod(cursor + stride, pairs);
    }

    static int nextEquipmentCursor(int cursor, int attempted, int pairs) {
        return nextCoprimeCursor(cursor, attempted, pairs);
    }

    private static int gcd(int a, int b) {
        int x = Math.abs(a);
        int y = Math.abs(b);
        while (y != 0) {
            int r = x % y;
            x = y;
            y = r;
        }
        return x;
    }

    private void equipment(AIPlayer ai, Map<String, Candidate> found, CandidateActionContext.Context actionContext) {
        var player = ai.getPlayer();
        int held = player.getInventory().getHeldItemSlot();
        Map<Integer, ExactEquipment.Stack> targets = new LinkedHashMap<>();
        for (int slot : new int[] { held, 40, 39, 38, 37, 36 }) {
            if (ai.getWorkBudget().isExhausted())
                return;
            targets.put(slot, ExactEquipment.read(player, slot));
        }
        int[] amounts = new int[41];
        for (int slot = 0; slot < 41; slot++) {
            if (targets.containsKey(slot)) {
                amounts[slot] = targets.get(slot).amount();
            } else {
                var item = player.getInventory().getItem(slot);
                amounts[slot] = item == null || item.isEmpty() ? 0 : item.getAmount();
            }
        }
        List<Integer> sources = equipmentSourceSlots(0, held, amounts, 41);
        List<Integer> targetSlots = List.copyOf(targets.keySet());
        int pairs = sources.size() * targetSlots.size();
        if (pairs <= 0) {
            equipmentCursor = 0;
            return;
        }
        int offered = 0;
        int attempted = 0;
        int attemptLimit = Math.min(pairs, 24);
        while (attempted < attemptLimit && offered < MAX_EQUIPMENT_CANDIDATES
                && !ai.getWorkBudget().isExhausted() && found.size() < 512) {
            int pairIndex = equipmentPairIndex(equipmentCursor, attempted, pairs);
            attempted++;
            int source = sources.get(pairIndex / targetSlots.size());
            int dest = targetSlots.get(pairIndex % targetSlots.size());
            if (source == dest)
                continue;
            var from = targets.containsKey(source) ? targets.get(source) : ExactEquipment.read(player, source);
            var targetStack = targets.get(dest);
            var choice = new ExactEquipment.Choice(ai.getWorldId(), source, dest, held, from, targetStack);
            if (!ExactEquipment.offered(player, choice))
                continue;
            String id = "equip-exact:" + source + ":" + dest;
            double[] features = new double[PolicyFrame.ACTION_SIZE];
            features[PrimitiveType.EQUIP_ITEM.ordinal()] = 1;
            features[27] = Math.min(16, from.amount() / 64.0);
            features[28] = Math.min(16, targetStack.amount() / 64.0);
            features[29] = source / 40.0;
            features[56] = from.damageFraction();
            features[58] = targetStack.amount() == 0 ? 1 : 0;
            features[60] = from.amount() == 0 ? 1 : 0;
            features[59] = from.enchantments().getOrDefault("minecraft:binding_curse", 0) > 0 ? 1 : 0;
            features[61] = choice.selectHotbar() ? 1 : 0;
            features[62] = dest / 40.0;
            features[63] = 1;
            if (from.amount() > 0)
                features[32 + bucket(from.material(), 16)] = 1;
            if (targetStack.amount() > 0)
                features[64 + bucket(targetStack.material(), 16)] = 1;
            from.enchantments().forEach((key, level) -> {
                int index = 80 + bucket(key, 16);
                features[index] = Math.clamp(features[index] + level / 8.0, 0, 16);
            });
            String equipMaterial = from.amount() > 0 ? from.material() : null;
            CandidateActionContext.encode(features, PrimitiveType.EQUIP_ITEM,
                    equipMaterial, null, actionContext);
            var equipProps = resolveMaterialProperties(ai, equipMaterial);
            CandidateActionContext.encodeMaterialProperties(features, equipProps);
            int equipOwned = equipMaterial == null ? 0 : actionContext.stock().getOrDefault(equipMaterial, 0);
            CandidateActionContext.encodeCraftUtility(features, PrimitiveType.EQUIP_ITEM,
                    equipMaterial, equipProps, ownedHarvestCaps, equipOwned);
            if (found.putIfAbsent(id,
                    new Candidate(id, null, null, null, new PolicyFrame.Option(id, features), 0, id, choice)) == null)
                offered++;
        }
        equipmentCursor = nextEquipmentCursor(equipmentCursor, attempted, pairs);
    }

    private static List<BlockPoint> movementOffsets() {
        List<BlockPoint> offsets = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0)
                    continue;
                for (int stride : new int[] { 1, 2 })
                    for (int dy : new int[] { 0, 1, -1 })
                        offsets.add(new BlockPoint(dx * stride, dy, dz * stride));
            }
        return List.copyOf(offsets);
    }

    /**
     * Rotate presentation, not action preference; even a truncated candidate budget
     * sees every direction over time.
     */
    static List<BlockPoint> movementOffsets(int cursor) {
        List<BlockPoint> result = new ArrayList<>(MOVE_OFFSETS.size());
        for (int i = 0; i < MOVE_OFFSETS.size(); i++)
            result.add(MOVE_OFFSETS.get(Math.floorMod(cursor + i, MOVE_OFFSETS.size())));
        return List.copyOf(result);
    }

    private static List<BlockPoint> placementOffsets() {
        List<BlockPoint> cardinal = List.of(
                new BlockPoint(1, 0, 0), new BlockPoint(-1, 0, 0),
                new BlockPoint(0, 0, 1), new BlockPoint(0, 0, -1));
        List<BlockPoint> rest = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = -1; dy <= 3; dy++) {
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == 1))
                        continue;
                    var offset = new BlockPoint(dx, dy, dz);
                    if (cardinal.contains(offset))
                        continue;
                    double distSq = dx * dx + (dy - 1.1) * (dy - 1.1) + dz * dz;
                    if (distSq > 4.6 * 4.6)
                        continue;
                    rest.add(offset);
                }
        rest.sort(Comparator.comparingInt(p -> p.x() * p.x() + (p.y() - 1) * (p.y() - 1) + p.z() * p.z()));
        List<BlockPoint> all = new ArrayList<>(cardinal.size() + rest.size());
        all.addAll(cardinal);
        all.addAll(rest);
        return List.copyOf(all);
    }

    /**
     * Pairs one rotating cardinal feet-level cell with rotating 3D cells so neither
     * starves the other in selectFair.
     */
    static List<BlockPoint> placementOffsets(int cursor, int limit) {
        List<BlockPoint> result = new ArrayList<>(limit);
        int fixed = Math.min(4, PLACE_OFFSETS.size());
        if (fixed > 0 && result.size() < limit) {
            result.add(PLACE_OFFSETS.get(Math.floorMod(cursor, fixed)));
        }
        int pool = PLACE_OFFSETS.size() - fixed;
        for (int i = 0; i < pool && result.size() < limit; i++) {
            result.add(PLACE_OFFSETS.get(fixed + Math.floorMod(cursor + i, pool)));
        }
        return List.copyOf(result);
    }

    static List<BlockPoint> nearbyTargets(BlockPoint feet) {
        var points = new ArrayList<BlockPoint>();
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = -1; dy <= 2; dy++)
                    points.add(feet.offset(dx, dy, dz));
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                points.add(feet.offset(dx, -2, dz));
        for (int[] shaft : new int[][] { { 0, 0 }, { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } })
            points.add(feet.offset(shaft[0], -3, shaft[1]));
        // Geometric proximity, never material desirability; includes dy=-1..-3 for
        // reachable downward/staircase mining.
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
        if (crosshair != null)
            targets.add(crosshair);
        // A visible block inside the local volume can still fall outside this tick's
        // rotating scan.
        var visibleTargets = sightTargets.stream().distinct().iterator();
        for (int offset = 0; offset < nearby.size() && targets.size() < 24; offset++) {
            targets.add(nearby.get(Math.floorMod(cursor + offset, nearby.size())));
            if (targets.size() < 24 && visibleTargets.hasNext())
                targets.add(visibleTargets.next());
        }
        return List.copyOf(targets);
    }

    private static List<BlockPoint> reachableSightTargets(PolicyFrame.SightObservation sight) {
        if (sight == null)
            return List.of();
        List<BlockPoint> result = new ArrayList<>();
        for (var ray : sight.rays()) {
            var block = ray.block();
            if (block == null)
                continue;
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
        while (advanced < nearby.size()
                && processed.contains(nearby.get(Math.floorMod(cursor + advanced, nearby.size()))))
            advanced++;
        return Math.floorMod(cursor + advanced, nearby.size());
    }

    static List<me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot> miningSlots(
            List<me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot> inventory) {
        var slots = new ArrayList<me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot>();
        me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot bareHand = null;
        for (var slot : inventory) {
            if (slot.slot() >= 36)
                continue;
            boolean durableOrEnchanted = slot.amount() > 0
                    && (slot.maxDamage() > 0 || !slot.enchantments().isEmpty());
            if (durableOrEnchanted) {
                slots.add(slot);
            } else if (bareHand == null || (bareHand.amount() > 0 && slot.amount() == 0)) {
                // Non-durable unenchanted items share identical mining speed and drops with an empty hand.
                bareHand = slot;
            }
        }
        if (bareHand != null) {
            int insert = 0;
            while (insert < slots.size() && slots.get(insert).slot() < bareHand.slot())
                insert++;
            slots.add(insert, bareHand);
        }
        return List.copyOf(slots);
    }

    private boolean add(AIPlayer ai, Map<String, Candidate> found, String id, PrimitiveCommand command,
            RecipeOption recipe,
            SurvivalObservation.Item consumable, double dx, double dy, double dz, String identity, boolean check,
            CandidateActionContext.Context actionContext) {
        if (found.size() >= 512 || found.containsKey(id))
            return false;
        if (check && (ai.getWorkBudget().isExhausted() || !PrimitiveAdapter.check(ai, command).allowed()))
            return false;
        double[] features = new double[PolicyFrame.ACTION_SIZE];
        PrimitiveType type = recipe != null ? PrimitiveType.CRAFT_ITEM
                : consumable != null ? PrimitiveType.USE_ITEM : command.type();
        features[type.ordinal()] = 1;
        if (id.startsWith("approach:"))
            features[284] = 1;
        double range = command instanceof PrimitiveCommand.ShootBow ? ShootBowAction.RANGE : 16;
        features[24] = Math.clamp(dx / range, -1, 1);
        features[25] = Math.clamp(dy / range, -1, 1);
        features[26] = Math.clamp(dz / range, -1, 1);
        features[32 + bucket(identity, 16)] = 1;
        String semantic = recipe != null ? recipe.result()
                : consumable != null ? consumable.consumption().remainderMaterial()
                        : command instanceof PrimitiveCommand.BreakBlock mine ? mine.tool().material()
                                : command instanceof PrimitiveCommand.FurnaceTransfer transfer ? transfer.material()
                                        : command instanceof PrimitiveCommand.IgnitePortal ? "FLINT_AND_STEEL"
                : command instanceof PrimitiveCommand.FillBucket fill ? ("LAVA".equals(fill.fluid()) ? "LAVA_BUCKET" : "WATER_BUCKET")
                : command instanceof PrimitiveCommand.PourBucket pour ? pour.bucket()
                                                : command instanceof PrimitiveCommand.EnterNetherPortal
                                                        ? "NETHER_PORTAL"
                                                        : command instanceof PrimitiveCommand.EnterEndPortal
                                                                ? "END_PORTAL"
                                                                : command instanceof PrimitiveCommand.ThrowEnderEye
                                                                        || command instanceof PrimitiveCommand.SocketEnderEye
                                                                                ? "ENDER_EYE"
                                                                                : command instanceof PrimitiveCommand.ShootBow
                                                                                        ? "BOW"
                                                                                        : command instanceof PrimitiveCommand.ShieldBlock
                                                                                                ? "SHIELD"
                                                                                                : command instanceof PrimitiveCommand.DropExact drop
                                                                                                        ? drop.material()
                                                                                                        : command instanceof PrimitiveCommand.LootChest
                                                                                                                ? "CHEST"
                                                                                                                : command instanceof PrimitiveCommand.UseBed
                                                                                                                        ? "BED"
                                                                                                                        : command instanceof PrimitiveCommand.ToggleBlock
                                                                                                                                ? identity
                                                                                                                                : command instanceof PrimitiveCommand.Equip equip
                                                                                                                ? equip.tool()
                                                                                                                        .material()
                                                                                                                : "";
        if (!semantic.isBlank())
            features[48 + bucket(semantic, 7)] = 1;
        if (consumable != null) {
            var use = consumable.consumption();
            features[27] = Math.min(16, consumable.amount() / 64.0);
            features[28] = use.useTicks() / 200.0;
            features[29] = consumable.slot() / 35.0;
            features[31] = use.nutrition() / 20.0;
            features[57] = Math.clamp(use.saturation() / 20.0, 0, 16);
            features[58] = use.clearsEffects() ? 1 : 0;
            features[59] = use.randomTeleport() ? 1 : 0;
            features[60] = use.canAlwaysEat() ? 1 : 0;
            features[61] = use.removedEffects().isEmpty() ? 0 : 1;
            features[63] = 1;
            EffectEncoding.encode(features, use.effects());
        }
        PolicyFrame.BlockEvidence blockEvidence = null;
        String breakDropMaterial = null;
        if (command instanceof PrimitiveCommand.BreakBlock mine) {
            var observedBlock = Positions.block(ai.getPlayer().getWorld(), mine.target());
            blockEvidence = new PolicyFrame.BlockEvidence(observedBlock.getType().name(), ai.getWorldId(),
                    mine.target().x(), mine.target().y(), mine.target().z(),
                    Integer.toUnsignedLong(Bukkit.getCurrentTick()));
            var tool = ai.getPlayer().getInventory().getItem(mine.tool().slot());
            if (tool != null && !tool.isEmpty()) {
                Integer damage = tool.getData(io.papermc.paper.datacomponent.DataComponentTypes.DAMAGE);
                Integer maximum = tool.getData(io.papermc.paper.datacomponent.DataComponentTypes.MAX_DAMAGE);
                features[56] = maximum == null || maximum <= 0 ? 0
                        : Math.clamp((damage == null ? 0 : damage) / (double) maximum, 0, 1);
            }
            var actualTool = tool == null ? org.bukkit.inventory.ItemStack.empty() : tool;
            features[57] = Math.clamp(observedBlock.getBlockData().getDestroySpeed(actualTool, true) / 32.0, 0, 16);
            boolean preferredTool = observedBlock.isPreferredTool(actualTool);
            boolean requiresCorrectTool = !observedBlock.isPreferredTool(org.bukkit.inventory.ItemStack.empty());
            CandidateActionContext.encodeMiningPhysics(features, preferredTool, requiresCorrectTool);
            boolean silkTouch = !actualTool.isEmpty()
                    && actualTool.containsEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH);
            boolean shears = !actualTool.isEmpty() && actualTool.getType() == Material.SHEARS;
            breakDropMaterial = CandidateActionContext.observedDropMaterial(
                    blockEvidence.material(), preferredTool, silkTouch, shears);
        }
        if (recipe != null)
            features[27] = recipe.resultAmount() / 64.0;
        if (command instanceof PrimitiveCommand.PickupExact pickup
                && Bukkit.getEntity(pickup.entityId()) instanceof org.bukkit.entity.Item drop) {
            features[27] = Math.min(16, drop.getItemStack().getAmount() / 64.0);
            features[285] = 1;
        }
        if (command instanceof PrimitiveCommand.FurnaceTransfer transfer)
            features[29] = transfer.inventorySlot() / 35.0;
        if (command instanceof PrimitiveCommand.DropExact drop) {
            var item = ai.getPlayer().getInventory().getItem(drop.slot());
            if (item != null && !item.isEmpty())
                features[27] = Math.min(16, item.getAmount() / 64.0);
            features[29] = drop.slot() / 35.0;
        }
        if (command instanceof PrimitiveCommand.ShieldBlock shield)
            features[56] = shield.offHand() ? 1 : 0;
        String directMaterial = recipe != null ? recipe.result()
                : blockEvidence != null ? breakDropMaterial
                        : command instanceof PrimitiveCommand.PlaceBlock place ? place.material()
                                : command instanceof PrimitiveCommand.FillBucket fill
                                        ? ("LAVA".equals(fill.fluid()) ? "LAVA_BUCKET" : "WATER_BUCKET")
                                        : command instanceof PrimitiveCommand.PourBucket pour ? pour.bucket()
                                                : command instanceof PrimitiveCommand.DropExact drop ? drop.material()
                                                        : command instanceof PrimitiveCommand.ShieldBlock ? "SHIELD"
                                                                : command instanceof PrimitiveCommand.FurnaceTransfer transfer
                                                                        ? transfer.material()
                                                                        : command instanceof PrimitiveCommand.PickupExact pickup
                                                                                ? pickup.material()
                                                                                : consumable != null
                                                                                        ? consumable.material()
                                                                                        : approachMaterial(id, identity);
        boolean takesObservedItem = command instanceof PrimitiveCommand.PickupExact
                || command instanceof PrimitiveCommand.FillBucket
                || command instanceof PrimitiveCommand.FurnaceTransfer transfer
                        && transfer.slot() == PrimitiveCommand.FurnaceSlot.RESULT;
        CandidateActionContext.encode(features, type, directMaterial, recipe, actionContext, takesObservedItem);
        var materialProps = resolveMaterialProperties(ai, directMaterial);
        CandidateActionContext.encodeMaterialProperties(features, materialProps);
        int ownedCount = directMaterial == null ? 0 : actionContext.stock().getOrDefault(directMaterial, 0);
        CandidateActionContext.encodeCraftUtility(features, type, directMaterial, materialProps, ownedHarvestCaps, ownedCount);
        found.put(id,
                new Candidate(id, command, recipe, consumable, new PolicyFrame.Option(id, features, blockEvidence)));
        return true;
    }

    static List<Candidate> selectFair(List<Candidate> all, String activeId) {
        List<Candidate> selected = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Candidate candidate : all)
            if (candidate.id().equals(activeId) && ids.add(candidate.id()))
                selected.add(candidate);
        Map<PrimitiveType, ArrayDeque<Candidate>> groups = new EnumMap<>(PrimitiveType.class);
        for (Candidate candidate : all) {
            PrimitiveType type = candidate.type();
            groups.computeIfAbsent(type, ignored -> new ArrayDeque<>()).add(candidate);
        }
        boolean remaining = true;
        while (selected.size() < PolicyFrame.MAX_OPTIONS && remaining) {
            remaining = false;
            for (var group : groups.values()) {
                if (group.isEmpty())
                    continue;
                remaining = true;
                Candidate candidate = group.removeFirst();
                if (ids.add(candidate.id()))
                    selected.add(candidate);
                if (selected.size() == PolicyFrame.MAX_OPTIONS)
                    break;
            }
        }
        return List.copyOf(selected);
    }

    private CandidateActionContext.HarvestCapabilities computeOwnedHarvestCapabilities(
            AIPlayer ai, Map<String, Integer> stock) {
        boolean stone = false, iron = false, diamond = false, obsidian = false;
        for (var entry : stock.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            var props = resolveMaterialProperties(ai, entry.getKey());
            stone |= props.harvestsStone();
            iron |= props.harvestsIronOre();
            diamond |= props.harvestsDiamondOre();
            obsidian |= props.harvestsObsidian();
        }
        return new CandidateActionContext.HarvestCapabilities(stone, iron, diamond, obsidian);
    }

    private CandidateActionContext.MaterialProperties resolveMaterialProperties(AIPlayer ai, String directMaterial) {
        if (directMaterial == null || directMaterial.isBlank() || Bukkit.getServer() == null) {
            return CandidateActionContext.MaterialProperties.EMPTY;
        }
        long revision = ai.getCrafting().revision();
        if (materialPropertiesRevision != revision) {
            materialPropertiesCache.clear();
            materialPropertiesRevision = revision;
        }
        Material mat = Material.getMaterial(directMaterial);
        if (mat == null || mat.isAir()) {
            return CandidateActionContext.MaterialProperties.EMPTY;
        }
        return materialPropertiesCache.computeIfAbsent(mat, m -> {
            if (!m.isItem()) {
                return new CandidateActionContext.MaterialProperties(
                        0, false, false, false, 0.0, false, false, m.isBlock(), false, false);
            }
            var sample = new org.bukkit.inventory.ItemStack(m);
            var nms = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(sample);
            int dep = ai.getCrafting().dependentRecipeCount(m);
            boolean durability = nms.has(net.minecraft.core.component.DataComponents.MAX_DAMAGE);
            var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
            var ironOre = net.minecraft.world.level.block.Blocks.IRON_ORE.defaultBlockState();
            var diamondOre = net.minecraft.world.level.block.Blocks.DIAMOND_ORE.defaultBlockState();
            var obsidian = net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState();
            boolean harvestsStone = nms.isCorrectToolForDrops(stone);
            boolean harvestsIron = nms.isCorrectToolForDrops(ironOre);
            double stoneSpeed = nms.getDestroySpeed(stone);
            boolean harvestsDiamond = nms.isCorrectToolForDrops(diamondOre);
            boolean harvestsObsidian = nms.isCorrectToolForDrops(obsidian);
            boolean food = nms.has(net.minecraft.core.component.DataComponents.FOOD);
            boolean fuel = nms.has(net.minecraft.core.component.DataComponents.COOKING_FUEL);
            return new CandidateActionContext.MaterialProperties(
                    dep, durability, harvestsStone, harvestsIron, stoneSpeed, harvestsDiamond, harvestsObsidian,
                    m.isBlock(), food, fuel);
        });
    }

    static String approachMaterial(String id, String identity) {
        if (id != null && id.startsWith("approach:") && !id.startsWith("approach:clear:")
                && identity != null && !identity.isBlank() && !"HORIZON".equals(identity)) {
            return CandidateActionContext.observedDropMaterial(identity, true, false, false);
        }
        return null;
    }

    private static int bucket(String text, int size) {
        return Math.floorMod(text.hashCode(), size);
    }

    /**
     * Keep execution identity and observation age; finite movement/equipment retain
     * their existing bound.
     */
    static List<Candidate> compatibleReconsiderations(Candidate active) {
        if (active.movement() != null || active.equipment() != null
                || active.recipe() != null
                || active.command() instanceof PrimitiveCommand.ShootBow
                || active.command() instanceof PrimitiveCommand.ShieldBlock
                || active.command() instanceof PrimitiveCommand.DropExact
                || active.command() instanceof PrimitiveCommand.LootChest
                || active.command() instanceof PrimitiveCommand.UseBed
                || active.command() instanceof PrimitiveCommand.ToggleBlock
                || active.command() instanceof PrimitiveCommand.FurnaceTransfer
                || active.command() instanceof PrimitiveCommand.IgnitePortal
                || active.command() instanceof PrimitiveCommand.FillBucket
                || active.command() instanceof PrimitiveCommand.PourBucket
                || active.command() instanceof PrimitiveCommand.ThrowEnderEye
                || active.command() instanceof PrimitiveCommand.SocketEnderEye
                || active.command() instanceof PrimitiveCommand.LookAt
                || active.command() instanceof PrimitiveCommand.Jump
                || active.command() instanceof PrimitiveCommand.PlaceBlock)
            return List.of();
        List<Candidate> result = new ArrayList<>();
        for (int ticks : reconsiderationTicks(active)) {
            var variant = active.committed(ticks);
            if (variant.id().equals(active.id()))
                continue;
            double[] features = variant.encoded().features();
            features[55] = 0;
            result.add(new Candidate(variant.id(), variant.command(), variant.recipe(), variant.consumable(),
                    new PolicyFrame.Option(variant.id(), features, variant.encoded().blockEvidence()),
                    variant.commitmentTicks(),
                    variant.inputId(), variant.equipment(), variant.movement()));
        }
        return List.copyOf(result);
    }

    private static List<Integer> reconsiderationTicks(Candidate candidate) {
        if (candidate.movement() != null)
            return INPUT_RECONSIDERATION_TICKS;
        if (candidate.command() instanceof PrimitiveCommand.BreakBlock)
            return MINING_RECONSIDERATION_TICKS;
        if (candidate.command() instanceof PrimitiveCommand.MoveTo
                || candidate.command() instanceof PrimitiveCommand.Pickup
                || candidate.command() instanceof PrimitiveCommand.PickupExact)
            return MOVE_RECONSIDERATION_TICKS;
        return RECONSIDERATION_TICKS;
    }
}
