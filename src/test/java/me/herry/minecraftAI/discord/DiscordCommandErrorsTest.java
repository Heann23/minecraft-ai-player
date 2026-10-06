package me.herry.minecraftAI.discord;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordCommandErrorsTest {
    private static final String GENERIC = "처리하지 못했어요. 연결이나 기억 저장 상태를 확인해 주세요.";
    @Test void knownFailuresGetSpecificTextEvenWhenWrapped() {
        assertTrue(DiscordCommandErrors.failure(new IllegalStateException(DiscordCommandErrors.RESTORE_IN_PROGRESS)).contains("복원이 진행 중"));
        assertTrue(DiscordCommandErrors.failure(new CompletionException(new IllegalStateException(DiscordCommandErrors.RESTORE_IN_PROGRESS))).contains("복원이 진행 중"));
        assertTrue(DiscordCommandErrors.failure(new IllegalStateException(DiscordCommandErrors.RESTORE_UNAVAILABLE)).contains("복원을 시작할 수 없어요"));
        assertTrue(DiscordCommandErrors.failure(new CompletionException(new ExecutionException(new IOException(DiscordCommandErrors.UNKNOWN_BACKUP)))).contains("백업을 찾지 못했어요"));
        for (String closed : java.util.List.of(DiscordCommandErrors.SESSION_CLOSED, DiscordCommandErrors.AUDIO_CLOSED, DiscordCommandErrors.MEMORY_CLOSED))
            assertTrue(DiscordCommandErrors.failure(new IllegalStateException(closed)).contains("종료되는 중"), closed);
        assertTrue(DiscordCommandErrors.failure(new RejectedExecutionException("queue full")).contains("종료되는 중"));
    }
    @Test void unknownFailuresNeverEchoExceptionText() {
        for (Throwable error : java.util.List.of(new IllegalStateException("secret-token path C:\\server\\data"), new IOException("disk full at /srv/secret"),
                new RuntimeException("provider said secret"), new CompletionException(new IllegalArgumentException("secret"))))
            assertEquals(GENERIC, DiscordCommandErrors.failure(error));
        assertEquals(GENERIC, DiscordCommandErrors.failure(null));
    }
    @Test void invalidInputHintMatchesTheCommand() {
        assertTrue(DiscordCommandErrors.invalidInput("name").contains("호칭"));
        assertTrue(DiscordCommandErrors.invalidInput("restore").contains("/herry backups"));
        assertFalse(DiscordCommandErrors.invalidInput("restore").contains("호칭"));
        assertTrue(DiscordCommandErrors.invalidInput("forget").contains("범위"));
        assertEquals("입력값을 확인해 주세요.", DiscordCommandErrors.invalidInput("speech"));
        assertEquals("입력값을 확인해 주세요.", DiscordCommandErrors.invalidInput(null));
    }
    @Test void onlyArgumentProblemsAreBlamedOnTheInput() {
        assertEquals(DiscordCommandErrors.invalidInput("restore"), DiscordCommandErrors.synchronous("restore", new IllegalArgumentException("backup identifier")));
        assertEquals(DiscordCommandErrors.invalidInput("name"), DiscordCommandErrors.synchronous("name", new NullPointerException()));
        assertTrue(DiscordCommandErrors.synchronous("restore", new IllegalStateException(DiscordCommandErrors.RESTORE_IN_PROGRESS)).contains("복원이 진행 중"));
        assertEquals(GENERIC, DiscordCommandErrors.synchronous("backup", new IllegalStateException("something else")));
    }
    @Test void productionThrowSitesUseTheSharedMessages() {
        var invalid = assertThrows(IllegalArgumentException.class, () -> MemoryFiles.requireBackupName("../escape.mem"));
        assertEquals(DiscordCommandErrors.invalidInput("restore"), DiscordCommandErrors.synchronous("restore", invalid));
    }
}
