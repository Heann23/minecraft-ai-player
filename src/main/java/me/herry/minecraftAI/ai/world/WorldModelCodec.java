package me.herry.minecraftAI.ai.world;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * WorldModel 을 저장 파일에 쓸 수 있는 단순한 값(문자열, 숫자, 목록, 맵)으로 바꾸고 되돌린다.
 * 한 줄 표기는 "월드UUID|x,y,z" 꼴이다.
 */
final class WorldModelCodec {
    private static final String SEPARATOR = "\\|";

    private WorldModelCodec() {
    }

    static Map<String, Object> export(WorldModel model, Map<UUID, Set<Long>> explored) {
        Map<String, Object> state = new LinkedHashMap<>();
        Base home = model.getHome();
        if (home != null) state.put("home", home.exportState());

        List<String> portals = new ArrayList<>();
        for (WorldModel.Portal portal : model.getPortals()) {
            portals.add(portal.kind().name() + "|" + place(portal.world(), portal.pos()));
        }
        state.put("portals", portals);

        WorldModel.Place stronghold = model.getStronghold();
        if (stronghold != null) state.put("stronghold", place(stronghold.world(), stronghold.pos()));
        state.put("endPortalReady", model.isEndPortalReady());
        state.put("dragonDefeated", model.isDragonDefeated());

        List<String> deaths = new ArrayList<>();
        for (WorldModel.Place death : model.getDeaths()) deaths.add(place(death.world(), death.pos()));
        state.put("deaths", deaths);

        Map<String, Object> chunks = new LinkedHashMap<>();
        for (Map.Entry<UUID, Set<Long>> entry : explored.entrySet()) {
            StringBuilder line = new StringBuilder();
            for (long key : entry.getValue()) {
                if (!line.isEmpty()) line.append(' ');
                line.append((int) (key >> 32)).append(':').append((int) key);
            }
            chunks.put(entry.getKey().toString(), line.toString());
        }
        state.put("explored", chunks);

        List<String> storage = new ArrayList<>();
        for (Map.Entry<WorldModel.Place, Map<String, Integer>> entry : model.getStorage().entrySet()) {
            StringBuilder contents = new StringBuilder();
            for (Map.Entry<String, Integer> item : entry.getValue().entrySet()) {
                if (!contents.isEmpty()) contents.append(';');
                contents.append(item.getKey()).append('=').append(item.getValue());
            }
            storage.add(place(entry.getKey().world(), entry.getKey().pos()) + "|" + contents);
        }
        state.put("storage", storage);
        return state;
    }

    @SuppressWarnings("unchecked")
    static void restore(WorldModel model, Map<String, Object> state) {
        if (state.get("home") instanceof Map<?, ?> home) model.setHome(Base.importState((Map<String, Object>) home));

        for (String line : strings(state.get("portals"))) {
            String[] parts = line.split(SEPARATOR);
            if (parts.length != 3) continue;
            try {
                model.rememberPortal(WorldModel.PortalKind.valueOf(parts[0]), UUID.fromString(parts[1]), BlockPoint.parse(parts[2]));
            } catch (IllegalArgumentException ignored) {
                // 알 수 없는 종류이거나 좌표가 망가진 줄은 건너뛴다.
            }
        }

        if (state.get("stronghold") != null) model.setStronghold(parsePlace(String.valueOf(state.get("stronghold"))));
        model.setEndPortalReady(Boolean.TRUE.equals(state.get("endPortalReady")));
        model.setDragonDefeated(Boolean.TRUE.equals(state.get("dragonDefeated")));

        for (String line : strings(state.get("deaths"))) {
            WorldModel.Place place = parsePlace(line);
            if (place != null) model.recordDeath(place.world(), place.pos());
        }

        if (state.get("explored") instanceof Map<?, ?> chunks) {
            for (Map.Entry<?, ?> entry : chunks.entrySet()) restoreChunks(model, String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }

        for (String line : strings(state.get("storage"))) {
            String[] parts = line.split(SEPARATOR, 3);
            if (parts.length < 2) continue;
            WorldModel.Place place = parsePlace(parts[0] + "|" + parts[1]);
            if (place != null) model.rememberContents(place.world(), place.pos(), parseContents(parts.length == 3 ? parts[2] : ""));
        }
    }

    private static void restoreChunks(WorldModel model, String worldId, String line) {
        UUID world;
        try {
            world = UUID.fromString(worldId);
        } catch (IllegalArgumentException e) {
            return;
        }
        for (String chunk : line.split(" ")) {
            int colon = chunk.indexOf(':');
            if (colon <= 0) continue;
            try {
                model.markExplored(world, Integer.parseInt(chunk.substring(0, colon)), Integer.parseInt(chunk.substring(colon + 1)));
            } catch (NumberFormatException ignored) {
                // 망가진 항목은 건너뛴다.
            }
        }
    }

    private static Map<String, Integer> parseContents(String text) {
        Map<String, Integer> contents = new HashMap<>();
        for (String item : text.split(";")) {
            int equals = item.indexOf('=');
            if (equals <= 0) continue;
            try {
                contents.put(item.substring(0, equals), Integer.parseInt(item.substring(equals + 1)));
            } catch (NumberFormatException ignored) {
                // 망가진 항목은 건너뛴다.
            }
        }
        return contents;
    }

    private static String place(UUID world, BlockPoint pos) {
        return world + "|" + pos.encode();
    }

    private static @Nullable WorldModel.Place parsePlace(String line) {
        String[] parts = line.split(SEPARATOR);
        if (parts.length != 2) return null;
        try {
            return new WorldModel.Place(UUID.fromString(parts[0]), BlockPoint.parse(parts[1]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> strings(@Nullable Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) result.add(String.valueOf(item));
        }
        return result;
    }
}
