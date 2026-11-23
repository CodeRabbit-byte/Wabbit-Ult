package com.wabbit.ultimate.cosmetics;

import com.wabbit.ultimate.config.WabbitConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every built-in cosmetic. Each one is a handful of coloured blocks, so no model or texture files are needed. */
public final class Catalog {
    private Catalog() {}

    public record Placed(Cosmetic item, int tintRgb) {}

    /** Wing pivots (model pixels). */
    public static final float[] WING_PIVOT = {1.5f, 1.5f, 2.6f};

    private static final Map<String, Cosmetic> BY_ID = new LinkedHashMap<>();

    private static Box fix(float x1, float y1, float z1, float x2, float y2, float z2, int argb) {
        return new Box(x1, y1, z1, x2, y2, z2, argb, false, false, 0);
    }

    private static Box tint(float x1, float y1, float z1, float x2, float y2, float z2) {
        return new Box(x1, y1, z1, x2, y2, z2, 0xFFFFFFFF, true, false, 0);
    }

    private static Box tintA(float x1, float y1, float z1, float x2, float y2, float z2, int alpha, boolean glow) {
        return new Box(x1, y1, z1, x2, y2, z2, (alpha << 24) | 0xFFFFFF, true, glow, 0);
    }

    private static Box glowTint(float x1, float y1, float z1, float x2, float y2, float z2) {
        return new Box(x1, y1, z1, x2, y2, z2, 0xFFFFFFFF, true, true, 0);
    }

    private static Box glowFix(float x1, float y1, float z1, float x2, float y2, float z2, int argb) {
        return new Box(x1, y1, z1, x2, y2, z2, argb, false, true, 0);
    }

    private static void add(String id, String name, Slot slot, int defaultTint, boolean bob, List<Box> boxes) {
        BY_ID.put(id, new Cosmetic(id, name, slot, defaultTint, bob, List.copyOf(boxes)));
    }

    /** Boxes for the +x wing, then the same mirrored for the other side. */
    private static List<Box> wingPair(List<Box> oneSide) {
        List<Box> all = new ArrayList<>();
        for (Box b : oneSide) {
            all.add(b.inGroup(1));
            all.add(b.mirroredX(2));
        }
        return all;
    }

    /** Boxes for the +x side of a head item, then the same mirrored onto the other side (both fixed). */
    private static List<Box> mirrorPair(List<Box> oneSide) {
        List<Box> all = new ArrayList<>();
        for (Box b : oneSide) {
            all.add(b);
            all.add(b.mirroredX(0));
        }
        return all;
    }

    private static final int PINK = 0xFFFFA6C9;

