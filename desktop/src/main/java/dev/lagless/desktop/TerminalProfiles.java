package dev.lagless.desktop;

import dev.lagless.I18n;
import dev.lagless.SshConfigFile;
import com.jcraft.jsch.OpenSSHConfig;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Read the same ~/.ssh/config that `lagless add/list` use, without modifying it. */
final class TerminalProfiles {
    private TerminalProfiles(){ }
    static Path source(Path home){return home.toAbsolutePath().getParent().resolve(".ssh/config");}
    static List<Profile> read(Path home)throws IOException{
        Path file=source(home);if(!Files.isRegularFile(file))return List.of();
        if(Files.size(file)>16*1024*1024)throw new IOException(I18n.t("终端 SSH 配置超过 16 MiB","Terminal SSH config exceeds 16 MiB"));
        List<Profile> imported=AccountFormats.openssh(file);
        Set<String> aliases=new HashSet<>();new SshConfigFile(file).load().concreteHosts().forEach(h->aliases.add(h.name()));
        var config=OpenSSHConfig.parseFile(file.toString());List<Profile> result=new ArrayList<>();
        for(Profile p:imported){
            if(!aliases.contains(p.name())){result.add(p);continue;}
            var effective=config.getConfig(p.name());String hostname=effective.getHostname(),user=effective.getUser(),identity=effective.getValue("IdentityFile");int port=effective.getPort();
            result.add(new Profile(p.id(),p.name(),hostname==null?p.host():hostname,user==null?p.user():user,port>0?port:p.port(),identity==null?p.identity():identity,p.jumpId(),p.reconnect(),p.tmux(),p.name()));
        }
        return result;
    }
    /** One-time migration, including upgrades with an existing empty desktop store. */
    static int migrate(Path home,List<Profile> current)throws IOException{
        Path marker=home.resolve("desktop/terminal-imported.json");if(Files.exists(marker))return 0;
        int added=merge(current,read(home));
        if(added>0)new ProfileStore(home).save(current);
        ProfileStore.atomicWrite(marker,dev.lagless.Json.stringify(Map.of("version",1,"source",source(home).toString())));return added;
    }
    /** Selecting one terminal-only jump imports that route, without resurrecting unrelated deleted accounts. */
    static String addJumpChoice(List<Profile> current,List<Profile> terminal,Profile chosen)throws IOException{
        Profile existing=current.stream().filter(p->p.id().equals(chosen.id())||sameConnection(p,chosen)).findFirst().orElse(null);
        if(existing!=null)return existing.id();
        Profile source=terminal.stream().filter(p->sameConnection(p,chosen)).findFirst().orElseThrow(()->new IOException(I18n.t("跳板配置已变化，请重新打开编辑器","Jump host settings changed; reopen the editor")));
        List<Profile> route=new ArrayList<>();Set<String> seen=new HashSet<>();Profile step=source;
        while(step!=null){if(!seen.add(step.id()))throw new IOException(I18n.t("跳板链循环","Jump host chain loops"));route.add(step);String parent=step.jumpId();step=parent.isBlank()?null:terminal.stream().filter(p->p.id().equals(parent)).findFirst().orElseThrow(()->new IOException(I18n.t("终端跳板链不完整","Terminal jump host chain is incomplete")));}
        merge(current,route);return current.stream().filter(p->sameConnection(p,source)).findFirst().orElseThrow().id();
    }
    private static boolean sameConnection(Profile a,Profile b){return !a.sshAlias().isBlank()&&a.sshAlias().equals(b.sshAlias())||a.name().equals(b.name())&&a.host().equals(b.host())&&a.port()==b.port()&&a.user().equals(b.user())&&a.identity().equals(b.identity());}
    /** Re-import only missing connections; preserve desktop edits and jump references. */
    static int merge(List<Profile> current,List<Profile> imported)throws IOException{
        Map<String,String> ids=new HashMap<>();Set<String> used=new HashSet<>();current.forEach(p->used.add(p.id()));
        List<Profile> missing=new ArrayList<>();
        for(Profile p:imported){
            Profile match=current.stream().filter(c->(!p.sshAlias().isBlank()&&p.sshAlias().equals(c.sshAlias()))||
                    (p.name().equals(c.name())&&p.host().equals(c.host())&&p.port()==c.port()&&p.user().equals(c.user())&&p.identity().equals(c.identity()))).findFirst().orElse(null);
            if(match!=null)ids.put(p.id(),match.id());
            else{String id=p.id();while(!used.add(id))id=UUID.randomUUID().toString();ids.put(p.id(),id);missing.add(p);}
        }
        List<Profile> next=new ArrayList<>(current);
        for(Profile p:missing)next.add(new Profile(ids.get(p.id()),p.name(),p.host(),p.user(),p.port(),p.identity(),ids.getOrDefault(p.jumpId(),""),p.reconnect(),p.tmux(),p.sshAlias()));
        try{DesktopApp.validateRoutes(next);}catch(IllegalArgumentException e){throw new IOException(I18n.t("终端配置的跳板链无效","Terminal config has an invalid jump host chain"),e);}
        current.clear();current.addAll(next);return missing.size();
    }
}
