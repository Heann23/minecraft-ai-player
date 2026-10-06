package me.herry.minecraftAI.discord;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

import static me.herry.minecraftAI.discord.DiscordMemory.*;

/** Bounded versioned binary format with a checksum; no Java object deserialization. */
final class MemoryCodec {
    static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAGIC = 0x48455252;
    private static final int SCHEMA = 1;

    static byte[] encode(Snapshot snapshot) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(body)) {
            out.writeInt(MAGIC); out.writeInt(SCHEMA);
            out.writeLong(snapshot.revision()); out.writeLong(snapshot.createdAt());
            out.writeInt(snapshot.facts().size());
            for (Fact fact : snapshot.facts().values()) {
                key(out, fact.key());
                out.writeUTF(fact.value()); out.writeUTF(fact.evidence().name()); out.writeUTF(fact.sourceId());
                out.writeLong(fact.recordedAt()); out.writeLong(fact.expiresAt()); out.writeLong(fact.revision());
            }
            out.writeInt(snapshot.deleted().size());
            for (Map.Entry<Subject, Long> entry : snapshot.deleted().entrySet()) {
                subject(out, entry.getKey()); out.writeLong(entry.getValue());
            }
            out.writeInt(snapshot.erasedKeys().size());
            for (Map.Entry<Key, Long> entry : snapshot.erasedKeys().entrySet()) {
                key(out, entry.getKey()); out.writeLong(entry.getValue());
            }
        }
        byte[] bytes = body.toByteArray();
        if (bytes.length + 32 > MAX_BYTES) throw new IOException("memory size limit");
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.writeBytes(digest(bytes)); result.writeBytes(bytes);
        return result.toByteArray();
    }

    static Snapshot decode(byte[] bytes) throws IOException {
        if (bytes.length < 60 || bytes.length > MAX_BYTES) throw new IOException("memory size");
        byte[] expected = java.util.Arrays.copyOfRange(bytes, 0, 32);
        byte[] body = java.util.Arrays.copyOfRange(bytes, 32, bytes.length);
        if (!MessageDigest.isEqual(expected, digest(body))) throw new IOException("memory checksum");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            if (in.readInt() != MAGIC || in.readInt() != SCHEMA) throw new IOException("memory schema");
            long revision = in.readLong(), created = in.readLong();
            Map<Key, Fact> facts = new HashMap<>();
            int count = count(in);
            for (int i = 0; i < count; i++) {
                Key key = key(in);
                Fact fact = new Fact(key, in.readUTF(), Evidence.valueOf(in.readUTF()), in.readUTF(),
                        in.readLong(), in.readLong(), in.readLong());
                if (facts.put(key, fact) != null) throw new IOException("duplicate memory");
            }
            Map<Subject, Long> deleted = new HashMap<>();
            count = count(in);
            for (int i = 0; i < count; i++) if (deleted.put(subject(in), in.readLong()) != null) throw new IOException("duplicate deletion");
            Map<Key, Long> erasedKeys = new HashMap<>();
            count = count(in);
            for (int i = 0; i < count; i++) if (erasedKeys.put(key(in), in.readLong()) != null) throw new IOException("duplicate key deletion");
            if (in.available() != 0) throw new IOException("trailing memory data");
            return new Snapshot(revision, created, facts, deleted, erasedKeys);
        } catch (IllegalArgumentException e) { throw new IOException("invalid memory fields", e); }
    }

    private static int count(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 10_000) throw new IOException("memory entry count");
        return count;
    }
    private static Subject subject(DataInputStream in) throws IOException { return new Subject(in.readUTF(), in.readUTF(), in.readUTF()); }
    private static Key key(DataInputStream in) throws IOException {
        return new Key(subject(in), Kind.valueOf(in.readUTF()), in.readUTF(), in.readUTF());
    }
    private static void key(DataOutputStream out, Key key) throws IOException {
        subject(out, key.subject()); out.writeUTF(key.kind().name()); out.writeUTF(key.otherUserId()); out.writeUTF(key.label());
    }
    private static void subject(DataOutputStream out, Subject subject) throws IOException {
        out.writeUTF(subject.guildId()); out.writeUTF(subject.characterId()); out.writeUTF(subject.userId());
    }
    private static byte[] digest(byte[] body) {
        try { return MessageDigest.getInstance("SHA-256").digest(body); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
