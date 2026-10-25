package dev.lagless;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Drives {@link LocalEcho} against a simulated Claude Code input box over a
 * slow link: random typing, cursor moves and backspaces, a placeholder hint,
 * spinner redraws, and input that wraps onto more rows as it grows. What the
 * user's terminal shows is checked against the text the user typed.
 */
class ClaudeInputSimulationTest {

    private static final long MS = 1_000_000L;
    private static final int COLS = 60, ROWS = 24;
    /** Claude Code wraps its input before the last columns. */
    private static final int WRAP = COLS - 4;

    /** The remote program: its own input buffer and cursor, redrawn after each key it handles. */
    private static final class Remote {
        final List<Integer> text = new ArrayList<>();
        int pos;
        int spinner;
        int lastRows = 1;
        /** Claude Code's box: a rule above and below the input. */
        boolean border;

        String handle(String keys) {
            for (String k : LocalEcho.tokens(keys)) {
                if (k.equals("\u007f")) {
                    if (pos > 0) text.remove(--pos);
                } else if (k.equals("\u001b[D")) {
                    pos = Math.max(0, pos - 1);
                } else if (k.equals("\u001b[C")) {
                    pos = Math.min(text.size(), pos + 1);
                } else if (k.equals("\r")) {
                    text.clear();
                    pos = 0;
                } else if (k.codePointAt(0) >= 0x20) {
                    text.add(pos++, k.codePointAt(0));
                }
            }
            return frame();
        }

        /** Rows the input takes: "❯ " then the text, wrapped every WRAP cells. */
        int rowsFor(int n) {
            return (2 + n) / WRAP + 1;
        }

        String frame() {
            StringBuilder sb = new StringBuilder("\u001b[?25l");
            int rows = rowsFor(text.size());
            int first = 21 - rows;
            // Clear the old box and whatever the box grew over.
            for (int r = Math.min(first, 21 - lastRows); r <= 21; r++) {
                sb.append("\u001b[").append(r + 1).append(";1H\u001b[2K");
            }
            lastRows = rows;
            sb.append("\u001b[").append(first - 1).append(";1H✻ Thinking ").append(spinner++).append("\u001b[K");
            if (border) {
                sb.append("\u001b[").append(first).append(";1H").append("─".repeat(COLS));
                sb.append("\u001b[22;1H").append("─".repeat(COLS));
            }
            String all;
            if (text.isEmpty()) {
                all = "❯ \u001b[2mTry \"fix lint errors\"\u001b[22m";
                sb.append("\u001b[").append(first + 1).append(";1H").append(all);
            } else {
                StringBuilder line = new StringBuilder("❯ ");
                for (int cp : text) line.appendCodePoint(cp);
                String s = line.toString();
                for (int r = 0, i = 0; i < s.length(); r++, i += WRAP) {
                    sb.append("\u001b[").append(first + 1 + r).append(";1H").append(s, i, Math.min(s.length(), i + WRAP));
                }
            }
            sb.append("\u001b[24;1Hstatus\u001b[K");
            int at = 2 + pos;
            int row = first + at / WRAP, col = at % WRAP;
            sb.append("\u001b[").append(row + 1).append(';').append(col + 1).append("H\u001b[?25h");
            return sb.toString();
        }
    }

    private record Packet(long at, String data) { }

    private final ScreenModel screen = new ScreenModel(COLS, ROWS);
    private final LocalEcho echo = new LocalEcho(screen);
    private final ScreenModel terminal = new ScreenModel(COLS, ROWS);
    private final Remote remote = new Remote();
    private final ArrayDeque<Packet> toRemote = new ArrayDeque<>(), toLocal = new ArrayDeque<>();
    private long now = 1_000 * MS;
    private long latency;

    private void deliver() {
        String out = echo.takeOutgoing();
        if (!out.isEmpty()) toRemote.add(new Packet(now + latency / 2, out));
        while (!toRemote.isEmpty() && toRemote.peek().at() <= now) {
            toLocal.add(new Packet(now + latency / 2, remote.handle(toRemote.poll().data())));
        }
        while (!toLocal.isEmpty() && toLocal.peek().at() <= now) {
            terminal.feed(echo.onOutput(toLocal.poll().data(), now));
        }
        terminal.feed(echo.tick(now));
    }

    private void advance(long nanos) {
        long end = now + nanos;
        while (now < end) {
            now += Math.min(5 * MS, end - now);
            deliver();
        }
    }

