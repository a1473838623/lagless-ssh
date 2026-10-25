package dev.lagless.desktop;

import dev.lagless.I18n;
import dev.lagless.Options;
import java.util.*;

/** Passwords and API keys deliberately never belong to an account export. */
public record Profile(String id, String name, String host, String user, int port,
                      String identity, String jumpId, boolean reconnect, boolean tmux, String sshAlias) {
    public Profile(String id,String name,String host,String user,int port,String identity,String jumpId,boolean reconnect,boolean tmux){
        this(id,name,host,user,port,identity,jumpId,reconnect,tmux,"");
    }
    public Profile {
        Objects.requireNonNull(id);
        if(id.isBlank())throw new IllegalArgumentException(I18n.t("账号 ID 不能为空","Account ID cannot be empty"));
        if (name == null || name.isBlank() || host == null || host.isBlank())
            throw new IllegalArgumentException(I18n.t("名称和主机不能为空","Name and host cannot be empty"));
        if (port < 1 || port > 65535) throw new IllegalArgumentException(I18n.t("端口必须为 1–65535","Port must be 1–65535"));
        if (host.chars().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException(I18n.t("主机不能含空白字符","Host cannot contain whitespace"));
        user = user == null ? "" : user;
        identity = identity == null ? "" : identity;
        jumpId = jumpId == null ? "" : jumpId;
        sshAlias=sshAlias==null?"":sshAlias;
        for (String field : List.of(id,name,host,user,identity,jumpId,sshAlias))
            if (field.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException(I18n.t("账号字段不能含控制字符","Account fields cannot contain control characters"));
    }
    public Options options() {
        Options o = new Options();
        o.host = host; o.user = user.isBlank() ? null : user; o.port = port;
        // Keep alias-specific SSH and Lagless settings on this machine. An account
        // exported to another machine can still use its concrete hostname.
        if(!sshAlias.isBlank())try{if(new dev.lagless.SshConfigFile(dev.lagless.SshConfigFile.defaultPath()).load().find(sshAlias)!=null)o.host=sshAlias;}catch(java.io.IOException ignored){ }
        o.identity = identity.isBlank() ? null : identity;
        return o;
    }
    public Map<String, Object> json() {
        Map<String,Object> result=new LinkedHashMap<>();result.put("id",id);result.put("name",name);result.put("host",host);result.put("user",user);result.put("port",port);
        result.put("identity",identity);result.put("jumpId",jumpId);result.put("reconnect",reconnect);result.put("tmux",tmux);result.put("sshAlias",sshAlias);return result;
    }
    public static Profile from(Map<?, ?> m) {
        Object port = m.get("port");
        if (port != null && (!(port instanceof Number n) || n.doubleValue() != n.intValue()))
            throw new IllegalArgumentException(I18n.t("端口必须是整数","Port must be a whole number"));
        return new Profile(str(m,"id",UUID.randomUUID().toString()), str(m,"name",""), str(m,"host",""),
                str(m,"user",""), ((Number) Objects.requireNonNullElse(m.get("port"), 22)).intValue(),
                str(m,"identity",""), str(m,"jumpId",""), Boolean.TRUE.equals(m.get("reconnect")), Boolean.TRUE.equals(m.get("tmux")),str(m,"sshAlias",""));
    }
    private static String str(Map<?, ?> m, String key, String fallback) {
        return m.get(key) instanceof String s ? s : fallback;
    }
    @Override public String toString() { return name; }
}
