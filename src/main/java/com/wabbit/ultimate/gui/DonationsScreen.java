package com.wabbit.ultimate.gui;

import com.wabbit.ultimate.config.ConfigManager;
import com.wabbit.ultimate.config.WabbitConfig;
import com.wabbit.ultimate.donut.Advent;
import com.wabbit.ultimate.hud.DonationAlerts;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Trigger manual donation alerts and edit the donation tiers. */
public class DonationsScreen extends Screen {
    private final Screen parent;
    private int left, top, w, h;
    private String status = "";
    private TextFieldWidget nameField, amountField;
    private final List<WabbitConfig.DonationTier> shown = new ArrayList<>();

    public DonationsScreen(Screen parent) {
        super(Text.literal("Donation Alerts"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        w = Math.min(450, width - 12);
        h = Math.min(270, height - 8);
        left = (width - w) / 2;
        top = (height - h) / 2;
        WabbitConfig cfg = ConfigManager.get();

        nameField = new TextFieldWidget(textRenderer, left + 52, top + 22, 90, 18, Text.literal("Donor"));
        nameField.setMaxLength(24);
        nameField.setText(cfg.donationLastName);
        addDrawableChild(nameField);

        amountField = new TextFieldWidget(textRenderer, left + 196, top + 22, 80, 18, Text.literal("Amount"));
        amountField.setMaxLength(16);
        amountField.setText(cfg.donationLastAmount);
        addDrawableChild(amountField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Trigger alert"), b -> trigger())
                .dimensions(left + 284, top + 22, w - 294, 18).build());

        shown.clear();
        List<WabbitConfig.DonationTier> tiers = cfg.donationTiers;
        for (int i = 0; i < tiers.size() && i < 4; i++) {
            WabbitConfig.DonationTier t = tiers.get(i);
            shown.add(t);
            int y = top + 66 + i * 42;

            TextFieldWidget amt = new TextFieldWidget(textRenderer, left + 10, y, 64, 18, Text.literal("Min"));
            amt.setMaxLength(16);
            amt.setText(Advent.format(t.min));
            amt.setChangedListener(s -> {
                long v = Advent.parseAmount(s);
                if (v > 0) t.min = v;
            });
            addDrawableChild(amt);

            int bx = left + 176;
            int bw = (w - 186 - 4) / 2;
            addDrawableChild(ButtonWidget.builder(Text.literal("Sound: " + DonationAlerts.SOUND_NAMES[Math.floorMod(t.sound, 5)]), b -> {
                t.sound = (t.sound + 1) % DonationAlerts.SOUND_NAMES.length;
                b.setMessage(Text.literal("Sound: " + DonationAlerts.SOUND_NAMES[t.sound]));
                DonationAlerts.playSound(t.sound);
                ConfigManager.save();
            }).dimensions(bx, y, bw, 18).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Effect: " + DonationAlerts.EFFECT_NAMES[Math.floorMod(t.effect, 3)]), b -> {
                t.effect = (t.effect + 1) % DonationAlerts.EFFECT_NAMES.length;
                b.setMessage(Text.literal("Effect: " + DonationAlerts.EFFECT_NAMES[t.effect]));
                ConfigManager.save();
            }).dimensions(bx + bw + 4, y, bw, 18).build());

            TextFieldWidget msg = new TextFieldWidget(textRenderer, left + 10, y + 20, w - 20, 18, Text.literal("Message"));
            msg.setMaxLength(80);
            msg.setText(t.message == null ? "" : t.message);
            msg.setChangedListener(s -> t.message = s);
            addDrawableChild(msg);
        }

        int by = top + h - 26;
        int bw = (w - 20 - 8) / 3;
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset tiers"), b -> {
            cfg.donationTiers = WabbitConfig.defaultDonationTiers();
            ConfigManager.save();
            status = "Tiers reset to defaults.";
            clearAndInit();
        }).dimensions(left + 10, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Sound: " + (cfg.donationSound ? "ON" : "OFF")), b -> {
            cfg.donationSound = !cfg.donationSound;
            ConfigManager.save();
            b.setMessage(Text.literal("Sound: " + (cfg.donationSound ? "ON" : "OFF")));
        }).dimensions(left + 14 + bw, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
                .dimensions(left + 18 + 2 * bw, by, bw, 20).build());
    }

    private void trigger() {
        WabbitConfig cfg = ConfigManager.get();
        long amount = Advent.parseAmount(amountField.getText());
        if (amount < 0) {
            status = "Enter an amount like 10M, 250M or 1.5B.";
            return;
        }
        String name = nameField.getText().isBlank() ? "Anonymous" : nameField.getText().trim();
        cfg.donationLastName = name;
        cfg.donationLastAmount = amountField.getText().trim();
        ConfigManager.save();
        DonationAlerts.trigger(name, amount);
        status = "";
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

        ctx.drawText(textRenderer, "Donation Alerts", left + 10, top + 8, cfg.accent(), true);
        ctx.drawText(textRenderer, "Donor:", left + 10, top + 27, text, false);
        ctx.drawText(textRenderer, "Amount:", left + 150, top + 27, text, false);
        ctx.drawText(textRenderer, "Tiers: minimum amount, effect and message ({name} {amount} {tier})",
                left + 10, top + 50, dim, false);

        for (int i = 0; i < shown.size(); i++) {
            WabbitConfig.DonationTier t = shown.get(i);
            String label = t.name == null ? "Tier" : t.name;
            ctx.drawText(textRenderer, textRenderer.trimToWidth(label, 94), left + 78, top + 71 + i * 42,
                    t.color == 0 ? 0xFFFFD84A : t.color, false);
        }
        if (!status.isEmpty()) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(status, w - 20), left + 10, top + h - 38, 0xFFFF9090, false);
        }

        // live preview of triggered alerts
        DonationAlerts.render(ctx, width, height);
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
