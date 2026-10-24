package dev.lagless;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowsTerminalProfilesTest {
    @Test void windowsTerminalJsonEscapesBackslashes() {
        String json = WindowsTerminalProfiles.jsonString("C:\\Program Files\\java.exe \"x\"");
        assertEquals("\"C:\\\\Program Files\\\\java.exe \\\"x\\\"\"", json);
    }
}
