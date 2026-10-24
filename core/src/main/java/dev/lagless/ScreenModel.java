package dev.lagless;

import java.util.Arrays;

/**
 * A small model of what the remote has drawn on the local terminal: the
 * character in every cell, the SGR pen it was drawn with, the cursor and the
 * few modes local echo cares about. It exists so {@link LocalEcho} can tell
 * whether a predicted keystroke was echoed where it was expected and can put
 * back whatever a wrong prediction covered.
 *
 * <p>Only the visible screen is kept, no scrollback. Colours are not decoded:
 * each cell remembers the SGR sequences that were in effect, which is enough
 * to repaint it.
 *
 * <p>Not thread safe; the session feeds and queries it under its output lock.
 */
public final class ScreenModel {
    /** Optional desktop history sink. CLI users pay no allocation cost when it is absent. */
    public interface HistoryListener {
        void scrolled(int[] characters,String[] pens);
        default void beforeClear(ScreenModel screen){}
        /** First write of a new cursor-addressed run, before existing text is replaced. */
        default void beforeOverwrite(ScreenModel screen,int row){}
        default void beforeEraseRow(ScreenModel screen,int row){beforeClear(screen);}
        default void lineCompleted(ScreenModel screen,int row){}
        default void frameStarted(ScreenModel screen){}
        default void frameCompleted(ScreenModel screen){}
    }
    private HistoryListener history;
    private int historyRow=-1,historyNextColumn=-1;
    private long revision;
    private long[] rowRevisions;
    public void setHistoryListener(HistoryListener listener){history=listener;historyRow=historyNextColumn=-1;rowRevisions=listener==null?null:new long[rows];markAllRows();}
    /** Available only when a history sink is attached; standalone CLI has no row tracking. */
    public long rowRevision(int row){return rowRevisions==null||row<0||row>=rows?0:rowRevisions[row];}
    private void markRow(int row){if(rowRevisions!=null)rowRevisions[row]=++revision;}
    private void markAllRows(){if(rowRevisions!=null){if(rowRevisions.length!=rows)rowRevisions=new long[rows];Arrays.fill(rowRevisions,++revision);}}

    /** Marks the right half of a double-width character. */
    static final int WIDE_TAIL = -1;
    private static final int PEN_LIMIT = 256;

    private enum State { GROUND, ESC, ESC_SKIP, CSI, STRING, STRING_ESC }

    private int cols;
    private int rows;
    private int[][] chars;
    private String[][] pens;
    private int[][] savedChars;
    private String[][] savedPens;

    private int row;
    private int col;
    private boolean pendingWrap;
    private String pen = "";
    /** Last character printed, for REP (CSI n b). */
    private int lastPrinted = -1;
    private int top;
    private int bottom;
    private boolean cursorVisible = true;
    private boolean synchronizedOutput;
    private boolean altScreen;
    private boolean originMode;
    private boolean autowrap = true;
    private int savedRow;
    private int savedCol;
    private String savedPen = "";

    private State state = State.GROUND;
    private final StringBuilder seq = new StringBuilder();

    public ScreenModel(int cols, int rows) {
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
        chars = blankChars(this.cols, this.rows);
        pens = blankPens(this.cols, this.rows);
        top = 0;
        bottom = this.rows - 1;
    }

    // ------------------------------------------------------------ queries

    public int cols() {
        return cols;
    }

    public int rows() {
        return rows;
    }

    public int row() {
        return row;
    }

    public int col() {
        return col;
    }

    public boolean pendingWrap() {
        return pendingWrap;
    }

    public boolean cursorVisible() {
        return cursorVisible;
    }
    public boolean synchronizedOutput(){return synchronizedOutput;}

    public boolean altScreen() {
        return altScreen;
    }

    public boolean originMode() {
        return originMode;
    }

    /** True when the stream is not in the middle of an escape sequence. */
    public boolean inGround() {
        return state == State.GROUND;
    }

    /** SGR sequences currently in effect ("" for the default rendition). */
    public String pen() {
        return pen;
    }

    /** Code point in the cell, {@code ' '} when blank, {@link #WIDE_TAIL} for the right half of a wide one. */
    public int charAt(int r, int c) {
        if (r < 0 || r >= rows || c < 0 || c >= cols) {
            return ' ';
        }
        return chars[r][c];
    }

