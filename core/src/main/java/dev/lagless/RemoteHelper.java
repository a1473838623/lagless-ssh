package dev.lagless;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Everything that needs a second channel to the remote host: command names,
 * directory listings for path completion, the working directory of the
 * interactive shell and the remote history. All network work happens on a
 * background thread; {@link #complete} only ever reads caches, so pressing
 * Tab never blocks. When a listing is missing it is requested and the next
 * Tab uses it.
 */
public final class RemoteHelper implements Completer, AutoCloseable {

    private record Entry(String name, boolean dir) {
    }

    private record Listing(List<Entry> entries, long fetchedNanos) {
    }

    private static final long LISTING_TTL_NANOS = TimeUnit.SECONDS.toNanos(20);

    private final SshConnection conn;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lagless-helper");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Listing> listings = new ConcurrentHashMap<>();
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();
    private volatile List<String> commands = Collections.emptyList();
    private volatile String home;
    private volatile String shellPid;
    private volatile String cwd;
    private volatile boolean available = true;

    public RemoteHelper(SshConnection conn) {
        this.conn = conn;
    }

    /** Kicks off discovery. Returns immediately. */
    public void start() {
        exec.submit(() -> {
            String h = run("printf %s \"$HOME\"", 8000);
            if (h == null) {
                available = false;
                Log.d("helper: exec channel unavailable, completion disabled");
                return;
            }
            home = h.trim();
            discoverShell();
            refreshCwdNow();
            String out = run("compgen -c 2>/dev/null | sort -u", 15000);
            if (out != null) {
                List<String> list = new ArrayList<>();
                for (String s : out.split("\n")) {
                    s = s.trim();
                    if (!s.isEmpty() && s.chars().allMatch(c -> c > 0x20 && c < 0x7F)) {
                        list.add(s);
                    }
                }
                commands = list;
                Log.d("helper: " + list.size() + " commands");
            }
            if (cwd != null) {
                fetchListing(cwd);
            }
        });
    }

    public boolean isAvailable() {
        return available;
    }

    public String cwd() {
        return cwd;
    }

    /** Called every time the shell shows a prompt: refresh cwd and prefetch its listing. */
    public void onPromptShown() {
        if (!available) {
            return;
        }
        exec.submit(() -> {
            refreshCwdNow();
            if (cwd != null) {
                fetchListing(cwd);
            }
        });
    }

    /** Fetches the tail of the remote shell history (best effort, may be empty). */
    public List<String> importHistory() {
        String out = run("tail -n 500 \"$HOME/.bash_history\" 2>/dev/null; "
                + "tail -n 500 \"$HOME/.zsh_history\" 2>/dev/null | sed -e 's/^: [0-9]*:[0-9]*;//'", 10000);
        List<String> lines = new ArrayList<>();
        if (out == null) {
            return lines;
        }
        for (String s : out.split("\n")) {
            if (!s.isBlank() && !s.startsWith("#")) {
                lines.add(s.stripTrailing());
            }
        }
        return lines;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (!available) {
            return;
        }
        String word = line.word().substring(0, line.wordCursor());
        boolean commandPosition = line.wordIndex() == 0;
        if (commandPosition && !word.contains("/") && !word.startsWith("~") && !word.startsWith(".")) {
            for (String c : commands) {
                if (c.startsWith(word)) {
                    candidates.add(new Candidate(c, c, null, null, null, null, true));
                }
            }
            return;
        }
        int slash = word.lastIndexOf('/');
        String dirPart = slash >= 0 ? word.substring(0, slash + 1) : "";
        String base = word.substring(slash + 1);
        String dirKey = resolveDir(dirPart);
        if (dirKey == null) {
            return;
        }
        Listing l = listings.get(dirKey);
        if (l == null || System.nanoTime() - l.fetchedNanos() > LISTING_TTL_NANOS) {
            requestListing(dirKey);
            if (l == null) {
                return;
            }
        }
        boolean showHidden = base.startsWith(".");
        for (Entry e : l.entries()) {
            if (!e.name().startsWith(base)) {
                continue;
            }
            if (!showHidden && e.name().startsWith(".")) {
                continue;
            }
            String value = dirPart + e.name() + (e.dir() ? "/" : "");
            candidates.add(new Candidate(value, e.name() + (e.dir() ? "/" : ""), null, null, null, null, !e.dir()));
        }
    }

    private String resolveDir(String dirPart) {
        String h = home;
        if (dirPart.startsWith("/")) {
            return normalize(dirPart);
        }
        if (dirPart.equals("~") || dirPart.startsWith("~/")) {
            if (h == null) {
                return null;
            }
            return normalize(h + dirPart.substring(1));
        }
        String base = cwd != null ? cwd : h;
        if (base == null) {
            return null;
        }
        return normalize(base + "/" + dirPart);
    }

    private static String normalize(String p) {
        List<String> parts = new ArrayList<>();
        for (String s : p.split("/")) {
            if (s.isEmpty() || s.equals(".")) {
                continue;
            }
            if (s.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
                continue;
            }
            parts.add(s);
        }
        return "/" + String.join("/", parts);
    }

    private void requestListing(String dir) {
        if (inflight.add(dir)) {
            exec.submit(() -> {
                try {
                    fetchListing(dir);
                } finally {
                    inflight.remove(dir);
                }
            });
        }
    }

    private void fetchListing(String dir) {
        String out = run("cd -- " + quote(dir) + " 2>/dev/null && ls -1Ap --color=never 2>/dev/null | head -n 4000", 10000);
        if (out == null) {
            return;
        }
        List<Entry> entries = new ArrayList<>();
        for (String s : out.split("\n")) {
            if (s.isEmpty()) {
                continue;
            }
            boolean dirFlag = s.endsWith("/");
            entries.add(new Entry(dirFlag ? s.substring(0, s.length() - 1) : s, dirFlag));
        }
        listings.put(dir, new Listing(entries, System.nanoTime()));
        Log.d("helper: listing " + dir + " -> " + entries.size() + " entries");
    }

    private void discoverShell() {
        // The interactive shell is the most recently started shell process of
        // this user that owns a pty. Exec channels have no pty, so they never match.
        String out = run("ps -o pid=,tty=,etimes=,comm= -u \"$(id -u)\" 2>/dev/null"
                + " | awk '$2 ~ /^(pts|tty|ttys)/ && $4 ~ /sh$/ {print $3, $1}' | sort -n | head -n 1 | cut -d' ' -f2", 8000);
        if (out != null && !out.isBlank()) {
            shellPid = out.trim();
            Log.d("helper: shell pid " + shellPid);
        } else {
            Log.d("helper: could not find the interactive shell pid; completion is relative to $HOME");
        }
    }

    private void refreshCwdNow() {
        if (shellPid == null) {
            cwd = home;
            return;
        }
        String out = run("readlink /proc/" + shellPid + "/cwd 2>/dev/null || pwdx " + shellPid + " 2>/dev/null | cut -d' ' -f2-", 8000);
        if (out != null && !out.isBlank()) {
            String c = out.trim();
            if (!c.equals(cwd)) {
                Log.d("helper: cwd " + c);
            }
            cwd = c;
        }
    }

    private String run(String cmd, long timeoutMs) {
        return conn.exec(cmd, timeoutMs);
    }

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    @Override
    public void close() {
        exec.shutdownNow();
    }
}
