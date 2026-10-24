package dev.lagless;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.ConfigRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.OpenSSHConfig;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.Proxy;
import com.jcraft.jsch.SocketFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Thin wrapper over JSch: one authenticated session, one interactive shell
 * channel with a pty, and short-lived exec channels for helper commands.
 */
public final class SshConnection implements AutoCloseable {
    private static final java.util.Map<Path, com.jcraft.jsch.HostKeyRepository> HOST_KEYS = new java.util.HashMap<>();

    /** Answers interactive questions (password, passphrase, host key) through the terminal. */
    public interface Prompter {
        String askSecret(String prompt);

        boolean askYesNo(String question);

        void show(String message);
    }

    private final Session session;
    private Channel shell;
    private SshConnection ownedJump;
    private InputStream shellIn;
    private OutputStream shellOut;

    private SshConnection(Session session) {
        this.session = session;
    }

    public static SshConnection connect(Options o, Prompter prompter) throws JSchException, IOException {
        return connectRoute(o, prompter, new java.util.HashSet<>());
    }

    private static SshConnection connectRoute(Options o, Prompter prompter, java.util.Set<String> visited) throws JSchException, IOException {
        String jump = o.jump;
        Path config = Paths.get(System.getProperty("user.home"), ".ssh", "config");
        if (jump == null && Files.isRegularFile(config)) jump = OpenSSHConfig.parseFile(config.toString()).getConfig(o.host).getValue("ProxyJump");
        if (jump == null || jump.isBlank() || jump.equalsIgnoreCase("none")) return connectVia(o, prompter, null);
        if (visited.size() >= 8 || !visited.add(o.host)) throw new IOException("ProxyJump cycle or more than 8 hops: " + o.host);
        String[] hops = jump.split(",");
        Options upstream = Options.parse(new String[]{hops[hops.length - 1].trim()});
        if (hops.length > 1) upstream.jump = String.join(",", java.util.Arrays.copyOf(hops, hops.length - 1));
        SshConnection parent = connectRoute(upstream, prompter, visited);
        try {
            SshConnection connection = connectVia(o, prompter, parent);
            connection.ownedJump = parent;
            return connection;
        } catch (JSchException | IOException | RuntimeException e) { parent.close(); throw e; }
    }

