package com.wabbit.ultimate.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wabbit.ultimate.config.ConfigManager;
import com.wabbit.ultimate.config.WabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Background poller for "currently playing" plus lyrics lookup. Never touches the render thread. */
public final class SpotifyService {
    private static final Logger LOG = LoggerFactory.getLogger("WabbitUltimate");
    private SpotifyService() {}

    public record Now(String trackId, String title, String artist, String album,
                      long durationMs, long progressMs, long polledAt, boolean playing) {
        public long position() {
            long p = progressMs + (playing ? System.currentTimeMillis() - polledAt : 0);
            return Math.max(0, Math.min(p, durationMs));
        }
    }

    private static final AtomicReference<Now> NOW = new AtomicReference<>();
    private static volatile Lrc lyrics;
    private static volatile String lyricsTrack = "";
    private static volatile String lyricsState = "";
    private static volatile String status = "Not connected.";
    private static volatile String accessToken;
    private static volatile long accessExpiresAt;
    private static volatile long backoffUntil;
    private static volatile boolean screenOpen;
    private static String pendingVerifier, pendingState;
    private static ScheduledExecutorService exec;

    public static Now now() { return NOW.get(); }
    public static Lrc lyrics() { return lyrics; }
    public static String lyricsState() { return lyricsState; }
    public static String status() { return status; }
    public static void setScreenOpen(boolean open) { screenOpen = open; }

