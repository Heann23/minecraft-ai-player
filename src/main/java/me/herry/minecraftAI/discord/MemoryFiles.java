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
        if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MemoryCodec.MAX_BYTES) throw new IOException("memory file size/type");
        try (var input = Files.newInputStream(path, StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MemoryCodec.MAX_BYTES + 1);
            if (bytes.length > MemoryCodec.MAX_BYTES) throw new IOException("memory file size/type");
            return MemoryCodec.decode(bytes);
        }
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
            return paths.filter(p -> validBackupName(p.getFileName().toString()) && Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .sorted(java.util.Comparator.comparingLong(MemoryFiles::revision).reversed()).toList();
        }
    }
    static void requireBackupName(String name) {
        if (!validBackupName(name)) throw new IllegalArgumentException("backup identifier");
    }
    private static boolean validBackupName(String name) {
        if (name == null || !name.matches("(periodic|daily)-[0-9]{1,19}-[0-9]{1,19}\\.mem")) return false;
        String[] parts = name.substring(0, name.length() - 4).split("-");
        try { Long.parseLong(parts[1]); Long.parseLong(parts[2]); return true; }
        catch (NumberFormatException invalid) { return false; }
    }
    private static long revision(Path path) {
        String[] parts = path.getFileName().toString().replace(".mem", "").split("-");
        try { return Long.parseLong(parts[2]); } catch (NumberFormatException e) { return -1; }
    }
}
