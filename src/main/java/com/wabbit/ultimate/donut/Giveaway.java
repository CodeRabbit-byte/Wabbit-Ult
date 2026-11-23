package com.wabbit.ultimate.donut;

import com.wabbit.ultimate.config.WabbitConfig;

import java.util.Random;

/** General giveaway pool generator with reserve, prize-count and max-prize options. */
public final class Giveaway {
    private Giveaway() {}

    public static final String[] RESERVE_NAMES = {"Keep 0%", "Keep 10%", "Keep 25%", "Keep 50%"};
    public static final double[] RESERVE_PCT = {0, 0.10, 0.25, 0.50};
    public static final int[] COUNTS = {3, 5, 10, 15, 20};
    public static final String[] MAX_NAMES = {"Max prize 10%", "Max prize 25%", "Max prize 50%", "Max prize 100%"};
    public static final double[] MAX_PCT = {0.10, 0.25, 0.50, 1.0};

    /**
     * Budget = (balance minus the reserved part) times the tier percentage.
     * Every prize is also capped at the chosen share of the budget, so the total can only go down.
     * Returns null if the budget is below {@link Advent#MIN_BUDGET}.
     */
    public static WabbitConfig.GiveawayState generate(long balance, int tier, int reserveIdx, int countIdx,
                                                      int maxIdx, double[] chances, Random rnd) {
        int ri = Math.floorMod(reserveIdx, RESERVE_PCT.length);
        int ci = Math.floorMod(countIdx, COUNTS.length);
        int mi = Math.floorMod(maxIdx, MAX_PCT.length);
        long spendable = balance - (long) (balance * RESERVE_PCT[ri]);
        long budget = Advent.budgetFor(spendable, tier);
        if (budget < Advent.MIN_BUDGET) return null;

        int n = COUNTS[ci];
        long maxPrize = Math.max(1, (long) (budget * MAX_PCT[mi]));
        long[] prizes = new long[n];
        int[] rarities = new int[n];
        Advent.fill(n, budget, chances, rnd, prizes, rarities);
        for (int i = 0; i < n; i++) prizes[i] = Math.min(prizes[i], maxPrize);

        // sort biggest first (simple insertion sort, n <= 20)
        for (int i = 1; i < n; i++) {
            long p = prizes[i];
            int r = rarities[i];
            int j = i - 1;
            while (j >= 0 && prizes[j] < p) {
                prizes[j + 1] = prizes[j];
                rarities[j + 1] = rarities[j];
                j--;
            }
            prizes[j + 1] = p;
            rarities[j + 1] = r;
        }

        WabbitConfig.GiveawayState st = new WabbitConfig.GiveawayState();
        st.balance = balance;
        st.spendable = spendable;
        st.budget = budget;
        st.prizes = prizes;
        st.rarities = rarities;
        return st;
    }

    public static long total(WabbitConfig.GiveawayState st) {
        long t = 0;
        for (long p : st.prizes) t += p;
        return t;
    }
}
