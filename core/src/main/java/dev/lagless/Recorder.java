package dev.lagless;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * {@code --record FILE}: keeps an exact account of a session for diagnosing
 * screen corruption. One line per event, "R" for bytes from the remote, "K"
 * for keys the user typed, "W" for bytes lagless wrote to the terminal, each
 * with a timestamp in microseconds and the bytes in base64.
 *
 * <p>Rendering the R stream and the W stream with any terminal emulator
 * tells whether lagless changed what the remote meant to show, or whether
 * the terminal drew the remote's output differently.
 */
public final class Recorder {

    private static OutputStream out;
    private static long start;

    private Recorder() {
    }

    public static synchronized void start(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        out = new BufferedOutputStream(Files.newOutputStream(file));
        start = System.nanoTime();
    }

    public static boolean on() {
        return out != null;
    }

    public static void remote(String s) {
        event('R', s);
    }

    public static void key(String s) {
        event('K', s);
    }

    public static void wrote(String s) {
        event('W', s);
    }

    private static synchronized void event(char type, String s) {
        if (out == null || s.isEmpty()) {
            return;
        }
        try {
            String line = type + " " + (System.nanoTime() - start) / 1000 + " "
                    + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)) + "\n";
            out.write(line.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException e) {
            out = null;
        }
    }
}
