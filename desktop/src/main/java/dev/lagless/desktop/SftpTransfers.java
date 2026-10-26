package dev.lagless.desktop;

import dev.lagless.I18n;
import com.jcraft.jsch.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import java.util.function.Consumer;

/** Recursive SFTP with a shared bandwidth budget and staged replacement of each file. */
final class SftpTransfers {
    record Entry(String name,boolean directory,boolean link,long size,boolean regular){public String toString(){return name+(link?I18n.t(" (链接)"," (link)"):"");}}
    /** One bandwidth budget shared by every channel of a transfer; {@code rate} 0 means unlimited. */
    static final class Throttle {
        private final long rate;private long nextPermit=System.nanoTime();
        Throttle(long rate){this.rate=rate;}
        /** Nanoseconds to wait before {@code n} more bytes may go. */
        synchronized long take(int n){if(rate<=0)return 0;long now=System.nanoTime();nextPermit=Math.max(nextPermit,now)+n*1_000_000_000L/rate;return nextPermit-now;}
    }
    private final ChannelSftp channel;private final Throttle throttle;
    private final AtomicBoolean cancelled;private final LongConsumer progress;private long bytes,lastUpdate;private int entries;
    private LongConsumer chunk=n->{};
    private java.util.function.BooleanSupplier stopped;
    /** Also stops when {@code halt} is set: a parallel transfer halts every channel after one fails. */
    SftpTransfers haltWith(AtomicBoolean halt){java.util.function.BooleanSupplier user=stopped;stopped=()->user.getAsBoolean()||halt.get();return this;}
    /** Reports the path being transferred. */
    SftpTransfers showing(Consumer<String> listener){current=listener;return this;}
    /** Sees each chunk's byte count as it is written, for a total shared by several channels. */
    SftpTransfers onBytes(LongConsumer listener){chunk=listener;return this;}
    private Consumer<String> current=path->{};
    private Runnable fileDone=()->{};
    /** Called after each file is complete, for a files-done count beside the byte progress. */
    SftpTransfers onFileDone(Runnable listener){fileDone=listener;return this;}
    private Path downloadAnchor;
    SftpTransfers(ChannelSftp channel,long rate,AtomicBoolean cancelled,LongConsumer progress)throws JSchException{this(channel,new Throttle(rate),cancelled,progress);}
    SftpTransfers(ChannelSftp channel,Throttle throttle,AtomicBoolean cancelled,LongConsumer progress)throws JSchException{
        // 64 reads in flight per file: on a high-latency link few outstanding requests, not
        // bandwidth, limit SFTP speed (JSch's default is 16; this used to be 4).
        this.channel=channel;this.throttle=throttle;this.cancelled=cancelled;this.progress=progress;this.stopped=cancelled::get;channel.setBulkRequests(64);
    }
    SftpTransfers(ChannelSftp channel,long rate,AtomicBoolean cancelled,LongConsumer progress,Consumer<String> current)throws JSchException{this(channel,rate,cancelled,progress);this.current=current;}
    static List<Entry> list(ChannelSftp channel,String path)throws SftpException{
        List<Entry> result=new ArrayList<>();for(var item:channel.ls(path)){String name=item.getFilename();if(name.equals(".")||name.equals(".."))continue;var a=item.getAttrs();result.add(new Entry(name,a.isDir(),a.isLink(),a.getSize(),a.isReg()));}
        result.sort(Comparator.comparing(Entry::directory).reversed().thenComparing(Entry::name,String.CASE_INSENSITIVE_ORDER));return result;
    }
    static String child(String parent,String name)throws IOException{validName(name);return (parent.endsWith("/")?parent:parent+"/")+name;}
    static void validName(String name)throws IOException{
        if(name.isBlank()||name.equals(".")||name.equals("..")||name.chars().anyMatch(c->c<32||"/\\:*?\"<>|".indexOf(c)>=0)||name.endsWith(".")||name.endsWith(" ")||name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"))throw new IOException(I18n.t("无法安全传输文件名：","Cannot transfer this file name safely: ")+name);
    }
    void upload(Path local,String remote)throws Exception{upload(local,remote,0);}
    private void upload(Path local,String remote,int depth)throws Exception{
        check(depth);current.accept(remote);if(Files.isSymbolicLink(local))throw new IOException(I18n.t("不跟随符号链接：","Symbolic link not followed: ")+local);
        if(Files.isDirectory(local,LinkOption.NOFOLLOW_LINKS)){
            SftpATTRS existing=stat(remote);if(existing==null)channel.mkdir(remote);else if(!existing.isDir()||existing.isLink())throw new IOException(I18n.t("目标不是普通目录：","Target is not a regular folder: ")+remote);
            try(var children=Files.newDirectoryStream(local)){for(Path item:children)upload(item,child(remote,item.getFileName().toString()),depth+1);}
        }else{
            if(!Files.isRegularFile(local,LinkOption.NOFOLLOW_LINKS))throw new IOException(I18n.t("不是普通文件：","Not a regular file: ")+local);
            SftpATTRS existing=stat(remote);if(existing!=null&&(existing.isLink()||existing.isDir()))throw new IOException(I18n.t("目标文件类型冲突：","Target file type conflict: ")+remote);
            String staged=remote+suffix();try(InputStream in=Files.newInputStream(local);OutputStream out=channel.put(staged)){copy(in,out);}checkCancelled();channel.rename(staged,remote);fileDone.run();
        }
    }
    /** A remote folder of a parallel upload, created when missing. */
    void remoteFolder(String remote)throws Exception{
        checkCancelled();current.accept(remote);SftpATTRS existing=stat(remote);if(existing==null)channel.mkdir(remote);else if(!existing.isDir()||existing.isLink())throw new IOException(I18n.t("目标不是普通目录：","Target is not a regular folder: ")+remote);
    }
    /** One regular file of a parallel upload; its folder already exists. */
    void uploadFile(Path local,String remote)throws Exception{upload(local,remote,0);}
    /** Fixes the local folder a download may write into and returns the canonical destination. */
    Path anchorDownload(Path local)throws IOException{
        Path destination=local.toAbsolutePath().normalize();if(Files.isSymbolicLink(destination))throw new IOException(I18n.t("不跟随本地符号链接：","Local symbolic link not followed: ")+destination);
        Path anchor=destination.getParent();while(anchor!=null&&!Files.exists(anchor,LinkOption.NOFOLLOW_LINKS))anchor=anchor.getParent();
        if(anchor==null)throw new IOException(I18n.t("无法确定本地目标目录：","Cannot determine the local target folder: ")+destination);
        downloadAnchor=anchor.toRealPath();return downloadAnchor.resolve(anchor.relativize(destination)).normalize();
    }
    /** Uses the folder fixed by another channel's {@link #anchorDownload}. */
    void shareAnchor(Path anchor){downloadAnchor=anchor;}
    Path anchor(){return downloadAnchor;}
    /** One regular remote file of a parallel download, already checked by the folder walk: no extra lstat. */
    void downloadFile(String remote,Path local)throws Exception{
        checkCancelled();current.accept(remote);safeLocal(local);
        if(Files.isDirectory(local,LinkOption.NOFOLLOW_LINKS))throw new IOException(I18n.t("目标文件类型冲突：","Target file type conflict: ")+local);
        Files.createDirectories(local.getParent());Path staged=local.resolveSibling(local.getFileName()+suffix());
        try(InputStream in=channel.get(remote);OutputStream out=Files.newOutputStream(staged,StandardOpenOption.CREATE_NEW)){copy(in,out);}checkCancelled();
        try{Files.move(staged,local,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(staged,local,StandardCopyOption.REPLACE_EXISTING);}
        fileDone.run();
    }
    /**
     * Writes {@code size} bytes of {@code in} to {@code local} through a staged .part file, as
     * an SFTP download does: used for files unpacked from an archive stream.
     */
    void receiveFile(InputStream in,long size,Path local,long modified)throws Exception{
        checkCancelled();current.accept(local.toString());safeLocal(local);
        if(Files.isDirectory(local,LinkOption.NOFOLLOW_LINKS))throw new IOException(I18n.t("目标文件类型冲突：","Target file type conflict: ")+local);
        Files.createDirectories(local.getParent());Path staged=local.resolveSibling(local.getFileName()+suffix());
        try(OutputStream out=Files.newOutputStream(staged,StandardOpenOption.CREATE_NEW)){copy(new Bounded(in,size),out);}
        catch(Exception error){Files.deleteIfExists(staged);throw error;}
        checkCancelled();if(modified>0)Files.setLastModifiedTime(staged,java.nio.file.attribute.FileTime.fromMillis(modified));
        try{Files.move(staged,local,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(staged,local,StandardCopyOption.REPLACE_EXISTING);}
        fileDone.run();
    }
    /** Copies a local file into an archive stream, with the same budget, progress and cancellation. */
    void sendFile(Path local,OutputStream out)throws Exception{checkCancelled();current.accept(local.toString());try(InputStream in=Files.newInputStream(local)){copy(in,new FilterOutputStream(out){@Override public void write(byte[] b,int off,int len)throws IOException{out.write(b,off,len);}@Override public void close(){}});}fileDone.run();}
    /** Exactly {@code size} bytes of the underlying stream, never more. */
    private static final class Bounded extends InputStream {
        private final InputStream in;private long left;
        Bounded(InputStream in,long size){this.in=in;this.left=size;}
        @Override public int read()throws IOException{byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
        @Override public int read(byte[] b,int off,int len)throws IOException{if(left<=0)return -1;int n=in.read(b,off,(int)Math.min(len,left));if(n<0)throw new EOFException(I18n.t("归档数据意外结束","The archive ended unexpectedly"));left-=n;return n;}
    }
    /** A local folder of a parallel download, inside the anchored destination. */
    void localFolder(Path local)throws IOException{checkCancelled();safeLocal(local);Files.createDirectories(local);}
    // The user-selected existing parent is canonicalized once. This permits OS path aliases such
    // as macOS /var -> /private/var, while still rejecting links inside the destination tree.
    void download(String remote,Path local)throws Exception{download(remote,anchorDownload(local),0);}
    void copyRemote(ChannelSftp source,String from,String to)throws Exception{copyRemote(source,from,to,0);}
    private void copyRemote(ChannelSftp source,String from,String to,int depth)throws Exception{
        check(depth);current.accept(from);SftpATTRS attrs=source.lstat(from);
        if(attrs.isLink())throw new IOException(I18n.t("复制不跟随符号链接：","Copy does not follow symbolic links: ")+from);
        if(stat(to)!=null)throw new IOException(I18n.t("目标已存在：","Target already exists: ")+to);
        if(attrs.isDir()){
            channel.mkdir(to);for(Entry item:list(source,from))copyRemote(source,child(from,item.name()),child(to,item.name()),depth+1);
        }else{
            if(!attrs.isReg())throw new IOException(I18n.t("不是普通文件：","Not a regular file: ")+from);
            String staged=to+suffix();try(InputStream in=source.get(from);OutputStream out=channel.put(staged)){copy(in,out);}checkCancelled();channel.rename(staged,to);
        }
    }
    private void download(String remote,Path local,int depth)throws Exception{
        check(depth);current.accept(remote);safeLocal(local);SftpATTRS attrs=channel.lstat(remote);if(attrs.isLink())throw new IOException(I18n.t("不跟随远端符号链接：","Remote symbolic link not followed: ")+remote);
        if(attrs.isDir()){
            Files.createDirectories(local);for(Entry item:list(channel,remote)){validName(item.name());download(child(remote,item.name()),local.resolve(item.name()),depth+1);}
        }else{
            if(!attrs.isReg())throw new IOException(I18n.t("不是普通远端文件：","Not a regular remote file: ")+remote);
            if(Files.isDirectory(local,LinkOption.NOFOLLOW_LINKS))throw new IOException(I18n.t("目标文件类型冲突：","Target file type conflict: ")+local);
            Files.createDirectories(local.getParent());Path staged=local.resolveSibling(local.getFileName()+suffix());
            try(InputStream in=channel.get(remote);OutputStream out=Files.newOutputStream(staged,StandardOpenOption.CREATE_NEW)){copy(in,out);}checkCancelled();
            try{Files.move(staged,local,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(staged,local,StandardCopyOption.REPLACE_EXISTING);}
            fileDone.run();
        }
    }
    private void safeLocal(Path path)throws IOException{
        Path normalized=path.toAbsolutePath().normalize();if(downloadAnchor==null||!normalized.startsWith(downloadAnchor))throw new IOException(I18n.t("下载目标超出选定目录：","Download target is outside the chosen folder: ")+normalized);
        Path cursor=downloadAnchor;for(Path part:downloadAnchor.relativize(normalized)){cursor=cursor.resolve(part);if(Files.isSymbolicLink(cursor))throw new IOException(I18n.t("目标目录包含符号链接：","Target folder contains a symbolic link: ")+cursor);}
    }
    private SftpATTRS stat(String path)throws SftpException{try{return channel.lstat(path);}catch(SftpException e){if(e.id==ChannelSftp.SSH_FX_NO_SUCH_FILE)return null;throw e;}}
    private void check(int depth)throws IOException{checkCancelled();if(depth>64||++entries>100000)throw new IOException(I18n.t("目录超过 64 层或 100000 项，请分批传输。","Folder exceeds 64 levels or 100000 items; transfer in batches."));}
    private void checkCancelled()throws InterruptedIOException{if(stopped.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException(I18n.t("传输已取消；原文件保留，未完成部分保留为 .part 文件。","Transfer cancelled; the original is kept and the unfinished part stays as a .part file."));}
    private static String suffix(){return ".lagless-"+UUID.randomUUID()+".part";}
    private void copy(InputStream in,OutputStream out)throws Exception{
        byte[] buffer=new byte[65536];int n;while(true){checkCancelled();n=in.read(buffer);if(n<0)break;checkCancelled();out.write(buffer,0,n);bytes+=n;chunk.accept(n);
            // A per-chunk deadline cannot overflow after a multi-gigabyte transfer.
            long delay=throttle.take(n);
            while(delay>0){checkCancelled();long pause=Math.min(delay,50_000_000L);java.util.concurrent.TimeUnit.NANOSECONDS.sleep(pause);delay-=pause;}
            if(System.nanoTime()-lastUpdate>250_000_000L){progress.accept(bytes);lastUpdate=System.nanoTime();}
        }progress.accept(bytes);
    }
}
