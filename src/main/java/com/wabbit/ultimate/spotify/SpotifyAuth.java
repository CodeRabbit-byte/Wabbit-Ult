package com.wabbit.ultimate.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Spotify "Authorization Code with PKCE": no client secret, no local web server. */
public final class SpotifyAuth {
    private SpotifyAuth() {}

    public static final String REDIRECT_URI = "http://127.0.0.1:8888/callback";
    public static final String SCOPES = "user-read-currently-playing user-read-playback-state";
    private static final String VERIFIER_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public record Tokens(String access, String refresh, long expiresInSec) {}

    public static String newVerifier() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++) sb.append(VERIFIER_CHARS.charAt(RANDOM.nextInt(VERIFIER_CHARS.length())));
        return sb.toString();
    }

    public static String newState() {
        byte[] b = new byte[12];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String challenge(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String authUrl(String clientId, String challenge, String state) {
        return "https://accounts.spotify.com/authorize?response_type=code"
                + "&client_id=" + enc(clientId)
                + "&scope=" + enc(SCOPES)
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&code_challenge_method=S256"
                + "&code_challenge=" + enc(challenge)
                + "&state=" + enc(state);
    }

    /**
     * Pulls the login code out of whatever the person pasted: the full address of the page Spotify sent
     * them to (which may show a "can't connect" error, that is expected) or just the code.
     */
    public static String extractCode(String pasted, String expectedState) {
        if (pasted == null || pasted.isBlank()) throw new IllegalArgumentException("Paste the address from your browser first.");
        String s = pasted.trim();
        Matcher err = Pattern.compile("[?&]error=([^&\\s#]+)").matcher(s);
        if (err.find()) throw new IllegalArgumentException("Spotify said: " + dec(err.group(1)) + ". Try the login link again.");
        Matcher code = Pattern.compile("[?&]code=([^&\\s#]+)").matcher(s);
        if (code.find()) {
            Matcher st = Pattern.compile("[?&]state=([^&\\s#]+)").matcher(s);
            if (st.find() && expectedState != null && !dec(st.group(1)).equals(expectedState)) {
                throw new IllegalArgumentException("That address is from an older login attempt. Press Copy login link again.");
            }
            return dec(code.group(1));
        }
        if (!s.contains("://") && !s.contains("?") && s.length() > 20) return s; // a bare code
        throw new IllegalArgumentException("I can't find a login code in that. Paste the full address from the browser.");
    }

    public static Tokens exchange(String clientId, String code, String verifier) throws IOException, InterruptedException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "authorization_code");
        f.put("code", code);
        f.put("redirect_uri", REDIRECT_URI);
        f.put("client_id", clientId);
        f.put("code_verifier", verifier);
        return token(f, null);
    }

    public static Tokens refresh(String clientId, String refreshToken) throws IOException, InterruptedException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "refresh_token");
        f.put("refresh_token", refreshToken);
        f.put("client_id", clientId);
        return token(f, refreshToken);
    }

    private static Tokens token(Map<String, String> form, String oldRefresh) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject o;
        try {
            o = JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("Spotify sent an unreadable answer (HTTP " + resp.statusCode() + ").");
        }
        if (resp.statusCode() != 200) {
            String d = o.has("error_description") ? o.get("error_description").getAsString()
                    : (o.has("error") ? o.get("error").getAsString() : "HTTP " + resp.statusCode());
            throw new IOException(d);
        }
        String access = o.get("access_token").getAsString();
        String refresh = o.has("refresh_token") && !o.get("refresh_token").isJsonNull()
                ? o.get("refresh_token").getAsString() : oldRefresh;
        long exp = o.has("expires_in") ? o.get("expires_in").getAsLong() : 3600;
        return new Tokens(access, refresh, exp);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
