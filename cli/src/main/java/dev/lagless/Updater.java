package dev.lagless;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Checks the project's GitHub releases and replaces the running jar with a
 * newer one.
 *
 * <p>Only the jar is replaced. A bundled Java runtime, when present, is left
 * alone: its files are in use while lagless runs, and it changes far less often
 * than the program. When a release needs a new runtime the user is told to take
 * the full zip instead.
 */
public final class Updater {

    public static final String DEFAULT_REPO = "a1473838623/lagless-ssh";

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Object UPDATE_LOCK = new Object();

    /** One release as far as lagless cares. */
    public record Release(String tag, Map<String, Asset> assets) {
    }

    public record Asset(String name, String apiUrl, String browserUrl, long size) {
    }

    /** Negative received means a preparation stage; negative total means unknown size. */
    public record Progress(String message,long received,long total) { }

    private final PrintWriter out;
    private final String repo;
    private final String token;
    private final java.util.function.Consumer<Progress> progress;

    public Updater(PrintWriter out, String repo, String token) {
        this(out,repo,token,event -> { });
    }
    public Updater(PrintWriter out,String repo,String token,java.util.function.Consumer<Progress> progress) {
        this.out = out;
        this.repo = repo == null || repo.isBlank() ? DEFAULT_REPO : repo;
        this.token = token;
        this.progress = java.util.Objects.requireNonNull(progress);
    }

    // ------------------------------------------------------------ versions

    private static final java.util.regex.Pattern VERSION=java.util.regex.Pattern.compile("v?[0-9]+(?:\\.[0-9]+)*(?:-[0-9a-z.-]+)?(?:\\+[0-9a-z.-]+)?",java.util.regex.Pattern.CASE_INSENSITIVE);
    private record Version(String[] core,String[] preview){}
    /** Numeric versions and SemVer prereleases; a final release sorts after its RCs. */
    public static int compareVersions(String a, String b) {
        Version x=parseVersion(a),y=parseVersion(b);
        for(int i=0;i<Math.max(x.core().length,y.core().length);i++){
            int difference=compareNumber(i<x.core().length?x.core()[i]:"0",i<y.core().length?y.core()[i]:"0");if(difference!=0)return difference;
        }
        if(x.preview().length==0||y.preview().length==0)return x.preview().length==0?(y.preview().length==0?0:1):-1;
        for(int i=0;i<Math.min(x.preview().length,y.preview().length);i++){
            String left=x.preview()[i],right=y.preview()[i];boolean ln=left.matches("[0-9]+"),rn=right.matches("[0-9]+");
            int difference=ln&&rn?compareNumber(left,right):ln!=rn?(ln?-1:1):left.compareTo(right);if(difference!=0)return difference;
        }
        return Integer.compare(x.preview().length,y.preview().length);
    }
    private static int compareNumber(String left,String right){
        left=left.replaceFirst("^0+(?!$)","");right=right.replaceFirst("^0+(?!$)","");
        return left.length()==right.length()?left.compareTo(right):Integer.compare(left.length(),right.length());
    }
    private static Version parseVersion(String value){
        String text=value==null?"":value.trim().toLowerCase(Locale.ROOT).replaceFirst("^v","");
        if(!VERSION.matcher(text).matches())return new Version(new String[]{"0"},new String[]{"dev"});
        text=text.split("\\+",2)[0];String[] parts=text.split("-",2);
        return new Version(parts[0].split("\\."),parts.length==1?new String[0]:parts[1].split("\\."));
    }
    public static boolean usesPrereleases(String version){return VERSION.matcher(version==null?"":version.trim()).matches()&&parseVersion(version).preview().length>0;}

    // -------------------------------------------------------------- tokens

