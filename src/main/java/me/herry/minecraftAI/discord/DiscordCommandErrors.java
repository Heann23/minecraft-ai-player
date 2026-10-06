package me.herry.minecraftAI.discord;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;

/**
 * User-facing text for failed /herry commands. Chosen only by exception type and the code-owned messages below;
 * the text of any other exception, which may carry paths or provider details, is never shown.
 */
final class DiscordCommandErrors {
    static final String RESTORE_IN_PROGRESS = "memory restore in progress";
    static final String RESTORE_UNAVAILABLE = "Discord restore unavailable";
    static final String UNKNOWN_BACKUP = "unknown backup";
    static final String SESSION_CLOSED = "Discord session closed";
    static final String AUDIO_CLOSED = "Discord audio closed";
    static final String MEMORY_CLOSED = "memory closed";
    private static final String GENERIC = "처리하지 못했어요. 연결이나 기억 저장 상태를 확인해 주세요.";
    private static final String CLOSING = "해리가 종료되는 중이거나 아직 준비되지 않았어요. 잠시 뒤 다시 시도해 주세요.";
    private DiscordCommandErrors() {}

    /** A command whose own input was rejected before any work started. */
    static String invalidInput(String subcommand) {
        return switch (subcommand == null ? "" : subcommand) {
            case "name" -> "입력값을 확인해 주세요. 호칭은 한글·영문 1~20자로 입력해요.";
            case "restore" -> "백업 식별자를 확인해 주세요. /herry backups로 목록을 볼 수 있어요.";
            case "forget" -> "지울 범위를 확인해 주세요. 전체·이름·말투·장난 중에서 골라요.";
            default -> "입력값을 확인해 주세요.";
        };
    }
    /** An exception thrown while the command was being started. Only argument problems are blamed on the input. */
    static String synchronous(String subcommand, RuntimeException thrown) {
        return thrown instanceof IllegalArgumentException || thrown instanceof NullPointerException ? invalidInput(subcommand) : failure(thrown);
    }
    /** A command whose asynchronous work failed. */
    static String failure(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; depth < 5 && cause != null && (cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null; depth++) cause = cause.getCause();
        if (cause instanceof RejectedExecutionException) return CLOSING;
        if (cause instanceof IOException && UNKNOWN_BACKUP.equals(cause.getMessage()))
            return "그 백업을 찾지 못했어요. /herry backups로 목록을 확인해 주세요.";
        if (cause instanceof IllegalStateException) {
            String message = cause.getMessage();
            if (RESTORE_IN_PROGRESS.equals(message)) return "기억 복원이 진행 중이에요. 끝난 뒤 다시 시도해 주세요.";
            if (RESTORE_UNAVAILABLE.equals(message)) return "지금은 복원을 시작할 수 없어요. 이미 복원 중이거나 종료되는 중이에요.";
            if (SESSION_CLOSED.equals(message) || AUDIO_CLOSED.equals(message) || MEMORY_CLOSED.equals(message)) return CLOSING;
        }
        return GENERIC;
    }
}
