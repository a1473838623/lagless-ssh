package dev.lagless;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Options o;
        try {
            o = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("lagless: " + e.getMessage());
            System.err.println(Options.usage());
            System.exit(2);
            return;
        }
        if (o.help) {
            System.out.println(Options.usage());
            System.exit(0);
            return;
        }
        if ("gui".equals(o.command)) {
            Installation.cleanUpAfterUpdate();
            ApplicationLauncher launcher = java.util.ServiceLoader.load(ApplicationLauncher.class).stream()
                    .map(java.util.ServiceLoader.Provider::get)
                    .filter(candidate -> candidate.command().equals("gui")).findFirst().orElse(null);
            if (launcher == null) {
                System.err.println("lagless: this is the terminal-only edition. SSH works without the desktop client.");
                System.err.println("Install the optional desktop edition to use 'lagless gui'.");
                System.exit(2);
                return;
            }
            launcher.launch();
            return;
        }
        if (o.record != null) {
            Recorder.start(Paths.get(o.record));
        }
        if (o.debug) {
            Log.enable(Paths.get(System.getProperty("user.home"), ".lagless", "debug.log"));
        }

        Installation.cleanUpAfterUpdate();
        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .encoding(StandardCharsets.UTF_8)
                .build();
        int code;
        try {
            if (o.command != null) {
                code = new Cli(terminal).run(o);
            } else {
                if (o.host == null) {
                    String picked = new Cli(terminal).pickHost();
                    if (picked == null) {
                        terminal.close();
                        System.exit(2);
                        return;
                    }
                    Options dest = Options.parse(new String[] {picked});
                    o.host = dest.host;
                    if (o.user == null) {
                        o.user = dest.user;
                    }
                    if (o.port <= 0) {
                        o.port = dest.port;
                    }
                }
                LaglessConfig config = LaglessConfig.load(LaglessConfig.defaultPath());
                config.applyTo(o, o.host);
                code = connect(terminal, o, config);
            }
        } finally {
            terminal.close();
        }
        System.exit(code);
    }

    private static int connect(Terminal terminal, Options o, LaglessConfig config) {
        LineReader askReader = LineReaderBuilder.builder().terminal(terminal).build();
        SshConnection.Prompter prompter = new SshConnection.Prompter() {
            @Override
            public String askSecret(String prompt) {
                try {
                    return askReader.readLine(prompt, '*');
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            public boolean askYesNo(String question) {
                try {
                    String a = askReader.readLine(question + " [yes/no] ");
                    return a != null && a.trim().equalsIgnoreCase("yes");
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public void show(String message) {
                terminal.writer().println(message);
                terminal.writer().flush();
            }
        };

        // Look for a newer release while the session runs; the launcher puts
        // it in place the next time lagless starts.
        AutoUpdate autoUpdate = AutoUpdate.startIfDue(config, o.host);
        try (SshConnection conn = SshConnection.connect(o, prompter)) {
            new TerminalSession(terminal, conn, o).run();
            return 0;
        } catch (Exception e) {
            terminal.writer().println();
            terminal.writer().println("lagless: " + errorDetails(e));
            terminal.writer().flush();
            Log.d("fatal: " + e);
            return 1;
        } finally {
            String note = autoUpdate == null ? null : autoUpdate.noteAfterSession();
            if (note != null) {
                terminal.writer().println(note);
                terminal.writer().flush();
            }
        }
    }

    /**
     * JSch sometimes places the useful diagnostic in a nested exception. For
     * example, a rejected private-key file can be reported as the cause of a
     * generic authentication failure. Keep every distinct message so users
     * can act on the real error rather than only the outer wrapper.
     */
    static String errorDetails(Throwable error) {
        StringBuilder details = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> messages = new LinkedHashSet<>();
        for (Throwable current = error; current != null && seen.add(current); current = current.getCause()) {
            String message = current.getMessage();
            if (message == null || message.isBlank()) {
                message = current.getClass().getSimpleName();
            }
            if (!messages.add(message)) {
                continue;
            }
            if (!details.isEmpty()) {
                details.append(System.lineSeparator()).append("lagless: caused by: ");
            }
            details.append(message);
        }
        return details.isEmpty() ? error.getClass().getSimpleName() : details.toString();
    }
}
