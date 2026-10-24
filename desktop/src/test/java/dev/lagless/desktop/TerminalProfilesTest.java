package dev.lagless.desktop;

import dev.lagless.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class TerminalProfilesTest {
    @TempDir Path dir;
    private Path config()throws Exception{Path file=dir.resolve(".ssh/config");Files.createDirectories(file.getParent());return file;}
    @Test void migratesConnectionsSavedByTerminalWithoutChangingItsConfig()throws Exception{
        Path file=config();SshConfigFile cli=new SshConfigFile(file).load();cli.add("production","192.0.2.10","deploy",2222,"C:/keys/server");cli.save();String original=Files.readString(file);
        Path home=dir.resolve(".lagless");new ProfileStore(home).save(List.of());DesktopApp app=new DesktopApp(home);
        try{assertEquals(1,app.accounts.size());Profile p=app.accounts.get(0);assertEquals("production",p.name());assertEquals("production",p.sshAlias());assertEquals("192.0.2.10",p.host());assertEquals("deploy",p.user());assertEquals(2222,p.port());assertEquals("C:/keys/server",p.identity());assertEquals(original,Files.readString(file));assertEquals(app.profiles(),new ProfileStore(home).load());}
        finally{app.close();assertTrue(app.awaitShutdown(System.nanoTime()+TimeUnit.SECONDS.toNanos(10)),"desktop shutdown must finish before temporary home cleanup");}
    }
    @Test void repeatedImportPreservesEditsAndRemapsNewJumpReferences()throws Exception{
        Path file=config();Files.writeString(file,"Host edge\n HostName edge.internal\n User admin\nHost db\n HostName db.internal\n ProxyJump edge\n");Path home=dir.resolve(".lagless");List<Profile> saved=new ArrayList<>();assertEquals(2,TerminalProfiles.migrate(home,saved));
        Profile edge=saved.getFirst();saved.set(0,new Profile(edge.id(),"My edited jump",edge.host(),"changed-user",edge.port(),edge.identity(),edge.jumpId(),false,true,edge.sshAlias()));
        assertEquals(0,TerminalProfiles.merge(saved,TerminalProfiles.read(home)));assertEquals("changed-user",saved.getFirst().user());
        Files.writeString(file,"\nHost cache\n HostName cache.internal\n ProxyJump edge\n",StandardOpenOption.APPEND);
        assertEquals(1,TerminalProfiles.merge(saved,TerminalProfiles.read(home)));assertEquals(edge.id(),saved.getLast().jumpId());DesktopApp.validateRoutes(saved);
        assertEquals(0,TerminalProfiles.migrate(home,saved));
    }
    @Test void keepsGlobalDefaultsAndLocalAliasButExportsConcreteHost()throws Exception{
        Path file=config();Files.writeString(file,"Host production\n HostName 192.0.2.15\nHost *\n User global-user\n Port 2200\n IdentityFile ~/.ssh/global-key\n");
        Profile p=TerminalProfiles.read(dir.resolve(".lagless")).getFirst();assertEquals("global-user",p.user());assertEquals(2200,p.port());
        String old=System.getProperty("user.home");try{System.setProperty("user.home",dir.toString());assertEquals("production",p.options().host);Files.delete(file);assertEquals("192.0.2.15",p.options().host);}finally{System.setProperty("user.home",old);}
        assertTrue(AccountFormats.export(List.of(p),"openssh").contains("HostName 192.0.2.15"));assertEquals(p,ProfileStore.decode(ProfileStore.encode(List.of(p))).getFirst());
    }
    @Test void existingAccountsAndInvalidTerminalSourceDoNotEraseDesktopData()throws Exception{
        Profile manual=new Profile("manual","personal","personal.internal","u",22,"","",true,false);Path home=dir.resolve(".lagless");new ProfileStore(home).save(List.of(manual));
        Files.writeString(config(),"Host broken\n HostName example.internal\n Port invalid\n");DesktopApp app=new DesktopApp(home);
        try{assertEquals(List.of(manual),app.profiles());assertEquals(List.of(manual),new ProfileStore(home).load());assertFalse(Files.exists(home.resolve("desktop/terminal-imported.json")));}
        finally{app.close();assertTrue(app.awaitShutdown(System.nanoTime()+TimeUnit.SECONDS.toNanos(10)),"desktop shutdown must finish before temporary home cleanup");}
    }
}
