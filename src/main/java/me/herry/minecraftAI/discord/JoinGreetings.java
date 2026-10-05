package me.herry.minecraftAI.discord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Event-lane-only admission. No inference, filesystem, or guessed relationship/identity. */
final class JoinGreetings {
    record Policy(boolean enabled, long settleMillis, long cooldownMillis, long expiresMillis) {
        Policy {
            if (settleMillis < 1000 || settleMillis > 10_000 || cooldownMillis < 60_000 || cooldownMillis > 3_600_000
                    || expiresMillis < settleMillis || expiresMillis > 60_000) throw new IllegalArgumentException("greeting policy");
        }
        static Policy defaults(boolean enabled) { return new Policy(enabled, 2000, 600_000, 30_000); }
    }
    private final Policy policy;
    private final Map<String, Long> pending = new LinkedHashMap<>(), attempted = new LinkedHashMap<>();
    private Set<String> members = Set.of();
    JoinGreetings(Policy policy) { this.policy = java.util.Objects.requireNonNull(policy); }
    void participants(Set<String> next, long now) {
        if (next.size() > 8) throw new IllegalArgumentException("greeting participant capacity");
        prune(now); pending.keySet().retainAll(next);
        if (policy.enabled) for (String user : next) if (!members.contains(user) && !attempted.containsKey(user)) pending.put(user, now);
        members = Set.copyOf(next);
    }
    String claim(long now, long lastHumanSpeech, boolean busy) {
        prune(now);
        if (!policy.enabled || busy || now - lastHumanSpeech < policy.settleMillis) return null;
        var candidates = pending.entrySet().iterator();
        while (candidates.hasNext()) {
            var entry = candidates.next();
            if (now - entry.getValue() < policy.settleMillis) continue;
            String user = entry.getKey(); candidates.remove(); attempted.put(user, now);
            while (attempted.size() > 256) attempted.remove(attempted.keySet().iterator().next());
            return user;
        }
        return null;
    }
    void clearPending() { pending.clear(); }
    void dismiss(String user, long now) {
        pending.remove(user); attempted.put(user, now);
        while (attempted.size() > 256) attempted.remove(attempted.keySet().iterator().next());
    }
    private void prune(long now) {
        pending.entrySet().removeIf(entry -> now - entry.getValue() >= policy.expiresMillis);
        attempted.entrySet().removeIf(entry -> now - entry.getValue() >= policy.cooldownMillis);
    }
    static String text(DiscordMemory.Subject person, List<DiscordMemory.Fact> facts, long now) {
        String name = ""; boolean allowed = false, refused = false;
        for (var fact : facts) {
            if (!fact.key().subject().equals(person) || fact.evidence() != DiscordMemory.Evidence.EXPLICIT || fact.expired(now)) continue;
            if (fact.key().kind() == DiscordMemory.Kind.NAME && fact.value().matches("[가-힣A-Za-z]{1,20}")) name = fact.value();
            if (fact.key().kind() == DiscordMemory.Kind.SPEECH_AGREEMENT) {
                allowed |= fact.value().equals("ALLOWED"); refused |= fact.value().equals("REFUSED");
            }
        }
        if (allowed && !refused) return name.isEmpty() ? "왔어? 오늘도 같이 놀자." : name + ", 왔어? 오늘도 같이 놀자.";
        return name.isEmpty() ? "안녕하세요! 해리예요. 같이 즐겁게 놀아요." : name + "님, 반가워요. 오늘도 같이 놀아요.";
    }
}
