package com.wabbit.ultimate.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What the Dashboard search box can find. */
public final class FeatureIndex {
    private FeatureIndex() {}

    public enum Target { PAGE_FEATURES, PAGE_SETTINGS, HUD_EDITOR, MODS, ADVENT, GIVEAWAY, ALERTS, YOUTUBE, SPOTIFY, COSMETICS, SYNC }

    public record Entry(String name, String keywords, Target target) {}

    private static final List<Entry> ALL = List.of(
            new Entry("FPS counter", "fps frames performance counter hud", Target.PAGE_FEATURES),
            new Entry("Memory usage", "ram memory performance hud", Target.PAGE_FEATURES),
            new Entry("Performance alerts", "lag low fps memory warning notification toast", Target.PAGE_FEATURES),
            new Entry("Balance HUD", "balance money donut donutsmp hud", Target.PAGE_FEATURES),
            new Entry("HUD editor", "hud move drag layout opacity position copy paste import export", Target.HUD_EDITOR),
            new Entry("Mod manager", "mods enable disable jar profile server restricted", Target.MODS),
            new Entry("Advent calendar", "christmas advent rng giveaway donutsmp prizes doors", Target.ADVENT),
            new Entry("Giveaway manager", "giveaway budget prizes balance donutsmp history", Target.GIVEAWAY),
            new Entry("Donation alerts", "donation alert tier sound confetti toast banner", Target.ALERTS),
            new Entry("YouTube links", "youtube video live stream link paste", Target.YOUTUBE),
            new Entry("Spotify lyrics", "spotify music song lyrics", Target.SPOTIFY),
            new Entry("Cosmetics", "hat crown halo mask glasses wings backpack cosmetic loadout favorite", Target.COSMETICS),
            new Entry("Cape", "cape skin png upload image", Target.COSMETICS),
            new Entry("Backup & sync", "backup restore sync cloud settings dropbox onedrive icloud", Target.SYNC),
            new Entry("Theme & accent color", "theme dark light accent color", Target.PAGE_SETTINGS));

    /** Every word typed must appear in the name or keywords. Name matches come first. At most 4 results. */
    public static List<Entry> search(String query) {
        List<Entry> out = new ArrayList<>();
        if (query == null || query.isBlank()) return out;
        String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<Entry> nameFirst = new ArrayList<>(), rest = new ArrayList<>();
        for (Entry e : ALL) {
            String name = e.name().toLowerCase(Locale.ROOT);
            String hay = name + " " + e.keywords();
            boolean all = true;
            for (String w : words) if (!hay.contains(w)) all = false;
            if (!all) continue;
            (name.startsWith(words[0]) ? nameFirst : rest).add(e);
        }
        out.addAll(nameFirst);
        out.addAll(rest);
        return out.size() > 4 ? new ArrayList<>(out.subList(0, 4)) : out;
    }
}
