package dev.lagless;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Echo prediction (as with --no-local-line, or in vim). Drives
 * {@link LocalEcho} with output shaped like Claude Code's full-screen
 * renderer (input line on row 21, "❯ " prompt, the cursor parked after the
 * text) and checks what a terminal fed with everything lagless writes would
 * show, after every key and every remote update.
 */
class LocalEchoTest {

    private static final long MS = 1_000_000L;
    private static final int INPUT = 20;

    private final ScreenModel screen = new ScreenModel(80, 24);
    private final LocalEcho echo = new LocalEcho(screen);
    /** The user's terminal: sees nothing but the bytes lagless writes. */
    private final ScreenModel terminal = new ScreenModel(80, 24);
    private final StringBuilder bytes = new StringBuilder();
    private long now = 1_000 * MS;

    {
        // These cover echo prediction; the local line has its own tests.
        echo.setLocalLine(false);
    }

    private void write(String s) {
        bytes.append(s);
        terminal.feed(s);
    }

    private void remote(String s) {
        write(echo.onOutput(s, now));
    }

    private void key(String k) {
        write(echo.onKeys(k, now));
    }

    /** The input line as the user sees it. */
    private String input() {
        return terminal.rowText(INPUT).replace(' ', ' ').stripTrailing();
    }

    private int cursor() {
        return terminal.col();
    }

