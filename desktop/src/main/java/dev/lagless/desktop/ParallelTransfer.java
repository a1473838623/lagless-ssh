package dev.lagless.desktop;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import dev.lagless.I18n;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A folder transfer that starts at once instead of measuring first. One SFTP channel walks
 * the tree and queues each file as it is found; the other channels move files meanwhile,
 * and the walker joins them when the walk is done. Small files are latency bound, so a few
 * channels in parallel finish them several times faster than one.
 */
final class ParallelTransfer {
    /**
     * Channels per transfer, the walker included. OpenSSH allows 10 per connection by default;
     * the terminal, the queue's own channel and helper commands need the rest. A server that
     * allows fewer just runs the transfer on the channels it grants.
     */
    static final int CHANNELS = 6;

    /** Where the transfer reports what it found and moved; called from several threads. */
    interface Progress {
        void found(long bytes);
        void moved(long bytes);
        void fileDone();
        void current(String path);
        void walking(boolean walking);
        /** The total size, when known from elsewhere (the server's du for an archive download). */
        default void expect(long bytes) { }
    }

    @FunctionalInterface interface Channels { ChannelSftp open() throws Exception; }

    private record Task(String remote, Path local) { }
    private static final Task END = new Task(null, null);

    private final Channels channels;
    private final SftpTransfers.Throttle throttle;
    private final AtomicBoolean cancelled;
    private final Progress progress;
    private final List<ChannelSftp> opened = new CopyOnWriteArrayList<>();
    private final AtomicBoolean halt = new AtomicBoolean();
    private final AtomicReference<Exception> failure = new AtomicReference<>();
    private final BlockingQueue<Task> tasks = new LinkedBlockingQueue<>();
    /** Download workers wait until the walker has fixed the destination folder. */
    private final CountDownLatch anchored = new CountDownLatch(1);

    ParallelTransfer(Channels channels, long rate, AtomicBoolean cancelled, Progress progress) {
        this.channels = channels;
        this.throttle = new SftpTransfers.Throttle(rate);
        this.cancelled = cancelled;
        this.progress = progress;
    }

    /** Every channel this transfer opened, so a cancel can disconnect them all. */
    List<ChannelSftp> channels() { return opened; }

    void download(String remote, Path local) throws Exception {
        run(walker -> {
            Path destination = walker.anchorDownload(local);
            anchored.countDown();
            SftpATTRS root = opened.getFirst().lstat(remote);
            if (root.isLink()) throw new IOException(I18n.t("不跟随远端符号链接：", "Remote symbolic link not followed: ") + remote);
            if (!root.isDir()) {
                if (!root.isReg()) throw new IOException(I18n.t("不是普通远端文件：", "Not a regular remote file: ") + remote);
                progress.found(root.getSize());
                tasks.put(new Task(remote, destination));
                return destination;
            }
            walkRemote(walker, remote, destination);
            return destination;
        }, false);
    }

    void upload(Path local, String remote) throws Exception {
        run(walker -> {
            if (Files.isSymbolicLink(local)) throw new IOException(I18n.t("不跟随符号链接：", "Symbolic link not followed: ") + local);
            if (!Files.isDirectory(local, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(local, LinkOption.NOFOLLOW_LINKS)) throw new IOException(I18n.t("不是普通文件：", "Not a regular file: ") + local);
                progress.found(Files.size(local));
                tasks.put(new Task(remote, local));
                return null;
            }
            walkLocal(walker, local, remote);
            return null;
        }, true);
    }

    @FunctionalInterface private interface Walk { Path walk(SftpTransfers walker) throws Exception; }

    private void run(Walk walk, boolean upload) throws Exception {
        ChannelSftp first = open();
        SftpTransfers walker = transfers(first);
        List<Thread> workers = new ArrayList<>();
        try {
            progress.walking(true);
            // Workers start before the walk, so the first files move while it continues.
            for (int i = 1; i < CHANNELS; i++) {
                Thread worker = new Thread(() -> {
                    SftpTransfers transfer;
                    try {
                        transfer = transfers(open());
                    } catch (Exception refused) {
                        // A server that allows fewer channels still finishes on those it gave.
                        return;
                    }
                    try {
                        if (!upload) { anchored.await(); transfer.shareAnchor(walker.anchor()); }
                        work(transfer, upload);
                    } catch (Exception error) {
                        fail(error);
                    }
                }, "lagless-transfer-" + i);
                worker.setDaemon(true);
                workers.add(worker);
                worker.start();
            }
            try {
                walk.walk(walker);
            } finally {
                anchored.countDown();
                progress.walking(false);
                tasks.put(END);
            }
            work(walker, upload);
        } catch (Exception error) {
            fail(error);
        } finally {
            for (Thread worker : workers) worker.join();
            for (ChannelSftp channel : opened) channel.disconnect();
        }
        Exception error = failure.get();
        if (error != null) throw error;
        if (cancelled.get()) throw new InterruptedIOException(I18n.t("传输已取消", "Transfer cancelled"));
    }

