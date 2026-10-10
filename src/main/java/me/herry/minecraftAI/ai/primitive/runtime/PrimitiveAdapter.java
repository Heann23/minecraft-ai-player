package me.herry.minecraftAI.ai.primitive.runtime;

import io.papermc.paper.datacomponent.DataComponentTypes;
import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.AIState;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.AttackEntityAction;
import me.herry.minecraftAI.ai.action.BreakBlockAction;
import me.herry.minecraftAI.ai.action.CraftItemAction;
import me.herry.minecraftAI.ai.action.DropJunkAction;
import me.herry.minecraftAI.ai.action.EatFoodAction;
import me.herry.minecraftAI.ai.action.ExactPlaceBlockAction;
import me.herry.minecraftAI.ai.action.JumpAction;
import me.herry.minecraftAI.ai.action.LookAtAction;
import me.herry.minecraftAI.ai.action.MoveToAction;
import me.herry.minecraftAI.ai.action.PickupItemAction;
import me.herry.minecraftAI.ai.action.SelectHotbarSlotAction;
import me.herry.minecraftAI.ai.action.SlotEquipAction;
import me.herry.minecraftAI.ai.action.SneakAction;
import me.herry.minecraftAI.ai.action.SwimAction;
import me.herry.minecraftAI.ai.action.WaitAction;
import me.herry.minecraftAI.ai.inventory.JunkPolicy;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.navigation.BlockClass;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveCheck;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot;
import me.herry.minecraftAI.ai.primitive.control.ToolChoice;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.ai.world.Base;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Resolves value-only policy commands on the server thread, using the normal survival actions. */
public final class PrimitiveAdapter {
    public static final int MAX_CANDIDATES = 32;
    private static final double LOCAL_RANGE = 32.0;
    private static final double REACH = 4.9;
    private static final BlockFace[] FACES = {
            BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };
    private static final EnumSet<PrimitiveType> SUPPORTED = EnumSet.complementOf(
            EnumSet.of(PrimitiveType.INTERACT_BLOCK, PrimitiveType.INTERACT_ENTITY));

    private PrimitiveAdapter() {
    }

    /** A type can be implemented without being available at the current position. */
    public record Capability(PrimitiveType type, boolean supported, String effect, String limitation) {
    }

    public static List<Capability> capabilities() {
        List<Capability> result = new ArrayList<>();
        for (PrimitiveType type : PrimitiveType.values()) {
            String effect = switch (type) {
                case MOVE_TO -> "Reach the selected local block using survival navigation";
                case LOOK_AT -> "Face the selected point";
                case JUMP -> "Press jump once and land";
                case BREAK_BLOCK -> "Mine exactly one visible block with the selected slot and enchantments";
                case PLACE_BLOCK -> "Place one selected solid block at the selected cell";
                case USE_ITEM -> "Consume one safe food item using the normal eating action";
                case ATTACK -> "Fight the selected living entity without switching to another target";
                case PICKUP_ITEM -> "Walk to nearby drops using the existing pickup action";
                case DROP_ITEM -> "Discard stacks selected by the existing junk policy";
                case EQUIP_ITEM -> "Equip the exact storage slot whose item fingerprint still matches";
                case SELECT_SLOT -> "Select one hotbar slot";
                case CRAFT_ITEM -> "Perform registered recipes using owned materials and a reachable table when required";
                case WAIT -> "Wait for a bounded number of ticks";
                case SNEAK -> "Hold sneak for a bounded number of ticks and release it";
                case SWIM -> "Use swimming inputs toward a local point in water";
                case INTERACT_BLOCK, INTERACT_ENTITY -> "Unavailable in control schema version 1";
            };
            String limitation = switch (type) {
                case USE_ITEM -> "Food only; bucket, bow and other item uses need explicit contracts";
                case ATTACK -> "Existing combat action chooses a weapon and may chase; this is not a single-hit input";
                case PICKUP_ITEM -> "No implicit obstruction mining; a blocked drop fails and requires another chosen command";
                case DROP_ITEM -> "Junk policy selection, not arbitrary item disposal";
                case CRAFT_ITEM -> "Recipe action may craft intermediate ingredients; not individual grid clicks";
                case BREAK_BLOCK -> "Reach, visibility, correct drop tool, floor, home, lava and falling-block guards apply";
                case PLACE_BLOCK -> "Solid single-cell non-gravity blocks only";
                case INTERACT_BLOCK, INTERACT_ENTITY -> "Unsupported commands are rejected, never replaced with a guessed action";
                default -> "Availability must be checked for the concrete command before execution";
            };
            result.add(new Capability(type, SUPPORTED.contains(type), effect, limitation));
        }
        return List.copyOf(result);
    }

