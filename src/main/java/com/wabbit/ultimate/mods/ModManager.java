package com.wabbit.ultimate.mods;

import com.wabbit.ultimate.config.ConfigManager;
import com.wabbit.ultimate.config.WabbitConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Safe mod management. Mods are never unloaded at runtime: "disable" renames the jar to
 * "<name>.jar.disabled" so Fabric skips it on the NEXT launch. A restart is always required.
 */
public final class ModManager {
    private static final Logger LOG = LoggerFactory.getLogger("WabbitUltimate");
    private static final String SELF_ID = "wabbitultimate";
    private static final String SUFFIX = ".disabled";

    public record LoadedMod(String jar, String id, String name, String version,
                            Set<String> provided, Set<String> depends) {}

    public record Entry(String jar, String name, String version, boolean enabled, LoadedMod info) {}

    public record Result(boolean ok, String message) {}

    private static final Map<String, LoadedMod> LOADED = new HashMap<>();
    private static final List<Text> PENDING = new ArrayList<>();

    /** True once a jar was renamed this session. */
    public static boolean pendingRestart;
    /** Lowercase address of the current multiplayer server, or null (singleplayer / menus). */
    public static String currentServer;

    private ModManager() {}

    private static Path modsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("mods").toAbsolutePath().normalize();
    }

    public static void init() {
        try {
            scanLoadedMods();
        } catch (Exception e) {
            LOG.error("Could not scan loaded mods", e);
        }
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> currentServer = null);
        ClientTickEvents.END_CLIENT_TICK.register(ModManager::flush);
    }

    private static void scanLoadedMods() {
        Path dir = modsDir();
        Collection<ModContainer> all = FabricLoader.getInstance().getAllMods();
        for (ModContainer m : all) {
            if (m.getContainingMod().isPresent()) continue;
            ModMetadata md = m.getMetadata();
            if ("builtin".equals(md.getType())) continue;
            ModOrigin origin = m.getOrigin();
            if (origin.getKind() != ModOrigin.Kind.PATH) continue;
            List<Path> paths = origin.getPaths();
            if (paths.size() != 1) continue;
            Path p = paths.get(0).toAbsolutePath().normalize();
            if (p.getParent() == null || !p.getParent().equals(dir)) continue;
            String jar = p.getFileName().toString();
            if (!jar.endsWith(".jar")) continue;

            Set<String> provided = new HashSet<>();
            for (ModContainer x : all) {
                ModContainer top = x;
                while (top.getContainingMod().isPresent()) top = top.getContainingMod().get();
                if (top.getMetadata().getId().equals(md.getId())) {
                    provided.add(x.getMetadata().getId());
                    provided.addAll(x.getMetadata().getProvides());
                }
            }
            Set<String> depends = new HashSet<>();
            for (ModDependency d : md.getDependencies()) {
                if (d.getKind() == ModDependency.Kind.DEPENDS) depends.add(d.getModId());
            }
            LOADED.put(jar, new LoadedMod(jar, md.getId(), md.getName(),
                    md.getVersion().getFriendlyString(), provided, depends));
        }
    }

    /** Every mod jar in the mods folder (enabled and disabled), sorted by name. */
    public static List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(modsDir())) {
            s.forEach(p -> {
                String f = p.getFileName().toString();
                boolean dis = f.endsWith(".jar" + SUFFIX);
                if (!dis && !f.endsWith(".jar")) return;
                String base = dis ? f.substring(0, f.length() - SUFFIX.length()) : f;
                LoadedMod lm = LOADED.get(base);
                out.add(new Entry(base, lm != null ? lm.name() : base,
                        lm != null ? lm.version() : "", !dis, lm));
            });
        } catch (Exception e) {
            LOG.error("Could not list mods folder", e);
        }
        out.sort(Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static String blockedBy(String base) {
        LoadedMod lm = LOADED.get(base);
        if (lm == null) return null;
        Path dir = modsDir();
        for (LoadedMod other : LOADED.values()) {
            if (other.jar().equals(base)) continue;
            if (!Files.exists(dir.resolve(other.jar()))) continue; // other is itself disabled
            for (String d : other.depends()) {
                if (lm.provided().contains(d)) return other.name();
            }
        }
        return null;
    }

    public static Result setEnabled(String base, boolean enable) {
        Path dir = modsDir();
        Path on = dir.resolve(base);
        Path off = dir.resolve(base + SUFFIX);
        try {
            if (!enable) {
                LoadedMod lm = LOADED.get(base);
                if (lm != null && lm.provided().contains(SELF_ID)) {
                    return new Result(false, "Wabbit Ultimate can't disable itself.");
                }
                String blocker = blockedBy(base);
                if (blocker != null) return new Result(false, "Can't disable: required by " + blocker + ".");
                if (!Files.exists(on)) return new Result(false, "Already disabled.");
                Files.move(on, off);
                pendingRestart = true;
                return new Result(true, "Disabled " + base + ". Restart required.");
            } else {
                if (!Files.exists(off)) return new Result(false, "Already enabled.");
                Files.move(off, on);
                pendingRestart = true;
                return new Result(true, "Enabled " + base + ". Restart required.");
            }
        } catch (Exception e) {
            LOG.error("Rename failed for {}", base, e);
            return new Result(false, "Could not rename the jar (file locked?). Rename it by hand with the game closed.");
        }
    }

    // ---------- profiles ----------

    public static WabbitConfig.ModProfile captureProfile(String name) {
        WabbitConfig.ModProfile p = new WabbitConfig.ModProfile();
        p.name = name;
        for (Entry e : list()) if (!e.enabled()) p.disabledJars.add(e.jar());
        return p;
    }

    public static int diffCount(WabbitConfig.ModProfile p) {
        int n = 0;
        for (Entry e : list()) if (e.enabled() == p.disabledJars.contains(e.jar())) n++;
        return n;
    }

    public static String applyProfile(WabbitConfig.ModProfile p) {
        int changed = 0, skipped = 0;
        for (Entry e : list()) {
            boolean shouldDisable = p.disabledJars.contains(e.jar());
            if (e.enabled() && shouldDisable) {
                if (setEnabled(e.jar(), false).ok()) changed++; else skipped++;
            } else if (!e.enabled() && !shouldDisable) {
                if (setEnabled(e.jar(), true).ok()) changed++; else skipped++;
            }
        }
        String s = "Profile '" + p.name + "': " + changed + " jar(s) changed";
        if (skipped > 0) s += ", " + skipped + " skipped (protected or locked)";
        return s + (changed > 0 ? ". Restart required." : ".");
    }

    public static WabbitConfig.ModProfile findProfile(String name) {
        if (name == null) return null;
        for (WabbitConfig.ModProfile p : ConfigManager.get().modProfiles) {
            if (name.equals(p.name)) return p;
        }
        return null;
    }

    // ---------- restricted-mod flags (per server, user-defined) ----------

    public static boolean isFlagged(String modId) {
        if (currentServer == null) return false;
        List<String> l = ConfigManager.get().restrictedMods.get(currentServer);
        return l != null && l.contains(modId);
    }

    public static void toggleFlag(String modId) {
        if (currentServer == null) return;
        Map<String, List<String>> m = ConfigManager.get().restrictedMods;
        List<String> l = m.computeIfAbsent(currentServer, k -> new ArrayList<>());
        if (!l.remove(modId)) l.add(modId);
        if (l.isEmpty()) m.remove(currentServer);
        ConfigManager.save();
    }

    // ---------- server detection ----------

    private static void onJoin(MinecraftClient client) {
        try {
            ServerInfo si = client.getCurrentServerEntry();
            currentServer = si == null ? null : si.address.toLowerCase(Locale.ROOT).trim();
            if (currentServer == null) return;
            WabbitConfig cfg = ConfigManager.get();

            WabbitConfig.ModProfile p = findProfile(cfg.serverProfiles.get(currentServer));
            if (p != null) {
                int diff = diffCount(p);
                if (diff > 0) {
                    PENDING.add(Text.literal("[Wabbit] Profile '" + p.name + "' is set for this server but "
                            + diff + " mod jar(s) differ. Apply it in Mod Manager > Profiles, then restart."));
                }
            }
            List<String> flagged = cfg.restrictedMods.get(currentServer);
            if (flagged != null) {
                List<String> loaded = new ArrayList<>();
                for (String id : flagged) if (FabricLoader.getInstance().isModLoaded(id)) loaded.add(id);
                if (!loaded.isEmpty()) {
                    PENDING.add(Text.literal("[Wabbit] Mods you flagged for this server are loaded: "
                            + String.join(", ", loaded)));
                }
            }
        } catch (Exception e) {
            LOG.error("Server join handling failed", e);
        }
    }

    private static void flush(MinecraftClient client) {
        if (PENDING.isEmpty() || client.player == null) return;
        for (Text t : PENDING) client.player.sendMessage(t, false);
        PENDING.clear();
    }
}
