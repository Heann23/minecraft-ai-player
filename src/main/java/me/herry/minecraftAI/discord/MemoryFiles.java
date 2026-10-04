package me.herry.minecraftAI.discord;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static me.herry.minecraftAI.discord.DiscordMemory.Snapshot;

/** Used only by the memory worker. Existing files survive unsuccessful writes. */
final class MemoryFiles {
    private MemoryFiles() {}
    static Snapshot read(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > MemoryCodec.MAX_BYTES) throw new IOException("memory file size/type");
        return MemoryCodec.decode(Files.readAllBytes(path));
    }
    static void write(Path path, Snapshot snapshot) throws IOException {
        Files.createDirectories(path.getParent());
        byte[] bytes = MemoryCodec.encode(snapshot);
        Path temp = Files.createTempFile(path.getParent(), "memory-", ".tmp");
        try {
            Files.write(temp, bytes);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            read(temp);
            // If atomic replacement is unavailable, fail and keep the previous recovery point.
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    static List<Path> backups(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (var paths = Files.list(directory)) {
            return paths.filter(p -> p.getFileName().toString().matches("(periodic|daily)-[0-9]+-[0-9]+\\.mem"))
                    .sorted(java.util.Comparator.comparingLong(MemoryFiles::revision).reversed()).toList();
        }
    }
    private static long revision(Path path) {
        String[] parts = path.getFileName().toString().replace(".mem", "").split("-");
        try { return Long.parseLong(parts[2]); } catch (NumberFormatException e) { return -1; }
    }
}
