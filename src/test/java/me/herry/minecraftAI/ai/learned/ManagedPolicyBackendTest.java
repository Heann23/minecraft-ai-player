package me.herry.minecraftAI.ai.learned;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Process/state orchestration fixtures only. Does not launch Python, train weights or start Paper. */
class ManagedPolicyBackendTest {
    @TempDir Path root;
    private final String brain = UUID.randomUUID().toString();

    private ManagedPolicyBackend.Settings settings() throws Exception {
        Path script = root.resolve("trainer.py");
        Files.writeString(script, "fixture only");
        return new ManagedPolicyBackend.Settings("fixture-python", script, root.resolve("catalog"), root.resolve("runs"),
                root.resolve("channel"), "", false, 64, 30);
    }

    private Process ready(List<String> command, Path log) throws java.io.IOException {
        Path output = Path.of(command.get(command.indexOf("--output") + 1));
        Files.createDirectories(output);
        Files.writeString(output.resolve("run.json"), "{}");
        Files.writeString(output.resolve("runtime-session.json"), "{\"payload\":{\"schema\":\"minecraft-brain-runtime-session/v1\","
                + "\"brainId\":\"" + brain + "\",\"learningEnabled\":false}}");
        return new Child();
    }

    @Test void restartRestoresTheSameSavedSessionAndUsesANewTransportDirectory() throws Exception {
        var settings = settings();
        var commands = new AtomicReference<List<String>>();
        Path firstChannel;
        try (var first = new ManagedPolicyBackend(settings, (command, log) -> {
            commands.set(command); return ready(command, log);
        })) {
            assertEquals(brain, first.start().get(5, TimeUnit.SECONDS));
            assertTrue(commands.get().contains("--catalog"));
            assertTrue(commands.get().contains("--inference-only"));
            firstChannel = first.channel();
        }
        try (var second = new ManagedPolicyBackend(settings, (command, log) -> {
            commands.set(command); return ready(command, log);
        })) {
            assertEquals(brain, second.start().get(5, TimeUnit.SECONDS));
            assertNotEquals(firstChannel, second.channel());
            assertTrue(commands.get().contains("--restore-session"));
            assertFalse(commands.get().contains("--catalog"));
            Path previous = Path.of(commands.get().get(commands.get().indexOf("--restore-session") + 1));
            assertTrue(Files.isRegularFile(previous));
        }
    }

    @Test void corruptPointerNeverStartsANewModelOrOverwritesTheOriginal() throws Exception {
        var settings = settings();
        Files.createDirectories(settings.runs());
        Path pointer = settings.runs().resolve("current-session.json");
        Files.writeString(pointer, "corrupt original");
        var launches = new AtomicInteger();
        try (var backend = new ManagedPolicyBackend(settings, (command, log) -> {
            launches.incrementAndGet(); return ready(command, log);
        })) {
            assertThrows(ExecutionException.class, () -> backend.start().get(5, TimeUnit.SECONDS));
            assertEquals(0, launches.get());
            assertEquals("corrupt original", Files.readString(pointer));
        }
    }

    @Test void anotherOwnerCannotLaunchOrStopTheExistingChild() throws Exception {
        var settings = settings();
        var child = new AtomicReference<Child>();
        try (var first = new ManagedPolicyBackend(settings, (command, log) -> {
            Child process = (Child) ready(command, log); child.set(process); return process;
        })) {
            first.start().get(5, TimeUnit.SECONDS);
            try (var second = new ManagedPolicyBackend(settings, (command, log) -> fail("must not launch a second owner"))) {
                assertThrows(ExecutionException.class, () -> second.start().get(5, TimeUnit.SECONDS));
                assertTrue(child.get().isAlive());
            }
            assertTrue(child.get().isAlive());
        }
        assertFalse(child.get().isAlive());
    }

    private static final class Child extends Process {
        private volatile boolean alive = true;
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { alive = false; return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { alive = false; return true; }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { alive = false; }
        @Override public boolean isAlive() { return alive; }
    }
}
