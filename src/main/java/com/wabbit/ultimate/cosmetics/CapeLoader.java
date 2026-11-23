package com.wabbit.ultimate.cosmetics;

import com.wabbit.ultimate.config.ConfigManager;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Capes are PNG files the player drops into config/wabbit-capes/. */
public final class CapeLoader {
    private static final Logger LOG = LoggerFactory.getLogger("WabbitUltimate");
    private CapeLoader() {}

    private static volatile CapeData current;
    private static volatile String lastError = "";

    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("wabbit-capes");
    }

    public static CapeData current() { return current; }
    public static String lastError() { return lastError; }

    public static List<String> list() {
        List<String> out = new ArrayList<>();
        try {
            Files.createDirectories(dir());
            try (Stream<Path> s = Files.list(dir())) {
                s.map(p -> p.getFileName().toString())
                        .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".png"))
                        .forEach(out::add);
            }
        } catch (IOException e) {
            lastError = "Can't read the capes folder: " + e.getMessage();
        }
        out.sort(Comparator.comparing(n -> n.toLowerCase(Locale.ROOT)));
        return out;
    }

    /** Loads the cape named in the config (or clears it). Never throws. */
    public static void reload() {
        String name = ConfigManager.get().capeFile;
        lastError = "";
        if (name == null || name.isBlank()) {
            current = null;
            return;
        }
        try {
            Path p = dir().resolve(name).normalize();
            if (!p.startsWith(dir().normalize())) throw new IOException("Bad file name.");
            current = CapeData.fromFile(p);
        } catch (Throwable t) { // includes a Java without image support
            current = null;
            lastError = t instanceof IOException ? t.getMessage() : "Could not load the image (" + t.getClass().getSimpleName() + ").";
            LOG.warn("Cape load failed: {}", t.toString());
        }
    }
}
