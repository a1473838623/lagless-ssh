package dev.lagless;

/**
 * Helpers for dealing with terminal escape sequences in the byte stream
 * coming from the remote side.
 */
public final class AnsiUtil {

    private AnsiUtil() {
    }

    /**
     * Removes every escape sequence (CSI, OSC, single-char ESC sequences) and
     * carriage returns from the text. Backspaces delete the previous character.
     */
    public static String strip(CharSequence s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == 0x1B) {
                i = skipEscape(s, i);
                continue;
            }
            if (c == '\r') {
                i++;
                continue;
            }
            if (c == '\b') {
                if (out.length() > 0) {
                    out.setLength(out.length() - 1);
                }
                i++;
                continue;
            }
            if (c == 0x07 || c == 0x0F || c == 0x0E) {
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Keeps only SGR (colour/attribute) sequences and printable text. Used to
     * turn the remote prompt line into something JLine can render as a prompt.
     */
    public static String keepSgrOnly(CharSequence s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == 0x1B) {
                int end = skipEscape(s, i);
                if (isSgr(s, i, end)) {
                    out.append(s, i, end);
                }
                i = end;
                continue;
            }
            if (c == '\r' || c == '\n' || c == 0x07) {
                i++;
                continue;
            }
            if (c == '\b') {
                if (out.length() > 0) {
                    out.setLength(out.length() - 1);
                }
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isSgr(CharSequence s, int start, int end) {
        if(end-start<3||s.charAt(start+1)!='['||s.charAt(end-1)!='m')return false;
        for(int i=start+2;i<end-1;i++){
            char c=s.charAt(i);
            if(c!=';'&&c!=':'&&(c<'0'||c>'9'))return false;
        }
        return true;
    }
    public static boolean isSgrSequence(CharSequence sequence){return isSgr(sequence,0,sequence.length());}

    /**
     * Returns the index just after the escape sequence that starts at
     * {@code start} (which must point at ESC). Unterminated sequences run to
     * the end of the input.
     */
    public static int skipEscape(CharSequence s, int start) {
        int n = s.length();
        int i = start + 1;
        if (i >= n) {
            return n;
        }
        char c = s.charAt(i);
        if (c == '[') {
            // CSI: parameters 0x30-0x3F, intermediates 0x20-0x2F, final 0x40-0x7E
            i++;
            while (i < n) {
                char d = s.charAt(i);
                if (d >= 0x40 && d <= 0x7E) {
                    return i + 1;
                }
                i++;
            }
            return n;
        }
        if (c == ']' || c == 'P' || c == '^' || c == '_') {
            // OSC / DCS / PM / APC: terminated by BEL or ST (ESC \)
            i++;
            while (i < n) {
                char d = s.charAt(i);
                if (d == 0x07) {
                    return i + 1;
                }
                if (d == 0x1B && i + 1 < n && s.charAt(i + 1) == '\\') {
                    return i + 2;
                }
                i++;
            }
            return n;
        }
        if (c == '(' || c == ')' || c == '*' || c == '+' || c == '#' || c == '%') {
            return Math.min(n, i + 2);
        }
        return i + 1;
    }
}
