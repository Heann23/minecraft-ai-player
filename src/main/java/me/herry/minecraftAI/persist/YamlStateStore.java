package me.herry.minecraftAI.persist;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AI 한 명당 YAML 파일 하나(plugins/MinecraftAI/players/이름.yml)로 저장한다.
 * 저장할 양이 수십 KB 이하이고, 사람이 열어서 확인하거나 고칠 수 있다는 점 때문에 YAML 을 골랐다.
 */
public final class YamlStateStore implements AIStateStore {
    private static final String EXTENSION = ".yml";

    private final File directory;
    private final Logger logger;
    // 디스크에 쓰는 일은 다른 스레드에서 실행될 수 있어서, 파일을 건드리는 동안에는 이 잠금을 잡는다.
    private final Object ioLock = new Object();
    // AI 마다 가장 최근에 요청된 저장(또는 삭제)의 번호. 이보다 오래된 저장은 늦게 실행되더라도 버린다.
    private final Map<String, Long> latestRequest = new HashMap<>();
    private long requests;

    public YamlStateStore(File directory, Logger logger) {
        this.directory = directory;
        this.logger = logger;
    }

    @Override
    public List<AISnapshot> loadAll() {
        List<AISnapshot> snapshots = new ArrayList<>();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(EXTENSION));
        if (files == null) return snapshots;
        for (File file : files) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
                AISnapshot snapshot = SnapshotCodec.fromMap(toMap(yaml));
                if (snapshot != null) snapshots.add(snapshot);
                else logger.warning("Skipped unreadable AI state file " + file.getName());
            } catch (IOException | InvalidConfigurationException e) {
                logger.log(Level.WARNING, "Could not read AI state file " + file.getName(), e);
            }
        }
        return snapshots;
    }

    @Override
    public Runnable prepareSave(AISnapshot snapshot) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, Object> entry : SnapshotCodec.toMap(snapshot).entrySet()) yaml.set(entry.getKey(), entry.getValue());
        String text = yaml.saveToString();
        File file = fileOf(snapshot.name);
        long request = newRequest(file.getName());
        return () -> {
            synchronized (ioLock) {
                // 그사이에 더 새로운 저장이 준비됐거나 AI 가 제거됐으면, 늦게 도착한 이 내용은 쓰지 않는다.
                if (latestRequest.getOrDefault(file.getName(), 0L) != request) return;
                write(file.toPath(), text);
            }
        };
    }

    @Override
    public void delete(String name) {
        File file = fileOf(name);
        synchronized (ioLock) {
            // 아직 실행되지 않은 저장이 파일을 되살리지 못하게 번호를 올려 둔다.
            latestRequest.put(file.getName(), ++requests);
            try {
                Files.deleteIfExists(file.toPath());
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not delete AI state of " + name, e);
            }
        }
    }

    private long newRequest(String key) {
        synchronized (ioLock) {
            long request = ++requests;
            latestRequest.put(key, request);
            return request;
        }
    }

    // 쓰는 도중에 서버가 꺼져도 기존 파일이 망가지지 않도록, 임시 파일에 쓴 뒤 바꿔치기한다.
    private void write(Path target, String text) {
        try {
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not save AI state to " + target.getFileName(), e);
        }
    }

    private File fileOf(String name) {
        return new File(directory, name.toLowerCase(Locale.ROOT) + EXTENSION);
    }

    // YAML 의 하위 구역(ConfigurationSection)을 보통의 맵으로 바꾼다.
    private static Map<String, Object> toMap(ConfigurationSection section) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            map.put(key, value instanceof ConfigurationSection child ? toMap(child) : value);
        }
        return map;
    }
}
