package com.wabbit.ultimate.gui;

import com.wabbit.ultimate.config.ConfigManager;
import com.wabbit.ultimate.config.WabbitConfig;
import com.wabbit.ultimate.donut.Advent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Random;

/** Christmas RNG advent calendar: enter a balance, get a budget and 24 random prizes. */
public class AdventScreen extends Screen {
    private final Screen parent;
    private final Random rnd = new Random();
    private int left, top, w, h, doorW;
    private static final int DOOR_H = 24;
    private final int[] doorX = new int[24];
    private final int[] doorY = new int[24];
    private final long[] openTime = new long[24];
    private long jackpotUntil;
    private String status = "";
    private TextFieldWidget field;

    // snowfall
    private final float[] fx = new float[70], fy = new float[70], fv = new float[70];
    private final int[] fs = new int[70];
    private boolean snowReady;
    private long lastNanos;

    public AdventScreen(Screen parent) {
        super(Text.literal("Advent Calendar"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        w = Math.min(450, width - 12);
        h = Math.min(270, height - 8);
        left = (width - w) / 2;
        top = (height - h) / 2;
        WabbitConfig cfg = ConfigManager.get();

        field = new TextFieldWidget(textRenderer, left + 64, top + 22, 110, 18, Text.literal("Balance"));
        field.setMaxLength(24);
        field.setText(cfg.adventBalanceText);
        field.setChangedListener(s -> cfg.adventBalanceText = s);
        addDrawableChild(field);

        addDrawableChild(ButtonWidget.builder(Text.literal(Advent.TIER_NAMES[Math.floorMod(cfg.adventTier, 4)]), b -> {
            cfg.adventTier = (cfg.adventTier + 1) % 4;
            ConfigManager.save();
            clearAndInit();
        }).dimensions(left + 180, top + 22, 112, 18).build());

        addDrawableChild(ButtonWidget.builder(Text.literal(cfg.advent == null ? "Generate" : "Regenerate"), b -> generate())
                .dimensions(left + 296, top + 22, w - 306, 18).build());

        doorW = (w - 20 - 5 * 4) / 6;
        for (int i = 0; i < 24; i++) {
            final int idx = i;
            doorX[i] = left + 10 + (i % 6) * (doorW + 4);
            doorY[i] = top + 84 + (i / 6) * 28;
            WabbitConfig.AdventState st = cfg.advent;
            boolean open = st != null && st.opened[i];
            String label = open ? Advent.format(st.prizes[i]) : Integer.toString(i + 1);
            ButtonWidget door = ButtonWidget.builder(Text.literal(label), b -> openDoor(idx))
                    .dimensions(doorX[i], doorY[i], doorW, DOOR_H).build();
            door.active = st != null && !open;
            addDrawableChild(door);
        }

        int by = top + h - 26;
        int bw = (w - 20 - 3 * 4) / 4;
        addDrawableChild(ButtonWidget.builder(Text.literal("Reveal all"), b -> setAll(true))
                .dimensions(left + 10, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Hide all"), b -> setAll(false))
                .dimensions(left + 14 + bw, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Copy list"), b -> copyList())
                .dimensions(left + 18 + 2 * bw, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
                .dimensions(left + 22 + 3 * bw, by, bw, 20).build());

        if (!snowReady) {
            for (int i = 0; i < fx.length; i++) respawn(i, true);
            snowReady = true;
        }
    }

    private void respawn(int i, boolean anywhere) {
        fx[i] = rnd.nextFloat() * Math.max(1, width);
        fy[i] = anywhere ? rnd.nextFloat() * Math.max(1, height) : -4;
        fv[i] = 12 + rnd.nextFloat() * 22;
        fs[i] = 1 + rnd.nextInt(2);
    }

    private void generate() {
        WabbitConfig cfg = ConfigManager.get();
        long balance = Advent.parseAmount(cfg.adventBalanceText);
        if (balance < 0) {
            status = "Enter your balance like 2.5B, 750M, 12k or 12000000.";
            return;
        }
        long budget = Advent.budgetFor(balance, cfg.adventTier);
        if (budget < Advent.MIN_BUDGET) {
            status = "Budget is too small (needs at least " + Advent.MIN_BUDGET + "). Try a bigger tier or balance.";
            return;
        }
        cfg.advent = Advent.generate(balance, budget, cfg.adventChances, rnd);
        ConfigManager.save();
        status = "New calendar generated.";
        clearAndInit();
    }

    private void openDoor(int i) {
        WabbitConfig.AdventState st = ConfigManager.get().advent;
        if (st == null || st.opened[i]) return;
        st.opened[i] = true;
        openTime[i] = System.currentTimeMillis();
        if (st.rarities[i] == 5) jackpotUntil = System.currentTimeMillis() + 3000;
        ConfigManager.save();
        clearAndInit();
    }

    private void setAll(boolean open) {
        WabbitConfig.AdventState st = ConfigManager.get().advent;
        if (st == null) {
            status = "Generate a calendar first.";
            return;
        }
        for (int i = 0; i < 24; i++) st.opened[i] = open;
        ConfigManager.save();
        clearAndInit();
    }

    private void copyList() {
        WabbitConfig.AdventState st = ConfigManager.get().advent;
        if (st == null) {
            status = "Generate a calendar first.";
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            sb.append("Door ").append(i + 1).append(": ").append(Advent.format(st.prizes[i]))
                    .append(" (").append(Advent.RARITY_NAMES[st.rarities[i]]).append(")\n");
        }
        sb.append("Total: ").append(Advent.format(Advent.total(st)));
        MinecraftClient.getInstance().keyboard.setClipboard(sb.toString());
        status = "Prize list copied to clipboard.";
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fill(left, top, left + w, top + h, 0xF01A2B20);
        ctx.fill(left, top, left + w, top + 3, 0xFFC0392B);
        ctx.fill(left, top + 3, left + w, top + 4, 0xFFE6B422);

        // snowfall
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        for (int i = 0; i < fx.length; i++) {
            fy[i] += fv[i] * dt;
            fx[i] += (float) Math.sin((fy[i] + i * 7) / 18f) * 6f * dt;
            if (fy[i] > height) respawn(i, false);
            ctx.fill((int) fx[i], (int) fy[i], (int) fx[i] + fs[i], (int) fy[i] + fs[i], 0xCCFFFFFF);
        }

        // decorations in the gap above the bottom buttons
        int dy = top + 204;
        tree(ctx, left + 12, dy);
        present(ctx, left + 44, dy + 14);
        candyCane(ctx, left + 68, dy + 8);
        tree(ctx, left + w - 32, dy);
        present(ctx, left + w - 62, dy + 14);
        candyCane(ctx, left + w - 80, dy + 8);
    }

    private void tree(DrawContext c, int x, int y) {
        int green = 0xFF2E9D4A;
        c.fill(x + 8, y - 3, x + 12, y, 0xFFFFD84A);
        c.fill(x + 7, y, x + 13, y + 6, green);
        c.fill(x + 4, y + 6, x + 16, y + 13, green);
        c.fill(x + 1, y + 13, x + 19, y + 22, green);
        c.fill(x + 8, y + 22, x + 12, y + 28, 0xFF6B4423);
        c.fill(x + 6, y + 9, x + 8, y + 11, 0xFFE03A3A);
        c.fill(x + 12, y + 16, x + 14, y + 18, 0xFFFFD84A);
        c.fill(x + 5, y + 18, x + 7, y + 20, 0xFF55AAFF);
    }

    private void present(DrawContext c, int x, int y) {
        c.fill(x, y, x + 16, y + 12, 0xFFD13030);
        c.fill(x + 7, y, x + 9, y + 12, 0xFFFFD84A);
        c.fill(x, y + 5, x + 16, y + 7, 0xFFFFD84A);
    }

    private void candyCane(DrawContext c, int x, int y) {
        for (int k = 0; k < 5; k++) {
            c.fill(x, y + k * 4, x + 3, y + k * 4 + 4, k % 2 == 0 ? 0xFFE03A3A : 0xFFFFFFFF);
        }
        c.fill(x, y - 3, x + 8, y, 0xFFE03A3A);
        c.fill(x + 5, y, x + 8, y + 3, 0xFFFFFFFF);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        WabbitConfig cfg = ConfigManager.get();
        WabbitConfig.AdventState st = cfg.advent;
        int white = 0xFFFFFFFF, gold = 0xFFFFD84A, dim = 0xFFB5C4B8;

        ctx.drawText(textRenderer, "Christmas RNG Advent Calendar", left + 10, top + 9, gold, true);
        ctx.drawText(textRenderer, "Balance:", left + 10, top + 27, white, false);

        LocalDate today = LocalDate.now();
        LocalDate xmas = LocalDate.of(today.getYear(), 12, 25);
        if (today.isAfter(xmas)) xmas = xmas.plusYears(1);
        long days = ChronoUnit.DAYS.between(today, xmas);
        String cd = days == 0 ? "Merry Christmas!" : days + " days until Christmas";
        ctx.drawText(textRenderer, cd, left + w - 10 - textRenderer.getWidth(cd), top + 9, 0xFFFF8080, false);

        // recommendations from the typed balance
        long typed = Advent.parseAmount(cfg.adventBalanceText);
        String rec = typed < 0 ? "Type your balance to see budget recommendations."
                : "Safe " + Advent.format(Advent.budgetFor(typed, 0))
                + " | Balanced " + Advent.format(Advent.budgetFor(typed, 1))
                + " | Generous " + Advent.format(Advent.budgetFor(typed, 2))
                + " | Large " + Advent.format(Advent.budgetFor(typed, 3));
        ctx.drawText(textRenderer, textRenderer.trimToWidth(rec, w - 20), left + 10, top + 46, dim, false);

        if (st != null) {
            long total = Advent.total(st);
            String line = "Balance " + Advent.format(st.balance) + " | Budget " + Advent.format(st.budget)
                    + " | Calendar " + Advent.format(total) + " | Left " + Advent.format(st.balance - total);
            ctx.drawText(textRenderer, textRenderer.trimToWidth(line, w - 20), left + 10, top + 58, white, false);

            int[] counts = new int[6];
            for (int r : st.rarities) counts[r]++;
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < 6; r++) {
                if (r > 0) sb.append(" | ");
                sb.append(Advent.RARITY_SHORT[r]).append(' ').append(Math.round(counts[r] * 100f / 24f)).append('%');
            }
            ctx.drawText(textRenderer, textRenderer.trimToWidth(sb.toString(), w - 20), left + 10, top + 70, gold, false);
        } else {
            ctx.drawText(textRenderer, "No calendar yet. Press Generate.", left + 10, top + 58, white, false);
        }

        // door rarity bars + opening flash
        long ms = System.currentTimeMillis();
        if (st != null) {
            for (int i = 0; i < 24; i++) {
                if (!st.opened[i]) continue;
                int color = Advent.RARITY_COLORS[st.rarities[i]];
                ctx.fill(doorX[i], doorY[i] + DOOR_H, doorX[i] + doorW, doorY[i] + DOOR_H + 2, color);
                long age = ms - openTime[i];
                if (age >= 0 && age < 500) {
                    int a = (int) (200 * (1f - age / 500f));
                    ctx.fill(doorX[i], doorY[i], doorX[i] + doorW, doorY[i] + DOOR_H, (a << 24) | (color & 0xFFFFFF));
                }
            }
        }

        // jackpot celebration
        if (ms < jackpotUntil) {
            int a = 90 + (int) (120 * (0.5 + 0.5 * Math.sin(ms / 90.0)));
            int c = (a << 24) | 0xFFD84A;
            ctx.fill(left, top, left + w, top + 3, c);
            ctx.fill(left, top + h - 3, left + w, top + h, c);
            ctx.fill(left, top, left + 3, top + h, c);
            ctx.fill(left + w - 3, top, left + w, top + h, c);
            ctx.drawCenteredTextWithShadow(textRenderer, "CHRISTMAS JACKPOT!", left + w / 2, top + 192, gold);
        }

        if (!status.isEmpty()) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(status, w - 20), left + 10, top + h - 40, 0xFFFF9090, false);
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
