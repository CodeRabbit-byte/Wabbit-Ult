package com.wabbit.ultimate.hud;

/** HUD layouts as one line of text, so they can be copied and pasted between PCs or friends. */
public final class LayoutCodec {
    private LayoutCodec() {}

    public static final String PREFIX = "wabbit-hud-1";

    public record Layout(int x, int y, int opacity, boolean fps, boolean memory, boolean background) {}

    public static String encode(Layout l) {
        return String.join(";", PREFIX, Integer.toString(l.x()), Integer.toString(l.y()),
                Integer.toString(l.opacity()), l.fps() ? "1" : "0", l.memory() ? "1" : "0", l.background() ? "1" : "0");
    }

    /** @throws IllegalArgumentException with a person-friendly message if the text is not a layout. */
    public static Layout decode(String text) {
        if (text == null) throw new IllegalArgumentException("The clipboard is empty.");
        String[] t = text.trim().split(";");
        if (t.length != 7 || !t[0].equals(PREFIX)) {
            throw new IllegalArgumentException("That is not a Wabbit HUD layout. Press Copy layout on the other PC first.");
        }
        try {
            int x = clamp(Integer.parseInt(t[1].trim()), 0, 4000);
            int y = clamp(Integer.parseInt(t[2].trim()), 0, 4000);
            int op = clamp(Integer.parseInt(t[3].trim()), 0, 100);
            return new Layout(x, y, op, flag(t[4]), flag(t[5]), flag(t[6]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("That layout text is damaged.");
        }
    }

    private static boolean flag(String s) {
        String v = s.trim();
        if (!v.equals("0") && !v.equals("1")) throw new NumberFormatException(v);
        return v.equals("1");
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
