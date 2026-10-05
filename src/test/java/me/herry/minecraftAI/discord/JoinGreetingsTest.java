package me.herry.minecraftAI.discord;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static me.herry.minecraftAI.discord.DiscordMemory.*;
import static org.junit.jupiter.api.Assertions.*;

class JoinGreetingsTest {
    private static final Subject A = new Subject("guild", "herry", "A");
    private Fact fact(Subject person, Kind kind, String value, long expires) {
        return new Fact(new Key(person, kind, "", "confirmed"), value, Evidence.EXPLICIT, "personal", 1000, expires, 1);
    }
    @Test void joinsWaitForSettlingSilenceAndIdleThenRespectReconnectCooldown() {
        var greetings = new JoinGreetings(JoinGreetings.Policy.defaults(true));
        greetings.participants(Set.of("A"), 1000);
        assertNull(greetings.claim(2999, 1000, false));
        assertNull(greetings.claim(3000, 1000, true));
        assertNull(greetings.claim(3500, 2000, false));
        assertEquals("A", greetings.claim(4000, 2000, false)); assertNull(greetings.claim(6000, 2000, false));
        greetings.participants(Set.of(), 6100); greetings.participants(Set.of("A"), 6200);
        assertNull(greetings.claim(10_000, 2000, false));
        greetings.participants(Set.of(), 604_000); greetings.participants(Set.of("A"), 604_001);
        assertEquals("A", greetings.claim(606_001, 2000, false));
    }
    @Test void longConversationDropsDelayedGreetingInsteadOfInterruptingLater() {
        var greetings = new JoinGreetings(JoinGreetings.Policy.defaults(true));
        greetings.participants(Set.of("A"), 1000); assertNull(greetings.claim(31_000, 1000, true));
        assertNull(greetings.claim(33_000, 1000, false));
    }
    @Test void leavesQuietAndDirectConversationRetirePendingGreetings() {
        var greetings = new JoinGreetings(JoinGreetings.Policy.defaults(true));
        greetings.participants(Set.of("A"), 1000); greetings.participants(Set.of(), 2000);
        assertNull(greetings.claim(4000, 1000, false));
        greetings.participants(Set.of("A"), 5000); greetings.clearPending(); assertNull(greetings.claim(8000, 1000, false));
        greetings.participants(Set.of(), 9000); greetings.participants(Set.of("A"), 10_000); greetings.dismiss("A", 11_000);
        assertNull(greetings.claim(15_000, 1000, false));
        greetings.participants(Set.of(), 16_000); greetings.participants(Set.of("A"), 17_000); assertNull(greetings.claim(20_000, 1000, false));
    }
    @Test void disabledAndEmptyChannelsNeverAdmitGreetings() {
        var greetings = new JoinGreetings(JoinGreetings.Policy.defaults(false));
        assertNull(greetings.claim(4000, 1000, false)); greetings.participants(Set.of("A"), 1000);
        assertNull(greetings.claim(4000, 1000, false));
    }
    @Test void greetingUsesOnlyThisPersonsConfirmedUnexpiredNameAndConsent() {
        assertEquals("안녕하세요! 해리예요. 같이 즐겁게 놀아요.", JoinGreetings.text(A, List.of(), 2000));
        assertEquals("민수님, 반가워요. 오늘도 같이 놀아요.", JoinGreetings.text(A, List.of(fact(A, Kind.NAME, "민수", 0)), 2000));
        var allowed = fact(A, Kind.SPEECH_AGREEMENT, "ALLOWED", 0);
        assertEquals("민수, 왔어? 오늘도 같이 놀자.", JoinGreetings.text(A, List.of(fact(A, Kind.NAME, "민수", 0), allowed), 2000));
        assertEquals("안녕하세요! 해리예요. 같이 즐겁게 놀아요.", JoinGreetings.text(A, List.of(
                fact(new Subject("guild", "herry", "B"), Kind.NAME, "수빈", 0),
                fact(new Subject("other", "herry", "A"), Kind.NAME, "다른이름", 0),
                fact(A, Kind.NAME, "만료", 1500), fact(A, Kind.SPEECH_AGREEMENT, "ALLOWED", 1500)), 2000));
        assertEquals("안녕하세요! 해리예요. 같이 즐겁게 놀아요.", JoinGreetings.text(A, List.of(allowed,
                fact(A, Kind.SPEECH_AGREEMENT, "REFUSED", 0), fact(A, Kind.NAME, "안녕\n다른지시", 0)), 2000));
    }
    @Test void oversizedMembershipCannotPartiallyQueueNewUsers() {
        var greetings = new JoinGreetings(JoinGreetings.Policy.defaults(true));
        greetings.participants(Set.of("A"), 1000);
        assertThrows(IllegalArgumentException.class, () -> greetings.participants(Set.of("1", "2", "3", "4", "5", "6", "7", "8", "9"), 1000));
        assertEquals("A", greetings.claim(3000, 1000, false));
    }
}