    static {
        // ---- hats (head frame: head spans y -8..0, x -4..4, z -4..4) ----
        add("top_hat", "Top Hat", Slot.HAT, 0, false, List.of(
                fix(-5.5f, -9.0f, -5.5f, 5.5f, -8.1f, 5.5f, 0xFF1B1B1F),
                fix(-3.6f, -15.0f, -3.6f, 3.6f, -9.0f, 3.6f, 0xFF1B1B1F),
                tint(-3.8f, -10.6f, -3.8f, 3.8f, -9.0f, 3.8f)));
        add("santa_hat", "Santa Hat", Slot.HAT, 0, false, List.of(
                fix(-4.7f, -9.7f, -4.7f, 4.7f, -8.1f, 4.7f, 0xFFF8F8F8),
                tint(-4.1f, -11.5f, -4.1f, 4.1f, -9.7f, 4.1f),
                tint(-3.2f, -13.3f, -3.2f, 3.2f, -11.5f, 3.2f),
                tint(-2.3f, -14.9f, -2.3f, 2.3f, -13.3f, 2.3f),
                tint(1.5f, -15.9f, -1.2f, 5.0f, -14.3f, 1.2f),
                fix(4.4f, -16.5f, -1.3f, 6.8f, -14.1f, 1.3f, 0xFFF8F8F8)));
        add("party_hat", "Party Hat", Slot.HAT, 2, false, List.of(
                tint(-3.0f, -9.6f, -3.0f, 3.0f, -8.1f, 3.0f),
                fix(-2.4f, -11.1f, -2.4f, 2.4f, -9.6f, 2.4f, 0xFFF4F4F4),
                tint(-1.8f, -12.6f, -1.8f, 1.8f, -11.1f, 1.8f),
                fix(-1.2f, -14.1f, -1.2f, 1.2f, -12.6f, 1.2f, 0xFFF4F4F4),
                fix(-0.7f, -15.3f, -0.7f, 0.7f, -14.1f, 0.7f, 0xFFFFC830)));
        add("wizard_hat", "Wizard Hat", Slot.HAT, 4, false, List.of(
                tint(-6.0f, -9.0f, -6.0f, 6.0f, -8.1f, 6.0f),
                fix(-3.9f, -9.9f, -3.9f, 3.9f, -9.0f, 3.9f, 0xFFFFC830),
                tint(-3.8f, -11.0f, -3.8f, 3.8f, -9.9f, 3.8f),
                tint(-2.9f, -13.0f, -2.9f, 2.9f, -11.0f, 2.9f),
                tint(-2.0f, -15.0f, -1.5f, 2.0f, -13.0f, 2.5f),
                tint(-1.1f, -16.6f, 0.5f, 1.1f, -15.0f, 3.5f),
                tint(-0.5f, -17.6f, 2.2f, 0.5f, -16.6f, 4.4f),
                glowFix(-0.7f, -12.6f, -3.05f, 0.7f, -11.2f, -2.9f, 0xFFFFE066)));
        // the right ear flops forward; the left one stands up
        add("bunny_ears", "Bunny Ears", Slot.HAT, 6, false, List.of(
                tint(1.0f, -14.6f, -0.45f, 2.8f, -8.0f, 0.45f),
                fix(1.45f, -13.9f, -0.6f, 2.35f, -8.6f, -0.45f, PINK),
                tint(-2.8f, -12.2f, -0.45f, -1.0f, -8.0f, 0.45f),
                tint(-2.8f, -13.0f, -3.2f, -1.0f, -12.2f, 0.45f),
                fix(-2.35f, -11.6f, -0.6f, -1.45f, -8.6f, -0.45f, PINK)));
        add("cat_ears", "Cat Ears", Slot.HAT, 7, false, mirrorPair(List.of(
                tint(1.2f, -9.2f, -1.0f, 3.8f, -8.0f, 0.0f),
                tint(1.6f, -10.2f, -1.0f, 3.4f, -9.2f, 0.0f),
                tint(2.0f, -11.0f, -1.0f, 3.0f, -10.2f, 0.0f),
                fix(1.8f, -9.8f, -1.15f, 3.2f, -8.2f, -1.0f, PINK))));
        add("devil_horns", "Devil Horns", Slot.HAT, 0, false, mirrorPair(List.of(
                tint(2.0f, -9.0f, -2.6f, 3.4f, -8.0f, -1.2f),
                tint(2.6f, -10.2f, -2.4f, 3.8f, -9.0f, -1.4f),
                tint(3.2f, -11.2f, -2.2f, 4.2f, -10.2f, -1.6f),
                tint(3.6f, -11.9f, -2.1f, 4.3f, -11.2f, -1.7f))));

        // ---- crowns ----
        add("royal_crown", "Royal Crown", Slot.CROWN, 1, false, List.of(
                tint(-4.5f, -9.8f, -4.5f, 4.5f, -8.2f, -3.5f),
                tint(-4.5f, -9.8f, 3.5f, 4.5f, -8.2f, 4.5f),
                tint(-4.5f, -9.8f, -3.5f, -3.5f, -8.2f, 3.5f),
                tint(3.5f, -9.8f, -3.5f, 4.5f, -8.2f, 3.5f),
                tint(-4.5f, -12.0f, -4.5f, -3.3f, -9.8f, -3.3f),
                tint(3.3f, -12.0f, -4.5f, 4.5f, -9.8f, -3.3f),
                tint(-4.5f, -12.0f, 3.3f, -3.3f, -9.8f, 4.5f),
                tint(3.3f, -12.0f, 3.3f, 4.5f, -9.8f, 4.5f),
                tint(-0.7f, -12.6f, -4.5f, 0.7f, -9.8f, -3.5f),
                tint(-0.7f, -12.6f, 3.5f, 0.7f, -9.8f, 4.5f),
                fix(-0.6f, -9.4f, -4.7f, 0.6f, -8.4f, -4.4f, 0xFFE0282E),
                fix(-2.6f, -9.4f, -4.7f, -1.6f, -8.6f, -4.4f, 0xFF3C82F0),
                fix(1.6f, -9.4f, -4.7f, 2.6f, -8.6f, -4.4f, 0xFF3C82F0)));
        add("tiara", "Tiara", Slot.CROWN, 6, false, List.of(
                tint(-4.5f, -9.6f, -4.6f, 4.5f, -8.6f, -3.6f),
                tint(-4.5f, -9.6f, -3.6f, -3.6f, -8.6f, 3.0f),
                tint(3.6f, -9.6f, -3.6f, 4.5f, -8.6f, 3.0f),
                tint(-2.7f, -11.6f, -4.6f, -1.7f, -9.6f, -3.8f),
                tint(-0.7f, -12.8f, -4.6f, 0.7f, -9.6f, -3.8f),
                tint(1.7f, -11.6f, -4.6f, 2.7f, -9.6f, -3.8f),
                glowFix(-0.45f, -11.2f, -4.8f, 0.45f, -10.2f, -4.4f, 0xFF7FD4FF)));

        // ---- halos (float above the head, glow, gently bob) ----
        add("halo", "Halo", Slot.HALO, 1, true, List.of(
                glowTint(-5.4f, -12.3f, -5.4f, 5.4f, -11.4f, -4.5f),
                glowTint(-5.4f, -12.3f, 4.5f, 5.4f, -11.4f, 5.4f),
                glowTint(-5.4f, -12.3f, -4.5f, -4.5f, -11.4f, 4.5f),
                glowTint(4.5f, -12.3f, -4.5f, 5.4f, -11.4f, 4.5f)));
        add("double_halo", "Double Halo", Slot.HALO, 2, true, List.of(
                glowTint(-4.4f, -12.0f, -4.4f, 4.4f, -11.3f, -3.7f),
                glowTint(-4.4f, -12.0f, 3.7f, 4.4f, -11.3f, 4.4f),
                glowTint(-4.4f, -12.0f, -3.7f, -3.7f, -11.3f, 3.7f),
                glowTint(3.7f, -12.0f, -3.7f, 4.4f, -11.3f, 3.7f),
                glowFix(-6.2f, -14.0f, -6.2f, 6.2f, -13.2f, -5.4f, 0xFFFFFFFF),
                glowFix(-6.2f, -14.0f, 5.4f, 6.2f, -13.2f, 6.2f, 0xFFFFFFFF),
                glowFix(-6.2f, -14.0f, -5.4f, -5.4f, -13.2f, 5.4f, 0xFFFFFFFF),
                glowFix(5.4f, -14.0f, -5.4f, 6.2f, -13.2f, 5.4f, 0xFFFFFFFF)));

        // ---- masks (front of the face is z = -4) ----
        add("bandit_mask", "Bandit Mask", Slot.MASK, 7, false, List.of(
                tint(-4.4f, -5.4f, -4.6f, 4.4f, -4.4f, -4.1f),
                tint(-4.4f, -4.4f, -4.6f, -3.0f, -2.8f, -4.1f),
                tint(-1.0f, -4.4f, -4.6f, 1.0f, -2.8f, -4.1f),
                tint(3.0f, -4.4f, -4.6f, 4.4f, -2.8f, -4.1f),
                tint(-4.4f, -2.8f, -4.6f, 4.4f, -2.2f, -4.1f)));
        add("white_mask", "White Mask", Slot.MASK, 6, false, List.of(
                tint(-3.9f, -7.7f, -4.7f, 3.9f, -5.0f, -4.2f),
                tint(-3.9f, -5.0f, -4.7f, -3.0f, -2.6f, -4.2f),
                tint(-1.0f, -5.0f, -4.7f, 1.0f, -2.6f, -4.2f),
                tint(3.0f, -5.0f, -4.7f, 3.9f, -2.6f, -4.2f),
                tint(-3.9f, -2.6f, -4.7f, 3.9f, -0.4f, -4.2f)));

        // ---- glasses ----
        add("sunglasses", "Sunglasses", Slot.GLASSES, 7, false, List.of(
                tint(-3.9f, -5.1f, -4.9f, 3.9f, -4.6f, -4.4f),
                tint(-0.6f, -4.6f, -4.9f, 0.6f, -3.9f, -4.4f),
                fix(0.6f, -4.7f, -4.85f, 3.7f, -2.7f, -4.4f, 0xE0101820),
                fix(-3.7f, -4.7f, -4.85f, -0.6f, -2.7f, -4.4f, 0xE0101820),
                tint(4.5f, -4.9f, -4.7f, 5.1f, -4.3f, 0.0f),
                tint(-5.1f, -4.9f, -4.7f, -4.5f, -4.3f, 0.0f)));
        add("neon_shades", "Neon Shades", Slot.GLASSES, 5, false, List.of(
                fix(-3.9f, -5.1f, -4.9f, 3.9f, -4.6f, -4.4f, 0xFF15151A),
                fix(-0.6f, -4.6f, -4.9f, 0.6f, -3.9f, -4.4f, 0xFF15151A),
                tintA(0.6f, -4.7f, -4.85f, 3.7f, -2.7f, -4.4f, 0xC8, true),
                tintA(-3.7f, -4.7f, -4.85f, -0.6f, -2.7f, -4.4f, 0xC8, true),
                fix(4.5f, -4.9f, -4.7f, 5.1f, -4.3f, 0.0f, 0xFF15151A),
                fix(-5.1f, -4.9f, -4.7f, -4.5f, -4.3f, 0.0f, 0xFF15151A)));

        // ---- wings (body frame: body spans y 0..12, x -4..4, z -2..2; back is +z) ----
        add("angel_wings", "Angel Wings", Slot.WINGS, 6, false, wingPair(List.of(
                tint(1.5f, -1.5f, 2.6f, 10.5f, 0.7f, 3.3f),
                tint(1.5f, 0.7f, 2.6f, 9.5f, 2.7f, 3.3f),
                tint(1.5f, 2.7f, 2.6f, 8.0f, 4.5f, 3.3f),
                tint(1.5f, 4.5f, 2.6f, 6.2f, 6.2f, 3.3f),
                tint(1.5f, 6.2f, 2.6f, 4.2f, 7.6f, 3.3f),
                fix(1.5f, -1.9f, 2.5f, 10.9f, -1.5f, 3.4f, 0xFFFFE9A0))));
        add("dragon_wings", "Dragon Wings", Slot.WINGS, 0, false, wingPair(List.of(
                fix(1.5f, -1.9f, 2.6f, 11.8f, -0.8f, 3.3f, 0xFF2A2024),
                fix(6.4f, -0.8f, 2.6f, 7.4f, 6.8f, 3.3f, 0xFF2A2024),
                fix(10.6f, -0.8f, 2.6f, 11.6f, 4.2f, 3.3f, 0xFF2A2024),
                tintA(1.5f, -0.8f, 2.75f, 6.4f, 5.8f, 3.15f, 0xE6, false),
                tintA(7.4f, -0.8f, 2.75f, 10.6f, 3.6f, 3.15f, 0xE6, false),
                tintA(1.5f, 5.8f, 2.75f, 6.4f, 7.8f, 3.15f, 0xE6, false))));
        add("butterfly_wings", "Butterfly Wings", Slot.WINGS, 2, false, wingPair(List.of(
                fix(1.5f, -3.9f, 2.6f, 10.4f, -3.5f, 3.1f, 0xFF1A1A1E),
                tintA(1.5f, -3.5f, 2.65f, 8.5f, 2.5f, 3.05f, 0xD0, false),
                tintA(8.5f, -3.5f, 2.65f, 10.4f, 0.5f, 3.05f, 0xD0, false),
                tintA(1.5f, 2.5f, 2.65f, 6.5f, 7.5f, 3.05f, 0xD0, false),
                fix(5.5f, -2.5f, 2.6f, 7.5f, -0.5f, 3.1f, 0xFFF4F4F4),
                fix(3.0f, 4.0f, 2.6f, 4.5f, 5.5f, 3.1f, 0xFFF4F4F4))));

        // ---- back items ----
        add("backpack", "Backpack", Slot.BACK, 8, false, List.of(
                tint(-3.6f, 1.0f, 2.3f, 3.6f, 10.4f, 5.8f),
                tint(-3.8f, 0.4f, 2.3f, 3.8f, 2.6f, 6.0f),
                tint(-2.2f, 6.0f, 5.8f, 2.2f, 9.6f, 6.6f),
                fix(-3.2f, 0.0f, 2.0f, -2.2f, 10.0f, 2.3f, 0xFF1A1A1E),
                fix(2.2f, 0.0f, 2.0f, 3.2f, 10.0f, 2.3f, 0xFF1A1A1E),
                fix(-0.5f, 2.2f, 6.0f, 0.5f, 3.4f, 6.2f, 0xFFFFC830)));
        add("santa_sack", "Santa Sack", Slot.BACK, 0, false, List.of(
                tint(-4.2f, 0.8f, 2.3f, 4.2f, 12.4f, 7.8f),
                tint(-3.0f, -0.8f, 3.4f, 3.0f, 0.8f, 6.4f),
                fix(-2.4f, 0.2f, 3.2f, 2.4f, 1.4f, 6.8f, 0xFFFFC830)));
        add("jetpack", "Jetpack", Slot.BACK, 10, true, List.of(
                tint(-3.8f, 0.8f, 2.3f, -0.4f, 10.2f, 5.4f),
                tint(0.4f, 0.8f, 2.3f, 3.8f, 10.2f, 5.4f),
                fix(-3.2f, 10.2f, 2.9f, -1.0f, 11.4f, 4.8f, 0xFF2A2A30),
                fix(1.0f, 10.2f, 2.9f, 3.2f, 11.4f, 4.8f, 0xFF2A2A30),
                glowFix(-2.9f, 11.4f, 3.2f, -1.3f, 14.6f, 4.5f, 0xCCFF8A1F),
                glowFix(1.3f, 11.4f, 3.2f, 2.9f, 14.6f, 4.5f, 0xCCFF8A1F),
                glowFix(-2.5f, 11.4f, 3.6f, -1.7f, 13.0f, 4.1f, 0xFFFFE066),
                glowFix(1.7f, 11.4f, 3.6f, 2.5f, 13.0f, 4.1f, 0xFFFFE066)));
    }

