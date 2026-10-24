package dev.lagless;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ~/.lagless/config: lagless-specific settings that do not belong in
 * ~/.ssh/config. INI style: keys before any section are defaults, a
 * [host] section overrides them for that host alias or address.
 *
 * <pre>
 * quiet-ms = 120
 * completion = true
 * remote-history = true
 * predict = true
 *
 * [192.0.2.47]
 * prompt-regex = \$ $
 * quiet-ms = 200
 * </pre>
 */
public final class LaglessConfig {

    private final Map<String, String> defaults = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> hosts = new LinkedHashMap<>();

    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"), ".lagless", "config");
    }

    public static LaglessConfig load(Path file) throws IOException {
        LaglessConfig c = new LaglessConfig();
        if (!Files.isRegularFile(file)) {
            return c;
        }
        c.parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        return c;
    }

    void parse(List<String> lines) {
        Map<String, String> current = defaults;
        for (String raw : lines) {
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith(";")) {
                continue;
            }
            if (t.startsWith("[") && t.endsWith("]")) {
                current = hosts.computeIfAbsent(t.substring(1, t.length() - 1).trim(), k -> new LinkedHashMap<>());
                continue;
            }
            int eq = t.indexOf('=');
            if (eq > 0) {
                current.put(t.substring(0, eq).trim().toLowerCase(), t.substring(eq + 1).trim());
            }
        }
    }

    public String get(String host, String key) {
        Map<String, String> h = host == null ? null : hosts.get(host);
        if (h != null && h.containsKey(key)) {
            return h.get(key);
        }
        return defaults.get(key);
    }

    /** Fills in options the command line did not set explicitly. */
    public void applyTo(Options o, String host) {
        String v = get(host, "quiet-ms");
        if (v != null && !o.quietMsSet) {
            o.quietMs = Integer.parseInt(v);
        }
        v = get(host, "prompt-regex");
        if (v != null && o.promptRegex == null) {
            o.promptRegex = v;
        }
        v = get(host, "completion");
        if (v != null && o.completion) {
            o.completion = Boolean.parseBoolean(v);
        }
        v = get(host, "remote-history");
        if (v != null && o.remoteHistory) {
            o.remoteHistory = Boolean.parseBoolean(v);
        }
        v = get(host, "local-line");
        if (v != null && o.localLine) {
            o.localLine = Boolean.parseBoolean(v);
        }
        v = get(host, "mouse");
        if (v != null && !o.appMouse) {
            o.appMouse = v.trim().equalsIgnoreCase("app");
        }
        v = get(host, "predict");
        if (v != null && o.predict) {
            o.predict = Boolean.parseBoolean(v);
        }
    }

    public static String template() {
        return String.join(System.lineSeparator(),
                "# lagless settings. Keys before any section are defaults;",
                "# a [host] section overrides them for that alias or address.",
                "quiet-ms = 120",
                "completion = true",
                "remote-history = true",
                "# Draw keys typed into full-screen programs (Claude Code, vim, tmux)",
                "# right away instead of waiting a round trip for the echo.",
                "predict = true",
                "# At Claude Code's empty prompt, edit the line locally and send it on enter.",
                "local-line = true",
                "# select: the mouse selects text for copying (default).",
                "# app: programs that ask for the mouse (Claude Code, vim, htop) get it.",
                "mouse = select",
                "",
                "# Check GitHub for a newer release in the background while a session",
                "# runs, at most once per interval, and stage it for the next start.",
                "auto-update = true",
                "update-check-hours = 24",
                "",
                "# [myserver]",
                "# prompt-regex = \\$ $",
                "");
    }
}
