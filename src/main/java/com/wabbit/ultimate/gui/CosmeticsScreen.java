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
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Pick cosmetics, colours, a cape, favourites and saved loadouts, with a live spinning preview. */
public class CosmeticsScreen extends Screen {
    private static final String[] TABS = {"Hats", "Crowns", "Halos", "Masks", "Glasses", "Wings", "Back items", "Cape", "Favorites"};
    private static final int CAPE_TAB = 7, FAV_TAB = 8, MAX_ROWS = 5;
    private static final String CAPE_HELP = "Put a PNG in the capes folder (Copy folder path), then press Use. "
            + "Cape files (64x32, 128x64...) use the cape layout; any other picture is stretched over the cape. "
            + "Use Flip if it looks reversed.";
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
        int y1 = top + h - 50, y2 = top + h - 26;

        // tabs shrink on short screens so they never run into the bottom buttons
        int step = Math.max(12, Math.min(19, (y1 - 4 - (top + 24)) / TABS.length));
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            addDrawableChild(ButtonWidget.builder(Text.literal((i == tab ? "> " : "  ") + TABS[i]), b -> {
                tab = idx;
                page = 0;
                clearAndInit();
            }).dimensions(left + 8, top + 24 + i * step, 74, step - 2).build());
        }

        int lx = left + 88, lw = 190;
        if (tab == CAPE_TAB) buildCapeTab(cfg, lx, lw, rowsFit(y1, 96));
        else buildItemTab(cfg, lx, lw, rowsFit(y1, 62));

        // bottom controls, laid out from the right edge so they fit narrow screens
        int right = left + w - 8;
        int clearX = right - 70, toggleX = clearX - 4 - 110;
        if (tab < Slot.values().length) {
            Slot slot = Slot.values()[tab];
            Cosmetic eq = Catalog.byId(cfg.cosmeticSlots.get(slot.id));
            int idx = eq == null ? 0 : tintIndex(cfg, slot, eq);
            ButtonWidget color = ButtonWidget.builder(Text.literal(eq != null && eq.hasTintable()
                    ? "Color: " + Palette.name(idx) : "Color: (equip first)"), b -> {
                cfg.cosmeticTints.put(slot.id, (tintIndex(cfg, slot, eq) + 1) % Palette.COLORS.length);
                ConfigManager.save();
                clearAndInit();
            }).dimensions(lx, y1, Math.max(60, Math.min(140, toggleX - 4 - lx)), 18).build();
            color.active = eq != null && eq.hasTintable();
            addDrawableChild(color);
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Cosmetics: " + (cfg.cosmeticsOn ? "ON" : "OFF")), b -> {
            cfg.cosmeticsOn = !cfg.cosmeticsOn;
            ConfigManager.save();
            b.setMessage(Text.literal("Cosmetics: " + (cfg.cosmeticsOn ? "ON" : "OFF")));
        }).dimensions(toggleX, y1, 110, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Clear all"), b -> {
            cfg.cosmeticSlots.clear();
            cfg.cosmeticTints.clear();
            ConfigManager.save();
            status = "All cosmetics removed.";
            clearAndInit();
        }).dimensions(clearX, y1, 70, 18).build());

        int bw = Math.max(30, Math.min(48, (right - lx - 56 - 6 - 8 - 5 * 4) / 6));
        int loadX = lx + 3 * (bw + 4) + 8;
        for (int i = 0; i < 3; i++) {
            final int slotNo = i;
            addDrawableChild(ButtonWidget.builder(Text.literal("Save " + (i + 1)), b -> saveLoadout(slotNo))
                    .dimensions(lx + i * (bw + 4), y2, bw, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Load " + (i + 1)), b -> loadLoadout(slotNo))
                    .dimensions(loadX + i * (bw + 4), y2, bw, 20).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
                .dimensions(right - 56, y2, 56, 20).build());
    }

    /** How many 22-pixel list rows fit above the bottom buttons, leaving {@code below} pixels for the extras. */
    private int rowsFit(int y1, int below) {
        return Math.max(1, Math.min(MAX_ROWS, (y1 - top - below) / 22));
    }

    private static int tintIndex(WabbitConfig cfg, Slot slot, Cosmetic c) {
        Integer t = cfg.cosmeticTints.get(slot.id);
        return t == null ? (c == null ? 0 : c.defaultTint()) : t;
    }

    private void buildItemTab(WabbitConfig cfg, int lx, int lw, int rows) {
        List<Cosmetic> items = new ArrayList<>();
        if (tab == FAV_TAB) {
            for (String id : cfg.cosmeticFavs) {
                Cosmetic c = Catalog.byId(id);
                if (c != null) items.add(c);
            }
        } else if (tab < Slot.values().length) {
            items = Catalog.forSlot(Slot.values()[tab]);
        }
        int pages = Math.max(1, (items.size() + rows - 1) / rows);
        page = Math.max(0, Math.min(page, pages - 1));

        for (int i = 0; i < rows && page * rows + i < items.size(); i++) {
            Cosmetic c = items.get(page * rows + i);
            int y = top + 26 + i * 22;
            boolean equipped = c.id().equals(cfg.cosmeticSlots.get(c.slot().id));
            boolean fav = cfg.cosmeticFavs.contains(c.id());
            String name = c.name() + (tab == FAV_TAB ? " (" + c.slot().id + ")" : "");
            labels.add(new Label(textRenderer.trimToWidth(name, lw - 84), lx, y + 5,
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
        labels.add(new Label("* favorite   o not favorite", lx, top + 26 + rows * 22 + 2, cfg.darkTheme ? 0xFF8A90A0 : 0xFF6A7080));
        if (pages > 1) {
            addDrawableChild(ButtonWidget.builder(Text.literal("More (" + (page + 1) + "/" + pages + ")"), b -> {
                page = (page + 1) % pages;
                clearAndInit();
            }).dimensions(lx, top + 26 + rows * 22 + 14, 92, 18).build());
        }
    }

    private void buildCapeTab(WabbitConfig cfg, int lx, int lw, int rows) {
        List<String> files = CapeLoader.list();
        int pages = Math.max(1, (files.size() + rows - 1) / rows);
        page = Math.max(0, Math.min(page, pages - 1));
        int dim = cfg.darkTheme ? 0xFF8A90A0 : 0xFF6A7080;

        for (int i = 0; i < rows && page * rows + i < files.size(); i++) {
            String name = files.get(page * rows + i);
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

        int cy = top + 26 + rows * 22 + 4;
        addDrawableChild(ButtonWidget.builder(Text.literal("Cape: " + (cfg.capeOn ? "ON" : "OFF")), b -> {
            cfg.capeOn = !cfg.capeOn;
            ConfigManager.save();
            b.setMessage(Text.literal("Cape: " + (cfg.capeOn ? "ON" : "OFF")));
        }).dimensions(lx, cy, 92, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Flip image"), b -> {
            cfg.capeFlip = !cfg.capeFlip;
            ConfigManager.save();
            status = cfg.capeFlip ? "Cape flipped." : "Cape not flipped.";
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
        // older or hand-edited configs can hold nulls here
        cfg.cosmeticSlots = l.slots == null ? new HashMap<>() : new HashMap<>(l.slots);
        cfg.cosmeticTints = l.tints == null ? new HashMap<>() : new HashMap<>(l.tints);
        cfg.capeFile = l.capeFile == null ? "" : l.capeFile;
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

        // preview panel
        int px1 = left + 284, px2 = left + w - 8, py1 = top + 22, py2 = top + h - 56;
        int cx = (px1 + px2) / 2;
        long ms = System.currentTimeMillis();
        ctx.enableScissor(px1, py1, px2, py2);

        if (tab == CAPE_TAB) {
            CapeData cape = CapeLoader.current();
            int helpY;
            if (cape == null) {
                ctx.drawCenteredTextWithShadow(textRenderer, "No cape selected", cx, py1 + 30, dim);
                String err = CapeLoader.lastError();
                if (!err.isEmpty()) ctx.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(err, px2 - px1 - 8), cx, py1 + 44, 0xFFFF9090);
                helpY = py1 + 62;
            } else {
                int cell = py2 - py1 >= 180 ? 3 : 2;
                int x0 = cx - CapeData.W * cell / 2;
                int y0 = py1 + 8;
                for (int gy = 0; gy < CapeData.H; gy++) {
                    for (int gx = 0; gx < CapeData.W; gx++) {
                        int c = cape.at(gx, gy, cfg.capeFlip);
                        if (((c >>> 24) & 0xFF) < 16) continue;
                        ctx.fill(x0 + gx * cell, y0 + gy * cell, x0 + (gx + 1) * cell, y0 + (gy + 1) * cell, c | 0xFF000000);
                    }
                }
                ctx.drawCenteredTextWithShadow(textRenderer, "Seen from behind", cx, y0 + CapeData.H * cell + 4, dim);
                helpY = y0 + CapeData.H * cell + 18;
            }
            for (OrderedText line : textRenderer.wrapLines(Text.literal(CAPE_HELP), px2 - px1 - 10)) {
                if (helpY + 9 > py2 - 13) break; // keep the status line free
                ctx.drawText(textRenderer, line, px1 + 5, helpY, dim, false);
                helpY += 10;
            }
        } else {
            List<Catalog.Placed> placed = Catalog.equipped(cfg);
            boolean backBusy = false;
            for (Catalog.Placed p : placed) if (p.item().slot() == Slot.WINGS || p.item().slot() == Slot.BACK) backBusy = true;
            CapeData cape = cfg.capeOn && !backBusy ? CapeLoader.current() : null;

            // fit the tallest item (wizard hat) and the feet inside the panel, above the two text lines
            float areaTop = py1 + 4, areaBottom = py2 - 26;
            float scale = Math.min(4f, Math.min((areaBottom - areaTop) / (Preview.BOTTOM_Y - Preview.TOP_Y), (px2 - px1 - 8) / 26f));
            float headTop = areaTop + (-8f - Preview.TOP_Y) * scale;
            float angle = (ms / 25f) % 360f;
            float age = ms / 50f;
            for (Preview.Rect r : Preview.project(placed, true, cape, cfg.capeFlip, age, angle, cx, headTop, scale)) {
                int base = r.color();
                if (!r.outline()) {
                    ctx.fill(r.x1(), r.y1(), r.x2(), r.y2(), base);
                    continue;
                }
                int edge = (base & 0xFF000000)
                        | (((base >> 16 & 0xFF) * 6 / 10) << 16) | (((base >> 8 & 0xFF) * 6 / 10) << 8) | ((base & 0xFF) * 6 / 10);
                ctx.fill(r.x1(), r.y1(), r.x2(), r.y2(), edge);
                if (r.x2() - r.x1() > 2 && r.y2() - r.y1() > 2) ctx.fill(r.x1() + 1, r.y1() + 1, r.x2() - 1, r.y2() - 1, base);
            }
            ctx.drawCenteredTextWithShadow(textRenderer, placed.size() + " equipped", cx, py2 - 22, dim);
        }
        if (!status.isEmpty()) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(status, px2 - px1 - 8), px1 + 4, py2 - 11, cfg.accent(), false);
        }
        ctx.disableScissor();
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
