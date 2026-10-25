package dev.lagless;

import org.jline.keymap.KeyMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.PosixSysTerminal;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The interactive session: pumps remote output to the terminal, forwards
 * keystrokes, and switches between RAW pass-through and local EDIT mode.
 *
 * <pre>
 *   RAW  ──(output quiet + last line is the prompt)──▶ EDIT
 *   EDIT ──(enter / ctrl-c / ctrl-d / ctrl-])────────▶ RAW
 * </pre>
 */
public final class TerminalSession {

    public enum Mode { RAW, EDIT }

    private static final char HOTKEY = 0x1D; // ctrl-]
    /** ctrl-u: tells the remote line editor to discard whatever it has buffered. */
    private static final String CLEAR_REMOTE_LINE = String.valueOf((char) 0x15);

    private final Terminal terminal;
    private final SshConnection conn;
    private final Options options;
    private final OutputMonitor monitor = new OutputMonitor();
    private final PromptDetector detector;
    private final EchoFilter echoFilter = new EchoFilter();
    private final MouseModeFilter mouseFilter = new MouseModeFilter();
    private final Object outLock = new Object();
    private final PrintWriter writer;
    private ScreenModel screen;
    private LocalEcho localEcho;
    private RemoteHelper helper;
    private FileTransfer transfer;
    private LineReader lineReader;
    private OutputStream toRemote;
    private Consumer<Mode> modeListener = m -> { };
    private Consumer<String> outputListener = text -> { };
    private Consumer<String> submittedInputListener = text -> { };
    private Consumer<String> inputAudit = message -> { };
    private java.util.function.Predicate<String> commandGuard = line -> true;
    private Runnable disconnectListener = () -> { };

    private volatile Mode mode = Mode.RAW;
    private volatile boolean promptReady;
    private volatile boolean passThrough;
    private final java.util.concurrent.atomic.AtomicBoolean passThroughWake = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean suppressUntilOutput;
    private volatile boolean closed;
    private enum EditOutcome { LINE, INTERRUPT, HOTKEY }

    /**
     * Printable characters forwarded to the remote while a command was running.
     * The remote pty buffers them and the shell's line editor picks them up
     * when the next prompt appears, so lagless has to account for them instead
     * of assuming the remote line buffer is empty.
     */
    private final StringBuilder typeAhead = new StringBuilder();
    private volatile String pendingSeed = "";
    private volatile boolean remoteLineDirty;

    private volatile EditOutcome editOutcome = EditOutcome.LINE;
    private volatile String hotkeyPartial = "";
    /** JLine owns this prompt while readLine is active; remote WINCH repaints are not new output. */
    private volatile String editPrompt = "";
    private final StringBuilder editRepaint = new StringBuilder();
    private long editRepaintAt;
    private volatile long lastKeyNanos = System.nanoTime();
    private long lastHotkeyNanos;
    private long lastTrimNanos = System.nanoTime();
    private Size lastSize;

    public TerminalSession(Terminal terminal, SshConnection conn, Options options) {
        this.terminal = terminal;
        this.conn = conn;
        this.options = options;
        this.detector = new PromptDetector(options.promptRegex);
        this.writer = terminal.writer();
    }

    /** Test hook: observe mode changes. */
    public void setModeListener(Consumer<Mode> l) {
        this.modeListener = l;
    }

    public Mode mode() {
        return mode;
    }

