package com.wabbit.ultimate.gui;

import com.wabbit.ultimate.config.ConfigManager;
import com.wabbit.ultimate.config.WabbitConfig;
import com.wabbit.ultimate.donut.Advent;
import com.wabbit.ultimate.donut.Giveaway;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;

/** Giveaway Manager: balance in, budget + random prize pool out, with history. */
public class GiveawayScreen extends Screen {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private final Screen parent;
    private final Random rnd = new Random();
    private int left, top, w, h;
    private String status = "";

    public GiveawayScreen(Screen parent) {
        super(Text.literal("Giveaway Manager"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        w = Math.min(450, width - 12);
        h = Math.min(270, height - 8);
        left = (width - w) / 2;
        top = (height - h) / 2;
        WabbitConfig cfg = ConfigManager.get();

        // The balance is shared with the Advent Calendar so you only type it once.
        TextFieldWidget field = new TextFieldWidget(textRenderer, left + 64, top + 22, 110, 18, Text.literal("Balance"));
        field.setMaxLength(24);
        field.setText(cfg.adventBalanceText);
        field.setChangedListener(s -> cfg.adventBalanceText = s);
        addDrawableChild(field);

        addDrawableChild(ButtonWidget.builder(Text.literal(Advent.TIER_NAMES[Math.floorMod(cfg.giveTier, 4)]), b -> {
            cfg.giveTier = (cfg.giveTier + 1) % 4;
            ConfigManager.save();
            clearAndInit();
        }).dimensions(left + 180, top + 22, 112, 18).build());

        addDrawableChild(ButtonWidget.builder(Text.literal(Giveaway.RESERVE_NAMES[Math.floorMod(cfg.giveReserveIdx, 4)]), b -> {
            cfg.giveReserveIdx = (cfg.giveReserveIdx + 1) % 4;
            ConfigManager.save();
            clearAndInit();
        }).dimensions(left + 296, top + 22, w - 306, 18).build());

        int third = (w - 20 - 8) / 3;
        addDrawableChild(ButtonWidget.builder(Text.literal(Giveaway.COUNTS[Math.floorMod(cfg.giveCountIdx, 5)] + " prizes"), b -> {
            cfg.giveCountIdx = (cfg.giveCountIdx + 1) % Giveaway.COUNTS.length;
            ConfigManager.save();
            clearAndInit();
        }).dimensions(left + 10, top + 44, third, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(Giveaway.MAX_NAMES[Math.floorMod(cfg.giveMaxIdx, 4)]), b -> {
            cfg.giveMaxIdx = (cfg.giveMaxIdx + 1) % 4;
            ConfigManager.save();
            clearAndInit();
        }).dimensions(left + 14 + third, top + 44, third, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(cfg.giveaway == null ? "Generate" : "Regenerate"), b -> generate())
                .dimensions(left + 18 + 2 * third, top + 44, third, 18).build());

        int by = top + h - 26;
        int bw = (w - 20 - 3 * 4) / 4;
        ButtonWidget save = ButtonWidget.builder(Text.literal("Save to history"), b -> saveHistory())
                .dimensions(left + 10, by, bw, 20).build();
        save.active = cfg.giveaway != null && !cfg.giveaway.saved;
        addDrawableChild(save);
        addDrawableChild(ButtonWidget.builder(Text.literal("Copy list"), b -> copyList())
                .dimensions(left + 14 + bw, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Clear history"), b -> {
            cfg.giveawayHistory.clear();
            ConfigManager.save();
            status = "History cleared.";
        }).dimensions(left + 18 + 2 * bw, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
                .dimensions(left + 22 + 3 * bw, by, bw, 20).build());
    }

    private void generate() {
        WabbitConfig cfg = ConfigManager.get();
        long balance = Advent.parseAmount(cfg.adventBalanceText);
        if (balance < 0) {
            status = "Enter your balance like 2.5B, 750M, 12k or 12000000.";
            return;
        }
        WabbitConfig.GiveawayState st = Giveaway.generate(balance, cfg.giveTier, cfg.giveReserveIdx,
                cfg.giveCountIdx, cfg.giveMaxIdx, cfg.adventChances, rnd);
        if (st == null) {
            status = "Budget is too small (needs at least " + Advent.MIN_BUDGET + "). Try a bigger tier or keep less.";
            return;
        }
        cfg.giveaway = st;
        ConfigManager.save();
        status = "New prize pool generated.";
        clearAndInit();
    }

    private void saveHistory() {
        WabbitConfig cfg = ConfigManager.get();
        WabbitConfig.GiveawayState st = cfg.giveaway;
        if (st == null || st.saved) return;
        WabbitConfig.GiveawayRecord r = new WabbitConfig.GiveawayRecord();
        r.when = System.currentTimeMillis();
        r.balance = st.balance;
        r.budget = st.budget;
        r.total = Giveaway.total(st);
        r.prizes = st.prizes.length;
        cfg.giveawayHistory.add(0, r);
        while (cfg.giveawayHistory.size() > 10) cfg.giveawayHistory.remove(cfg.giveawayHistory.size() - 1);
        st.saved = true;
        ConfigManager.save();
        status = "Saved to history.";
        clearAndInit();
    }

    private void copyList() {
        WabbitConfig.GiveawayState st = ConfigManager.get().giveaway;
        if (st == null) {
            status = "Generate a prize pool first.";
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < st.prizes.length; i++) {
            sb.append('#').append(i + 1).append(": ").append(Advent.format(st.prizes[i]))
                    .append(" (").append(Advent.RARITY_NAMES[st.rarities[i]]).append(")\n");
        }
        sb.append("Total: ").append(Advent.format(Giveaway.total(st)));
        MinecraftClient.getInstance().keyboard.setClipboard(sb.toString());
        status = "Prize list copied to clipboard.";
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
        WabbitConfig.GiveawayState st = cfg.giveaway;
        int text = cfg.darkTheme ? 0xFFFFFFFF : 0xFF1A1A1A;
        int dim = cfg.darkTheme ? 0xFF8A90A0 : 0xFF6A7080;

        ctx.drawText(textRenderer, "Giveaway Manager", left + 10, top + 9, cfg.accent(), true);
        ctx.drawText(textRenderer, "Balance:", left + 10, top + 27, text, false);

        if (st == null) {
            long typed = Advent.parseAmount(cfg.adventBalanceText);
            String hint = typed < 0 ? "Type your balance, pick options, press Generate."
                    : "Budget would be about " + Advent.format(Advent.budgetFor(
                    typed - (long) (typed * Giveaway.RESERVE_PCT[Math.floorMod(cfg.giveReserveIdx, 4)]), cfg.giveTier))
                    + ". Press Generate.";
            ctx.drawText(textRenderer, textRenderer.trimToWidth(hint, w - 20), left + 10, top + 68, dim, false);
        } else {
            long total = Giveaway.total(st);
            String l1 = "Balance " + Advent.format(st.balance) + " | Spendable " + Advent.format(st.spendable)
                    + " | Budget " + Advent.format(st.budget);
            long pct = st.budget == 0 ? 0 : total * 100 / st.budget;
            String l2 = "Pool total " + Advent.format(total) + " (" + pct + "% of budget) | Balance after "
                    + Advent.format(st.balance - total);
            ctx.drawText(textRenderer, textRenderer.trimToWidth(l1, w - 20), left + 10, top + 68, text, false);
            ctx.drawText(textRenderer, textRenderer.trimToWidth(l2, w - 20), left + 10, top + 79, text, false);

            int colW = (w - 20) / 4;
            for (int i = 0; i < st.prizes.length; i++) {
                int x = left + 10 + (i / 5) * colW;
                int y = top + 94 + (i % 5) * 11;
                String s = "#" + (i + 1) + " " + Advent.format(st.prizes[i]) + " " + Advent.RARITY_SHORT[st.rarities[i]];
                ctx.drawText(textRenderer, textRenderer.trimToWidth(s, colW - 4), x, y,
                        Advent.RARITY_COLORS[st.rarities[i]], false);
            }
        }

        ctx.drawText(textRenderer, "Previous giveaways:", left + 10, top + 156, dim, false);
        List<WabbitConfig.GiveawayRecord> hist = cfg.giveawayHistory;
        for (int i = 0; i < Math.min(4, hist.size()); i++) {
            WabbitConfig.GiveawayRecord r = hist.get(i);
            String when = FMT.format(Instant.ofEpochMilli(r.when).atZone(ZoneId.systemDefault()));
            String line = when + "  budget " + Advent.format(r.budget) + "  pool " + Advent.format(r.total)
                    + "  " + r.prizes + " prizes";
            ctx.drawText(textRenderer, textRenderer.trimToWidth(line, w - 20), left + 10, top + 168 + i * 11, text, false);
        }
        if (hist.isEmpty()) {
            ctx.drawText(textRenderer, "None saved yet.", left + 10, top + 168, dim, false);
        }

        if (!status.isEmpty()) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(status, w - 20), left + 10, top + h - 40, cfg.accent(), false);
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

    @Override
    public void removed() {
        ConfigManager.save(); // keep a balance typed into a field even when the menu is closed with Esc
    }
}
