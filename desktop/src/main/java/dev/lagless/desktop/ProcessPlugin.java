package dev.lagless.desktop;

import dev.lagless.I18n;
import dev.lagless.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Versioned JSON-lines RPC to a trusted local process. No shell, JVM, or class loading required. */
public final class ProcessPlugin implements LaglessPlugin {
    private static final int MAX_LINE = 1024 * 1024;
    private final String name;
    private final List<String> command;
    private final Path directory;
    private final long timeout;
    private final boolean events;
    private final String configuration;
    private final java.util.function.Consumer<String> logger;
    private final BlockingQueue<String> outgoing = new ArrayBlockingQueue<>(128);
    private final ConcurrentMap<Long,CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(), skipped = new AtomicLong();
    private volatile boolean closed;
    private Process process;

    public ProcessPlugin(Path descriptor) throws IOException {
        this(descriptor,Map.of(),message->{});
    }
    public ProcessPlugin(Path descriptor,Map<String,Object> settings) throws IOException {
        this(descriptor,settings,message->{});
    }
    ProcessPlugin(Path descriptor,Map<String,Object> settings,java.util.function.Consumer<String> logger) throws IOException {
        this.logger=logger;
        if (Files.size(descriptor) > 65536) throw new IOException(I18n.t("插件描述文件过大","Plugin descriptor is too large"));
        Object config = Json.parse(Files.readString(descriptor));
        if (Json.number(config,0,"version") != 1) throw new IOException(I18n.t("不支持的插件协议版本","Unsupported plugin protocol version"));
        name = Objects.requireNonNull(Json.string(config,"name"),I18n.t("插件缺少名称","Plugin name is missing"));
        directory = descriptor.toAbsolutePath().getParent();
        List<String> args = new ArrayList<>();
        for (Object value : Json.list(config,"command")) {
            if (!(value instanceof String arg) || arg.indexOf(0)>=0) throw new IOException(I18n.t("插件命令参数无效","Invalid plugin command arguments"));
            args.add(arg);
        }
        if (args.isEmpty() || args.size()>64) throw new IOException(I18n.t("插件缺少可执行命令","Plugin command is missing"));
        Path executable = Path.of(args.get(0));
        if (!executable.isAbsolute()) executable = directory.resolve(executable).normalize();
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) throw new IOException(I18n.t("插件程序不存在或不可执行：","Plugin program is missing or not executable: ")+executable);
        args.set(0,executable.toString()); command=List.copyOf(args);
        timeout=Math.max(100,Math.min(120000,Json.number(config,30000,"timeout-ms")));
        events=Boolean.TRUE.equals(Json.get(config,"output-events"));
        Object configured=Json.get(config,"config");
        if(configured!=null&&!(configured instanceof Map<?,?>))throw new IOException(I18n.t("插件 config 须为 JSON 对象","Plugin config must be a JSON object"));
        Map<String,Object> combined=new LinkedHashMap<>();
        if(configured instanceof Map<?,?> values)for(var entry:values.entrySet())if(entry.getKey() instanceof String key)combined.put(key,entry.getValue());
        combined.putAll(settings);configuration=Json.stringify(combined);
    }
    @Override public String name() { return name; }
    public long skippedEvents() { return skipped.get(); }
    private synchronized void start() throws IOException {
        if (closed) throw new IOException(I18n.t("插件已停止，请重新启动客户端：","Plugin stopped; restart the client: ")+name);
        if (process != null) {
            if (!process.isAlive()) throw new IOException(I18n.t("插件进程已经退出：","Plugin process has exited: ")+name);
            return;
        }
        ProcessBuilder builder=new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().put("LAGLESS_PLUGIN_CONFIG",configuration);
        process=builder.start();logger.accept(name+I18n.t("：进程已启动",": process started"));
        Thread writer=new Thread(() -> {
            try (Writer out=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8))) {
                while (!closed) { out.write(outgoing.take());out.write('\n');out.flush(); }
            } catch (Exception e) { fail(e); }
        },"lagless-plugin-write"); writer.setDaemon(true);writer.start();
        Thread reader=new Thread(() -> {
            try (Reader in=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))) {
                String line;
                while ((line=readLine(in))!=null) {
                    Object reply=Json.parse(line);long id=Json.number(reply,-1,"id");
                    CompletableFuture<String> request=pending.remove(id);
                    if(request==null)continue;
                    if(Boolean.TRUE.equals(Json.get(reply,"ok")) && Json.string(reply,"text")!=null) request.complete(Json.string(reply,"text"));
                    else request.completeExceptionally(new IOException(Objects.requireNonNullElse(Json.string(reply,"error"),I18n.t("插件返回错误","Plugin returned an error"))));
                }
                fail(new EOFException(I18n.t("插件已退出：","Plugin exited: ")+name));
            } catch (Exception e) { fail(e); }
        },"lagless-plugin-read");reader.setDaemon(true);reader.start();
        Thread errors=new Thread(() -> {try(Reader in=new BufferedReader(new InputStreamReader(process.getErrorStream(),StandardCharsets.UTF_8))){
            int count=0;String line;while((line=readLine(in))!=null){if(count++<100)logger.accept(name+" [stderr] "+line.substring(0,Math.min(line.length(),1000)));else if(count==101)logger.accept(name+I18n.t("：本次进程的后续诊断输出已省略",": further diagnostic output from this process is omitted"));}
        }catch(IOException error){logger.accept(name+I18n.t("：读取诊断输出失败：",": failed to read diagnostic output: ")+error.getMessage());}},"lagless-plugin-stderr");
        errors.setDaemon(true);errors.start();
    }
    @Override public void onOutput(String sessionId,String text) throws IOException {
        if (!events) return;
        start();
        String line=Json.stringify(Map.of("version",1,"id",0,"type","output","session",sessionId,"text",text));
        if(line.length()>MAX_LINE || !outgoing.offer(line)) skipped.incrementAndGet();
    }
    @Override public String assist(String action,String text,String context) throws Exception {
        start();
        if(pending.size()>=32)throw new IOException(I18n.t("插件待处理请求过多","Too many pending plugin requests"));
        long id=nextId.incrementAndGet();CompletableFuture<String> answer=new CompletableFuture<>();pending.put(id,answer);
        try {
            String request=Json.stringify(Map.of("version",1,"id",id,"type","assist","action",action,"text",text,"context",context));
            if(request.length()>MAX_LINE || !outgoing.offer(request))throw new IOException(I18n.t("插件请求队列已满或请求过大","Plugin request queue is full or the request is too large"));
            return answer.get(timeout,TimeUnit.MILLISECONDS);
        } catch(TimeoutException e) { throw new IOException(I18n.t("插件请求超时：","Plugin request timed out: ")+name,e); }
        finally { pending.remove(id); }
    }
    static String readLine(Reader in) throws IOException {
        StringBuilder line=new StringBuilder();int c;
        while((c=in.read())!=-1){if(c=='\n')return line.toString();if(c!='\r')line.append((char)c);if(line.length()>MAX_LINE)throw new IOException(I18n.t("插件响应超过 1 MiB","Plugin response exceeds 1 MiB"));}
        return line.isEmpty()?null:line.toString();
    }
    private void fail(Exception error) {
        if(!closed)logger.accept(name+I18n.t("：进程或请求失败：",": process or request failed: ")+error);
        for(var request:pending.values())request.completeExceptionally(error);
        pending.clear();close();
    }
    @Override public synchronized void close() {
        if(closed)return;closed=true;
        logger.accept(name+I18n.t("：进程已停止",": process stopped"));
        for(var request:pending.values())request.completeExceptionally(new IOException(I18n.t("插件已关闭","Plugin is closed")));pending.clear();
        if(process!=null){
            Process active=process;active.destroy();boolean interrupted=false;
            try{if(!active.waitFor(500,TimeUnit.MILLISECONDS))active.destroyForcibly();active.waitFor();}
            catch(InterruptedException error){interrupted=true;active.destroyForcibly();}
            finally{if(interrupted)Thread.currentThread().interrupt();}
        }
        // A sentinel wakes the writer even when no requests are pending.
        outgoing.clear();outgoing.offer("");
    }
    @Override public String toString(){return name;}
}
