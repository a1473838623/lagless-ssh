package dev.lagless;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EchoFilterTest {

    @Test
    void swallowsEchoInOneChunk() {
        EchoFilter f = new EchoFilter();
        f.expect("echo hi");
        assertEquals("hi\r\nuser$ ", f.feed("echo hi\r\nhi\r\nuser$ "));
        assertFalse(f.isActive());
    }

    @Test
    void swallowsEchoSplitAcrossChunks() {
        EchoFilter f = new EchoFilter();
        f.expect("echo hi");
        assertEquals("", f.feed("echo "));
        assertEquals("", f.feed("hi"));
        assertEquals("hi\r\n", f.feed("\r\nhi\r\n"));
    }

    @Test
    void swallowsEchoWithBracketedPasteNoise() {
        EchoFilter f = new EchoFilter();
        f.expect("ls");
        assertEquals("a b\r\n", f.feed("\u001b[?2004lls\r\n\u001b[?2004ha b\r\n".replace("\u001b[?2004ha b", "a b")));
    }

    @Test
    void releasesUnrelatedOutput() {
        EchoFilter f = new EchoFilter();
        f.expect("echo hi");
        assertEquals("Password: ", f.feed("Password: "));
        assertFalse(f.isActive());
    }

    @Test
    void emptyLineEcho() {
        EchoFilter f = new EchoFilter();
        f.expect("");
        assertEquals("user$ ", f.feed("\r\nuser$ "));
    }

    @Test
    void staysActiveWhilePrefixMatches() {
        EchoFilter f = new EchoFilter();
        f.expect("echo hi");
        assertEquals("", f.feed("ec"));
        assertTrue(f.isActive());
    }
}
