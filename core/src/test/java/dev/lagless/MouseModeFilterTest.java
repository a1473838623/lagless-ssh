package dev.lagless;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MouseModeFilterTest {

    private final MouseModeFilter f = new MouseModeFilter();

    @Test
    void dropsTheMouseModesClaudeCodeTurnsOn() {
        assertEquals("\u001b[?2004h", f.feed("\u001b[?2004h\u001b[?1000h\u001b[?1002h\u001b[?1003h\u001b[?1006h"));
    }

    @Test
    void keepsOtherModesInACombinedSequence() {
        assertEquals("a\u001b[?25hb", f.feed("a\u001b[?1000;25;1006hb"));
    }

    @Test
    void leavesTurningModesOffAndEverythingElseAlone() {
        String s = "\u001b[?1000l\u001b[?1049h\u001b[1;31mred\u001b[0m\u001b]0;title\u0007\u001b[H";
        assertEquals(s, f.feed(s));
    }

    @Test
    void completesASequenceSplitAcrossChunks() {
        assertEquals("text", f.feed("text\u001b[?10"));
        assertTrue(f.isHolding());
        assertEquals("\u001b[?25h", f.feed("00;25h"));
        assertFalse(f.isHolding());
        assertEquals("", f.feed("\u001b"));
        assertEquals("", f.feed("[?1006h"));
    }

    @Test
    void flushHandsBackAnUnfinishedSequence() {
        assertEquals("x", f.feed("x\u001b["));
        assertEquals("\u001b[", f.flush());
        assertFalse(f.isHolding());
    }
}
