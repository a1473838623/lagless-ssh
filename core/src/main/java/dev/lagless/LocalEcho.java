package dev.lagless;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Predictive local echo for full-screen programs, in the spirit of mosh.
 *
 * <p>Inside the alternate screen (Claude Code, vim insert mode, nano, a shell
 * inside tmux) every key is passed through and normally only appears once the
 * remote has echoed it, a full round trip later. Here what the user typed is
 * drawn at once and stays the truth on screen until the remote has caught up.
 *
 * <p>Typed keys are kept as edits of the line the cursor is on: a character is
 * inserted at the cursor and pushes the rest of the line right, a backspace
 * removes the character before the cursor and pulls the rest left, as the
 * input box of Claude Code, vim and shells do. The line as it should look is
 * the base line (as the remote last drew it) with every pending edit applied.
 *
 * <p>The remote handles keys in order, so after each remote update the number
 * of keys it has handled is found by asking which prefix of the pending edits
 * explains what it drew: the line after the first k edits, with the cursor
 * after the k-th, must match the remote's line and cursor. Those k edits are
 * settled and the rest stay on screen. Intermediate states (typing, then
 * backspacing over keys the remote has not echoed yet) are explained the same
 * way, so nothing flickers while the echoes catch up.
 *
 * <p>Three screen models are kept: {@code screen}, everything the remote sent,
 * as soon as it arrived; {@code written}, the remote output handed to the
 * terminal so far (it lags while a redraw is held back); and {@code shown},
 * exactly what the terminal displays. After every key and every remote update
 * only the cells where {@code shown} differs from the intended display are
 * drawn.
 *
 * <p>Guessing wrong must never leave garbage behind, so:
 * <ul>
 *   <li>Predictions are only drawn once the program has proven it echoes at the
 *       cursor: in each epoch keys are sent without drawing anything until the
 *       last one typed shows up just left of the remote cursor. Any key that is
 *       not plain text (enter, arrows, escape, ctrl keys, a paste) ends the
 *       epoch; what was typed before it stays on screen until a remote update
 *       it cannot explain.</li>
 *   <li>Predictions are taken back when the remote's cursor says it handled
 *       every key but its line differs, or when the oldest key has gone
 *       unexplained for far too long. A slow remote is not a wrong one.</li>
 *   <li>While predictions are on screen, remote output is held until the
 *       program has finished its redraw (cursor visible again), for at most
 *       {@link #HOLD_NANOS}, then written together with the redrawn display as
 *       one synchronized update with the cursor hidden.</li>
 * </ul>
 *
 * <p>Methods return the bytes to write to the local terminal; the caller holds
 * the output lock and must write every returned string, in order.
 */
public final class LocalEcho {

    /** Longest run of text in one read that is still treated as typing. */
    private static final int MAX_TYPED_BURST = 32;
    private static final int MAX_PENDING = 128;
    private static final long MIN_TIMEOUT_NANOS = 2_000_000_000L;
    private static final long MAX_TIMEOUT_NANOS = 5_000_000_000L;
    /** Longest time remote output is held back to finish a redraw. */
    static final long HOLD_NANOS = 50_000_000L;
    private static final int HOLD_LIMIT = 64 * 1024;
    private static final int MAX_TYPED_CELLS = 4096;
    /**
     * Columns from the right edge where a program may wrap its input onto the
     * next row (Claude Code wraps before the last column). Text is not pushed
     * or pulled there by prediction when the row below holds text.
     */
    private static final int WRAP_MARGIN = 12;
    /** DEC synchronized output: the terminal shows the update only once it is complete. */
    private static final String SYNC_ON = "\u001b[?2026h";
    private static final String SYNC_OFF = "\u001b[?2026l";
    private static final String HIDE_CURSOR = "\u001b[?25l";
    private static final String SHOW_CURSOR = "\u001b[?25h";
    private static final String SGR_RESET = "\u001b[0m";
    /** Kitty keyboard protocol report for a key with no modifier or only shift. */
    private static final Pattern KITTY_KEY = Pattern.compile("\u001b\\[(\\d{1,7})(?:;[12])?u");

    /** One typed key as an edit of the line. */
    private static final class Edit {
        final boolean erase;
        final int col;
        /** Code point inserted; unused for a backspace; {@link #MOVE} for a cursor move to {@code col}. */
        final int cp;
        final int width;
        final long sentNanos;
        /** Typed before a key that ended the epoch. */
        boolean sealed;

        static final int MOVE = -1;

        Edit(boolean erase, int col, int cp, int width, long sentNanos) {
            this.erase = erase;
            this.col = col;
            this.cp = cp;
            this.width = width;
            this.sentNanos = sentNanos;
        }
    }

    /** One row's content, the pens it is drawn in, which cells the user typed, and the cursor column. */
    private static final class Line {
        final int[] cells;
        final String[] pens;
        final boolean[] typed;
        int cursor;

        Line(int[] cells, String[] pens, boolean[] typed, int cursor) {
            this.cells = cells;
            this.pens = pens;
            this.typed = typed;
            this.cursor = cursor;
        }

        Line copy() {
            return new Line(cells.clone(), pens.clone(), typed.clone(), cursor);
        }

        void apply(Edit e) {
            int n = cells.length;
            if (e.cp == Edit.MOVE) {
                cursor = Math.max(0, Math.min(e.col, n));
                return;
            }
            // After the window shrinks, an edit made on the wider row may lie past the new
            // edge; drawing it there used to throw and end the program. Clip it instead.
            if (e.col < 0 || e.col >= n || !e.erase && e.col + e.width > n) {
                cursor = Math.max(0, Math.min(e.col, n));
                return;
            }
            if (e.erase) {
                int w = Math.min(e.width, n - e.col);
                System.arraycopy(cells, e.col + w, cells, e.col, n - e.col - w);
                System.arraycopy(pens, e.col + w, pens, e.col, n - e.col - w);
                System.arraycopy(typed, e.col + w, typed, e.col, n - e.col - w);
                for (int c = n - w; c < n; c++) {
                    cells[c] = ' ';
                    pens[c] = "";
                    typed[c] = false;
                }
                cursor = e.col;
                return;
            }
            String pen = e.col > 0 ? pens[e.col - 1] : pens[e.col];
            System.arraycopy(cells, e.col, cells, e.col + e.width, n - e.col - e.width);
            System.arraycopy(pens, e.col, pens, e.col + e.width, n - e.col - e.width);
            System.arraycopy(typed, e.col, typed, e.col + e.width, n - e.col - e.width);
            cells[e.col] = e.cp;
            pens[e.col] = pen;
            typed[e.col] = true;
            if (e.width == 2) {
                cells[e.col + 1] = ScreenModel.WIDE_TAIL;
                pens[e.col + 1] = pen;
                typed[e.col + 1] = true;
            }
            cursor = e.col + e.width;
        }
    }

    private final ScreenModel screen;
    private final ScreenModel written;
    private final ScreenModel shown;
    /** Keys drawn locally that the remote has not handled yet, oldest first. */
    private final List<Edit> pending = new ArrayList<>();
    /** Row the pending edits apply to, -1 when there are none. */
    private int baseRow = -1;
    /** That row as the remote last drew it, before the pending edits. */
    private Line base;
    /** Rows whose display may differ from what the terminal shows. */
    private final TreeSet<Integer> dirtyRows = new TreeSet<>();
    /**
     * Cells the user typed, with the character typed there. A predicted
     * backspace may only erase such a cell, and only while it still holds that
     * character: the program's own prompt is never ours to rub out.
     */
    private final Map<Integer, Integer> typedCells = new HashMap<>();
    private boolean confident;
    private long srttNanos = -1;
    /** Fastest round trip seen: no echo can come back sooner. */
    private long minRttNanos = -1;
    private boolean enabled = true;
    /**
     * Keys typed while not yet confident. Their position is not guessed: the
     * program may have swallowed earlier keys (vim's "i"), so they count as
     * echoed once the last one stands just left of the remote cursor.
     */
    private final List<Integer> probe = new ArrayList<>();
    /** When each probe key was sent. */
    private final List<Long> probeSent = new ArrayList<>();
    /**
     * The last complete remote frame (cursor shown, no sequence cut): whether
     * keys could be predicted there, where its cursor was, what its cursor row
     * held, and whether that was an empty input prompt. A redraw cut short by
     * a lost packet leaves the cursor hidden somewhere else for as long as the
     * link stalls; typing still lands where this frame left the cursor.
     */
    private boolean stableOk;
    private int stableRow;
    private int stableCol;
    private int[] stableCells = new int[0];
    private boolean stableEmptyPrompt;
    /** When remote output last arrived, and when it resumed after the link went quiet. */
    private long lastOutputNanos;
    private long resumedNanos;
    /** lagless showed the cursor to draw a local line while the remote had it hidden. */
    private boolean cursorShownByUs;
    /** Longest a key is waited for regardless of what the link does. */
    private static final long MAX_WAIT_NANOS = 30_000_000_000L;
    /** Bytes to send to the remote, collected by {@link #takeOutgoing}. */
    private final StringBuilder outgoing = new StringBuilder();
    /**
     * Local line: at the empty input prompt of a line-input program (Claude
     * Code's "❯ ") the line is edited here and only sent on enter, like the
     * shell prompt in lagless's own line editor. Nothing about it depends on
     * the remote while typing.
     */
    private boolean lineEnabled = true;
    private boolean lineActive;
    /** The prompt is on screen; false while the program shows something else (a question). */
    private boolean lineShown;
    private int lineRow;
    private int lineCol;
    private final List<Integer> lineText = new ArrayList<>();
    private int linePos;
    /** A line was sent; its enter follows once the remote shows it. */
    private boolean submitting;
    private long submitSince;
    private final List<Integer> submitText = new ArrayList<>();
    /** Lines entered while an earlier one still waits for its enter. */
    private final List<List<Integer>> queuedSubmits = new ArrayList<>();
    /** The enter went; the remote's input still shows the sent text until it takes it. */
    private boolean awaitingClear;
    private long awaitingSince;
    /** Keys typed while a submit waits for its echo, sent after the enter. */
    private final StringBuilder afterSubmit = new StringBuilder();
    /** Remote output fed to {@code screen} but not written yet. */
    private final StringBuilder held = new StringBuilder();
    private long heldSince;
    private boolean heldForPredictions;

    public LocalEcho(ScreenModel screen) {
        this.screen = screen;
        this.written = new ScreenModel(screen.cols(), screen.rows());
        this.shown = new ScreenModel(screen.cols(), screen.rows());
        // Load the edit classes now: loading them on the first typed key
        // costs that key milliseconds on a slow machine.
        new Line(new int[2], new String[] {"", ""}, new boolean[2], 0).apply(new Edit(false, 0, 'x', 1, 0));
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Whether the empty prompt of Claude Code and the like is edited locally. */
    public void setLocalLine(boolean on) {
        this.lineEnabled = on;
    }

    /** For tests: whether a line is being edited locally. */
    boolean isEditingLine() {
        return lineActive;
    }

    /**
     * What to send to the remote for the keys and time that went by. Normally
     * the keys themselves; while a line is edited locally, nothing until enter.
     */
    public String takeOutgoing() {
        String s = outgoing.toString();
        outgoing.setLength(0);
        return s;
    }

    /** Unsent local text must survive an explicit change to transparent input. */
    boolean hasBufferedInput() {
        return lineActive || submitting || !queuedSubmits.isEmpty() || afterSubmit.length() > 0;
    }

    /** Smoothed round trip measured from confirmed echoes, -1 before the first one. */
    public long roundTripMillis() {
        return srttNanos < 0 ? -1 : srttNanos / 1_000_000L;
    }

    public boolean isConfident() {
        return confident;
    }

    public int pendingCount() {
        return pending.size();
    }

    /** For tests: whether remote output is being held back. */
    boolean isHolding() {
        return held.length() > 0;
    }

    // ---------------------------------------------------------------- keys

    /**
     * Called with keys about to be sent to the remote. Returns what to draw
     * locally for them, possibly empty.
     */
    public String onKeys(String keys, long now) {
        if (lineActive) {
            return lineKeys(tokens(keys), 0, now);
        }
        if (submitting && afterSubmit.length() == 0 && lineEnabled) {
            List<String> t = tokens(keys);
            if (!t.isEmpty() && printable(t.get(0)) >= 0) {
                // A line was entered and its enter is still on the way: the
                // next one is typed in the same place meanwhile.
                lineActive = true;
                lineShown = true;
                return lineKeys(t, 0, now);
            }
        }
        if (submitting || afterSubmit.length() > 0) {
            afterSubmit.append(keys);
            return "";
        }
        if (lineEnabled && pending.isEmpty() && probe.isEmpty() && keysPredictable()) {
            List<String> t = tokens(keys);
            ScreenModel m = current();
            boolean empty = eligible(m) ? atEmptyPrompt(m) : stableEmptyPrompt;
            if (!t.isEmpty() && printable(t.get(0)) >= 0 && empty) {
                lineActive = true;
                lineShown = true;
                lineRow = cursorRow();
                lineCol = cursorCol();
                lineText.clear();
                linePos = 0;
                return lineKeys(t, 0, now);
            }
        }
        outgoing.append(keys);
        return predictKeys(keys, now);
    }

    /** Echo prediction for keys that are sent as they are typed. */
    private String predictKeys(String keys, long now) {
        if (!enabled || !keysPredictable()) {
            endEpoch();
            return "";
        }
        int[] cps = decodeKeys(keys);
        if (cps == null || cps.length > MAX_TYPED_BURST) {
            endEpoch();
            return "";
        }
        for (int cp : cps) {
            boolean ok;
            if (cp == 0x7F || cp == 0x08) {
                ok = predictBackspace(now);
            } else {
                int w = CharWidth.of(cp);
                ok = cp >= 0x20 && w >= 1 && predictChar(cp, w, now);
            }
            if (!ok) {
                endEpoch();
                break;
            }
        }
        return render();
    }

    private boolean predictChar(int cp, int w, long now) {
        if (!confident) {
            if (probe.size() >= MAX_PENDING) {
                return false;
            }
            probe.add(cp);
            probeSent.add(now);
            return true;
        }
        if (pending.size() >= MAX_PENDING) {
            return false;
        }
        startBase();
        Line line = display();
        int c = line.cursor;
        int cols = line.cells.length;
        // Inserting pushes the rest of the line right. Near the edge the
        // program may wrap, scroll or refuse; do not guess.
        if (c + w >= cols || lastUsed(line.cells) + w >= cols - 1) {
            return false;
        }
        // The row may be the first of a wrapped input: what crosses its wrap
        // point continues on the next row, which no row edit can show.
        if (lastUsed(line.cells) + w >= cols - WRAP_MARGIN && rowBelowHasText(baseRow)) {
            return false;
        }
        pending.add(new Edit(false, c, cp, w, now));
        dirtyRows.add(baseRow);
        return true;
    }

    private boolean predictBackspace(long now) {
        if (!confident) {
            // Still probing: a backspace just takes back the last probe key.
            if (probe.isEmpty()) {
                return false;
            }
            probe.remove(probe.size() - 1);
            probeSent.remove(probeSent.size() - 1);
            return true;
        }
        if (pending.size() >= MAX_PENDING) {
            return false;
        }
        startBase();
        Line line = display();
        int c = line.cursor;
        int w = c >= 2 && line.cells[c - 1] == ScreenModel.WIDE_TAIL ? 2 : 1;
        int col = c - w;
        // Only what the user typed may be rubbed out, never the program's prompt.
        if (col < 0 || !line.typed[col]) {
            if (pending.isEmpty()) {
                baseRow = -1;
            }
            return false;
        }
        // Erasing inside a full row of a wrapped input pulls text up from the next row.
        if (c <= lastUsed(line.cells) && lastUsed(line.cells) >= line.cells.length - WRAP_MARGIN && rowBelowHasText(baseRow)) {
            if (pending.isEmpty()) {
                baseRow = -1;
            }
            return false;
        }
        pending.add(new Edit(true, col, ' ', w, now));
        dirtyRows.add(baseRow);
        return true;
    }

    // ---------------------------------------------------------------- local line

    /** Handles keys while a line is edited locally, from token {@code from} on. */
    private String lineKeys(List<String> t, int from, long now) {
        for (int i = from; i < t.size(); i++) {
            String k = t.get(i);
            if (!lineShown) {
                // The program is asking something else: keys go to it.
                outgoing.append(k);
                continue;
            }
            int cp = printable(k);
            if (cp >= 0) {
                int w = Math.max(1, CharWidth.of(cp));
                if (lineCol + lineWidth(lineText.size()) + w >= written.cols() - 1) {
                    // Longer than the row: the program would wrap it. Hand
                    // over what there is and let it take the rest.
                    return passOn(t, i, now);
                }
                lineText.add(linePos++, cp);
            } else if (k.equals("\u007f") || k.equals("\b")) {
                if (lineText.isEmpty()) {
                    endLine();
                } else if (linePos > 0) {
                    lineText.remove(--linePos);
                }
            } else if (k.equals("\u001b[3~")) {
                if (linePos < lineText.size()) {
                    lineText.remove(linePos);
                }
            } else if (k.equals("\u001b[D") || k.equals("\u001bOD") || k.equals("\u0002")) {
                linePos = Math.max(0, linePos - 1);
            } else if (k.equals("\u001b[C") || k.equals("\u001bOC") || k.equals("\u0006")) {
                linePos = Math.min(lineText.size(), linePos + 1);
            } else if (k.equals("\u001b[H") || k.equals("\u001bOH") || k.equals("\u001b[1~") || k.equals("\u0001")) {
                linePos = 0;
            } else if (k.equals("\u001b[F") || k.equals("\u001bOF") || k.equals("\u001b[4~") || k.equals("\u0005")) {
                linePos = lineText.size();
            } else if (k.equals("\u0015")) {
                lineText.subList(0, linePos).clear();
                linePos = 0;
            } else if (k.equals("\u000b")) {
                lineText.subList(linePos, lineText.size()).clear();
            } else if (k.equals("\u0017")) {
                int p = linePos;
                while (p > 0 && lineText.get(p - 1) == ' ') {
                    p--;
                }
                while (p > 0 && lineText.get(p - 1) != ' ') {
                    p--;
                }
                lineText.subList(p, linePos).clear();
                linePos = p;
            } else if (k.equals("\u0003")) {
                // Like the program's own ctrl-c on a typed line: clear it.
                endLine();
            } else if (k.equals("\r") || k.equals("\n")) {
                if (lineText.isEmpty() && !submitting) {
                    outgoing.append('\r');
                    endLine();
                } else if (!lineText.isEmpty()) {
                    submit(now);
                }
            } else if (submitting) {
                // The earlier line's enter has not gone yet: anything else
                // would land in its input. It waits, as typed.
                afterSubmit.append(String.join("", t.subList(i, t.size())));
                break;
            } else {
                // Tab, arrows up/down, escape, a paste...: the program has to
                // see the line and the key.
                return passOn(t, i, now);
            }
            if (!lineActive) {
                // The line was cleared away; the rest goes as typed.
                String rest = String.join("", t.subList(i + 1, t.size()));
                outgoing.append(rest);
                dirtyRows.add(lineRow);
                return render() + (rest.isEmpty() ? "" : predictKeys(rest, now));
            }
        }
        dirtyRows.add(lineRow);
        return render();
    }

    /**
     * Sends the local line as typed, then the keys from token {@code i} on,
     * which go on with echo prediction. The typed text becomes pending edits
     * so it stays on screen until the remote echoes it.
     */
    private String passOn(List<String> t, int i, long now) {
        flushLine(now);
        String rest = String.join("", t.subList(i, t.size()));
        outgoing.append(rest);
        return render() + predictKeys(rest, now);
    }

    private void flushLine(long now) {
        String text = lineString();
        outgoing.append(text);
        dirtyRows.add(lineRow);
        lineActive = false;
        if (lineText.isEmpty()) {
            return;
        }
        if (linePos < lineText.size()) {
            // The cursor was inside the text: move the remote's there too.
            outgoing.append("\u001b[D".repeat(lineText.size() - linePos));
        }
        dropPending();
        baseRow = lineRow;
        base = lineOf(written, lineRow, lineCol);
        for (int c = lineCol; c < base.cells.length; c++) {
            base.cells[c] = ' ';
            base.pens[c] = "";
            base.typed[c] = false;
        }
        confident = true;
        int c = lineCol;
        for (int cp : lineText) {
            int w = Math.max(1, CharWidth.of(cp));
            pending.add(new Edit(false, c, cp, w, now));
            c += w;
        }
        if (linePos < lineText.size()) {
            // The text stays on screen, with the cursor where it was, until
            // the remote has drawn the text and moved its cursor back too.
            pending.add(new Edit(false, lineCol + lineWidth(linePos), Edit.MOVE, 0, now));
        }
    }

    /**
     * Enter on a typed line. The input box clears at once and the next line
     * can be typed; the text goes out now and its enter once the remote shows
     * the text, so it cannot be taken for part of a paste. A line entered
     * while an earlier one still waits for its enter waits its turn.
     */
    private void submit(long now) {
        List<Integer> text = new ArrayList<>(lineText);
        lineText.clear();
        linePos = 0;
        dirtyRows.add(lineRow);
        if (submitting) {
            queuedSubmits.add(text);
            return;
        }
        startSubmit(text, now);
    }

    private void startSubmit(List<Integer> text, long now) {
        submitText.clear();
        submitText.addAll(text);
        StringBuilder sb = new StringBuilder();
        for (int cp : text) {
            sb.appendCodePoint(cp);
        }
        outgoing.append(sb);
        submitting = true;
        submitSince = now;
    }

    /**
     * The enter goes once the remote has put the whole text in its input; if
     * the echo never comes (the program shows long text differently), once
     * the remote has had plenty of time to show it. A stalled link is not
     * such time: the enter then waits for the link.
     */
    private void checkSubmit(long now) {
        if (!submitting) {
            return;
        }
        long timeout = srttNanos < 0 ? 1_500_000_000L : Math.max(500_000_000L, srttNanos * 3);
        if (!lineEchoed() && !overdue(submitSince, now, timeout)) {
            return;
        }
        outgoing.append('\r');
        submitting = false;
        awaitingClear = true;
        awaitingSince = now;
        if (!queuedSubmits.isEmpty()) {
            startSubmit(queuedSubmits.remove(0), now);
            return;
        }
        if (afterSubmit.length() > 0) {
            outgoing.append(afterSubmit);
            afterSubmit.setLength(0);
        }
    }

    /** The remote's input shows the text being submitted, with its cursor after it. */
    private boolean lineEchoed() {
        int end = lineCol;
        for (int cp : submitText) {
            end += Math.max(1, CharWidth.of(cp));
        }
        return screen.inGround() && screen.row() == lineRow && screen.col() == end && rowShows(screen, submitText);
    }

    /** The remote's input row, from the prompt on, starts with this text. */
    private boolean rowShows(ScreenModel m, List<Integer> text) {
        int c = lineCol;
        for (int cp : text) {
            if (m.charAt(lineRow, c) != cp) {
                return false;
            }
            c += Math.max(1, CharWidth.of(cp));
        }
        return !text.isEmpty();
    }

    private void endLine() {
        lineActive = false;
        lineText.clear();
        linePos = 0;
        dirtyRows.add(lineRow);
    }

    /**
     * After a complete remote frame: the prompt may have moved (the layout
     * changed), or given way to a question the user has to answer.
     */
    private void followPrompt() {
        if (!lineActive || submitting) {
            return;
        }
        if (awaitingClear) {
            // The enter went, but the remote's input still shows what was
            // sent until it takes it. The new line stays meanwhile.
            if (written.altScreen() && rowShows(written, submitText)
                    && !overdue(awaitingSince, lastOutputNanos, timeoutNanos())) {
                lineShown = true;
                dirtyRows.add(lineRow);
                return;
            }
            awaitingClear = false;
            if (lineText.isEmpty()) {
                // The remote took the line and nothing was typed since: its
                // own prompt can show again.
                endLine();
                return;
            }
        }
        if (!written.altScreen()) {
            // The program is gone; what was typed into it goes with it.
            endLine();
            return;
        }
        boolean empty = atEmptyPrompt(written);
        if (empty && (written.row() != lineRow || written.col() != lineCol)) {
            dirtyRows.add(lineRow);
            lineRow = written.row();
            lineCol = written.col();
        }
        lineShown = empty;
        dirtyRows.add(lineRow);
    }

    /** The local line as the user should see it: the prompt, then the text. */
    private Line lineDisplay() {
        Line line = lineOf(written, lineRow, lineCol);
        for (int c = lineCol; c < line.cells.length; c++) {
            line.cells[c] = ' ';
            line.pens[c] = "";
        }
        int c = lineCol;
        for (int cp : lineText) {
            int w = Math.max(1, CharWidth.of(cp));
            if (c + w > line.cells.length) {
                break;
            }
            line.cells[c] = cp;
            if (w == 2) {
                line.cells[c + 1] = ScreenModel.WIDE_TAIL;
            }
            c += w;
        }
        line.cursor = lineCol + lineWidth(linePos);
        return line;
    }

    private int lineWidth(int upTo) {
        int w = 0;
        for (int i = 0; i < upTo; i++) {
            w += Math.max(1, CharWidth.of(lineText.get(i)));
        }
        return w;
    }

    private String lineString() {
        StringBuilder sb = new StringBuilder();
        for (int cp : lineText) {
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    /**
     * The cursor stands right after an input prompt ("❯ " or "> ") at the
     * left of its row, with nothing typed after it: at most a dim placeholder.
     */
    private static boolean atEmptyPrompt(ScreenModel m) {
        if (!eligible(m)) {
            return false;
        }
        int r = m.row();
        int c = m.col();
        if (c < 2) {
            return false;
        }
        int glyph = m.charAt(r, c - 2);
        int gap = m.charAt(r, c - 1);
        if ((glyph != 0x276F && glyph != '>') || (gap != ' ' && gap != 0xA0)) {
            return false;
        }
        for (int x = 0; x < c - 2; x++) {
            int ch = m.charAt(r, x);
            if (ch != ' ' && ch != 0x2502 && ch != '|') {
                return false;
            }
        }
        for (int x = c; x < m.cols(); x++) {
            if (m.charAt(r, x) != ' ' && !isDim(m.penAt(r, x))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDim(String pen) {
        return pen.matches("(?s).*\\u001b\\[(?:[0-9;]*;)?2(?:;[0-9;]*)?m.*");
    }

    /** The code point of a token that is a single printable character, else -1. */
    private static int printable(String k) {
        int cp = k.codePointAt(0);
        if (k.length() != Character.charCount(cp) || cp < 0x20 || cp == 0x7F || (cp >= 0x80 && cp < 0xA0)) {
            return -1;
        }
        return CharWidth.of(cp) >= 1 ? cp : -1;
    }

    /**
     * Splits keyboard input into keys: one character, one control, or one
     * escape sequence each. A bracketed paste is one token to the end.
     */
    static List<String> tokens(String keys) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = keys.length();
        while (i < n) {
            char c = keys.charAt(i);
            int end;
            if (c == 0x1B && i + 1 < n && keys.charAt(i + 1) == '[') {
                if (keys.startsWith("\u001b[200~", i)) {
                    out.add(keys.substring(i));
                    break;
                }
                end = i + 2;
                while (end < n && !(keys.charAt(end) >= 0x40 && keys.charAt(end) <= 0x7E)) {
                    end++;
                }
                end = Math.min(n, end + 1);
            } else if (c == 0x1B && i + 2 < n && keys.charAt(i + 1) == 'O') {
                end = i + 3;
            } else if (c == 0x1B && i + 1 < n) {
                end = i + 1 + Character.charCount(keys.codePointAt(i + 1));
            } else {
                end = i + Character.charCount(keys.codePointAt(i));
            }
            out.add(keys.substring(i, end));
            i = end;
        }
        return out;
    }

    /** Takes the line under the cursor as the base when the first edit is made. */
    private void startBase() {
        if (!pending.isEmpty() && baseRow >= 0) {
            return;
        }
        ScreenModel src = current();
        baseRow = cursorRow();
        base = lineOf(src, baseRow, cursorCol());
    }

    private Line lineOf(ScreenModel m, int r, int cursor) {
        int cols = m.cols();
        int[] cells = new int[cols];
        String[] pens = new String[cols];
        boolean[] typed = new boolean[cols];
        for (int c = 0; c < cols; c++) {
            cells[c] = m.charAt(r, c);
            pens[c] = m.penAt(r, c);
            Integer t = typedCells.get(key(r, c));
            typed[c] = t != null && t == cells[c];
        }
        return new Line(cells, pens, typed, cursor);
    }

    /** The base line with every pending edit applied: what the user should see. */
    private Line display() {
        Line line = base.copy();
        for (Edit e : pending) {
            line.apply(e);
        }
        return line;
    }

    // ---------------------------------------------------------------- output

    /**
     * Called with remote output: returns what to write to the terminal now.
     * While predictions are on screen and the program is in the middle of a
     * redraw, the output is held back and "" returned; it goes out with the
     * rest of the redraw, or from {@link #tick} after at most
     * {@link #HOLD_NANOS}.
     */
    public String onOutput(String text, long now) {
        long quiet = srttNanos < 0 ? 1_000_000_000L : Math.max(1_000_000_000L, srttNanos * 3);
        if (lastOutputNanos != 0 && now - lastOutputNanos > quiet) {
            // The link was quiet (stalled, or the program idle): keys sent
            // meanwhile could only have reached the remote about now.
            resumedNanos = now;
        }
        lastOutputNanos = now;
        if (held.length() == 0) {
            heldSince = now;
            heldForPredictions = !pending.isEmpty() || (lineActive && lineShown);
        }
        held.append(text);
        screen.feed(text);
        noteStable();
        boolean redrawDone = screen.inGround() && screen.cursorVisible();
        if (heldForPredictions && !redrawDone && now - heldSince < HOLD_NANOS && held.length() < HOLD_LIMIT) {
            return "";
        }
        return flush(now);
    }

    /**
     * Called with output the terminal shows by other means (lagless's own line
     * editor prints it): the models learn about it, nothing is written.
     */
    public void observe(String text) {
        screen.feed(text);
        written.feed(text);
        shown.feed(text);
        noteStable();
    }

    /** Called periodically: releases held output, takes back predictions that never came true. */
    public String tick(long now) {
        if (held.length() > 0) {
            return now - heldSince >= HOLD_NANOS ? flush(now) : "";
        }
        checkSubmit(now);
        if (pending.isEmpty() && probe.isEmpty() && dirtyRows.isEmpty()) {
            return "";
        }
        settle(now, false);
        return render();
    }

    /** Forgets every prediction, e.g. when leaving raw mode or before a resize. */
    public String reset() { return reset(false); }

    /** Resize changes only the display; it must never flush a pending Enter. */
    public String resetForResize() { return reset(true); }

    private String reset(boolean resizing) {
        StringBuilder out = new StringBuilder();
        if (held.length() > 0) {
            String data = held.toString();
            held.setLength(0);
            String back = cursorBack();
            written.feed(data);
            out.append(emit(back + data));
        }
        if (submitting && !resizing) {
            outgoing.append('\r');
            for (List<Integer> queued : queuedSubmits) {
                for (int cp : queued) {
                    outgoing.appendCodePoint(cp);
                }
                outgoing.append('\r');
            }
            queuedSubmits.clear();
            outgoing.append(afterSubmit);
            afterSubmit.setLength(0);
            submitting = false;
            endLine();
        }
        if (lineActive) {
            // A resize keeps the line (the prompt is found again on the next
            // frame); leaving the program drops it, it was typed for the program.
            if (resizing || written.altScreen()) {
                dirtyRows.add(lineRow);
            } else {
                endLine();
            }
        }
        dropPending();
        endEpoch();
        out.append(render());
        return out.toString();
    }

    /** The terminal changed size. Call {@link #resetForResize} first. */
    public void resize(int cols, int rows) {
        screen.resize(cols, rows);
        written.resize(cols, rows);
        shown.resize(cols, rows);
        dirtyRows.clear();
        typedCells.clear();
    }

    private String flush(long now) {
        // An escape sequence cut off by a packet boundary (or a lost packet)
        // stays back until the rest arrives: with it the terminal is mid
        // sequence, and nothing, not even a typed key, could be drawn.
        int complete = completeLength(held);
        if (held.length() - complete > 4096) {
            complete = held.length();
        }
        String data = held.substring(0, complete);
        held.delete(0, complete);
        heldSince = now;
        String back = cursorBack();
        written.feed(data);
        // Only wrap a complete update: closing it after half an escape
        // sequence would corrupt the sequence.
        boolean wrap = heldForPredictions && written.inGround();
        StringBuilder out = new StringBuilder();
        if (wrap) {
            out.append(emit(SYNC_ON + HIDE_CURSOR));
        }
        out.append(emit(back + data));
        settle(now, true);
        // The output may have painted over the typed line (a redraw ends with
        // erase-to-end-of-line): compare it again.
        if (baseRow >= 0) {
            dirtyRows.add(baseRow);
        }
        if (written.inGround() && written.cursorVisible()) {
            followPrompt();
        }
        if (lineActive) {
            dirtyRows.add(lineRow);
        }
        checkSubmit(now);
        out.append(render());
        if (wrap) {
            out.append(emit((written.cursorVisible() ? SHOW_CURSOR : "") + SYNC_OFF));
        }
        return out.toString();
    }

    /**
     * The remote's relative moves assume the cursor where its last output left
     * it; lagless may have moved it to draw typed keys. It is moved back by
     * exactly the relative steps lagless took, never to an absolute position:
     * the models may be a cell off (a glyph whose width the terminal sees
     * differently), and an absolute move would then put the remote's next
     * output in the wrong place.
     */
    private String cursorBack() {
        if (!shown.inGround() || !written.inGround()) {
            return "";
        }
        String hide = "";
        if (cursorShownByUs) {
            cursorShownByUs = false;
            hide = HIDE_CURSOR;
        }
        return hide + step(shown.row(), shown.col(), written.row(), written.col());
    }

    // ---------------------------------------------------------------- settling

    /** Works out how many pending keys the remote has handled, and whether it went its own way. */
    private void settle(long now, boolean remoteUpdate) {
        // Decisions need a complete picture of what the remote drew.
        if (!screen.inGround()) {
            return;
        }
        long timeout = timeoutNanos();
        checkProbe(now, timeout);
        if (pending.isEmpty()) {
            return;
        }
        List<Line> states = new ArrayList<>(pending.size() + 1);
        Line line = base.copy();
        states.add(line.copy());
        for (Edit e : pending) {
            line.apply(e);
            states.add(line.copy());
        }
        if (remoteUpdate && moved(now)) {
            // The input is no longer on the row the keys were drawn on (the
            // program's input box grew as the text wrapped): the remote's
            // drawing is the truth from here.
            Log.d("local echo: input moved to another row, backing off");
            dropPending();
            endEpoch();
            return;
        }
        int handled = handledCount(states, now);
        if (handled >= 0) {
            if (handled > 0) {
                sample(now - pending.get(handled - 1).sentNanos);
                pending.subList(0, handled).clear();
                // The remote's own drawing of the line becomes the new base.
                base = lineOf(screen, baseRow, screen.col());
                boolean[] typed = states.get(handled).typed;
                System.arraycopy(typed, 0, base.typed, 0, Math.min(typed.length, base.typed.length));
                rememberRow(baseRow, base);
                dirtyRows.add(baseRow);
                if (pending.isEmpty()) {
                    baseRow = -1;
                }
            }
            if (!pending.isEmpty() && overdue(pending.get(0).sentNanos, now, timeout)) {
                // The remote is still showing an older state of the line; it
                // has not answered this key for far too long.
                Log.d("local echo: key not echoed in " + timeout / 1_000_000L + " ms, backing off");
                dropPending();
                endEpoch();
            }
            return;
        }
        // No prefix of the typed keys explains what the remote drew. That is
        // often just a state in between (a redraw of part of the line); what
        // was typed stays the truth unless the epoch ended or it is too old.
        boolean sealed = false;
        for (Edit e : pending) {
            sealed |= e.sealed;
        }
        if (remoteUpdate && sealed || rewrapped(states.get(states.size() - 1), now)
                || overdue(pending.get(0).sentNanos, now, timeout)) {
            if (!sealed) {
                Log.d("local echo: remote line differs from what was typed, backing off");
            }
            dropPending();
            if (!sealed) {
                endEpoch();
            }
        }
    }

    /**
     * How many pending edits the remote has handled: the largest k such that
     * the remote's line and cursor are exactly the base with the first k
     * edits applied; -1 if no k is.
     *
     * <p>States repeat (type a space, then backspace it), so a match alone can
     * credit the remote with keys it has not even received. A key sent less
     * than a round trip ago cannot be in what the remote drew, so such k are
     * tried only if nothing else matches, and then the smallest one is taken.
     */
    private int handledCount(List<Line> states, long now) {
        if (screen.row() != baseRow) {
            return -1;
        }
        int from = base.cursor;
        for (Edit e : pending) {
            from = Math.min(from, e.col);
        }
        long tooRecent = minRttNanos < 0 ? Long.MAX_VALUE : now - minRttNanos * 3 / 4;
        for (int k = states.size() - 1; k >= 0; k--) {
            boolean feasible = k == 0 || pending.get(k - 1).sentNanos <= tooRecent;
            if (feasible && drawn(states.get(k), from)) {
                return k;
            }
        }
        for (int k = 1; k < states.size(); k++) {
            if (drawn(states.get(k), from)) {
                return k;
            }
        }
        return -1;
    }

    /**
     * The remote handled every key and wrapped the text where the typed line
     * did not: a finished frame with the cursor where the last key leaves it,
     * and the row equal to the typed line except that one of them ends
     * earlier, the rest having moved to or come from the next row.
     */
    private boolean rewrapped(Line last, long now) {
        // With no round trip measured yet (a line handed over from the local
        // line), any finished frame counts.
        long tooRecent = minRttNanos < 0 ? now : now - minRttNanos * 3 / 4;
        if (!screen.cursorVisible() || screen.row() != baseRow || screen.col() != last.cursor
                || pending.get(pending.size() - 1).sentNanos > tooRecent) {
            return false;
        }
        int[] remote = rowCells(screen, baseRow);
        int remoteEnd = lastUsed(remote);
        int typedEnd = lastUsed(last.cells);
        if (remoteEnd == typedEnd) {
            return false;
        }
        for (int c = 0; c <= Math.min(remoteEnd, typedEnd); c++) {
            if (remote[c] != last.cells[c]) {
                return false;
            }
        }
        return true;
    }

    /**
     * A finished frame, long enough after the oldest pending key for the
     * remote to have handled it, leaves the cursor on another row than the one
     * being edited, or changes that row left of every edit (where the prompt
     * stood): the keys did not land on the row they were drawn on.
     */
    private boolean moved(long now) {
        // With no round trip measured yet (a line handed over from the local
        // line), any finished frame counts.
        long tooRecent = minRttNanos < 0 ? now : now - minRttNanos * 3 / 4;
        if (!screen.cursorVisible() || pending.get(0).sentNanos > tooRecent) {
            return false;
        }
        if (screen.row() != baseRow) {
            return true;
        }
        int from = Math.min(base.cursor, Math.min(base.cells.length, screen.cols()));
        for (Edit e : pending) {
            from = Math.min(from, e.col);
        }
        for (int c = 0; c < from; c++) {
            if (screen.charAt(baseRow, c) != base.cells[c]) {
                return true;
            }
        }
        return false;
    }

    /** The remote's line from column {@code from} on, and its cursor, are exactly this state. */
    private boolean drawn(Line s, int from) {
        if (s.cursor != screen.col()) {
            return false;
        }
        for (int c = from; c < s.cells.length; c++) {
            if (screen.charAt(baseRow, c) != s.cells[c]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The probe came true when the program has drawn a run of the probe keys
     * and moved the cursor past them: some j such that probe keys ..j stand
     * just left of the remote cursor, in order. Then the remote has
     * handled those j keys and the cursor is where key j+1 lands, so the keys
     * typed since are drawn at once as pending edits. Waiting for the last key
     * instead would mean waiting until the user pauses for a round trip.
     */
    private void checkProbe(long now, long timeout) {
        if (probe.isEmpty()) {
            return;
        }
        if (overdue(probeSent.get(0), now, timeout)) {
            probe.clear();
            probeSent.clear();
            return;
        }
        if (!eligible(screen)) {
            return;
        }
        int r = screen.row();
        int c = screen.col();
        long tooRecent = minRttNanos < 0 ? Long.MAX_VALUE : now - minRttNanos * 3 / 4;
        // Keys before the run may have been swallowed (vim's "i"). The longest
        // run wins. Equally long runs ending at different keys (the same key
        // typed twice where the run meets the row start) leave open how many
        // keys the remote handled: drawing the rest could show a key twice,
        // so the probe waits for an echo that tells them apart.
        int handled = 0;
        int bestRun = 0;
        boolean ambiguous = false;
        for (int j = 1; j <= probe.size(); j++) {
            if (probeSent.get(j - 1) > tooRecent) {
                continue;
            }
            int run = 0;
            int x = c;
            for (int i = j - 1; i >= 0; i--) {
                x -= Math.max(1, CharWidth.of(probe.get(i)));
                if (x < 0 || screen.charAt(r, x) != probe.get(i)) {
                    break;
                }
                run++;
            }
            if (run > bestRun) {
                bestRun = run;
                handled = j;
                ambiguous = false;
            } else if (run == bestRun && run > 0) {
                ambiguous = true;
            }
        }
        if (handled == 0 || ambiguous) {
            return;
        }
        int x = c;
        for (int i = handled - 1; i >= handled - bestRun; i--) {
            x -= Math.max(1, CharWidth.of(probe.get(i)));
            remember(r, x, probe.get(i));
        }
        sample(now - probeSent.get(handled - 1));
        List<Integer> rest = new ArrayList<>(probe.subList(handled, probe.size()));
        List<Long> restSent = new ArrayList<>(probeSent.subList(handled, probeSent.size()));
        probe.clear();
        probeSent.clear();
        // Anything typed before the probe has been handled by now.
        dropPending();
        confident = true;
        Log.d("local echo: keys are echoed at the cursor, predicting (rtt " + roundTripMillis() + " ms, "
                + rest.size() + " keys still in flight)");
        // The keys typed since are drawn now, as edits from the remote's line.
        for (int i = 0; i < rest.size(); i++) {
            int cp = rest.get(i);
            if (!predictChar(cp, Math.max(1, CharWidth.of(cp)), restSent.get(i))) {
                dropPending();
                endEpoch();
                return;
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    /**
     * Makes the terminal show the display, remote output as written with the
     * pending keys applied, by drawing only the cells that differ, then puts
     * the cursor after the last pending key (or where the remote left it).
     */
    private String render() {
        // Mid escape sequence: anything injected now would corrupt it.
        if (!shown.inGround() || !written.inGround()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        Line typedLine = pending.isEmpty() ? null : display();
        Line localLine = lineActive && lineShown ? lineDisplay() : null;
        // Every move is relative to where the terminal's cursor is, so a model
        // that is a cell off cannot send lagless's drawing (or the remote's
        // next output) somewhere else than intended.
        int atRow = shown.row();
        int atCol = shown.col();
        for (int r : dirtyRows) {
            if (r < 0 || r >= shown.rows()) {
                continue;
            }
            Line line = localLine != null && r == lineRow ? localLine
                    : r == baseRow && typedLine != null ? typedLine : lineOf(written, r, 0);
            int c = 0;
            while (c < line.cells.length) {
                if (line.cells[c] == shown.charAt(r, c)) {
                    c++;
                    continue;
                }
                int start = c > 0 && line.cells[c] == ScreenModel.WIDE_TAIL ? c - 1 : c;
                int end = c;
                while (end < line.cells.length
                        && (line.cells[end] != shown.charAt(r, end) || line.cells[end] == ScreenModel.WIDE_TAIL)) {
                    end++;
                }
                out.append(step(atRow, atCol, r, start));
                for (int x = start; x < end; x++) {
                    if (line.cells[x] != ScreenModel.WIDE_TAIL) {
                        out.append(SGR_RESET).append(line.pens[x]).appendCodePoint(line.cells[x]);
                    }
                }
                atRow = r;
                atCol = Math.min(end, line.cells.length - 1);
                c = end;
            }
        }
        dirtyRows.clear();
        if (out.length() > 0) {
            // Leave the rendition as the remote set it.
            out.append(SGR_RESET).append(written.pen());
        }
        shown.feed(out);
        int cr = typedLine != null ? baseRow : written.row();
        int cc = typedLine != null ? typedLine.cursor : written.col();
        if (localLine != null) {
            cr = lineRow;
            cc = localLine.cursor;
            if (!shown.cursorVisible()) {
                // The remote hid it for a redraw that has not finished (the
                // link stalls); the user still needs to see where they type.
                out.append(emit(SHOW_CURSOR));
                cursorShownByUs = true;
            }
        }
        out.append(emit(step(shown.row(), shown.col(), cr, cc)));
        return out.toString();
    }

    /**
     * Length of the part of {@code s} that ends outside any escape sequence
     * and not in the middle of a surrogate pair.
     */
    static int completeLength(CharSequence s) {
        int n = s.length();
        int i = 0;
        while (i < n) {
            if (s.charAt(i) != 0x1B) {
                i++;
                continue;
            }
            int end = sequenceEnd(s, i);
            if (end < 0) {
                return i;
            }
            i = end;
        }
        return n > 0 && Character.isHighSurrogate(s.charAt(n - 1)) ? n - 1 : n;
    }

    /** Index just after the escape sequence starting at {@code i}, or -1 if it is not complete yet. */
    private static int sequenceEnd(CharSequence s, int i) {
        int n = s.length();
        if (i + 1 >= n) {
            return -1;
        }
        char c = s.charAt(i + 1);
        if (c == '[') {
            for (int j = i + 2; j < n; j++) {
                char d = s.charAt(j);
                if (d >= 0x40 && d <= 0x7E) {
                    return j + 1;
                }
            }
            return -1;
        }
        if (c == ']' || c == 'P' || c == '^' || c == '_' || c == 'X') {
            for (int j = i + 2; j < n; j++) {
                char d = s.charAt(j);
                if (d == 0x07) {
                    return j + 1;
                }
                if (d == 0x1B) {
                    return j + 1 < n ? (s.charAt(j + 1) == '\\' ? j + 2 : j) : -1;
                }
            }
            return -1;
        }
        if ("()*+-./#% ".indexOf(c) >= 0) {
            return i + 3 <= n ? i + 3 : -1;
        }
        return i + 2;
    }

    /** Index of the last non-blank cell, -1 for a blank row. */
    /** The row under {@code r} shows text, not just blanks or a box border. */
    private boolean rowBelowHasText(int r) {
        ScreenModel m = current();
        if (r + 1 >= m.rows()) {
            return false;
        }
        for (int c = 0; c < m.cols(); c++) {
            int cp = m.charAt(r + 1, c);
            if (cp != ' ' && cp != ScreenModel.WIDE_TAIL && cp != 0xA0 && !(cp >= 0x2500 && cp <= 0x257F)) {
                return true;
            }
        }
        return false;
    }

    private static int lastUsed(int[] line) {
        for (int c = line.length - 1; c >= 0; c--) {
            if (line[c] != ' ') {
                return c;
            }
        }
        return -1;
    }

    /** Records bytes going to the terminal and hands them back. */
    private String emit(String s) {
        shown.feed(s);
        return s;
    }

    // ---------------------------------------------------------------- helpers

    private void noteStable() {
        if (screen.inGround() && screen.cursorVisible()) {
            stableOk = eligible(screen);
            stableRow = screen.row();
            stableCol = screen.col();
            stableCells = rowCells(screen, stableRow);
            stableEmptyPrompt = stableOk && atEmptyPrompt(screen);
        }
    }

    /** The state keys are judged against: what the terminal has (the remote's output as written). */
    private ScreenModel current() {
        return held.length() > 0 ? written : screen;
    }

    /**
     * Keys can be predicted when the screen allows it now, or, when the
     * current frame is unfinished (cursor hidden mid-redraw, the rest held up
     * by a lost packet), when the last complete frame allowed it and the row
     * the user types on still looks as it did then.
     */
    private boolean keysPredictable() {
        ScreenModel m = current();
        return eligible(m) || fromStable(m);
    }

    private boolean fromStable(ScreenModel m) {
        return !eligible(m) && stableOk && m.altScreen() && !m.originMode()
                && stableRow < m.rows() && Arrays.equals(rowCells(m, stableRow), stableCells);
    }

    /** Where the next key lands when nothing is pending. */
    private int cursorRow() {
        ScreenModel m = current();
        return fromStable(m) ? stableRow : m.row();
    }

    private int cursorCol() {
        ScreenModel m = current();
        return fromStable(m) ? stableCol : m.col();
    }

    private static int[] rowCells(ScreenModel m, int r) {
        int[] cells = new int[m.cols()];
        for (int c = 0; c < cells.length; c++) {
            cells[c] = m.charAt(r, c);
        }
        return cells;
    }

    /**
     * A key sent at {@code sent} is overdue when the remote has been talking
     * for a whole timeout since it could have received the key, i.e. since it
     * was sent or since the link came back from a stall, whichever is later.
     * While the link is silent nothing is overdue: the answer cannot come.
     */
    private boolean overdue(long sent, long now, long timeout) {
        long from = Math.max(sent, resumedNanos);
        return lastOutputNanos - from > timeout || now - sent > MAX_WAIT_NANOS;
    }

    private static boolean eligible(ScreenModel m) {
        return m.altScreen() && m.cursorVisible() && !m.originMode() && m.inGround() && !m.pendingWrap();
    }

    /**
     * A key whose effect cannot be predicted was sent. What was typed before
     * it stays on screen until a remote update it cannot explain; nothing
     * typed after it is drawn until an echo proves prediction works again.
     */
    private void endEpoch() {
        confident = false;
        probe.clear();
        probeSent.clear();
        for (Edit e : pending) {
            e.sealed = true;
        }
    }

    private void dropPending() {
        if (baseRow >= 0) {
            dirtyRows.add(baseRow);
        }
        pending.clear();
        baseRow = -1;
    }

    private void remember(int r, int c, int cp) {
        if (typedCells.size() >= MAX_TYPED_CELLS) {
            typedCells.clear();
        }
        typedCells.put(key(r, c), cp);
    }

    /** Records which cells of a row the user typed, replacing what was known about it. */
    private void rememberRow(int r, Line line) {
        typedCells.keySet().removeIf(k -> k / 4096 == r);
        for (int c = 0; c < line.cells.length; c++) {
            if (line.typed[c]) {
                remember(r, c, line.cells[c]);
            }
        }
    }

    private void sample(long nanos) {
        if (nanos <= 0) {
            return;
        }
        srttNanos = srttNanos < 0 ? nanos : (srttNanos * 7 + nanos) / 8;
        minRttNanos = minRttNanos < 0 ? nanos : Math.min(minRttNanos, nanos);
    }

    private long timeoutNanos() {
        if (srttNanos < 0) {
            return MAX_TIMEOUT_NANOS;
        }
        return Math.max(MIN_TIMEOUT_NANOS, Math.min(MAX_TIMEOUT_NANOS, srttNanos * 6));
    }

    /** Relative cursor movement from one cell to another. */
    private static String step(int fromRow, int fromCol, int toRow, int toCol) {
        StringBuilder sb = new StringBuilder();
        if (toRow < fromRow) {
            sb.append("\u001b[").append(fromRow - toRow).append('A');
        } else if (toRow > fromRow) {
            sb.append("\u001b[").append(toRow - fromRow).append('B');
        }
        if (toCol < fromCol) {
            sb.append("\u001b[").append(fromCol - toCol).append('D');
        } else if (toCol > fromCol) {
            sb.append("\u001b[").append(toCol - fromCol).append('C');
        }
        return sb.toString();
    }

    private static int key(int r, int c) {
        return r * 4096 + c;
    }

    /**
     * Turns what the keyboard sent into typed code points: plain text, DEL/BS,
     * and kitty-protocol "CSI code u" keys with no modifier but shift. Returns
     * null for anything else (escape sequences, controls other than backspace).
     */
    static int[] decodeKeys(String keys) {
        List<Integer> out = new ArrayList<>();
        int i = 0;
        int n = keys.length();
        while (i < n) {
            int cp = keys.codePointAt(i);
            if (cp == 0x1B) {
                Matcher m = KITTY_KEY.matcher(keys).region(i, n);
                if (!m.lookingAt()) {
                    return null;
                }
                int code = Integer.parseInt(m.group(1));
                if (code != 0x7F && (code < 0x20 || (code >= 0x80 && code < 0xA0) || code > 0x10FFFF)) {
                    return null;
                }
                out.add(code);
                i = m.end();
                continue;
            }
            if ((cp < 0x20 && cp != 0x08) || (cp >= 0x80 && cp < 0xA0)) {
                return null;
            }
            out.add(cp);
            i += Character.charCount(cp);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }
}