    /** Disable prompt detection and prediction for arbitrary interactive programs.
     * If true is returned, the caller must enqueue Ctrl+] on this terminal's input
     * to release a pending local line. That wake byte is consumed, never forwarded.
     * Any partial local line is handed over without Enter, like the existing hotkey.
     */
    public boolean setPassThrough(boolean enabled) {
        synchronized (outLock) {
            if (passThrough == enabled) return false;
            if (enabled && localEcho != null && localEcho.hasBufferedInput())
                throw new IllegalStateException(I18n.t("终端仍有本地输入或待发送内容，请先发送/清空并等候远端接收，再启用透传。","The terminal still has local input or pending text; send or clear it and wait for the remote side before enabling pass-through."));
            passThrough = enabled;
            if (enabled) promptReady = false;
            if (localEcho != null) {
                // A mode switch is a display change. reset() can flush a
                // queued Enter, which turns opening/closing a view into a
                // remote blank command. Preserve the no-submit behavior used
                // for terminal resizes.
                writer.write(localEcho.resetForResize());
                writer.flush();
                localEcho.setEnabled(options.predict && !options.rawOnly && !enabled);
                localEcho.setLocalLine(options.localLine && !options.rawOnly && !enabled);
            }
            if (enabled && mode == Mode.EDIT) {
                passThroughWake.set(true);
                return true;
            }
            return false;
        }
    }
    public boolean passThrough() { return passThrough; }

    /** Desktop hooks; output is remote-only, never authentication keystrokes. */
    public void setOutputListener(Consumer<String> listener) { outputListener = listener; }
    /** Receives accepted local command lines, not raw keys or hidden prompt input. */
    public void setSubmittedInputListener(Consumer<String> listener) { submittedInputListener = listener == null ? text -> { } : listener; }
    /** Observes only control-key metadata immediately before bytes are written to SSH. */
    public void setInputAudit(Consumer<String> listener) { inputAudit = listener == null ? message -> { } : listener; }
    public void setCommandGuard(java.util.function.Predicate<String> guard) { commandGuard = guard; }
    public void setDisconnectListener(Runnable listener) { disconnectListener = listener; }

