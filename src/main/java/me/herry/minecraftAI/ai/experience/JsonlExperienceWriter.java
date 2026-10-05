package me.herry.minecraftAI.ai.experience;

import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 기록을 에피소드마다 파일 하나(JSONL)로 남긴다: <폴더>/<AI 이름>/<에피소드 ID>.jsonl
 *
 * 파일 쓰기는 전용 스레드 하나가 한다. 메인 스레드는 줄을 대기열에 넣기만 하고, 대기열이 꽉 차 있으면 그 줄을 버린다.
 * 폴더가 정한 크기를 넘으면 더 남기지 않는다. 쓰다가 오류가 나면 그 에피소드의 나머지를 버리고 경고를 한 번 남긴다.
 * 어느 경우에도 예외가 밖으로 나가지 않는다.
 */
public final class JsonlExperienceWriter implements ExperienceSink, AutoCloseable {
    private static final int QUEUE_CAPACITY = 4096;
    // 이만큼 쌓이면, 또는 대기열이 비면 디스크로 내보낸다.
    private static final int FLUSH_EVERY = 64;
    private static final long IDLE_POLL_MILLIS = 500L;

    // 대기열에 들어가는 것 한 건. json 이 null 이면 "그 에피소드를 닫는다"는 뜻이다.
    private record Entry(String ai, String episodeId, @Nullable String json) {
    }

    private static final Entry STOP = new Entry("", "", null);

    private final Path root;
    private final long maxBytes;
    private final Consumer<String> warn;
    private final BlockingQueue<Entry> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread thread;
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean accepting = true;
    private volatile boolean full;

    // 아래는 쓰는 스레드만 건드린다.
    private final Map<String, BufferedWriter> open = new HashMap<>();
    private long bytes;
    private int sinceFlush;
    private boolean warnedIo;

    /**
     * @param root     기록을 둘 폴더 (없으면 만든다)
     * @param maxBytes 폴더에 둘 수 있는 총 크기. 넘으면 더 남기지 않는다.
     * @param warn     사람에게 알릴 일이 생겼을 때 그 내용을 받는다 (쓰는 스레드에서 불린다)
     */
    public JsonlExperienceWriter(Path root, long maxBytes, Consumer<String> warn) {
        this.root = root;
        this.maxBytes = maxBytes;
        this.warn = warn;
        this.thread = new Thread(this::run, "MinecraftAI-experience");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    @Override
    public void append(String ai, String episodeId, Object record) {
        if (!accepting || full) {
            dropped.incrementAndGet();
            return;
        }
        String json;
        try {
            json = JsonLines.toJson(record);
        } catch (RuntimeException e) {
            dropped.incrementAndGet();
            return;
        }
        if (!queue.offer(new Entry(ai, episodeId, json))) dropped.incrementAndGet();
    }

    @Override
    public void endEpisode(String ai, String episodeId) {
        if (!accepting) return;
        // 닫는 표시를 넣지 못해도 파일은 close() 때 닫힌다.
        queue.offer(new Entry(ai, episodeId, null));
    }

    // 지금까지 파일에 쓴 줄 수
    public long writtenCount() {
        return written.get();
    }

    // 대기열이 찼거나, 크기 제한을 넘었거나, 쓰다가 오류가 나서 버린 줄 수
    public long droppedCount() {
        return dropped.get();
    }

    // 크기 제한을 넘어서 더 남기지 않는 중인지
    public boolean isFull() {
        return full;
    }

    /**
     * 남은 것을 다 쓰고 파일을 닫는다. 서버가 꺼질 때 부른다. 정한 시간 안에 끝나지 않으면 기다리지 않고 돌아온다.
     */
    public void close(long timeoutMillis) {
        accepting = false;
        try {
            // 대기열이 꽉 차 있어도 멈추라는 표시는 넣어야 하므로 자리가 날 때까지 잠깐 기다린다.
            if (!queue.offer(STOP, timeoutMillis, TimeUnit.MILLISECONDS)) thread.interrupt();
            thread.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        close(5000L);
    }

    private void run() {
        bytes = existingBytes();
        if (bytes >= maxBytes) reportFull();
        try {
            while (true) {
                Entry entry = queue.poll(IDLE_POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (entry == null) {
                    flushAll();
                    continue;
                }
                if (entry == STOP) break;
                if (entry.json() == null) closeEpisode(key(entry));
                else writeLine(entry);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeAll();
        }
    }

    private void writeLine(Entry entry) {
        if (full) {
            dropped.incrementAndGet();
            return;
        }
        String key = key(entry);
        try {
            BufferedWriter writer = open.get(key);
            if (writer == null) {
                Path file = root.resolve(safe(entry.ai())).resolve(safe(entry.episodeId()) + ".jsonl");
                Files.createDirectories(file.getParent());
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                open.put(key, writer);
            }
            writer.write(entry.json());
            writer.write('\n');
            written.incrementAndGet();
            // 한글 등은 한 글자가 여러 바이트지만, 제한은 어림값이면 충분하다.
            bytes += entry.json().length() + 1L;
            if (++sinceFlush >= FLUSH_EVERY) flushAll();
            if (bytes >= maxBytes) {
                flushAll();
                reportFull();
            }
        } catch (IOException e) {
            dropped.incrementAndGet();
            warnIo("write", e);
            closeEpisode(key);
        }
    }

    private void reportFull() {
        full = true;
        warn.accept("Training data reached the size limit (" + maxBytes / (1024 * 1024) + " MB). Nothing more is recorded until "
                + "old files are removed from " + root + " and the server is restarted.");
    }

    private void flushAll() {
        sinceFlush = 0;
        for (BufferedWriter writer : open.values()) {
            try {
                writer.flush();
            } catch (IOException e) {
                // 닫을 때 다시 시도한다. 그때도 안 되면 그 파일의 끝부분만 잃는다.
                warnIo("flush", e);
            }
        }
    }

    private void closeEpisode(String key) {
        BufferedWriter writer = open.remove(key);
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException e) {
            warnIo("close", e);
        }
    }

    // 디스크가 찼거나 폴더를 쓸 수 없을 때 같은 경고가 줄마다 나오지 않게 한 번만 알린다.
    private void warnIo(String doing, IOException e) {
        if (warnedIo) return;
        warnedIo = true;
        warn.accept("Could not " + doing + " training data in " + root + ": " + e);
    }

    private void closeAll() {
        for (String key : open.keySet().toArray(new String[0])) closeEpisode(key);
    }

    // 전에 남긴 것까지 합쳐서 크기 제한을 본다.
    private long existingBytes() {
        if (!Files.isDirectory(root)) return 0L;
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String key(Entry entry) {
        return entry.ai() + '/' + entry.episodeId();
    }

    // 파일 이름에 쓸 수 없는 글자를 바꾼다. AI 이름과 에피소드 ID 는 원래 영숫자와 밑줄, 붙임표뿐이다.
    private static String safe(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }
}
