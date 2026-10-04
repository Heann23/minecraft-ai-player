package me.herry.minecraftAI.ai.team;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.AIState;
import me.herry.minecraftAI.ai.comm.CommunicationHub;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.plan.ResourceLocator;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * AI 가 여럿일 때 서로 돕게 하는 선택 기능: 자원 위치 공유, 캐는 블록 맡아 두기, 도움과 음식 요청.
 * AI 가 한 명뿐이면 동료가 없으므로 이 기능들은 아무 일도 하지 않고, AI 의 판단과 진행은 여기에 의존하지 않는다.
 * (역할 분담은 없다. 모든 AI 가 모든 일을 스스로 한다.)
 *
 * 채팅 문장은 사람이 읽을 수 있게 한국어로 내보내고, 실제 정보(좌표, 대상)는 같은 시점에 상대의 기억에 직접 넣어 준다.
 */
public final class TeamChat {
    private record FoodRequest(AIPlayer requester, long expiresAt) {
    }

    private record Claim(String owner, UUID world, BlockPoint pos, long expiresAt) {
    }

    // 자원 위치를 알려 주는 거리와 도움 요청이 닿는 거리
    private static final double SHARE_RANGE = 64.0;
    private static final double HELP_RANGE = 40.0;
    private static final long SHARED_MEMORY_TTL = 6000L;
    private static final long CLAIM_TTL = 300L;
    private static final long HELP_TTL = 400L;
    private static final long HELP_COOLDOWN = 300L;
    private static final double FOOD_SHARE_RANGE = 128.0;
    private static final long FOOD_REQUEST_COOLDOWN = 600L;
    private static final long FOOD_REQUEST_TTL = 1200L;
    // 이만큼은 가지고 있어야 남에게 나눠 준다.
    private static final int MIN_FOOD_TO_SHARE = 4;
    // 다른 AI 가 맡은 블록에서 이 범위 안의 블록은 같은 나무나 같은 돌무더기로 보고 건드리지 않는다.
    private static final int CLAIM_RADIUS = 2;
    private static final int CLAIM_HEIGHT = 8;
    // 이 거리 안에 서 있는 동료는 떨어진 아이템을 곧 주울 것으로 본다.
    private static final double DROP_OWNER_RANGE = 4.0;
    private static final MemoryType[] SHARED_TYPES = {MemoryType.TREE, MemoryType.STONE, MemoryType.IRON_ORE, MemoryType.DIAMOND_ORE, MemoryType.WORKBENCH};

    private final Supplier<List<AIPlayer>> members;
    private final CommunicationHub hub;
    private final List<Claim> claims = new ArrayList<>();
    private final Map<String, Long> lastHelpRequest = new HashMap<>();

    // 파 놓은 굴. 땅속으로 내려갈 때 새로 파지 않고 이 굴을 따라간다. AI 가 여럿이면 함께 쓴다.
    private final ShaftRegistry shafts = new ShaftRegistry();
    private final Map<String, FoodRequest> foodRequests = new HashMap<>();
    private final Map<String, Long> lastFoodRequest = new HashMap<>();

    public TeamChat(Supplier<List<AIPlayer>> members, CommunicationHub hub) {
        this.members = members;
        this.hub = hub;
    }

    /**
     * 배고픈데 음식이 없을 때 동료에게 나눠 달라고 청한다. 음식이 넉넉한 동료가 있으면 그 동료가 가져다준다.
     */
    public void requestFood(AIPlayer ai) {
        long now = Bukkit.getCurrentTick();
        Long last = lastFoodRequest.get(ai.getName());
        if (last != null && now - last < FOOD_REQUEST_COOLDOWN) return;

        List<AIPlayer> mates = teammates(ai, FOOD_SHARE_RANGE);
        if (mates.isEmpty()) return;
        lastFoodRequest.put(ai.getName(), now);

        AIPlayer donor = null;
        for (AIPlayer mate : mates) {
            if (mate.getState() == AIState.RUNNING && mate.getInventory().foodCount() >= MIN_FOOD_TO_SHARE
                    && !foodRequests.containsKey(mate.getName())) {
                donor = mate;
                break;
            }
        }

        say(ai, Phrases.askFood(), true);
        if (donor == null) {
            say(ai, Phrases.goForage(), true);
            return;
        }
        foodRequests.put(donor.getName(), new FoodRequest(ai, now + FOOD_REQUEST_TTL));
        say(donor, Phrases.willShareFood(ai.getName()), true);
    }

