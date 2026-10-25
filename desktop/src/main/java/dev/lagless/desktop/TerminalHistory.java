package dev.lagless.desktop;

import dev.lagless.I18n;
import dev.lagless.ScreenModel;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Append-only disk scrollback and a fixed-size hot cache; no fixed total line limit. */
final class TerminalHistory implements AutoCloseable {
    /** {@code wrapped}: the row's text continues on the next row (automatic wrap, not a line break). */
    record Row(int columns,String ansi,boolean wrapped){
        /** Bit kept in the index's column field; older files never set it. */
        private static final int WRAPPED_BIT=1<<30;
        Row(int columns,String ansi){this(columns,ansi,false);}
        static Row cells(int[] characters,String[] pens){return cells(characters,pens,false);}
        static Row cells(int[] characters,String[] pens,boolean wrapped){StringBuilder value=new StringBuilder("\u001b[0m");String previous="";int end=characters.length;while(end>0&&characters[end-1]==' '&&(pens[end-1]==null||pens[end-1].isEmpty()))end--;for(int i=0;i<end;i++){if(characters[i]<0)continue;String pen=pens[i]==null?"":pens[i];if(!pen.equals(previous)){value.append("\u001b[0m").append(pen);previous=pen;}value.appendCodePoint(characters[i]);}return new Row(characters.length,value.append("\u001b[0m").toString(),wrapped);}
        ScreenModel model(){ScreenModel model=new ScreenModel(columns,1);model.feed(ansi);return model;}
    }
    private final Path path;private final FileChannel data,index;private final ThreadPoolExecutor writer;
    private final JournalTail tail;private final ScheduledExecutorService tailFlusher;
    private List<Row> pendingTail=List.of();private long tailRevision,acceptedBytes;
    private final LinkedHashMap<Long,Row> recent=new LinkedHashMap<>();private long count,cacheChars,omitted,pendingChars;private volatile long committed;private boolean closed;private volatile IOException failure;
    TerminalHistory(Path home,String session)throws IOException{
        Path directory=home.resolve("desktop/scrollback");Files.createDirectories(directory);String name=java.time.Instant.now().toString().replace(':','-')+"-"+session;path=directory.resolve(name+".log");data=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE);index=FileChannel.open(directory.resolve(name+".idx"),StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE);
        tail=new JournalTail(path);
        tailFlusher=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"lagless-scrollback-tail");t.setDaemon(true);return t;});
        tailFlusher.scheduleWithFixedDelay(()->{try{tail.force();}catch(IOException e){failure=e;}},1,1,TimeUnit.SECONDS);
        writer=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(65536),r->new Thread(r,"lagless-scrollback-writer")){
            @Override protected void terminated(){try{data.force(false);index.force(false);tail.force();}catch(IOException e){failure=e;}finally{try{data.close();index.close();}catch(IOException e){failure=e;}}}
        };
    }
    synchronized long count(){return count+pendingTail.size();}
    synchronized long stableCount(){return count;}
    synchronized long tailRevision(){return tailRevision;}
    synchronized void setPendingTail(List<Row> rows){
        pendingTail=List.copyOf(rows);tailRevision++;
        StringBuilder text=new StringBuilder();for(Row row:rows)text.append(row.ansi()).append('\n');tail.set(text.toString(),acceptedBytes);
    }
    Path path(){return path;}
    synchronized long omitted(){return omitted;}
    synchronized boolean append(Row row){
        if(closed||failure!=null)return false;long sequence=count;if(pendingChars+row.ansi().length()>8_000_000){omitted++;return false;}pendingChars+=row.ansi().length();
        try{writer.execute(()->{try{if(failure!=null)return;byte[] bytes=(row.ansi()+"\n").getBytes(StandardCharsets.UTF_8);long offset=data.position();ByteBuffer payload=ByteBuffer.wrap(bytes);while(payload.hasRemaining())data.write(payload);ByteBuffer slot=ByteBuffer.allocate(16).putLong(offset).putInt(row.columns()|(row.wrapped()?Row.WRAPPED_BIT:0)).putInt(bytes.length);slot.flip();while(slot.hasRemaining())index.write(slot);committed=sequence+1;}catch(IOException e){failure=e;}finally{synchronized(this){pendingChars-=row.ansi().length();}}});}catch(RejectedExecutionException e){pendingChars-=row.ansi().length();omitted++;return false;}
        count++;acceptedBytes+=(row.ansi()+"\n").getBytes(StandardCharsets.UTF_8).length;recent.put(sequence,row);cacheChars+=row.ansi().length();while(recent.size()>2048||cacheChars>4_000_000){var entry=recent.entrySet().iterator().next();cacheChars-=entry.getValue().ansi().length();recent.remove(entry.getKey());}return true;
    }
    Row read(long row)throws IOException{
        synchronized(this){if(row>=count&&row<count+pendingTail.size())return pendingTail.get((int)(row-count));Row cached=recent.get(row);if(cached!=null)return cached;}
        if(failure!=null)throw failure;if(row<0||row>=committed)return null;
        ByteBuffer slot=ByteBuffer.allocate(16);readFully(index,slot,row*16);slot.flip();long from=slot.getLong();int columns=slot.getInt();boolean wrapped=(columns&Row.WRAPPED_BIT)!=0;columns&=~Row.WRAPPED_BIT;long to=from+slot.getInt();
        if(to-from>16*1024*1024||to<=from)throw new IOException(I18n.t("历史索引无效","Invalid history index"));ByteBuffer bytes=ByteBuffer.allocate((int)(to-from));readFully(data,bytes,from);bytes.flip();String text=StandardCharsets.UTF_8.decode(bytes).toString();return new Row(columns,text.endsWith("\n")?text.substring(0,text.length()-1):text,wrapped);
    }
    String plainText(int lastRows,int maxChars)throws IOException{
        long total=count(),start=lastRows<=0?0:Math.max(0,total-lastRows);StringBuilder result=new StringBuilder();
        for(long row=start;row<total;row++){Row item=read(row);if(item==null)continue;String line=item.wrapped()?item.model().rowText(0):item.model().rowText(0).stripTrailing();
            if(result.length()+line.length()+1>maxChars)throw new IOException(I18n.t("所选终端输出超过 ","Selected terminal output exceeds ")+maxChars+I18n.t(" 个字符，请缩小历史范围。"," characters; narrow the history range."));result.append(line);if(!item.wrapped())result.append('\n');}
        return result.toString();
    }
    private static void readFully(FileChannel file,ByteBuffer bytes,long at)throws IOException{while(bytes.hasRemaining()){int n=file.read(bytes,at+bytes.position());if(n<0)throw new EOFException(I18n.t("历史文件意外截断","History file was truncated unexpectedly"));}}
    void force()throws Exception{writer.submit(()->{try{data.force(false);index.force(false);tail.force();}catch(IOException e){throw new UncheckedIOException(e);}}).get();}
    boolean awaitClosed(long timeout,TimeUnit unit)throws InterruptedException{return writer.awaitTermination(timeout,unit);}
    @Override public synchronized void close(){if(closed)return;closed=true;tailFlusher.shutdownNow();writer.shutdown();}
}
