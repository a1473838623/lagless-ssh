package dev.lagless;

import java.nio.file.Files;
import java.nio.file.Path;

/** Where this copy of lagless lives on disk and what version it is. */
public final class Installation {
    /**
     * The program {@code name} on PATH, or null. Optional helpers (gh, gsettings, ...) are looked
     * up here before starting them: on Windows a Native Image EXE crashed in the C runtime while
     * reporting that a program to start does not exist, instead of throwing an exception.
     */
    public static java.nio.file.Path findOnPath(String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank() || name.isBlank()) return null;
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        java.util.List<String> suffixes = new java.util.ArrayList<>();
        suffixes.add("");
        if (windows) {
            String ext = System.getenv("PATHEXT");
            for (String e : (ext == null || ext.isBlank() ? ".COM;.EXE;.BAT;.CMD" : ext).split(";")) if (!e.isBlank()) suffixes.add(e.toLowerCase(java.util.Locale.ROOT));
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String suffix : suffixes) {
                try {
                    java.nio.file.Path candidate = java.nio.file.Path.of(dir.replace("\"", ""), name + suffix);
                    if (java.nio.file.Files.isRegularFile(candidate) && (windows || java.nio.file.Files.isExecutable(candidate))) return candidate;
                } catch (RuntimeException invalid) {
                    // A malformed PATH entry is skipped.
                }
            }
        }
        return null;
    }


    private Installation() {
    }

    /** Version from the jar manifest, or "dev" when running from classes. */
    public static String version() {
        if (isNative()) return System.getProperty("lagless.build.version", "dev");
        String v = Main.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? "dev" : v;
    }

    /** Release asset selection is tied to the installed edition, never a GUI fallback. */
    public static String distribution() {
        if (isNative()) return "desktop-native";
        Path jar = runningJar();
        if (jar == null) return "terminal";
        try { return distribution(jar); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot read installation edition", e); }
    }

    public static String distribution(Path jar) throws java.io.IOException {
        try (java.util.jar.JarFile archive = new java.util.jar.JarFile(jar.toFile())) {
            String value = archive.getManifest() == null ? null
                    : archive.getManifest().getMainAttributes().getValue("Lagless-Distribution");
            boolean containsDesktop = archive.getEntry("dev/lagless/desktop/DesktopApp.class") != null;
            // Historical CLI-only and early complete JARs predate the edition attribute.
            if (value == null) return containsDesktop ? "desktop" : "terminal";
            if (!value.equals("terminal") && !value.equals("desktop")) throw new java.io.IOException("Unknown Lagless edition: " + value);
            if (value.equals("terminal") && containsDesktop) throw new java.io.IOException("Terminal artifact unexpectedly contains the desktop client");
            return value;
        }
    }

    public static String updateAssetName(String edition) {
        return switch (edition) {
            case "terminal" -> "lagless-terminal.jar";
            case "desktop" -> "lagless.jar";
            default -> throw new IllegalArgumentException("Unknown Lagless edition: " + edition);
        };
    }

    /**
     * The running jar, or null when lagless was started from a directory of
     * class files (a development run), where replacing it makes no sense.
     */
    public static Path runningJar() {
        if (isNative()) return null;
        try {
            Path p = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Directory holding the jar, falling back to the working directory. */
    public static Path home() {
        if (isNative()) return Path.of(System.getProperty("lagless.native.home"));
        Path jar = runningJar();
        return jar != null ? jar.getParent() : Path.of(System.getProperty("user.dir"));
    }

    public static boolean isNative() { return "true".equals(System.getProperty("lagless.native.image")); }

    /** True when a bundled Java runtime sits next to the jar. */
    public static boolean hasBundledRuntime() {
        return Files.isDirectory(home().resolve("runtime"));
    }

    /**
     * Removes the previous jar left behind by an update. It stays locked while
     * the old process runs, so the deletion happens on a later start instead.
     */
    public static void cleanUpAfterUpdate() {
        Path jar = runningJar();
        if (jar == null) {
            return;
        }
        try {
            Files.deleteIfExists(jar.resolveSibling(jar.getFileName() + ".old"));
        } catch (Exception e) {
            Log.d("could not remove the previous jar: " + e);
        }
    }
}
