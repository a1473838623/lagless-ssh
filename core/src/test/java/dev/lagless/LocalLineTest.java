package dev.lagless;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The local line: at Claude Code's empty "❯ " prompt the line is edited in
 * lagless and only sent on enter. Checks what a terminal fed with everything
 * lagless writes would show, and exactly what goes to the remote.
 */
class LocalLineTest {

    private static final long MS = 1_000_000L;
    private static final int INPUT = 20;

    private final ScreenModel screen = new ScreenModel(80, 24);
    private final LocalEcho echo = new LocalEcho(screen);
    private final ScreenModel terminal = new ScreenModel(80, 24);
    private final StringBuilder sent = new StringBuilder();
    private long now = 1_000 * MS;

    private void write(String s) {
        terminal.feed(s);
        sent.append(echo.takeOutgoing());
    }

    private void remote(String s) {
        write(echo.onOutput(s, now));
    }

    private void key(String k) {
        write(echo.onKeys(k, now));
    }

    private String input() {
        return terminal.rowText(INPUT).replace(' ', ' ').stripTrailing();
    }

    @Test void coloredTextAfterTheCursorIsInputNotAPlaceholder() {
        startClaude();
        // Tab accepted a suggestion, which Claude Code draws in a 24-bit colour; the user then
        // moved the cursor to the start of the input.
        remote("\u001b[?25l\u001b[21;1H❯ \u001b[38;2;177;185;249mfix the failing tests\u001b[39m\u001b[K\u001b[21;3H\u001b[?25h");
        key("x");
        assertFalse(echo.isEditingLine(), "typing at the start of existing input must not start a local line over it");
        assertEquals("x", sent.toString(), "the key goes straight to Claude Code");
        assertTrue(input().contains("fix the failing tests"), "the existing text stays on screen: " + input());
    }

    @Test void onlyFaintTextCountsAsDim() {
        assertTrue(LocalEcho.isDim("\u001b[2m"));
        assertTrue(LocalEcho.isDim("\u001b[0;2;38;2;1;2;3m"));
        assertFalse(LocalEcho.isDim("\u001b[2m\u001b[22m"));
        assertFalse(LocalEcho.isDim("\u001b[2m\u001b[0m"));
        assertFalse(LocalEcho.isDim("\u001b[38;2;200;200;200m"), "24-bit colour is not dim");
        assertFalse(LocalEcho.isDim("\u001b[38;5;2m"), "palette colour 2 is not dim");
        assertFalse(LocalEcho.isDim("\u001b[48;2;2;2;2m"));
        assertTrue(LocalEcho.isDim("\u001b[38;2;9;9;9;2m"), "dim after a 24-bit colour");
        assertFalse(LocalEcho.isDim(""));
    }

    @Test void modeChangeMustNotResetAnUnsentLocalLine() {
        startClaude();assertFalse(echo.hasBufferedInput());key("unsent");assertTrue(echo.hasBufferedInput());assertEquals("",sent.toString());key("\u0015");key("\u007f");assertFalse(echo.hasBufferedInput());
    }

    /** Claude Code's input line: prompt, text, dim placeholder when empty, cursor after the text. */
    private static String claudeLine(String text) {
        String body = text.isEmpty() ? "\u001b[2mTry \"fix lint errors\"\u001b[22m" : text;
        return "\u001b[?25l\u001b[21;1H❯ " + body + "\u001b[K\u001b[24;1Hstatus\u001b[K\u001b[21;"
                + (text.length() + 3) + "H\u001b[?25h";
    }

    /** A frame that redraws the transcript and the spinner, like Claude Code while it answers. */
    private static String busyFrame(int n) {
        return "\u001b[?25l\u001b[5;1Hline " + n + "\u001b[K\u001b[19;1H✻ Thinking… " + n + "s\u001b[K"
                + claudeLine("").substring("\u001b[?25l".length());
    }

    private void startClaude() {
        remote("\u001b[?1049h\u001b[2J\u001b[H" + claudeLine(""));
    }

    @Test
    void typingIsLocalAndNothingIsSentUntilEnter() {
        startClaude();
        for (String k : "hello world".split("")) {
            key(k);
        }
        assertTrue(echo.isEditingLine());
        assertEquals("❯ hello world", input());
        assertEquals(13, terminal.col());
        assertEquals("", sent.toString());
    }

