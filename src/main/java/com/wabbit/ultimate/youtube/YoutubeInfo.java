package com.wabbit.ultimate.youtube;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Looks up a video's title and channel through YouTube's public oEmbed endpoint.
 * Runs in the background; the callback may arrive on any thread.
 */
public final class YoutubeInfo {
    private YoutubeInfo() {}

    public record Result(boolean ok, String title, String channel, String error) {}

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    public static void fetch(String id, Consumer<Result> callback) {
        try {
            String url = "https://www.youtube.com/oembed?format=json&url="
                    + URLEncoder.encode(YoutubeLink.watchUrl(id), StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "WabbitUltimate/0.7 (Minecraft Fabric mod)")
                    .GET().build();
            CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(resp -> {
                try {
                    if (resp.statusCode() == 200) {
                        JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
                        String title = o.has("title") ? o.get("title").getAsString() : "(no title)";
                        String channel = o.has("author_name") ? o.get("author_name").getAsString() : "";
                        callback.accept(new Result(true, title, channel, null));
                    } else if (resp.statusCode() == 401 || resp.statusCode() == 403 || resp.statusCode() == 404) {
                        callback.accept(new Result(false, null, null,
                                "YouTube has no public info for that video (private, removed or embedding off)."));
                    } else {
                        callback.accept(new Result(false, null, null, "YouTube answered with error " + resp.statusCode() + "."));
                    }
                } catch (Exception e) {
                    callback.accept(new Result(false, null, null, "Could not read YouTube's answer."));
                }
            }).exceptionally(ex -> {
                callback.accept(new Result(false, null, null, "Could not reach YouTube. Check your internet."));
                return null;
            });
        } catch (Exception e) {
            callback.accept(new Result(false, null, null, "Could not start the lookup."));
        }
    }
}
