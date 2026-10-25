package dev.lagless.desktop;

import dev.lagless.I18n;
import dev.lagless.Json;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Managed native application files, with bounded extraction and per-file integrity checks. */
public final class NativeBundle {
    public static final String MANIFEST="bundle.json";
    private static final long LIMIT=512L*1024*1024;
    public record Entry(String path,String sha256,long size,boolean executable) { }
    public record Manifest(String version,String platform,String executable,List<Entry> files) { }
    private NativeBundle(){ }
    public static String platform() {
        String os=System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String platform=os.contains("win")?"windows":os.contains("mac")?"macos":os.contains("linux")?"linux":"unknown";
        String arch=System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        return platform+"-"+(arch.equals("aarch64")||arch.equals("arm64")?"arm64":arch.equals("amd64")||arch.equals("x86_64")?"x64":arch);
    }
    static boolean safe(String path) {
        if(path==null||path.isBlank()||path.startsWith("/")||path.contains("\\")||path.contains(":")||path.contains("//")||path.indexOf(0)>=0)return false;
        for(String part:path.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals("..")||part.endsWith(".")||part.endsWith(" "))return false;
        String lower=path.toLowerCase(Locale.ROOT);
        return !lower.startsWith(".updates")&&!lower.startsWith(".rollback");
    }
    public static Manifest manifest(Path root,boolean matchingPlatform)throws IOException {
        Path file=root.resolve(MANIFEST);
        if(Files.size(file)>2*1024*1024)throw new IOException(I18n.t("原生包清单过大","Native package manifest is too large"));
        Object json=Json.parse(Files.readString(file));
        if(Json.number(json,0,"format")!=1||!"desktop-native".equals(Json.string(json,"edition")))throw new IOException(I18n.t("不是 Lagless 原生桌面包","Not a Lagless native desktop package"));
        String version=Json.string(json,"version"),platform=Json.string(json,"platform"),executable=Json.string(json,"executable");
        if(version==null||version.isBlank()||platform==null||!safe(executable)||executable.contains("/"))throw new IOException(I18n.t("原生包清单字段无效","Invalid native package manifest field"));
        if(matchingPlatform&&!platform().equals(platform))throw new IOException(I18n.t("原生包平台不匹配：","Native package platform mismatch: ")+platform+" / "+platform());
        if(!(executable.equals("Lagless.exe")||executable.equals("Lagless")))throw new IOException(I18n.t("原生程序名称无效","Invalid native program name"));
        List<Entry> entries=new ArrayList<>();Set<String> names=new HashSet<>();long total=0;
        for(Object value:Json.list(json,"files")) {
            String name=Json.string(value,"path"),hash=Json.string(value,"sha256");long size=Json.number(value,-1,"size");
            if(!safe(name)||name.equals(MANIFEST)||!names.add(name.toLowerCase(Locale.ROOT))||hash==null||!hash.matches("[a-fA-F0-9]{64}")||size<0||(total+=size)>LIMIT)
                throw new IOException(I18n.t("原生包文件清单无效","Invalid native package file list"));
            entries.add(new Entry(name,hash.toLowerCase(Locale.ROOT),size,Boolean.TRUE.equals(Json.get(value,"executable"))));
        }
        if(entries.isEmpty()||entries.size()>5000||entries.stream().noneMatch(e -> e.path().equals(executable)&&e.executable()))throw new IOException(I18n.t("原生包缺少可执行文件","Native package is missing the executable"));
        return new Manifest(version,platform,executable,List.copyOf(entries));
    }
    public static Manifest validate(Path root,boolean matchingPlatform)throws IOException {
        Manifest manifest=manifest(root,matchingPlatform);
        for(Entry entry:manifest.files()) {
            Path file=root.resolve(entry.path());rejectLinks(root,file);
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=entry.size()||!sha256(file).equals(entry.sha256()))throw new IOException(I18n.t("原生文件校验失败：","Native file verification failed: ")+entry.path());
        }
        return manifest;
    }
    public static Manifest extract(Path archive,Path target,boolean matchingPlatform)throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());Files.createDirectory(target);
        Set<String> paths=new HashSet<>();long total=0;
        try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                String name=entry.getName();
                if(entry.isDirectory()){if(!safe(name.replaceFirst("/$","")))throw new IOException(I18n.t("原生包包含非法目录","Native package contains an invalid folder"));continue;}
                if(!safe(name)||!paths.add(name.toLowerCase(Locale.ROOT))||paths.size()>5001)throw new IOException(I18n.t("原生包包含非法或重复路径","Native package contains an invalid or duplicate path"));
                Path file=target.resolve(name);Files.createDirectories(file.getParent());
                try(OutputStream out=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)) {
                    byte[] bytes=new byte[65536];int n;
                    while((n=zip.read(bytes))!=-1){total+=n;if(total>LIMIT+2*1024*1024)throw new IOException(I18n.t("原生包超过大小上限","Native package exceeds the size limit"));out.write(bytes,0,n);}
                }
            }
        }
        Manifest manifest=validate(target,matchingPlatform);
        Set<String> expected=new HashSet<>();expected.add(MANIFEST);manifest.files().forEach(e -> expected.add(e.path().toLowerCase(Locale.ROOT)));
        if(!expected.equals(paths))throw new IOException(I18n.t("原生包包含清单外文件","Native package contains files outside the manifest"));
        return manifest;
    }
    public static void pack(Path root,Path archive)throws IOException {
        Manifest manifest=validate(root,false);
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(archive))) {
            List<String> files=new ArrayList<>();files.add(MANIFEST);manifest.files().forEach(e -> files.add(e.path()));
            for(String name:files){zip.putNextEntry(new ZipEntry(name));Files.copy(root.resolve(name),zip);zip.closeEntry();}
        }
    }
    static void rejectLinks(Path root,Path file)throws IOException {
        for(Path p=file.toAbsolutePath();p!=null;p=p.getParent()) {
            if(Files.isSymbolicLink(p))throw new IOException(I18n.t("原生文件路径包含符号链接：","Native file path contains a symbolic link: ")+p);
            if(p.equals(root.toAbsolutePath()))break;
        }
    }
    public static String sha256(Path file)throws IOException {
        try(InputStream in=Files.newInputStream(file)){
            MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);
            return HexFormat.of().formatHex(digest.digest());
        }catch(GeneralSecurityException e){throw new IOException(e);}
    }
}
