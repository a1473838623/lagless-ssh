package dev.lagless;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainTest {

    @Test
    void includesUnderlyingSshDiagnostic() {
        Exception error = new Exception("authentication failed",
                new IOException("Permissions 0644 for private key are too open"));

        assertEquals("authentication failed" + System.lineSeparator()
                        + "lagless: caused by: Permissions 0644 for private key are too open",
                Main.errorDetails(error));
    }

    @Test
    void omitsRepeatedMessagesAndHandlesMissingMessages() {
        Exception error = new Exception("connection failed",
                new IOException("connection failed", new IllegalStateException()));

        assertEquals("connection failed" + System.lineSeparator()
                        + "lagless: caused by: IllegalStateException",
                Main.errorDetails(error));
    }
}
