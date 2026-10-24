package dev.lagless;

/**
 * Watches the decoded output stream from the remote and keeps just enough
 * state to answer two questions: are we in the alternate screen (a
 * full-screen program is running) and what does the current, unfinished
 * output line look like (is it a prompt?).
 *
 * <p>Not thread safe; the output pump thread owns it.
 */
public final class OutputMonitor {

    private final StringBuilder rawLine = new StringBuilder();
    private final StringBuilder pendingEscape = new StringBuilder();
    private boolean altScreen;
    private volatile long lastDataNanos = System.nanoTime();

    public void feed(CharSequence chunk) {
        lastDataNanos = System.nanoTime();
        int i = 0;
        int n = chunk.length();
        while (i < n) {
            if (pendingEscape.length() > 0) {
                pendingEscape.append(chunk.charAt(i));
                i++;
                if (escapeComplete(pendingEscape)) {
                    handleEscape(pendingEscape.toString());
                    pendingEscape.setLength(0);
                }
                continue;
            }
            char c = chunk.charAt(i);
            if (c == 0x1B) {
                pendingEscape.append(c);
                i++;
                continue;
            }
            if (c == '\n') {
                rawLine.setLength(0);
            } else if (c == '\r') {
                // A bare CR means the line is being rewritten from column 0
                // (progress bars). CR LF is handled by the LF branch.
                if (i + 1 < n && chunk.charAt(i + 1) == '\n') {
                    // leave it to the LF
                } else {
                    rawLine.setLength(0);
                }
            } else {
                rawLine.append(c);
            }
            i++;
        }
    }

    private static boolean escapeComplete(StringBuilder esc) {
        if (esc.length() < 2) {
            return false;
        }
        char c = esc.charAt(1);
        if (c == '[') {
            char last = esc.charAt(esc.length() - 1);
            return esc.length() >= 3 && last >= 0x40 && last <= 0x7E;
        }
        if (c == ']' || c == 'P' || c == '^' || c == '_') {
            char last = esc.charAt(esc.length() - 1);
            if (last == 0x07) {
                return true;
            }
            return esc.length() >= 4 && esc.charAt(esc.length() - 2) == 0x1B && last == '\\';
        }
        if (c == '(' || c == ')' || c == '*' || c == '+' || c == '#' || c == '%') {
            return esc.length() >= 3;
        }
        return true;
    }

    private void handleEscape(String esc) {
        if (esc.startsWith("\u001b[?")) {
            String body = esc.substring(3, esc.length() - 1);
            char fin = esc.charAt(esc.length() - 1);
            boolean isAlt = body.equals("1049") || body.equals("47") || body.equals("1047");
            if (isAlt && fin == 'h') {
                altScreen = true;
                rawLine.setLength(0);
            } else if (isAlt && fin == 'l') {
                altScreen = false;
                rawLine.setLength(0);
            }
            return;
        }
        if (esc.equals("\u001b[K") || esc.equals("\u001b[0K") || esc.equals("\u001b[2K") || esc.equals("\u001b[2J")) {
            rawLine.setLength(0);
            return;
        }
        if (AnsiUtil.isSgrSequence(esc)) {
            // SGR: keep it so the prompt can be redrawn with colours.
            rawLine.append(esc);
            return;
        }
        if (esc.startsWith("\u001b[") && esc.endsWith("G")) {
            // Cursor horizontal absolute: shell is rewriting the line.
            rawLine.setLength(0);
        }
    }

    public boolean isAltScreen() {
        return altScreen;
    }

    public long quietMillis() {
        return (System.nanoTime() - lastDataNanos) / 1_000_000L;
    }

    /** Current unfinished line with escape sequences removed. */
    public String currentLineText() {
        return AnsiUtil.strip(rawLine);
    }

    /** Current unfinished line keeping SGR colours, suitable as a JLine prompt. */
    public String currentLinePrompt() {
        return AnsiUtil.keepSgrOnly(rawLine);
    }

    public void resetLine() {
        rawLine.setLength(0);
    }
}
