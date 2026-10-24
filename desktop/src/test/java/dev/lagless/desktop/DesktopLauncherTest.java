package dev.lagless.desktop;

import dev.lagless.ApplicationLauncher;
import org.junit.jupiter.api.Test;
import java.util.ServiceLoader;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopLauncherTest {
    @Test void desktopRegistersOptionalEntryPoint() {
        assertTrue(ServiceLoader.load(ApplicationLauncher.class).stream()
                .map(ServiceLoader.Provider::get)
                .anyMatch(launcher -> launcher.command().equals("gui") && launcher instanceof DesktopLauncher));
    }
}
