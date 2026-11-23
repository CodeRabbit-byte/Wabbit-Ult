package com.wabbit.ultimate.cosmetics;

/** Colours for the "Color" button. */
public final class Palette {
    private Palette() {}

    public static final String[] NAMES =
            {"Red", "Gold", "Blue", "Green", "Purple", "Pink", "White", "Black", "Brown", "Orange", "Gray"};
    public static final int[] COLORS = {
            0xFFD0302B, 0xFFFFC830, 0xFF3C82F0, 0xFF3DBE5A, 0xFF9A55E0, 0xFFFF6FB5,
            0xFFF4F4F4, 0xFF1A1A1E, 0xFF7A4B26, 0xFFFF8A1F, 0xFF8C8F99
    };

    public static int color(int index) {
        return COLORS[Math.floorMod(index, COLORS.length)];
    }

    public static String name(int index) {
        return NAMES[Math.floorMod(index, NAMES.length)];
    }
}
