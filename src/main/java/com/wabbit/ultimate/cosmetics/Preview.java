package com.wabbit.ultimate.cosmetics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns the player and the equipped cosmetics into flat rectangles for the menu preview.
 * A box turned about the vertical axis always projects to a rectangle, so no polygon drawing is needed.
 */
public final class Preview {
    private Preview() {}

    public record Rect(int x1, int y1, int x2, int y2, int color, float depth) {}

    private static final Box[] AVATAR = {
            new Box(-4, -8, -4, 4, 0, 4, 0xFFC89B7B, false, false, 0),      // head
            new Box(-4, 0, -2, 4, 12, 2, 0xFF3C7BBF, false, false, 0),      // body
            new Box(4, 0, -2, 8, 12, 2, 0xFFC89B7B, false, false, 0),       // arm
            new Box(-8, 0, -2, -4, 12, 2, 0xFFC89B7B, false, false, 0),     // arm
            new Box(0, 12, -2, 4, 24, 2, 0xFF3B3B8F, false, false, 0),      // leg
            new Box(-4, 12, -2, 0, 24, 2, 0xFF3B3B8F, false, false, 0),     // leg
    };

    /**
     * @param angleDeg viewing angle, 0 = looking at the player's face
     * @param cx       screen x of the player's centre line
     * @param top      screen y of the top of the head
     * @param scale    screen pixels per model pixel
     */
    public static List<Rect> project(List<Catalog.Placed> placed, boolean avatar, float ageTicks,
                                     float angleDeg, float cx, float top, float scale) {
        List<Rect> out = new ArrayList<>();
        if (avatar) for (Box b : AVATAR) addBox(out, b, b.argb(), 0, 0, 0, 0, 0, angleDeg, cx, top, scale);
        for (Catalog.Placed p : placed) {
            float bob = p.item().bob() ? (float) Math.sin(ageTicks * 0.08f) * 0.6f : 0f;
            for (Box b : p.item().boxes()) {
                float local = 0;
                float px = 0, pz = 0;
                if (b.group() != 0) {
                    local = Catalog.wingRotation(b.group(), ageTicks);
                    px = b.group() == 1 ? Catalog.WING_PIVOT[0] : -Catalog.WING_PIVOT[0];
                    pz = Catalog.WING_PIVOT[2];
                }
                addBox(out, b, b.color(p.tintRgb()), bob, local, px, pz, 0, angleDeg, cx, top, scale);
            }
        }
        // far first, near last
        out.sort(Comparator.comparingDouble((Rect r) -> r.depth()).reversed());
        return out;
    }

    private static void addBox(List<Rect> out, Box b, int color, float dy, float localDeg, float pivotX, float pivotZ,
                               float unused, float angleDeg, float cx, float top, float scale) {
        double local = Math.toRadians(localDeg);
        double g = Math.toRadians(angleDeg);
        float[] xs = {b.x1(), b.x2(), b.x1(), b.x2()};
        float[] zs = {b.z1(), b.z1(), b.z2(), b.z2()};
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, depthSum = 0;
        for (int i = 0; i < 4; i++) {
            double x = xs[i], z = zs[i];
            if (localDeg != 0) { // spin the wing about its own pivot first
                double rx = x - pivotX, rz = z - pivotZ;
                x = pivotX + rx * Math.cos(local) + rz * Math.sin(local);
                z = pivotZ - rx * Math.sin(local) + rz * Math.cos(local);
            }
            double xp = x * Math.cos(g) + z * Math.sin(g);
            double zp = -x * Math.sin(g) + z * Math.cos(g);
            minX = Math.min(minX, xp);
            maxX = Math.max(maxX, xp);
            depthSum += zp;
        }
        int sx1 = Math.round((float) (cx + minX * scale));
        int sx2 = Math.max(sx1 + 1, Math.round((float) (cx + maxX * scale)));
        int sy1 = Math.round(top + (b.y1() + dy + 8) * scale);
        int sy2 = Math.max(sy1 + 1, Math.round(top + (b.y2() + dy + 8) * scale));
        out.add(new Rect(sx1, sy1, sx2, sy2, color, (float) (depthSum / 4)));
    }
}