    @Test
    void theRemoteRedrawingItsScreenNeverMovesTheTypedLine() {
        startClaude();
        String typed = "";
        for (String k : "fix the bug in main please".split("")) {
            key(k);
            typed += k;
            now += 40 * MS;
            // Whatever the link does, the remote keeps drawing its empty prompt.
            remote(busyFrame(typed.length()));
            assertEquals(("❯ " + typed).stripTrailing(), input());
            assertEquals(2 + typed.length(), terminal.col());
        }
        assertEquals("", sent.toString());
        assertEquals("line " + typed.length(), terminal.rowText(4).stripTrailing());
    }

    @Test
    void editingKeysWorkLocally() {
        startClaude();
        for (String k : "helo wrld".split("")) {
            key(k);
        }
        key("\u001b[D");
        key("\u001b[D");
        key("\u001b[D");
        key("o");
        assertEquals("❯ helo world", input());
        key("\u0001");
        key("\u001b[C");
        key("\u001b[C");
        key("\u001b[C");
        key("l");
        assertEquals("❯ hello world", input());
        key("\u0005");
        key("\u0017");
        assertEquals("❯ hello", input());
        // "hello " minus the space and the "o".
        key("\u007f");
        key("\u007f");
        assertEquals("❯ hell", input());
        assertEquals(6, terminal.col());
        key("\u0015");
        assertEquals("❯", input());
        assertEquals("", sent.toString());
    }

    @Test
    void enterClearsTheBoxAtOnceAndSendsEnterOnceTheRemoteShowsTheText() {
        startClaude();
        for (String k : "hi there".split("")) {
            key(k);
        }
        key("\r");
        // Like a chat box: empty at once, the text is on its way.
        assertEquals("hi there", sent.toString());
        assertEquals("❯", input());
        now += 100 * MS;
        remote(claudeLine("hi"));
        assertEquals("hi there", sent.toString());
        assertEquals("❯", input());
        remote(claudeLine("hi there"));
        assertEquals("hi there\r", sent.toString());
        // The remote still shows the text until it takes the enter.
        assertEquals("❯", input());
        now += 100 * MS;
        remote(claudeLine(""));
        assertFalse(echo.isEditingLine());
        assertEquals("❯ Try \"fix lint errors\"", input());
    }

    @Test
    void enterGoesAnywayWhenTheRemoteTalksButNeverShowsTheText() {
        startClaude();
        key("x");
        key("\r");
        assertEquals("x", sent.toString());
        for (int i = 1; i <= 30; i++) {
            now += 100 * MS;
            remote(busyFrame(i));
        }
        assertEquals("x\r", sent.toString());
    }

    @Test
    void aStalledLinkDelaysOnlyTheSendingNeverTheScreen() {
        startClaude();
        remote(busyFrame(1));
        // A lost packet cuts the next redraw: cursor hidden, sequence cut.
        remote("\u001b[?25l\u001b[5;1Hline 2\u001b[K\u001b[19;");
        for (String k : "typed during the stall".split("")) {
            now += 50 * MS;
            key(k);
            write(echo.tick(now));
        }
        assertEquals("❯ typed during the stall", input());
        assertTrue(terminal.cursorVisible());
        key("\r");
        assertEquals("❯", input());
        key("n");
        key("e");
        key("x");
        key("t");
        assertEquals("❯ next", input());
        // Seconds of silence: nothing is given up, nothing moves.
        now += 20_000 * MS;
        write(echo.tick(now));
        assertEquals("❯ next", input());
        assertEquals("typed during the stall", sent.toString());
        // The link comes back: the rest of the redraw, then the echo.
        remote("1H\u273b Thinking\u2026\u001b[K" + claudeLine("").substring("\u001b[?25l".length()));
        assertEquals("❯ next", input());
        now += 100 * MS;
        remote(claudeLine("typed during the stall"));
        assertEquals("typed during the stall\r", sent.toString());
        assertEquals("❯ next", input());
        now += 100 * MS;
        remote(claudeLine(""));
        assertEquals("❯ next", input());
        assertTrue(echo.isEditingLine());
    }

    @Test
    void theNextLineIsTypedWhileTheEnterWaits() {
        startClaude();
        key("a");
        key("\r");
        key("b");
        key("c");
        assertEquals("a", sent.toString());
        assertEquals("❯ bc", input());
        now += 100 * MS;
        remote(claudeLine("a"));
        assertEquals("a\r", sent.toString());
        assertEquals("❯ bc", input());
        remote(claudeLine(""));
        assertEquals("❯ bc", input());
        key("\r");
        assertEquals("a\rbc", sent.toString());
    }