    public static List<Cosmetic> all() {
        return new ArrayList<>(BY_ID.values());
    }

    public static Cosmetic byId(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    public static List<Cosmetic> forSlot(Slot slot) {
        List<Cosmetic> out = new ArrayList<>();
        for (Cosmetic c : BY_ID.values()) if (c.slot() == slot) out.add(c);
        return out;
    }

    /** What the person has equipped, with the colour each one should use. */
    public static List<Placed> equipped(WabbitConfig cfg) {
        List<Placed> out = new ArrayList<>();
        if (cfg.cosmeticSlots == null) return out;
        for (Slot s : Slot.values()) {
            Cosmetic c = byId(cfg.cosmeticSlots.get(s.id));
            if (c == null || c.slot() != s) continue;
            Integer t = cfg.cosmeticTints == null ? null : cfg.cosmeticTints.get(s.id);
            out.add(new Placed(c, Palette.color(t == null ? c.defaultTint() : t)));
        }
        return out;
    }

    /** Wing angle in degrees for the given wing group at the given age (ticks). Positive = swung backwards. */
    public static float wingSpread(float ageTicks) {
        return 22f + 14f * (float) Math.sin(ageTicks * 0.12f);
    }

    /** The y-rotation (degrees) applied to a wing group; opposite signs keep the two wings mirrored. */
    public static float wingRotation(int group, float ageTicks) {
        float a = wingSpread(ageTicks);
        return group == 1 ? -a : a;
    }
}