    public static PrimitiveSnapshot capture(AIPlayer ai) {
        requireMainThread();
        Objects.requireNonNull(ai, "ai");
        Player player = ai.getPlayer();
        Location position = player.getLocation();
        List<PrimitiveSnapshot.ItemSlot> inventory = new ArrayList<>(36);
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.isEmpty()) {
                inventory.add(new PrimitiveSnapshot.ItemSlot(slot, Material.AIR.name(), 0, 0, 0, Map.of()));
                continue;
            }
            Integer damage = item.getData(DataComponentTypes.DAMAGE);
            Integer maxDamage = item.getData(DataComponentTypes.MAX_DAMAGE);
            inventory.add(new PrimitiveSnapshot.ItemSlot(slot, item.getType().name(), item.getAmount(),
                    damage == null ? 0 : damage, maxDamage == null ? 0 : maxDamage, enchantments(item)));
        }
        return new PrimitiveSnapshot(PrimitiveSnapshot.CURRENT_SCHEMA_VERSION, ai.getTicks(), player.getWorld().getUID(),
                new PrimitiveTarget.Point(position.getX(), position.getY(), position.getZ()), player.getHealth(),
                player.getFoodLevel(), player.getRemainingAir(), ai.getBody().isUsable() && !player.isDead(),
                player.isInWater(), player.isSneaking(), inventory);
    }

    /** Reuses discovered landmarks and cached entity lists; it does not start another surrounding-block scan. */
    public static PrimitiveContext context(AIPlayer ai) {
        requireMainThread();
        PrimitiveSnapshot state = capture(ai);
        Map<BlockPoint, String> landmarks = new LinkedHashMap<>();
        boolean complete = true;
        outer:
        for (MemoryType type : MemoryType.values()) {
            for (var entry : ai.getMemory().all(type, state.worldId(), state.tick())) {
                if (ai.getWorkBudget().isExhausted()) {
                    complete = false;
                    break outer;
                }
                if (!checkBlock(ai, entry.pos(), LOCAL_RANGE).isEmpty()) continue;
                if (landmarks.size() == MAX_CANDIDATES && !landmarks.containsKey(entry.pos())) {
                    complete = false;
                    break outer;
                }
                landmarks.putIfAbsent(entry.pos(), type.name());
            }
        }
        List<PrimitiveContext.BlockView> blocks = new ArrayList<>();
        for (var landmark : landmarks.entrySet()) {
            if (ai.getWorkBudget().isExhausted()) {
                complete = false;
                break;
            }
            blocks.add(inspectBlock(ai, landmark.getKey(), "MEMORY:" + landmark.getValue()));
        }
        var perception = ai.getPerception();
        Map<java.util.UUID, Entity> seen = new LinkedHashMap<>();
        List<List<? extends Entity>> cached = List.of(perception.getDrops(), perception.getHostiles(),
                perception.getAnimals(), perception.getPlayers());
        List<PrimitiveContext.EntityView> entities = new ArrayList<>();
        outer:
        for (List<? extends Entity> list : cached) {
            for (Entity entity : list) {
                if (ai.getWorkBudget().isExhausted() || entities.size() == MAX_CANDIDATES) {
                    complete = false;
                    break outer;
                }
                if (!entity.isValid() || entity.isDead() || !entity.getWorld().getUID().equals(state.worldId())) continue;
                if (seen.putIfAbsent(entity.getUniqueId(), entity) != null) continue;
                Location position = entity.getLocation();
                if (position.distanceSquared(ai.getPlayer().getLocation()) > LOCAL_RANGE * LOCAL_RANGE
                        || !PerceptionSystem.canSee(ai.getPlayer(), entity)) continue;
                double health = entity instanceof LivingEntity living ? living.getHealth() : 0.0;
                String itemMaterial = entity instanceof Item item ? item.getItemStack().getType().name() : "AIR";
                int amount = entity instanceof Item item ? item.getItemStack().getAmount() : 0;
                entities.add(new PrimitiveContext.EntityView(entity.getUniqueId(), entity.getType().name(),
                        new PrimitiveTarget.Point(position.getX(), position.getY(), position.getZ()), health, itemMaterial, amount, true));
            }
        }
        return new PrimitiveContext(state, blocks, entities, complete);
    }

    /** Inspect at most 32 requested local cells. Hidden cells never expose their current material. */
    public static PrimitiveContext inspect(AIPlayer ai, List<BlockPoint> points) {
        requireMainThread();
        Objects.requireNonNull(points, "points");
        if (points.size() > MAX_CANDIDATES) throw new IllegalArgumentException("at most " + MAX_CANDIDATES + " targets");
        PrimitiveSnapshot state = capture(ai);
        List<PrimitiveContext.BlockView> blocks = new ArrayList<>(points.size());
        boolean complete = true;
        for (BlockPoint point : points) {
            Objects.requireNonNull(point, "point");
            if (ai.getWorkBudget().isExhausted()) {
                complete = false;
                break;
            }
            blocks.add(inspectBlock(ai, point, "REQUESTED"));
        }
        return new PrimitiveContext(state, blocks, List.of(), complete);
    }

    private static PrimitiveContext.BlockView inspectBlock(AIPlayer ai, BlockPoint point, String source) {
        World world = ai.getPlayer().getWorld();
        if (!checkBlock(ai, point, LOCAL_RANGE).isEmpty()) {
            return new PrimitiveContext.BlockView(point, "UNKNOWN", false, false, source);
        }
        boolean visible = visibleBlock(ai.getPlayer(), point);
        String material = visible ? Positions.block(world, point).getType().name() : "UNKNOWN";
        return new PrimitiveContext.BlockView(point, material, true, visible, source);
    }

    /** The same check is used for a candidate mask and immediately before starting a step. */
    public static PrimitiveCheck check(AIPlayer ai, PrimitiveCommand command) {
        requireMainThread();
        Objects.requireNonNull(ai, "ai");
        if (command == null) return PrimitiveCheck.reject("missing command");
        if (ai.getState() != AIState.RUNNING || !ai.getBody().isUsable() || ai.getPlayer().isDead()) {
            return PrimitiveCheck.reject("AI is not running and alive");
        }
        String reason = switch (command) {
            case PrimitiveCommand.MoveTo move -> checkMove(ai, move);
            case PrimitiveCommand.LookAt look -> checkPoint(ai, look.target(), LOCAL_RANGE);
            case PrimitiveCommand.Jump ignored -> ai.getBody().isGrounded() ? "" : "not grounded";
            case PrimitiveCommand.BreakBlock mine -> checkMining(ai, mine);
            case PrimitiveCommand.PlaceBlock place -> checkPlacement(ai, place);
            case PrimitiveCommand.Eat ignored -> ai.getPlayer().getFoodLevel() >= 20 ? "not hungry"
                    : ai.getInventory().bestFoodSlot() < 0 ? "no safe food" : "";
            case PrimitiveCommand.Attack attack -> checkAttack(ai, attack);
            case PrimitiveCommand.Pickup pickup -> checkPickup(ai, pickup.radius());
            case PrimitiveCommand.Equip equip -> checkTool(ai, equip.tool());
            case PrimitiveCommand.SelectSlot select -> select.slot() < 0 || select.slot() > 8 ? "hotbar slot must be 0..8" : "";
            case PrimitiveCommand.Craft craft -> checkCraft(ai, craft);
            case PrimitiveCommand.Wait wait -> checkTicks(wait.ticks(), 200);
            case PrimitiveCommand.Sneak sneak -> checkTicks(sneak.ticks(), 200);
            case PrimitiveCommand.Swim swim -> checkSwimming(ai, swim);
            case PrimitiveCommand.DropJunk ignored -> JunkPolicy.junkSlots(ai.getPlayer().getInventory()).isEmpty() ? "no junk to discard" : "";
        };
        return reason.isEmpty() ? PrimitiveCheck.allow() : PrimitiveCheck.reject(reason);
    }

    public static List<PrimitiveCheck> checkCandidates(AIPlayer ai, List<? extends PrimitiveCommand> commands) {
        requireMainThread();
        Objects.requireNonNull(commands, "commands");
        if (commands.size() > MAX_CANDIDATES) throw new IllegalArgumentException("at most " + MAX_CANDIDATES + " candidates");
        List<PrimitiveCheck> result = new ArrayList<>(commands.size());
        for (PrimitiveCommand command : commands) result.add(check(ai, command));
        return List.copyOf(result);
    }

    public static Action create(AIPlayer ai, PrimitiveCommand command) {
        PrimitiveCheck checked = check(ai, command);
        if (!checked.allowed()) throw new IllegalArgumentException(checked.reason());
        return switch (command) {
            case PrimitiveCommand.MoveTo move -> new MoveToAction(PathGoal.arrive(move.target(), move.radius()), false);
            case PrimitiveCommand.LookAt look -> new LookAtAction(look.target().x(), look.target().y(), look.target().z());
            case PrimitiveCommand.Jump ignored -> new JumpAction();
            case PrimitiveCommand.BreakBlock mine -> BreakBlockAction.withTool(mine.target(), mine.tool(), mine.sneaking());
            case PrimitiveCommand.PlaceBlock place -> new ExactPlaceBlockAction(place.target(), material(place.material()));
            case PrimitiveCommand.Eat ignored -> new EatFoodAction();
            case PrimitiveCommand.Attack attack -> AttackEntityAction.exactTarget((LivingEntity) Bukkit.getEntity(attack.entityId()));
            case PrimitiveCommand.Pickup pickup -> PickupItemAction.direct(pickup.radius(), true);
            case PrimitiveCommand.Equip equip -> new SlotEquipAction(equip.tool());
            case PrimitiveCommand.SelectSlot select -> new SelectHotbarSlotAction(select.slot());
            case PrimitiveCommand.Craft craft -> new CraftItemAction(material(craft.material()), craft.amount());
            case PrimitiveCommand.Wait wait -> new WaitAction(wait.ticks());
            case PrimitiveCommand.Sneak sneak -> new SneakAction(sneak.ticks());
            case PrimitiveCommand.Swim swim -> new SwimAction(swim.target(), swim.timeoutTicks());
            case PrimitiveCommand.DropJunk ignored -> new DropJunkAction();
        };
    }

    private static String checkMove(AIPlayer ai, PrimitiveCommand.MoveTo move) {
        if (!bounded(move.radius(), 0.25, 4.0)) return "movement radius must be 0.25..4";
        String reason = checkBlock(ai, move.target(), LOCAL_RANGE);
        if (!reason.isEmpty()) return reason;
        World world = ai.getPlayer().getWorld();
        if (move.target().y() + 1 >= world.getMaxHeight()) return "no headroom within world height";
        Block block = Positions.block(world, move.target());
        BlockClass feet = BukkitTerrainView.classify(block.getType());
        BlockClass head = BukkitTerrainView.classify(block.getRelative(BlockFace.UP).getType());
        if (feet != BlockClass.OPEN || head != BlockClass.OPEN) return "movement target lacks safe standing space";
        if (move.target().y() <= world.getMinHeight()) return "movement target has no floor";
        BlockClass floor = BukkitTerrainView.classify(block.getRelative(BlockFace.DOWN).getType());
        return floor == BlockClass.SOLID ? "" : "movement target has no safe floor";
    }

    private static String checkMining(AIPlayer ai, PrimitiveCommand.BreakBlock mine) {
        String reason = checkBlock(ai, mine.target(), REACH);
        if (!reason.isEmpty()) return reason;
        reason = checkTool(ai, mine.tool());
        if (!reason.isEmpty()) return reason;
        Player player = ai.getPlayer();
        World world = player.getWorld();
        Block block = Positions.block(world, mine.target());
        if (block.isEmpty() || block.isLiquid()) return "target is not a mineable block";
        if (block.getType().getHardness() < 0.0F) return "unbreakable block";
        if (!visibleBlock(player, mine.target())) return "target is blocked from view";
        ItemStack selected = player.getInventory().getItem(mine.tool().slot());
        if (!block.isPreferredTool(selected == null ? ItemStack.empty() : selected)) return "chosen tool cannot obtain this block's normal drops";
        BlockPoint feet = Positions.feet(player.getLocation());
        if (mine.target().x() == feet.x() && mine.target().z() == feet.z() && mine.target().y() < feet.y()) return "would remove own floor";
        if (wouldCollapseOnPlayer(player, block)) return "falling blocks above the player";
        Base home = ai.getWorldModel().homeIn(world.getUID());
        if (home != null && home.isInsideBuilding(world.getUID(), mine.target())) return "part of home";
        if (ai.getTeam().getShafts().isStep(world.getUID(), mine.target().offset(0, 1, 0))) return "would cut the way out";
        if (ai.getFurnaceJob() != null && ai.getFurnaceJob().isAt(world.getUID(), mine.target())) return "furnace in use";
        for (BlockFace face : FACES) {
            BlockPoint neighbor = mine.target().offset(face.getModX(), face.getModY(), face.getModZ());
            if (neighbor.y() < world.getMinHeight() || neighbor.y() >= world.getMaxHeight()) continue;
            if (!Positions.isLoaded(world, neighbor)) return "adjacent chunk not loaded";
            if (Positions.block(world, neighbor).getType() == Material.LAVA) return "lava next to target";
        }
        return "";
    }

    private static boolean wouldCollapseOnPlayer(Player player, Block target) {
        BoundingBox body = player.getBoundingBox();
        boolean overBody = target.getX() + 1 > body.getMinX() && target.getX() < body.getMaxX()
                && target.getZ() + 1 > body.getMinZ() && target.getZ() < body.getMaxZ();
        if (!overBody || target.getY() < player.getLocation().getY()) return false;
        if (target.getType().hasGravity()) return true;
        World world = target.getWorld();
        for (int dy = 1; dy <= 8 && target.getY() + dy < world.getMaxHeight(); dy++) {
            Material above = world.getBlockAt(target.getX(), target.getY() + dy, target.getZ()).getType();
            if (above.hasGravity()) return true;
            if (!above.isAir()) return false;
        }
        return false;
    }

    private static String checkPlacement(AIPlayer ai, PrimitiveCommand.PlaceBlock place) {
        Material material = material(place.material());
        if (material == null) return "unknown material";
        String reason = checkBlock(ai, place.target(), REACH);
        return reason.isEmpty() ? ExactPlaceBlockAction.unavailableReason(ai, place.target(), material) : reason;
    }

    private static String checkAttack(AIPlayer ai, PrimitiveCommand.Attack attack) {
        Entity entity = Bukkit.getEntity(attack.entityId());
        if (!(entity instanceof LivingEntity target) || target instanceof Player) return "target is not an attackable non-player living entity";
        if (!target.isValid() || target.isDead() || !target.getWorld().equals(ai.getPlayer().getWorld())) return "target is unavailable in this world";
        if (target.getLocation().distanceSquared(ai.getPlayer().getLocation()) > 16.0 * 16.0) return "combat target exceeds local range";
        return ai.getPlayer().hasLineOfSight(target) ? "" : "combat target is blocked from view";
    }

    private static String checkPickup(AIPlayer ai, double radius) {
        if (!bounded(radius, 0.5, 12.0)) return "pickup radius must be 0.5..12";
        if (ai.getInventory().isFull()) return "inventory is full";
        Player player = ai.getPlayer();
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof Item item && item.isValid() && !DropJunkAction.isDiscarded(item)
                    && PickupItemAction.isInRange(player.getLocation(), item.getLocation(), radius)) return "";
        }
        return "no nearby item to pick up";
    }

    private static String checkTool(AIPlayer ai, ToolChoice choice) {
        if (choice == null || choice.slot() < 0 || choice.slot() >= 36) return "storage slot must be 0..35";
        ItemStack item = ai.getPlayer().getInventory().getItem(choice.slot());
        String actual = item == null || item.isEmpty() ? Material.AIR.name() : item.getType().name();
        Map<String, Integer> enchants = item == null || item.isEmpty() ? Map.of() : enchantments(item);
        return actual.equals(choice.material()) && enchants.equals(choice.enchantments()) ? "" : "chosen slot item or enchantments changed";
    }

    private static String checkCraft(AIPlayer ai, PrimitiveCommand.Craft craft) {
        Material material = material(craft.material());
        if (material == null || material.isAir() || !material.isItem()) return "unknown craftable item";
        if (craft.amount() < 1 || craft.amount() > 64) return "craft amount must be 1..64";
        if (ai.getWorkBudget().isExhausted()) return "work budget exhausted; retry next tick";
        var plan = ai.getCrafting().plan(material, craft.amount(), ai.getInventory().snapshot());
        if (!plan.isFeasible()) return "recipe or required materials unavailable";
        if (!plan.needsTable()) return "";
        Player player = ai.getPlayer();
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    if (ai.getWorkBudget().isExhausted()) return "work budget exhausted; retry next tick";
                    BlockPoint point = new BlockPoint(eye.getBlockX() + dx, eye.getBlockY() + dy, eye.getBlockZ() + dz);
                    if (point.y() < world.getMinHeight() || point.y() >= world.getMaxHeight() || !Positions.isLoaded(world, point)) continue;
                    if (Positions.block(world, point).getType() == Material.CRAFTING_TABLE
                            && eye.distanceSquared(Positions.center(world, point)) <= 4.5 * 4.5 && visibleBlock(player, point)) return "";
                }
            }
        }
        return "no visible crafting table in reach";
    }

    private static String checkSwimming(AIPlayer ai, PrimitiveCommand.Swim swim) {
        String reason = checkTicks(swim.timeoutTicks(), 400);
        if (!reason.isEmpty()) return reason;
        reason = checkPoint(ai, swim.target(), 16.0);
        if (!reason.isEmpty()) return reason;
        if (!ai.getPlayer().isInWater()) return "not in water";
        if (ai.getPlayer().getRemainingAir() <= 60) return "insufficient air for swimming";
        BlockPoint target = new BlockPoint((int) Math.floor(swim.target().x()), (int) Math.floor(swim.target().y()), (int) Math.floor(swim.target().z()));
        return Positions.block(ai.getPlayer().getWorld(), target).getType() == Material.WATER ? "" : "swimming target is not water";
    }

    private static String checkBlock(AIPlayer ai, BlockPoint point, double range) {
        if (point == null) return "missing target";
        return checkPoint(ai, new PrimitiveTarget.Point(point.x() + 0.5, point.y() + 0.5, point.z() + 0.5), range);
    }

    private static String checkPoint(AIPlayer ai, PrimitiveTarget.Point point, double range) {
        if (point == null || !Double.isFinite(point.x()) || !Double.isFinite(point.y()) || !Double.isFinite(point.z())) return "non-finite target";
        World world = ai.getPlayer().getWorld();
        if (point.y() < world.getMinHeight() || point.y() >= world.getMaxHeight()) return "target outside world height";
        Location target = new Location(world, point.x(), point.y(), point.z());
        if (ai.getPlayer().getEyeLocation().distanceSquared(target) > range * range) return "target exceeds local range";
        if (!world.getWorldBorder().isInside(target)) return "target outside world border";
        return world.isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4) ? "" : "target chunk not loaded";
    }

    private static boolean visibleBlock(Player player, BlockPoint target) {
        Location eye = player.getEyeLocation();
        World world = player.getWorld();
        Location center = Positions.center(world, target);
        int minX = Math.min(eye.getBlockX(), target.x()) >> 4;
        int maxX = Math.max(eye.getBlockX(), target.x()) >> 4;
        int minZ = Math.min(eye.getBlockZ(), target.z()) >> 4;
        int maxZ = Math.max(eye.getBlockZ(), target.z()) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) if (!world.isChunkLoaded(x, z)) return false;
        }
        Vector direction = center.toVector().subtract(eye.toVector());
        double length = direction.length();
        if (length < 1.0E-6) return true;
        RayTraceResult hit = world.rayTraceBlocks(eye, direction.normalize(), length + 0.01, FluidCollisionMode.NEVER, true);
        return hit == null || hit.getHitBlock() == null || Positions.of(hit.getHitBlock()).equals(target);
    }

    private static Map<String, Integer> enchantments(ItemStack item) {
        Map<String, Integer> result = new TreeMap<>();
        item.getEnchantments().forEach((enchantment, level) -> result.put(enchantment.getKey().toString(), level));
        return Map.copyOf(result);
    }

    private static Material material(String name) {
        return name == null ? null : Material.getMaterial(name);
    }

    private static String checkTicks(int ticks, int maximum) {
        return ticks >= 1 && ticks <= maximum ? "" : "ticks must be 1.." + maximum;
    }

    private static boolean bounded(double value, double minimum, double maximum) {
        return Double.isFinite(value) && value >= minimum && value <= maximum;
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("primitive world access requires the server main thread");
    }
}
