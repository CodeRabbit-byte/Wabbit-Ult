package com.wabbit.ultimate.gui;

import com.wabbit.ultimate.config.ConfigManager;
import com.wabbit.ultimate.config.WabbitConfig;
import com.wabbit.ultimate.cosmetics.CapeData;
import com.wabbit.ultimate.cosmetics.CapeLoader;
import com.wabbit.ultimate.cosmetics.Catalog;
import com.wabbit.ultimate.cosmetics.Cosmetic;
import com.wabbit.ultimate.cosmetics.Palette;
import com.wabbit.ultimate.cosmetics.Preview;
import com.wabbit.ultimate.cosmetics.Slot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Pick cosmetics, colours, a cape, favourites and saved loadouts, with a live spinning preview. */
public class CosmeticsScreen extends Screen {
    private static final String[] TABS = {"Hats", "Crowns", "Halos", "Masks", "Glasses", "Wings", "Back items", "Cape", "Favorites"};
    private static final int CAPE_TAB = 7, FAV_TAB = 8, ROWS = 5;
    private static int tab = 0;
    private static int page = 0;

    private record Label(String text, int x, int y, int color) {}

    private final Screen parent;
    private int left, top, w, h;
    private String status = "";
    private final List<Label> labels = new ArrayList<>();

    public CosmeticsScreen(Screen parent) {
        super(Text.literal("Cosmetics"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        w = Math.min(470, width - 12);
        h = Math.min(270, height - 8);
        left = (width - w) / 2;
        top = (height - h) / 2;
        WabbitConfig cfg = ConfigManager.get();
        labels.clear();

        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            addDrawableChild(ButtonWidget.builder(Text.literal((i == tab ? "> " : "  ") + TABS[i]), b -> {
                tab = idx;
                page = 0;
                clearAndInit();
            }).dimensions(left + 8, top + 24 + i * 19, 74, 17).build());
        }

        int lx = left + 88, lw = 190;
        if (tab == CAPE_TAB) buildCapeTab(cfg, lx, lw);
        else buildItemTab(cfg, lx, lw);

        // bottom controls
        int y1 = top + h - 50, y2 = top + h - 26;
        if (tab < Slot.values().length) {
            Slot slot = Slot.values()[tab];
            Cosmetic eq = Catalog.byId(cfg.cosmeticSlots.get(slot.id));
            int idx = eq == null ? 0 : tintIndex(cfg, slot, eq);
            ButtonWidget color = ButtonWidget.builder(Text.literal(eq != null && eq.hasTintable()
                    ? "Color: " + Palette.name(idx) : "Color: (equip first)"), b -> {
                cfg.cosmeticTints.put(slot.id, (tintIndex(cfg, slot, eq) + 1) % Palette.COLORS.length);
                ConfigManager.save();
                clearAndInit();
            }).dimensions(lx, y1, 140, 18).build();
            color.active = eq != null && eq.hasTintable();
            addDrawableChild(color);
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Cosmetics: " + (cfg.cosmeticsOn ? "ON" : "OFF")), b -> {
            cfg.cosmeticsOn = !cfg.cosmeticsOn;
            ConfigManager.save();
            b.setMessage(Text.literal("Cosmetics: " + (cfg.cosmeticsOn ? "ON" : "OFF")));
        }).dimensions(left + 232, y1, 110, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Clear all"), b -> {
            cfg.cosmeticSlots.clear();
            cfg.cosmeticTints.clear();
            ConfigManager.save();
            status = "All cosmetics removed.";
            clearAndInit();
        }).dimensions(left + 346, y1, 70, 18).build());

        for (int i = 0; i < 3; i++) {
            final int slotNo = i;
            addDrawableChild(ButtonWidget.builder(Text.literal("Save " + (i + 1)), b -> saveLoadout(slotNo))
                    .dimensions(left + 88 + i * 52, y2, 48, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Load " + (i + 1)), b -> loadLoadout(slotNo))
                    .dimensions(left + 248 + i * 52, y2, 48, 20).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
                .dimensions(left + 410, y2, 56, 20).build());
    }

    private static int tintIndex(WabbitConfig cfg, Slot slot, Cosmetic c) {
        Integer t = cfg.cosmeticTints.get(slot.id);
        return t == null ? (c == null ? 0 : c.defaultTint()) : t;
    }

    private void buildItemTab(WabbitConfig cfg, int lx, int lw) {
        List<Cosmetic> items = new ArrayList<>();
        if (tab == FAV_TAB) {
            for (String id : cfg.cosmeticFavs) {
                Cosmetic c = Catalog.byId(id);
                if (c != null) items.add(c);
            }
        } else if (tab < Slot.values().length) {
            items = Catalog.forSlot(Slot.values()[tab]);
        }
        int pages = Math.max(1, (items.size() + ROWS - 1) / ROWS);
        page = Math.max(0, Math.min(page, pages - 1));

        for (int i = 0; i < ROWS && page * ROWS + i < items.size(); i++) {
            Cosmetic c = items.get(page * ROWS + i);
            int y = top + 26 + i * 22;
            boolean equipped = c.id().equals(cfg.cosmeticSlots.get(c.slot().id));
            boolean fav = cfg.cosmeticFavs.contains(c.id());
            labels.add(new Label(c.name() + (tab == FAV_TAB ? " (" + c.slot().id + ")" : ""), lx, y + 5,
                    equipped ? cfg.accent() : (cfg.darkTheme ? 0xFFFFFFFF : 0xFF1A1A1A)));

            addDrawableChild(ButtonWidget.builder(Text.literal(equipped ? "Remove" : "Equip"), b -> {
                if (equipped) {
                    cfg.cosmeticSlots.remove(c.slot().id);
                    cfg.cosmeticTints.remove(c.slot().id);
                } else {
                    cfg.cosmeticSlots.put(c.slot().id, c.id());
                    cfg.cosmeticTints.put(c.slot().id, c.defaultTint());
                }
                ConfigManager.save();
                clearAndInit();
            }).dimensions(lx + lw - 24 - 56, y, 56, 18).build());

            addDrawableChild(ButtonWidget.builder(Text.literal(fav ? "*" : "o"), b -> {
                if (fav) cfg.cosmeticFavs.remove(c.id());
                else cfg.cosmeticFavs.add(c.id());
                ConfigManager.save();
                clearAndInit();
            }).dimensions(lx + lw - 20, y, 20, 18).build());
        }
        if (items.isEmpty()) {
            labels.add(new Label(tab == FAV_TAB ? "No favorites yet. Press the o next to an item." : "Nothing here.",
                    lx, top + 31, cfg.darkTheme ? 0xFF8A90A0 : 0xFF6A7080));
        }
        labels.add(new Label("* favorite   o not favorite", lx, top + 26 + ROWS * 22 + 2, cfg.darkTheme ? 0xFF8A90A0 : 0xFF6A7080));
        if (pages > 1) {
            addDrawableChild(ButtonWidget.builder(Text.literal("More (" + (page + 1) + "/" + pages + ")"), b -> {
                page = (page + 1) % pages;
                clearAndInit();
            }).dimensions(lx, top + 26 + ROWS * 22 + 14, 92, 18).build());
        }
    }

    private void buildCapeTab(WabbitConfig cfg, int lx, int lw) {
        List<String> files = CapeLoader.list();
        int pages = Math.max(1, (files.size() + ROWS - 1) / ROWS);
        page = Math.max(0, Math.min(page, pages - 1));
        int dim = cfg.darkTheme ? 0xFF8A90A0 : 0xFF6A7080;

        for (int i = 0; i < ROWS && page * ROWS + i < files.size(); i++) {
            String name = files.get(page * ROWS + i);
            int y = top + 26 + i * 22;
            boolean using = name.equals(cfg.capeFile);
            labels.add(new Label(textRenderer.trimToWidth(name, lw - 64), lx, y + 5, using ? cfg.accent() : (cfg.darkTheme ? 0xFFFFFFFF : 0xFF1A1A1A)));
            ButtonWidget use = ButtonWidget.builder(Text.literal(using ? "Using" : "Use"), b -> {
                cfg.capeFile = name;
                CapeLoader.reload();
                ConfigManager.save();
                status = CapeLoader.lastError().isEmpty() ? "Cape loaded." : CapeLoader.lastError();
                clearAndInit();
            }).dimensions(lx + lw - 56, y, 56, 18).build();
            use.active = !using;
            addDrawableChild(use);
        }
        if (files.isEmpty()) {
            labels.add(new Label("No cape files yet.", lx, top + 31, dim));
        }

        int cy = top + 26 + ROWS * 22 + 4;
        addDrawableChild(ButtonWidget.builder(Text.literal("Cape: " + (cfg.capeOn ? "ON" : "OFF")), b -> {
            cfg.capeOn = !cfg.capeOn;
            ConfigManager.save();
            b.setMessage(Text.literal("Cape: " + (cfg.capeOn ? "ON" : "OFF")));
        }).dimensions(lx, cy, 92, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Flip image"), b -> {
            cfg.capeFlip = !cfg.capeFlip;
            ConfigManager.save();
        }).dimensions(lx + 98, cy, 92, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Copy folder path"), b -> {
            MinecraftClient.getInstance().keyboard.setClipboard(CapeLoader.dir().toString());
            status = "Folder path copied.";
        }).dimensions(lx, cy + 22, 92, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Remove cape"), b -> {
            cfg.capeFile = "";
            CapeLoader.reload();
            ConfigManager.save();
            clearAndInit();
        }).dimensions(lx + 98, cy + 22, 92, 18).build());
        if (pages > 1) {
            addDrawableChild(ButtonWidget.builder(Text.literal("More files (" + (page + 1) + "/" + pages + ")"), b -> {
                page = (page + 1) % pages;
                clearAndInit();
            }).dimensions(lx, cy + 44, 190, 18).build());
        }
        labels.add(new Label("Put a PNG in the capes folder, then press Use.", lx, cy + 66, dim));
        labels.add(new Label("Cape files (64x32) use the cape area; any other", lx, cy + 77, dim));
        labels.add(new Label("picture is stretched to fit. Use Flip if reversed.", lx, cy + 88, dim));
    }

    private void saveLoadout(int i) {
        WabbitConfig cfg = ConfigManager.get();
        WabbitConfig.CosmeticLoadout l = new WabbitConfig.CosmeticLoadout();
        l.slots = new HashMap<>(cfg.cosmeticSlots);
        l.tints = new HashMap<>(cfg.cosmeticTints);
        l.capeFile = cfg.capeFile == null ? "" : cfg.capeFile;
        l.capeFlip = cfg.capeFlip;
        cfg.loadoutSlots()[i] = l;
        ConfigManager.save();
        status = "Saved loadout " + (i + 1) + ".";
    }

    private void loadLoadout(int i) {
        WabbitConfig cfg = ConfigManager.get();
        WabbitConfig.CosmeticLoadout l = cfg.loadoutSlots()[i];
        if (l == null) {
            status = "Loadout " + (i + 1) + " is empty.";
            return;
        }
        cfg.cosmeticSlots = new HashMap<>(l.slots);
        cfg.cosmeticTints = new HashMap<>(l.tints);
        cfg.capeFile = l.capeFile;
        cfg.capeFlip = l.capeFlip;
        CapeLoader.reload();
        ConfigManager.save();
        status = "Loaded loadout " + (i + 1) + ".";
        clearAndInit();
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        WabbitConfig cfg = ConfigManager.get();
        ctx.fill(left, top, left + w, top + h, cfg.darkTheme ? 0xF0151821 : 0xF0F2F2F5);
        ctx.fill(left, top, left + w, top + 2, cfg.accent());
        ctx.fill(left + 284, top + 22, left + w - 8, top + h - 56, cfg.darkTheme ? 0xFF0E1016 : 0xFFDADCE3);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        WabbitConfig cfg = ConfigManager.get();
        int dim = cfg.darkTheme ? 0xFF8A90A0 : 0xFF6A7080;

        ctx.drawText(textRenderer, "Cosmetics", left + 10, top + 8, cfg.accent(), true);
        ctx.drawText(textRenderer, "Only you can see these", left + 100, top + 8, dim, false);
        for (Label l : labels) ctx.drawText(textRenderer, l.text(), l.x(), l.y(), l.color(), false);

        int px1 = left + 284, px2 = left + w - 8;
        int cx = (px1 + px2) / 2;
        long ms = System.currentTimeMillis();

        if (tab == CAPE_TAB) {
            CapeData cape = CapeLoader.current();
            if (cape == null) {
                ctx.drawCenteredTextWithShadow(textRenderer, "No cape selected", cx, top + 80, dim);
                String err = CapeLoader.lastError();
                if (!err.isEmpty()) ctx.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(err, px2 - px1 - 8), cx, top + 94, 0xFFFF9090);
            } else {
                int cell = 3;
                int x0 = cx - CapeData.W * cell / 2;
                int y0 = top + 30;
                for (int gy = 0; gy < CapeData.H; gy++) {
                    for (int gx = 0; gx < CapeData.W; gx++) {
                        int c = cape.at(gx, gy, cfg.capeFlip);
                        if (((c >>> 24) & 0xFF) < 16) continue;
                        ctx.fill(x0 + gx * cell, y0 + gy * cell, x0 + (gx + 1) * cell, y0 + (gy + 1) * cell, c | 0xFF000000);
                    }
                }
                ctx.drawCenteredTextWithShadow(textRenderer, "Seen from behind", cx, y0 + CapeData.H * cell + 6, dim);
            }
        } else {
            List<Catalog.Placed> placed = Catalog.equipped(cfg);
            float angle = (ms / 25f) % 360f;
            float age = ms / 50f;
            for (Preview.Rect r : Preview.project(placed, true, age, angle, cx, top + 34, 4f)) {
                int base = r.color();
                int edge = (base & 0xFF000000)
                        | (((base >> 16 & 0xFF) * 6 / 10) << 16) | (((base >> 8 & 0xFF) * 6 / 10) << 8) | ((base & 0xFF) * 6 / 10);
                ctx.fill(r.x1(), r.y1(), r.x2(), r.y2(), edge);
                if (r.x2() - r.x1() > 2 && r.y2() - r.y1() > 2) ctx.fill(r.x1() + 1, r.y1() + 1, r.x2() - 1, r.y2() - 1, base);
            }
            ctx.drawCenteredTextWithShadow(textRenderer, placed.size() + " equipped", cx, top + h - 70, dim);
        }
        if (!status.isEmpty()) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(status, px2 - px1), px1, top + h - 52 - 12 + 0, cfg.accent(), false);
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
