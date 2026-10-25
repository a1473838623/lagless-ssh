package dev.lagless;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenModelTest {

    @Test
    void tracksCursorMovesAndText() {
        ScreenModel m = new ScreenModel(20, 5);
        m.feed("\u001b[3;5Hab\u001b[2D\u001b[1Cz");
        assertEquals(2, m.row());
        assertEquals(6, m.col());
        assertEquals("    az", m.rowText(2).stripTrailing());
    }

    @Test
    void relativeMovesFromHomeLikeClaudeCode() {
        ScreenModel m = new ScreenModel(80, 24);
        m.feed("\u001b[?1049h\u001b[2J\u001b[H\u001b[21;3H\u001b[?25h");
        m.feed("\u001b[?25l\u001b[H\r\u001b[2C\u001b[20Bh\u001b[K\u001b[24;1H\u001b[21;4H\u001b[?25h");
        assertEquals('h', m.charAt(20, 2));
        assertEquals(20, m.row());
        assertEquals(3, m.col());
        assertTrue(m.cursorVisible());
        assertTrue(m.altScreen());
    }

    @Test
    void eraseInLineAndDisplay() {
        ScreenModel m = new ScreenModel(10, 3);
        m.feed("abcdef\r\nxyz");
        m.feed("\u001b[1;3H\u001b[K");
        assertEquals("ab", m.rowText(0).stripTrailing());
        m.feed("\u001b[2J");
        assertEquals("", m.rowText(1).stripTrailing());
    }

    @Test
    void wrapsAndScrollsAtTheBottom() {
        ScreenModel m = new ScreenModel(4, 2);
        m.feed("abcd");
        assertTrue(m.pendingWrap());
        assertEquals(3, m.col());
        m.feed("ef");
        assertEquals("abcd", m.rowText(0));
        assertEquals("ef", m.rowText(1).stripTrailing());
        m.feed("gh\r\nij");
        assertEquals("efgh", m.rowText(0));
        assertEquals("ij", m.rowText(1).stripTrailing());
    }

    @Test
    void wideCharactersTakeTwoCells() {
        ScreenModel m = new ScreenModel(10, 2);
        m.feed("你好a");
        assertEquals('你', m.charAt(0, 0));
        assertEquals(ScreenModel.WIDE_TAIL, m.charAt(0, 1));
        assertEquals('a', m.charAt(0, 4));
        assertEquals(5, m.col());
        m.feed("\u001b[1;2Hx");
        assertEquals(' ', m.charAt(0, 0));
        assertEquals('x', m.charAt(0, 1));
    }

    @Test
    void alternateScreenKeepsTheMainScreen() {
        ScreenModel m = new ScreenModel(10, 3);
        m.feed("shell$ ");
        m.feed("\u001b[?1049h");
        assertTrue(m.altScreen());
        assertEquals("", m.rowText(0).stripTrailing());
        m.feed("\u001b[2;2Hvim");
        m.feed("\u001b[?1049l");
        assertFalse(m.altScreen());
        assertEquals("shell$", m.rowText(0).stripTrailing());
        assertEquals(7, m.col());
    }

    @Test
    void remembersPenPerCell() {
        ScreenModel m = new ScreenModel(10, 2);
        m.feed("\u001b[1m\u001b[38;5;246mA\u001b[0mB");
        assertEquals("\u001b[1m\u001b[38;5;246m", m.penAt(0, 0));
        assertEquals("", m.penAt(0, 1));
        assertEquals("", m.pen());
    }

    @Test
    void knowsWhenASequenceIsCutInHalf() {
        ScreenModel m = new ScreenModel(10, 2);
        m.feed("\u001b[2");
        assertFalse(m.inGround());
        m.feed(";3H");
        assertTrue(m.inGround());
        assertEquals(1, m.row());
        assertEquals(2, m.col());
        m.feed("\u001b]0;title\u0007x");
        assertEquals('x', m.charAt(1, 2));
    }

    @Test
    void scrollRegionAndLineInsertDelete() {
        ScreenModel m = new ScreenModel(5, 4);
        m.feed("a\r\nb\r\nc\r\nd");
        m.feed("\u001b[2;3r\u001b[3;1H\n");
        assertEquals("a", m.rowText(0).trim());
        assertEquals("c", m.rowText(1).trim());
        assertEquals("", m.rowText(2).trim());
        assertEquals("d", m.rowText(3).trim());
        m.feed("\u001b[r\u001b[1;1H\u001b[M");
        assertEquals("c", m.rowText(0).trim());
        m.feed("\u001b[L");
        assertEquals("", m.rowText(0).trim());
        assertEquals("c", m.rowText(1).trim());
    }

    @Test
    void emojiDrawnAsPicturesTakeTwoColumns() {
        ScreenModel m = new ScreenModel(20, 2);
        m.feed("\u2705 done \uD83D\uDE80x");
        assertEquals(ScreenModel.WIDE_TAIL, m.charAt(0, 1));
        assertEquals('d', m.charAt(0, 3));
        assertEquals('x', m.charAt(0, 10));
        assertEquals(11, m.col());
        // Text-style symbols stay one column.
        m.feed("\r\u25cf\u23bfz");
        assertEquals('z', m.charAt(0, 2));
    }

    @Test
    void repeatsThePrecedingCharacter() {
        ScreenModel m = new ScreenModel(20, 2);
        m.feed("a-\u001b[4bz");
        assertEquals("a-----z", m.rowText(0).stripTrailing());
        assertEquals(7, m.col());
    }

    @Test
    void marksOnlyAutomaticWrapsAsContinuedRows() {
        ScreenModel m = new ScreenModel(5, 4);
        // Exactly full, then a real line break: not a wrap.
        m.feed("abcde\r\n");
        // Overflowing text continues on the next row.
        m.feed("fghijkl");
        assertFalse(m.wrapped(0));
        assertTrue(m.wrapped(1));
        assertFalse(m.wrapped(2));
        // Erasing the end of a row ends its continuation.
        m.feed("\u001b[2;3H\u001b[K");
        assertFalse(m.wrapped(1));
    }

    @Test
    void wrapFlagsScrollWithTheirRows() {
        ScreenModel m = new ScreenModel(4, 2);
        boolean[] scrolledWrapped = {false};
        m.setHistoryListener(new ScreenModel.HistoryListener() {
            public void scrolled(int[] characters, String[] pens) { }
            public void scrolled(int[] characters, String[] pens, boolean wrapped) { scrolledWrapped[0] |= wrapped; }
        });
        m.feed("abcdefgh\r\nxy");
        assertTrue(scrolledWrapped[0]);
        assertFalse(m.wrapped(0));
        assertFalse(m.wrapped(1));
        m.feed("\u001b[?1049h");
        assertFalse(m.wrapped(0));
    }
}