    public String penAt(int r, int c) {
        if (r < 0 || r >= rows || c < 0 || c >= cols) {
            return "";
        }
        return pens[r][c];
    }

    /** Text of one row, wide-character tails left out; for tests and logs. */
    public String rowText(int r) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < cols; c++) {
            if (chars[r][c] != WIDE_TAIL) {
                sb.appendCodePoint(chars[r][c]);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ resize

    public void resize(int newCols, int newRows) {
        newCols = Math.max(1, newCols);
        newRows = Math.max(1, newRows);
        if (newCols == cols && newRows == rows) {
            return;
        }
        if(history!=null&&(newRows<rows||newCols<cols))history.beforeClear(this);
        chars = copyChars(chars, newCols, newRows);
        pens = copyPens(pens, newCols, newRows);
        if (savedChars != null) {
            savedChars = copyChars(savedChars, newCols, newRows);
            savedPens = copyPens(savedPens, newCols, newRows);
        }
        cols = newCols;
        rows = newRows;
        top = 0;
        bottom = rows - 1;
        row = Math.min(row, rows - 1);
        col = Math.min(col, cols - 1);
        pendingWrap = false;
        markAllRows();
    }

    // ------------------------------------------------------------ parser

    public void feed(CharSequence text) {
        int i = 0;
        int n = text.length();
        while (i < n) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            step(cp);
        }
    }

    private void step(int cp) {
        switch (state) {
            case GROUND -> ground(cp);
            case ESC -> escape(cp);
            case ESC_SKIP -> state = State.GROUND;
            case CSI -> {
                if (cp == 0x18 || cp == 0x1A) {
                    state = State.GROUND;
                } else if (cp == 0x1B) {
                    seq.setLength(0);
                    state = State.ESC;
                } else if (cp >= 0x40 && cp <= 0x7E) {
                    csi(seq.toString(), (char) cp);
                    state = State.GROUND;
                } else if (cp >= 0x20) {
                    seq.appendCodePoint(cp);
                    if (seq.length() > 64) {
                        state = State.GROUND;
                    }
                }
                // C0 controls inside a CSI are executed by real terminals.
                else {
                    control(cp);
                }
            }
            case STRING -> {
                if (cp == 0x07 || cp == 0x9C) {
                    state = State.GROUND;
                } else if (cp == 0x1B) {
                    state = State.STRING_ESC;
                }
            }
            case STRING_ESC -> state = cp == '\\' ? State.GROUND : State.STRING;
        }
    }

    private void ground(int cp) {
        if (cp == 0x1B) {
            state = State.ESC;
            return;
        }
        if (cp < 0x20 || cp == 0x7F) {
            control(cp);
            return;
        }
        if (cp >= 0x80 && cp < 0xA0) {
            return;
        }
        print(cp);
    }

    private void control(int cp) {
        switch (cp) {
            case '\r' -> {
                col = 0;
                pendingWrap = false;
            }
            case '\n', 0x0B, 0x0C -> lineFeed();
            case 0x08 -> {
                if (col > 0) {
                    col--;
                }
                pendingWrap = false;
            }
            case '\t' -> {
                col = Math.min(cols - 1, (col / 8 + 1) * 8);
                pendingWrap = false;
            }
            default -> {
                // BEL, SO, SI and the rest do not move anything.
            }
        }
    }

    private void escape(int cp) {
        state = State.GROUND;
        switch (cp) {
            case '[' -> {
                seq.setLength(0);
                state = State.CSI;
            }
            case ']', 'P', '^', '_', 'X' -> state = State.STRING;
            case '(', ')', '*', '+', '-', '.', '/', '#', '%', ' ' -> state = State.ESC_SKIP;
            case '7' -> saveCursor();
            case '8' -> restoreCursor();
            case 'D' -> lineFeed();
            case 'E' -> {
                col = 0;
                lineFeed();
            }
            case 'M' -> reverseIndex();
            case 'c' -> hardReset();
            default -> {
                // '=', '>' (keypad modes) and anything unknown: no effect on cells.
            }
        }
    }

    private void csi(String body, char fin) {
        char prefix = 0;
        if (!body.isEmpty() && "?<=>".indexOf(body.charAt(0)) >= 0) {
            prefix = body.charAt(0);
            body = body.substring(1);
        }
        // Intermediates (space, '!', '"', '$' ...) mean a different command
        // (cursor style, soft reset); none of them move the cursor or cells.
        for (int k = 0; k < body.length(); k++) {
            char c = body.charAt(k);
            if (!(c >= '0' && c <= '9') && c != ';' && c != ':') {
                return;
            }
        }
        if (prefix == '?') {
            if (fin == 'h' || fin == 'l') {
                for (String p : body.split(";")) {
                    privateMode(parse(p, 0), fin == 'h');
                }
            }
            return;
        }
        if (prefix != 0) {
            return;
        }
        int[] p = params(body);
        int p0 = p.length > 0 ? p[0] : 0;
        int n = Math.max(1, p0);
        switch (fin) {
            case 'A' -> moveTo(Math.max(row < top ? 0 : top, row - n), col);
            case 'B', 'e' -> moveTo(Math.min(row > bottom ? rows - 1 : bottom, row + n), col);
            case 'C', 'a' -> moveTo(row, Math.min(cols - 1, col + n));
            case 'D' -> moveTo(row, Math.max(0, col - n));
            case 'E' -> moveTo(Math.min(bottom, row + n), 0);
            case 'F' -> moveTo(Math.max(top, row - n), 0);
            case 'G', '`' -> moveTo(row, clamp(n - 1, cols));
            case 'd' -> moveTo(originRow(n), col);
            case 'H', 'f' -> moveTo(originRow(n), clamp((p.length > 1 ? Math.max(1, p[1]) : 1) - 1, cols));
            case 'J' -> eraseDisplay(p0);
            case 'K' -> eraseLine(p0);
            case 'X' -> {if(history!=null)history.beforeOverwrite(this,row);clear(row, col, Math.min(cols, col + n));}
            case '@' -> insertChars(n);
            case 'P' -> deleteChars(n);
            case 'L' -> insertLines(n);
            case 'M' -> deleteLines(n);
            case 'S' -> scrollUp(top, bottom, n);
            case 'T' -> scrollDown(top, bottom, n);
            case 'r' -> setRegion(p);
            case 's' -> saveCursor();
            case 'u' -> restoreCursor();
            case 'm' -> sgr(body, p);
            case 'b' -> {
                // REP: the preceding character again, n times.
                if (lastPrinted >= 0) {
                    for (int k = 0; k < Math.min(n, cols * rows); k++) {
                        print(lastPrinted);
                    }
                }
            }
            default -> {
                // Queries, tab stops, modes: nothing to track.
            }
        }
    }

    private void privateMode(int mode, boolean on) {
        switch (mode) {
            case 6 -> {
                originMode = on;
                moveTo(on ? top : 0, 0);
            }
            case 7 -> autowrap = on;
            case 25 -> {cursorVisible=on;if(on&&!synchronizedOutput&&history!=null)history.frameCompleted(this);}
            case 2026 -> {synchronizedOutput=on;if(history!=null){if(on)history.frameStarted(this);else history.frameCompleted(this);}}
            case 47, 1047 -> switchScreen(on, false);
            case 1049 -> switchScreen(on, true);
            default -> {
            }
        }
    }

    private void sgr(String body, int[] p) {
        if (body.isEmpty() || (p.length == 1 && p[0] == 0)) {
            pen = "";
            return;
        }
        String s = "\u001b[" + body + "m";
        // A reset anywhere in the list drops what came before it.
        if (body.startsWith("0;")) {
            pen = s;
        } else {
            pen = pen.length() + s.length() > PEN_LIMIT ? s : pen + s;
        }
    }

    // ------------------------------------------------------------ actions

    private void print(int cp) {
        int w = CharWidth.of(cp);
        if (w <= 0) {
            // Combining marks and other zero-width code points stay with the
            // previous cell; nothing for the model to move.
            return;
        }
        if (pendingWrap || col + w > cols) {
            if (autowrap) {
                col = 0;
                lineFeed();
            } else {
                col = Math.max(0, cols - w);
            }
            pendingWrap = false;
        }
        if(history!=null&&(historyRow!=row||historyNextColumn!=col))history.beforeOverwrite(this,row);
        historyRow=row;historyNextColumn=col+w;
        breakWide(row, col);
        if (w == 2) {
            breakWide(row, col + 1);
        }
        chars[row][col] = cp;
        pens[row][col] = pen;
        if (w == 2 && col + 1 < cols) {
            chars[row][col + 1] = WIDE_TAIL;
            pens[row][col + 1] = pen;
        }
        markRow(row);
        lastPrinted = cp;
        col += w;
        if (col >= cols) {
            col = cols - 1;
            pendingWrap = autowrap;
        }
    }

    /** Overwriting half of a wide character blanks the other half. */
    private void breakWide(int r, int c) {
        if (c >= cols) {
            return;
        }
        if (chars[r][c] == WIDE_TAIL && c > 0) {
            chars[r][c - 1] = ' ';
        } else if (c + 1 < cols && chars[r][c + 1] == WIDE_TAIL) {
            chars[r][c + 1] = ' ';
        }
    }

    private void lineFeed() {
        if(history!=null)history.lineCompleted(this,row);
        pendingWrap = false;
        if (row == bottom) {
            scrollUp(top, bottom, 1);
        } else if (row < rows - 1) {
            row++;
        }
    }

    private void reverseIndex() {
        pendingWrap = false;
        if (row == top) {
            scrollDown(top, bottom, 1);
        } else if (row > 0) {
            row--;
        }
    }

    private void moveTo(int r, int c) {
        row = Math.max(0, Math.min(rows - 1, r));
        col = Math.max(0, Math.min(cols - 1, c));
        pendingWrap = false;
    }

    private int originRow(int n) {
        int r = Math.max(1, n) - 1;
        return originMode ? Math.min(bottom, top + r) : r;
    }

    private void eraseDisplay(int mode) {
        if(history!=null)history.beforeClear(this);
        switch (mode) {
            case 0 -> {
                clear(row, col, cols);
                for (int r = row + 1; r < rows; r++) {
                    clear(r, 0, cols);
                }
            }
            case 1 -> {
                for (int r = 0; r < row; r++) {
                    clear(r, 0, cols);
                }
                clear(row, 0, col + 1);
            }
            case 2, 3 -> {
                for (int r = 0; r < rows; r++) {
                    clear(r, 0, cols);
                }
            }
            default -> {
            }
        }
    }

    private void eraseLine(int mode) {
        if(history!=null)history.beforeEraseRow(this,row);
        switch (mode) {
            case 0 -> clear(row, col, cols);
            case 1 -> clear(row, 0, col + 1);
            case 2 -> clear(row, 0, cols);
            default -> {
            }
        }
    }

    /** Blanks [from, to) of a row with the current pen, as terminals do. */
    private void clear(int r, int from, int to) {
        from = Math.max(0, from);
        to = Math.min(cols, to);
        if (from >= to) {
            return;
        }
        if (from > 0 && chars[r][from] == WIDE_TAIL) {
            chars[r][from - 1] = ' ';
        }
        if (to < cols && chars[r][to] == WIDE_TAIL) {
            chars[r][to] = ' ';
        }
        Arrays.fill(chars[r], from, to, ' ');
        Arrays.fill(pens[r], from, to, pen);
        markRow(r);
        pendingWrap = false;
    }

    private void insertChars(int n) {
        if(history!=null)history.beforeOverwrite(this,row);
        n = Math.min(n, cols - col);
        System.arraycopy(chars[row], col, chars[row], col + n, cols - col - n);
        System.arraycopy(pens[row], col, pens[row], col + n, cols - col - n);
        clear(row, col, col + n);
    }

    private void deleteChars(int n) {
        if(history!=null)history.beforeOverwrite(this,row);
        n = Math.min(n, cols - col);
        System.arraycopy(chars[row], col + n, chars[row], col, cols - col - n);
        System.arraycopy(pens[row], col + n, pens[row], col, cols - col - n);
        clear(row, cols - n, cols);
    }

    private void insertLines(int n) {
        if (row < top || row > bottom) {
            return;
        }
        scrollDown(row, bottom, n);
        col = 0;
    }

    private void deleteLines(int n) {
        if (row < top || row > bottom) {
            return;
        }
        scrollUp(row, bottom, n);
        col = 0;
    }

    private void scrollUp(int from, int to, int n) {
        n = Math.min(n, to - from + 1);
        if(history!=null)for(int r=from;r<from+n;r++)history.scrolled(chars[r].clone(),pens[r].clone());
        for (int r = from; r <= to; r++) {
            int src = r + n;
            if (src <= to) {
                chars[r] = chars[src];
                pens[r] = pens[src];
            } else {
                chars[r] = blankRow(cols);
                pens[r] = penRow(cols, pen);
            }
            markRow(r);
        }
    }

    private void scrollDown(int from, int to, int n) {
        n = Math.min(n, to - from + 1);
        if(history!=null)for(int r=to-n+1;r<=to;r++)history.beforeOverwrite(this,r);
        for (int r = to; r >= from; r--) {
            int src = r - n;
            if (src >= from) {
                chars[r] = chars[src];
                pens[r] = pens[src];
            } else {
                chars[r] = blankRow(cols);
                pens[r] = penRow(cols, pen);
            }
            markRow(r);
        }
    }

    private void setRegion(int[] p) {
        int t = p.length > 0 && p[0] > 0 ? p[0] - 1 : 0;
        int b = p.length > 1 && p[1] > 0 ? p[1] - 1 : rows - 1;
        b = Math.min(b, rows - 1);
        if (t >= b) {
            return;
        }
        top = t;
        bottom = b;
        moveTo(originMode ? top : 0, 0);
    }

    private void saveCursor() {
        savedRow = row;
        savedCol = col;
        savedPen = pen;
    }

    private void restoreCursor() {
        moveTo(savedRow, savedCol);
        pen = savedPen;
    }

    private void switchScreen(boolean toAlt, boolean saveCursor) {
        if (toAlt == altScreen) {
            return;
        }
        if(history!=null)history.beforeClear(this);
        if (toAlt) {
            if (saveCursor) {
                saveCursor();
            }
            savedChars = chars;
            savedPens = pens;
            chars = blankChars(cols, rows);
            pens = blankPens(cols, rows);
        } else {
            if (savedChars != null) {
                chars = savedChars;
                pens = savedPens;
                savedChars = null;
                savedPens = null;
            }
            if (saveCursor) {
                restoreCursor();
            }
        }
        altScreen = toAlt;
        pendingWrap = false;
        markAllRows();
    }

    private void hardReset() {
        if(history!=null)history.beforeClear(this);
        chars = blankChars(cols, rows);
        pens = blankPens(cols, rows);
        savedChars = null;
        savedPens = null;
        row = 0;
        col = 0;
        pendingWrap = false;
        pen = "";
        top = 0;
        bottom = rows - 1;
        cursorVisible = true;
        synchronizedOutput=false;
        altScreen = false;
        markAllRows();
        originMode = false;
        autowrap = true;
    }

    // ------------------------------------------------------------ helpers

    private static int clamp(int v, int limit) {
        return Math.max(0, Math.min(limit - 1, v));
    }

    private static int[] params(String body) {
        if (body.isEmpty()) {
            return new int[0];
        }
        String[] parts = body.split(";", -1);
        int[] out = new int[parts.length];
        for (int k = 0; k < parts.length; k++) {
            out[k] = parse(parts[k], 0);
        }
        return out;
    }

    private static int parse(String s, int dflt) {
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(0, colon);
        }
        if (s.isEmpty()) {
            return dflt;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static int[] blankRow(int cols) {
        int[] r = new int[cols];
        Arrays.fill(r, ' ');
        return r;
    }

    private static String[] penRow(int cols, String pen) {
        String[] r = new String[cols];
        Arrays.fill(r, pen);
        return r;
    }

    private static int[][] blankChars(int cols, int rows) {
        int[][] g = new int[rows][];
        for (int r = 0; r < rows; r++) {
            g[r] = blankRow(cols);
        }
        return g;
    }

    private static String[][] blankPens(int cols, int rows) {
        String[][] g = new String[rows][];
        for (int r = 0; r < rows; r++) {
            g[r] = penRow(cols, "");
        }
        return g;
    }

    private static int[][] copyChars(int[][] src, int cols, int rows) {
        int[][] g = blankChars(cols, rows);
        for (int r = 0; r < Math.min(rows, src.length); r++) {
            System.arraycopy(src[r], 0, g[r], 0, Math.min(cols, src[r].length));
        }
        return g;
    }

    private static String[][] copyPens(String[][] src, int cols, int rows) {
        String[][] g = blankPens(cols, rows);
        for (int r = 0; r < Math.min(rows, src.length); r++) {
            System.arraycopy(src[r], 0, g[r], 0, Math.min(cols, src[r].length));
        }
        return g;
    }
}
