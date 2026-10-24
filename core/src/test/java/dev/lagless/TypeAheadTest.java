package dev.lagless;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characters typed while a command is still running go straight to the remote
 * pty and end up in the shell's line buffer. lagless must not then send a new
 * line on top of them, or the two get concatenated.
 */
@EnabledIfEnvironmentVariable(named = "LAGLESS_TEST_HOST", matches = ".+")
class TypeAheadTest {

    private PipedOutputStream keys;
    private final ByteArrayOutputStream screen = new ByteArrayOutputStream();
    private Terminal terminal;
    private SshConnection conn;
    private Thread runner;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger edits = new AtomicInteger();

    @BeforeEach
    void connect() throws Exception {
        Log.enable(Paths.get("target", "typeahead-debug.log"));
        PipedInputStream in = new PipedInputStream(1 << 16);
        keys = new PipedOutputStream(in);
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) {
                synchronized (screen) {
                    screen.write(b);
                }
            }

            @Override
            public void write(byte[] b, int off, int len) {
                synchronized (screen) {
                    screen.write(b, off, len);
                }
            }
        };
        terminal = TerminalBuilder.builder().system(false).streams(in, out)
                .type("xterm-256color").encoding(StandardCharsets.UTF_8)
                .size(new Size(120, 40)).build();

        Options o = new Options();
        o.host = System.getenv("LAGLESS_TEST_HOST");
        o.port = Integer.parseInt(System.getenv().getOrDefault("LAGLESS_TEST_PORT", "22"));
        o.user = System.getenv("LAGLESS_TEST_USER");
        o.identity = System.getenv("LAGLESS_TEST_KEY");
        o.debug = true;
        conn = SshConnection.connect(o, SshConnection.nonInteractive(q -> true));
        TerminalSession session = new TerminalSession(terminal, conn, o);
        session.setModeListener(m -> {
            if (m == TerminalSession.Mode.EDIT) {
                edits.incrementAndGet();
            }
        });
        runner = new Thread(() -> {
            try {
                session.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "typeahead-session");
        runner.start();
    }

    @AfterEach
    void disconnect() throws Exception {
        try {
            type("exit" + "\r");
            runner.join(5000);
        } finally {
            conn.close();
            terminal.close();
        }
    }

    @Test
    void typedAheadTextIsKeptOnceAndNotDuplicated() throws Exception {
        waitForNextEdit(15_000);
        type("sleep 2" + "\r");
        Thread.sleep(500);
        // Still running: this byte goes straight to the remote and the shell
        // buffers it until the next prompt.
        type("e");
        waitForNextEdit(20_000);

        int start = screenLength();
        // The "e" must be sitting in the local buffer exactly once, so typing
        // the rest of the word forms a valid command.
        type("cho MARKER" + "\r");
        waitForNextEdit(20_000);
        String out = AnsiUtil.strip(screenText().substring(start));
        assertTrue(out.contains("MARKER"), "echo should have run: " + out);
        assertFalse(out.contains("not found"), "typed-ahead text was lost or duplicated: " + out);
    }

    @Test
    void typedAheadTextCanBeEditedAway() throws Exception {
        waitForNextEdit(15_000);
        type("sleep 2" + "\r");
        Thread.sleep(500);
        type("X");
        waitForNextEdit(20_000);

        int start = screenLength();
        // Backspace removes it locally; the remote copy has to go as well.
        type("\u007F");
        type("echo MARKER" + "\r");
        waitForNextEdit(20_000);
        String out = AnsiUtil.strip(screenText().substring(start));
        assertTrue(out.contains("MARKER"), "echo should have run: " + out);
        assertFalse(out.contains("not found"), "the remote line buffer still held the typed-ahead text: " + out);
    }

    @Test
    void inputConsumedByTheRunningCommandIsNotReinjected() throws Exception {
        waitForNextEdit(15_000);
        type("read -t 5 -n 1 V; echo got=$V" + "\r");
        Thread.sleep(500);
        // The running read consumes this, so it never reaches the next prompt.
        type("Z");
        waitForNextEdit(20_000);

        int start = screenLength();
        type("echo MARKER" + "\r");
        waitForNextEdit(20_000);
        String out = AnsiUtil.strip(screenText().substring(start));
        assertTrue(out.contains("MARKER"), "echo should have run: " + out);
        assertFalse(out.contains("not found"), "consumed input was wrongly put back on the line: " + out);
    }

    private void type(String s) throws IOException {
        keys.write(s.getBytes(StandardCharsets.UTF_8));
        keys.flush();
    }

    private String screenText() {
        synchronized (screen) {
            return screen.toString(StandardCharsets.UTF_8);
        }
    }

    private int screenLength() {
        return screenText().length();
    }

    private void waitForNextEdit(long timeoutMs) throws InterruptedException {
        int target = edits.get() + 1;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (failure.get() != null) {
                throw new AssertionError("session failed", failure.get());
            }
            if (edits.get() >= target) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("expected edit session #" + target + " screen: "
                + Log.show(screenText().substring(Math.max(0, screenLength() - 300))));
    }
}
