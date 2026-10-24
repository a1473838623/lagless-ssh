package dev.lagless;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates a Windows Terminal "fragment": a JSON file that Windows Terminal
 * merges into its settings, adding one profile per host. Fragments live in
 * %LOCALAPPDATA%\Microsoft\Windows Terminal\Fragments\&lt;app&gt;\*.json and
 * take effect the next time the settings are reloaded.
 */
public final class WindowsTerminalProfiles {

    private WindowsTerminalProfiles() {
    }

    public static Path fragmentPath() {
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) {
            local = Path.of(System.getProperty("user.home"), "AppData", "Local").toString();
        }
        return Path.of(local, "Microsoft", "Windows Terminal", "Fragments", "lagless", "hosts.json");
    }

    /** The command that launches lagless for a host, as Windows Terminal should run it. */
    public static String commandLine(String host) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        String jar = jarPath();
        return quote(java.toString()) + " --enable-native-access=ALL-UNNAMED -jar " + quote(jar) + " " + host;
    }

    static String jarPath() {
        try {
            Path p = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(p)) {
                return p.toString();
            }
            if (p.getFileName().toString().equals("classes")) {
                return p.getParent().resolve("lagless-cli-standalone.jar").toString();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return Path.of(System.getProperty("user.dir"), "cli", "target", "lagless-cli-standalone.jar").toString();
    }

    public static String json(List<SshConfigFile.HostBlock> hosts) {
        StringBuilder sb = new StringBuilder();
        sb.append("{").append(nl());
        sb.append("  \"profiles\": [").append(nl());
        for (int i = 0; i < hosts.size(); i++) {
            SshConfigFile.HostBlock h = hosts.get(i);
            sb.append("    {").append(nl());
            sb.append("      \"name\": ").append(jsonString("lagless " + h.name())).append(",").append(nl());
            sb.append("      \"commandline\": ").append(jsonString(commandLine(h.name()))).append(",").append(nl());
            sb.append("      \"closeOnExit\": \"graceful\",").append(nl());
            sb.append("      \"suppressApplicationTitle\": false").append(nl());
            sb.append("    }").append(i + 1 < hosts.size() ? "," : "").append(nl());
        }
        sb.append("  ]").append(nl());
        sb.append("}").append(nl());
        return sb.toString();
    }

    public static Path install(List<SshConfigFile.HostBlock> hosts) throws IOException {
        Path p = fragmentPath();
        Files.createDirectories(p.getParent());
        Files.writeString(p, json(hosts), StandardCharsets.UTF_8);
        return p;
    }

    private static String quote(String s) {
        return s.contains(" ") ? "\"" + s + "\"" : s;
    }

    static String jsonString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    private static String nl() {
        return System.lineSeparator();
    }
}
