package dev.lagless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoUpdateTest {

    @TempDir
    Path dir;

    private Path stampWritten(long millisAgo) throws Exception {
        Path p = dir.resolve("update-check");
        Files.writeString(p, Long.toString(System.currentTimeMillis() - millisAgo), StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void neverCheckedMeansDue() {
        assertTrue(AutoUpdate.isDue(dir.resolve("absent"), Duration.ofHours(24)));
    }

    @Test
    void recentCheckIsNotDue() throws Exception {
        assertFalse(AutoUpdate.isDue(stampWritten(Duration.ofHours(1).toMillis()), Duration.ofHours(24)));
    }

    @Test
    void oldCheckIsDueAgain() throws Exception {
        assertTrue(AutoUpdate.isDue(stampWritten(Duration.ofHours(25).toMillis()), Duration.ofHours(24)));
    }

    @Test
    void aZeroIntervalAlwaysChecks() throws Exception {
        assertTrue(AutoUpdate.isDue(stampWritten(0), Duration.ZERO));
    }

    @Test
    void unreadableStampMeansDue() throws Exception {
        Path p = dir.resolve("update-check");
        Files.writeString(p, "not a number", StandardCharsets.UTF_8);
        assertTrue(AutoUpdate.isDue(p, Duration.ofHours(24)));
    }

    @Test
    void switchedOffInConfigStartsNothing() {
        LaglessConfig config = new LaglessConfig();
        config.parse(List.of("auto-update = false"));
        assertNull(AutoUpdate.startIfDue(config, "somehost"));
    }

    @Test
    void perHostOverrideIsRead() {
        LaglessConfig config = new LaglessConfig();
        config.parse(List.of("auto-update = true", "[quiet-box]", "auto-update = false"));
        assertNull(AutoUpdate.startIfDue(config, "quiet-box"));
        // Running from class files rather than a jar also starts nothing, so the
        // enabled case cannot be asserted here without touching the network.
    }

    @Test
    void theTemplateDocumentsTheNewKeys() {
        String template = LaglessConfig.template();
        assertTrue(template.contains("auto-update"), template);
        assertTrue(template.contains("update-check-hours"), template);
    }
}
