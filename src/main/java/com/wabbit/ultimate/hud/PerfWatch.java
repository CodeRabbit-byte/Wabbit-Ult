package com.wabbit.ultimate.hud;

/** Decides when to warn about low FPS or nearly full memory. Fed once per tick, acts about once a second. */
public final class PerfWatch {
    public record Note(String title, String text, int color) {}

    private static final long FPS_COOLDOWN_MS = 60_000, MEM_COOLDOWN_MS = 120_000;
    private static final int LOW_FPS_SECONDS = 5, HIGH_MEM_SECONDS = 10;

    private long lastCheck = Long.MIN_VALUE / 2;
    private long lastFpsNote = Long.MIN_VALUE / 2, lastMemNote = Long.MIN_VALUE / 2;
    private int lowSeconds, highSeconds;

    /** Call with real values; returns a warning to show, or null. */
    public Note check(long nowMs, int fps, long usedMb, long maxMb, int lowFpsLimit, int memPctLimit) {
        if (nowMs - lastCheck < 1000) return null;
        lastCheck = nowMs;

        lowSeconds = (fps > 0 && fps < lowFpsLimit) ? lowSeconds + 1 : 0;
        int pct = maxMb <= 0 ? 0 : (int) (usedMb * 100 / maxMb);
        highSeconds = pct >= memPctLimit ? highSeconds + 1 : 0;

        if (lowSeconds >= LOW_FPS_SECONDS && nowMs - lastFpsNote >= FPS_COOLDOWN_MS) {
            lastFpsNote = nowMs;
            lowSeconds = 0;
            return new Note("Low FPS", "Your FPS is " + fps + " (limit " + lowFpsLimit + ")", 0xFFFFAA00);
        }
        if (highSeconds >= HIGH_MEM_SECONDS && nowMs - lastMemNote >= MEM_COOLDOWN_MS) {
            lastMemNote = nowMs;
            highSeconds = 0;
            return new Note("Memory almost full", pct + "% used. Allocate more RAM or close other programs.", 0xFFFF5555);
        }
        return null;
    }

    public void reset() {
        lowSeconds = 0;
        highSeconds = 0;
    }
}
