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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the client through a virtual terminal against a real sshd.
 * Enabled only when LAGLESS_TEST_HOST is set, e.g.
 * LAGLESS_TEST_HOST=localhost LAGLESS_TEST_PORT=2222 LAGLESS_TEST_USER=ubuntu LAGLESS_TEST_KEY=/path/key
 */
@EnabledIfEnvironmentVariable(named = "LAGLESS_TEST_HOST", matches = ".+")
class EndToEndTest {

    private static final String ESC = "\u001b";

    private PipedOutputStream keys;
    private final ByteArrayOutputStream screen = new ByteArrayOutputStream();
    private Terminal terminal;
    private SshConnection conn;
    private TerminalSession session;
    private Thread runner;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicReference<TerminalSession.Mode> mode = new AtomicReference<>(TerminalSession.Mode.RAW);
    private final AtomicInteger edits = new AtomicInteger();

    @BeforeEach
    void connect() throws Exception {
        Log.enable(Paths.get("target", "e2e-debug.log"));
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
        terminal = TerminalBuilder.builder()
                .system(false)
                .streams(in, out)
                .type("xterm-256color")
                .encoding(StandardCharsets.UTF_8)
                .size(new Size(120, 40))
                .build();

        Options o = new Options();
        o.host = System.getenv("LAGLESS_TEST_HOST");
        o.port = Integer.parseInt(System.getenv().getOrDefault("LAGLESS_TEST_PORT", "22"));
        o.user = System.getenv("LAGLESS_TEST_USER");
        o.identity = System.getenv("LAGLESS_TEST_KEY");
        o.debug = true;
        conn = SshConnection.connect(o, SshConnection.nonInteractive(q -> true));
        session = new TerminalSession(terminal, conn, o);
        session.setModeListener(m -> {
            mode.set(m);
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
        }, "e2e-session");
        runner.start();
    }

    @AfterEach
    void disconnect() throws Exception {
        try {
            type("exit\r");
            runner.join(5000);
        } finally {
            conn.close();
            terminal.close();
        }
        if (failure.get() != null) {
            throw new AssertionError("session failed", failure.get());
        }
    }

    @Test
    void localEditingHistoryVimAndCompletion() throws Exception {
        waitForNextEdit(10_000);
        String prompt = AnsiUtil.strip(screenText());
        assertTrue(prompt.contains("$ "), "prompt should be on screen: " + prompt);

        // 1. Edit inside the line locally: "echo hello", cursor left x5, insert X.
        type("echo hello");
        type(ESC + "[D" + ESC + "[D" + ESC + "[D" + ESC + "[D" + ESC + "[D");
        type("X");
        int before = screenLength();
        type("\r");
        waitForNextEdit(10_000);
        String after = AnsiUtil.strip(screenText().substring(before));
        assertTrue(after.contains("Xhello"), "command output missing: " + after);
        assertFalse(after.contains("echo Xhello"), "remote echo was not swallowed: " + after);

        // 2. History: up arrow recalls the previous line locally.
        int histStart = screenLength();
        type(ESC + "[A");
        waitFor(() -> AnsiUtil.strip(screenText().substring(histStart)).contains("echo Xhello"), 2_000,
                "history recall should redraw the previous command");
        assertEquals(TerminalSession.Mode.EDIT, mode.get());
        type("\u0003"); // ctrl-c: drop the line, remote prints a fresh prompt
        waitForNextEdit(10_000);
        // The dropped line must not have left an empty history entry behind.
        int hist2 = screenLength();
        type(ESC + "[A");
        waitFor(() -> AnsiUtil.strip(screenText().substring(hist2)).contains("echo Xhello"), 2_000,
                "history after ctrl-c should still recall the last real command");
        type("\u0003");
        waitForNextEdit(10_000);

        // 3. Full-screen program: vim switches to the alternate screen, we must stay RAW.
        int vimStart = screenLength();
        type("vim\r");
        waitForMode(TerminalSession.Mode.RAW, 2_000);
        waitFor(() -> screenText().substring(vimStart).contains(ESC + "[?1049h"), 25_000, "vim should enter the alternate screen");
        Thread.sleep(800);
        assertEquals(TerminalSession.Mode.RAW, mode.get(), "must not start local editing inside vim");
        type(ESC + ":q\r");
        waitForMode(TerminalSession.Mode.EDIT, 10_000);

        // 4. Async completion: first tab requests the listing, second tab applies it.
        int tabStart = screenLength();
        type("ls /ho");
        type("\t");
        Thread.sleep(1500);
        type("\t");
        waitFor(() -> AnsiUtil.strip(screenText().substring(tabStart)).contains("ls /home/"), 3_000,
                "tab completion should complete /ho to /home/");
        type("\u0003");
        waitForNextEdit(10_000);

        // 5. :put / :get over sftp, handled locally, prompt stays editable.
        java.nio.file.Path local = java.nio.file.Files.createTempFile("lagless-e2e", ".txt");
        java.nio.file.Files.writeString(local, "sftp round trip 42");
        int putStart = screenLength();
        type(":put " + local + " /tmp/lagless-e2e.txt\r");
        waitFor(() -> AnsiUtil.strip(screenText().substring(putStart)).contains("uploaded /tmp/lagless-e2e.txt"), 10_000,
                "upload should report completion; screen: " + Log.show(screenText().substring(putStart)));
        assertEquals(TerminalSession.Mode.EDIT, mode.get(), "still at the prompt after :put");
        int catStart = screenLength();
        type("cat /tmp/lagless-e2e.txt\r");
        waitForNextEdit(10_000);
        assertTrue(AnsiUtil.strip(screenText().substring(catStart)).contains("sftp round trip 42"), "uploaded content");
        java.nio.file.Path back = local.resolveSibling(local.getFileName() + ".back");
        int getStart = screenLength();
        type(":get /tmp/lagless-e2e.txt " + back + "\r");
        waitFor(() -> AnsiUtil.strip(screenText().substring(getStart)).contains("downloaded"), 10_000,
                "download should report completion");
        assertEquals("sftp round trip 42", java.nio.file.Files.readString(back));
        java.nio.file.Files.deleteIfExists(local);
        java.nio.file.Files.deleteIfExists(back);
        type("\u0003");
        waitForNextEdit(10_000);
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
        synchronized (screen) {
            return screen.toString(StandardCharsets.UTF_8).length();
        }
    }

    private void waitForNextEdit(long timeoutMs) throws InterruptedException {
        int target = edits.get() + 1;
        waitFor(() -> edits.get() >= target, timeoutMs, "expected edit session #" + target
                + " screen: " + Log.show(screenText().substring(Math.max(0, screenLength() - 300))));
    }

    private void waitForMode(TerminalSession.Mode m, long timeoutMs) throws InterruptedException {
        waitFor(() -> mode.get() == m, timeoutMs, "expected mode " + m + " but was " + mode.get()
                + "\nscreen: " + Log.show(screenText().substring(Math.max(0, screenLength() - 300))));
    }

    private interface Condition {
        boolean ok();
    }

    private void waitFor(Condition c, long timeoutMs, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (failure.get() != null) {
                throw new AssertionError("session failed", failure.get());
            }
            if (c.ok()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError(message);
    }
}