    @Test
    void tabHandsTheLineToTheRemoteAndKeepsItOnScreen() {
        startClaude();
        for (String k : "/mod".split("")) {
            key(k);
        }
        key("\t");
        assertEquals("/mod\t", sent.toString());
        assertFalse(echo.isEditingLine());
        assertEquals("❯ /mod", input());
        now += 100 * MS;
        remote(claudeLine("/model"));
        assertEquals("❯ /model", input());
    }

    @Test
    void aQuestionFromTheProgramGetsTheKeysAndTheLineComesBack() {
        startClaude();
        key("n");
        key("e");
        now += 50 * MS;
        // A permission question replaces the prompt: cursor hidden on the menu.
        remote("\u001b[?25l\u001b[21;1H❯ 1. Yes\u001b[K\u001b[22;1H  2. No\u001b[K\u001b[?25h\u001b[23;1H");
        assertEquals("❯ 1. Yes", input());
        key("1");
        assertEquals("1", sent.toString());
        now += 50 * MS;
        remote("\u001b[22;1H\u001b[K" + claudeLine(""));
        assertEquals("❯ ne", input());
        key("w");
        assertEquals("❯ new", input());
        assertEquals("1", sent.toString());
    }

    @Test
    void leavingTheProgramDropsTheLineWithoutSendingIt() {
        startClaude();
        key("q");
        remote("\u001b[?1049l$ ");
        write(echo.reset());
        assertFalse(echo.isEditingLine());
        assertEquals("", sent.toString());
    }

    @Test
    void backspaceOnAnEmptyLineSendsNothing() {
        startClaude();
        key("a");
        key("\u007f");
        key("\u007f");
        assertFalse(echo.isEditingLine());
        assertEquals("", sent.toString());
        assertEquals("❯ Try \"fix lint errors\"", input());
    }

    @Test
    void ctrlCClearsTheLineLocally() {
        startClaude();
        key("o");
        key("o");
        key("\u0003");
        assertFalse(echo.isEditingLine());
        assertEquals("", sent.toString());
        assertEquals("❯ Try \"fix lint errors\"", input());
    }

    @Test
    void aLineLongerThanTheRowIsHandedOver() {
        startClaude();
        StringBuilder typed = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            key("x");
            typed.append('x');
        }
        assertFalse(echo.isEditingLine());
        assertEquals(typed.toString(), sent.toString());
    }

    @Test
    void aLongLineHandedOverWithTheCursorInsideStaysOnScreenUntilTheRemoteShowsIt() {
        startClaude();
        String text = "a".repeat(40) + "b".repeat(36);
        for (String k : text.split("")) {
            key(k);
        }
        for (int i = 0; i < 36; i++) {
            key("\u001b[D");
        }
        // Too long for the row: the line goes to the remote, cursor and all.
        key("c");
        assertFalse(echo.isEditingLine());
        assertEquals(text + "\u001b[D".repeat(36) + "c", sent.toString());
        // Before the echo the typed text stays, never the remote's empty prompt.
        String shown = "❯ " + "a".repeat(40) + "c" + "b".repeat(36);
        assertEquals(shown, input());
        assertEquals(43, terminal.col());
        now += 100 * MS;
        remote(claudeLine(""));
        assertEquals(shown, input());
    }

    @Test
    void chineseTextFromAnImeIsEditedLocally() {
        startClaude();
        key("你好");
        key("世界");
        assertEquals("❯ 你好世界", input());
        assertEquals(10, terminal.col());
        key("\u007f");
        assertEquals("❯ 你好世", input());
        assertEquals("", sent.toString());
    }

    @Test
    void offMeansKeysGoOutAsTyped() {
        echo.setLocalLine(false);
        startClaude();
        key("a");
        assertEquals("a", sent.toString());
        assertFalse(echo.isEditingLine());
    }

    @Test
    void textAlreadyInTheRemoteInputIsNotEditedLocally() {
        remote("\u001b[?1049h\u001b[2J\u001b[H" + claudeLine("draft"));
        key("x");
        assertFalse(echo.isEditingLine());
        assertEquals("x", sent.toString());
    }

    @Test
    void splitsKeysIntoTokens() {
        assertEquals(java.util.List.of("a", "\u001b[D", "\u001bOH", "\u007f", "\u001b[3~", "你"),
                LocalEcho.tokens("a\u001b[D\u001bOH\u007f\u001b[3~你"));
        assertEquals(java.util.List.of("x", "\u001b[200~pasted\u001b[201~"),
                LocalEcho.tokens("x\u001b[200~pasted\u001b[201~"));
    }
}
