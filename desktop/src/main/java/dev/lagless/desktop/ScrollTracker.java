package dev.lagless.desktop;

import java.util.*;

/**
 * Follows content that a full-screen application scrolls by redrawing the screen. Each
 * finished frame is compared with the previous one: when its rows reappear a few rows
 * higher or lower, the content moved by that many rows. Rows are numbered in one continuous
 * document, so a selection stays on its text, and rows that scrolled out of view are kept
 * for copying. Rows that do not move, such as a header or a status line, are outside the
 * scrolling region.
 */
final class ScrollTracker {
    /** Where the shown frame sits: screen row r is document row {@code top + r}. */
    record Track(long top, int regionTop, int regionBottom, long generation) { }

    static final int KEPT_ROWS = 20000;
    private static final long BLANK = Long.MIN_VALUE;

    private final LinkedHashMap<Long, int[]> lines = new LinkedHashMap<>(256, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, int[]> eldest) { return size() > KEPT_ROWS; }
    };
    private long[] previous;
    private int columns, rows, regionTop, regionBottom;
    private long top, generation;
    private boolean active;

    boolean active() { return active; }

    /** Forgets the document; a selection made in it no longer applies. */
    void reset() {
        active = false; previous = null; lines.clear(); top = 0; generation++;
    }

    Track track() { return active ? new Track(top, regionTop, regionBottom, generation) : null; }

    /** Records a finished frame of {@code rows} rows, row by row in {@code chars}. */
    Track frame(int columns, int rows, int[] chars) {
        if (columns != this.columns || rows != this.rows) {
            if (active) reset();
            this.columns = columns; this.rows = rows;
        }
        long[] current = new long[rows];
        for (int r = 0; r < rows; r++) {
            long hash = 17; boolean blank = true;
            for (int c = 0; c < columns; c++) {
                int cp = chars[r * columns + c];
                hash = hash * 31 + cp;
                if (cp > ' ') blank = false;
            }
            current[r] = blank ? BLANK : hash == BLANK ? hash + 1 : hash;
        }
        if (previous == null) {
            regionTop = 0; regionBottom = rows - 1;
        } else {
            int[] moved = shift(previous, current);
            if (moved != null) { top += moved[0]; regionTop = moved[1]; regionBottom = moved[2]; }
        }
        previous = current; active = true;
        for (int r = regionTop; r <= regionBottom; r++) lines.put(top + r, Arrays.copyOfRange(chars, r * columns, (r + 1) * columns));
        return track();
    }

    /** The last content seen in a document row, or null when it was never on screen. */
    int[] line(long row) { return lines.get(row); }

    /**
     * How far the content moved between two frames, given as row hashes: {shift, first
     * scrolling row, last scrolling row}, or null when it did not move. A positive shift
     * means the content moved up. Only rows that occur once in both frames count, so
     * repeated borders or blank lines cannot suggest a move.
     */
    static int[] shift(long[] previous, long[] current) {
        int rows = current.length;
        Map<Long, Integer> count = new HashMap<>();
        for (long hash : current) if (hash != BLANK) count.merge(hash, 1, Integer::sum);
        Map<Long, Integer> before = new HashMap<>();
        for (int r = 0; r < previous.length; r++) if (previous[r] != BLANK) before.merge(previous[r], r, (a, b) -> -1);
        int[] votes = new int[2 * rows + 1];
        int[] from = new int[rows];
        for (int r = 0; r < rows; r++) {
            from[r] = -1;
            if (current[r] == BLANK || count.get(current[r]) != 1) continue;
            Integer at = before.get(current[r]);
            if (at == null || at < 0) continue;
            from[r] = at;
            votes[at - r + rows]++;
        }
        int best = 0;
        for (int distance = 1; distance < rows; distance++)
            for (int d : new int[] {distance, -distance})
                if (votes[d + rows] > votes[best + rows] || best == 0 && votes[d + rows] > 0) best = d;
        if (best == 0 || votes[best + rows] < 2 || votes[best + rows] < votes[rows]) return null;
        int low = rows, high = -1;
        for (int r = 0; r < rows; r++) if (from[r] >= 0 && from[r] - r == best) { low = Math.min(low, r); high = Math.max(high, r); }
        while (low > 0 && !fixed(previous, current, count, low - 1)) low--;
        while (high < rows - 1 && !fixed(previous, current, count, high + 1)) high++;
        return new int[] {best, low, high};
    }

    private static boolean fixed(long[] previous, long[] current, Map<Long, Integer> count, int row) {
        return current[row] != BLANK && current[row] == previous[row] && count.get(current[row]) == 1;
    }
}