    // 이 AI 가 음식을 가져다주기로 한 상대. 없거나 약속이 끝났으면 null.
    public @Nullable AIPlayer foodRequesterFor(AIPlayer donor) {
        FoodRequest request = foodRequests.get(donor.getName());
        if (request == null) return null;
        AIPlayer requester = request.requester();
        boolean valid = Bukkit.getCurrentTick() < request.expiresAt() && requester.getBody().isUsable() && donor.getBody().isUsable()
                && Positions.sameWorld(requester.getPlayer().getLocation(), donor.getPlayer().getLocation());
        if (!valid) {
            foodRequests.remove(donor.getName());
            return null;
        }
        return requester;
    }

    public boolean isFoodOnTheWay(AIPlayer ai) {
        long now = Bukkit.getCurrentTick();
        for (FoodRequest request : foodRequests.values()) {
            if (request.requester() == ai && now < request.expiresAt()) return true;
        }
        return false;
    }

    public void finishFoodRequest(AIPlayer donor) {
        foodRequests.remove(donor.getName());
    }

    public ShaftRegistry getShafts() {
        return shafts;
    }

    /**
     * AI 가 스스로 하는 말. 어디로 내보낼지(인게임 채팅, Discord)와 얼마나 자주 말할지는 CommunicationHub 가 정한다.
     */
    public void say(AIPlayer speaker, String message, boolean urgent) {
        hub.say(speaker.getName(), message, urgent);
    }

    public void announceGoal(AIPlayer ai, GoalType goal) {
        String line = Phrases.goalAnnouncement(goal);
        if (line != null) say(ai, line, goal == GoalType.ESCAPE_DANGER || goal == GoalType.FIGHT_HOSTILE);
    }

    /**
     * 자기가 아는 자원 위치를, 아직 그 자원을 모르는 가까운 동료에게 알려 준다.
     */
    public void shareDiscoveries(AIPlayer ai) {
        List<AIPlayer> mates = teammates(ai, SHARE_RANGE);
        if (mates.isEmpty()) return;

        UUID world = ai.getWorldId();
        for (MemoryType type : SHARED_TYPES) {
            BlockPoint pos = ResourceLocator.locate(ai, type);
            if (pos == null) continue;

            AIPlayer informed = null;
            for (AIPlayer mate : mates) {
                if (mate.getMemory().count(type, world, mate.getTicks()) > 0) continue;
                mate.getMemory().remember(type, world, pos, mate.getTicks(), SHARED_MEMORY_TTL);
                informed = mate;
            }
            if (informed != null) {
                say(ai, Phrases.found(type), true);
                say(informed, Phrases.thanks(ai.getName()), false);
            }
        }
    }

    // 작업대처럼 방금 설치한 것을 가까운 동료 모두에게 알린다.
    public void sharePlaced(AIPlayer ai, MemoryType type, BlockPoint pos) {
        List<AIPlayer> mates = teammates(ai, SHARE_RANGE);
        if (mates.isEmpty()) return;
        for (AIPlayer mate : mates) mate.getMemory().rememberPermanent(type, ai.getWorldId(), pos, mate.getTicks());
        say(ai, Phrases.placed(type), true);
    }