    private void work(SftpTransfers transfer, boolean upload) throws Exception {
        while (!halt.get() && !cancelled.get()) {
            Task task = tasks.poll(200, TimeUnit.MILLISECONDS);
            if (task == null) continue;
            if (task == END) { tasks.put(END); return; }
            try {
                if (upload) transfer.uploadFile(task.local(), task.remote());
                else transfer.downloadFile(task.remote(), task.local());
            } catch (Exception error) {
                fail(error);
                return;
            }
        }
    }

    private void walkRemote(SftpTransfers walker, String root, Path destination) throws Exception {
        ChannelSftp channel = opened.getFirst();
        Deque<Object[]> pending = new ArrayDeque<>();
        pending.add(new Object[] {root, destination, 0});
        int entries = 0;
        while (!pending.isEmpty()) {
            Object[] folder = pending.poll();
            String remote = (String) folder[0]; Path local = (Path) folder[1]; int depth = (Integer) folder[2];
            limits(depth, ++entries);
            progress.current(remote);
            walker.localFolder(local);
            for (var item : channel.ls(remote)) {
                String name = item.getFilename();
                if (name.equals(".") || name.equals("..")) continue;
                SftpTransfers.validName(name);
                String child = SftpTransfers.child(remote, name);
                SftpATTRS a = item.getAttrs();
                if (a.isLink()) throw new IOException(I18n.t("不跟随远端符号链接：", "Remote symbolic link not followed: ") + child);
                if (a.isDir()) pending.add(new Object[] {child, local.resolve(name), depth + 1});
                else if (a.isReg()) { limits(depth + 1, ++entries); progress.found(a.getSize()); tasks.put(new Task(child, local.resolve(name))); }
                else throw new IOException(I18n.t("不是普通远端文件：", "Not a regular remote file: ") + child);
            }
        }
    }

    private void walkLocal(SftpTransfers walker, Path root, String destination) throws Exception {
        Deque<Object[]> pending = new ArrayDeque<>();
        pending.add(new Object[] {root, destination, 0});
        int entries = 0;
        while (!pending.isEmpty()) {
            Object[] folder = pending.poll();
            Path local = (Path) folder[0]; String remote = (String) folder[1]; int depth = (Integer) folder[2];
            limits(depth, ++entries);
            // The folder exists before any file inside it is queued.
            walker.remoteFolder(remote);
            try (var children = Files.newDirectoryStream(local)) {
                for (Path item : children) {
                    String child = SftpTransfers.child(remote, item.getFileName().toString());
                    if (Files.isSymbolicLink(item)) throw new IOException(I18n.t("不跟随符号链接：", "Symbolic link not followed: ") + item);
                    if (Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS)) pending.add(new Object[] {item, child, depth + 1});
                    else if (Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS)) { limits(depth + 1, ++entries); progress.found(Files.size(item)); tasks.put(new Task(child, item)); }
                    else throw new IOException(I18n.t("不是普通文件：", "Not a regular file: ") + item);
                }
            }
        }
    }

    private void limits(int depth, int entries) throws IOException {
        if (halt.get() || cancelled.get()) throw new InterruptedIOException(I18n.t("传输已取消", "Transfer cancelled"));
        if (depth > 64 || entries > 100000) throw new IOException(I18n.t("目录超过 64 层或 100000 项，请分批传输。", "Folder exceeds 64 levels or 100000 items; transfer in batches."));
    }

    private ChannelSftp open() throws Exception {
        if (halt.get() || cancelled.get()) throw new InterruptedIOException(I18n.t("传输已取消", "Transfer cancelled"));
        ChannelSftp channel = channels.open();
        opened.add(channel);
        return channel;
    }

    private SftpTransfers transfers(ChannelSftp channel) throws Exception {
        return new SftpTransfers(channel, throttle, cancelled, n -> { }).showing(progress::current)
                .haltWith(halt).onBytes(progress::moved).onFileDone(progress::fileDone);
    }

    /** Keeps the first real error; the interruptions it causes in other channels are not reported. */
    private void fail(Exception error) {
        boolean echo = error instanceof InterruptedIOException && halt.get();
        if (!echo) failure.compareAndSet(null, error);
        halt.set(true);
    }
}
