package dev.lagless;

import java.util.ArrayList;
import java.util.List;

/** Command line options. */
public final class Options {

    static final java.util.Set<String> COMMANDS =
            java.util.Set.of("list", "add", "rm", "profiles", "config", "update", "version", "gui");

    public String host;
    public String user;
    public int port = -1;
    public String identity;
    public String jump;
    public String resumeId;
    /** Isolate concurrent desktop history writers; CLI retains the shared host history. */
    public String historySuffix;
    public String promptRegex;
    public int quietMs = 120;
    public boolean quietMsSet = false;
    public boolean completion = true;
    public boolean remoteHistory = true;
    public boolean debug = false;
    public boolean help = false;
    public boolean rawOnly = false;
    /** Draw keys typed into full-screen programs before the remote echoes them. */
    public boolean predict = true;
    /** Let remote programs take over the mouse (clicks, wheel) instead of keeping it for text selection. */
    public boolean appMouse = false;
    /** Edit the empty input prompt of Claude Code and the like locally, send on enter. */
    public boolean localLine = true;
    /** --record FILE: log remote output, keys and terminal output for diagnosis. */
    public String record;
    /** update: report what is available instead of installing it. */
    public boolean check = false;
    /** Sub-command (list, add, rm, profiles) or null for connect. */
    public String command;
    public java.util.List<String> commandArgs = new java.util.ArrayList<>();

    public static Options parse(String[] args) {
        Options o = new Options();
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h", "--help" -> o.help = true;
                case "-p", "--port" -> o.port = Integer.parseInt(need(args, ++i, a));
                case "-i", "--identity" -> o.identity = need(args, ++i, a);
                case "-l", "--login" -> o.user = need(args, ++i, a);
                case "-J", "--jump" -> o.jump = need(args, ++i, a);
                case "--prompt-regex" -> o.promptRegex = need(args, ++i, a);
                case "--quiet-ms" -> {
                    o.quietMs = Integer.parseInt(need(args, ++i, a));
                    o.quietMsSet = true;
                }
                case "--no-completion" -> o.completion = false;
                case "--no-remote-history" -> o.remoteHistory = false;
                case "--raw" -> o.rawOnly = true;
                case "--no-predict" -> o.predict = false;
                case "--app-mouse" -> o.appMouse = true;
                case "--no-local-line" -> o.localLine = false;
                case "--record" -> o.record = need(args, ++i, a);
                case "--check" -> o.check = true;
                case "--debug" -> o.debug = true;
                default -> {
                    if (a.startsWith("-")) {
                        throw new IllegalArgumentException("unknown option: " + a);
                    }
                    positional.add(a);
                }
            }
        }
        if (!positional.isEmpty() && COMMANDS.contains(positional.get(0))) {
            o.command = positional.get(0);
            o.commandArgs.addAll(positional.subList(1, positional.size()));
            return o;
        }
        if (positional.size() > 1) {
            throw new IllegalArgumentException("only one destination is accepted");
        }
        if (!positional.isEmpty()) {
            String dest = positional.get(0);
            if (dest.startsWith("ssh://")) {
                dest = dest.substring("ssh://".length());
            }
            int at = dest.lastIndexOf('@');
            if (at >= 0) {
                o.user = dest.substring(0, at);
                dest = dest.substring(at + 1);
            }
            int colon = dest.lastIndexOf(':');
            if (colon >= 0 && dest.indexOf(':') == colon) {
                o.port = Integer.parseInt(dest.substring(colon + 1));
                dest = dest.substring(0, colon);
            }
            o.host = dest;
        }
        return o;
    }

    private static String need(String[] args, int i, String opt) {
        if (i >= args.length) {
            throw new IllegalArgumentException("option " + opt + " needs a value");
        }
        return args[i];
    }

    public static String usage() {
        return String.join(System.lineSeparator(),
                "usage: lagless [options] [user@]host[:port]     connect (no host: pick from ~/.ssh/config)",
                "       lagless list                            hosts in ~/.ssh/config",
                "       lagless add NAME [user@]host[:port] [-i KEY]",
                "       lagless rm NAME",
                "       lagless profiles [install]              Windows Terminal profiles, one per host",
                "       lagless config                          show ~/.lagless/config (created if missing)",
                "       lagless update [--check]                install the newest release from GitHub",
                "       lagless version                         show the installed version",
                "       lagless gui                             optional desktop edition only",
                "",
                "  -p, --port PORT          remote port (default 22, or from ~/.ssh/config)",
                "  -i, --identity FILE      private key file (default: ~/.ssh/config, ~/.ssh/id_*)",
                "  -l, --login USER         login name",
                "  -J, --jump HOST[,HOST]   background SSH jump chain (also reads ProxyJump)",
                "      --prompt-regex RE    regex that matches your shell prompt (default: learned)",
                "      --quiet-ms N         output must be quiet for N ms before editing starts (120)",
                "      --no-completion      do not open a second channel for tab completion",
                "      --no-remote-history  do not import the remote shell history",
                "      --no-predict         in full-screen programs (claude, vim, tmux) show keys only once echoed",
                "      --no-local-line      type into Claude Code's prompt key by key instead of editing the line locally",
                "      --app-mouse          let programs (claude, vim, htop) take the mouse; default keeps it for selecting text",
                "      --raw                plain pass-through, no local editing (for comparison)",
                "      --debug              write a trace to ~/.lagless/debug.log",
                "      --record FILE        save remote output, keys and screen output to FILE (for bug reports)",
                "",
                "commands while editing a line (not sent to the remote shell):",
                "  :put LOCAL [REMOTE]      upload over sftp, relative to the remote working directory",
                "  :get REMOTE [LOCAL]      download over sftp",
                "keys while editing a line:",
                "  arrows/home/end          move inside the line, up/down walk the history",
                "  ctrl-r                   search the history",
                "  tab                      complete (async: press again when the listing has arrived)",
                "  ctrl-]                   hand the line to the remote and switch to raw mode",
                "keys in raw mode:",
                "  ctrl-]                   force local editing on the current line",
                "  ctrl-] twice quickly     forget the learned prompt and relearn");
    }
}
