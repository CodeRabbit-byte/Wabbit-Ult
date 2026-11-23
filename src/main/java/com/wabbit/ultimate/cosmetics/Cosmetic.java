package com.wabbit.ultimate.cosmetics;

import java.util.List;

public record Cosmetic(String id, String name, Slot slot, int defaultTint, boolean bob, List<Box> boxes) {

    public boolean hasTintable() {
        for (Box b : boxes) if (b.tint()) return true;
        return false;
    }

    public List<Box> group(int g) {
        return boxes.stream().filter(b -> b.group() == g).toList();
    }
}
