package com.wabbit.ultimate.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Local backups and "cloud" sync through a folder that the person's own cloud service (Dropbox,
 * OneDrive, iCloud Drive, Google Drive...) keeps in sync. No Wabbit server, no account.
 */
public final class SyncManager {
    private SyncManager() {}

    public static final String SYNC_FILE = "wabbit-ultimate-sync.json";
    public static final int KEEP_BACKUPS = 10;
    /** Settings that stay on this machine. */
    private static final Set<String> EXCLUDED = Set.of("syncFolder", "syncLastTime", "spotifyRefreshToken");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public record Remote(String device, long savedAt, JsonObject config) {}

    // ----- backups -----

    public static String backup(Path configFile, Path dir) throws IOException {
        Files.createDirectories(dir);
        String name = "wabbit-" + LocalDateTime.now().format(STAMP) + ".json";
        Files.copy(configFile, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        List<String> all = listBackups(dir);
        for (int i = KEEP_BACKUPS; i < all.size(); i++) Files.deleteIfExists(dir.resolve(all.get(i)));
        return name;
    }

    /** Newest first. */
    public static List<String> listBackups(Path dir) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("wabbit-") && n.endsWith(".json"))
                    .forEach(out::add);
        } catch (IOException ignored) {
            // an unreadable folder just shows no backups
        }
        out.sort(Comparator.reverseOrder());
        return out;
    }

    public static void restore(Path backupFile, Path configFile) throws IOException {
        JsonObject backup = JsonParser.parseString(Files.readString(backupFile, StandardCharsets.UTF_8)).getAsJsonObject(); // refuse corrupt files
        // settings that belong to this machine (sync folder, Spotify login) stay as they are now
        if (Files.exists(configFile)) {
            JsonObject now = JsonParser.parseString(Files.readString(configFile, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String k : EXCLUDED) {
                if (now.has(k)) backup.add(k, now.get(k));
                else backup.remove(k);
            }
        }
        Files.writeString(configFile, GSON.toJson(backup), StandardCharsets.UTF_8);
    }

    // ----- sync folder -----

    public static void push(Path configFile, Path folder, String device, long now) throws IOException {
        JsonObject cfg = JsonParser.parseString(Files.readString(configFile, StandardCharsets.UTF_8)).getAsJsonObject();
        for (String k : EXCLUDED) cfg.remove(k);
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("device", device);
        wrapper.addProperty("savedAt", now);
        wrapper.add("config", cfg);
        Files.createDirectories(folder);
        Path tmp = folder.resolve(SYNC_FILE + ".tmp");
        Files.writeString(tmp, GSON.toJson(wrapper), StandardCharsets.UTF_8);
        Files.move(tmp, folder.resolve(SYNC_FILE), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Returns null if there is no sync file in the folder yet. */
    public static Remote readRemote(Path folder) throws IOException {
        Path f = folder.resolve(SYNC_FILE);
        if (!Files.exists(f)) return null;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            return new Remote(o.get("device").getAsString(), o.get("savedAt").getAsLong(), o.getAsJsonObject("config"));
        } catch (RuntimeException e) {
            throw new IOException("The sync file in that folder is damaged.");
        }
    }

    /** Backs up this machine's settings first, then overlays the cloud copy. Returns the backup name. */
    public static String pull(Path configFile, Path backupDir, Path folder) throws IOException {
        Remote r = readRemote(folder);
        if (r == null) throw new IOException("There is no Wabbit sync file in that folder yet. Push from the other PC first.");
        String backup = backup(configFile, backupDir);
        JsonObject local = JsonParser.parseString(Files.readString(configFile, StandardCharsets.UTF_8)).getAsJsonObject();
        for (Map.Entry<String, com.google.gson.JsonElement> e : r.config().entrySet()) {
            if (!EXCLUDED.contains(e.getKey())) local.add(e.getKey(), e.getValue());
        }
        Files.writeString(configFile, GSON.toJson(local), StandardCharsets.UTF_8);
        return backup;
    }
}
