package com.wabbit.ultimate.gui;

import com.wabbit.ultimate.config.ConfigManager;
import com.wabbit.ultimate.config.WabbitConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;

/** Main Wabbit menu: sidebar navigation + pages. */
public class WabbitScreen extends Screen {
    private enum Page {
        DASHBOARD("Dashboard"), FEATURES("Features"), MODS("Mods"), DONUT("DonutSMP"), MEDIA("Media"), COSMETICS("Cosmetics"), SETTINGS("Settings");
        final String label;
        Page(String label) { this.label = label; }
    }

    private static Page page = Page.DASHBOARD; // remembers last page while the game runs
    private static final int SIDEBAR_W = 108;

    private int left, top, w, h;
    private final ButtonWidget[] resultButtons = new ButtonWidget[4];
    private List<FeatureIndex.Entry> matches = List.of();
    private boolean searching;

    public WabbitScreen() {
        super(Text.literal("Wabbit Ultimate"));
    }

    @Override
    protected void init() {
        w = Math.min(400, width - 16);
        h = Math.min(230, height - 16);
        left = (width - w) / 2;
        top = (height - h) / 2;

        int y = top + 40;
        for (Page p : Page.values()) {
            String label = (p == page ? "> " : "  ") + p.label;
            addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> {
                page = p;
                clearAndInit();
            }).dimensions(left + 8, y, SIDEBAR_W - 16, 18).build());
            y += 22;
        }

        int cx = left + SIDEBAR_W + 12;
        int cw = w - SIDEBAR_W - 24;
        int cy = top + 40;
        WabbitConfig cfg = ConfigManager.get();

        switch (page) {
            case DASHBOARD -> buildSearch(cx, cy, cw);
            case FEATURES -> {
                cy = toggle(cx, cy, cw, "FPS Counter", cfg.fpsHud, v -> cfg.fpsHud = v);
                cy = toggle(cx, cy, cw, "Memory Usage", cfg.memoryHud, v -> cfg.memoryHud = v);
                cy = toggle(cx, cy, cw, "HUD Background", cfg.hudBackground, v -> cfg.hudBackground = v);
                cy = toggle(cx, cy, cw, "Balance HUD", cfg.balanceHud, v -> cfg.balanceHud = v);
                cy = toggle(cx, cy, cw, "Performance alerts", cfg.perfAlerts, v -> cfg.perfAlerts = v);
                addDrawableChild(ButtonWidget.builder(Text.literal("Edit HUD Layout..."),
                        b -> client.setScreen(new HudEditorScreen(this)))
                        .dimensions(cx, cy, cw, 20).build());
            }
            case MODS -> addDrawableChild(ButtonWidget.builder(Text.literal("Open Mod Manager..."),
                    b -> client.setScreen(new ModManagerScreen(this)))
                    .dimensions(cx, cy, cw, 20).build());
            case DONUT -> {
                addDrawableChild(ButtonWidget.builder(Text.literal("Open Advent Calendar..."),
                        b -> client.setScreen(new AdventScreen(this)))
                        .dimensions(cx, cy, cw, 20).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("Open Giveaway Manager..."),
                        b -> client.setScreen(new GiveawayScreen(this)))
                        .dimensions(cx, cy + 24, cw, 20).build());
            }
            case MEDIA -> {
                addDrawableChild(ButtonWidget.builder(Text.literal("Donation Alerts..."),
                        b -> client.setScreen(new DonationsScreen(this)))
                        .dimensions(cx, cy, cw, 20).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("YouTube Links..."),
                        b -> client.setScreen(new YoutubeScreen(this)))
                        .dimensions(cx, cy + 24, cw, 20).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("Spotify Lyrics..."),
                        b -> client.setScreen(new SpotifyScreen(this)))
                        .dimensions(cx, cy + 48, cw, 20).build());
            }
            case COSMETICS -> addDrawableChild(ButtonWidget.builder(Text.literal("Open Cosmetics..."),
                    b -> client.setScreen(new CosmeticsScreen(this)))
                    .dimensions(cx, cy, cw, 20).build());
            case SETTINGS -> {
                cy = toggle(cx, cy, cw, "Dark Theme", cfg.darkTheme, v -> cfg.darkTheme = v);
                addDrawableChild(ButtonWidget.builder(Text.literal("Accent: " + cfg.accentName()), b -> {
                    cfg.accentIndex = (cfg.accentIndex + 1) % WabbitConfig.ACCENT_COLORS.length;
                    ConfigManager.save();
                    clearAndInit();
                }).dimensions(cx, cy, cw, 20).build());
                cy += 24;
                addDrawableChild(ButtonWidget.builder(Text.literal("Reset HUD position"), b -> {
                    cfg.resetHud();
                    ConfigManager.save();
                }).dimensions(cx, cy, cw, 20).build());
                cy += 24;
                addDrawableChild(ButtonWidget.builder(Text.literal("Backup & Sync..."),
                        b -> client.setScreen(new SyncScreen(this)))
                        .dimensions(cx, cy, cw, 20).build());
            }
            default -> { }
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(left + 8, top + h - 28, SIDEBAR_W - 16, 20).build());
    }

    private void buildSearch(int cx, int cy, int cw) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, cx, cy - 4, cw, 18, Text.literal("Search"));
        field.setMaxLength(30);
        field.setChangedListener(this::onSearch);
        addDrawableChild(field);
        for (int i = 0; i < resultButtons.length; i++) {
            final int idx = i;
            ButtonWidget b = ButtonWidget.builder(Text.literal(""), btn -> openMatch(idx))
                    .dimensions(cx, cy + 18 + i * 22, cw, 20).build();
            b.visible = false;
            resultButtons[i] = b;
            addDrawableChild(b);
        }
        searching = false;
        matches = List.of();
    }

    private void onSearch(String query) {
        searching = query != null && !query.isBlank();
        matches = FeatureIndex.search(query);
        for (int i = 0; i < resultButtons.length; i++) {
            ButtonWidget b = resultButtons[i];
            if (b == null) continue;
            b.visible = i < matches.size();
            if (b.visible) b.setMessage(Text.literal(matches.get(i).name() + "  >"));
        }
    }

    private void openMatch(int i) {
        if (i >= matches.size()) return;
        switch (matches.get(i).target()) {
            case PAGE_FEATURES -> { page = Page.FEATURES; clearAndInit(); }
            case PAGE_SETTINGS -> { page = Page.SETTINGS; clearAndInit(); }
            case HUD_EDITOR -> client.setScreen(new HudEditorScreen(this));
            case MODS -> client.setScreen(new ModManagerScreen(this));
            case ADVENT -> client.setScreen(new AdventScreen(this));
            case GIVEAWAY -> client.setScreen(new GiveawayScreen(this));
            case ALERTS -> client.setScreen(new DonationsScreen(this));
            case YOUTUBE -> client.setScreen(new YoutubeScreen(this));
            case SPOTIFY -> client.setScreen(new SpotifyScreen(this));
            case COSMETICS -> client.setScreen(new CosmeticsScreen(this));
            case SYNC -> client.setScreen(new SyncScreen(this));
        }
    }

    private interface BoolSetter { void set(boolean v); }

    private int toggle(int x, int y, int cw, String name, boolean current, BoolSetter setter) {
        boolean[] state = {current};
        addDrawableChild(ButtonWidget.builder(label(name, current), b -> {
            state[0] = !state[0];
            setter.set(state[0]);
            ConfigManager.save();
            b.setMessage(label(name, state[0]));
        }).dimensions(x, y, cw, 20).build());
        return y + 24;
    }

    private static Text label(String name, boolean on) {
        return Text.literal(name + ": " + (on ? "ON" : "OFF"));
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        WabbitConfig cfg = ConfigManager.get();
        int panel = cfg.darkTheme ? 0xF0151821 : 0xF0F2F2F5;
        int side = cfg.darkTheme ? 0xF00E1016 : 0xF0DADCE3;
        ctx.fill(left, top, left + w, top + h, panel);
        ctx.fill(left, top, left + SIDEBAR_W, top + h, side);
        ctx.fill(left, top, left + w, top + 2, cfg.accent());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        WabbitConfig cfg = ConfigManager.get();
        int text = cfg.darkTheme ? 0xFFFFFFFF : 0xFF1A1A1A;
        int dim = cfg.darkTheme ? 0xFFA0A6B5 : 0xFF555B6B;

        ctx.drawText(textRenderer, "Wabbit Ultimate", left + 10, top + 12, cfg.accent(), true);
        ctx.drawText(textRenderer, "v0.10.0", left + 10, top + 24, dim, false);

        int cx = left + SIDEBAR_W + 12;
        ctx.drawText(textRenderer, page.label, cx, top + 12, text, true);

        int y = top + 40;
        switch (page) {
            case DASHBOARD -> {
                if (!searching) {
                    y = line(ctx, cx, y + 26, "Search above, or pick a page on the left.", dim);
                    y += 4;
                    y = line(ctx, cx, y, "Working now:", text);
                    y = line(ctx, cx, y, " - HUDs + editor, performance alerts", dim);
                    y = line(ctx, cx, y, " - Mod list, enable/disable, profiles", dim);
                    y = line(ctx, cx, y, " - DonutSMP: advent calendar, giveaways", dim);
                    y = line(ctx, cx, y, " - Donation alerts, YouTube, Spotify", dim);
                    y = line(ctx, cx, y, " - Cosmetics + cape, backup + sync", dim);
                    y += 4;
                    line(ctx, cx, y, "Rebind the menu key in Controls.", dim);
                }
            }
            case FEATURES -> ctx.drawText(textRenderer, "Changes save automatically.", cx, top + h - 20, dim, false);
            case MODS -> {
                y = line(ctx, cx, y + 26, "Lists every jar in your mods folder.", dim);
                y = line(ctx, cx, y, "Disable/Enable renames the jar (restart", dim);
                y = line(ctx, cx, y, "required). Mods are never unloaded live.", dim);
                line(ctx, cx, y, "Profiles can be bound to a server.", dim);
            }
            case DONUT -> {
                y = line(ctx, cx, y + 50, "Enter your balance once; both tools use it.", dim);
                y = line(ctx, cx, y, "Prizes are money amounts you hand out", dim);
                y = line(ctx, cx, y, "yourself. No server API is used.", dim);
                line(ctx, cx, y, "Balance HUD: Features page.", dim);
            }
            case MEDIA -> {
                y = line(ctx, cx, y + 74, "Alerts: manual, 4 editable tiers.", dim);
                y = line(ctx, cx, y, "YouTube: paste a link, see its title.", dim);
                line(ctx, cx, y, "Spotify: needs a one-time setup.", dim);
            }
            case COSMETICS -> {
                y = line(ctx, cx, y + 26, "16 hats, crowns, halos, masks, glasses,", dim);
                y = line(ctx, cx, y, "wings and back items, plus your own cape", dim);
                y = line(ctx, cx, y, "(PNG file). Favorites and 3 loadouts.", dim);
                line(ctx, cx, y, "Only you can see them; nobody else.", dim);
            }
            case SETTINGS -> {
                y = line(ctx, cx, y + 74, "Backup & Sync saves your settings and can", dim);
                y = line(ctx, cx, y, "share them between PCs via a cloud folder.", dim);
                line(ctx, cx, y, "Changes save automatically.", dim);
            }
        }
    }

    private int line(DrawContext ctx, int x, int y, String s, int color) {
        ctx.drawText(textRenderer, s, x, y, color, false);
        return y + 11;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
