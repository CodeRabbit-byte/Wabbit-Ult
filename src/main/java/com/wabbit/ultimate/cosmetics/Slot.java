package com.wabbit.ultimate.cosmetics;

public enum Slot {
    HAT("hat", "Hats", true),
    CROWN("crown", "Crowns", true),
    HALO("halo", "Halos", true),
    MASK("mask", "Masks", true),
    GLASSES("glasses", "Glasses", true),
    WINGS("wings", "Wings", false),
    BACK("back", "Back items", false);

    public final String id;
    public final String label;
    /** Head items follow the head's turning; the others follow the body. */
    public final boolean head;

    Slot(String id, String label, boolean head) {
        this.id = id;
        this.label = label;
        this.head = head;
    }

    public static Slot byId(String id) {
        for (Slot s : values()) if (s.id.equals(id)) return s;
        return null;
    }
}
