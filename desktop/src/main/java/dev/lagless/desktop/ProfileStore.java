package dev.lagless.desktop;

import dev.lagless.I18n;
import dev.lagless.Json;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ProfileStore {
    private final Path file;
    public ProfileStore(Path home) { file = home.resolve("desktop/accounts.json"); }
    public List<Profile> load() throws IOException {
        return Files.exists(file) ? decode(Files.readString(file)) : new ArrayList<>();
    }
    public static List<Profile> decode(String text) throws IOException {
        try {
            Object root = Json.parse(text);
            if (Json.number(root, 0, "version") != 1) throw new IOException(I18n.t("不支持的账号格式版本","Unsupported account file version"));
            List<Profile> result = new ArrayList<>();
            if (!(Json.get(root, "accounts") instanceof List<?>)) throw new IOException(I18n.t("缺少账号列表","Account list is missing"));
            Set<String> ids = new HashSet<>();
            for (Object entry : Json.list(root, "accounts")) {
                if (!(entry instanceof Map<?, ?> object)) throw new IOException(I18n.t("账号条目必须是对象","Each account entry must be an object"));
                Profile p = Profile.from(object);
                if (!ids.add(p.id())) throw new IOException(I18n.t("重复的账号 ID","Duplicate account ID"));
                result.add(p);
            }
            DesktopApp.validateRoutes(result);
            return result;
        } catch (IllegalArgumentException | ClassCastException e) { throw new IOException(I18n.t("账号文件无效","Invalid account file"), e); }
    }
    public static String encode(List<Profile> profiles) {
        return Json.stringify(Map.of("version", 1, "accounts", profiles.stream().map(Profile::json).toList()));
    }
    public void save(List<Profile> profiles) throws IOException { atomicWrite(file, encode(profiles)); }
    public static void atomicWrite(Path file, String text) throws IOException {
        atomicWriteBytes(file,text.getBytes(StandardCharsets.UTF_8));
    }
    public static void atomicWriteBytes(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), ".lagless-", ".tmp");
        try {
            Files.write(tmp, bytes);
            try { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }
    /** Preserve jump links within the imported group; never overwrite existing accounts. */
    public static void merge(List<Profile> current, List<Profile> imported) {
        Map<String, String> ids = new HashMap<>();
        imported.forEach(p -> ids.put(p.id(), UUID.randomUUID().toString()));
        for (Profile p : imported) current.add(new Profile(ids.get(p.id()), p.name(), p.host(), p.user(), p.port(),
                p.identity(), ids.getOrDefault(p.jumpId(), ""), p.reconnect(), p.tmux(),p.sshAlias()));
    }
}