    public void run() throws Exception {
        Size size = terminal.getSize();
        if (size.getColumns() <= 0 || size.getRows() <= 0) {
            size = new Size(80, 24);
        }
        lastSize = size;
        screen = new ScreenModel(size.getColumns(), size.getRows());
        localEcho = new LocalEcho(screen);
        localEcho.setEnabled(options.predict && !options.rawOnly && !passThrough);
        localEcho.setLocalLine(options.localLine && !options.rawOnly && !passThrough);
        String term = System.getenv("TERM");
        if (term == null || term.isBlank() || term.equals("dumb")) {
            term = "xterm-256color";
        }
        conn.openShell(term, size.getColumns(), size.getRows(), options.resumeId);
        toRemote = conn.shellOutput();

        if (options.completion || options.remoteHistory) {
            helper = new RemoteHelper(conn);
            helper.start();
        }
        buildLineReader();

        Attributes saved = terminal.enterRawMode();
        Attributes raw = terminal.getAttributes();
        raw.setLocalFlag(Attributes.LocalFlag.ISIG, false);
        raw.setInputFlag(Attributes.InputFlag.IXON, false);
        raw.setInputFlag(Attributes.InputFlag.IXOFF, false);
        terminal.setAttributes(raw);
        terminal.handle(Terminal.Signal.WINCH, s -> checkResize());

        Thread pump = new Thread(this::pumpOutput, "lagless-output");
        pump.setDaemon(true);
        pump.start();
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lagless-ticker");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(this::tick, 25, 25, TimeUnit.MILLISECONDS);
        try {
            mainLoop();
        } finally {
            // The channel can close before the output pump consumes its buffered tail.
            if (!conn.isShellOpen()) {
                try { pump.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            closed = true;
            ticker.shutdownNow();
            if (helper != null) {
                helper.close();
            }
            terminal.setAttributes(saved);
            synchronized (outLock) {
                writer.println();
                writer.flush();
            }
        }
    }

    // ---------------------------------------------------------------- setup

    private void buildLineReader() throws IOException {
        Path histDir = Paths.get(System.getProperty("user.home"), ".lagless", "history");
        Files.createDirectories(histDir);
        String histName = (conn.getUserName() + "@" + conn.getHost()).replaceAll("[^A-Za-z0-9_.@-]", "_");
        if (options.historySuffix != null) histName += "-" + options.historySuffix.replaceAll("[^A-Za-z0-9_-]", "_");
        DefaultParser parser = new DefaultParser();
        parser.setEofOnUnclosedQuote(false);
        parser.setEofOnEscapedNewLine(false);
        LineReaderBuilder b = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("lagless")
                .parser(parser)
                .history(new DefaultHistory())
                .variable(LineReader.HISTORY_FILE, histDir.resolve(histName))
                .variable(LineReader.HISTORY_SIZE, 5000)
                .variable(LineReader.HISTORY_FILE_SIZE, 10000)
                .variable(LineReader.BELL_STYLE, "none")
                .option(LineReader.Option.HISTORY_IGNORE_DUPS, true)
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .option(LineReader.Option.INSERT_TAB, false)
                .option(LineReader.Option.AUTO_GROUP, false)
                .option(LineReader.Option.LIST_PACKED, true);
        if (helper != null && options.completion) {
            b.completer(helper);
        }
        lineReader = b.build();
        lineReader.setVariable(LineReader.HISTORY_FILE, histDir.resolve(histName));
        lineReader.getHistory().attach(lineReader);

        KeyMap<org.jline.reader.Binding> main = lineReader.getKeyMaps().get(LineReader.MAIN);
        // Ctrl-C and the hotkey must end readLine. JLine's send-break only does
        // that when it arrives as a signal, and signals are off so that raw mode
        // can forward ctrl-c as a byte. So: remember the intent, clear the
        // buffer and accept the (now empty) line.
        lineReader.getWidgets().put("lagless-ctrl-c", () -> {
            editOutcome = EditOutcome.INTERRUPT;
            lineReader.getBuffer().clear();
            return lineReader.getBuiltinWidgets().get(LineReader.ACCEPT_LINE).apply();
        });
        main.bind(new Reference("lagless-ctrl-c"), KeyMap.ctrl('C'));
        lineReader.getWidgets().put("lagless-hotkey", () -> {
            if (passThroughWake.getAndSet(false)) {
                // JLine's normal readLine cleanup prints a newline even when
                // ACCEPT_LINE was invoked only to change modes. Keep the
                // current display row in place for this programmatic wake.
                lineReader.setOpt(LineReader.Option.ERASE_LINE_ON_FINISH);
            }
            editOutcome = EditOutcome.HOTKEY;
            hotkeyPartial = lineReader.getBuffer().toString();
            lineReader.getBuffer().clear();
            return lineReader.getBuiltinWidgets().get(LineReader.ACCEPT_LINE).apply();
        });
        main.bind(new Reference("lagless-hotkey"), String.valueOf(HOTKEY));
        // Terminals send arrows either in normal (ESC [ X) or application (ESC O X)
        // mode; JLine only binds the form its terminfo entry lists. Bind both.
        bind(main, LineReader.BACKWARD_CHAR, "\u001b[D", "\u001bOD");
        bind(main, LineReader.FORWARD_CHAR, "\u001b[C", "\u001bOC");
        bind(main, LineReader.UP_LINE_OR_HISTORY, "\u001b[A", "\u001bOA");
        bind(main, LineReader.DOWN_LINE_OR_HISTORY, "\u001b[B", "\u001bOB");
        bind(main, LineReader.BEGINNING_OF_LINE, "\u001b[H", "\u001bOH", "\u001b[1~");
        bind(main, LineReader.END_OF_LINE, "\u001b[F", "\u001bOF", "\u001b[4~");
        bind(main, LineReader.DELETE_CHAR, "\u001b[3~");
        bind(main, LineReader.BACKWARD_WORD, "\u001b[1;5D");
        bind(main, LineReader.FORWARD_WORD, "\u001b[1;5C");

        if (helper != null && options.remoteHistory) {
            Thread t = new Thread(() -> {
                List<String> lines = helper.importHistory();
                if (lines.isEmpty()) {
                    return;
                }
                synchronized (lineReader.getHistory()) {
                    for (String l : lines) {
                        lineReader.getHistory().add(l);
                    }
                }
                Log.d("imported " + lines.size() + " remote history lines");
            }, "lagless-history");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void bind(KeyMap<org.jline.reader.Binding> map, String widget, String... keys) {
        for (String k : keys) {
            map.bind(new Reference(widget), k);
        }
    }

    // ------------------------------------------------------------ output side

    private void pumpOutput() {
        try (Reader in = new InputStreamReader(conn.shellInput(), StandardCharsets.UTF_8)) {
            char[] buf = new char[8192];
            while (!closed) {
                int n = in.read(buf);
                if (n < 0) {
                    break;
                }
                if (n == 0) {
                    continue;
                }
                String chunk = new String(buf, 0, n);
                Log.d("<< " + Log.show(chunk));
                Recorder.remote(chunk);
                outputListener.accept(chunk);
                if (!options.appMouse) {
                    chunk = mouseFilter.feed(chunk);
                    if (mouseFilter.isHolding()) {
                        // A sequence was cut at the packet boundary; the rest
                        // normally follows at once. Do not hold it for long.
                        waitForMore(in, 30);
                        if (!in.ready()) {
                            chunk += mouseFilter.flush();
                        }
                    }
                    if (chunk.isEmpty()) {
                        continue;
                    }
                }
                monitor.feed(chunk);
                suppressUntilOutput = false;
                String visible = echoFilter.feed(chunk);
                show(visible);
            }
        } catch (IOException e) {
            Log.d("output pump ended: " + e);
        } finally {
            closed = true;
            disconnectListener.run();
        }
    }

    private static void waitForMore(Reader in, long millis) throws IOException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (!in.ready() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void show(String text) {
        if (text.isEmpty()) {
            return;
        }
        synchronized (outLock) {
            if (mode == Mode.EDIT) {
                localEcho.observe(text);
                // Bash/readline repaints its prompt in place on a PTY resize:
                // CR, erase-to-end, CR, prompt, with no LF. printAbove() always
                // appends LF, turning every resize into a false history line.
                // Hold split repaint packets until the prompt is complete;
                // only genuine output is printed above the local editor.
                if (editRepaint.length() > 0 || inlineRepaint(text)) {
                    editRepaint.append(text);
                    editRepaintAt = System.nanoTime();
                    if (editRepaint.indexOf("\n") >= 0 || editRepaint.length() > 8192) flushEditRepaint();
                    else if (monitor.currentLinePrompt().equals(editPrompt)) flushEditRepaint();
                } else lineReader.printAbove(text);
            } else {
                // One write: on Windows every write() is its own console call,
                // and the console may paint between two of them.
                String out = localEcho.onOutput(text, System.nanoTime());
                if (!out.isEmpty()) {
                    Recorder.wrote(out);
                    writer.write(out);
                    writer.flush();
                }
                String reply = localEcho.takeOutgoing();
                if (!reply.isEmpty()) {
                    try {
                        send(reply,"local-echo-submit");
                    } catch (IOException e) {
                        Log.d("send failed: " + e);
                    }
                }
            }
        }
    }

    private static boolean inlineRepaint(String text) {
        for (int i = 0; i < text.length(); i++)
            if (text.charAt(i) == '\r' && (i + 1 == text.length() || text.charAt(i + 1) != '\n')) return true;
        return false;
    }

    /** Called with outLock held. A remote prompt repaint has no new line to preserve. */
    private void flushEditRepaint() {
        if (editRepaint.length() == 0) return;
        String repaint = editRepaint.toString();
        editRepaint.setLength(0);
        if (repaint.indexOf('\n') < 0 && monitor.currentLinePrompt().equals(editPrompt)) {
            audit(I18n.t("远端原位重绘当前提示符；本地行编辑器未增加历史行；SSH 输入未改变","Remote redrew the current prompt in place; no history line added to the local line editor; SSH input unchanged"));
            return;
        }
        lineReader.printAbove(repaint);
    }

    private void tick() {
        try {
            synchronized (outLock) {
                if (mode == Mode.EDIT && editRepaint.length() > 0 && System.nanoTime() - editRepaintAt > 300_000_000L)
                    flushEditRepaint();
            }
            String late = echoFilter.poll();
            if (!late.isEmpty()) {
                show(late);
            }
            checkResize();
            trimMemory();
            if (mode == Mode.RAW) {
                synchronized (outLock) {
                    String fix = localEcho.tick(System.nanoTime());
                    if (!fix.isEmpty() && mode == Mode.RAW) {
                        Recorder.wrote(fix);
                        writer.write(fix);
                        writer.flush();
                    }
                }
                sendOutgoing();
            }
            if (options.rawOnly || passThrough || mode != Mode.RAW || promptReady || suppressUntilOutput || monitor.isAltScreen()) {
                return;
            }
            long quiet = monitor.quietMillis();
            long idle = (System.nanoTime() - lastKeyNanos) / 1_000_000L;
            if (quiet < options.quietMs || idle < options.quietMs || echoFilter.isActive()) {
                return;
            }
            String line = monitor.currentLineText();
            // The shell redraws the prompt with any typed-ahead characters
            // already in its buffer, so "host:~$ X" is still a prompt. Only what
            // the remote echoed back counts as pending: anything a running
            // program consumed never reaches the prompt line.
            String seed = echoedTypeAhead(line);
            String candidate = line.substring(0, line.length() - seed.length());
            if (detector.looksLikePrompt(candidate)) {
                pendingSeed = seed;
                Log.d("prompt detected: " + Log.show(candidate) + " [" + detector.describe() + "]"
                        + (seed.isEmpty() ? "" : " pending input: " + Log.show(seed)));
                promptReady = true;
            }
        } catch (Exception e) {
            Log.d("tick error: " + e);
        }
    }

    /**
     * Lets the JVM give unused heap back to the system now and then while the
     * user is idle. Every window is its own JVM, and one started without the
     * launcher's heap cap otherwise keeps whatever it once grew to.
     */
    private void trimMemory() {
        long now = System.nanoTime();
        if (now - lastTrimNanos < 300_000_000_000L || now - lastKeyNanos < 30_000_000_000L) {
            return;
        }
        lastTrimNanos = now;
        System.gc();
    }

    /**
     * Longest tail of the tracked type-ahead that the given line ends with.
     * Empty when nothing we typed is still sitting in the remote line buffer.
     */
    private String echoedTypeAhead(String line) {
        String pending;
        synchronized (typeAhead) {
            pending = typeAhead.toString();
        }
        for (int i = 0; i < pending.length(); i++) {
            String tail = pending.substring(i);
            if (line.length() > tail.length() && line.endsWith(tail)) {
                return tail;
            }
        }
        return "";
    }

    /**
     * Records printable keys sent while in raw mode. Anything else (escape
     * sequences, enter, ctrl keys) makes the remote line buffer unpredictable,
     * so tracking restarts.
     */
    private void noteRawKeys(String keys) {
        synchronized (typeAhead) {
            for (int i = 0; i < keys.length(); i++) {
                char c = keys.charAt(i);
                if (c == 0x7F || c == 0x08) {
                    if (typeAhead.length() > 0) {
                        typeAhead.setLength(typeAhead.length() - 1);
                    }
                } else if (c < 0x20 || c == 0x1B) {
                    typeAhead.setLength(0);
                    return;
                } else if (typeAhead.length() < 256) {
                    typeAhead.append(c);
                } else {
                    typeAhead.setLength(0);
                    return;
                }
            }
        }
    }

    /** Returns the tracked type-ahead and forgets it. */
    private String takeTypeAhead() {
        synchronized (typeAhead) {
            String pending = typeAhead.toString();
            typeAhead.setLength(0);
            return pending;
        }
    }

    private void checkResize() {
        Size s = terminal.getSize();
        if (s.getColumns() > 0 && s.getRows() > 0 && !s.equals(lastSize)) {
            lastSize = s;
            synchronized (outLock) {
                // The terminal may reflow on resize; nothing predicted survives that reliably.
                String flushed = localEcho.resetForResize();
                if (!flushed.isEmpty()) {
                    Recorder.wrote(flushed);
                    writer.write(flushed);
                    writer.flush();
                }
                localEcho.resize(s.getColumns(), s.getRows());
            }
            conn.resize(s.getColumns(), s.getRows());
            audit(I18n.t("终端尺寸变更；列=","Terminal resized; cols=") + s.getColumns() + I18n.t("；行=","; rows=") + s.getRows() + I18n.t("；仅通知远端 PTY，无 SSH 回车","; remote PTY notified only, no SSH Enter"));
        }
    }

    // ------------------------------------------------------------- input side

    private void mainLoop() throws IOException {
        NonBlockingReader reader = terminal.reader();
        StringBuilder pending = new StringBuilder();
        while (!closed && conn.isShellOpen()) {
            if (!passThrough && promptReady && mode == Mode.RAW) {
                editOneLine();
                continue;
            }
            int c = reader.read(30);
            if (c == NonBlockingReader.EOF) {
                break;
            }
            if (c == NonBlockingReader.READ_EXPIRED) {
                continue;
            }
            lastKeyNanos = System.nanoTime();
            pending.setLength(0);
            pending.append((char) c);
            // Drain whatever arrived together (escape sequences, pastes).
            int more;
            while (moreInputQueued() && (more = reader.read(1)) >= 0) {
                pending.append((char) more);
                if (pending.length() > 4096) {
                    break;
                }
            }
            handleRawInput(pending.toString());
        }
    }

    /**
     * Whether another read would find input already waiting. On a POSIX tty
     * JLine reads with VTIME=1, so a read that finds nothing blocks in the
     * kernel for 100 ms whatever timeout is asked for; draining blindly after
     * every key would delay each keystroke by that much. Asking the kernel how
     * many bytes are queued avoids it. Other terminals (Windows console, the
     * tests' stream terminals) honour short timeouts, so just try the read.
     */
    private boolean moreInputQueued() throws IOException {
        if (terminal instanceof PosixSysTerminal) {
            return System.in.available() > 0;
        }
        return true;
    }

    private void handleRawInput(String typed) throws IOException {
        if (passThroughWake.get() && typed.indexOf(HOTKEY) >= 0 && passThroughWake.compareAndSet(true, false)) {
            int wake = typed.indexOf(HOTKEY);
            typed = typed.substring(0, wake) + typed.substring(wake + 1);
        }
        Recorder.key(typed);
        if (options.rawOnly || passThrough) {
            send(typed,"terminal-keyboard");
            return;
        }
        // Local echo decides what goes out: normally the keys as typed, while
        // a line is edited locally nothing until enter.
        String keys = echoLocally(typed);
        if (keys.isEmpty()) {
            return;
        }
        int idx = keys.indexOf(HOTKEY);
        if (idx < 0) {
            noteRawKeys(keys);
            send(keys,"terminal-keyboard");
            return;
        }
        if (idx > 0) {
            noteRawKeys(keys.substring(0, idx));
            send(keys.substring(0, idx),"terminal-keyboard");
        }
        long now = System.nanoTime();
        boolean doublePress = now - lastHotkeyNanos < 600_000_000L;
        lastHotkeyNanos = now;
        if (monitor.isAltScreen()) {
            send(String.valueOf(HOTKEY),"terminal-hotkey");
            return;
        }
        String line = monitor.currentLineText();
        if (doublePress) {
            detector.forget();
            Log.d("hotkey: prompt forgotten");
        }
        detector.learn(line);
        Log.d("hotkey: forcing edit mode on " + Log.show(line) + " [" + detector.describe() + "]");
        promptReady = true;
        if (idx + 1 < keys.length()) {
            noteRawKeys(keys.substring(idx + 1));
            send(keys.substring(idx + 1),"terminal-keyboard");
        }
    }

    /**
     * Draws local echo for keys going to a full-screen program and returns
     * what to send for them; see {@link LocalEcho}.
     */
    private String echoLocally(String keys) {
        synchronized (outLock) {
            if (mode != Mode.RAW) {
                return keys;
            }
            String drawn = localEcho.onKeys(keys, System.nanoTime());
            if (!drawn.isEmpty()) {
                Recorder.wrote(drawn);
                writer.write(drawn);
                writer.flush();
            }
            return localEcho.takeOutgoing();
        }
    }

    /**
     * Sends what local echo decided to send on its own: the enter after a
     * locally edited line, once the remote shows the line.
     */
    private void sendOutgoing() {
        String s;
        synchronized (outLock) {
            s = localEcho.takeOutgoing();
        }
        if (s.isEmpty()) {
            return;
        }
        try {
            send(s,"local-echo-submit");
        } catch (IOException e) {
            Log.d("send failed: " + e);
        }
    }

    private void editOneLine() throws IOException {
        // Claim edit mode before clearing the flag. The other order leaves a
        // window where the ticker sees raw mode with no pending prompt and
        // detects the very same prompt a second time, which would open another
        // edit as soon as this one ends, on top of the running command.
        synchronized (outLock) {
            if (passThrough) { promptReady = false; return; }
            mode = Mode.EDIT;
            writer.write(localEcho.reset());
            writer.write("\r\u001b[K");
            writer.flush();
        }
        sendOutgoing();
        promptReady = false;
        editOutcome = EditOutcome.LINE;
        hotkeyPartial = "";
        // Characters typed while the last command ran may still sit in the
        // remote line buffer. Whatever the shell echoed back after the prompt
        // is certainly there: take it out of the prompt and put it in the local
        // buffer, where it stays visible and can be edited away.
        //
        // Anything typed but not echoed after the prompt is uncertain: a
        // running program may have consumed it, or the shell may simply not
        // have redrawn it where lagless could see it. Either way the remote
        // buffer gets cleared before the next line is sent, so a line can never
        // land on top of leftover text. Input that was never shown at the
        // prompt is dropped rather than silently prepended to the next command.
        String seed = pendingSeed;
        pendingSeed = "";
        boolean typedDuringCommand = !takeTypeAhead().isEmpty();
        remoteLineDirty = !seed.isEmpty() || typedDuringCommand;
        if (remoteLineDirty && seed.isEmpty()) {
            Log.d("typed during the last command but not echoed at the prompt; "
                    + "the remote line buffer will be cleared");
        }
        String prompt = monitor.currentLinePrompt();
        if (!seed.isEmpty() && prompt.endsWith(seed)) {
            prompt = prompt.substring(0, prompt.length() - seed.length());
        }
        editPrompt = prompt;
        modeListener.accept(Mode.EDIT);
        if (helper != null) {
            helper.onPromptShown();
        }
        String line;
        try {
            line = lineReader.readLine(prompt, null, seed.isEmpty() ? null : seed);
        } catch (UserInterruptException e) {
            leaveEdit();
            if (closed) return;
            suppressUntilOutput = true;
            remoteLineDirty = false;
            send("\u0003");
            return;
        } catch (EndOfFileException e) {
            leaveEdit();
            if (closed) return;
            suppressUntilOutput = true;
            send("\u0004");
            return;
        } finally {
            lineReader.unsetOpt(LineReader.Option.ERASE_LINE_ON_FINISH);
        }
        leaveEdit();
        if (closed || !conn.isShellOpen()) return;
        if (editOutcome == EditOutcome.INTERRUPT) {
            Log.d(">> ctrl-c");
            suppressUntilOutput = true;
            // ctrl-c makes the remote drop its line buffer by itself.
            remoteLineDirty = false;
            send("\u0003");
            return;
        }
        if (editOutcome == EditOutcome.HOTKEY) {
            String partial = hotkeyPartial;
            Log.d("hotkey: handing partial line to remote: " + Log.show(partial));
            synchronized (outLock) {
                writer.write(prompt);
                writer.flush();
            }
            monitor.resetLine();
            suppressUntilOutput = true;
            send(takeRemoteLinePrefix() + partial);
            return;
        }
        if (line.startsWith(":") && handleLocalCommand(line)) {
            Log.d(">> local command: " + Log.show(line));
            // Nothing was sent; the remote prompt is still waiting. Re-enter
            // editing on the next loop iteration.
            promptReady = true;
            return;
        }
        if (!commandGuard.test(line)) {
            promptReady = true;
            return;
        }
        echoFilter.expect(line);
        suppressUntilOutput = true;
        Log.d(">> line: " + Log.show(line));
        send(takeRemoteLinePrefix() + line + "\r","line-editor-submit");
        submittedInputListener.accept(line);
    }

    /**
     * Commands handled locally instead of being sent to the shell. Returns
     * false for unknown ":" lines so they still reach the remote (vim users
     * type ":q" at the wrong prompt often enough).
     */
    private boolean handleLocalCommand(String line) {
        String[] parts = line.trim().split("\\s+", 3);
        String cmd = parts[0];
        String remoteCwd = helper != null ? helper.cwd() : null;
        switch (cmd) {
            case ":put" -> {
                if (parts.length < 2) {
                    show("usage: :put LOCAL [REMOTE]" + System.lineSeparator());
                    return true;
                }
                fileTransfer().put(parts[1], parts.length > 2 ? parts[2] : null, remoteCwd);
                return true;
            }
            case ":get" -> {
                if (parts.length < 2) {
                    show("usage: :get REMOTE [LOCAL]" + System.lineSeparator());
                    return true;
                }
                fileTransfer().get(parts[1], parts.length > 2 ? parts[2] : null, remoteCwd);
                return true;
            }
            case ":help" -> {
                show(Options.usage() + System.lineSeparator());
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private FileTransfer fileTransfer() {
        if (transfer == null) {
            transfer = new FileTransfer(conn, msg -> {
                Log.d("transfer: " + msg);
                show(msg + System.lineSeparator());
            });
        }
        return transfer;
    }

    /**
     * Returns the ctrl-u that has to precede the next thing we send when the
     * remote line buffer is known to hold typed-ahead characters, so the line
     * lagless sends replaces them instead of being appended to them.
     */
    private String takeRemoteLinePrefix() {
        if (!remoteLineDirty) {
            return "";
        }
        remoteLineDirty = false;
        Log.d("clearing the remote line buffer before sending");
        return CLEAR_REMOTE_LINE;
    }

    private void leaveEdit() {
        synchronized (outLock) {
            if (editRepaint.length() > 0) flushEditRepaint();
            editPrompt = "";
            mode = Mode.RAW;
        }
        lastKeyNanos = System.nanoTime();
        modeListener.accept(Mode.RAW);
    }

    /** Keys come from the input loop, the enter of a local line from the output or ticker thread. */
    private void send(String s) throws IOException {
        send(s,"terminal-input");
    }

    private void send(String s,String origin) throws IOException {
        if (s.isEmpty()) {
            return;
        }
        if(s.indexOf('\r')>=0||s.indexOf('\n')>=0)audit(I18n.t("SSH 写入；来源=","SSH write; source=")+origin+I18n.t("；字符数=","; chars=")+s.length()+I18n.t("；CR=","; CR=")+s.chars().filter(c->c=='\r').count()+I18n.t("；LF=","; LF=")+s.chars().filter(c->c=='\n').count()+I18n.t("；仅换行=","; LF only=")+s.chars().allMatch(c->c=='\r'||c=='\n'));
        synchronized (toRemote) {
            toRemote.write(s.getBytes(StandardCharsets.UTF_8));
            toRemote.flush();
        }
    }

    private void audit(String message) {
        try { inputAudit.accept(message); } catch (RuntimeException ignored) { }
    }
}
