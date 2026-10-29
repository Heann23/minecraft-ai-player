package me.herry.minecraftAI.ai.learned;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Resume discovery after the last visited source. This schedules observation work, never a chosen action. */
final class CandidateDiscovery {
    enum Source { DIRECT_MOVE, TARGET_MOVE, LOOK, JUMP, EQUIPMENT, MINE, CONSUME, PLACE, ATTACK, PICKUP, CRAFT }
    record Visit(List<Source> sources, boolean budgetExhausted) {
        Visit { sources = List.copyOf(sources); }
    }
    private int next;

    Visit visit(BooleanSupplier exhausted, Consumer<Source> discover) {
        var sources = new ArrayList<Source>();
        Source[] all = Source.values();
        while (sources.size() < all.length && !exhausted.getAsBoolean()) {
            Source source = all[next];
            next = (next + 1) % all.length;
            sources.add(source);
            discover.accept(source);
        }
        if (sources.size() == all.length) next = (next + 1) % all.length;
        return new Visit(sources, exhausted.getAsBoolean());
    }
}
