package com.wabbit.ultimate.spotify;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Synced lyrics in LRC format: "[01:23.45] some line". */
public final class Lrc {
    public final long[] ms;
    public final String[] text;

    private Lrc(long[] ms, String[] text) {
        this.ms = ms;
        this.text = text;
    }

    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");

    /** Returns null if the text holds no timed lines. */
    public static Lrc parse(String lrc) {
        if (lrc == null || lrc.isBlank()) return null;
        record Line(long ms, String text) {}
        List<Line> lines = new ArrayList<>();
        for (String raw : lrc.split("\\r?\\n")) {
            Matcher m = STAMP.matcher(raw);
            List<Long> stamps = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                long frac = 0;
                String f = m.group(3);
                if (f != null) {
                    frac = Long.parseLong(f);
                    if (f.length() == 1) frac *= 100;
                    else if (f.length() == 2) frac *= 10;
                }
                stamps.add(min * 60_000 + sec * 1000 + frac);
                end = m.end();
            }
            if (stamps.isEmpty()) continue; // metadata such as [ar:Artist]
            String t = raw.substring(end).trim();
            if (t.isEmpty()) t = "...";
            for (long st : stamps) lines.add(new Line(st, t));
        }
        if (lines.isEmpty()) return null;
        lines.sort(Comparator.comparingLong(Line::ms));
        long[] ms = new long[lines.size()];
        String[] text = new String[lines.size()];
        for (int i = 0; i < ms.length; i++) {
            ms[i] = lines.get(i).ms();
            text[i] = lines.get(i).text();
        }
        return new Lrc(ms, text);
    }

    /** Index of the line being sung at the given position, or -1 before the first line. */
    public int indexAt(long positionMs) {
        int lo = 0, hi = ms.length - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (ms[mid] <= positionMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    public int size() {
        return ms.length;
    }
}