    /**
     * Claude Code redrawing the input line: text after the prompt, spaces as
     * cursor moves rather than characters, erase to the end, cursor after it.
     */
    private static String claudeLine(String text) {
        StringBuilder sb = new StringBuilder("\u001b[?25l\u001b[H\r\u001b[20B❯ ");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                sb.append("\u001b[").append(i + 4).append('G');
            } else {
                sb.append(c);
            }
        }
        sb.append("\u001b[K\u001b[24;1Hstatus\u001b[K\u001b[21;").append(text.length() + 3).append("H\u001b[?25h");
        return sb.toString();
    }

    private void startClaude() {
        remote("\u001b[?1049h\u001b[2J\u001b[H" + claudeLine("").substring("\u001b[?25l\u001b[H".length()));
    }

    /** Types "h", lets its echo arrive: prediction is then on. */
    private void confirmedWith(String first) {
        startClaude();
        key(first);
        now += 100 * MS;
        remote(claudeLine(first));
        assertTrue(echo.isConfident());
    }

    @Test
    void firstKeyWaitsForTheEchoThenKeysAppearAtOnce() {
        startClaude();
        key("h");
        assertEquals("❯", input());
        now += 200 * MS;
        remote(claudeLine("h"));
        assertEquals("❯ h", input());
        assertTrue(echo.isConfident());
        assertEquals(200, echo.roundTripMillis());

        key("i");
        assertEquals("❯ hi", input());
        assertEquals(4, cursor());
    }

    @Test
    void spacesAndTheWordsAfterThemStayWhereTheyWereTyped() {
        confirmedWith("h");
        String typed = "h";
        for (String k : new String[] {"i", " ", "w", "o", " ", " ", "x"}) {
            key(k);
            typed += k;
            assertEquals("❯ " + typed.stripTrailing(), input());
            assertEquals(2 + typed.length(), cursor());
        }
        // The echoes come in one by one, a round trip late. None of them may
        // move what is on screen: the typed text is the truth.
        for (int n = 2; n <= typed.length(); n++) {
            now += 30 * MS;
            remote(claudeLine(typed.substring(0, n)));
            assertEquals("❯ " + typed, input(), "after echo of " + n + " keys");
            assertEquals(2 + typed.length(), cursor());
        }
        assertEquals(0, echo.pendingCount());
        assertTrue(echo.isConfident());
    }

    @Test
    void backspaceIsDrawnAtOnceAndStopsAtThePrompt() {
        confirmedWith("h");
        key("i");
        now += 100 * MS;
        remote(claudeLine("hi"));
        key("\u007f");
        assertEquals("❯ h", input());
        assertEquals(3, cursor());
        key("\u007f");
        assertEquals("❯", input());
        assertEquals(2, cursor());
        // The prompt is not ours to erase: nothing drawn, prediction pauses.
        key("\u007f");
        assertEquals("❯", input());
        assertFalse(echo.isConfident());
        now += 100 * MS;
        remote(claudeLine(""));
        assertEquals("❯", input());
    }

    @Test
    void erasingASpaceWaitsForTheRemoteCursor() {
        confirmedWith("h");
        key(" ");
        now += 100 * MS;
        remote(claudeLine("h "));
        assertEquals(0, echo.pendingCount());
        key("\u007f");
        assertEquals(3, cursor());
        assertEquals(1, echo.pendingCount());
        key("x");
        assertEquals("❯ hx", input());
        now += 100 * MS;
        remote(claudeLine("h"));
        assertEquals("❯ hx", input());
        remote(claudeLine("hx"));
        assertEquals(0, echo.pendingCount());
        assertTrue(echo.isConfident());
    }

    @Test
    void typingInTheMiddlePushesTheRestRight() {
        confirmedWith("h");
        for (String k : new String[] {"e", "l", "d"}) {
            key(k);
        }
        now += 100 * MS;
        remote(claudeLine("held"));
        // Left arrow: the remote moves the cursor between "e" and "l".
        key("\u001b[D\u001b[D");
        now += 100 * MS;
        remote("\u001b[21;5H");
        // First key after the arrow is a probe.
        key("x");
        now += 100 * MS;
        remote("\u001b[?25l\u001b[21;3Hhexld\u001b[K\u001b[21;6H\u001b[?25h");
        assertTrue(echo.isConfident());
        key("y");
        assertEquals("❯ hexyld", input());
        assertEquals(6, cursor());
        key("\u007f");
        assertEquals("❯ hexld", input());
        key("\u007f");
        assertEquals("❯ held", input());
        assertEquals(4, cursor());
    }

    @Test
    void laterKeyHandledSettlesEarlierOnes() {
        confirmedWith("a");
        key("b");
        key("\u007f");
        key("c");
        assertEquals("❯ ac", input());
        now += 100 * MS;
        remote(claudeLine("ac"));
        assertEquals(0, echo.pendingCount());
        assertTrue(echo.isConfident());
    }

    @Test
    void aStateThatRepeatsIsNotCreditedWithKeysTheRemoteHasNotSeen() {
        confirmedWith("h");
        key("Q");
        now += 90 * MS;
        // Space, R, and two backspaces: the line looks as it did after "Q".
        key(" ");
        key("R");
        key("\u007f");
        key("\u007f");
        assertEquals("\u276f hQ", input());
        now += 10 * MS;
        // Only "Q" can have made the round trip yet.
        remote(claudeLine("hQ"));
        assertEquals(4, echo.pendingCount());
        assertEquals("\u276f hQ", input());
        for (String state : new String[] {"hQ ", "hQ R", "hQ ", "hQ"}) {
            now += 30 * MS;
            remote(claudeLine(state));
            assertEquals("\u276f hQ", input(), "after remote showed " + state);
            assertEquals(4, cursor());
        }
        assertEquals(0, echo.pendingCount());
        assertTrue(echo.isConfident());
    }

    @Test
    void enterKeepsWhatWasTypedUntilTheRemoteAnswers() {
        confirmedWith("h");
        key("i");
        key("\r");
        assertFalse(echo.isConfident());
        assertEquals("❯ hi", input());
        now += 100 * MS;
        // The remote took "i" and the enter: the input is empty again.
        remote(claudeLine(""));
        assertEquals("❯", input());
        assertEquals(0, echo.pendingCount());
    }

    @Test
    void redrawSplitAcrossPacketsIsHeldUntilItIsComplete() {
        confirmedWith("h");
        key("i");
        key("!");
        String frame = claudeLine("hi");
        int cut = frame.indexOf("\u001b[K");
        now += 100 * MS;
        int before = bytes.length();
        remote(frame.substring(0, cut));
        assertTrue(echo.isHolding());
        assertEquals(before, bytes.length());
        remote(frame.substring(cut));
        assertFalse(echo.isHolding());
        assertEquals("❯ hi!", input());
        String update = bytes.substring(before);
        assertTrue(update.startsWith("\u001b[?2026h\u001b[?25l"), update);
        assertTrue(update.endsWith("\u001b[?25h\u001b[?2026l"), update);
    }

    @Test
    void keysTypedWhileARedrawIsHeldArePredictedFromTheLastCompleteFrame() {
        confirmedWith("h");
        key("i");
        remote("\u001b[?25l\u001b[24;1Hstatus");
        assertTrue(echo.isHolding());
        key("\u007f");
        key("j");
        assertEquals("❯ hj", input());
        assertTrue(echo.isConfident());
    }

    @Test
    void heldOutputIsWrittenAfterAtMostTheHoldTime() {
        confirmedWith("h");
        key("i");
        remote("\u001b[?25l\u001b[1;1Hbusy");
        assertTrue(echo.isHolding());
        write(echo.tick(now + LocalEcho.HOLD_NANOS / 2));
        assertTrue(echo.isHolding());
        write(echo.tick(now + LocalEcho.HOLD_NANOS));
        assertFalse(echo.isHolding());
        assertEquals("busy", terminal.rowText(0).stripTrailing());
        assertEquals("❯ hi", input());
    }

    @Test
    void outputIsNotHeldWhenNothingIsPredicted() {
        startClaude();
        assertEquals("\u001b[?25lx", echo.onOutput("\u001b[?25lx", now));
    }

    @Test
    void keyTheRemoteHandledDifferentlyIsTakenBackOnceItIsClearlyWrong() {
        confirmedWith("h");
        key("x");
        assertEquals("❯ hx", input());
        now += 100 * MS;
        // The remote drew something else. It may be a state in between, so
        // what was typed stays while the remote keeps talking...
        remote(claudeLine("h*"));
        assertEquals("❯ hx", input());
        for (int i = 0; i < 5; i++) {
            now += 200 * MS;
            remote(claudeLine("h*"));
        }
        assertEquals("❯ hx", input());
        // ...until it has talked for a whole timeout without echoing the key.
        for (int i = 0; i < 10; i++) {
            now += 200 * MS;
            remote(claudeLine("h*"));
        }
        assertEquals("❯ h*", input());
        assertFalse(echo.isConfident());
    }

    @Test
    void deletedLetterDoesNotComeBackWhenTheRemoteIsHalfwayThere() {
        confirmedWith("h");
        key("a");
        key("b");
        now += 40 * MS;
        key("c");
        now += 50 * MS;
        key("\u007f");
        key("d");
        assertEquals("❯ habd", input());
        now += 10 * MS;
        // The remote has handled a, b and c. Its cursor stands where the
        // final one will, but the backspace and d have not reached it yet.
        remote(claudeLine("habc"));
        assertEquals("❯ habd", input());
        assertEquals(2, echo.pendingCount());
        now += 60 * MS;
        remote(claudeLine("hab"));
        assertEquals("❯ habd", input());
        remote(claudeLine("habd"));
        assertEquals("❯ habd", input());
        assertEquals(0, echo.pendingCount());
        assertTrue(echo.isConfident());
    }

    @Test
    void outputAfterTypingLandsWhereTheRemoteLeftTheCursor() {
        confirmedWith("h");
        int before = bytes.length();
        key("i");
        // The remote writes relative to where it left the cursor (after "h").
        now += 100 * MS;
        remote("\u001b[?25l\u001b[1D\u001b[1Chi\u001b[K\u001b[?25h".replace("\u001b[1D\u001b[1Ch", ""));
        assertEquals("❯ hi", input());
        String out = bytes.substring(before);
        // Local echo moves the cursor only relative to where it is.
        assertFalse(out.replace("\u001b[?2026h", "").matches("(?s).*\u001b\\[\\d+;\\d+H.*"),
                "absolute cursor move from local echo: " + out);
    }

    @Test
    void aSilentLinkKeepsTypedTextOnScreen() {
        confirmedWith("h");
        key("i");
        write(echo.tick(now + 600 * MS));
        assertEquals("❯ hi", input());
        // A stalled link: no answer can come, so nothing is given up.
        write(echo.tick(now + 20_000 * MS));
        assertEquals("❯ hi", input());
        assertTrue(echo.isConfident());
        // Only a key that has waited half a minute is let go.
        write(echo.tick(now + 31_000 * MS));
        assertEquals("❯ h", input());
    }

    @Test
    void keysTheProgramSwallowsDoNotThrowPredictionsOff() {
        // vim: "i" enters insert mode and draws nothing, "a" is then echoed at 1,1.
        remote("\u001b[?1049h\u001b[H\u001b[2J\u001b[24;1H\"v.txt\" [New]\u001b[1;1H");
        key("i");
        now += 100 * MS;
        remote("\u001b[24;1H\u001b[K-- INSERT --\u001b[1;1H");
        assertFalse(echo.isConfident());
        key("a");
        now += 100 * MS;
        remote("\u001b[?25la\u001b[?25h");
        assertTrue(echo.isConfident());
        key("b");
        assertEquals("ab", terminal.rowText(0).stripTrailing());
        now += 100 * MS;
        remote("\u001b[?25lb\u001b[?25h");
        assertEquals(0, echo.pendingCount());
        assertEquals("ab", terminal.rowText(0).stripTrailing());
    }

    @Test
    void fastTypingAfterAnEpochEndsIsDrawnOnceTheFirstKeyIsEchoed() {
        startClaude();
        for (String k : new String[] {"a", "b", "c", "d"}) {
            key(k);
            now += 10 * MS;
        }
        assertEquals("❯", input());
        // One round trip later only "a" has come back, and more keys follow.
        now += 70 * MS;
        remote(claudeLine("a"));
        assertTrue(echo.isConfident());
        assertEquals("❯ abcd", input());
        assertEquals(6, cursor());
        key("\u007f");
        key("x");
        assertEquals("❯ abcx", input());
        for (String state : new String[] {"ab", "abc", "abcd", "abc", "abcx"}) {
            now += 10 * MS;
            remote(claudeLine(state));
            assertEquals("❯ abcx", input(), "after remote showed " + state);
        }
        assertEquals(0, echo.pendingCount());
    }

    @Test
    void probeNeedsOnlyTheFirstKeysEchoAndDrawsTheRest() {
        startClaude();
        key("a");
        key("b");
        now += 100 * MS;
        remote(claudeLine("a"));
        assertTrue(echo.isConfident());
        assertEquals("❯ ab", input());
        assertEquals(1, echo.pendingCount());
        remote(claudeLine("ab"));
        assertEquals(0, echo.pendingCount());
        assertEquals("❯ ab", input());
    }

    @Test
    void keysTypedBeforePredictionStartedCanBeBackspacedLocally() {
        startClaude();
        key("a");
        key("b");
        key("c");
        now += 100 * MS;
        remote(claudeLine("abc"));
        assertTrue(echo.isConfident());
        key("\u007f");
        key("\u007f");
        assertEquals("❯ a", input());
        key("\u007f");
        assertEquals("❯", input());
        key("\u007f");
        assertEquals("❯", input());
    }

    @Test
    void backspaceWhileProbingKeepsProbing() {
        startClaude();
        key("a");
        key("x");
        key("\u007f");
        now += 100 * MS;
        remote(claudeLine("a"));
        assertTrue(echo.isConfident());
    }

    @Test
    void nothingIsPredictedOutsideTheAlternateScreen() {
        remote("user@host:~$ python3\r\n>>> ");
        key("a");
        now += 100 * MS;
        remote("a");
        key("b");
        assertFalse(echo.isConfident());
        assertEquals(">>> a", terminal.rowText(1).stripTrailing());
    }

    @Test
    void aRedrawCutByAStalledLinkDoesNotStopPrediction() {
        confirmedWith("h");
        // The next redraw starts (cursor hidden) and stops: the rest is stuck.
        remote("\u001b[?25l\u001b[24;1Hsta");
        write(echo.tick(now + LocalEcho.HOLD_NANOS));
        key("i");
        assertEquals("❯ hi", input());
    }

    @Test
    void nothingIsPredictedWhenTheTypingRowWasRedrawnAsSomethingElse() {
        confirmedWith("h");
        // A question replaced the input row, cursor hidden.
        remote("\u001b[?25l\u001b[21;1H❯ 1. Yes\u001b[K");
        write(echo.tick(now + LocalEcho.HOLD_NANOS));
        key("1");
        assertEquals("❯ 1. Yes", input());
    }

    @Test
    void imeCommitOfWideCharactersIsPredicted() {
        confirmedWith("h");
        key("你好");
        assertEquals("❯ h你好", input());
        assertEquals(7, cursor());
        now += 100 * MS;
        remote(claudeLine("h你好").replace("\u001b[21;6H", "\u001b[21;8H"));
        assertEquals(0, echo.pendingCount());
        assertEquals("❯ h你好", input());
    }

    @Test
    void doesNotPredictIntoTheLastColumn() {
        remote("\u001b[?1049h\u001b[1;79H\u001b[?25h");
        key("a");
        remote("a");
        assertTrue(echo.isConfident());
        key("b");
        assertFalse(echo.isConfident());
        assertEquals('a', terminal.charAt(0, 78));
        assertEquals(' ', terminal.charAt(0, 79));
    }

    @Test
    void resetPutsBackWhatTheRemoteShows() {
        confirmedWith("h");
        key("i");
        write(echo.reset());
        assertEquals("❯ h", input());
        assertEquals(0, echo.pendingCount());
    }

    @Test
    void disabledDoesNothing() {
        echo.setEnabled(false);
        startClaude();
        key("h");
        remote(claudeLine("h"));
        key("i");
        assertEquals("❯ h", input());
        assertFalse(echo.isConfident());
    }

    @Test
    void decodesTypedKeys() {
        assertArrayEquals(new int[] {'a', 'b'}, LocalEcho.decodeKeys("ab"));
        assertArrayEquals(new int[] {0x7F}, LocalEcho.decodeKeys("\u007f"));
        assertArrayEquals(new int[] {'h', 'I'}, LocalEcho.decodeKeys("\u001b[104u\u001b[73;2u"));
        assertNull(LocalEcho.decodeKeys("\u001b[104;5u"));
        assertNull(LocalEcho.decodeKeys("\u001b[A"));
        assertNull(LocalEcho.decodeKeys("\r"));
        assertNull(LocalEcho.decodeKeys("\u001b[13u"));
    }
}
