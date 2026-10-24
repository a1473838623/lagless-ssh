package dev.lagless;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Keeps remote programs from switching the local terminal into mouse
 * reporting, so the mouse keeps selecting text for copy and paste.
 *
 * <p>Full-screen programs such as Claude Code turn on mouse tracking
 * ({@code ESC[?1000h}, {@code ?1002h}, {@code ?1003h}, {@code ?1006h} ...).
 * The terminal then hands clicks and drags to the program instead of
 * selecting. On Windows those reports do not even reach the remote through
 * the console input lagless reads, so the user loses selection and gains
 * nothing. This filter drops the mouse modes from "set private mode"
 * sequences and passes everything else through untouched. Turning a mode off
 * is harmless and left alone.
 *
 * <p>Streaming: a sequence split across two chunks is completed from the next
 * chunk. Not thread safe; the output pump owns it.
 */
public final class MouseModeFilter {

    /** X10, normal, highlight, button-event, any-event tracking and the report encodings. */
    private static final Set<String> MOUSE_MODES =
            Set.of("9", "1000", "1001", "1002", "1003", "1005", "1006", "1015", "1016");
    private static final int MAX_SEQUENCE = 64;

    /** Start of a sequence cut off at the end of the previous chunk. */
    private final StringBuilder partial = new StringBuilder();

    public String feed(String chunk) {
        String text = partial.length() == 0 ? chunk : partial + chunk;
        partial.setLength(0);
        if (text.indexOf('\u001b') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c != '\u001b') {
                out.append(c);
                i++;
                continue;
            }
            int end = privateModeEnd(text, i);
            if (end == -2) {
                // Might become "ESC [ ? ... h" once the rest arrives.
                partial.append(text, i, n);
                break;
            }
            if (end < 0) {
                out.append(c);
                i++;
                continue;
            }
            out.append(rewrite(text.substring(i, end + 1)));
            i = end + 1;
        }
        return out.toString();
    }

    public boolean isHolding() {
        return partial.length() > 0;
    }

    /** Hands back a held partial sequence, e.g. when the stream has gone quiet. */
    public String flush() {
        String s = partial.toString();
        partial.setLength(0);
        return s;
    }

    /**
     * Index of the final byte of "ESC [ ? digits/semicolons h|l" starting at
     * {@code start}, -1 when the text there is something else, -2 when the
     * text ends before it can tell.
     */
    private static int privateModeEnd(String s, int start) {
        int n = s.length();
        int i = start + 1;
        if (i >= n) {
            return -2;
        }
        if (s.charAt(i) != '[') {
            return -1;
        }
        i++;
        if (i >= n) {
            return -2;
        }
        if (s.charAt(i) != '?') {
            return -1;
        }
        i++;
        while (i < n && i - start < MAX_SEQUENCE) {
            char c = s.charAt(i);
            if (c == 'h' || c == 'l') {
                return i;
            }
            if (!(c >= '0' && c <= '9') && c != ';') {
                return -1;
            }
            i++;
        }
        return i >= n && i - start < MAX_SEQUENCE ? -2 : -1;
    }

    /** "ESC[?1000;1006;25h" becomes "ESC[?25h"; a set of only mouse modes disappears. */
    private static String rewrite(String seq) {
        if (seq.charAt(seq.length() - 1) != 'h') {
            return seq;
        }
        String body = seq.substring(3, seq.length() - 1);
        List<String> kept = new ArrayList<>();
        boolean dropped = false;
        for (String p : body.split(";", -1)) {
            if (MOUSE_MODES.contains(p)) {
                dropped = true;
            } else {
                kept.add(p);
            }
        }
        if (!dropped) {
            return seq;
        }
        if (kept.isEmpty()) {
            Log.d("mouse tracking request dropped: " + Log.show(seq));
            return "";
        }
        return "\u001b[?" + String.join(";", kept) + "h";
    }
}
