package dev.lagless;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Resizing a window while output arrives and keys are being predicted must
 * never throw: an exception there ended the whole program. Random output,
 * keys and sizes, including one-cell windows, are mixed and replayed per seed.
 */
class ResizeStressTest {

    private static final String[] OUTPUT = {
            "hello world ", "\r\n", "\r", "\n", "\b", "\t", "中文宽字符", "😀",
            "\u001b[H", "\u001b[2J", "\u001b[K", "\u001b[1K", "\u001b[J", "\u001b[1J",
            "\u001b[5;10H", "\u001b[999;999H", "\u001b[A", "\u001b[5B", "\u001b[20C", "\u001b[3D",
            "\u001b[?1049h", "\u001b[?1049l", "\u001b[?47h", "\u001b[?47l", "\u001b7", "\u001b8",
            "\u001b[3;12r", "\u001b[r", "\u001b[L", "\u001b[3M", "\u001b[2@", "\u001b[4P", "\u001b[S", "\u001b[2T",
            "\u001b[?25l", "\u001b[?25h", "\u001b[?2026h", "\u001b[?2026l", "\u001b[32m", "\u001b[0m", "\u001bM", "\u001bD",
            "❯ ", "x".repeat(70), "\u001b[?7l", "\u001b[?7h",
    };
    private static final String[] KEYS = {"a", "b", " ", "中", "\u007f", "\u001b[D", "\u001b[C", "\r", "\u0003", "\u001b"};

    @Test
    void randomResizesNeverThrow() {
        long seeds = Long.getLong("resize.seeds", 300);
        for (long seed = 1; seed <= seeds; seed++) {
            run(seed);
        }
    }

    private static void run(long seed) {
        Random random = new Random(seed);
        int cols = 80, rows = 24;
        ScreenModel screen = new ScreenModel(cols, rows);
        LocalEcho echo = new LocalEcho(screen);
        echo.setLocalLine(true);
        ScreenModel terminal = new ScreenModel(cols, rows);
        long now = 1_000_000_000L;
        StringBuilder log = new StringBuilder();
        try {
            for (int step = 0; step < 400; step++) {
                now += (1 + random.nextInt(80)) * 1_000_000L;
                int choice = random.nextInt(10);
                if (choice < 4) {
                    StringBuilder out = new StringBuilder();
                    for (int n = 1 + random.nextInt(4); n > 0; n--) out.append(OUTPUT[random.nextInt(OUTPUT.length)]);
                    log.append(" out");
                    terminal.feed(echo.onOutput(out.toString(), now));
                } else if (choice < 7) {
                    String key = KEYS[random.nextInt(KEYS.length)];
                    log.append(" key");
                    terminal.feed(echo.onKeys(key, now));
                    echo.takeOutgoing();
                } else if (choice < 9) {
                    // As TerminalSession.checkResize does it.
                    cols = random.nextInt(8) == 0 ? 1 + random.nextInt(3) : 1 + random.nextInt(220);
                    rows = random.nextInt(8) == 0 ? 1 + random.nextInt(3) : 1 + random.nextInt(70);
                    log.append(" resize ").append(cols).append('x').append(rows);
                    terminal.feed(echo.resetForResize());
                    echo.resize(cols, rows);
                    terminal.resize(cols, rows);
                } else {
                    log.append(" tick");
                    terminal.feed(echo.tick(now));
                }
            }
        } catch (RuntimeException error) {
            AssertionError failure = new AssertionError("seed " + seed + " after:" + log.substring(Math.max(0, log.length() - 400)), error);
            fail(failure);
        }
    }
}
