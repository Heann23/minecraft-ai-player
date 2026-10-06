package me.herry.minecraftAI.discord;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Bounded counts of the fixed diagnostic codes since start. Only code-shaped strings are kept; anything else is just counted as other. */
final class DiagnosticCounters {
    static final int MAX_CODES = 64, SHOWN = 12;
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9-]{2,63}");
    private final ConcurrentHashMap<String, LongAdder> counts = new ConcurrentHashMap<>();
    private final LongAdder other = new LongAdder();

    void record(String code) {
        if (code == null || !CODE.matcher(code).matches()) { other.increment(); return; }
        LongAdder adder = counts.get(code);
        if (adder == null) {
            if (counts.size() >= MAX_CODES) { other.increment(); return; }
            adder = counts.computeIfAbsent(code, ignored -> new LongAdder());
        }
        adder.increment();
    }
    /** Most frequent first, then by name; at most {@link #SHOWN} codes. Contains no text other than the fixed codes. */
    String describe() {
        String shown = counts.entrySet().stream().map(entry -> Map.entry(entry.getKey(), entry.getValue().sum()))
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(SHOWN).map(entry -> entry.getKey() + " " + entry.getValue()).collect(Collectors.joining(" · "));
        long unlisted = other.sum();
        if (unlisted > 0) shown = (shown.isEmpty() ? "" : shown + " · ") + "기타 " + unlisted;
        return shown.isEmpty() ? "없음" : shown;
    }
}
