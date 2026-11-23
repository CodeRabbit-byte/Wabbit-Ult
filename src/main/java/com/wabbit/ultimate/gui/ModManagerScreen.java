package com.wabbit.ultimate.gui;

import com.wabbit.ultimate.config.ConfigManager;
import com.wabbit.ultimate.config.WabbitConfig;
import com.wabbit.ultimate.mods.ModManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Lists every jar in the mods folder. Disable/Enable renames the jar (restart required). */
public class ModManagerScreen extends Screen {
    private final Screen parent;
    private static int page = 0;
    private static String status = "";
    private int left, top, w, h, rows, pages;
    private List<ModManager.Entry> all = new ArrayList<>();
    private final List<ModManager.Entry> shown = new ArrayList<>();

    public ModManagerScreen(Screen parent) {
        super(Text.literal("Mod Manager"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        w = Math.min(430, width - 16);
        h = Math.min(250, height - 16);
        left = (width - w) / 2;
        top = (height - h) / 2;
        rows = Math.max(3, (h - 50 - 44) / 22);

        all = ModManager.list();
        pages = Math.max(1, (all.size() + rows - 1) / rows);
        page = Math.max(0, Math.min(page, pages - 1));

        shown.clear();
        int start = page * rows;
        for (int i = 0; i < rows && start + i < all.size(); i++) {
            ModManager.Entry e = all.get(start + i);
            shown.add(e);
            int y = top + 50 + i * 22;
            int bx = left + w - 8 - 62;
            addDrawableChild(ButtonWidget.builder(Text.literal(e.enabled() ? "Disable" : "Enable"), b -> {
                status = ModManager.setEnabled(e.jar(), !e.enabled()).message();
                clearAndInit();
            }).dimensions(bx, y, 62, 20).build());

            if (e.info() != null && e.enabled()) {
                String id = e.info().id();
                ButtonWidget flag = ButtonWidget.builder(
                        Text.literal(ModManager.isFlagged(id) ? "Unflag" : "Flag"), b -> {
                            ModManager.toggleFlag(id);
                            clearAndInit();
                        }).dimensions(bx - 4 - 50, y, 50, 20).build();
                flag.active = ModManager.currentServer != null;
                addDrawableChild(flag);
            }
        }

        int by = top + h - 26;
        int x = left + (w - 302) / 2;
        ButtonWidget prev = ButtonWidget.builder(Text.literal("< Prev"), b -> {
            page--;
            clearAndInit();
        }).dimensions(x, by, 70, 20).build();
        prev.active = page > 0;
        addDrawableChild(prev);
        ButtonWidget next = ButtonWidget.builder(Text.literal("Next >"), b -> {
            page++;
            clearAndInit();
        }).dimensions(x + 74, by, 70, 20).build();
        next.active = page < pages - 1;
        addDrawableChild(next);
        addDrawableChild(ButtonWidget.builder(Text.literal("Profiles..."),
                b -> client.setScreen(new ProfilesScreen(this)))
                .dimensions(x + 148, by, 80, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
                .dimensions(x + 232, by, 70, 20).build());
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

        ctx.drawText(textRenderer, "Mod Manager", left + 10, top + 10, cfg.accent(), true);
        String server = ModManager.currentServer == null ? "no server (flags need a server)" : ModManager.currentServer;
        ctx.drawText(textRenderer, "Server: " + server, left + 10, top + 22, dim, false);
        if (ModManager.pendingRestart) {
            ctx.drawText(textRenderer, "Restart required to apply changes.", left + 10, top + 34, cfg.accent(), false);
        }
        if (!status.isEmpty()) {
            int sw = textRenderer.getWidth(status);
            ctx.drawText(textRenderer, status, left + w - 10 - sw, top + 34, text, false);
        }

        int maxText = w - 8 - 62 - 4 - 50 - 14;
        for (int i = 0; i < shown.size(); i++) {
            ModManager.Entry e = shown.get(i);
            String label = e.name() + (e.version().isEmpty() ? "" : " " + e.version());
            if (!e.enabled()) label += " (disabled)";
            boolean flagged = e.info() != null && ModManager.isFlagged(e.info().id());
            if (flagged) label = "! " + label;
            int color = !e.enabled() ? dim : (flagged ? cfg.accent() : text);
            ctx.drawText(textRenderer, textRenderer.trimToWidth(label, maxText),
                    left + 10, top + 56 + i * 22, color, false);
        }
        ctx.drawText(textRenderer, "Page " + (page + 1) + "/" + pages + "  -  " + all.size() + " jars",
                left + 10, top + h - 38, dim, false);
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
