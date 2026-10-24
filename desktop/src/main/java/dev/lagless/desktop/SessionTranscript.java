package dev.lagless.desktop;

import dev.lagless.ScreenModel;
import dev.lagless.AnsiUtil;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Readable SSH transcript with a lossless raw remote-output companion when enabled. */
final class SessionTranscript implements AutoCloseable {
    private static final Pattern REDRAW=Pattern.compile("\\u001b\\[(?:[0-9;]*[HfABJK]|\\?[0-9;]*(?:1049|2026)[hl])");
    private final SessionJournal journal;
    private final ScreenModel screen=new ScreenModel(100,30);
    private final ScreenCapture capture;
    private final ScheduledExecutorService projectionTimer;
    private final BooleanSupplier localEditing;
    private boolean full=true,finished,realtime;
    private boolean projectionPending;
    private long firstPendingAt,lastOutputAt;
    private volatile RuntimeException projectionFailure;
    private String controls="";
    private final StringBuilder pending=new StringBuilder();
    SessionTranscript(SessionJournal journal,BooleanSupplier localEditing){
        this.journal=journal;this.localEditing=localEditing;
        capture=new ScreenCapture(row->pending.append(AnsiUtil.strip(row.ansi())).append('\n'),rows->{
            flush();StringBuilder text=new StringBuilder();for(var row:rows)text.append(AnsiUtil.strip(row.ansi())).append('\n');journal.setPendingTail(text.toString());
        });
        screen.setHistoryListener(new ScreenModel.HistoryListener(){
            public void scrolled(int[] chars,String[] pens){if(projected())capture.scrolled(chars,pens);}
            public void beforeClear(ScreenModel model){if(projected())capture.changedRows(model);}
            public void beforeOverwrite(ScreenModel model,int row){}
            public void beforeEraseRow(ScreenModel model,int row){if(projected())capture.changedRow(model,row);}
            public void frameStarted(ScreenModel model){realtime=true;}
            public void frameCompleted(ScreenModel model){if(projected()){capture.changedRows(model);projectionPending=false;}}
            public void lineCompleted(ScreenModel model,int row){
                if(!projected())return;
                if(!realtime&&!model.altScreen()&&localEditing.getAsBoolean())pending.append(model.rowText(row).stripTrailing()).append('\n');
                else capture.changedRow(model,row);
            }
        });
        projectionTimer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"lagless-transcript-projector");t.setDaemon(true);return t;});
        projectionTimer.scheduleWithFixedDelay(()->{
            try{projectPending(false);}catch(RuntimeException error){projectionFailure=error;}
        },80,80,TimeUnit.MILLISECONDS);
    }
    private boolean projected(){return realtime;}
    private synchronized void projectPending(boolean force){
        if(!projectionPending)return;
        long now=System.nanoTime();
        if(!force&&now-lastOutputAt<40_000_000L&&now-firstPendingAt<250_000_000L)return;
        capture.changedRows(screen);flush();projectionPending=false;
    }
    synchronized void setFull(boolean enabled){
        if(full==enabled)return;
        // A display preference must not finalize a still-growing logical line.
        projectPending(true);if(projected())capture.changedRows(screen);flush();full=enabled;
        journal.append("\n[记录模式："+(full?"保存完整原始远端输出":"仅保存可读输出，省略原始全屏控制流")+"]\n");
    }
    synchronized void recordSubmittedInput(String line){
        if(line==null||line.isBlank())return;
        projectPending(true);flush();
        String printable=AnsiUtil.strip(line).replace('\r','\n').replaceAll("[\\p{Cntrl}&&[^\\n\\t]]","?");
        journal.append("\n[本地输入 · 已提交]\n"+printable+"\n[/本地输入]\n");
    }
    synchronized void append(String text,int columns,int rows){
        if(projectionFailure!=null)throw projectionFailure;
        finished=false;
        String data=controls+text;
        boolean redraw=REDRAW.matcher(data).find();
        if(screen.altScreen()||screen.synchronizedOutput()||redraw&&!localEditing.getAsBoolean())realtime=true;
        controls=data.substring(Math.max(0,data.length()-32));
        screen.resize(Math.max(20,columns),Math.max(5,rows));
        boolean project=projected();screen.feed(text);
        if(full)journal.appendInteraction(text);
        if(project||projected()){
            long now=System.nanoTime();if(!projectionPending)firstPendingAt=now;
            lastOutputAt=now;projectionPending=true;
        }else journal.append(text);
        flush();
        if(realtime&&localEditing.getAsBoolean()&&!screen.altScreen()&&!screen.synchronizedOutput()&&!redraw){
            // Returning to an editable shell is a semantic boundary, not a timer.
            projectPending(true);
            capture.flush();flush();realtime=false;
        }
    }
    private void flush(){if(!pending.isEmpty()){journal.append(pending.toString());pending.setLength(0);}}
    synchronized void snapshot(){projectPending(true);if(projected())capture.changedRows(screen);flush();}
    synchronized void finish(){if(finished)return;finished=true;snapshot();capture.flush();flush();realtime=false;}
    @Override public void close(){projectionTimer.shutdownNow();finish();}
}
