package dev.lagless.desktop;

import dev.lagless.I18n;
import dev.lagless.SshConfigFile;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.*;
import java.net.URLDecoder;
import java.util.*;
import java.util.regex.*;

/** Interchange of connection metadata only, never opaque vendor password blobs. */
public final class AccountFormats {
    private AccountFormats() { }
    public static List<Profile> read(Path file) throws IOException {
        if (Files.size(file) > 16 * 1024 * 1024) throw new IOException(I18n.t("账号导入文件超过 16 MiB","Account import file exceeds 16 MiB"));
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) return ProfileStore.decode(Files.readString(file));
        byte[] bytes = Files.readAllBytes(file);
        String text = new String(bytes, bytes.length > 1 && bytes[0] == (byte)0xff && bytes[1] == (byte)0xfe
                ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8).replace("\ufeff", "");
        if (name.endsWith(".reg")) return putty(text);
        if (name.endsWith(".xsh")) return xshell(text, file.getFileName().toString());
        return openssh(file);
    }
    public static List<Profile> openssh(Path file) throws IOException {
        List<Profile> out = new ArrayList<>();
        List<SshConfigFile.HostBlock> hosts = new SshConfigFile(file).load().concreteHosts();
        for (var h : hosts) {
            String host = h.option("HostName");
            out.add(profile(h.name(), host == null ? h.name() : unquote(host), unquote(h.option("User")),
                    h.option("Port"), unquote(h.option("IdentityFile"))));
        }
        Map<String,Profile> aliases = new HashMap<>(); out.forEach(p -> aliases.put(p.name(),p));
        for (int i=0;i<hosts.size();i++) {
            String jump=hosts.get(i).option("ProxyJump");
            if(jump==null || jump.isBlank() || jump.equalsIgnoreCase("none"))continue;
            String previous="";String[] chain=jump.split(",");
            for(String hop:chain) {
                Profile base=aliases.get(hop.trim());
                if(base==null) {dev.lagless.Options options=dev.lagless.Options.parse(new String[]{hop.trim()});base=profile(hop.trim(),options.host,options.user,options.port>0?Integer.toString(options.port):null,options.identity);}
                if(chain.length>1 || !aliases.containsKey(hop.trim())) {
                    base=new Profile(UUID.randomUUID().toString(),base.name()+" (jump)",base.host(),base.user(),base.port(),base.identity(),previous,true,false);out.add(base);
                }
                previous=base.id();
            }
            Profile target=out.get(i);out.set(i,new Profile(target.id(),target.name(),target.host(),target.user(),target.port(),target.identity(),previous,target.reconnect(),target.tmux()));
        }
        DesktopApp.validateRoutes(out);
        return out;
    }
    static List<Profile> putty(String text) {
        List<Profile> out = new ArrayList<>();
        String label = null;
        Map<String,String> values = new HashMap<>();
        Pattern section = Pattern.compile("\\[HKEY_CURRENT_USER\\\\Software\\\\SimonTatham\\\\PuTTY\\\\Sessions\\\\([^]\\r\\n]+)]", Pattern.CASE_INSENSITIVE);
        Pattern entry = Pattern.compile("\"([^\"]+)\"=(?:\"(.*)\"|dword:([0-9a-fA-F]+))");
        for (String line : (text + "\n[END]").split("\\R")) {
            if (line.startsWith("[")) {
                if (label != null && values.containsKey("HostName") && !values.get("HostName").isBlank()
                        && "ssh".equalsIgnoreCase(values.getOrDefault("Protocol", "ssh")))
                    out.add(profile(label, values.get("HostName"), values.get("UserName"), values.get("PortNumber"), values.get("PublicKeyFile")));
                Matcher m = section.matcher(line);
                label = m.matches() ? URLDecoder.decode(m.group(1).replace("+", "%2B"), StandardCharsets.UTF_8) : null;
                values.clear();
            } else {
                Matcher m = entry.matcher(line.trim());
                if (m.matches()) values.put(m.group(1), m.group(2) != null ? m.group(2).replace("\\\\", "\\").replace("\\\"", "\"")
                        : Integer.toString(Integer.parseUnsignedInt(m.group(3), 16)));
            }
        }
        return out;
    }
    static List<Profile> xshell(String text, String fileName) {
        Map<String,String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String line : text.split("\\R")) {
            int i = line.indexOf('=');
            if (i > 0) values.put(line.substring(0,i).trim(), unquote(line.substring(i+1).trim()));
        }
        String host = values.get("Host");
        if (host == null || host.isBlank() || !values.getOrDefault("Protocol", "SSH").equalsIgnoreCase("SSH")) return List.of();
        return List.of(profile(fileName.replaceFirst("(?i)\\.xsh$", ""), host, values.get("UserName"), values.get("Port"), values.get("UserKey")));
    }
    private static Profile profile(String name, String host, String user, String port, String key) {
        return new Profile(UUID.randomUUID().toString(), name, host, user, port == null || port.isBlank() ? 22 : Integer.parseInt(port), key, "", true, false);
    }
    private static String unquote(String s) { return s == null ? "" : s.replaceAll("^\"|\"$", ""); }
    public static void write(Path file,List<Profile> profiles,String format) throws IOException {
        String text=export(profiles,format);
        if(format.equals("reg") || format.equals("xsh"))ProfileStore.atomicWriteBytes(file,("\ufeff"+text).getBytes(StandardCharsets.UTF_16LE));
        else ProfileStore.atomicWrite(file,text);
    }
    public static String export(List<Profile> profiles, String format) {
        if (format.equals("json")) return ProfileStore.encode(profiles);
        StringBuilder out = new StringBuilder(format.equals("reg") ? "Windows Registry Editor Version 5.00\r\n\r\n" : "");
        Map<String,String> aliases=new HashMap<>();Set<String> used=new HashSet<>();
        for(Profile p:profiles){String base=p.name().replaceAll("[^\\p{L}\\p{N}_.-]","_"),alias=base;int counter=2;while(!used.add(alias.toLowerCase(Locale.ROOT)))alias=base+"_"+counter++;aliases.put(p.id(),alias);}
        Map<String,String> labels=new HashMap<>();used.clear();
        for(Profile p:profiles){String label=p.name();int counter=2;while(!used.add(label.toLowerCase(Locale.ROOT)))label=p.name()+" ("+(counter++)+")";labels.put(p.id(),label);}
        for (Profile p : profiles) {
            if (format.equals("reg")) {
                String name = java.net.URLEncoder.encode(labels.get(p.id()), StandardCharsets.UTF_8).replace("+", "%20");
                out.append("[HKEY_CURRENT_USER\\Software\\SimonTatham\\PuTTY\\Sessions\\").append(name).append("]\r\n")
                        .append("\"HostName\"=\"").append(reg(p.host())).append("\"\r\n\"UserName\"=\"").append(reg(p.user()))
                        .append("\"\r\n\"Protocol\"=\"ssh\"\r\n\"PortNumber\"=dword:").append(String.format("%08x", p.port()))
                        .append("\r\n\"PublicKeyFile\"=\"").append(reg(p.identity())).append("\"\r\n\r\n");
            } else if (format.equals("xsh")) {
                if (profiles.size() != 1) throw new IllegalArgumentException(I18n.t("Xshell 每个文件只能导出一个账号","Xshell files can hold only one account each"));
                out.append("[CONNECTION]\r\nProtocol=SSH\r\nHost=").append(p.host()).append("\r\nPort=").append(p.port())
                        .append("\r\n[CONNECTION:AUTHENTICATION]\r\nUserName=").append(p.user()).append("\r\nUserKey=").append(p.identity()).append("\r\n");
            } else {
                String alias = aliases.get(p.id());
                out.append("Host ").append(alias).append("\n    HostName ").append(p.host()).append("\n    Port ").append(p.port()).append('\n');
                if (!p.user().isBlank()) out.append("    User \"").append(p.user().replace("\"", "")).append("\"\n");
                if (!p.identity().isBlank()) out.append("    IdentityFile \"").append(p.identity().replace('\\', '/').replace("\"", "")).append("\"\n");
                profiles.stream().filter(j -> j.id().equals(p.jumpId())).findFirst().ifPresent(j ->
                        out.append("    ProxyJump ").append(aliases.get(j.id())).append('\n'));
                out.append('\n');
            }
        }
        return out.toString();
    }
    private static String reg(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
