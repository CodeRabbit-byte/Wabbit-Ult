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

/** Save the current enabled/disabled jar state as a profile, apply it later, bind it to a server. */
public class ProfilesScreen extends Screen {
    private static final int MAX = 5;
    private final Screen parent;
    private String status = "";
    private int left, top, w, h;
    private final List<String> labels = new ArrayList<>();

    public ProfilesScreen(Screen parent) {
        super(Text.literal("Mod Profiles"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        w = Math.min(430, width - 16);
        h = Math.min(220, height - 16);
        left = (width - w) / 2;
        top = (height - h) / 2;
        WabbitConfig cfg = ConfigManager.get();
        labels.clear();

        List<WabbitConfig.ModProfile> profiles = cfg.modProfiles;
        for (int i = 0; i < profiles.size() && i < MAX; i++) {
            WabbitConfig.ModProfile p = profiles.get(i);
            int y = top + 50 + i * 24;
            int diff = ModManager.diffCount(p);
            boolean here = ModManager.currentServer != null
                    && p.name.equals(cfg.serverProfiles.get(ModManager.currentServer));
            labels.add(p.name + (here ? "  [this server]" : "") + "  (" + diff + " differ)");

            int delX = left + w - 8 - 52;
            int useX = delX - 4 - 64;
            int applyX = useX - 4 - 52;
            addDrawableChild(ButtonWidget.builder(Text.literal("Apply"), b -> {
                status = ModManager.applyProfile(p);
                clearAndInit();
            }).dimensions(applyX, y, 52, 20).build());

            ButtonWidget use = ButtonWidget.builder(Text.literal(here ? "Unset" : "Use here"), b -> {
                if (here) cfg.serverProfiles.remove(ModManager.currentServer);
                else cfg.serverProfiles.put(ModManager.currentServer, p.name);
                ConfigManager.save();
                clearAndInit();
            }).dimensions(useX, y, 64, 20).build();
            use.active = ModManager.currentServer != null;
            addDrawableChild(use);

            addDrawableChild(ButtonWidget.builder(Text.literal("Delete"), b -> {
                cfg.modProfiles.remove(p);
                cfg.serverProfiles.values().removeIf(n -> n.equals(p.name));
                ConfigManager.save();
                status = "Deleted '" + p.name + "'";
                clearAndInit();
            }).dimensions(delX, y, 52, 20).build());
        }

        int by = top + h - 26;
        ButtonWidget save = ButtonWidget.builder(Text.literal("Save current state as profile"), b -> {
            int n = 1;
            while (ModManager.findProfile("Profile " + n) != null) n++;
            cfg.modProfiles.add(ModManager.captureProfile("Profile " + n));
            ConfigManager.save();
            status = "Saved 'Profile " + n + "'";
            clearAndInit();
        }).dimensions(left + 10, by, 190, 20).build();
        save.active = profiles.size() < MAX;
        addDrawableChild(save);
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
                .dimensions(left + w - 10 - 80, by, 80, 20).build());
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

        ctx.drawText(textRenderer, "Mod Profiles", left + 10, top + 10, cfg.accent(), true);
        String server = ModManager.currentServer == null ? "not on a server" : ModManager.currentServer;
        ctx.drawText(textRenderer, "Server: " + server, left + 10, top + 22, dim, false);
        ctx.drawText(textRenderer, "Applying renames jars; restart needed.", left + 10, top + 34, dim, false);

        int maxText = w - 10 - (52 + 4 + 64 + 4 + 52) - 24;
        for (int i = 0; i < labels.size(); i++) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(labels.get(i), maxText),
                    left + 10, top + 56 + i * 24, text, false);
        }
        if (labels.isEmpty()) {
            ctx.drawText(textRenderer, "No profiles yet. Set mods how you want, then save.",
                    left + 10, top + 56, dim, false);
        }
        if (!status.isEmpty()) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(status, w - 20),
                    left + 10, top + h - 40, cfg.accent(), false);
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
