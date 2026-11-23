package com.wabbit.ultimate.donut;

import com.wabbit.ultimate.config.WabbitConfig;

import java.util.Locale;
import java.util.Random;

/** Balance parsing, budget recommendations and the 24-door prize generator. */
public final class Advent {
    private Advent() {}

    public static final String[] RARITY_NAMES =
            {"Common", "Uncommon", "Rare", "Epic", "Legendary", "Christmas Jackpot"};
    public static final String[] RARITY_SHORT = {"Common", "Uncommon", "Rare", "Epic", "Legend", "Jackpot"};
    public static final int[] RARITY_COLORS = {
            0xFFB8B8B8, 0xFF55E060, 0xFF55AAFF, 0xFFB066FF, 0xFFFFAA00, 0xFFFFE04A
    };
    /** Relative prize size per rarity (before random jitter). */
    private static final double[] VALUE_FACTOR = {1, 2.5, 6, 14, 35, 90};
    private static final double[] DEFAULT_CHANCES = {50, 28, 14, 6, 1.8, 0.2};

    public static final String[] TIER_NAMES = {"Safe 5%", "Balanced 10%", "Generous 20%", "Large 35%"};
    public static final double[] TIER_PCT = {0.05, 0.10, 0.20, 0.35};

    public static final long MIN_BUDGET = 1000;

    /** Parses "2.5B", "750m", "12k", "1,000,000" or "$5000". Returns -1 if invalid. */
    public static long parseAmount(String raw) {
        if (raw == null) return -1;
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("$", "").replace(" ", "");
        if (s.isEmpty()) return -1;
        double mult = 1;
        char last = s.charAt(s.length() - 1);
        switch (last) {
            case 'k' -> mult = 1e3;
            case 'm' -> mult = 1e6;
            case 'b' -> mult = 1e9;
            case 't' -> mult = 1e12;
            default -> { }
        }
        if (mult != 1) s = s.substring(0, s.length() - 1);
        try {
            double v = Double.parseDouble(s) * mult;
            if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0 || v > 1e15) return -1;
            return (long) v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String format(long v) {
        if (v >= 1_000_000_000_000L) return trim(v / 1e12) + "T";
        if (v >= 1_000_000_000L) return trim(v / 1e9) + "B";
        if (v >= 1_000_000L) return trim(v / 1e6) + "M";
        if (v >= 1_000L) return trim(v / 1e3) + "K";
        return Long.toString(v);
    }

    private static String trim(double d) {
        // truncate (not round) so 9,999,999 shows as 9.99M instead of a misleading 10M
        String s = String.format(Locale.ROOT, "%.2f", Math.floor(d * 100.0) / 100.0);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static long budgetFor(long balance, int tier) {
        int t = Math.floorMod(tier, TIER_PCT.length);
        return (long) (balance * TIER_PCT[t]);
    }

    /** Builds a fresh calendar. The total never exceeds {@code budget}. */
    public static WabbitConfig.AdventState generate(long balance, long budget, double[] chances, Random rnd) {
        WabbitConfig.AdventState st = new WabbitConfig.AdventState();
        st.balance = balance;
        st.budget = budget;
        fill(24, budget, chances, rnd, st.prizes, st.rarities);
        return st;
    }

    /**
     * Rolls {@code n} rarities and prize values into the given arrays. The sum of the prizes
     * never exceeds {@code budget} (it uses roughly 90-99% of it).
     */
    public static void fill(int n, long budget, double[] chances, Random rnd, long[] prizes, int[] rarities) {
        double[] ch = validChances(chances);
        double chanceSum = 0;
        for (double c : ch) chanceSum += c;

        double[] raw = new double[n];
        double rawSum = 0;
        for (int i = 0; i < n; i++) {
            double roll = rnd.nextDouble() * chanceSum;
            int r = 0;
            double acc = 0;
            for (int k = 0; k < ch.length; k++) {
                acc += ch[k];
                if (roll < acc) {
                    r = k;
                    break;
                }
                r = k;
            }
            rarities[i] = r;
            raw[i] = VALUE_FACTOR[r] * (0.6 + 0.8 * rnd.nextDouble());
            rawSum += raw[i];
        }

        double target = budget * (0.90 + 0.09 * rnd.nextDouble());
        double scale = target / rawSum;
        long total = 0;
        for (int i = 0; i < n; i++) {
            prizes[i] = Math.max(1, tidy(raw[i] * scale));
            total += prizes[i];
        }
        int guard = 0;
        while (total > budget && guard++ < 1000) {
            int big = 0;
            for (int i = 1; i < n; i++) if (prizes[i] > prizes[big]) big = i;
            long cut = Math.max(1, prizes[big] / 10);
            prizes[big] -= cut;
            total -= cut;
        }
    }

    private static double[] validChances(double[] c) {
        if (c == null || c.length != 6) return DEFAULT_CHANCES;
        double sum = 0;
        for (double v : c) {
            if (v < 0) return DEFAULT_CHANCES;
            sum += v;
        }
        return sum > 0 ? c : DEFAULT_CHANCES;
    }

    /** Rounds down to two significant digits (1234567 -> 1200000). */
    private static long tidy(double v) {
        long x = (long) Math.floor(v);
        if (x < 100) return x;
        long mag = 1;
        while (x / mag >= 100) mag *= 10;
        return (x / mag) * mag;
    }

    public static long total(WabbitConfig.AdventState st) {
        long t = 0;
        for (long p : st.prizes) t += p;
        return t;
    }
}
