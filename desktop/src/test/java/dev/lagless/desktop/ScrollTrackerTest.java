package dev.lagless.desktop;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScrollTrackerTest {
    static final int COLUMNS = 20;

    /** A screen of {@code rows} rows showing document lines from {@code first}, with an optional header and status line. */
    static int[] screen(int rows, int first, boolean chrome) {
        int[] chars = new int[COLUMNS * rows];
        java.util.Arrays.fill(chars, ' ');
        for (int r = 0; r < rows; r++) {
            String text = chrome && r == 0 ? "HEADER" : chrome && r == rows - 1 ? "-- status --" : "line " + (first + r - (chrome ? 1 : 0));
            for (int c = 0; c < text.length(); c++) chars[r * COLUMNS + c] = text.charAt(c);
        }
        return chars;
    }

    static String text(int[] cells) {
        StringBuilder text = new StringBuilder();
        for (int cp : cells) text.appendCodePoint(cp);
        return text.toString().strip();
    }

    @Test void followsContentScrolledUpAndDown() {
        ScrollTracker tracker = new ScrollTracker();
        ScrollTracker.Track start = tracker.frame(COLUMNS, 10, screen(10, 0, false));
        assertEquals(0, start.top());
        assertEquals(3, tracker.frame(COLUMNS, 10, screen(10, 3, false)).top());
        assertEquals(1, tracker.frame(COLUMNS, 10, screen(10, 1, false)).top());
        assertEquals(1, tracker.frame(COLUMNS, 10, screen(10, 1, false)).top(), "an unchanged frame does not move");
        // Every line seen stays readable at its document row, even after it scrolled away.
        for (int line = 0; line < 13; line++) assertEquals("line " + line, text(tracker.line(line)));
    }

    @Test void headerAndStatusLineStayOutsideTheScrollingRegion() {
        ScrollTracker tracker = new ScrollTracker();
        tracker.frame(COLUMNS, 12, screen(12, 0, true));
        ScrollTracker.Track moved = tracker.frame(COLUMNS, 12, screen(12, 4, true));
        assertEquals(4, moved.top());
        assertEquals(1, moved.regionTop());
        assertEquals(10, moved.regionBottom());
        assertEquals("line 4", text(tracker.line(moved.top() + 1)));
        assertEquals("line 0", text(tracker.line(1)));
    }

    @Test void unrelatedRedrawsAndRepeatedRowsDoNotSuggestAMove() {
        ScrollTracker tracker = new ScrollTracker();
        tracker.frame(COLUMNS, 8, screen(8, 0, false));
        int[] other = new int[COLUMNS * 8];
        java.util.Arrays.fill(other, '|');
        assertEquals(0, tracker.frame(COLUMNS, 8, other).top());
        assertEquals(0, tracker.frame(COLUMNS, 8, other).top());
        // One row that happens to reappear elsewhere is not a scroll.
        int[] single = screen(8, 100, false);
        System.arraycopy(screen(8, 0, false), 0, single, 5 * COLUMNS, COLUMNS);
        tracker.frame(COLUMNS, 8, screen(8, 0, false));
        assertEquals(0, tracker.frame(COLUMNS, 8, single).top());
    }

    @Test void resizeStartsANewDocument() {
        ScrollTracker tracker = new ScrollTracker();
        long generation = tracker.frame(COLUMNS, 10, screen(10, 0, false)).generation();
        tracker.frame(COLUMNS, 10, screen(10, 2, false));
        ScrollTracker.Track resized = tracker.frame(COLUMNS, 9, screen(9, 2, false));
        assertNotEquals(generation, resized.generation());
        assertEquals(0, resized.top());
    }
}