    /**
     * A token is needed while the repository is private. Looked up in the
     * environment, then in ~/.lagless/config, then from the GitHub CLI.
     */
    public static String findToken(LaglessConfig config) {
        for (String name : new String[] {"LAGLESS_GITHUB_TOKEN", "GITHUB_TOKEN", "GH_TOKEN"}) {
            String v = System.getenv(name);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        if (config != null) {
            String v = config.get(null, "github-token");
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return ghCliToken();
    }

    private static String ghCliToken() {
        try {
            // Only start gh when it is installed; see Installation.findOnPath.
            java.nio.file.Path gh = Installation.findOnPath("gh");
            if (gh == null) return null;
            Process p = new ProcessBuilder(gh.toString(), "auth", "token").redirectErrorStream(false).start();
            String output;
            try (InputStream in = p.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 && !output.isBlank() ? output : null;
        } catch (Exception e) {
            Log.d("gh auth token unavailable: " + e);
            return null;
        }
    }

    // ------------------------------------------------------------- network

    private HttpClient client() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    private HttpRequest.Builder request(URI uri) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("User-Agent", "lagless-ssh")
                .header("X-GitHub-Api-Version", "2022-11-28");
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    /** Latest published release, or null when the request was refused. */
    public Release fetchLatest() throws IOException, InterruptedException {
        return fetchLatest(Installation.version());
    }
    /** RC installations follow published prereleases as well as stable releases. */
    public Release fetchLatest(String installedVersion) throws IOException, InterruptedException {
        boolean previews=usesPrereleases(installedVersion);
        URI uri = URI.create("https://api.github.com/repos/" + repo + (previews?"/releases?per_page=100":"/releases/latest"));
        HttpRequest req = request(uri).header("Accept", "application/vnd.github+json").GET().build();
        HttpResponse<String> res = client().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() == 404) {
            out.println("lagless: no release found at " + repo + ".");
            if (token == null) {
                out.println("The repository may be private. Set GITHUB_TOKEN, put github-token in");
                out.println("~/.lagless/config, or sign in with the GitHub CLI (gh auth login).");
            }
            return null;
        }
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            out.println("lagless: GitHub refused the request (HTTP " + res.statusCode() + ").");
            out.println("The token may be missing the repo scope, or the rate limit was hit.");
            return null;
        }
        if (res.statusCode() != 200) {
            out.println("lagless: GitHub returned HTTP " + res.statusCode() + ".");
            return null;
        }
        Release release=selectLatestPublished(Json.parse(res.body()),previews);
        if(release==null)out.println("lagless: no published release found in the "+(previews?"prerelease":"stable")+" channel.");
        return release;
    }
    static Release selectLatestPublished(Object document,boolean previews){
        if(document==null)return null;
        Release selected=null;
        for(Object root:document instanceof List<?> list?list:List.of(document)){
            String tag=Json.string(root,"tag_name");
            if(tag==null||!VERSION.matcher(tag).matches()||Boolean.TRUE.equals(Json.get(root,"draft"))||!previews&&(Boolean.TRUE.equals(Json.get(root,"prerelease"))||usesPrereleases(tag)))continue;
            Release candidate=readRelease(root);
            if(selected==null||compareVersions(candidate.tag(),selected.tag())>0)selected=candidate;
        }
        return selected;
    }
    private static Release readRelease(Object root){
        String tag=Json.string(root,"tag_name");
        Map<String, Asset> assets = new java.util.LinkedHashMap<>();
        for (Object a : Json.list(root, "assets")) {
            String name = Json.string(a, "name");
            if (name != null) {
                assets.put(name, new Asset(name, Json.string(a, "url"),
                        Json.string(a, "browser_download_url"), Json.number(a, -1, "size")));
            }
        }
        return new Release(tag, assets);
    }

    private void download(Asset asset, Path dest) throws IOException, InterruptedException {
        String identity=java.util.UUID.nameUUIDFromBytes((asset.name()+"\n"+asset.apiUrl()+"\n"+asset.browserUrl()+"\n"+asset.size()).getBytes(StandardCharsets.UTF_8)).toString();
        Path metadata=downloadMetadata(dest);Object saved=null;
        try{if(Files.isRegularFile(metadata))saved=Json.parse(Files.readString(metadata));}catch(IOException|RuntimeException ignored){}
        boolean matching=identity.equals(Json.string(saved,"identity"));
        long offset=matching&&Files.isRegularFile(dest)?Files.size(dest):0;
        if(offset>0&&(asset.size()>0&&offset==asset.size()||asset.size()<=0&&Boolean.TRUE.equals(Json.get(saved,"complete")))){
            progress.accept(new Progress(I18n.t("复用已下载的更新包","Reusing the downloaded update package"),-1,-1));progress.accept(new Progress(asset.name(),offset,offset));return;
        }
        if(asset.size()>0&&offset>=asset.size())offset=0;
        String validator=matching?Json.string(saved,"validator"):null;
        for(int attempt=0;attempt<2;attempt++){
        progress.accept(new Progress(offset>0?I18n.t("正在从 ","Resuming download from ")+offset+I18n.t(" 字节处继续下载…"," bytes…"):I18n.t("正在连接下载服务器…","Connecting to the download server…"),-1,-1));
        // The asset API URL works for private repositories too; it answers with
        // a redirect to storage that must be followed without the token.
        URI uri = URI.create(asset.apiUrl() != null ? asset.apiUrl() : asset.browserUrl());
        HttpRequest.Builder initial=request(uri).header("Accept", "application/octet-stream");
        range(initial,offset,validator);HttpRequest req=initial.GET().build();
        HttpResponse<InputStream> res = client().send(req, HttpResponse.BodyHandlers.ofInputStream());
        int code = res.statusCode();
        if (code >= 300 && code < 400) {
            Optional<String> location = res.headers().firstValue("location");
            res.body().close();
            if (location.isEmpty()) {
                throw new IOException("GitHub redirected without a location header");
            }
            HttpRequest.Builder redirected=HttpRequest.newBuilder(URI.create(location.get()))
                    .timeout(TIMEOUT).header("User-Agent", "lagless-ssh");
            range(redirected,offset,validator);HttpRequest plain=redirected.GET().build();
            progress.accept(new Progress(I18n.t("正在连接更新包存储服务器…","Connecting to the update storage server…"),-1,-1));
            res = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
                    .send(plain, HttpResponse.BodyHandlers.ofInputStream());
            code = res.statusCode();
        }
        if(code==416&&offset>0){res.body().close();offset=0;validator=null;progress.accept(new Progress(I18n.t("服务器无法续传，正在重新下载…","Server cannot resume; downloading again…"),-1,-1));continue;}
        if (code != 200&&code!=206) {
            res.body().close();
            throw new IOException("download failed with HTTP " + code);
        }
        long total=asset.size()>0?asset.size():res.headers().firstValueAsLong("Content-Length").orElse(-1);
        String nextValidator=res.headers().firstValue("ETag").filter(v->!v.startsWith("W/")).orElse(res.headers().firstValue("Last-Modified").orElse(""));
        if(code==206){
            java.util.regex.Matcher range=java.util.regex.Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(res.headers().firstValue("Content-Range").orElse(""));
            boolean valid=false;
            try{if(range.matches()){long first=Long.parseLong(range.group(1)),last=Long.parseLong(range.group(2)),length=Long.parseLong(range.group(3));valid=first==offset&&last>=first&&last<length&&(asset.size()<=0||asset.size()==length)&&(validator==null||validator.isEmpty()||nextValidator.isEmpty()||validator.equals(nextValidator));total=length;}}catch(NumberFormatException ignored){}
            if(!valid){res.body().close();if(offset>0&&attempt==0){offset=0;validator=null;progress.accept(new Progress(I18n.t("续传响应不匹配，正在重新下载…","Resume response mismatch; downloading again…"),-1,-1));continue;}throw new IOException(I18n.t("更新包续传响应无效","Invalid resume response for the update package"));}
        }else if(offset>0){offset=0;progress.accept(new Progress(I18n.t("服务器不支持续传，正在重新下载…","Server does not support resuming; downloading again…"),-1,-1));}
        try (InputStream in = res.body();OutputStream target=offset>0?Files.newOutputStream(dest,StandardOpenOption.APPEND):Files.newOutputStream(dest)) {
            // Truncate a different/obsolete representation before changing its
            // identity: a crash must not bind old bytes to the new asset.
            saveDownloadMetadata(metadata,identity,nextValidator,false);
            long received=offset,last=System.nanoTime();byte[] bytes=new byte[65536];
            progress.accept(new Progress(asset.name(),received,total));
            int n;while((n=in.read(bytes))!=-1){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("Update download interrupted");
                target.write(bytes,0,n);received+=n;long now=System.nanoTime();
                if(now-last>=150_000_000L){progress.accept(new Progress(asset.name(),received,total));last=now;}
            }
            progress.accept(new Progress(asset.name(),received,total));
            if(total>0&&received!=total)throw new IOException(I18n.t("更新包尚未下载完整，已保留 ","Update package is incomplete; kept ")+received+I18n.t(" 字节供下次续传"," bytes to resume next time"));
        }
        saveDownloadMetadata(metadata,identity,nextValidator,true);return;
        }
        throw new IOException(I18n.t("更新包无法续传，请重试下载","Update package cannot resume; download again"));
    }

    private static void range(HttpRequest.Builder request,long offset,String validator){if(offset>0){request.header("Range","bytes="+offset+"-");if(validator!=null&&!validator.isBlank())request.header("If-Range",validator);}}
    private static Path downloadMetadata(Path dest){return dest.resolveSibling(dest.getFileName()+".download.json");}
    private static void saveDownloadMetadata(Path path,String identity,String validator,boolean complete)throws IOException{
        Path temp=Files.createTempFile(path.toAbsolutePath().getParent(),".lagless-download-",".tmp");
        try{Files.writeString(temp,Json.stringify(Map.of("identity",identity,"validator",validator,"complete",complete)));
            try{Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(java.nio.file.AtomicMoveNotSupportedException error){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
    }
    public static void discardDownload(Path dest)throws IOException{Files.deleteIfExists(dest);Files.deleteIfExists(downloadMetadata(dest));}

    /** Shared authenticated transport; native bundle validation stays in the desktop module. */
    public void downloadAsset(Asset asset, Path dest) throws IOException, InterruptedException { download(asset,dest); }

    // -------------------------------------------------------------- update

    /** Reports whether a newer release exists without changing anything. */
    public int check() throws IOException, InterruptedException {
        Release latest = fetchLatest();
        if (latest == null) {
            return 1;
        }
        String current = Installation.version();
        out.println("installed: " + current);
        out.println("latest:    " + latest.tag() + "  (" + repo + ")");
        if (compareVersions(current, latest.tag()) < 0) {
            out.println("A newer version is available. Install it with: lagless update");
        } else {
            out.println("Already up to date.");
        }
        return 0;
    }

    /** Downloads the newest jar and puts it in place. */
    public int update() throws IOException, InterruptedException {
        synchronized (UPDATE_LOCK) { return updateLocked(); }
    }

    private int updateLocked() throws IOException, InterruptedException {
        Path jar = Installation.runningJar();
        if (jar == null) {
            out.println("lagless: running from class files, not a jar; nothing to update.");
            return 1;
        }
        Release latest = fetchLatest();
        if (latest == null) {
            return 1;
        }
        String current = Installation.version();
        if (compareVersions(current, latest.tag()) >= 0) {
            out.println("Already up to date (" + current + ").");
            return 0;
        }
        String jarAsset = Installation.updateAssetName(Installation.distribution());
        Asset asset = latest.assets().get(jarAsset);
        if (asset == null) {
            out.println("lagless: release " + latest.tag() + " has no " + jarAsset + " to download. The installed edition is unchanged.");
            out.println("Assets: " + String.join(", ", latest.assets().keySet()));
            return 1;
        }
        out.println("updating " + current + " -> " + latest.tag());
        Path staged = fetchToStaging(jar, asset);
        if (staged == null) {
            out.println("lagless: the download was incomplete or not a jar. Nothing changed.");
            return 1;
        }

        // Unix lets the running jar be renamed out of the way, so the new one
        // can go in immediately. Windows locks it against renaming too, and
        // there the download simply stays put: the launcher script moves a
        // staged jar into place before the next JVM starts.
        Path old = retiredName(jar);
        try {
            Files.move(jar, old, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.d("the running jar cannot be replaced in place: " + e);
            out.println("Downloaded " + latest.tag() + ".");
            out.println("The launcher puts it in place as this command exits; check with:"
                    + " lagless version");
            return 0;
        }
        try {
            Files.move(staged, jar, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(old, jar, StandardCopyOption.REPLACE_EXISTING);
            throw e;
        }
        try {
            Files.deleteIfExists(old);
        } catch (IOException e) {
            Log.d("previous jar still locked, will be removed on the next start: " + e);
        }

        out.println("Installed " + latest.tag() + " into " + jar);
        if (Installation.hasBundledRuntime() && hasNewerRuntimeBundle(latest)) {
            out.println();
            out.println("This release also ships a new bundled-runtime zip. The runtime here was");
            out.println("left untouched; download the zip if the release notes ask for it.");
        }
        out.println("Restart lagless to use it.");
        return 0;
    }

    /**
     * Downloads the asset beside the jar and publishes it as "<jar>.new" only
     * once it is complete and valid, so a killed download leaves nothing the
     * launcher would pick up. Returns the staged path, or null on a bad
     * download.
     */
    private Path fetchToStaging(Path jar, Asset asset) throws IOException, InterruptedException {
        Path part = jar.resolveSibling(jar.getFileName() + ".part");
        Path staged = jar.resolveSibling(jar.getFileName() + ".new");
        download(asset, part);
        progress.accept(new Progress(I18n.t("正在校验更新文件…","Verifying update files…"),-1,-1));
        long size = Files.size(part);
        if (asset.size() > 0 && size != asset.size() || !isCompatibleUpdate(jar, part)) {
            discardDownload(part);
            Log.d("rejected download: " + size + " bytes, expected " + asset.size());
            return null;
        }
        Path rollback = jar.resolveSibling(jar.getFileName() + ".rollback");
        Path rollbackPart = jar.resolveSibling(jar.getFileName() + ".rollback.part");
        Files.copy(jar, rollbackPart, StandardCopyOption.REPLACE_EXISTING);
        Files.move(rollbackPart, rollback, StandardCopyOption.REPLACE_EXISTING);
        Files.move(part, staged, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(downloadMetadata(part));
        return staged;
    }

    /**
     * Background variant: downloads a newer jar without printing anything and
     * leaves it staged for the launcher. Returns a one-line note when something
     * was staged, null when there was nothing to do or it did not work out.
     */
    public String stageNewerQuietly() {
        synchronized (UPDATE_LOCK) { return stageNewerLocked(); }
    }

    private String stageNewerLocked() {
        try {
            Path jar = Installation.runningJar();
            if (jar == null) {
                return null;
            }
            Release latest = fetchLatest();
            if (latest == null) {
                return null;
            }
            String current = Installation.version();
            if (compareVersions(current, latest.tag()) >= 0) {
                return null;
            }
            Asset asset = latest.assets().get(Installation.updateAssetName(Installation.distribution()));
            if (asset == null) {
                return null;
            }
            if (fetchToStaging(jar, asset) == null) {
                return null;
            }
            Log.d("staged " + latest.tag() + " for the next start");
            return "lagless " + latest.tag() + " downloaded (you are on " + current
                    + "); the next run uses it, nothing to do.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            Log.d("background update check failed: " + e);
            return null;
        }
    }

    private static boolean hasNewerRuntimeBundle(Release latest) {
        return latest.assets().keySet().stream().anyMatch(n -> n.endsWith(".zip"));
    }

    private static Path retiredName(Path jar) {
        Path candidate = jar.resolveSibling(jar.getFileName() + ".old");
        if (!Files.exists(candidate)) {
            return candidate;
        }
        try {
            Files.delete(candidate);
            return candidate;
        } catch (IOException e) {
            return jar.resolveSibling(jar.getFileName() + ".old." + System.currentTimeMillis());
        }
    }

    public static boolean looksLikeJar(Path p) throws IOException {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(p.toFile())) {
            if (jar.getManifest() == null || !"dev.lagless.Main".equals(jar.getManifest().getMainAttributes().getValue("Main-Class"))
                    || jar.getEntry("dev/lagless/Main.class") == null) return false;
            long total = 0; byte[] bytes = new byte[65536];
            for (var entries = jar.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement(); if (entry.isDirectory()) continue;
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                try (InputStream in = jar.getInputStream(entry)) { int n; while ((n = in.read(bytes)) >= 0) {
                    total += n; if (total > 256L * 1024 * 1024) return false; crc.update(bytes, 0, n);
                } }
                if (entry.getCrc() != crc.getValue()) return false;
            }
            return true;
        } catch (java.util.zip.ZipException e) { return false; }
    }

    public static void stageRollback(Path jar) throws IOException {
        synchronized (UPDATE_LOCK) {
            if (jar == null) throw new IOException(I18n.t("从 class 文件运行时无法回滚应用 JAR","Cannot roll back the application JAR when running from class files"));
            Path rollback = jar.resolveSibling(jar.getFileName() + ".rollback");
            if (!Files.isRegularFile(rollback) || !isCompatibleUpdate(jar, rollback)) throw new IOException(I18n.t("没有与当前版本类型相同的有效上一版本备份","No valid previous-version backup of the same type"));
            Path temp = jar.resolveSibling(jar.getFileName() + ".part");
            Files.copy(rollback, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, jar.resolveSibling(jar.getFileName() + ".new"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static boolean isCompatibleUpdate(Path installed, Path candidate) throws IOException {
        return looksLikeJar(candidate) && Installation.distribution(installed).equals(Installation.distribution(candidate));
    }

    /** Assets of a release, for tests and diagnostics. */
    public static List<String> assetNames(Release r) {
        return List.copyOf(r.assets().keySet());
    }
}
