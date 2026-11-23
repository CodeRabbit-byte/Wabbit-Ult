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
            // A broken config must never crash the game: keep defaults and rewrite the file.
            LOG.error("Could not read config, using defaults", e);
            config = new WabbitConfig();
            save();
        }
    }

    public static void save() {
        try {
            Files.writeString(FILE, GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.error("Could not save config", e);
        }
    }
}
