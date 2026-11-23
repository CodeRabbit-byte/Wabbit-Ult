package com.wabbit.ultimate.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class ConfigManager {
    private static final Logger LOG = LoggerFactory.getLogger("WabbitUltimate");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("wabbit-ultimate.json");
    private static WabbitConfig config = new WabbitConfig();

    private ConfigManager() {}

    public static Path file() {
        return FILE;
    }

    public static Path backupDir() {
        return FILE.getParent().resolve("wabbit-backups");
    }

    public static WabbitConfig get() {
        return config;
    }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                String json = Files.readString(FILE, StandardCharsets.UTF_8);
                WabbitConfig loaded = GSON.fromJson(json, WabbitConfig.class);
                if (loaded != null) config = loaded;
            } else {
                save();
            }
        } catch (Exception e) {
            // A broken config must never crash the game: keep defaults, but set the broken file aside first so
            // a typo in a hand edit does not wipe every setting.
            LOG.error("Could not read config, using defaults", e);
            try {
                Path aside = FILE.resolveSibling("wabbit-ultimate.json.broken-"
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
                Files.copy(FILE, aside, StandardCopyOption.REPLACE_EXISTING);
                LOG.error("The unreadable config was kept as {}", aside.getFileName());
            } catch (IOException copyFailed) {
                LOG.error("Could not keep a copy of the unreadable config", copyFailed);
            }
            config = new WabbitConfig();
            save();
        }
    }

    /** Writes to a temporary file and swaps it in, so a crash mid-write never leaves half a config. */
    public static synchronized void save() {
        try {
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(config), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.error("Could not save config", e);
        }
    }
}
