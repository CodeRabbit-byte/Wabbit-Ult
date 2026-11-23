package com.wabbit.ultimate.gui;

import com.wabbit.ultimate.config.ConfigManager;
import com.wabbit.ultimate.config.WabbitConfig;
import com.wabbit.ultimate.spotify.SpotifyAuth;
import com.wabbit.ultimate.spotify.SpotifyService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.concurrent.CompletableFuture;

/** Connect Spotify (paste-the-address login) and set up the lyrics HUD. */
public class SpotifyScreen extends Screen {
    private static final String[] CORNERS = {"Top-left", "Top-right", "Bottom-left", "Bottom-right"};
    private final Screen parent;
    private int left, top, w, h;
    private String status = "";
    private TextFieldWidget clientField, pasteField;

    public SpotifyScreen(Screen parent) {
        super(Text.literal("Spotify Lyrics"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        w = Math.min(450, width - 12);
        h = Math.min(270, height - 8);
        left = (width - w) / 2;
        top = (height - h) / 2;
        WabbitConfig cfg = ConfigManager.get();
        SpotifyService.setScreenOpen(true);

        clientField = new TextFieldWidget(textRenderer, left + 74, top + 70, w - 84, 18, Text.literal("Client ID"));
        clientField.setMaxLength(64);
        clientField.setText(cfg.spotifyClientId);
        clientField.setChangedListener(s -> cfg.spotifyClientId = s.trim());
        addDrawableChild(clientField);

        addDrawableChild(ButtonWidget.builder(Text.literal("1. Copy login link (open it in your browser)"), b -> copyLink())
                .dimensions(left + 10, top + 92, w - 20, 18).build());

        pasteField = new TextFieldWidget(textRenderer, left + 10, top + 114, w - 20 - 4 - 70, 18, Text.literal("Address"));
        pasteField.setMaxLength(1000);
        addDrawableChild(pasteField);
        addDrawableChild(ButtonWidget.builder(Text.literal("2. Connect"), b -> connect())
                .dimensions(left + w - 10 - 70, top + 114, 70, 18).build());

        int by = top + h - 52;
        int bw = (w - 20 - 8) / 3;
        addDrawableChild(ButtonWidget.builder(Text.literal("HUD: " + (cfg.spotifyHud ? "ON" : "OFF")), b -> {
            cfg.spotifyHud = !cfg.spotifyHud;
            ConfigManager.save();
            b.setMessage(Text.literal("HUD: " + (cfg.spotifyHud ? "ON" : "OFF")));
        }).dimensions(left + 10, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(CORNERS[Math.floorMod(cfg.spotifyCorner, 4)]), b -> {
            cfg.spotifyCorner = (cfg.spotifyCorner + 1) % 4;
            ConfigManager.save();
            b.setMessage(Text.literal(CORNERS[cfg.spotifyCorner]));
        }).dimensions(left + 14 + bw, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(cfg.spotifyLines == 0 ? "Lyric lines: 3" : "Lyric lines: 5"), b -> {
            cfg.spotifyLines = cfg.spotifyLines == 0 ? 1 : 0;
            ConfigManager.save();
            b.setMessage(Text.literal(cfg.spotifyLines == 0 ? "Lyric lines: 3" : "Lyric lines: 5"));
        }).dimensions(left + 18 + 2 * bw, by, bw, 20).build());

        int by2 = top + h - 26;
        addDrawableChild(ButtonWidget.builder(Text.literal("Disconnect"), b -> {
            SpotifyService.disconnect();
            status = "Disconnected.";
        }).dimensions(left + 10, by2, 90, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
                .dimensions(left + w - 10 - 80, by2, 80, 20).build());
    }

    private void copyLink() {
        WabbitConfig cfg = ConfigManager.get();
        if (cfg.spotifyClientId == null || cfg.spotifyClientId.isBlank()) {
            status = "Paste your Spotify Client ID first.";
            return;
        }
        String url = SpotifyService.beginLogin(cfg.spotifyClientId.trim());
        MinecraftClient.getInstance().keyboard.setClipboard(url);
        status = "Link copied. Open it in a browser and log in. The final page may show an error: copy its address anyway.";
    }

    private void connect() {
        WabbitConfig cfg = ConfigManager.get();
        String verifier = SpotifyService.pendingVerifier();
        if (verifier == null) {
            status = "Press 'Copy login link' first.";
            return;
        }
        String code;
        try {
            code = SpotifyAuth.extractCode(pasteField.getText(), SpotifyService.pendingState());
        } catch (IllegalArgumentException e) {
            status = e.getMessage();
            return;
        }
        String clientId = cfg.spotifyClientId.trim();
        status = "Connecting...";
        MinecraftClient mc = MinecraftClient.getInstance();
        CompletableFuture.runAsync(() -> {
            try {
                SpotifyAuth.Tokens t = SpotifyAuth.exchange(clientId, code, verifier);
                mc.execute(() -> {
                    cfg.spotifyRefreshToken = t.refresh() == null ? "" : t.refresh();
                    ConfigManager.save();
                    SpotifyService.setTokens(t);
                    pasteField.setText("");
                    status = "Connected! Play a song in Spotify.";
                });
            } catch (Exception e) {
                mc.execute(() -> status = "Login failed: " + e.getMessage());
            }
        });
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        WabbitConfig cfg = ConfigManager.get();
        ctx.fill(left, top, left + w, top + h, cfg.darkTheme ? 0xF0151821 : 0xF0F2F2F5);
        ctx.fill(left, top, left + w, top + 2, cfg.accent());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        WabbitConfig cfg = ConfigManager.get();
        int text = cfg.darkTheme ? 0xFFFFFFFF : 0xFF1A1A1A;
        int dim = cfg.darkTheme ? 0xFF8A90A0 : 0xFF6A7080;

        ctx.drawText(textRenderer, "Spotify Lyrics HUD", left + 10, top + 8, cfg.accent(), true);
        String[] setup = {
                "One-time setup at developer.spotify.com/dashboard (Premium needed):",
                "Create an app, enable Web API, add Redirect URI " + SpotifyAuth.REDIRECT_URI,
                "Add your Spotify email under User Management, then copy the Client ID.",
        };
        for (int i = 0; i < setup.length; i++) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(setup[i], w - 20), left + 10, top + 22 + i * 11, dim, false);
        }
        ctx.drawText(textRenderer, "Client ID:", left + 10, top + 75, text, false);

        ctx.drawText(textRenderer, textRenderer.trimToWidth("Spotify: " + SpotifyService.status(), w - 20),
                left + 10, top + 140, text, false);
        SpotifyService.Now n = SpotifyService.now();
        if (n != null) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth("Now: " + n.title() + " - " + n.artist(), w - 20),
                    left + 10, top + 152, cfg.accent(), false);
            String ly = SpotifyService.lyrics() != null ? "Synced lyrics found." : SpotifyService.lyricsState();
            ctx.drawText(textRenderer, textRenderer.trimToWidth(ly, w - 20), left + 10, top + 164, dim, false);
        }
        if (!status.isEmpty()) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(status, w - 20), left + 10, top + h - 64, 0xFFFF9090, false);
        }
    }

    @Override
    public void removed() {
        SpotifyService.setScreenOpen(false);
        ConfigManager.save();
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
