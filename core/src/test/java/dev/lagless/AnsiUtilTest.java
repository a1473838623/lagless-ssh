package dev.lagless;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnsiUtilTest {

    @Test
    void stripsCsiOscAndControls() {
        String in = "\u001b]0;title\u0007\u001b[1;32muser@host\u001b[0m:\u001b[34m~\u001b[0m$ \r";
        assertEquals("user@host:~$ ", AnsiUtil.strip(in));
    }

    @Test
    void backspaceDeletesPreviousChar() {
        assertEquals("ab", AnsiUtil.strip("abc\b"));
    }

    @Test
    void keepsOnlySgr() {
        String in = "\u001b]0;title\u0007\u001b[1;32mu\u001b[0m\u001b[2K$ ";
        assertEquals("\u001b[1;32mu\u001b[0m$ ", AnsiUtil.keepSgrOnly(in));
    }

    @Test
    void unterminatedEscapeRunsToEnd() {
        assertEquals("ab", AnsiUtil.strip("ab\u001b[1;3"));
    }
}
