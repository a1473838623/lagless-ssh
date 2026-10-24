package dev.lagless;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The non-connecting sub-commands and the interactive host picker. */
public final class Cli {

    private final Terminal terminal;
    private final PrintWriter out;

    public Cli(Terminal terminal) {
        this.terminal = terminal;
        this.out = terminal.writer();
    }

    /** Runs a sub-command; returns the process exit code. */
    public int run(Options o) throws IOException {
        switch (o.command) {
            case "list" -> {
                return list();
            }
            case "add" -> {
                return add(o);
            }
            case "rm" -> {
                return rm(o);
            }
            case "profiles" -> {
                return profiles(o);
            }
            case "config" -> {
                return config();
            }
            case "update" -> {
                return update(o);
            }
            case "version" -> {
                return version();
            }
            default -> {
                out.println("lagless: unknown command " + o.command);
                return 2;
            }
        }
    }

    private int version() {
        out.println("lagless " + Installation.version());
        out.println("edition: " + Installation.distribution());
        java.nio.file.Path jar = Installation.runningJar();
        out.println("jar:     " + (jar == null ? "(running from class files)" : jar));
        out.println("java:    " + System.getProperty("java.version")
                + (Installation.hasBundledRuntime() ? "  (bundled runtime)" : "  (system)"));
        out.flush();
        return 0;
    }

    private int update(Options o) {
        LaglessConfig config;
        try {
            config = LaglessConfig.load(LaglessConfig.defaultPath());
        } catch (IOException e) {
            config = new LaglessConfig();
        }
        Updater updater = new Updater(out, config.get(null, "update-repo"), Updater.findToken(config));
        try {
            return o.check ? updater.check() : updater.update();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.println("lagless: interrupted.");
            return 1;
        } catch (IOException e) {
            out.println("lagless: update failed: " + e.getMessage());
            Log.d("update failed: " + e);
            return 1;
        } finally {
            out.flush();
        }
    }

    private int list() throws IOException {
        SshConfigFile cfg = new SshConfigFile(SshConfigFile.defaultPath()).load();
        List<SshConfigFile.HostBlock> hosts = cfg.concreteHosts();
        if (hosts.isEmpty()) {
            out.println("no hosts in " + SshConfigFile.defaultPath() + "; add one with: lagless add NAME user@host -i KEY");
            out.flush();
            return 0;
        }
        int w = hosts.stream().mapToInt(h -> h.name().length()).max().orElse(4);
        for (SshConfigFile.HostBlock h : hosts) {
            out.printf("%-" + w + "s  %s%n", h.name(), SshConfigFile.describe(h));
        }
        out.flush();
        return 0;
    }

    private int add(Options o) throws IOException {
        // lagless add NAME [user@]host[:port] [-i KEY] -- the destination was
        // parsed into commandArgs, options into the normal fields.
        if (o.commandArgs.size() < 2) {
            out.println("usage: lagless add NAME [user@]host[:port] [-i KEY] [-p PORT] [-l USER]");
            out.flush();
            return 2;
        }
        String name = o.commandArgs.get(0);
        Options dest = Options.parse(new String[] {o.commandArgs.get(1)});
        String user = o.user != null ? o.user : dest.user;
        int port = o.port > 0 ? o.port : dest.port;
        if (o.identity != null) {
            Path key = Path.of(o.identity);
            if (!Files.isRegularFile(key)) {
                out.println("lagless: key file not found: " + key);
                out.flush();
                return 1;
            }
            o.identity = key.toAbsolutePath().toString();
        }
        SshConfigFile cfg = new SshConfigFile(SshConfigFile.defaultPath()).load();
        boolean existed = cfg.find(name) != null;
        cfg.add(name, dest.host, user, port, o.identity);
        cfg.save();
        out.println((existed ? "updated " : "added ") + name + " in " + SshConfigFile.defaultPath());
        out.println("connect with: lagless " + name);
        out.flush();
        return 0;
    }

    private int rm(Options o) throws IOException {
        if (o.commandArgs.size() != 1) {
            out.println("usage: lagless rm NAME");
            out.flush();
            return 2;
        }
        SshConfigFile cfg = new SshConfigFile(SshConfigFile.defaultPath()).load();
        if (!cfg.remove(o.commandArgs.get(0))) {
            out.println("lagless: no host named " + o.commandArgs.get(0));
            out.flush();
            return 1;
        }
        cfg.save();
        out.println("removed " + o.commandArgs.get(0) + " (backup in config.bak)");
        out.flush();
        return 0;
    }

    private int profiles(Options o) throws IOException {
        SshConfigFile cfg = new SshConfigFile(SshConfigFile.defaultPath()).load();
        List<SshConfigFile.HostBlock> hosts = cfg.concreteHosts();
        if (hosts.isEmpty()) {
            out.println("no hosts in " + SshConfigFile.defaultPath());
            out.flush();
            return 1;
        }
        boolean install = !o.commandArgs.isEmpty() && o.commandArgs.get(0).equals("install");
        if (install) {
            Path p = WindowsTerminalProfiles.install(hosts);
            out.println("wrote " + hosts.size() + " profiles to " + p);
            out.println("Windows Terminal picks them up on its next settings reload (reopen the dropdown, or restart it).");
        } else {
            out.print(WindowsTerminalProfiles.json(hosts));
            out.println();
            out.println("# run 'lagless profiles install' to write this to " + WindowsTerminalProfiles.fragmentPath());
        }
        out.flush();
        return 0;
    }

    private int config() throws IOException {
        Path p = LaglessConfig.defaultPath();
        if (!Files.isRegularFile(p)) {
            Files.createDirectories(p.getParent());
            Files.writeString(p, LaglessConfig.template(), StandardCharsets.UTF_8);
            out.println("created " + p);
        } else {
            out.println("# " + p);
        }
        out.print(Files.readString(p, StandardCharsets.UTF_8));
        out.flush();
        return 0;
    }

    /**
     * No destination given: list the hosts from ~/.ssh/config and let the
     * user pick one by number or name. Returns null when nothing was chosen.
     */
    public String pickHost() throws IOException {
        SshConfigFile cfg = new SshConfigFile(SshConfigFile.defaultPath()).load();
        List<SshConfigFile.HostBlock> hosts = cfg.concreteHosts();
        if (hosts.isEmpty()) {
            out.println(Options.usage());
            out.flush();
            return null;
        }
        List<String> names = new ArrayList<>();
        int w = hosts.stream().mapToInt(h -> h.name().length()).max().orElse(4);
        for (int i = 0; i < hosts.size(); i++) {
            SshConfigFile.HostBlock h = hosts.get(i);
            names.add(h.name());
            out.printf("%3d  %-" + w + "s  %s%n", i + 1, h.name(), SshConfigFile.describe(h));
        }
        out.flush();
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        String answer;
        try {
            answer = reader.readLine("host (number, name or user@host): ").trim();
        } catch (Exception e) {
            return null;
        }
        if (answer.isEmpty()) {
            return null;
        }
        if (answer.chars().allMatch(Character::isDigit)) {
            int idx = Integer.parseInt(answer) - 1;
            if (idx >= 0 && idx < names.size()) {
                return names.get(idx);
            }
            out.println("no such entry");
            out.flush();
            return null;
        }
        return answer;
    }
}
