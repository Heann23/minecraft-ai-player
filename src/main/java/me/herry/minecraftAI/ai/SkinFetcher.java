package me.herry.minecraftAI.ai;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 마인크래프트 계정 이름으로 그 계정의 스킨을 받아 온다.
 *
 * 서버의 이름 -> UUID 캐시는 쓰지 않는다. AI 플레이어가 접속하면 그 닉네임이 AI 용 UUID 로 캐시에 등록되는데,
 * 같은 이름의 진짜 계정 스킨을 찾을 때 그 잘못된 UUID 로 조회하게 되어 스킨을 찾지 못하기 때문이다.
 */
final class SkinFetcher {
    private static final Pattern ACCOUNT_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final String LOOKUP_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final Logger logger;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    SkinFetcher(Logger logger) {
        this.logger = logger;
    }

    /**
     * 메인 스레드에서 호출한다. 네트워크 조회는 다른 스레드에서 이루어지고, 찾지 못하면 null 로 완료된다.
     */
    CompletableFuture<@Nullable AISkin> fetch(String accountName) {
        if (!ACCOUNT_NAME.matcher(accountName).matches()) return CompletableFuture.completedFuture(null);

        // 접속해 있는 플레이어라면 서버가 이미 스킨을 가지고 있다.
        Player online = Bukkit.getPlayerExact(accountName);
        if (online != null) {
            AISkin skin = skinOf(online.getPlayerProfile());
            if (skin != null) return CompletableFuture.completedFuture(skin);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                UUID id = lookupId(accountName);
                if (id == null) return null;
                PlayerProfile profile = Bukkit.createProfile(id, accountName);
                profile.complete(true);
                return skinOf(profile);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Could not fetch the skin of " + accountName, e);
                return null;
            }
        });
    }

    // 모장 API 에서 계정의 진짜 UUID 를 조회한다. 그런 계정이 없으면 null.
    private @Nullable UUID lookupId(String accountName) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(LOOKUP_URL + accountName)).timeout(TIMEOUT).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!json.has("id")) return null;
        String hex = json.get("id").getAsString();
        if (hex.length() != 32) return null;
        // API 는 UUID 를 하이픈 없이 돌려준다.
        return UUID.fromString(hex.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
    }

    private static @Nullable AISkin skinOf(PlayerProfile profile) {
        for (ProfileProperty property : profile.getProperties()) {
            if (property.getName().equals("textures") && property.getSignature() != null) {
                return new AISkin(property.getValue(), property.getSignature());
            }
        }
        return null;
    }
}
