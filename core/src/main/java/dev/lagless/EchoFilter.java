package dev.lagless;

/**
 * After a locally edited line is sent, the remote shell echoes it back and
 * moves to a new line. The line is already on screen, so the echo must be
 * swallowed. This filter buffers incoming output until it either recognises
 * the echoed line (and drops it, up to and including the newline) or decides
 * the output is something else (and releases everything untouched).
 */
public final class EchoFilter {

    private static final long MAX_WAIT_MS = 1500;
    private static final int MAX_BUFFER = 8192;

    private final StringBuilder buffer = new StringBuilder();
    private String expected;
    private long startedNanos;

    public synchronized void expect(String line) {
        expected = line;
        buffer.setLength(0);
        startedNanos = System.nanoTime();
    }

    public synchronized boolean isActive() {
        return expected != null;
    }

    /**
     * Feeds a chunk. Returns the text that should be shown to the user now
     * (possibly empty while the echo is still being collected).
     */
    public synchronized String feed(String chunk) {
        if (expected == null) {
            return chunk;
        }
        buffer.append(chunk);
        String stripped = AnsiUtil.strip(buffer);
        int nl = stripped.indexOf('\n');
        if (nl >= 0) {
            String firstLine = stripped.substring(0, nl);
            if (firstLine.endsWith(expected) || expected.endsWith(firstLine) && firstLine.length() >= Math.min(expected.length(), 8)) {
                // Echo recognised: drop everything up to and including the raw newline.
                int rawNl = buffer.indexOf("\n");
                String rest = buffer.substring(rawNl + 1);
                release();
                return rest;
            }
            String all = buffer.toString();
            release();
            return all;
        }
        boolean stillPossible = expected.startsWith(stripped) || stripped.endsWith(expected) || expected.endsWith(stripped);
        if (!stillPossible || buffer.length() > MAX_BUFFER || elapsedMs() > MAX_WAIT_MS) {
            String all = buffer.toString();
            release();
            return all;
        }
        return "";
    }

    /** Called periodically; releases the buffer when the echo never arrives. */
    public synchronized String poll() {
        if (expected == null || buffer.length() == 0) {
            if (expected != null && elapsedMs() > MAX_WAIT_MS) {
                release();
            }
            return "";
        }
        if (elapsedMs() > MAX_WAIT_MS) {
            String all = buffer.toString();
            release();
            return all;
        }
        return "";
    }

    private long elapsedMs() {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private void release() {
        expected = null;
        buffer.setLength(0);
    }
}
