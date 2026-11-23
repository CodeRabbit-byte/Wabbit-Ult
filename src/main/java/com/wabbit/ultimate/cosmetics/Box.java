package com.wabbit.ultimate.cosmetics;

/**
 * One coloured block, in player-model pixels (16 pixels = 1 block). Model space: +x is the player's left,
 * +y is DOWN, -z is the front. {@code group} 0 = fixed, 1 = right-hand-side wing, 2 = other wing (they flap).
 */
public record Box(float x1, float y1, float z1, float x2, float y2, float z2,
                  int argb, boolean tint, boolean glow, int group) {

    public Box inGroup(int g) {
        return new Box(x1, y1, z1, x2, y2, z2, argb, tint, glow, g);
    }

    public Box mirroredX(int g) {
        return new Box(-x2, y1, z1, -x1, y2, z2, argb, tint, glow, g);
    }

    /** The real colour: tintable boxes take the chosen palette colour but keep their own transparency. */
    public int color(int tintRgb) {
        return tint ? ((argb & 0xFF000000) | (tintRgb & 0xFFFFFF)) : argb;
    }
}