    /** Nested SSH over direct-tcpip; host verification and authentication remain end to end. */
    public static SshConnection connectVia(Options o, Prompter prompter, SshConnection upstream) throws JSchException, IOException {
        JSch jsch = new JSch();
        Path sshDir = Paths.get(System.getProperty("user.home"), ".ssh");
        Path config = sshDir.resolve("config");
        if (Files.isRegularFile(config)) {
            try {
                jsch.setConfigRepository(tolerant(OpenSSHConfig.parseFile(config.toString()), prompter));
            } catch (IOException e) {
                prompter.show("lagless: ignoring unreadable " + config + ": " + e.getMessage());
            }
        }
        Path knownHosts = sshDir.resolve("known_hosts");
        Files.createDirectories(sshDir);
        // Give JSch a file even on first launch so accepted host keys survive restart.
        try (OutputStream ignored = Files.newOutputStream(knownHosts, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) { }
        // Share the repository in one desktop process: parallel new connections
        // must not overwrite each other's newly accepted host keys.
        synchronized (HOST_KEYS) {
            var repository = HOST_KEYS.get(knownHosts.toAbsolutePath().normalize());
            if (repository == null) {
                jsch.setKnownHosts(knownHosts.toString()); repository = jsch.getHostKeyRepository();
                HOST_KEYS.put(knownHosts.toAbsolutePath().normalize(), repository);
            } else jsch.setHostKeyRepository(repository);
        }
        if (o.identity != null) {
            jsch.addIdentity(expandHome(o.identity));
        } else {
            for (String name : new String[] {"id_ed25519", "id_ecdsa", "id_rsa"}) {
                Path key = sshDir.resolve(name);
                if (Files.isRegularFile(key)) {
                    jsch.addIdentity(key.toString());
                }
            }
        }

        String user = o.user;
        Session session = jsch.getSession(user, o.host, o.port > 0 ? o.port : 22);
        if (session.getUserName() == null || session.getUserName().isEmpty()) {
            session = jsch.getSession(System.getProperty("user.name"), o.host, o.port > 0 ? o.port : 22);
        }
        session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password");
        session.setConfig("StrictHostKeyChecking", "ask");
        if (upstream != null) session.setProxy(new JumpProxy(upstream.session));
        session.setServerAliveInterval(15_000);
        session.setServerAliveCountMax(4);
        session.setUserInfo(new PrompterUserInfo(prompter));
        session.setDaemonThread(true);
        Log.d("connecting to " + session.getUserName() + "@" + session.getHost() + ":" + session.getPort());
        try {
            session.connect(30_000);
            return new SshConnection(session);
        } catch (JSchException | RuntimeException e) { session.disconnect(); throw e; }
    }

    public String getUserName() {
        return session.getUserName();
    }

    public String getHost() {
        return session.getHost();
    }

    public void openShell(String term, int cols, int rows) throws JSchException, IOException {
        openShell(term, cols, rows, null);
    }

    public void openShell(String term, int cols, int rows, String resumeId) throws JSchException, IOException {
        if (resumeId == null) {
            ChannelShell ch = (ChannelShell) session.openChannel("shell");
            ch.setPtyType(term, cols, rows, 0, 0); ch.setPty(true); shell = ch;
        } else {
            if (!resumeId.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid resume ID");
            ChannelExec ch = (ChannelExec) session.openChannel("exec");
            ch.setPtyType(term, cols, rows, 0, 0); ch.setPty(true);
            ch.setCommand("if command -v tmux >/dev/null 2>&1; then exec tmux -u new-session -A -s " + resumeId
                    + "; else printf '\\r\\n[lagless] tmux unavailable; reconnect starts a NEW shell.\\r\\n'; exec \"${SHELL:-/bin/sh}\" -l; fi");
            shell = ch;
        }
        shellIn = shell.getInputStream();
        shellOut = shell.getOutputStream();
        shell.connect(15_000);
    }

    public InputStream shellInput() {
        return shellIn;
    }

    public OutputStream shellOutput() {
        return shellOut;
    }

    public boolean isShellOpen() {
        return shell != null && shell.isConnected() && !shell.isClosed();
    }

    public boolean isConnected() { return session.isConnected(); }
    public int shellExitStatus() { return shell == null ? -1 : shell.getExitStatus(); }

    public void resize(int cols, int rows) {
        if (shell != null && shell.isConnected()) {
            if (shell instanceof ChannelShell ch) ch.setPtySize(cols, rows, 0, 0);
            else if (shell instanceof ChannelExec ch) ch.setPtySize(cols, rows, 0, 0);
        }
    }

    /**
     * Runs a command on a fresh exec channel and returns its stdout, or null
     * when the channel could not be opened or the command failed to start.
     * Never throws; used only for best-effort helpers.
     */
    public String exec(String command, long timeoutMs) {
        ChannelExec ch = null;
        try {
            ch = (ChannelExec) session.openChannel("exec");
            ch.setCommand(command);
            ch.setInputStream(null);
            ch.setErrStream(OutputStream.nullOutputStream());
            InputStream in = ch.getInputStream();
            ch.connect((int) timeoutMs);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            while (true) {
                while (in.available() > 0) {
                    int n = in.read(buf);
                    if (n < 0) {
                        break;
                    }
                    bos.write(buf, 0, n);
                }
                if (ch.isClosed()) {
                    while (in.available() > 0) {
                        int n = in.read(buf);
                        if (n < 0) {
                            break;
                        }
                        bos.write(buf, 0, n);
                    }
                    break;
                }
                if (System.nanoTime() > deadline) {
                    Log.d("exec timeout: " + command);
                    return null;
                }
                Thread.sleep(10);
            }
            return bos.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.d("exec failed: " + command + " -> " + e);
            return null;
        } finally {
            if (ch != null) {
                ch.disconnect();
            }
        }
    }

    /** Opens an SFTP channel on this session; the caller disconnects it. */
    public ChannelSftp openSftp() throws JSchException {
        ChannelSftp ch = (ChannelSftp) session.openChannel("sftp");
        ch.connect(15_000);
        return ch;
    }

    @Override
    public void close() {
        if (shell != null) {
            shell.disconnect();
        }
        session.disconnect();
        if (ownedJump != null) ownedJump.close();
    }

    private static final class JumpProxy implements Proxy {
        private final Session upstream;
        private Channel channel;
        private InputStream input;
        private OutputStream output;
        JumpProxy(Session upstream) { this.upstream = upstream; }
        @Override public void connect(SocketFactory factory, String host, int port, int timeout) throws Exception {
            channel = upstream.getStreamForwarder(host, port);
            input = channel.getInputStream(); output = channel.getOutputStream(); channel.connect(timeout);
        }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public java.net.Socket getSocket() { return null; }
        @Override public void close() { if (channel != null) channel.disconnect(); }
    }

    /**
     * OpenSSH skips IdentityFile entries that do not exist; JSch aborts the
     * connection instead. Wrap the repository so missing files are dropped
     * with a warning, like ssh does.
     */
    private static ConfigRepository tolerant(ConfigRepository base, Prompter prompter) {
        return host -> {
            ConfigRepository.Config c = base.getConfig(host);
            if (c == null) {
                return null;
            }
            return new ConfigRepository.Config() {
                @Override
                public String getHostname() {
                    return c.getHostname();
                }

                @Override
                public String getUser() {
                    return c.getUser();
                }

                @Override
                public int getPort() {
                    return c.getPort();
                }

                @Override
                public String getValue(String key) {
                    if (key.equalsIgnoreCase("IdentityFile")) {
                        String[] v = getValues(key);
                        return v == null || v.length == 0 ? null : v[0];
                    }
                    return c.getValue(key);
                }

                @Override
                public String[] getValues(String key) {
                    String[] v = c.getValues(key);
                    if (v == null || !key.equalsIgnoreCase("IdentityFile")) {
                        return v;
                    }
                    List<String> kept = new ArrayList<>();
                    for (String f : v) {
                        String path = expandHome(f);
                        if (Files.isRegularFile(Paths.get(path))) {
                            kept.add(path);
                        } else {
                            prompter.show("lagless: ~/.ssh/config IdentityFile not found, skipping: " + f);
                        }
                    }
                    return kept.toArray(new String[0]);
                }
            };
        };
    }

    private static String expandHome(String p) {
        if (p.startsWith("~/") || p.startsWith("~\\")) {
            return System.getProperty("user.home") + p.substring(1);
        }
        return p;
    }

    private static final class PrompterUserInfo implements UserInfo, UIKeyboardInteractive {
        private final Prompter prompter;
        private String password;
        private String passphrase;

        PrompterUserInfo(Prompter prompter) {
            this.prompter = prompter;
        }

        @Override
        public String getPassphrase() {
            return passphrase;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public boolean promptPassword(String message) {
            password = prompter.askSecret(message + ": ");
            return password != null;
        }

        @Override
        public boolean promptPassphrase(String message) {
            passphrase = prompter.askSecret(message + ": ");
            return passphrase != null;
        }

        @Override
        public boolean promptYesNo(String message) {
            return prompter.askYesNo(message);
        }

        @Override
        public void showMessage(String message) {
            prompter.show(message);
        }

        @Override
        public String[] promptKeyboardInteractive(String destination, String name, String instruction,
                                                  String[] prompt, boolean[] echo) {
            if (instruction != null && !instruction.isBlank()) {
                prompter.show(instruction);
            }
            String[] answers = new String[prompt.length];
            for (int i = 0; i < prompt.length; i++) {
                String a = prompter.askSecret(prompt[i]);
                if (a == null) {
                    return null;
                }
                answers[i] = a;
            }
            return answers;
        }
    }

    /** Convenience for tests: adapts a lambda into a Prompter that never answers secrets. */
    public static Prompter nonInteractive(Function<String, Boolean> yesNo) {
        return new Prompter() {
            @Override
            public String askSecret(String prompt) {
                return null;
            }

            @Override
            public boolean askYesNo(String question) {
                return yesNo.apply(question);
            }

            @Override
            public void show(String message) {
            }
        };
    }
}
