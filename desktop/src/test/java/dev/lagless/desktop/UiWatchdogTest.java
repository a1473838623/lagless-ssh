package dev.lagless.desktop;

import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class UiWatchdogTest {
    @Test void aBlockedUiThreadIsLoggedWithItsStackAndRecovery() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        UiWatchdog.start(log::add);
        SwingUtilities.invokeAndWait(() -> { try { Thread.sleep(4_500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        for (int i = 0; i < 40 && log.size() < 2; i++) Thread.sleep(100);
        assertEquals(2, log.size(), String.valueOf(log));
        assertTrue(log.get(0).contains("UiWatchdogTest") || log.get(0).contains("not available") || log.get(0).contains("无法"), log.get(0));
        assertTrue(log.get(1).contains("recovered") || log.get(1).contains("恢复"), log.get(1));
    }
}
