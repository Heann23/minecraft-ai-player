package me.herry.minecraftAI.ai.experience;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 기록을 파일로 쓰는 쪽: 에피소드마다 파일 하나, 크기 제한, 닫은 뒤에는 받지 않기.
 */
class JsonlExperienceWriterTest {
    private static final long MEGABYTE = 1024L * 1024L;

    @TempDir
    Path dir;

    private final List<String> warnings = new CopyOnWriteArrayList<>();

    @Test
    void writesOneFilePerEpisodeInOrder() throws IOException {
        JsonlExperienceWriter writer = new JsonlExperienceWriter(dir, MEGABYTE, warnings::add);
        writer.append("Bot", "bot-1", Map.of("n", 1));
        writer.append("Bot", "bot-1", Map.of("n", 2));
        writer.endEpisode("Bot", "bot-1");
        writer.append("Bot", "bot-2", Map.of("n", 3));
        writer.close();

        // AI 이름은 소문자 폴더가 된다.
        assertEquals(List.of("{\"n\":1}", "{\"n\":2}"), Files.readAllLines(dir.resolve("bot").resolve("bot-1.jsonl")));
        assertEquals(List.of("{\"n\":3}"), Files.readAllLines(dir.resolve("bot").resolve("bot-2.jsonl")));
        assertEquals(3L, writer.writtenCount());
        assertEquals(0L, writer.droppedCount());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    // 닫은 뒤에 들어온 것은 버린다 (서버가 꺼지는 중에 파일을 다시 열지 않는다).
    @Test
    void dropsAfterClose() throws IOException {
        JsonlExperienceWriter writer = new JsonlExperienceWriter(dir, MEGABYTE, warnings::add);
        writer.append("Bot", "bot-1", Map.of("n", 1));
        writer.close();
        writer.append("Bot", "bot-1", Map.of("n", 2));
        writer.endEpisode("Bot", "bot-1");
        assertEquals(List.of("{\"n\":1}"), Files.readAllLines(dir.resolve("bot").resolve("bot-1.jsonl")));
        assertEquals(1L, writer.droppedCount());
    }

    // 폴더가 정한 크기에 닿으면 더 쓰지 않고 한 번 알린다.
    @Test
    void stopsAtTheSizeLimit() throws IOException {
        String text = "x".repeat(100);
        JsonlExperienceWriter writer = new JsonlExperienceWriter(dir, 250L, warnings::add);
        for (int i = 0; i < 10; i++) writer.append("Bot", "bot-1", Map.of("text", text));
        writer.close();

        // 한 줄이 110바이트 남짓이라 세 줄째에 제한(250)을 넘는다. 그 뒤는 버린다.
        assertEquals(3, Files.readAllLines(dir.resolve("bot").resolve("bot-1.jsonl")).size());
        assertEquals(3L, writer.writtenCount());
        assertEquals(7L, writer.droppedCount());
        assertTrue(writer.isFull());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("size limit"), warnings.get(0));
    }

    // 전에 남긴 파일의 크기도 제한에 넣는다. 이미 넘어 있으면 처음부터 쓰지 않는다.
    @Test
    void existingFilesCountTowardTheLimit() throws IOException {
        Files.createDirectories(dir.resolve("bot"));
        Files.writeString(dir.resolve("bot").resolve("old.jsonl"), "y".repeat(300));
        JsonlExperienceWriter writer = new JsonlExperienceWriter(dir, 250L, warnings::add);
        writer.append("Bot", "bot-9", Map.of("n", 1));
        writer.append("Bot", "bot-9", Map.of("n", 2));
        writer.close();

        assertFalse(Files.exists(dir.resolve("bot").resolve("bot-9.jsonl")));
        assertTrue(writer.isFull());
        assertEquals(0L, writer.writtenCount());
        assertEquals(1, warnings.size());
    }

    // 값이 아닌 것이 섞인 기록은 버리고, 다음 기록은 계속 받는다.
    @Test
    void unwritableRecordIsDroppedWithoutBreakingTheRest() throws IOException {
        JsonlExperienceWriter writer = new JsonlExperienceWriter(dir, MEGABYTE, warnings::add);
        writer.append("Bot", "bot-1", Map.of("bad", new Object()));
        writer.append("Bot", "bot-1", Map.of("n", 1));
        writer.close();
        assertEquals(List.of("{\"n\":1}"), Files.readAllLines(dir.resolve("bot").resolve("bot-1.jsonl")));
        assertEquals(1L, writer.droppedCount());
    }

    // 파일 이름으로 쓸 수 없는 글자가 들어와도 정한 폴더 밖으로 나가지 않는다.
    @Test
    void namesCannotEscapeTheFolder() throws IOException {
        JsonlExperienceWriter writer = new JsonlExperienceWriter(dir, MEGABYTE, warnings::add);
        writer.append("../Evil", "..\\..\\x", Map.of("n", 1));
        writer.close();
        assertTrue(Files.exists(dir.resolve("___evil").resolve("______x.jsonl")));
        assertFalse(Files.exists(dir.getParent().resolve("Evil")));
    }
}
