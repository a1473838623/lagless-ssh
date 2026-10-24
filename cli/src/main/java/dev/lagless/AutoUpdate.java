package dev.lagless;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Looks for a newer release in the background while a session runs and leaves
 * it staged for the launcher, so keeping lagless current needs no commands.
 *
 * <p>The check is skipped when it ran recently, so a busy day of connections
 * costs one request. Nothing is printed until the session ends, and only when
 * something was actually downloaded.
 */
public final class AutoUpdate {

    private static final Duration DEFAULT_INTERVAL = Duration.ofHours(24);

    private final Thread worker;
    private volatile String message;

    private AutoUpdate(Thread worker) {
        this.worker = worker;
    }

    /**
     * Starts a check unless it is switched off or ran recently. Returns null
     * when nothing was started.
     */
    public static AutoUpdate startIfDue(LaglessConfig config, String host) {
        if (!enabled(config, host) || Installation.runningJar() == null) {
            return null;
        }
        Path stamp = stampPath();
        if (!isDue(stamp, interval(config, host))) {
            Log.d("background update check skipped, ran recently");
            return null;
        }
        AutoUpdate[] holder = new AutoUpdate[1];
        Thread t = new Thread(() -> {
            Updater updater = new Updater(new PrintWriter(Writer.nullWriter()),
                    config.get(host, "update-repo"), Updater.findToken(config));
            String note = updater.stageNewerQuietly();
            if (note != null) {
                holder[0].message = note;
            }
            // Only now, having actually finished, does this count as a check.
            // A session that ended mid-download leaves the timestamp alone so
            // the next one picks the work up again instead of waiting a day.
            touch(stamp);
        }, "lagless-autoupdate");
        t.setDaemon(true);
        AutoUpdate self = new AutoUpdate(t);
        holder[0] = self;
        t.start();
        return self;
    }

    /**
     * Gives the download a short moment to finish, then returns the note to
     * show the user, or null. A download still running is simply abandoned:
     * it writes to a temporary name, so nothing half-written is left behind
     * for the launcher, and the check is not recorded so it runs again.
     */
    public String noteAfterSession() {
        try {
            worker.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return message;
    }

    private static boolean enabled(LaglessConfig config, String host) {
        String v = config.get(host, "auto-update");
        return v == null || Boolean.parseBoolean(v);
    }

    private static Duration interval(LaglessConfig config, String host) {
        String v = config.get(host, "update-check-hours");
        if (v == null) {
            return DEFAULT_INTERVAL;
        }
        try {
            return Duration.ofHours(Math.max(0, Long.parseLong(v.trim())));
        } catch (NumberFormatException e) {
            return DEFAULT_INTERVAL;
        }
    }

    static Path stampPath() {
        return Path.of(System.getProperty("user.home"), ".lagless", "update-check-" + Installation.distribution());
    }

    static boolean isDue(Path stamp, Duration interval) {
        if (interval.isZero()) {
            return true;
        }
        try {
            Instant last = Instant.ofEpochMilli(Long.parseLong(Files.readString(stamp, StandardCharsets.UTF_8).trim()));
            return Instant.now().isAfter(last.plus(interval));
        } catch (Exception e) {
            return true;
        }
    }

    private static void touch(Path stamp) {
        try {
            Files.createDirectories(stamp.getParent());
            Files.writeString(stamp, Long.toString(System.currentTimeMillis()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.d("could not record the update check time: " + e);
        }
    }
}
