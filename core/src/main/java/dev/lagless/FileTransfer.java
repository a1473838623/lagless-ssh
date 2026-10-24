package dev.lagless;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpProgressMonitor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * SFTP upload/download over the existing connection, for the in-session
 * {@code :put} and {@code :get} commands. Runs on its own thread and reports
 * through a message callback so the terminal is never blocked.
 */
public final class FileTransfer {

    private final SshConnection conn;
    private final Consumer<String> report;

    public FileTransfer(SshConnection conn, Consumer<String> report) {
        this.conn = conn;
        this.report = report;
    }

    /** Uploads a local file. Relative remote paths are resolved against remoteCwd. */
    public void put(String local, String remote, String remoteCwd) {
        Path src = Path.of(expandHome(local)).toAbsolutePath();
        if (!Files.isRegularFile(src)) {
            report.accept("lagless: no such local file: " + src);
            return;
        }
        String dest = remote == null || remote.isBlank() ? src.getFileName().toString() : remote;
        Thread t = new Thread(() -> {
            ChannelSftp ch = null;
            try {
                ch = conn.openSftp();
                if (remoteCwd != null) {
                    ch.cd(remoteCwd);
                }
                String target = dest;
                if (isRemoteDir(ch, target)) {
                    target = target.replaceAll("/+$", "") + "/" + src.getFileName();
                }
                long size = Files.size(src);
                report.accept("lagless: uploading " + src.getFileName() + " (" + human(size) + ") -> " + target);
                long start = System.nanoTime();
                ch.put(src.toString(), target, new Progress(size), ChannelSftp.OVERWRITE);
                report.accept("lagless: uploaded " + target + " in " + seconds(start));
            } catch (Exception e) {
                report.accept("lagless: upload failed: " + e.getMessage());
            } finally {
                if (ch != null) {
                    ch.disconnect();
                }
            }
        }, "lagless-sftp-put");
        t.setDaemon(true);
        t.start();
    }

    /** Downloads a remote file. Relative remote paths are resolved against remoteCwd. */
    public void get(String remote, String local, String remoteCwd) {
        String name = remote.substring(remote.lastIndexOf('/') + 1);
        Path dest = local == null || local.isBlank() ? Path.of(name) : Path.of(expandHome(local));
        if (Files.isDirectory(dest)) {
            dest = dest.resolve(name);
        }
        Path finalDest = dest.toAbsolutePath();
        Thread t = new Thread(() -> {
            ChannelSftp ch = null;
            try {
                ch = conn.openSftp();
                if (remoteCwd != null) {
                    ch.cd(remoteCwd);
                }
                SftpATTRS attrs = ch.stat(remote);
                long size = attrs.getSize();
                report.accept("lagless: downloading " + remote + " (" + human(size) + ") -> " + finalDest);
                long start = System.nanoTime();
                ch.get(remote, finalDest.toString(), new Progress(size), ChannelSftp.OVERWRITE);
                report.accept("lagless: downloaded " + finalDest + " in " + seconds(start));
            } catch (Exception e) {
                report.accept("lagless: download failed: " + e.getMessage());
            } finally {
                if (ch != null) {
                    ch.disconnect();
                }
            }
        }, "lagless-sftp-get");
        t.setDaemon(true);
        t.start();
    }

    private static boolean isRemoteDir(ChannelSftp ch, String path) {
        try {
            return ch.stat(path).isDir();
        } catch (Exception e) {
            return false;
        }
    }

    /** Reports every 10 percent for files over 1 MB. */
    private final class Progress implements SftpProgressMonitor {
        private final long total;
        private long done;
        private int lastTenth;

        Progress(long total) {
            this.total = total;
        }

        @Override
        public void init(int op, String src, String dest, long max) {
        }

        @Override
        public boolean count(long count) {
            done += count;
            if (total > 1_000_000 && total > 0) {
                int tenth = (int) (done * 10 / total);
                if (tenth > lastTenth && tenth < 10) {
                    lastTenth = tenth;
                    report.accept("lagless: " + tenth * 10 + "% (" + human(done) + ")");
                }
            }
            return true;
        }

        @Override
        public void end() {
        }
    }

    static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String seconds(long startNanos) {
        return String.format("%.1fs", (System.nanoTime() - startNanos) / 1e9);
    }

    private static String expandHome(String p) {
        if (p.startsWith("~/") || p.startsWith("~\\") || p.equals("~")) {
            return System.getProperty("user.home") + p.substring(1);
        }
        return p;
    }
}
