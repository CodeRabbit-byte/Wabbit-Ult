package com.wabbit.ultimate.gui;

import com.wabbit.ultimate.config.ConfigManager;
import com.wabbit.ultimate.config.WabbitConfig;
import com.wabbit.ultimate.youtube.YoutubeInfo;
import com.wabbit.ultimate.youtube.YoutubeLink;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Paste a YouTube video or livestream link. Shows title + channel and keeps a short saved list. */
public class YoutubeScreen extends Screen {
    private static final int MAX_LINKS = 6;
    private final Screen parent;
    private int left, top, w, h;
    private String status = "";
    private TextFieldWidget field;
    private final List<WabbitConfig.YtLink> shown = new ArrayList<>();

    public YoutubeScreen(Screen parent) {
        super(Text.literal("YouTube Links"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        w = Math.min(450, width - 12);
        h = Math.min(260, height - 8);
        left = (width - w) / 2;
        top = (height - h) / 2;
        WabbitConfig cfg = ConfigManager.get();

        field = new TextFieldWidget(textRenderer, left + 10, top + 34, w - 20 - 64 - 64 - 8, 18, Text.literal("Link"));
        field.setMaxLength(300);
        addDrawableChild(field);
        setInitialFocus(field);

        addDrawableChild(ButtonWidget.builder(Text.literal("Paste"), b -> {
            String clip = MinecraftClient.getInstance().keyboard.getClipboard();
            field.setText(clip == null ? "" : clip.trim());
        }).dimensions(left + w - 10 - 64 - 4 - 64, top + 34, 64, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Add"), b -> addLink())
                .dimensions(left + w - 10 - 64, top + 34, 64, 18).build());

        shown.clear();
        for (int i = 0; i < cfg.ytLinks.size() && i < MAX_LINKS; i++) {
            WabbitConfig.YtLink l = cfg.ytLinks.get(i);
            shown.add(l);
            int y = top + 66 + i * 24;
            int x = left + w - 10;

            addDrawableChild(ButtonWidget.builder(Text.literal("X"), b -> {
                cfg.ytLinks.remove(l);
                if (l.id.equals(cfg.ytSelectedId)) cfg.ytSelectedId = null;
                ConfigManager.save();
                status = "Removed.";
                clearAndInit();
            }).dimensions(x - 22, y, 22, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Copy"), b -> {
                MinecraftClient.getInstance().keyboard.setClipboard(YoutubeLink.watchUrl(l.id));
                status = "Link copied. Paste it into your browser to watch.";
            }).dimensions(x - 22 - 4 - 44, y, 44, 20).build());

            boolean selected = l.id.equals(cfg.ytSelectedId);
            ButtonWidget show = ButtonWidget.builder(Text.literal(selected ? "Shown" : "Show"), b -> {
                cfg.ytSelectedId = l.id;
                ConfigManager.save();
                clearAndInit();
            }).dimensions(x - 22 - 4 - 44 - 4 - 52, y, 52, 20).build();
            show.active = !selected;
            addDrawableChild(show);
        }

        int by = top + h - 26;
        int bw = (w - 20 - 8) / 3;
        addDrawableChild(ButtonWidget.builder(Text.literal("HUD title: " + (cfg.ytHud ? "ON" : "OFF")), b -> {
            cfg.ytHud = !cfg.ytHud;
            ConfigManager.save();
            b.setMessage(Text.literal("HUD title: " + (cfg.ytHud ? "ON" : "OFF")));
        }).dimensions(left + 10, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Clear all"), b -> {
            cfg.ytLinks.clear();
            cfg.ytSelectedId = null;
            ConfigManager.save();
            status = "List cleared.";
            clearAndInit();
        }).dimensions(left + 14 + bw, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
                .dimensions(left + 18 + 2 * bw, by, bw, 20).build());
    }

    private void addLink() {
        WabbitConfig cfg = ConfigManager.get();
        String id = YoutubeLink.parseId(field.getText());
        if (id == null) {
            status = "That is not a YouTube link. Paste a youtube.com or youtu.be link.";
            return;
        }
        for (WabbitConfig.YtLink existing : cfg.ytLinks) {
            if (existing.id.equals(id)) {
                cfg.ytSelectedId = id;
                ConfigManager.save();
                status = "Already in your list. Now shown on the HUD.";
                clearAndInit();
                return;
            }
        }
        if (cfg.ytLinks.size() >= MAX_LINKS) {
            status = "List is full (" + MAX_LINKS + "). Remove one first.";
            return;
        }
        WabbitConfig.YtLink link = new WabbitConfig.YtLink(id, "Loading...", "");
        cfg.ytLinks.add(link);
        cfg.ytSelectedId = id;
        ConfigManager.save();
        status = "Looking up title...";
        clearAndInit();

        MinecraftClient mc = MinecraftClient.getInstance();
        YoutubeInfo.fetch(id, res -> mc.execute(() -> {
            if (res.ok()) {
                link.title = res.title();
                link.channel = res.channel();
                if (mc.currentScreen == this) status = "Added.";
            } else {
                link.title = "(title unavailable)";
                link.channel = "";
                if (mc.currentScreen == this) status = res.error();
            }
            ConfigManager.save();
            if (mc.currentScreen == this) clearAndInit();
        }));
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

        ctx.drawText(textRenderer, "YouTube Links", left + 10, top + 8, cfg.accent(), true);
        ctx.drawText(textRenderer, "Paste a video or livestream link, then press Add.", left + 10, top + 22, dim, false);

        int maxText = w - 20 - (52 + 4 + 44 + 4 + 22) - 10;
        for (int i = 0; i < shown.size(); i++) {
            WabbitConfig.YtLink l = shown.get(i);
            boolean selected = l.id.equals(cfg.ytSelectedId);
            int y = top + 66 + i * 24;
            ctx.drawText(textRenderer, textRenderer.trimToWidth(l.title == null ? l.id : l.title, maxText),
                    left + 10, y + 1, selected ? cfg.accent() : text, false);
            String sub = (l.channel == null || l.channel.isEmpty()) ? l.id : l.channel;
            ctx.drawText(textRenderer, textRenderer.trimToWidth(sub, maxText), left + 10, y + 11, dim, false);
        }
        if (shown.isEmpty()) {
            ctx.drawText(textRenderer, "No links yet.", left + 10, top + 68, dim, false);
        }
        if (!status.isEmpty()) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(status, w - 20), left + 10, top + h - 38, cfg.accent(), false);
        }
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
