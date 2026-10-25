package dev.lagless.desktop;

import dev.lagless.I18n;
import java.io.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.*;

/** Records accepted editor submissions on a dedicated writer, never on the input thread. */
final class LocalCommandJournal implements AutoCloseable {
    private final SessionJournal journal;
    private final ThreadPoolExecutor writer;
    private volatile RuntimeException failure;
    private final ApplicationJournal application;
    record Pending(CompletableFuture<Boolean> accepted){void finish(boolean sent){accepted.complete(sent);}}
    LocalCommandJournal(Path home)throws IOException{
        this(new SessionJournal(home.resolve("desktop/commands"),"local-commands",true),null);
    }
    LocalCommandJournal(ApplicationJournal application){this(application.backing(),application);}
    private LocalCommandJournal(SessionJournal journal,ApplicationJournal application){
        this.journal=journal;this.application=application;
        writer=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(256),r->new Thread(r,"lagless-local-command-log")){
            @Override protected void terminated(){if(application==null)try{journal.close();}catch(IOException e){failure=new UncheckedIOException(e);}}
        };
    }
    Pending prepare(Profile profile,String session,String command,boolean interactive){
        if(failure!=null)throw failure;
        String entry=Instant.now()+I18n.t(" [本地命令] ["," [local command] [")+safe(profile.name())+" · "+safe(profile.user())+"@"+safe(profile.host())+":"+profile.port()+" · "+safe(session)+"] "+(interactive?I18n.t("交互发送","interactive send"):I18n.t("本地编辑","local edit"))+I18n.t(" · 已提交 · "," · submitted · ")+safe(command)+"\n";
        Pending pending=new Pending(new CompletableFuture<>());
        writer.execute(()->{if(pending.accepted().join())try{journal.append(entry);}catch(RuntimeException e){failure=e;}});return pending;
    }
    private static String safe(String value){return value.replace('\r',' ').replace('\n',' ').replace('\u001b','?');}
    Path path(){return journal.path();}
    void force()throws Exception{writer.submit(()->{if(failure!=null)throw failure;try{journal.force();}catch(IOException e){throw new UncheckedIOException(e);}}).get();}
    @Override public void close(){writer.shutdown();}
    void awaitClosed(long deadline)throws InterruptedException{writer.awaitTermination(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
}
