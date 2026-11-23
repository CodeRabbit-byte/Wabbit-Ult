package com.wabbit.ultimate.hud;

import com.wabbit.ultimate.config.ConfigManager;
import com.wabbit.ultimate.config.WabbitConfig;
import com.wabbit.ultimate.spotify.Lrc;
import com.wabbit.ultimate.spotify.SpotifyService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/** Song title plus previous / current / upcoming synced lyric lines, in a screen corner. */
public final class SpotifyHud {
    private SpotifyHud() {}

    public static void render(DrawContext ctx, int w, int h) {
        WabbitConfig cfg = ConfigManager.get();
        if (!cfg.spotifyHud) return;
        SpotifyService.Now n = SpotifyService.now();
        if (n == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden) return;
        TextRenderer tr = mc.textRenderer;

        int maxText = Math.max(120, Math.min(260, w / 2));
        List<String> texts = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        texts.add(n.title() + " - " + n.artist());
        colors.add(0xFFFFFFFF);

        Lrc l = SpotifyService.lyrics();
        if (l != null) {
            int idx = l.indexAt(n.position());
            int around = cfg.spotifyLines == 0 ? 1 : 2;
            for (int k = idx - around; k <= idx + around; k++) {
                if (k < 0 || k >= l.size()) continue;
                texts.add(l.text[k]);
                colors.add(k == idx ? cfg.accent() : 0xFF9AA0AE);
            }
            if (idx < 0) { // intro: show what is coming
                texts.clear();
                colors.clear();
                texts.add(n.title() + " - " + n.artist());
                colors.add(0xFFFFFFFF);
                for (int k = 0; k <= Math.min(around, l.size() - 1); k++) {
                    texts.add(l.text[k]);
                    colors.add(0xFF9AA0AE);
                }
            }
        } else if (!SpotifyService.lyricsState().isEmpty()) {
            texts.add(SpotifyService.lyricsState());
            colors.add(0xFF9AA0AE);
        }

        int boxW = 0;
        List<String> trimmed = new ArrayList<>();
        for (String t : texts) {
            String s = tr.trimToWidth(t, maxText);
            trimmed.add(s);
            boxW = Math.max(boxW, tr.getWidth(s));
        }
        boxW += 12;
        int boxH = trimmed.size() * 11 + 6;
        int corner = Math.floorMod(cfg.spotifyCorner, 4);
        int x = (corner == 0 || corner == 2) ? 6 : w - boxW - 6;
        int y = corner < 2 ? 6 : h - boxH - 6;
        if (corner == 0 && cfg.fpsHud) y = 40; // keep clear of the performance HUD in the same corner

        int alpha = Math.max(0, Math.min(100, cfg.hudOpacity)) * 255 / 100;
        ctx.fill(x, y, x + boxW, y + boxH, alpha << 24);
        ctx.fill(x, y, x + 2, y + boxH, cfg.accent());
        for (int i = 0; i < trimmed.size(); i++) {
            ctx.drawText(tr, trimmed.get(i), x + 7, y + 4 + i * 11, colors.get(i), true);
        }
    }
}
