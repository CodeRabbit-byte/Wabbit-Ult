package com.wabbit.ultimate.gui;

import com.wabbit.ultimate.config.ConfigManager;
import com.wabbit.ultimate.config.WabbitConfig;
import com.wabbit.ultimate.sync.SyncManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Settings backups and cloud sync through a folder your own cloud service keeps in sync. */
public class SyncScreen extends Screen {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final Screen parent;
    private int left, top, w, h;
    private String status = "";
    private TextFieldWidget folderField;
    private final List<String> backups = new ArrayList<>();
    private String cloudLine = "";
    private String warnLine = "";

    public SyncScreen(Screen parent) {
        super(Text.literal("Backup & Sync"));
        this.parent = parent;
    }

    private static String device() {
        return System.getProperty("os.name", "PC") + " (" + System.getProperty("user.name", "player") + ")";
    }

    private Path folder() {
        String f = ConfigManager.get().syncFolder;
        if (f == null || f.isBlank()) return null;
        try {
            return Path.of(f.trim());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void init() {
        w = Math.min(450, width - 12);
        h = Math.min(270, height - 8);
        left = (width - w) / 2;
        top = (height - h) / 2;
        WabbitConfig cfg = ConfigManager.get();

        addDrawableChild(ButtonWidget.builder(Text.literal("Create backup now"), b -> {
            try {
                ConfigManager.save();
                status = "Backup saved: " + SyncManager.backup(ConfigManager.file(), ConfigManager.backupDir());
            } catch (Exception e) {
                status = "Backup failed: " + e.getMessage();
            }
            clearAndInit();
        }).dimensions(left + 10, top + 22, 150, 18).build());

        backups.clear();
        backups.addAll(SyncManager.listBackups(ConfigManager.backupDir()));
        for (int i = 0; i < Math.min(4, backups.size()); i++) {
            String name = backups.get(i);
            addDrawableChild(ButtonWidget.builder(Text.literal("Restore"), b -> {
                try {
                    SyncManager.backup(ConfigManager.file(), ConfigManager.backupDir()); // safety copy of the current state
                    SyncManager.restore(ConfigManager.backupDir().resolve(name), ConfigManager.file());
                    ConfigManager.load();
                    status = "Restored " + name + ". (Your previous settings were backed up first.)";
                } catch (Exception e) {
                    status = "Restore failed: " + e.getMessage();
                }
                clearAndInit();
            }).dimensions(left + w - 10 - 60, top + 46 + i * 20, 60, 18).build());
        }

        folderField = new TextFieldWidget(textRenderer, left + 10, top + 146, w - 20, 18, Text.literal("Folder"));
        folderField.setMaxLength(400);
        folderField.setText(cfg.syncFolder == null ? "" : cfg.syncFolder);
        folderField.setChangedListener(s -> cfg.syncFolder = s.trim());
        addDrawableChild(folderField);

        int half = (w - 20 - 4) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("Push (this PC -> cloud)"), b -> push())
                .dimensions(left + 10, top + 168, half, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Pull (cloud -> this PC)"), b -> pull())
                .dimensions(left + 14 + half, top + 168, half, 18).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
                .dimensions(left + w - 10 - 80, top + h - 26, 80, 20).build());

        refreshCloudInfo();
    }

    private void refreshCloudInfo() {
        cloudLine = "";
        warnLine = "";
        Path f = folder();
        if (f == null) {
            cloudLine = "Pick the folder your cloud service syncs, e.g. inside Dropbox or OneDrive.";
            return;
        }
        try {
            SyncManager.Remote r = SyncManager.readRemote(f);
            if (r == null) {
                cloudLine = "No cloud copy in that folder yet. Press Push to create one.";
                return;
            }
            cloudLine = "Cloud copy: " + r.device() + ", "
                    + FMT.format(Instant.ofEpochMilli(r.savedAt()).atZone(ZoneId.systemDefault()));
            WabbitConfig cfg = ConfigManager.get();
            boolean remoteNewer = r.savedAt() > cfg.syncLastTime;
            long localMtime = Files.getLastModifiedTime(ConfigManager.file()).toMillis();
            boolean localChanged = localMtime > cfg.syncLastTime + 2000;
            if (remoteNewer && localChanged) {
                warnLine = "Both sides changed since your last sync. Push overwrites the cloud, Pull overwrites this PC.";
            } else if (remoteNewer) {
                warnLine = "The cloud copy is newer. Press Pull to get it.";
            } else if (localChanged) {
                warnLine = "You changed settings here since the last sync. Press Push to upload them.";
            } else {
                warnLine = "In sync.";
            }
        } catch (Exception e) {
            cloudLine = "Can't read that folder: " + e.getMessage();
        }
    }

    private void push() {
        Path f = folder();
        if (f == null) {
            status = "Type a folder first.";
            return;
        }
        try {
            ConfigManager.save();
            long now = System.currentTimeMillis();
            SyncManager.push(ConfigManager.file(), f, device(), now);
            ConfigManager.get().syncLastTime = now;
            ConfigManager.save();
            status = "Pushed to " + f + ". Your cloud service will upload it.";
        } catch (Exception e) {
            status = "Push failed: " + e.getMessage();
        }
        clearAndInit();
    }

    private void pull() {
        Path f = folder();
        if (f == null) {
            status = "Type a folder first.";
            return;
        }
        try {
            ConfigManager.save();
            String backup = SyncManager.pull(ConfigManager.file(), ConfigManager.backupDir(), f);
            String folderText = ConfigManager.get().syncFolder; // keep this machine's own folder setting
            ConfigManager.load();
            WabbitConfig cfg = ConfigManager.get();
            cfg.syncFolder = folderText;
            cfg.syncLastTime = System.currentTimeMillis();
            ConfigManager.save();
            status = "Pulled settings. Your old ones were backed up as " + backup + ".";
        } catch (Exception e) {
            status = "Pull failed: " + e.getMessage();
        }
        clearAndInit();
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

        ctx.drawText(textRenderer, "Backup & Sync", left + 10, top + 8, cfg.accent(), true);
        ctx.drawText(textRenderer, "Keeps the last " + SyncManager.KEEP_BACKUPS + " backups", left + 166, top + 27, dim, false);
        for (int i = 0; i < Math.min(4, backups.size()); i++) {
            ctx.drawText(textRenderer, backups.get(i), left + 10, top + 51 + i * 20, text, false);
        }
        if (backups.isEmpty()) {
            ctx.drawText(textRenderer, "No backups yet.", left + 10, top + 51, dim, false);
        }

        ctx.drawText(textRenderer, "Sync folder (Dropbox, OneDrive, iCloud Drive, Google Drive...):", left + 10, top + 134, dim, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(cloudLine, w - 20), left + 10, top + 192, text, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(warnLine, w - 20), left + 10, top + 203, cfg.accent(), false);
        ctx.drawText(textRenderer, "Spotify login stays on this PC. Everything else syncs.", left + 10, top + 214, dim, false);
        if (!status.isEmpty()) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(status, w - 20), left + 10, top + h - 38, 0xFFFF9090, false);
        }
    }

    @Override
    public void removed() {
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
