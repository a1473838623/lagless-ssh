package dev.lagless.desktop;

import dev.lagless.I18n;
import java.io.*;
import java.time.Instant;
import java.nio.file.*;

/** Client lifecycle events are never mixed into the remote SSH transcript. */
final class ApplicationJournal implements AutoCloseable {
    private final SessionJournal journal;
    private final Path home;
    private boolean closed;
    private Thread.UncaughtExceptionHandler handler,previousHandler;
    private String previousError="";
    private long errorWritten;
    private int repeatedErrors;
    ApplicationJournal(Path home)throws IOException{
        this.home=home;journal=new SessionJournal(home.resolve("desktop/events"),"application",true);
        record(null,I18n.t("程序启动 · Lagless ","Started · Lagless ")+DesktopRuntime.version()+" · PID "+ProcessHandle.current().pid()+" · "+DesktopRuntime.home());
    }
    void installErrorHandler(){
        previousHandler=Thread.getDefaultUncaughtExceptionHandler();
        handler=this::uncaughtError;
        Thread.setDefaultUncaughtExceptionHandler(handler);
    }
    private synchronized void uncaughtError(Thread thread,Throwable error){
        StackTraceElement[] frames=error.getStackTrace();String key=thread.getName()+"|"+error+"|"+(frames.length==0?"":frames[0]);long now=System.nanoTime();
        if(key.equals(previousError)&&now-errorWritten<5_000_000_000L){repeatedErrors++;return;}
        flushRepeatedErrors();previousError=key;errorWritten=now;
        error(thread.getName(),error);
    }
    private void flushRepeatedErrors(){if(repeatedErrors>0){record(null,I18n.t("上一条异常另重复 ","Previous error repeated ")+repeatedErrors+I18n.t(" 次"," time(s)"));repeatedErrors=0;}}
    synchronized void error(String source,Throwable error){
        StringWriter text=new StringWriter();error.printStackTrace(new PrintWriter(text));
        String entry=Instant.now()+I18n.t(" [错误] ["," [error] [")+source.replace('\r',' ').replace('\n',' ')+"] "+text+"\n";
        try{if(closed)throw new IOException(I18n.t("软件日志已经关闭","Application log is already closed"));journal.append(entry);}
        catch(IOException|UncheckedIOException failure){
            // Emergency fallback only when the run journal itself is unavailable.
            try{Path fallback=home.resolve("desktop/native-errors.log");Files.createDirectories(fallback.getParent());Files.writeString(fallback,entry,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
            catch(IOException ignored){error.printStackTrace(System.err);}
        }
    }
    SessionJournal backing(){return journal;}
    void operation(String message){record(I18n.t("本地操作","Local action"),null,message);}
    void operation(String session,String message){record(I18n.t("本地操作","Local action"),session,message);}
    synchronized void record(String session,String message){
        record(session==null?I18n.t("软件","Application"):I18n.t("连接","Connect"),session,message);
    }
    private synchronized void record(String prefix,String session,String message){
        if(closed)return;
        String safe=message==null?"":message.replace('\r',' ').replace('\n',' ').replace('\u001b','?');
        try{journal.append(Instant.now()+" ["+prefix+"] ["+(session==null?"app":session.replace('\r',' ').replace('\n',' ').replace('\u001b','?'))+"] "+safe+"\n");}
        catch(UncheckedIOException error){error.printStackTrace(System.err);}
    }
    void force()throws IOException{journal.force();}
    Path path(){return journal.path();}
    @Override public synchronized void close(){if(closed)return;flushRepeatedErrors();closed=true;if(handler!=null&&Thread.getDefaultUncaughtExceptionHandler()==handler)Thread.setDefaultUncaughtExceptionHandler(previousHandler);try{journal.close();}catch(IOException error){error.printStackTrace(System.err);}}
}
