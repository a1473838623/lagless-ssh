package dev.lagless.desktop;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;

/** Append-only remote output, independent of the bounded GUI scrollback. */
public final class SessionJournal implements AutoCloseable {
    private final Path path;
    private final FileChannel channel;
    private FileChannel interaction;
    private final JournalTail tail;
    private final ScheduledExecutorService flusher;
    private volatile IOException failure;
    public SessionJournal(Path home, String name) throws IOException {
        this(home.resolve("logs"),name,true);
    }
    SessionJournal(Path logs,String name,boolean directDirectory)throws IOException {
        Files.createDirectories(logs);
        path = logs.resolve(Instant.now().toString().replace(':', '-') + "-" + name.replaceAll("[^\\p{L}\\p{N}_.-]", "_")
                + "-" + UUID.randomUUID().toString().substring(0,8) + ".log");
        channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        tail=new JournalTail(path);
        flusher = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r,"lagless-log-flush"); t.setDaemon(true); return t; });
        flusher.scheduleWithFixedDelay(() -> { try { force(); } catch (IOException e) { failure = e; } }, 1, 1, TimeUnit.SECONDS);
    }
    public Path path() { return path; }
    public synchronized void append(String text) {
        try {
            if (failure != null) throw failure;
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
            while (bytes.hasRemaining()) channel.write(bytes);
        } catch (IOException e) { throw new UncheckedIOException("会话日志写入失败：" + path, e); }
    }
    synchronized void setPendingTail(String text){try{tail.set(text,channel.position());}catch(IOException e){throw new UncheckedIOException(e);}}
    synchronized void appendInteraction(String text){
        try{if(interaction==null)interaction=FileChannel.open(path.resolveSibling(path.getFileName()+".vt"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            ByteBuffer bytes=StandardCharsets.UTF_8.encode(text);while(bytes.hasRemaining())interaction.write(bytes);
        }catch(IOException e){throw new UncheckedIOException("全屏控制流记录失败："+path,e);}
    }
    public synchronized void force() throws IOException { if (channel.isOpen()) channel.force(false);if(interaction!=null&&interaction.isOpen())interaction.force(false);tail.force(); }
    public void export(Path target) throws IOException {
        long length;String suffix;
        synchronized (this) { force(); length = channel.isOpen() ? channel.size() : Files.size(path);suffix=tail.text(); }
        if (target.toAbsolutePath().normalize().equals(path.toAbsolutePath().normalize())) return;
        Path dest = target.toAbsolutePath();
        Path temp = Files.createTempFile(dest.getParent(), ".lagless-export-", ".tmp");
        try {
            // Copy a fixed snapshot outside the append lock: exporting a large log cannot stall rendering.
            try (InputStream in = Files.newInputStream(path); OutputStream out = Files.newOutputStream(temp)) {
                byte[] bytes = new byte[65536];
                while (length > 0) { int n = in.read(bytes, 0, (int)Math.min(bytes.length,length)); if(n<0)throw new EOFException("日志意外截断");out.write(bytes,0,n);length-=n; }
                out.write(suffix.getBytes(StandardCharsets.UTF_8));
            }
            try { Files.move(temp,dest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp,dest,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    @Override public synchronized void close() throws IOException {
        flusher.shutdownNow();
        try { force(); } finally { try{channel.close();}finally{if(interaction!=null)interaction.close();} }
        if (failure != null) throw failure;
    }
}
