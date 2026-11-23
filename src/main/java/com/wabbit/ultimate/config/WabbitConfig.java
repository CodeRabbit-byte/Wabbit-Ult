package com.wabbit.ultimate.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Persistent settings. Saved automatically as JSON in the Minecraft config folder. */
public class WabbitConfig {
    public boolean darkTheme = true;
    public int accentIndex = 0;

    public boolean fpsHud = true;
    public boolean memoryHud = false;
    public boolean hudBackground = true;
    public int hudX = 6;
    public int hudY = 6;
    public int hudOpacity = 60; // background opacity, percent

    /** Three saved HUD layout slots (null = empty). */
    public HudLayout[] layouts = new HudLayout[3];

    /** Mod profiles: each one lists the jar files that should be DISABLED. */
    public List<ModProfile> modProfiles = new ArrayList<>();
    /** Server address (lowercase) -> profile name. */
    public Map<String, String> serverProfiles = new HashMap<>();
    /** Server address (lowercase) -> mod ids the user flagged as risky on that server. */
    public Map<String, List<String>> restrictedMods = new HashMap<>();

    public static class ModProfile {
        public String name;
        public List<String> disabledJars = new ArrayList<>();
    }

    /** Advent calendar: balance text, chosen budget tier, drop chances, saved calendar. */
    public String adventBalanceText = "";
    public int adventTier = 1; // 0 safe, 1 balanced, 2 generous, 3 large
    /** Drop chances in percent: Common, Uncommon, Rare, Epic, Legendary, Christmas Jackpot. Edit in the config file. */
    public double[] adventChances = {50, 28, 14, 6, 1.8, 0.2};
    public AdventState advent = null;

    public static class AdventState {
        public long balance;
        public long budget;
        public long[] prizes = new long[24];
        public int[] rarities = new int[24];
        public boolean[] opened = new boolean[24];
    }

    /** Shows the typed DonutSMP balance on the HUD (manual input, no server API). */
    public boolean balanceHud = false;

    /** Giveaway Manager settings (indexes into the option tables in Giveaway.java). */
    public int giveTier = 1;
    public int giveReserveIdx = 0;
    public int giveCountIdx = 1;
    public int giveMaxIdx = 2;
    public GiveawayState giveaway = null;
    public List<GiveawayRecord> giveawayHistory = new ArrayList<>();

    public static class GiveawayState {
        public long balance;
        public long spendable;
        public long budget;
        public long[] prizes = new long[0];
        public int[] rarities = new int[0];
        public boolean saved;
    }

    public static class GiveawayRecord {
        public long when;
        public long balance;
        public long budget;
        public long total;
        public int prizes;
    }

    /** Donation alerts: highest tier whose amount is reached wins. Editable in the Alerts menu. */
    public boolean donationSound = true;
    public String donationLastName = "Steve";
    public String donationLastAmount = "10M";
    public List<DonationTier> donationTiers = defaultDonationTiers();

    public static class DonationTier {
        public long min;
        public String name;
        public int color;
        public String message;
        public int sound;  // 0 none, 1 level up, 2 challenge, 3 fireworks, 4 xp orb
        public int effect; // 0 toast, 1 banner, 2 celebration

        public DonationTier() {}

        public DonationTier(long min, String name, int color, String message, int sound, int effect) {
            this.min = min;
            this.name = name;
            this.color = color;
            this.message = message;
            this.sound = sound;
            this.effect = effect;
        }
    }

    public static List<DonationTier> defaultDonationTiers() {
        List<DonationTier> l = new ArrayList<>();
        l.add(new DonationTier(10_000_000L, "Supporter", 0xFF55E060, "{name} donated {amount}! Thank you!", 1, 0));
        l.add(new DonationTier(100_000_000L, "Big Donor", 0xFF55AAFF, "{name} donated {amount}! Amazing!", 2, 1));
        l.add(new DonationTier(1_000_000_000L, "Mega Donor", 0xFFFFAA00, "WOW! {name} donated {amount}!", 3, 2));
        l.add(new DonationTier(10_000_000_000L, "LEGEND", 0xFFFF55FF, "INSANE! {name} just donated {amount}!!!", 3, 2));
        return l;
    }

