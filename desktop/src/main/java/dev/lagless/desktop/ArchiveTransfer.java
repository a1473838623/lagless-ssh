package dev.lagless.desktop;

import com.jcraft.jsch.ChannelExec;
import dev.lagless.I18n;
import dev.lagless.SshConnection;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Optional folder transfer as one compressed tar stream through an SSH exec channel: much
 * faster than one SFTP request per file when a folder holds many small files. The archive
 * is streamed and unpacked on the fly, so no archive file is ever written on either side
 * and nothing needs cleaning up. Needs {@code tar} and {@code gzip} on the server.
 */
final class ArchiveTransfer {
    private final SshConnection connection;
    private final AtomicBoolean cancelled;
    private final SftpTransfers files;
    private final ParallelTransfer.Progress progress;

    ArchiveTransfer(SshConnection connection, SftpTransfers files, AtomicBoolean cancelled, ParallelTransfer.Progress progress) {
        this.connection = connection;
        this.files = files;
        this.cancelled = cancelled;
        this.progress = progress;
    }

    /** {@code remote} folder into {@code local} (its own name included), unpacked as it arrives. */
    void download(String remote, Path local) throws Exception {
        Path destination = files.anchorDownload(local);
        String parent = parent(remote), name = name(remote);
        SftpTransfers.validName(name);
        // The size comes from the server in the background; the transfer does not wait for it.
        Thread size = new Thread(() -> {
            try {
                String out = connection.exec("cd -- " + quote(parent) + " && (du -sb -- " + quote(name) + " 2>/dev/null || du -sk -- " + quote(name) + " | awk '{print $1*1024}')", 120_000);
                if (out != null && !out.isBlank()) progress.expect(Long.parseLong(out.trim().split("\\s+")[0]));
            } catch (RuntimeException ignored) {
                // Without a total the queue still shows bytes and files moved.
            }
        }, "lagless-archive-size");
        size.setDaemon(true);
        size.start();
        ChannelExec channel = connection.newExec("cd -- " + quote(parent) + " && tar -cf - -- " + quote(name) + " | gzip -1");
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        channel.setErrStream(errors);
        InputStream raw = channel.getInputStream();
        channel.connect(15_000);
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(raw, 1 << 16), 1 << 16)) {
            TarReader tar = new TarReader(in);
            TarReader.Entry entry;
            while ((entry = tar.next()) != null) {
                if (cancelled.get()) throw new InterruptedIOException(I18n.t("传输已取消", "Transfer cancelled"));
                Path target = inside(destination, name, entry.name());
                switch (entry.type()) {
                    case DIRECTORY -> files.localFolder(target);
                    case FILE -> { progress.found(0); files.receiveFile(tar.content(), entry.size(), target, entry.modified()); }
                    case LINK -> throw new IOException(I18n.t("不跟随远端符号链接：", "Remote symbolic link not followed: ") + entry.name());
                    case OTHER -> throw new IOException(I18n.t("不是普通远端文件：", "Not a regular remote file: ") + entry.name());
                }
                tar.skipRest();
            }
            int status = waitForExit(channel);
            if (status > 0) throw new IOException(I18n.t("服务器打包失败：", "Packing on the server failed: ") + failure(channel, errors, "exit " + status));
        } catch (EOFException | java.util.zip.ZipException broken) {
            throw new IOException(I18n.t("服务器未发送完整的归档：", "The server did not send a complete archive: ") + failure(channel, errors, broken.getMessage()));
        } finally {
            channel.disconnect();
        }
    }

    /** {@code local} folder into {@code remote} (its own name included), unpacked by the server. */
    void upload(Path local, String remote) throws Exception {
        String parent = parent(remote), name = name(remote);
        SftpTransfers.validName(name);
        List<Path> order = new ArrayList<>();
        progress.walking(true);
        collect(local, order, 0);
        progress.walking(false);
        ChannelExec channel = connection.newExec("mkdir -p -- " + quote(parent) + " && cd -- " + quote(parent) + " && gzip -dc | tar -xf -");
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        channel.setErrStream(errors);
        OutputStream raw = channel.getOutputStream();
        channel.connect(15_000);
        try (OutputStream out = new GZIPOutputStream(new BufferedOutputStream(raw, 1 << 16), 1 << 16)) {
            TarWriter tar = new TarWriter(out);
            for (Path item : order) {
                if (cancelled.get()) throw new InterruptedIOException(I18n.t("传输已取消", "Transfer cancelled"));
                String entry = name + (item.equals(local) ? "" : "/" + local.relativize(item).toString().replace('\\', '/'));
                long modified = Files.getLastModifiedTime(item, LinkOption.NOFOLLOW_LINKS).toMillis();
                if (Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS)) tar.directory(entry, modified);
                else {
                    long size = Files.size(item);
                    tar.file(entry, size, modified);
                    files.sendFile(item, out);
                    tar.pad(size);
                }
            }
            tar.finish();
        } catch (IOException broken) {
            if (!(broken instanceof InterruptedIOException)) throw new IOException(I18n.t("服务器未接收归档：", "The server did not accept the archive: ") + failure(channel, errors, broken.getMessage()), broken);
            throw broken;
        } finally {
            if (cancelled.get()) channel.disconnect();
        }
        int status = waitForExit(channel);
        channel.disconnect();
        if (status != 0) throw new IOException(I18n.t("服务器解包失败：", "Unpacking on the server failed: ") + failure(channel, errors, "exit " + status));
    }

    private void collect(Path item, List<Path> order, int depth) throws IOException {
        if (cancelled.get()) throw new InterruptedIOException(I18n.t("传输已取消", "Transfer cancelled"));
        if (depth > 64 || order.size() >= 100000) throw new IOException(I18n.t("目录超过 64 层或 100000 项，请分批传输。", "Folder exceeds 64 levels or 100000 items; transfer in batches."));
        if (Files.isSymbolicLink(item)) throw new IOException(I18n.t("不跟随符号链接：", "Symbolic link not followed: ") + item);
        if (depth > 0) SftpTransfers.validName(item.getFileName().toString());
        order.add(item);
        if (Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS)) {
            List<Path> children = new ArrayList<>();
            try (var list = Files.newDirectoryStream(item)) { list.forEach(children::add); }
            children.sort(Comparator.comparing(Path::toString));
            for (Path child : children) collect(child, order, depth + 1);
        } else if (Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS)) progress.found(Files.size(item));
        else throw new IOException(I18n.t("不是普通文件：", "Not a regular file: ") + item);
    }

    /** The local path of archive entry {@code entry}, which must lie under the transferred folder's name. */
    static Path inside(Path destination, String root, String entry) throws IOException {
        String path = entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry;
        if (path.startsWith("./")) path = path.substring(2);
        if (!path.equals(root) && !path.startsWith(root + "/")) throw new IOException(I18n.t("归档条目不在所选文件夹内：", "Archive entry is outside the chosen folder: ") + entry);
        Path target = destination;
        if (path.length() > root.length()) {
            for (String part : path.substring(root.length() + 1).split("/")) {
                SftpTransfers.validName(part);
                target = target.resolve(part);
            }
        }
        return target;
    }

    static String quote(String text) { return "'" + text.replace("'", "'\\''") + "'"; }
    static String parent(String remote) { int at = remote.lastIndexOf('/'); return at < 0 ? "." : at == 0 ? "/" : remote.substring(0, at); }
    static String name(String remote) { return remote.substring(remote.lastIndexOf('/') + 1); }

    private static int waitForExit(ChannelExec channel) throws InterruptedException {
        for (int i = 0; i < 600 && !channel.isClosed(); i++) Thread.sleep(50);
        return channel.getExitStatus();
    }

    private static String failure(ChannelExec channel, ByteArrayOutputStream errors, String fallback) {
        String text = errors.toString(StandardCharsets.UTF_8).strip();
        if (text.contains("not found")) text += I18n.t("（服务器需要 tar 和 gzip；可关闭归档模式改用逐个文件传输）", " (the server needs tar and gzip; turn off archive mode to transfer file by file)");
        return text.isEmpty() ? fallback : text;
    }

    /** Reads ustar/GNU/pax tar: regular files and folders, with GNU and pax long names. */
    static final class TarReader {
        enum Type { FILE, DIRECTORY, LINK, OTHER }
        record Entry(String name, Type type, long size, long modified) { }
        private final InputStream in;
        private long left, padding;
        TarReader(InputStream in) { this.in = in; }

        Entry next() throws IOException {
            skipRest();
            String longName = null, paxPath = null;
            long paxSize = -1;
            while (true) {
                byte[] header = in.readNBytes(512);
                if (header.length == 0) return null;
                if (header.length < 512) throw new EOFException(I18n.t("归档头不完整", "Incomplete archive header"));
                if (zero(header)) return null;
                char flag = (char) header[156];
                long size = number(header, 124, 12);
                if (flag == 'L' || flag == 'K' || flag == 'x' || flag == 'g') {
                    if (size > 1 << 20) throw new IOException(I18n.t("归档扩展头过大", "Archive extension header is too large"));
                    byte[] data = in.readNBytes((int) size);
                    in.skipNBytes((512 - size % 512) % 512);
                    if (flag == 'L') longName = cstring(data, 0, data.length);
                    else if (flag == 'x') for (String record : pax(data)) {
                        int eq = record.indexOf('=');
                        if (eq < 0) continue;
                        String key = record.substring(0, eq), value = record.substring(eq + 1);
                        if (key.equals("path")) paxPath = value;
                        else if (key.equals("size")) paxSize = Long.parseLong(value);
                    }
                    continue;
                }
                String name = cstring(header, 0, 100);
                String magic = cstring(header, 257, 6);
                if (magic.startsWith("ustar")) {
                    String prefix = cstring(header, 345, 155);
                    if (!prefix.isEmpty() && !magic.equals("ustar ")) name = prefix + "/" + name;
                }
                if (longName != null) name = longName;
                if (paxPath != null) name = paxPath;
                if (paxSize >= 0) size = paxSize;
                long modified = number(header, 136, 12) * 1000;
                Type type = switch (flag) {
                    case '0', '\0', '7' -> name.endsWith("/") ? Type.DIRECTORY : Type.FILE;
                    case '5' -> Type.DIRECTORY;
                    case '1', '2' -> Type.LINK;
                    default -> Type.OTHER;
                };
                left = type == Type.DIRECTORY ? 0 : size;
                padding = (512 - size % 512) % 512;
                if (type == Type.DIRECTORY && size > 0) left = size;
                return new Entry(name, type, size, modified);
            }
        }

        /** The current entry's data; read it fully, or call {@link #skipRest}. */
        InputStream content() {
            return new InputStream() {
                @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (left <= 0) return -1;
                    int n = in.read(b, off, (int) Math.min(len, left));
                    if (n < 0) throw new EOFException(I18n.t("归档数据意外结束", "The archive ended unexpectedly"));
                    left -= n;
                    return n;
                }
            };
        }

        void skipRest() throws IOException {
            if (left > 0) { in.skipNBytes(left); left = 0; }
            if (padding > 0) { in.skipNBytes(padding); padding = 0; }
        }

        private static List<String> pax(byte[] data) {
            List<String> records = new ArrayList<>();
            int at = 0;
            while (at < data.length) {
                int space = at;
                while (space < data.length && data[space] != ' ') space++;
                if (space >= data.length) break;
                int length;
                try { length = Integer.parseInt(new String(data, at, space - at, StandardCharsets.US_ASCII)); } catch (NumberFormatException e) { break; }
                if (length <= 0 || at + length > data.length) break;
                records.add(new String(data, space + 1, at + length - space - 2, StandardCharsets.UTF_8));
                at += length;
            }
            return records;
        }
    }

    /** Writes GNU-compatible tar: GNU long names, base-256 sizes over 8 GiB. */
    static final class TarWriter {
        private final OutputStream out;
        TarWriter(OutputStream out) { this.out = out; }
        void directory(String name, long modified) throws IOException { header(name.endsWith("/") ? name : name + "/", 0, modified, '5', 0755); }
        void file(String name, long size, long modified) throws IOException { header(name, size, modified, '0', 0644); }
        void pad(long size) throws IOException { out.write(new byte[(int) ((512 - size % 512) % 512)]); }
        void finish() throws IOException { out.write(new byte[1024]); out.flush(); }

        private void header(String name, long size, long modified, char type, int mode) throws IOException {
            byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 100) {
                byte[] longName = Arrays.copyOf(bytes, bytes.length + 1);
                write(block("././@LongLink", longName.length, 0, 'L', 0644));
                out.write(longName);
                pad(longName.length);
            }
            write(block(name, size, modified, type, mode));
        }

        private void write(byte[] header) throws IOException { out.write(header); }

        private static byte[] block(String name, long size, long modified, char type, int mode) {
            byte[] header = new byte[512];
            byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(bytes, 0, header, 0, Math.min(100, bytes.length));
            octal(header, 100, 8, mode);
            octal(header, 108, 8, 0);
            octal(header, 116, 8, 0);
            if (size < 077777777777L) octal(header, 124, 12, size);
            else { header[124] = (byte) 0x80; for (int i = 0; i < 8; i++) header[135 - i] = (byte) (size >>> (8 * i)); }
            octal(header, 136, 12, Math.max(0, modified / 1000));
            Arrays.fill(header, 148, 156, (byte) ' ');
            header[156] = (byte) type;
            System.arraycopy("ustar  \0".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 8);
            long sum = 0;
            for (byte b : header) sum += b & 255;
            String check = String.format(Locale.ROOT, "%06o", sum);
            System.arraycopy(check.getBytes(StandardCharsets.US_ASCII), 0, header, 148, 6);
            header[154] = 0;
            header[155] = ' ';
            return header;
        }

        private static void octal(byte[] header, int offset, int length, long value) {
            String text = String.format(Locale.ROOT, "%0" + (length - 1) + "o", value);
            System.arraycopy(text.getBytes(StandardCharsets.US_ASCII), 0, header, offset, length - 1);
            header[offset + length - 1] = 0;
        }
    }

    private static boolean zero(byte[] block) { for (byte b : block) if (b != 0) return false; return true; }

    private static String cstring(byte[] data, int offset, int length) {
        int end = offset;
        while (end < offset + length && end < data.length && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long number(byte[] header, int offset, int length) {
        if ((header[offset] & 0x80) != 0) {
            long value = 0;
            for (int i = offset + 1; i < offset + length; i++) value = (value << 8) | (header[i] & 255);
            return value;
        }
        String text = cstring(header, offset, length).trim();
        return text.isEmpty() ? 0 : Long.parseLong(text, 8);
    }
}
