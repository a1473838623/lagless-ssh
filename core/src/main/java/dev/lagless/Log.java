package dev.lagless;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;

/** Tiny opt-in trace log so behaviour on a real link can be inspected afterwards. */
public final class Log {

    private static PrintWriter out;

    private Log() {
    }

    public static synchronized void enable(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        out = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8), true);
    }

    public static synchronized void d(String msg) {
        if (out != null) {
            out.println(LocalTime.now() + " " + msg);
        }
    }

    public static String show(CharSequence s) {
        StringBuilder b = new StringBuilder();
        int n = Math.min(s.length(), 200);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == 0x1B) {
                b.append("\\e");
            } else if (c == '\r') {
                b.append("\\r");
            } else if (c == '\n') {
                b.append("\\n");
            } else if (c < 0x20) {
                b.append(String.format("\\x%02x", (int) c));
            } else {
                b.append(c);
            }
        }
        if (s.length() > n) {
            b.append("...");
        }
        return b.toString();
    }
}
