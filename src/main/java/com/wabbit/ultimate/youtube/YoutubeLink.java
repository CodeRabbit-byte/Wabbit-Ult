package com.wabbit.ultimate.youtube;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Turns anything a person might paste into a YouTube video id (works for videos and livestreams). */
public final class YoutubeLink {
    private YoutubeLink() {}

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    /** Returns the 11-character video id, or null if the text is not a recognisable YouTube link. */
    public static String parseId(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (ID.matcher(s).matches()) return s; // a bare id

        try {
            if (!s.contains("://")) s = "https://" + s;
            URI uri = URI.create(s);
            String host = uri.getHost();
            if (host == null) return null;
            host = host.toLowerCase();
            for (String prefix : new String[]{"www.", "m.", "music."}) {
                if (host.startsWith(prefix)) host = host.substring(prefix.length());
            }
            String path = uri.getPath() == null ? "" : uri.getPath();

            if (host.equals("youtu.be")) {
                return check(firstSegment(path));
            }
            if (host.equals("youtube.com") || host.equals("youtube-nocookie.com")) {
                if (path.equals("/watch") || path.equals("/watch/")) {
                    return check(queryParam(uri.getRawQuery(), "v"));
                }
                for (String p : new String[]{"/live/", "/shorts/", "/embed/", "/v/"}) {
                    if (path.startsWith(p)) return check(firstSegment(path.substring(p.length() - 1)));
                }
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    public static String watchUrl(String id) {
        return "https://www.youtube.com/watch?v=" + id;
    }

    private static String firstSegment(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        int slash = p.indexOf('/');
        return slash >= 0 ? p.substring(0, slash) : p;
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if (pair.substring(0, eq).equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String check(String id) {
        return id != null && ID.matcher(id).matches() ? id : null;
    }
}
