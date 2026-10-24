package dev.lagless;

/** Optional local frontends register an entry point without introducing GUI dependencies into the CLI. */
public interface ApplicationLauncher {
    String command();
    void launch() throws Exception;
}
