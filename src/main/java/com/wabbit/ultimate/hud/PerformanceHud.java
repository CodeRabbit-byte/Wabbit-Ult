package com.wabbit.ultimate.hud;

import com.wabbit.ultimate.config.ConfigManager;
import com.wabbit.ultimate.config.WabbitConfig;
import com.wabbit.ultimate.donut.Advent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/** FPS + memory overlay. Draws nothing when both are disabled (except in the editor preview). */
public final class PerformanceHud {
    private PerformanceHud() {}

    /** Normal in-game rendering. */
    public static void render(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || mc.getDebugHud().shouldShowDebugHud()) return;
        draw(ctx, false);
    }

    private static List<String> lines(WabbitConfig cfg, boolean preview) {
        MinecraftClient mc = MinecraftClient.getInstance();
        List<String> lines = new ArrayList<>();
        if (cfg.fpsHud) lines.add("FPS: " + mc.getCurrentFps());
        if (cfg.memoryHud) {
            Runtime rt = Runtime.getRuntime();
            long used = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
            long max = rt.maxMemory() / 1048576L;
            lines.add("RAM: " + used + " / " + max + " MB");
        }
        if (cfg.balanceHud) {
            long bal = Advent.parseAmount(cfg.adventBalanceText);
            lines.add(bal < 0 ? "Balance: set in DonutSMP" : "Balance: " + Advent.format(bal));
        }
        if (cfg.ytHud && cfg.ytSelectedId != null) {
            for (WabbitConfig.YtLink l : cfg.ytLinks) {
                if (cfg.ytSelectedId.equals(l.id)) {
                    String t = l.title == null ? l.id : l.title;
                    if (t.length() > 30) t = t.substring(0, 29) + "...";
                    lines.add("YT: " + t);
                    break;
                }
            }
        }
        if (lines.isEmpty() && preview) lines.add("HUD (enable in Features)");
        return lines;
    }

    /** Box bounds {x1, y1, x2, y2} of the HUD at its configured position. */
    public static int[] bounds(boolean preview) {
        WabbitConfig cfg = ConfigManager.get();
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        List<String> lines = lines(cfg, preview);
        int width = 0;
        for (String s : lines) width = Math.max(width, tr.getWidth(s));
        int height = Math.max(1, lines.size()) * 10;
        return new int[]{cfg.hudX - 3, cfg.hudY - 3, cfg.hudX + width + 3, cfg.hudY + height + 1};
    }

    public static void draw(DrawContext ctx, boolean preview) {
        WabbitConfig cfg = ConfigManager.get();
        List<String> lines = lines(cfg, preview);
        if (lines.isEmpty()) return;

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int[] b = bounds(preview);

        if (cfg.hudBackground || preview) {
            int alpha = Math.max(0, Math.min(100, cfg.hudOpacity)) * 255 / 100;
            ctx.fill(b[0], b[1], b[2], b[3], alpha << 24);
            ctx.fill(b[0], b[1], b[0] + 2, b[3], cfg.accent());
        }
        int ly = cfg.hudY;
        for (String s : lines) {
            ctx.drawText(tr, s, cfg.hudX + 2, ly, 0xFFFFFFFF, true);
            ly += 10;
        }
    }
}
