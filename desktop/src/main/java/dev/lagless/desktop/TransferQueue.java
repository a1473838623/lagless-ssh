package dev.lagless.desktop;

import dev.lagless.I18n;
import com.jcraft.jsch.ChannelSftp;
import dev.lagless.SshConnection;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** One serial queue per SSH tab. UI windows observe it without owning its lifetime. */
final class TransferQueue implements AutoCloseable {
    @FunctionalInterface interface Completion{void complete(ChannelSftp channel,Job job)throws Exception;default void prepare(ChannelSftp channel,Job job)throws Exception{}}
    enum State{WAITING,SCANNING,RUNNING,COMPLETE,FAILED,CANCELLED}
    static final class Job {
        final String id=UUID.randomUUID().toString();final boolean upload;final Path local;final String remote;final long rate;final AtomicBoolean cancelled=new AtomicBoolean();
        final Completion completion;
        volatile State state=State.WAITING;volatile long bytes,total,files,done,started;volatile String current="",error="";volatile ChannelSftp channel;volatile Future<?> future;
        Job(boolean upload,Path local,String remote,long rate){this(upload,local,remote,rate,null);}
        Job(boolean upload,Path local,String remote,long rate,Completion completion){this.upload=upload;this.local=local.toAbsolutePath().normalize();this.remote=remote;this.rate=rate;this.completion=completion;}
        boolean terminal(){return state==State.COMPLETE||state==State.FAILED||state==State.CANCELLED;}
        int percent(){return state==State.COMPLETE?100:total>0?(int)Math.min(99,bytes*100.0/total):0;}
        /** Progress label: one decimal below 10 %, so a large folder visibly moves instead of sitting at 0 %. */
        String progressText(){
            if(state==State.COMPLETE)return "100%";
            if(total<=0)return files>0&&done>0?done+" / "+files:"0%";
            double value=Math.min(99.9,bytes*100.0/total);
            return value<10?String.format(java.util.Locale.ROOT,"%.1f%%",value):(int)value+"%";
        }
        String from(){return upload?local.toString():remote;}
        String to(){return upload?remote:local.toString();}
        long speed(){long elapsed=System.nanoTime()-started;return started==0||elapsed<=0?0:(long)(bytes*1_000_000_000.0/elapsed);}
        String name(){return local.getFileName().toString();}
        String status(){return switch(state){case WAITING->I18n.t("排队中","Queued");case SCANNING->I18n.t("统计大小","Counting size");case RUNNING->I18n.t("传输中","Transferring");case COMPLETE->I18n.t("已完成","Finished");case FAILED->I18n.t("失败","Failed");case CANCELLED->I18n.t("已取消","Cancelled");};}
    }
    private final Supplier<SshConnection> connection;private final Consumer<String> events;private final Executor cancellations;
    private final List<Job> jobs=new ArrayList<>();private boolean closed;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(256),r->new Thread(r,"lagless-transfer-queue"));
    TransferQueue(Supplier<SshConnection> connection,Consumer<String> events,Executor cancellations){this.connection=connection;this.events=events;this.cancellations=cancellations;}
    synchronized Job enqueue(boolean upload,Path local,String remote,long rate){return enqueue(upload,local,remote,rate,null);}
    synchronized Job enqueue(boolean upload,Path local,String remote,long rate,Completion completion){if(closed)throw new IllegalStateException(I18n.t("SSH 会话已关闭","SSH session is closed"));if(jobs.size()>=256)throw new IllegalStateException(I18n.t("队列最多保留 256 项，请先清除已结束任务","The queue holds at most 256 items; clear finished tasks first"));Job job=new Job(upload,local,remote,rate,completion);jobs.add(job);try{job.future=worker.submit(()->run(job));}catch(RejectedExecutionException e){jobs.remove(job);throw e;}return job;}
    synchronized List<Job> snapshot(){return List.copyOf(jobs);}
    synchronized void clearFinished(){jobs.removeIf(Job::terminal);}
    Job retry(Job job){if(job.state!=State.FAILED&&job.state!=State.CANCELLED)throw new IllegalArgumentException(I18n.t("仅失败或取消的任务可重新加入队列","Only failed or cancelled tasks can be queued again"));return enqueue(job.upload,job.local,job.remote,job.rate,job.completion);}
    void cancel(Job job){if(job.terminal())return;job.cancelled.set(true);if(job.state==State.WAITING){job.state=State.CANCELLED;Future<?> future=job.future;if(future!=null)future.cancel(false);worker.purge();}ChannelSftp channel=job.channel;if(channel!=null)cancellations.execute(channel::disconnect);}
    private void run(Job job){
        if(job.cancelled.get()){job.state=State.CANCELLED;return;}job.state=State.SCANNING;events.accept((job.upload?I18n.t("上传","Upload"):I18n.t("下载","Download"))+I18n.t("开始："," started: ")+job.remote);
        try{ChannelSftp channel=connection.get().openSftp();job.channel=channel;try{
            if(job.completion!=null)job.completion.prepare(channel,job);
            var measure=SftpTransfers.measure(channel,job.remote,job.local,job.upload,job.cancelled,m->{job.total=m.bytes();job.files=m.files();});job.total=measure.bytes();job.files=measure.files();job.started=System.nanoTime();job.state=State.RUNNING;
            SftpTransfers transfer=new SftpTransfers(channel,job.rate,job.cancelled,n->job.bytes=n,path->job.current=path).onFileDone(()->job.done++);if(job.upload)transfer.upload(job.local,job.remote);else transfer.download(job.remote,job.local);
            if(!job.cancelled.get()&&job.completion!=null)job.completion.complete(channel,job);
            job.state=job.cancelled.get()?State.CANCELLED:State.COMPLETE;
        }finally{job.channel=null;channel.disconnect();}}
        catch(Exception error){job.error=error.getMessage()==null?error.toString():error.getMessage();job.state=job.cancelled.get()?State.CANCELLED:State.FAILED;}
        events.accept((job.upload?I18n.t("上传","Upload"):I18n.t("下载","Download"))+job.status()+I18n.t("：",": ")+job.remote+(job.error.isBlank()?"":" · "+job.error));
    }
    @Override public synchronized void close(){if(closed)return;closed=true;jobs.forEach(this::cancel);worker.shutdown();}
}
