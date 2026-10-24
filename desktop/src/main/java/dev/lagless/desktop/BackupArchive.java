package dev.lagless.desktop;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.KeySpec;
import java.util.*;
import java.util.zip.*;

/** Versioned, authenticated encrypted backups. Restore validates before touching user files. */
public final class BackupArchive {
    private static final byte[] MAGIC = "LAGLESS-BACKUP-1\n".getBytes(StandardCharsets.US_ASCII);
    private static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private BackupArchive() { }
    private static SecretKeySpec key(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, 210_000, 256);
        byte[] key = null;
        try {
            key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } finally { spec.clearPassword(); if (key != null) Arrays.fill(key, (byte) 0); }
    }
    public static void create(Path userHome, Path target, char[] password, Path appJar) throws Exception {
        if (password.length < 10) throw new IllegalArgumentException("备份密码至少 10 位");
        Path dest = target.toAbsolutePath().normalize();
        if (dest.startsWith(userHome.resolve(".lagless").toAbsolutePath().normalize())
                || dest.startsWith(userHome.resolve(".ssh").toAbsolutePath().normalize())) throw new IOException("备份文件请保存在数据目录之外");
        Files.createDirectories(dest.getParent());
        Path temp = Files.createTempFile(dest.getParent(), ".lagless-backup-", ".tmp");
        try{Files.setAttribute(temp,"dos:hidden",true);}catch(UnsupportedOperationException|IOException ignored){}
        byte[] salt = new byte[16], iv = new byte[8]; SecureRandom random = new SecureRandom(); random.nextBytes(salt); random.nextBytes(iv);
        try {
            try (OutputStream raw = Files.newOutputStream(temp)) {
                raw.write(MAGIC); raw.write(salt); raw.write(iv);
                try (ZipOutputStream zip = new ZipOutputStream(new EncryptedOutput(raw, key(password, salt), iv))) {
                    zip.putNextEntry(new ZipEntry("manifest")); zip.write("lagless-backup-v1".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                    long total = 0;
                    for (String directory : List.of(".lagless", ".ssh")) {
                        Path root = userHome.resolve(directory);
                        if (!Files.isDirectory(root)) continue;
                        try (var paths = Files.walk(root)) {
                            for (Path file : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                                String relative=root.relativize(file).toString().replace('\\','/');
                                // Pending tails are replaced/deleted while the application is running.
                                // Fold them into a stable .log snapshot instead of archiving transient files.
                                if(relative.endsWith(".log.pending")||relative.endsWith(".tmp"))continue;
                                long size;
                                try{size=Files.size(file);}catch(NoSuchFileException vanished){continue;}
                                if ((total += size) > MAX_BYTES) throw new IOException("备份超过 2 GiB，请先归档日志");
                                zip.putNextEntry(new ZipEntry(directory + "/" + relative));
                                if(directory.equals(".lagless")&&relative.equals("desktop/assistant.json")){
                                    zip.write(AssistantSettings.load(root).portableBackup());
                                }else if(directory.equals(".lagless")&&relative.endsWith(".log")){
                                    JournalTail.Snapshot snapshot=JournalTail.snapshot(file,size);
                                    try(InputStream in=Files.newInputStream(file)){
                                        byte[] bytes=new byte[65536];long remaining=snapshot.length();
                                        while(remaining>0){int n=in.read(bytes,0,(int)Math.min(bytes.length,remaining));if(n<0)throw new EOFException("备份期间日志缩短："+file);zip.write(bytes,0,n);remaining-=n;}
                                    }
                                    zip.write(snapshot.suffix());
                                }else try(InputStream in=Files.newInputStream(file)) {
                                    byte[] bytes=new byte[65536];long remaining=size;
                                    while(remaining>0){int n=in.read(bytes,0,(int)Math.min(bytes.length,remaining));if(n<0)throw new EOFException("备份期间文件缩短："+file);zip.write(bytes,0,n);remaining-=n;}
                                }
                                zip.closeEntry();
                            }
                        }
                    }
                    if (appJar != null && Files.isDirectory(appJar)) {
                        Path bundle = Files.createTempFile("lagless-native-backup-", ".zip");
                        try { NativeBundle.pack(appJar,bundle);zip.putNextEntry(new ZipEntry("application/native.zip"));Files.copy(bundle,zip);zip.closeEntry(); }
                        finally { Files.deleteIfExists(bundle); }
                    } else if (appJar != null && Files.isRegularFile(appJar)) {
                        zip.putNextEntry(new ZipEntry("application/lagless.jar")); Files.copy(appJar, zip); zip.closeEntry();
                    }
                }
            }
            try { Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    public static void restore(Path archive, Path userHome, char[] password, Path appJar) throws Exception {
        Path restoreRoot = canonicalizeDestination(userHome);
        Path stage = Files.createTempDirectory("lagless-restore-");
        try {
            Path zipFile = stage.resolve("verified.zip");
            try (InputStream raw = Files.newInputStream(archive)) {
                if (!Arrays.equals(raw.readNBytes(MAGIC.length), MAGIC)) throw new IOException("不支持的备份格式");
                byte[] salt = raw.readNBytes(16), iv = raw.readNBytes(8);
                if (salt.length != 16 || iv.length != 8) throw new IOException("备份已截断");
                // Authenticate the entire archive before applying even its first entry.
                try (InputStream decoded = new EncryptedInput(raw, key(password, salt), iv);
                     OutputStream out = Files.newOutputStream(zipFile)) { copyLimited(decoded, out, MAX_BYTES + 64 * 1024 * 1024); }
            }
            Path payload = stage.resolve("payload"); Files.createDirectories(payload);
            Set<String> names = new HashSet<>(); long total = 0;
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (!validEntry(name) || !names.add(name) || names.size() > 50_000) throw new IOException("备份包含非法或重复路径：" + name);
                    if (entry.isDirectory()) continue;
                    Path file = payload.resolve(name); Files.createDirectories(file.getParent());
                    try (OutputStream out = Files.newOutputStream(file)) { total += copyLimited(zip, out, MAX_BYTES - total); }
                }
            }
            if (!Files.isRegularFile(payload.resolve("manifest")) || !Files.readString(payload.resolve("manifest")).equals("lagless-backup-v1"))
                throw new IOException("缺少有效备份清单");
            Path accounts = payload.resolve(".lagless/desktop/accounts.json");
            if (Files.exists(accounts)) ProfileStore.decode(Files.readString(accounts));
            Path assistant=payload.resolve(".lagless/desktop/assistant.json");
            if(Files.isRegularFile(assistant))AssistantSettings.load(payload.resolve(".lagless")).save();
            Path application = payload.resolve("application/lagless.jar");
            if (Files.exists(application) && !dev.lagless.Updater.looksLikeJar(application)) throw new IOException("备份中的应用 JAR 无效");
            Path nativeArchive=payload.resolve("application/native.zip"), nativeStage=null;
            if(Files.exists(nativeArchive)) {
                if(appJar!=null && Files.isDirectory(appJar))nativeStage=NativeUpdater.prepare(appJar,nativeArchive);
                else NativeBundle.extract(nativeArchive,stage.resolve("native-check"),false);
            }
            Map<Path,Path> rollback = new LinkedHashMap<>();
            try (var paths = Files.walk(payload)) {
                try {
                    for (Path source : paths.filter(Files::isRegularFile).toList()) {
                        String relative = payload.relativize(source).toString().replace('\\', '/');
                        if (relative.equals("manifest")||relative.endsWith(".log.pending")) continue;
                        if (relative.equals("application/native.zip")) continue;
                        Path target;
                        if (relative.equals("application/lagless.jar")) {
                            if (appJar == null || Files.isDirectory(appJar)) continue;
                            target = appJar.resolveSibling(appJar.getFileName() + ".new");
                        } else target = restoreRoot.resolve(relative);
                        // A running application may own the destination log. Never replace it;
                        // both the current run and an earlier backup remain independently readable.
                        if(isLog(relative)&&Files.exists(target))continue;
                        if(target.startsWith(restoreRoot))rejectSymlinksBelow(restoreRoot,target);else rejectSymlinks(target);
                        Path previous = null;
                        if (Files.exists(target)) { previous = Files.createTempFile(stage, "rollback-", ".bin"); Files.copy(target, previous, StandardCopyOption.REPLACE_EXISTING); }
                        rollback.put(target, previous);
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                        try { Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
                        catch (UnsupportedOperationException ignored) { }
                    }
                    if(nativeStage!=null)NativeUpdater.arm(appJar,nativeStage);
                } catch (Exception e) {
                    for (var item : rollback.entrySet()) {
                        try { if (item.getValue() == null) Files.deleteIfExists(item.getKey());
                              else Files.copy(item.getValue(), item.getKey(), StandardCopyOption.REPLACE_EXISTING); }
                        catch (IOException recovery) { e.addSuppressed(recovery); }
                    }
                    throw e;
                }
            }
        } finally {
            try (var paths = Files.walk(stage)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
    private static boolean isLog(String relative){
        return relative.startsWith(".lagless/logs/")||relative.startsWith(".lagless/desktop/events/");
    }
    static boolean validEntry(String name) {
        if (name.contains("\\") || name.contains(":") || name.startsWith("/") || name.contains("//") || name.contains("\u0000")) return false;
        for (String part : name.split("/")) if (part.equals("..") || part.equals(".")) return false;
        return name.equals("manifest") || name.startsWith(".lagless/") || name.startsWith(".ssh/") || name.equals("application/lagless.jar") || name.equals("application/native.zip");
    }
    private static void rejectSymlinks(Path target) throws IOException {
        for (Path p = target.toAbsolutePath(); p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IOException("恢复目标含符号链接：" + p);
    }
    /** Resolve OS-level aliases in the chosen restore directory, then protect paths inside it. */
    private static Path canonicalizeDestination(Path requested) throws IOException {
        Path absolute=requested.toAbsolutePath().normalize(),existing=absolute;
        Deque<Path> suffix=new ArrayDeque<>();
        while(existing!=null&&!Files.exists(existing)){
            Path name=existing.getFileName();if(name!=null)suffix.addFirst(name);
            existing=existing.getParent();
        }
        if(existing==null)throw new IOException("无法定位恢复目标目录："+requested);
        Path result=existing.toRealPath();for(Path name:suffix)result=result.resolve(name);
        return result.normalize();
    }
    private static void rejectSymlinksBelow(Path root,Path target)throws IOException{
        Path normalizedRoot=root.toAbsolutePath().normalize(),normalizedTarget=target.toAbsolutePath().normalize();
        if(!normalizedTarget.startsWith(normalizedRoot))throw new IOException("恢复目标超出所选目录："+target);
        Path current=normalizedRoot;
        for(Path part:normalizedRoot.relativize(normalizedTarget)){
            current=current.resolve(part);
            if(Files.isSymbolicLink(current))throw new IOException("恢复目标含符号链接："+current);
        }
    }
    private static long copyLimited(InputStream in, OutputStream out, long limit) throws IOException {
        long total = 0; byte[] bytes = new byte[64 * 1024]; int n;
        while ((n = in.read(bytes)) >= 0) { if ((total += n) > limit) throw new IOException("备份超过大小限制"); out.write(bytes, 0, n); }
        return total;
    }

    // Independent authenticated 64 KiB frames bound memory, even with multi-gigabyte logs.
    // A mandatory authenticated empty final frame detects truncation at a frame boundary.
    private static byte[] crypt(int mode, SecretKeySpec key, byte[] nonce, int sequence, byte[] bytes, int length) throws IOException {
        try {
            byte[] iv = java.nio.ByteBuffer.allocate(12).put(nonce).putInt(sequence).array();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, iv)); cipher.updateAAD(MAGIC);
            cipher.updateAAD(java.nio.ByteBuffer.allocate(4).putInt(sequence).array());
            return cipher.doFinal(bytes, 0, length);
        } catch (GeneralSecurityException e) { throw new IOException("备份认证失败：密码错误或文件损坏", e); }
    }
    private static final class EncryptedOutput extends OutputStream {
        private final DataOutputStream out; private final SecretKeySpec key; private final byte[] nonce, buffer = new byte[65536];
        private int used, sequence; private boolean closed;
        EncryptedOutput(OutputStream out, SecretKeySpec key, byte[] nonce) { this.out = new DataOutputStream(out); this.key = key; this.nonce = nonce; }
        @Override public void write(int b) throws IOException { buffer[used++] = (byte)b; if (used == buffer.length) frame(); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            while (length > 0) { int n = Math.min(length, buffer.length-used); System.arraycopy(bytes,offset,buffer,used,n); used+=n;offset+=n;length-=n;if(used==buffer.length)frame(); }
        }
        private void frame() throws IOException { byte[] encrypted=crypt(Cipher.ENCRYPT_MODE,key,nonce,sequence++,buffer,used);out.writeInt(encrypted.length);out.write(encrypted);used=0; }
        @Override public void close() throws IOException { if(closed)return;closed=true;try{if(used>0)frame();frame();out.flush();}finally{out.close();Arrays.fill(buffer,(byte)0);} }
    }
    private static final class EncryptedInput extends InputStream {
        private final DataInputStream in; private final SecretKeySpec key; private final byte[] nonce;
        private byte[] buffer = new byte[0]; private int offset, sequence; private boolean eof;
        EncryptedInput(InputStream in, SecretKeySpec key, byte[] nonce) {this.in=new DataInputStream(in);this.key=key;this.nonce=nonce;}
        private boolean next() throws IOException {
            if(eof)return false;
            int length=in.readInt();if(length<16||length>65552)throw new IOException("备份加密帧大小无效");
            byte[] encrypted=in.readNBytes(length);if(encrypted.length!=length)throw new EOFException("备份已截断");
            buffer=crypt(Cipher.DECRYPT_MODE,key,nonce,sequence++,encrypted,length);offset=0;
            if(buffer.length==0){eof=true;if(in.read()!=-1)throw new IOException("备份结束标记后仍有数据");return false;}return true;
        }
        @Override public int read() throws IOException {if(offset==buffer.length&&!next())return -1;return buffer[offset++]&255;}
        @Override public int read(byte[] bytes,int off,int len) throws IOException {if(len==0)return 0;if(offset==buffer.length&&!next())return -1;int count=Math.min(len,buffer.length-offset);System.arraycopy(buffer,offset,bytes,off,count);offset+=count;return count;}
        @Override public void close() throws IOException {Arrays.fill(buffer,(byte)0);in.close();}
    }
}
