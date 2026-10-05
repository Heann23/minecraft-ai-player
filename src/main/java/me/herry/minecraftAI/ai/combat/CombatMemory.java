package me.herry.minecraftAI.ai.combat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 한 번의 전투가 이어지는 동안 유지해야 하는 기억.
 * 매 판단마다 그 순간의 상황만 보고 결정하면, 몬스터가 나무 뒤로 잠깐 가려지거나 체력이 조금 회복될 때마다
 * 싸움과 도망이 뒤바뀌어서 다가갔다 물러났다를 반복하게 된다.
 */
public final class CombatMemory {
    private static final long NEVER = Long.MIN_VALUE / 2;
    private static final int MAX_TRACKED = 64;
    // 한번 본 몬스터는 이 시간 동안 가려져 있어도 상대로 친다.
    public static final long SEEN_TICKS = 100L;
    // 다가갈 길이 없었던 몬스터는 이 시간 동안 공격 대상으로 고르지 않는다.
    private static final long UNREACHABLE_TICKS = 300L;
    // 숨은 뒤 이 시간(2분) 동안은 몬스터가 있는 쪽으로 굴을 뚫지 않는다.
    private static final long REFUGE_CAUTION_TICKS = 2400L;
    private static final int MAX_HITS = 8;

    private final Map<UUID, Long> lastSeen = new HashMap<>();
    private final Map<UUID, Long> unreachableAt = new HashMap<>();
    private long fleeUntil = NEVER;
    private long lastFledTick = NEVER;
    private long standGroundUntil = NEVER;
    private long retreatBlockedTick = NEVER;
    private long refugeTick = NEVER;
    // 최근에 맞은 시각들. 달아나는 중에도 계속 맞고 있는지 볼 때 쓴다.
    private final long[] hitTicks = new long[MAX_HITS];
    private int hitCount;
    private long fleeingSince = NEVER;
    private CombatSystem.Decision lastDecision = CombatSystem.Decision.NONE;
    private boolean sealedIn;

    public void markSeen(UUID entity, long now) {
        if (lastSeen.size() >= MAX_TRACKED && !lastSeen.containsKey(entity)) prune(now, 0L);
        lastSeen.put(entity, now);
    }

    // 최근에 본 몬스터는 지금 잠깐 가려져 있어도 계속 상대로 친다.
    public boolean seenWithin(UUID entity, long now, long window) {
        Long tick = lastSeen.get(entity);
        return tick != null && now - tick <= window;
    }

    public void prune(long now, long window) {
        lastSeen.values().removeIf(tick -> now - tick > window);
        unreachableAt.values().removeIf(tick -> now - tick > UNREACHABLE_TICKS);
    }

    // 다가가려 했지만 길이 없었던 몬스터를 적어 둔다 (협곡 건너편, 다른 층의 동굴 등).
    public void markUnreachable(UUID entity, long now) {
        if (unreachableAt.size() >= MAX_TRACKED && !unreachableAt.containsKey(entity)) unreachableAt.clear();
        unreachableAt.put(entity, now);
    }

    public boolean isUnreachable(UUID entity, long now) {
        Long tick = unreachableAt.get(entity);
        return tick != null && now - tick <= UNREACHABLE_TICKS;
    }

    public void onFlee(long now, long commitTicks) {
        fleeUntil = now + commitTicks;
        lastFledTick = now;
    }

    // 도망치기로 한 결정을 유지해야 하는 시간이 아직 남았는지
    public boolean isCommittedToFlee(long now) {
        return now < fleeUntil;
    }

    // 도망칠 수 없어서 맞서 싸우기로 했을 때 호출한다.
    public void onStandGround(long now, long commitTicks) {
        standGroundUntil = now + commitTicks;
        fleeUntil = NEVER;
    }

    public boolean isStandingGround(long now) {
        return now < standGroundUntil;
    }

    public boolean fledWithin(long now, long window) {
        return now - lastFledTick <= window;
    }

    // 크리퍼에게서 뒷걸음으로 물러나려 했지만 물러날 자리가 없었을 때 호출한다.
    public void onRetreatBlocked(long now) {
        retreatBlockedTick = now;
    }

    public boolean retreatBlockedWithin(long now, long window) {
        return now - retreatBlockedTick <= window;
    }

    // 몬스터에게 맞았을 때 호출한다.
    public void onHit(long now) {
        hitTicks[hitCount % MAX_HITS] = now;
        hitCount++;
    }

    // 판단할 때마다 호출해서 달아나기 시작한 시각을 적어 둔다. 달아나는 동안 맞은 횟수를 세는 기준이다.
    public void trackFleeing(boolean fleeing, long now) {
        if (!fleeing) fleeingSince = NEVER;
        else if (fleeingSince == NEVER) fleeingSince = now;
    }

    /**
     * 달아나기 시작한 뒤로 그 시간 안에 맞은 횟수 (최근 8번까지만 센다). 달아나는 중이 아니면 0.
     * 싸우다가 맞은 것은 세지 않는다. 싸우다 밀려서 달아나기로 한 순간에 "이미 많이 맞았다"고 보면 달아나지 못한다.
     */
    public int hitsWhileFleeing(long now, long window) {
        if (fleeingSince == NEVER) return 0;
        int hits = 0;
        for (int i = 0; i < Math.min(hitCount, MAX_HITS); i++) {
            if (hitTicks[i] >= fleeingSince && now - hitTicks[i] <= window) hits++;
        }
        return hits;
    }

    // 감당할 수 없는 몬스터를 피해 파고 들어가 숨었을 때 호출한다.
    public void onRefuge(long now) {
        refugeTick = now;
    }

    /**
     * 숨은 지 얼마 안 됐는지. 그동안은 몬스터가 있는 쪽으로 굴을 뚫지 않는다.
     * 평소에는 이렇게 조심하지 않는다. 채굴하다 동굴과 이어지는 것은 흔한 일이고, 감당할 수 있는 몬스터는 싸워서 처리한다.
     */
    public boolean isWaryAfterRefuge(long now) {
        return now - refugeTick <= REFUGE_CAUTION_TICKS;
    }

    // 바로 앞의 판단에서 몬스터를 상대하고 있었는지 (싸우거나 달아나는 중)
    public boolean wasEngaged() {
        return lastDecision != CombatSystem.Decision.NONE;
    }

    // 판단이 이전과 달라졌으면 true. 달라질 때만 디버그 로그를 남기기 위한 것이다.
    public boolean updateLastDecision(CombatSystem.Decision decision) {
        if (decision == lastDecision) return false;
        lastDecision = decision;
        return true;
    }

    // 숨은 상태가 이전과 달라졌으면 true. 달라질 때만 디버그 로그를 남기기 위한 것이다.
    public boolean updateSealedIn(boolean sealed) {
        if (sealed == sealedIn) return false;
        sealedIn = sealed;
        return true;
    }

    public void reset() {
        sealedIn = false;
        lastDecision = CombatSystem.Decision.NONE;
        lastSeen.clear();
        unreachableAt.clear();
        fleeUntil = NEVER;
        lastFledTick = NEVER;
        standGroundUntil = NEVER;
        retreatBlockedTick = NEVER;
        refugeTick = NEVER;
        hitCount = 0;
        fleeingSince = NEVER;
    }
}
