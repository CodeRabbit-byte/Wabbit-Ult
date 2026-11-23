package com.wabbit.ultimate.hud;

import com.wabbit.ultimate.config.ConfigManager;
import com.wabbit.ultimate.config.WabbitConfig;
import com.wabbit.ultimate.donut.Advent;
import com.wabbit.ultimate.donut.DonationLogic;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayDeque;
import java.util.Random;

/** Queue + renderer for donation alerts (toast, banner, celebration with confetti). */
public final class DonationAlerts {
    private DonationAlerts() {}

    public static final String[] SOUND_NAMES = {"None", "Level up", "Challenge", "Fireworks", "XP orb"};
    public static final String[] EFFECT_NAMES = {"Toast", "Banner", "Celebration"};
    private static final int[] CONFETTI = {0xFFFF5555, 0xFFFFD84A, 0xFF55E060, 0xFF55AAFF, 0xFFFF55FF, 0xFFFFFFFF};
    private static final int PARTICLES = 90;

    private static final class Alert {
        String title, text;
        int color, effect, sound;
        long duration, shownAt;
        float[] x0, y0, vy, phase, amp, size;
        int[] col;
    }

    private static final ArrayDeque<Alert> QUEUE = new ArrayDeque<>();

    /** Queues an alert. Safe to call from any menu or from tests. */
    public static void trigger(String donor, long amount) {
        WabbitConfig cfg = ConfigManager.get();
        WabbitConfig.DonationTier tier = DonationLogic.pickTier(cfg.donationTiers, amount);
        String amt = Advent.format(amount);
        Alert a = new Alert();
        if (tier == null) {
            a.title = "Donation";
            a.color = 0xFFB8B8B8;
            a.effect = 0;
            a.sound = 0;
            a.text = donor + " donated " + amt;
        } else {
            a.title = tier.name == null ? "Donation" : tier.name;
            a.color = tier.color == 0 ? 0xFFFFD84A : tier.color;
            a.effect = Math.max(0, Math.min(2, tier.effect));
            a.sound = tier.sound;
            a.text = DonationLogic.message(tier.message, donor, amt, a.title);
        }
        a.duration = a.effect == 0 ? 5000 : (a.effect == 1 ? 6500 : 9000);
        if (a.effect == 2) {
            Random r = new Random();
            a.x0 = new float[PARTICLES];
            a.y0 = new float[PARTICLES];
            a.vy = new float[PARTICLES];
            a.phase = new float[PARTICLES];
            a.amp = new float[PARTICLES];
            a.size = new float[PARTICLES];
            a.col = new int[PARTICLES];
            for (int i = 0; i < PARTICLES; i++) {
                a.x0[i] = r.nextFloat();           // fraction of screen width
                a.y0[i] = -r.nextFloat() * 160f;   // start above the screen, staggered
                a.vy[i] = 50f + r.nextFloat() * 90f;
                a.phase[i] = r.nextFloat() * 6.28f;
                a.amp[i] = 4f + r.nextFloat() * 10f;
                a.size[i] = 2f + r.nextInt(2);
                a.col[i] = CONFETTI[r.nextInt(CONFETTI.length)];
            }
        }
        if (QUEUE.size() < 20) QUEUE.add(a);
    }

    /** A plain toast with no sound (used for performance warnings). */
    public static void notice(String title, String text, int color) {
        Alert a = new Alert();
        a.title = title;
        a.text = text;
        a.color = color;
        a.effect = 0;
        a.sound = 0;
        a.duration = 5000;
        if (QUEUE.size() < 20) QUEUE.add(a);
    }

    public static void playSound(int idx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            switch (idx) {
                case 1 -> mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0f));
                case 2 -> mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f));
                case 3 -> mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f));
                case 4 -> mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f));
                default -> { }
            }
        } catch (Exception ignored) {
            // A sound problem must never break the alert.
        }
    }

    private static int withAlpha(int color, float f) {
        int a = Math.max(0, Math.min(255, (int) (255 * f)));
        return (a << 24) | (color & 0xFFFFFF);
    }

    public static void render(DrawContext ctx, int w, int h) {
        Alert a = QUEUE.peek();
        if (a == null) return;
        long now = System.currentTimeMillis();
        if (a.shownAt == 0) {
            a.shownAt = now;
            if (ConfigManager.get().donationSound) playSound(a.sound);
        }
        long t = now - a.shownAt;
        if (t > a.duration) {
            QUEUE.poll();
            return;
        }
        float in = Math.min(1f, t / 400f);
        float ease = 1f - (1f - in) * (1f - in) * (1f - in);
        float out = t > a.duration - 600 ? Math.max(0f, (a.duration - t) / 600f) : 1f;
        if (out < 0.08f) return;

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;

        // celebration: screen flash + confetti
        if (a.effect == 2) {
            if (t < 500) {
                ctx.fill(0, 0, w, h, withAlpha(a.color, 0.25f * (1f - t / 500f)));
            }
            float sec = t / 1000f;
            for (int i = 0; i < PARTICLES; i++) {
                float y = a.y0[i] + a.vy[i] * sec;
                if (y < 0 || y > h) continue;
                float x = a.x0[i] * w + (float) Math.sin(sec * 3f + a.phase[i]) * a.amp[i];
                int s = (int) a.size[i];
                ctx.fill((int) x, (int) y, (int) x + s, (int) y + s, withAlpha(a.col[i], out));
            }
        }

        int pw = Math.max(tr.getWidth(a.text), tr.getWidth(a.title)) + 32;
        int ph = a.effect == 0 ? 26 : 38;
        int px = (w - pw) / 2;
        int py = (int) (-ph + (ph + 14) * ease);

        ctx.fill(px, py, px + pw, py + ph, withAlpha(0x101018, 0.88f * out));
        int c = withAlpha(a.color, out);
        if (a.effect == 0) {
            ctx.fill(px, py, px + 3, py + ph, c);
        } else {
            ctx.fill(px, py, px + pw, py + 2, c);
            ctx.fill(px, py + ph - 2, px + pw, py + ph, c);
            ctx.fill(px, py, px + 2, py + ph, c);
            ctx.fill(px + pw - 2, py, px + pw, py + ph, c);
        }
        int ty = a.effect == 0 ? py + 4 : py + 7;
        ctx.drawCenteredTextWithShadow(tr, a.title, w / 2, ty, c);
        ctx.drawCenteredTextWithShadow(tr, a.text, w / 2, ty + (a.effect == 0 ? 11 : 14), withAlpha(0xFFFFFF, out));

        if (QUEUE.size() > 1) {
            ctx.drawCenteredTextWithShadow(tr, "+" + (QUEUE.size() - 1) + " more", w / 2, py + ph + 3, withAlpha(0xB8B8B8, out));
        }
    }
}