    /** All rows of the input box as the terminal shows them, joined as Claude would wrap them. */
    private String shownInput() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r <= 20; r++) {
            String row = terminal.rowText(r).stripTrailing();
            if (row.startsWith("❯")) {
                sb.setLength(0);
                // Every row but the last is filled to the wrap width.
                for (int k = r; k < 20; k++) sb.append(terminal.rowText(k), 0, WRAP);
                sb.append(terminal.rowText(20));
                return sb.toString().stripTrailing();
            }
        }
        return sb.toString();
    }

    private void run(long seed, int keys, long rtt, boolean border) {
        Random random = new Random(seed);
        latency = rtt;
        remote.border = border;
        terminal.feed(echo.onOutput("\u001b[?1049h\u001b[2J" + remote.frame(), now));
        List<Integer> typed = new ArrayList<>();
        int pos = 0;
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < keys; i++) {
            int choice = random.nextInt(10);
            String k;
            if (choice < 6 || typed.isEmpty()) {
                int cp = random.nextInt(5) == 0 ? ' ' : 'a' + random.nextInt(26);
                k = new String(Character.toChars(cp));
                typed.add(pos++, cp);
            } else if (choice < 8) {
                k = "\u001b[D";
                pos = Math.max(0, pos - 1);
            } else if (choice < 9) {
                k = "\u001b[C";
                pos = Math.min(typed.size(), pos + 1);
            } else {
                k = "\u007f";
                if (pos > 0) typed.remove(--pos);
            }
            log.append(k.length() == 1 ? k : k.equals("\u001b[D") ? "←" : "→");
            terminal.feed(echo.onKeys(k, now));
            deliver();
            advance((20 + random.nextInt(120)) * MS);
            StringBuilder expect = new StringBuilder("❯ ");
            for (int cp : typed) expect.appendCodePoint(cp);
            String want = typed.isEmpty() ? "❯" : expect.toString().stripTrailing();
            String got = shownInput();
            // Anything on screen must be the typed text, never stale or lost words.
            if (!got.equals(want) && !(typed.isEmpty() && got.startsWith("❯ Try"))) {
                // Give the link time: a long line handed to the remote shows once it echoes.
                advance(3 * rtt + 100 * MS);
                got = shownInput();
                if (!got.equals(want) && !(typed.isEmpty() && got.startsWith("❯ Try"))) {
                    StringBuilder remoteText = new StringBuilder("❯ ");
                    for (int cp : remote.text) remoteText.appendCodePoint(cp);
                    fail("seed " + seed + " rtt " + rtt / MS + (border ? " border" : "") + " after keys [" + log + "]\nwant: " + want + "\ngot:  " + got
                            + "\nremote has: " + remoteText + " (pos " + remote.pos + ", user pos " + pos + ")"
                            + "\nline=" + echo.isEditingLine() + " pending=" + echo.pendingCount()
                            + "\nrows:\n" + rows() + "remote rows:\n" + remoteRows());
                }
            }
        }
        advance(5_000 * MS);
        StringBuilder expect = new StringBuilder("❯ ");
        for (int cp : typed) expect.appendCodePoint(cp);
        assertEquals(typed.isEmpty() ? shownInput() : expect.toString().stripTrailing(), shownInput(), "seed " + seed + " settled");
    }

    private String remoteRows() {
        StringBuilder sb = new StringBuilder();
        for (int r = 17; r < ROWS; r++) sb.append(r).append(": ").append(screen.rowText(r).stripTrailing()).append('\n');
        return sb.toString();
    }

    private String rows() {
        StringBuilder sb = new StringBuilder();
        for (int r = 14; r < ROWS; r++) sb.append(r).append(": ").append(terminal.rowText(r).stripTrailing()).append('\n');
        return sb.toString();
    }

    private static void runAll(int keys) {
        long seeds = Long.getLong("sim.seeds", 60);
        for (long rtt : new long[] {60, 200, 600}) {
            for (boolean border : new boolean[] {false, true}) {
                for (long seed = 1; seed <= seeds; seed++) {
                    new ClaudeInputSimulationTest().run(seed, keys, rtt * MS, border);
                }
            }
        }
    }

    @Test
    void randomEditingOnOneRowShowsExactlyWhatWasTyped() {
        runAll(30);
    }

    @Test
    void randomEditingAcrossWrappedRowsShowsExactlyWhatWasTyped() {
        runAll(140);
    }
}
