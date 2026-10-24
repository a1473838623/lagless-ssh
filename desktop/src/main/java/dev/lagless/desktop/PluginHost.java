package dev.lagless.desktop;

import dev.lagless.Json;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class PluginHost implements AutoCloseable {
    private final List<LaglessPlugin> plugins = new ArrayList<>();
    private final List<URLClassLoader> loaders = new ArrayList<>();
    private final Path directory;
    private final ApplicationJournal journal;
    private final Properties states=new Properties();
    private final ThreadPoolExecutor events = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),
            r -> { Thread t = new Thread(r,"lagless-plugin-events"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
    private final AtomicLong skipped = new AtomicLong();
    private final List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
    public PluginHost(Path home) throws Exception {this(home,null);}
    PluginHost(Path home,ApplicationJournal journal) throws Exception {
        this.journal=journal;
        directory=home.resolve("plugins");Files.createDirectories(directory);
        Path stateFile=directory.resolve(".states.properties");
        if(Files.isRegularFile(stateFile))try(var in=Files.newBufferedReader(stateFile)){states.load(in);}
        plugins.add(new AssistantPlugin(home));log("已加载内置插件：AI助手");loadInstalled();
    }
    private synchronized void loadInstalled() throws Exception {
        List<Path> entries=new ArrayList<>();
        try(var root=Files.list(directory)){for(Path item:root.sorted().toList()){
            if(Files.isDirectory(item))try(var sub=Files.list(item)){entries.addAll(sub.sorted().toList());}
            else entries.add(item);
        }}
        for (Path jar : entries) {
                if(!enabled(jar))continue;
                if (jar.toString().endsWith(".plugin.json")) {
                    try { plugins.add(new ProcessPlugin(jar,configuration(jar),this::log));log("已加载进程插件："+jar.getFileName()); }
                    catch (Exception e) { pluginError(jar.getFileName()+": "+e.getMessage(),e); }
                    continue;
                }
                if (!jar.toString().endsWith(".jar")) continue;
                if (DesktopRuntime.nativeImage()) { pluginError(jar.getFileName()+": 原生版请使用进程插件（.plugin.json），或使用 JVM 版加载 JAR",null); continue; }
                URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, LaglessPlugin.class.getClassLoader());
                loaders.add(loader);
                try { for(LaglessPlugin plugin:ServiceLoader.load(LaglessPlugin.class,loader)){plugin.configure(configuration(jar));plugins.add(plugin);log("已加载 Java 插件："+plugin.name());} }
                catch (Exception | ServiceConfigurationError e) { pluginError(jar.getFileName() + ": " + e,e); }
        }
    }
    private void log(String message){if(journal!=null)journal.record(null,"[插件] "+message);}
    private void pluginError(String message,Throwable error){if(errors.size()<100)errors.add(message);if(journal!=null){if(error==null)log(message);else journal.error("插件",error);}}
    Path directory(){return directory;}
    private Map<String,Object> configuration(Path item) throws java.io.IOException {
        Path file=configPath(item);if(!Files.isRegularFile(file))return Map.of();
        Object parsed=Json.parse(Files.readString(file));if(!(parsed instanceof Map<?,?> object))throw new java.io.IOException("插件配置须为 JSON 对象");
        Map<String,Object> result=new LinkedHashMap<>();for(var entry:object.entrySet())if(entry.getKey() instanceof String key)result.put(key,entry.getValue());return result;
    }
    Path configPath(Path item){
        if(Files.isDirectory(item))return item.resolve(".config.json");
        if(!item.getParent().equals(directory))return item.getParent().resolve(".config.json");
        return item.resolveSibling(item.getFileName()+".config.json");
    }
    synchronized List<Path> installed() throws java.io.IOException {
        try(var paths=Files.list(directory)){return paths.filter(p->Files.isDirectory(p)||p.toString().endsWith(".plugin.json")||p.toString().endsWith(".jar")).sorted().toList();}
    }
    synchronized boolean enabled(Path item){
        Path relative=directory.relativize(item);
        return !"false".equals(states.getProperty(relative.toString()))
                &&(relative.getNameCount()<2||!"false".equals(states.getProperty(relative.getName(0).toString())));
    }
    synchronized void setEnabled(Path item,boolean value) throws java.io.IOException {
        states.setProperty(directory.relativize(item).toString(),Boolean.toString(value));
        saveStates();
        reload();
    }
    synchronized void forget(Path item) throws java.io.IOException {states.remove(directory.relativize(item).toString());saveStates();reload();}
    private void saveStates() throws java.io.IOException {java.io.StringWriter out=new java.io.StringWriter();states.store(out,"Lagless plugin states");ProfileStore.atomicWrite(directory.resolve(".states.properties"),out.toString());}
    synchronized void reload() throws java.io.IOException {
        events.getQueue().clear();
        for(LaglessPlugin plugin:plugins)if(!(plugin instanceof AssistantPlugin))try{plugin.close();}catch(Exception ignored){}
        for(URLClassLoader loader:loaders)try{loader.close();}catch(Exception ignored){}
        plugins.removeIf(p->!(p instanceof AssistantPlugin));loaders.clear();errors.clear();
        try{loadInstalled();}catch(Exception error){pluginError("插件重新加载失败："+error.getMessage(),error);throw new java.io.IOException("插件重新加载失败",error);}
    }
    public synchronized List<LaglessPlugin> plugins() { return List.copyOf(plugins); }
    public List<String> errors() { return List.copyOf(errors); }
    public synchronized long skippedEvents() { return skipped.get()+plugins.stream().filter(p -> p instanceof ProcessPlugin).mapToLong(p -> ((ProcessPlugin)p).skippedEvents()).sum(); }
    public void output(String session, String text) {
        List<LaglessPlugin> snapshot=plugins();try { events.execute(() -> { for (var plugin : snapshot) {
            try { plugin.onOutput(session, text); } catch (Throwable e) { pluginError(plugin.name() + ": " + e,e); }
        } }); } catch (RejectedExecutionException e) { skipped.incrementAndGet(); }
    }
    @Override public synchronized void close() {
        events.shutdownNow();
        for (var plugin : plugins) { try { plugin.close(); } catch (Exception ignored) { } }
        for (var loader : loaders) { try { loader.close(); } catch (Exception ignored) { } }
    }
}
