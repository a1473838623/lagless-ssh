package dev.lagless;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputMonitorTest {

    @Test
    void tracksLastLine() {
        OutputMonitor m = new OutputMonitor();
        m.feed("Welcome\r\nline two\r\nuser@host:~$ ");
        assertEquals("user@host:~$ ", m.currentLineText());
    }

    @Test
    void keepsColoursForPrompt() {
        OutputMonitor m = new OutputMonitor();
        m.feed("x\r\n\u001b]0;t\u0007\u001b[32muser\u001b[0m$ ");
        assertEquals("user$ ", m.currentLineText());
        assertEquals("\u001b[32muser\u001b[0m$ ", m.currentLinePrompt());
    }

    @Test
    void escapeSplitAcrossChunks() {
        OutputMonitor m = new OutputMonitor();
        m.feed("\u001b[3");
        m.feed("2mok$ ");
        assertEquals("ok$ ", m.currentLineText());
    }

    @Test
    void alternateScreen() {
        OutputMonitor m = new OutputMonitor();
        assertFalse(m.isAltScreen());
        m.feed("vim\r\n\u001b[?1049h\u001b[H~\r\n~");
        assertTrue(m.isAltScreen());
        m.feed("\u001b[?1049l\r\nuser$ ");
        assertFalse(m.isAltScreen());
        assertEquals("user$ ", m.currentLineText());
    }

    @Test
    void bareCarriageReturnRestartsLine() {
        OutputMonitor m = new OutputMonitor();
        m.feed("progress 10%\rprogress 20%");
        assertEquals("progress 20%", m.currentLineText());
    }
}