    public static void start() {
        if (exec != null) return;
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Wabbit-Spotify");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (Throwable t) {
                LOG.error("Spotify poll failed", t);
            }
        }, 3, 4, TimeUnit.SECONDS);
    }

    // ----- login -----

    public static String beginLogin(String clientId) {
        pendingVerifier = SpotifyAuth.newVerifier();
        pendingState = SpotifyAuth.newState();
        return SpotifyAuth.authUrl(clientId, SpotifyAuth.challenge(pendingVerifier), pendingState);
    }

    public static String pendingVerifier() { return pendingVerifier; }
    public static String pendingState() { return pendingState; }

    public static void setTokens(SpotifyAuth.Tokens t) {
        accessToken = t.access();
        accessExpiresAt = System.currentTimeMillis() + t.expiresInSec() * 1000L;
        backoffUntil = 0;
        status = "Connected. Waiting for Spotify...";
    }

    public static void disconnect() {
        WabbitConfig cfg = ConfigManager.get();
        cfg.spotifyRefreshToken = "";
        ConfigManager.save();
        accessToken = null;
        NOW.set(null);
        lyrics = null;
        lyricsTrack = "";
        lyricsState = "";
        status = "Not connected.";
    }

    // ----- polling -----

    private static void tick() throws Exception {
        WabbitConfig cfg = ConfigManager.get();
        if (cfg.spotifyClientId == null || cfg.spotifyClientId.isBlank()
                || cfg.spotifyRefreshToken == null || cfg.spotifyRefreshToken.isBlank()) {
            NOW.set(null);
            status = "Not connected.";
            return;
        }
        if (!cfg.spotifyHud && !screenOpen) return; // no need to ask Spotify when nobody is looking
        if (System.currentTimeMillis() < backoffUntil) return;

        if (accessToken == null || System.currentTimeMillis() > accessExpiresAt - 30_000) {
            try {
                SpotifyAuth.Tokens t = SpotifyAuth.refresh(cfg.spotifyClientId.trim(), cfg.spotifyRefreshToken);
                accessToken = t.access();
                accessExpiresAt = System.currentTimeMillis() + t.expiresInSec() * 1000L;
                if (t.refresh() != null && !t.refresh().equals(cfg.spotifyRefreshToken)) {
                    String newRefresh = t.refresh();
                    // config is read and written by the game thread; hand the change over instead of saving here
                    net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
                        cfg.spotifyRefreshToken = newRefresh;
                        ConfigManager.save();
                    });
                }
            } catch (Exception e) {
                accessToken = null;
                status = "Login expired or refused (" + e.getMessage() + "). Connect again.";
                backoffUntil = System.currentTimeMillis() + 30_000;
                return;
            }
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1/me/player/currently-playing"))
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + accessToken)
                .GET().build();
        HttpResponse<String> resp = SpotifyAuth.HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        long polledAt = System.currentTimeMillis();

        switch (resp.statusCode()) {
            case 200 -> parseNow(resp.body(), polledAt);
            case 204 -> {
                NOW.set(null);
                status = "Connected. Nothing is playing.";
            }
            case 401 -> {
                accessToken = null; // refresh on next tick
            }
            case 403 -> {
                status = "Spotify refused access. Add your Spotify email under User Management in the developer dashboard.";
                backoffUntil = polledAt + 60_000;
            }
            case 429 -> {
                long wait = resp.headers().firstValueAsLong("Retry-After").orElse(30);
                backoffUntil = polledAt + wait * 1000L;
                status = "Spotify asked us to slow down. Retrying in " + wait + "s.";
            }
            default -> status = "Spotify error " + resp.statusCode() + ".";
        }
    }

    private static void parseNow(String body, long polledAt) {
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        String type = str(o, "currently_playing_type", "track");
        JsonElement itemEl = o.get("item");
        if (!"track".equals(type) || itemEl == null || itemEl.isJsonNull()) {
            NOW.set(null);
            status = "Connected. Playing something that is not a song.";
            return;
        }
        JsonObject item = itemEl.getAsJsonObject();
        StringBuilder artists = new StringBuilder();
        JsonArray arr = item.has("artists") ? item.getAsJsonArray("artists") : new JsonArray();
        for (JsonElement a : arr) {
            if (artists.length() > 0) artists.append(", ");
            artists.append(str(a.getAsJsonObject(), "name", ""));
        }
        String album = item.has("album") && item.get("album").isJsonObject()
                ? str(item.getAsJsonObject("album"), "name", "") : "";
        String id = str(item, "id", str(item, "name", "?"));
        long duration = item.has("duration_ms") ? item.get("duration_ms").getAsLong() : 0;
        long progress = o.has("progress_ms") && !o.get("progress_ms").isJsonNull() ? o.get("progress_ms").getAsLong() : 0;
        boolean playing = o.has("is_playing") && o.get("is_playing").getAsBoolean();

        Now n = new Now(id, str(item, "name", "?"), artists.toString(), album, duration, progress, polledAt, playing);
        NOW.set(n);
        status = "Connected.";

        if (!id.equals(lyricsTrack)) {
            lyricsTrack = id;
            lyrics = null;
            lyricsState = "Looking for lyrics...";
            Lrc found = LrcLib.fetch(n.title(), n.artist(), n.album(), n.durationMs() / 1000);
            if (id.equals(lyricsTrack)) { // still the same song
                lyrics = found;
                lyricsState = found == null ? "No synced lyrics found for this song." : "";
            }
        }
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    // ----- lyrics (LRCLIB, a free public lyrics database) -----

    static final class LrcLib {
        static Lrc fetch(String title, String artist, String album, long durationSec) {
            try {
                String url = "https://lrclib.net/api/get?track_name=" + enc(title) + "&artist_name=" + enc(artist)
                        + "&album_name=" + enc(album) + "&duration=" + durationSec;
                HttpResponse<String> r = SpotifyAuth.HTTP.send(request(url), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    Lrc l = Lrc.parse(synced(JsonParser.parseString(r.body()).getAsJsonObject()));
                    if (l != null) return l;
                }
                // fall back to a looser search
                String s = "https://lrclib.net/api/search?track_name=" + enc(title) + "&artist_name=" + enc(artist);
                r = SpotifyAuth.HTTP.send(request(s), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    for (JsonElement e : JsonParser.parseString(r.body()).getAsJsonArray()) {
                        JsonObject o = e.getAsJsonObject();
                        double d = o.has("duration") && !o.get("duration").isJsonNull() ? o.get("duration").getAsDouble() : -1;
                        if (d >= 0 && Math.abs(d - durationSec) > 5) continue;
                        Lrc l = Lrc.parse(synced(o));
                        if (l != null) return l;
                    }
                }
            } catch (Exception e) {
                LOG.warn("Lyrics lookup failed: {}", e.toString());
            }
            return null;
        }

        private static String synced(JsonObject o) {
            return o.has("syncedLyrics") && !o.get("syncedLyrics").isJsonNull() ? o.get("syncedLyrics").getAsString() : null;
        }

        private static HttpRequest request(String url) {
            return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "WabbitUltimate/0.8 (Minecraft Fabric mod)").GET().build();
        }

        private static String enc(String s) {
            return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
        }
    }
}