    /** YouTube links (videos or livestreams) the user pasted. */
    public List<YtLink> ytLinks = new ArrayList<>();
    public String ytSelectedId = null;
    public boolean ytHud = true;

    public static class YtLink {
        public String id;
        public String title;
        public String channel;

        public YtLink() {}

        public YtLink(String id, String title, String channel) {
            this.id = id;
            this.title = title;
            this.channel = channel;
        }
    }

    /** Spotify lyrics HUD. The refresh token is NOT included when syncing to a cloud folder. */
    public String spotifyClientId = "";
    public String spotifyRefreshToken = "";
    public boolean spotifyHud = true;
    public int spotifyCorner = 1; // 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right
    public int spotifyLines = 0;  // 0 = 3 lyric lines, 1 = 5 lyric lines

    /** Backup & sync: a folder your own cloud service keeps in sync (Dropbox, OneDrive, iCloud...). */
    public String syncFolder = "";
    public long syncLastTime = 0L;

    /** Cosmetics (drawn on your own player only; other players cannot see them). */
    public boolean cosmeticsOn = true;
    public Map<String, String> cosmeticSlots = new HashMap<>();   // slot id -> cosmetic id
    public Map<String, Integer> cosmeticTints = new HashMap<>();  // slot id -> palette index
    public List<String> cosmeticFavs = new ArrayList<>();
    public CosmeticLoadout[] cosmeticLoadouts = new CosmeticLoadout[3];
    public String capeFile = "";   // PNG inside config/wabbit-capes
    public boolean capeOn = true;
    public boolean capeFlip = false;

    public static class CosmeticLoadout {
        public Map<String, String> slots = new HashMap<>();
        public Map<String, Integer> tints = new HashMap<>();
        public String capeFile = "";
        public boolean capeFlip;
    }

    public CosmeticLoadout[] loadoutSlots() {
        if (cosmeticLoadouts == null || cosmeticLoadouts.length != 3) cosmeticLoadouts = new CosmeticLoadout[3];
        return cosmeticLoadouts;
    }

    /** Performance alerts: a toast when FPS stays low or memory is nearly full. Thresholds can be edited here. */
    public boolean perfAlerts = true;
    public int perfLowFps = 30;
    public int perfHighMemPct = 90;

    public static final String[] ACCENT_NAMES = {"Pink", "Green", "Blue", "Gold", "Red", "Purple"};
    public static final int[] ACCENT_COLORS = {
            0xFFFF5FA2, 0xFF3DDC84, 0xFF4DA3FF, 0xFFFFC83D, 0xFFFF4D4D, 0xFFB26BFF
    };

    public static class HudLayout {
        public int x, y, opacity;
        public boolean fps, memory, background;
    }

    public HudLayout captureLayout() {
        HudLayout l = new HudLayout();
        l.x = hudX;
        l.y = hudY;
        l.opacity = hudOpacity;
        l.fps = fpsHud;
        l.memory = memoryHud;
        l.background = hudBackground;
        return l;
    }

    public void applyLayout(HudLayout l) {
        hudX = l.x;
        hudY = l.y;
        hudOpacity = l.opacity;
        fpsHud = l.fps;
        memoryHud = l.memory;
        hudBackground = l.background;
    }

    public void resetHud() {
        hudX = 6;
        hudY = 6;
        hudOpacity = 60;
    }

    public HudLayout[] layoutSlots() {
        if (layouts == null || layouts.length != 3) layouts = new HudLayout[3];
        return layouts;
    }

    public int accent() {
        return ACCENT_COLORS[Math.floorMod(accentIndex, ACCENT_COLORS.length)];
    }

    public String accentName() {
        return ACCENT_NAMES[Math.floorMod(accentIndex, ACCENT_NAMES.length)];
    }
}
