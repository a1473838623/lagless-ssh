package dev.lagless.desktop;

import dev.lagless.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Stage a complete native bundle; a copied native helper installs it after the GUI exits. */
public final class NativeUpdater {
    private NativeUpdater(){ }
    private static boolean alive(long pid){return pid>0&&ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);}
    private static Path cache(Path home){
        String key=home.toAbsolutePath().normalize().toString();if(NativeBundle.platform().startsWith("windows"))key=key.toLowerCase(Locale.ROOT);
        return Path.of(System.getProperty("user.home"),".lagless","desktop","updates",UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString());
    }
    private static boolean writable(Path directory)throws IOException{
        try{Files.createDirectories(directory);Path probe=Files.createTempFile(directory,".write-check-",".tmp");Files.delete(probe);return true;}
        catch(AccessDeniedException error){return false;}
    }
    private static Path control(Path home,boolean create)throws IOException{
        Path local=home.toAbsolutePath().normalize().resolve(".updates"),external=cache(home);
        if(create){if(Files.exists(external.resolve("pending.json"))||Files.exists(external.resolve("result.json"))){Files.createDirectories(external);return external;}if(writable(local))return local;Files.createDirectories(external);return external;}
        return Files.exists(external.resolve("pending.json"))||Files.exists(external.resolve("result.json"))?external:local;
    }
    public static String update(boolean check)throws Exception {
        return update(check,event -> { });
    }
    public static String update(boolean check,java.util.function.Consumer<Updater.Progress> progress)throws Exception {
        if(!check&&hasPending())return "更新包已下载并准备完成，无需重复下载。请点击「重启并应用更新」。";
        progress.accept(new Updater.Progress("正在检查可用版本…",-1,-1));
        LaglessConfig config=LaglessConfig.load(LaglessConfig.defaultPath());StringWriter text=new StringWriter();
        Updater client=new Updater(new PrintWriter(text,true),config.get(null,"update-repo"),Updater.findToken(config),progress);
        Updater.Release release=client.fetchLatest(DesktopRuntime.version());
        if(release==null)return text.toString();
        if(Updater.compareVersions(DesktopRuntime.version(),release.tag())>=0)return "当前："+DesktopRuntime.version()+"\n"+(Updater.usesPrereleases(DesktopRuntime.version())?"预发布":"稳定")+"渠道最新："+release.tag()+"\n未发现更新版本。";
        String name="lagless-desktop-native-"+release.tag().replaceFirst("^v","")+"-"+NativeBundle.platform()+".zip";
        Updater.Asset asset=release.assets().get(name);
        if(asset==null)throw new IOException("此发布缺少当前平台原生包："+name);
        if(check)return "当前："+DesktopRuntime.version()+"\n可用："+release.tag()+"\n"+name;
        Path updates=control(DesktopRuntime.home(),true);
        Path downloads=updates.resolve("downloads");Files.createDirectories(downloads);
        String key=UUID.nameUUIDFromBytes((release.tag()+"\n"+name+"\n"+asset.apiUrl()+"\n"+asset.size()).getBytes(StandardCharsets.UTF_8)).toString();
        Path archive=downloads.resolve(key+".zip");
        try(var channel=FileChannel.open(downloads.resolve(key+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)){
            FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException error){throw new IOException("此更新包正在下载，请稍后查看进度",error);}
            if(lock==null)throw new IOException("此更新包正在其他进程中下载，请稍后重试");
            try(lock){Path staged=null;
            for(int attempt=0;attempt<2;attempt++){
                boolean cached=Files.isRegularFile(archive);client.downloadAsset(asset,archive);
                progress.accept(new Updater.Progress("正在校验并解压更新包…",-1,-1));
                try{
                    if(asset.size()>0&&Files.size(archive)!=asset.size())throw new IOException("原生更新包下载不完整");
                    staged=prepare(DesktopRuntime.home(),archive);
                    if(Updater.compareVersions(NativeBundle.manifest(staged,true).version(),release.tag())!=0)throw new IOException("更新包版本与发布不匹配");
                    break;
                }catch(IOException error){Updater.discardDownload(archive);if(!cached||attempt>0)throw error;progress.accept(new Updater.Progress("缓存未通过校验，正在重新下载…",-1,-1));}
            }
            if(staged==null)throw new IOException("更新包校验失败");
            progress.accept(new Updater.Progress("正在准备安装助手…",-1,-1));
            arm(DesktopRuntime.home(),staged);
            }
        }
        return "更新包已校验，安装助手已就绪。点击「重启并应用更新」替换当前安装目录；也可退出全部窗口后等待安装完成。\n安装目录："+DesktopRuntime.home();
    }
    /** Also used by authenticated backup restore. No application file is touched yet. */
    public static Path prepare(Path home,Path archive)throws IOException {
        Path updates=control(home,true);
        Path staged=updates.resolve("candidate-"+UUID.randomUUID());NativeBundle.extract(archive,staged,true);
        return staged;
    }
    public static void stage(Path home,Path archive,boolean arm)throws Exception {
        Path staged=prepare(home,archive);if(arm)arm(home,staged);
    }
    public static void rollback()throws Exception {
        Path home=DesktopRuntime.home(),archive=home.resolve(".updates/previous.zip");
        if(!Files.isRegularFile(archive))throw new IOException("没有上一版原生包备份");stage(home,archive,true);
    }
    public static void arm(Path home,Path staged)throws Exception {
        home=home.toAbsolutePath().normalize();staged=staged.toAbsolutePath().normalize();Path updates=staged.getParent();
        if(!updates.equals(home.resolve(".updates"))&&!updates.equals(cache(home)))throw new IOException("更新暂存目录无效");
        try(var channel=java.nio.channels.FileChannel.open(updates.resolve("staging.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock lock;
            try{lock=channel.tryLock();}catch(java.nio.channels.OverlappingFileLockException e){throw new IOException("另一个更新操作正在准备，请稍后重试",e);}
            if(lock==null)throw new IOException("另一个进程正在准备更新，请稍后重试");
            try(lock){armLocked(home,staged,updates);}
        }
    }
    private static void armLocked(Path home,Path staged,Path updates)throws Exception {
        NativeBundle.Manifest next=NativeBundle.validate(staged,true),current=NativeBundle.validate(home,true);
        String nonce=UUID.randomUUID().toString();Path request=updates.resolve("request-"+nonce+".json");
        // One pending update per installation, including across separate desktop windows.
        Path lock=updates.resolve("pending.json");
        if(Files.exists(lock)){
            Object previous=Json.parse(Files.readString(lock));long pid=Json.number(previous,-1,"helper");
            if(alive(pid))throw new IOException("已经有等待退出的更新，请先完成该更新");
            Files.delete(lock);
        }
        Files.deleteIfExists(updates.resolve("result.json"));
        Files.writeString(updates.resolve("helper.stderr.log"),"");
        ProfileStore.atomicWrite(request,Json.stringify(Map.of("home",home.toString(),"staged",staged.toString(),"pid",ProcessHandle.current().pid(),"nonce",nonce)));
        // A private complete runtime avoids missing DLLs and loading libraries
        // from the installation that this helper is about to replace.
        // Upgrade with the new installer code; rollback uses the newer current
        // installer, rather than reverting its fixes along with the application.
        boolean upgrade=Updater.compareVersions(next.version(),current.version())>=0;
        Path installerSource=upgrade?staged:home;NativeBundle.Manifest installer=upgrade?next:current;
        Path helperRoot=updates.resolve("installer-"+nonce);Files.createDirectory(helperRoot);copyManaged(installerSource,helperRoot,installer);
        Files.copy(installerSource.resolve(NativeBundle.MANIFEST),helperRoot.resolve(NativeBundle.MANIFEST));
        Path helper=helperRoot.resolve(installer.executable());
        helper.toFile().setExecutable(true,true);
        boolean elevate=NativeBundle.platform().startsWith("windows")&&!writable(home);
        long pid=launchHelper(helper,request,updates,elevate);
        ProfileStore.atomicWrite(lock,Json.stringify(Map.of("helper",pid,"request",request.toString(),"nonce",nonce)));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(!Files.isRegularFile(updates.resolve("ready-"+nonce+".json"))){
            if(!alive(pid)){Files.deleteIfExists(lock);throw new IOException("更新助手启动失败，未替换程序。"+failureDetails(updates));}
            if(System.nanoTime()>deadline)throw new IOException("更新助手未确认就绪，不能确认更新成功。日志："+updates);
            Thread.sleep(100);
        }
    }
    private static String literal(String value){return "'"+value.replace("'","''")+"'";}
    private static long launchHelper(Path helper,Path request,Path updates,boolean elevate)throws Exception{
        if(!elevate)return new ProcessBuilder(helper.toString(),"--apply-update",request.toString()).directory(helper.getParent().toFile())
                .redirectOutput(updates.resolve("helper.stdout.log").toFile()).redirectError(updates.resolve("helper.stderr.log").toFile()).start().pid();
        // Elevate installation only. The separately launched restarter keeps
        // the original user's privileges instead of running the SSH client as admin.
        String script="$ErrorActionPreference='Stop';try{$p=Start-Process -FilePath "+literal(helper.toString())+" -WorkingDirectory "+literal(helper.getParent().toString())
                +" -ArgumentList @('--apply-update',"+literal("\""+request+"\"")+") -Verb RunAs -WindowStyle Hidden -PassThru;[Console]::Out.WriteLine($p.Id)}catch{[Console]::Error.WriteLine($_.Exception.Message);exit 1}";
        Path powershell=Path.of(System.getenv().getOrDefault("SystemRoot","C:\\Windows"),"System32","WindowsPowerShell","v1.0","powershell.exe");
        Process launcher=new ProcessBuilder(powershell.toString(),"-NoProfile","-NonInteractive","-WindowStyle","Hidden","-EncodedCommand",Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
                .redirectError(updates.resolve("elevation.stderr.log").toFile()).start();
        String output=new String(launcher.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip();
        if(launcher.waitFor()!=0||!output.matches("[0-9]+"))throw new IOException("未取得安装权限，原程序未修改。请允许 Windows 的管理员权限提示。"+failureDetails(updates));
        return Long.parseLong(output);
    }
    private static String failureDetails(Path updates){
        StringBuilder text=new StringBuilder("\n更新日志："+updates);
        for(String name:List.of("result.json","helper.stderr.log","elevation.stderr.log"))try{
            Path file=updates.resolve(name);if(Files.isRegularFile(file)&&Files.size(file)<65536){String value=Files.readString(file).strip();if(!value.isEmpty())text.append("\n").append(value);}
        }catch(IOException ignored){}return text.toString();
    }
    private record Request(Path home,Path staged,Path control,String nonce,long parent){}
    private static Request readRequest(Path request)throws IOException{
        request=request.toAbsolutePath().normalize();Object json=Json.parse(Files.readString(request));
        Path home=Path.of(Objects.requireNonNull(Json.string(json,"home"))).toAbsolutePath().normalize(),staged=Path.of(Objects.requireNonNull(Json.string(json,"staged"))).toAbsolutePath().normalize(),updates=request.getParent();
        String nonce=Json.string(json,"nonce");long parent=Json.number(json,-1,"pid");
        if(nonce==null||!nonce.matches("[0-9a-f-]{36}")||!request.getFileName().toString().equals("request-"+nonce+".json")||!updates.equals(staged.getParent())||!staged.getFileName().toString().startsWith("candidate-")||parent<=0)throw new IOException("非法原生更新请求");
        if(!updates.equals(home.resolve(".updates"))&&!updates.equals(cache(home))&&!(DesktopRuntime.nativeImage()&&DesktopRuntime.home().normalize().equals(updates.resolve("installer-"+nonce))))throw new IOException("非法原生更新控制目录");
        NativeBundle.rejectLinks(updates,staged);return new Request(home,staged,updates,nonce,parent);
    }
    public static void applyRequest(Path request)throws Exception {
        Request r=readRequest(request);Path pending=r.control().resolve("pending.json");
        if(r.parent()==ProcessHandle.current().pid())throw new IOException("更新父进程无效");
        try{
            NativeBundle.validate(r.staged(),true);
            phase(r,"等待当前程序和占用文件的进程退出");
            ProfileStore.atomicWrite(r.control().resolve("ready-"+r.nonce()+".json"),Json.stringify(Map.of("nonce",r.nonce(),"helper",ProcessHandle.current().pid())));
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(10);
            while(alive(r.parent())||installationRunning(r.home())){
                if(System.nanoTime()>deadline)throw new IOException("等待旧程序退出或文件释放超时，未替换程序。请关闭该安装目录的全部实例后重试。"+fileUserDetails(r.home()));Thread.sleep(500);
            }
            phase(r,"正在替换并校验程序文件");
            apply(r.home(),r.staged());writeResult(r,Map.of("ok",true,"version",NativeBundle.manifest(r.home(),true).version(),"nonce",r.nonce()));
            phase(r,"安装完成");
        }catch(Exception error){StringWriter details=new StringWriter();error.printStackTrace(new PrintWriter(details));phase(r,"更新失败："+error);writeResult(r,Map.of("ok",false,"error",error.toString(),"details",details.toString(),"nonce",r.nonce()));throw error;}
        finally{
            if(Files.exists(pending)&&r.nonce().equals(Json.string(Json.parse(Files.readString(pending)),"nonce")))Files.delete(pending);
        }
    }
    private static boolean installationRunning(Path home)throws IOException{
        long[] users=installationFileUsers(home);if(users.length>0)return true;
        Path executable=home.resolve(NativeBundle.platform().startsWith("windows")?"Lagless.exe":"Lagless");
        try(var processes=ProcessHandle.allProcesses()){
            return processes.anyMatch(p->p.pid()!=ProcessHandle.current().pid()&&p.info().command().map(command->Path.of(command).toAbsolutePath().normalize().equals(executable)).orElse(false));
        }
    }
    private static long[] installationFileUsers(Path home)throws IOException{
        if(!NativeBundle.platform().startsWith("windows"))return new long[0];
        if(!NativeGraphics.bridgeAvailable())throw new IOException("无法确认安装文件是否仍被占用，请使用完整原生包更新");
        List<String> files=new ArrayList<>();files.add(home.resolve(NativeBundle.MANIFEST).toString());
        for(var file:NativeBundle.manifest(home,true).files())if(Files.isRegularFile(home.resolve(file.path())))files.add(home.resolve(file.path()).toString());
        try{long[] users=NativeGraphics.fileUsers(files.toArray(String[]::new));if(users==null)throw new IOException("查询文件占用失败："+NativeGraphics.error());
            for(long pid:users)if(pid==ProcessHandle.current().pid())throw new IOException("安装助手仍占用目标目录文件，无法安全替换，请使用完整包手动升级");return users;
        }catch(UnsatisfiedLinkError error){throw new IOException("安装助手缺少文件占用检测，请使用完整新版原生包",error);}
    }
    private static String fileUserDetails(Path home){try{return " 文件占用进程 PID："+Arrays.toString(installationFileUsers(home));}catch(IOException error){return " "+error.getMessage();}}
    private static void phase(Request request,String message){try{ProfileStore.atomicWrite(request.control().resolve("install-state.json"),Json.stringify(Map.of("nonce",request.nonce(),"time",java.time.Instant.now().toString(),"message",message)));}catch(IOException error){error.printStackTrace(System.err);}}
    private static void writeResult(Request r,Map<String,Object> value)throws IOException{
        String text=Json.stringify(value);ProfileStore.atomicWrite(r.control().resolve("result.json"),text);
        Path legacy=r.home().resolve(".updates");
        if(!legacy.equals(r.control()))try{Files.createDirectories(legacy);ProfileStore.atomicWrite(legacy.resolve("result.json"),text);}catch(IOException ignored){}
    }
    public static boolean hasPending(){
        try{Path pending=control(DesktopRuntime.home(),false).resolve("pending.json");return Files.isRegularFile(pending)&&alive(Json.number(Json.parse(Files.readString(pending)),-1,"helper"));}
        catch(Exception error){return false;}
    }
    public static String status(){
        Path home=DesktopRuntime.home();String text="当前程序："+home+"\n运行版本："+DesktopRuntime.version();
        try{Path updates=control(home,false);if(hasPending()){
            String state="";Path phase=updates.resolve("install-state.json");
            if(Files.isRegularFile(phase)){Object value=Json.parse(Files.readString(phase)),pending=Json.parse(Files.readString(updates.resolve("pending.json")));if(Objects.equals(Json.string(value,"nonce"),Json.string(pending,"nonce")))state="\n安装状态："+Json.string(value,"message");}
            return text+"\n有待应用的更新，请点击「重启并应用更新」。"+state+"\n更新日志："+updates;}
            Path result=updates.resolve("result.json");if(Files.isRegularFile(result)){Object value=Json.parse(Files.readString(result));text+=Boolean.TRUE.equals(Json.get(value,"ok"))?"\n上次安装成功："+Json.string(value,"version"):"\n上次更新失败："+Json.string(value,"error");}
            else if(Files.exists(updates.resolve("pending.json")))text+="\n上次更新助手已退出，更新未确认完成。"+failureDetails(updates);
            text+="\n更新日志："+updates;
        }catch(Exception error){text+="\n更新状态读取失败："+error.getMessage();}return text;
    }
    public static void requestRestart()throws Exception{
        if(!hasPending())throw new IOException("没有可应用的更新，请重新下载并确认安装助手就绪。");
        Object pending=Json.parse(Files.readString(control(DesktopRuntime.home(),false).resolve("pending.json")));
        Path request=Path.of(Objects.requireNonNull(Json.string(pending,"request")));Request r=readRequest(request);
        Path helperRoot=r.control().resolve("installer-"+r.nonce());
        Path helper=helperRoot.resolve(NativeBundle.manifest(helperRoot,true).executable());
        new ProcessBuilder(helper.toString(),"--restart-after-update",request.toString()).directory(helper.getParent().toFile())
                .redirectOutput(r.control().resolve("restart.stdout.log").toFile()).redirectError(r.control().resolve("restart.stderr.log").toFile()).start();
    }
    /** A launch while replacement is pending must release the old executable. */
    public static boolean handoffPendingLaunch()throws Exception{if(!hasPending())return false;requestRestart();return true;}
    public static void restartAfterUpdate(Path request)throws Exception{
        Request r=readRequest(request);
        try(var channel=FileChannel.open(r.control().resolve("restart.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)){
            FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException ignored){return;}if(lock==null)return;
            try(lock){long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(11);
                while(true){
                    Path result=r.control().resolve("result.json");
                    if(Files.isRegularFile(result)){
                        Object value=Json.parse(Files.readString(result));
                        if(r.nonce().equals(Json.string(value,"nonce"))){
                            if(!Boolean.TRUE.equals(Json.get(value,"ok")))throw new IOException("更新未成功，旧版已保留："+Json.string(value,"error")+"\n日志："+r.control());
                            if(!Files.exists(r.control().resolve("pending.json"))){NativeBundle.Manifest installed=NativeBundle.validate(r.home(),true);new ProcessBuilder(r.home().resolve(installed.executable()).toString()).directory(r.home().toFile()).start();return;}
                        }
                    }
                    if(System.nanoTime()>deadline)throw new IOException("等待更新完成超时。日志："+r.control());Thread.sleep(250);
                }
            }
        }
    }
    public static synchronized void apply(Path home,Path staged)throws Exception {
        NativeBundle.Manifest next=NativeBundle.validate(staged,true),old=NativeBundle.validate(home,true);
        Path updates=home.resolve(".updates");Files.createDirectories(updates);
        Path backup=Files.createTempFile(updates,"previous-",".zip");NativeBundle.pack(home,backup);
        boolean preserveBackup=false;
        Set<String> added=new HashSet<>();next.files().forEach(e -> added.add(e.path()));old.files().forEach(e -> added.remove(e.path()));
        try {
            copyManaged(staged,home,next);
            for(var file:old.files())if(next.files().stream().noneMatch(e -> e.path().equals(file.path())))Files.deleteIfExists(home.resolve(file.path()));
            Files.copy(staged.resolve(NativeBundle.MANIFEST),home.resolve(NativeBundle.MANIFEST),StandardCopyOption.REPLACE_EXISTING);
            NativeBundle.validate(home,true);
            Files.move(backup,updates.resolve("previous.zip"),StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception error){
            Path recovery=updates.resolve("recovery-"+UUID.randomUUID());
            try {NativeBundle.Manifest original=NativeBundle.extract(backup,recovery,true);copyManaged(recovery,home,original);
                Files.copy(recovery.resolve(NativeBundle.MANIFEST),home.resolve(NativeBundle.MANIFEST),StandardCopyOption.REPLACE_EXISTING);
                for(String path:added)Files.deleteIfExists(home.resolve(path));
                NativeBundle.validate(home,true);
            }catch(Exception rollback){preserveBackup=true;error.addSuppressed(new IOException("回滚未完成，恢复备份已保留："+backup,rollback));}throw error;
        }finally{if(!preserveBackup)Files.deleteIfExists(backup);}
    }
    private static void copyManaged(Path source,Path home,NativeBundle.Manifest manifest)throws IOException {
        for(var file:manifest.files()) {
            Path target=home.resolve(file.path());NativeBundle.rejectLinks(home,target);
            if(Files.isRegularFile(target)&&Files.size(target)==file.size()&&NativeBundle.sha256(target).equals(file.sha256()))continue;
            Files.createDirectories(target.getParent());Path temp=Files.createTempFile(target.getParent(),".replace-",".tmp");
            try {Files.copy(source.resolve(file.path()),temp,StandardCopyOption.REPLACE_EXISTING);
                replaceFile(temp,target);
                if(file.executable())target.toFile().setExecutable(true,false);
            }finally{Files.deleteIfExists(temp);}
        }
    }
    private static void replaceFile(Path temp,Path target)throws IOException{
        for(int attempt=0;;attempt++)try{
            try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException error){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}return;
        }catch(AccessDeniedException error){
            if(attempt>=39)throw new IOException("无法替换 "+target+"；文件仍被占用或缺少替换权限。请关闭使用此目录的程序并确认安装权限。",error);
            try{Thread.sleep(250);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("等待替换文件被中断："+target,interrupted);}
        }
    }
}