    /**
     * 몬스터에게 공격받을 때 가까운 동료에게 도움을 청한다. 동료는 그 몬스터를 공격 대상으로 삼는다.
     */
    public void requestHelp(AIPlayer ai, LivingEntity attacker) {
        long now = Bukkit.getCurrentTick();
        Long last = lastHelpRequest.get(ai.getName());
        if (last != null && now - last < HELP_COOLDOWN) return;

        List<AIPlayer> mates = teammates(ai, HELP_RANGE);
        if (mates.isEmpty()) return;
        lastHelpRequest.put(ai.getName(), now);

        say(ai, Phrases.help(PerceptionSystem.threatTypeOf(attacker)), true);
        boolean answered = false;
        for (AIPlayer mate : mates) {
            if (mate.getState() != AIState.RUNNING) continue;
            mate.setAssistTarget(attacker, HELP_TTL);
            if (!answered) say(mate, Phrases.onMyWay(ai.getName()), true);
            answered = true;
        }
    }

    // 캐러 가는 블록을 맡아 둔다. 다른 AI 가 같은 나무나 돌을 동시에 노리지 않게 하기 위한 것이다.
    public void claim(AIPlayer ai, BlockPoint pos) {
        long now = Bukkit.getCurrentTick();
        claims.removeIf(claim -> claim.expiresAt() <= now || claim.owner().equals(ai.getName()));
        claims.add(new Claim(ai.getName(), ai.getWorldId(), pos, now + CLAIM_TTL));
    }

    public boolean isClaimedByOther(AIPlayer ai, UUID world, BlockPoint pos) {
        if (claims.isEmpty()) return false;
        long now = Bukkit.getCurrentTick();
        for (Claim claim : claims) {
            if (claim.expiresAt() <= now || claim.owner().equals(ai.getName()) || !claim.world().equals(world)) continue;
            BlockPoint other = claim.pos();
            if (Math.abs(other.x() - pos.x()) <= CLAIM_RADIUS && Math.abs(other.z() - pos.z()) <= CLAIM_RADIUS
                    && Math.abs(other.y() - pos.y()) <= CLAIM_HEIGHT) return true;
        }
        return false;
    }

    // AI 가 제거될 때 그 AI 가 남긴 기록을 지운다.
    public void forget(String name) {
        claims.removeIf(claim -> claim.owner().equals(name));
        lastHelpRequest.remove(name);
        lastFoodRequest.remove(name);
        foodRequests.remove(name);
        foodRequests.values().removeIf(request -> request.requester().getName().equals(name));
    }

    public int teamSize() {
        return members.get().size();
    }

    /**
     * 떨어진 아이템 바로 옆에 이 AI 보다 더 가까이 있는 동료가 있는지. 동료가 캐서 떨어뜨린 아이템은 그 동료가 줍게 둔다.
     * 그렇지 않으면 둘이 같은 아이템을 두고 서로 주우러 다니게 된다.
     */
    public boolean isTeammateCloser(AIPlayer ai, Location item) {
        double own = Positions.distance(ai.getPlayer().getLocation(), item);
        for (AIPlayer mate : teammates(ai, SHARE_RANGE)) {
            if (mate.getState() != AIState.RUNNING) continue;
            double distance = Positions.distance(mate.getPlayer().getLocation(), item);
            if (distance <= DROP_OWNER_RANGE && distance < own) return true;
        }
        return false;
    }

    // 가까이에서 움직이고 있는 동료의 수.
    public int countNearbyAllies(AIPlayer ai, double range) {
        int count = 0;
        for (AIPlayer mate : teammates(ai, range)) {
            if (mate.getState() == AIState.RUNNING) count++;
        }
        return count;
    }

    // 같은 월드에서 range 안에 있고 살아 있는 다른 AI 들.
    private List<AIPlayer> teammates(AIPlayer ai, double range) {
        List<AIPlayer> result = new ArrayList<>();
        if (!ai.getBody().isUsable()) return result;
        for (AIPlayer other : members.get()) {
            if (other == ai || !other.getBody().isUsable()) continue;
            if (Positions.distance(ai.getPlayer().getLocation(), other.getPlayer().getLocation()) <= range) result.add(other);
        }
        return result;
    }
}
