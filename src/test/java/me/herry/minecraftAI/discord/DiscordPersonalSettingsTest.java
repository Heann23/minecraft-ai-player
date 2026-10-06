package me.herry.minecraftAI.discord;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static me.herry.minecraftAI.discord.DiscordMemory.*;
import static org.junit.jupiter.api.Assertions.*;

class DiscordPersonalSettingsTest {
    private final Subject own = new Subject("guild", "herry", "A");
    private Fact fact(Subject subject, Kind kind, String label, String value, long expiry) {
        return new Fact(new Key(subject, kind, "", label), value, Evidence.EXPLICIT, "confirmed", 1000, expiry, 1);
    }
    private Snapshot snapshot(Fact... facts) {
        var values = new HashMap<Key, Fact>(); for (var fact : facts) values.put(fact.key(), fact);
        return new Snapshot(1, 1000, values, Map.of(), Map.of());
    }
    @Test void ownConfirmedSettingsAreRenderedWithoutRawSourceOrPrivateRelations() {
        var relation = new Fact(new Key(own, Kind.RELATION, "B", "relationship"), "사적인 관계", Evidence.EXPLICIT, "private-source", 1000, 0, 1);
        var state = snapshot(fact(own, Kind.NAME, "preferred", "민수", 0), fact(own, Kind.SPEECH_AGREEMENT, "casual", "REFUSED", 0),
                fact(own, Kind.AVOID_JOKE, "all", "AVOID", 0), relation);
        String view = DiscordPersonalSettings.describe(own, state, 2000);
        assertTrue(view.contains("호칭: 민수")); assertTrue(view.contains("반말 거절")); assertTrue(view.contains("장난 중단"));
        assertFalse(view.contains("사적인 관계")); assertFalse(view.contains("private-source")); assertTrue(view.length() < 1000);
    }
    @Test void otherUserGuildCharacterExpiredAndUnrecognizedLabelsCannotAppear() {
        var state = snapshot(fact(new Subject("guild", "herry", "B"), Kind.NAME, "preferred", "타인", 0),
                fact(new Subject("other-guild", "herry", "A"), Kind.NAME, "preferred", "다른서버", 0),
                fact(new Subject("guild", "other-character", "A"), Kind.NAME, "preferred", "다른캐릭터", 0),
                fact(own, Kind.NAME, "preferred", "만료된이름", 1500), fact(own, Kind.NAME, "unrecognized", "잘못된호칭", 0));
        String view = DiscordPersonalSettings.describe(own, state, 2000);
        assertTrue(view.contains("저장한 호칭 없음")); assertTrue(view.contains("기본 존댓말"));
        for (String secret : java.util.List.of("타인", "다른서버", "다른캐릭터", "만료된이름", "잘못된호칭")) assertFalse(view.contains(secret));
    }
    @Test void deletionAndCorrectionFloorsSuppressOtherwisePresentFacts() {
        var name = fact(own, Kind.NAME, "preferred", "삭제된이름", 0);
        var joke = fact(own, Kind.AVOID_JOKE, "all", "AVOID", 0);
        var state = new Snapshot(2, 1000, Map.of(name.key(), name, joke.key(), joke), Map.of(), Map.of(name.key(), 2L, joke.key(), 2L));
        String view = DiscordPersonalSettings.describe(own, state, 2000);
        assertTrue(view.contains("저장한 호칭 없음")); assertFalse(view.contains("장난 중단"));
        var deleted = new Snapshot(2, 1000, Map.of(name.key(), name), Map.of(own, 2L), Map.of());
        assertFalse(DiscordPersonalSettings.describe(own, deleted, 2000).contains("삭제된이름"));
    }
    @Test void malformedStoredNameAndSettingValuesAreNotEchoed() {
        var state = snapshot(fact(own, Kind.NAME, "preferred", "@everyone\n민수", 0),
                fact(own, Kind.SPEECH_AGREEMENT, "casual", "private-speech-value", 0), fact(own, Kind.AVOID_JOKE, "all", "private-joke-value", 0));
        String view = DiscordPersonalSettings.describe(own, state, 2000);
        assertTrue(view.contains("호칭 확인 필요")); assertTrue(view.contains("설정 확인 필요"));
        assertFalse(view.contains("@everyone")); assertFalse(view.contains("private-"));
    }
}
