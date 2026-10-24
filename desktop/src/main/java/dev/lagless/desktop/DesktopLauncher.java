package dev.lagless.desktop;

import dev.lagless.ApplicationLauncher;

public final class DesktopLauncher implements ApplicationLauncher {
    @Override public String command() { return "gui"; }
    @Override public void launch() { DesktopApp.launch(); }
}
