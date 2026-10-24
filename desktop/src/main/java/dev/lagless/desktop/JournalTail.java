package dev.lagless.desktop;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;

/** Crash-recoverable, replaceable suffix for text still changing on the screen. */
final class JournalTail {
    private record State(String text,long offset){}
    record Snapshot(long length,byte[] suffix){}
    private static final String HEADER="LAGLESS-TAIL-1 ";
    private final Path path,log;private volatile State state=new State("",0);private State saved=state;
    JournalTail(Path log){this.log=log;path=path(log);}
    static Path path(Path log){return log.resolveSibling(log.getFileName()+".pending");}
    void set(String value,long offset){state=new State(value,offset);}
    String text(){return state.text();}
    synchronized void force()throws IOException{
        State snapshot=state;
        if(saved.equals(snapshot))return;
        if(snapshot.text().isEmpty()){Files.deleteIfExists(path);saved=snapshot;return;}
        if(snapshot.offset()>Files.size(log))return;
        Path temp=Files.createTempFile(path.getParent(),".lagless-tail-",".tmp");
        try{
            try(FileChannel out=FileChannel.open(temp,StandardOpenOption.WRITE)){ByteBuffer data=StandardCharsets.UTF_8.encode(HEADER+snapshot.offset()+"\n"+snapshot.text());while(data.hasRemaining())out.write(data);out.force(false);}
            try{Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
            saved=snapshot;
        }finally{Files.deleteIfExists(temp);}
    }
    static byte[] read(Path log)throws IOException{
        Path pending=path(log);try{
            if(Files.size(pending)>16*1024*1024)throw new IOException("未完成日志过大");return Files.readAllBytes(pending);
        }catch(NoSuchFileException e){return new byte[0];}
    }
    static Snapshot snapshot(Path log,long length)throws IOException{
        byte[] data=read(log);byte[] marker=HEADER.getBytes(StandardCharsets.US_ASCII);
        if(data.length<marker.length||!Arrays.equals(data,0,marker.length,marker,0,marker.length))return new Snapshot(length,data);
        int end=marker.length;while(end<data.length&&end<80&&data[end]!='\n')end++;
        if(end>=data.length||end>=80)throw new IOException("未完成日志头无效");
        long offset;try{offset=Long.parseLong(new String(data,marker.length,end-marker.length,StandardCharsets.US_ASCII));}catch(NumberFormatException e){throw new IOException("未完成日志偏移无效",e);}
        if(offset<0||offset>length)return new Snapshot(length,new byte[0]);
        return new Snapshot(offset,Arrays.copyOfRange(data,end+1,data.length));
    }
    static void export(Path log,Path target)throws IOException{
        if(log.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize()))return;
        Snapshot snapshot=snapshot(log,Files.size(log));byte[] suffix=snapshot.suffix();long length=snapshot.length();Path dest=target.toAbsolutePath();Path temp=Files.createTempFile(dest.getParent(),".lagless-export-",".tmp");
        try{
            try(InputStream in=Files.newInputStream(log);OutputStream out=Files.newOutputStream(temp)){
                byte[] bytes=new byte[65536];while(length>0){int n=in.read(bytes,0,(int)Math.min(length,bytes.length));if(n<0)throw new EOFException("日志意外截断");out.write(bytes,0,n);length-=n;}out.write(suffix);
            }
            try{Files.move(temp,dest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,dest,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
    }
}
