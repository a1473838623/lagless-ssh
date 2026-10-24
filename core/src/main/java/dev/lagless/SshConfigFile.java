package dev.lagless;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal editor for ~/.ssh/config that preserves everything it does not
 * understand. The file is treated as a preamble followed by "Host" blocks;
 * blocks are added or removed as whole units, other text is copied verbatim.
 */
public final class SshConfigFile {

    /** One "Host name" block with the option lines that follow it. */
    public record HostBlock(String name, List<String> lines) {
        public String option(String key) {
            for (String l : lines) {
                String t = l.trim();
                int sp = indexOfSeparator(t);
                if (sp > 0 && t.substring(0, sp).equalsIgnoreCase(key)) {
                    return t.substring(sp + 1).trim();
                }
            }
            return null;
        }

        public boolean isPattern() {
            return name.contains("*") || name.contains("?") || name.contains("!");
        }
    }

    private final Path file;
    private final List<String> preamble = new ArrayList<>();
    private final List<HostBlock> blocks = new ArrayList<>();

    public SshConfigFile(Path file) {
        this.file = file;
    }

    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"), ".ssh", "config");
    }

    public SshConfigFile load() throws IOException {
        preamble.clear();
        blocks.clear();
        if (!Files.isRegularFile(file)) {
            return this;
        }
        HostBlock current = null;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String t = raw.trim();
            if (t.regionMatches(true, 0, "Host", 0, 4) && t.length() > 4
                    && Character.isWhitespace(t.charAt(4)) && !t.regionMatches(true, 0, "HostName", 0, 8)) {
                String name = t.substring(4).trim();
                int hash = name.indexOf('#');
                if (hash >= 0) {
                    name = name.substring(0, hash).trim();
                }
                current = new HostBlock(name, new ArrayList<>());
                blocks.add(current);
            } else if (current == null) {
                preamble.add(raw);
            } else {
                current.lines().add(raw);
            }
        }
        return this;
    }

    public List<HostBlock> hosts() {
        return blocks;
    }

    /** Concrete (non-wildcard) host entries in file order. */
    public List<HostBlock> concreteHosts() {
        List<HostBlock> out = new ArrayList<>();
        for (HostBlock b : blocks) {
            if (!b.isPattern()) {
                for (String n : b.name().split("\\s+")) {
                    if (!n.isEmpty()) {
                        out.add(new HostBlock(n, b.lines()));
                    }
                }
            }
        }
        return out;
    }

    public HostBlock find(String name) {
        for (HostBlock b : blocks) {
            for (String n : b.name().split("\\s+")) {
                if (n.equals(name)) {
                    return b;
                }
            }
        }
        return null;
    }

    public void add(String name, String hostName, String user, int port, String identity) {
        remove(name);
        List<String> lines = new ArrayList<>();
        lines.add("    HostName " + hostName);
        if (user != null && !user.isBlank()) {
            lines.add("    User " + user);
        }
        if (port > 0 && port != 22) {
            lines.add("    Port " + port);
        }
        if (identity != null && !identity.isBlank()) {
            lines.add("    IdentityFile " + identity.replace('\\', '/'));
        }
        lines.add("");
        blocks.add(new HostBlock(name, lines));
    }

    public boolean remove(String name) {
        return blocks.removeIf(b -> b.name().equals(name));
    }

    public void save() throws IOException {
        Files.createDirectories(file.getParent());
        if (Files.isRegularFile(file)) {
            Files.copy(file, file.resolveSibling("config.bak"), StandardCopyOption.REPLACE_EXISTING);
        }
        List<String> out = new ArrayList<>(preamble);
        if (!out.isEmpty() && !out.get(out.size() - 1).isBlank()) {
            out.add("");
        }
        for (HostBlock b : blocks) {
            out.add("Host " + b.name());
            out.addAll(b.lines());
            if (!b.lines().isEmpty() && !b.lines().get(b.lines().size() - 1).isBlank()) {
                out.add("");
            }
        }
        Files.write(file, out, StandardCharsets.UTF_8);
    }

    private static int indexOfSeparator(String t) {
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == ' ' || c == '\t' || c == '=') {
                return i;
            }
        }
        return -1;
    }

    /** Human-readable "user@host:port" for listings. */
    public static String describe(HostBlock b) {
        String hn = b.option("HostName");
        String user = b.option("User");
        String port = b.option("Port");
        StringBuilder s = new StringBuilder();
        if (user != null) {
            s.append(user).append('@');
        }
        s.append(hn != null ? hn : b.name());
        if (port != null && !port.equals("22")) {
            s.append(':').append(port);
        }
        String id = b.option("IdentityFile");
        if (id != null) {
            s.append("  key=").append(id);
        }
        return s.toString().toLowerCase(Locale.ROOT).equals(b.name().toLowerCase(Locale.ROOT)) ? "" : s.toString();
    }
}
