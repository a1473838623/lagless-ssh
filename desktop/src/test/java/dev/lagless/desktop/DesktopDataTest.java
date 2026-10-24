package dev.lagless.desktop;

import dev.lagless.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopDataTest {
    @TempDir Path dir;
    private Profile profile(String id,String jump) {return new Profile(id,"生产 · café","db.internal","ops",2222,"C:/keys/中文 key",jump,true,true);}
    @Test void accountsRoundTripAndMergePreservesJumpLinks() throws Exception {
        Profile jump=profile("jump", ""), target=profile("target","jump");
        ProfileStore store=new ProfileStore(dir);store.save(List.of(jump,target));assertEquals(List.of(jump,target),store.load());
        List<Profile> result=new ArrayList<>(List.of(jump));ProfileStore.merge(result,List.of(jump,target));
        assertEquals(3,result.size());assertNotEquals(jump.id(),result.get(1).id());assertEquals(result.get(1).id(),result.get(2).jumpId());
        assertEquals("x\n\"\\中文",Json.parse(Json.stringify("x\n\"\\中文")));
    }
    @Test void rejectsCorruptionCyclesAndConfigInjection() {
        assertThrows(Exception.class,() -> ProfileStore.decode("{\"version\":1}"));
        assertThrows(Exception.class,() -> ProfileStore.decode("{\"version\":1,\"accounts\":[null]}"));
        assertThrows(Exception.class,() -> ProfileStore.decode(ProfileStore.encode(List.of(profile("a","b"),profile("b","a")))));
        assertThrows(Exception.class,() -> ProfileStore.decode(ProfileStore.encode(List.of(profile("a","missing")))));
        assertThrows(Exception.class,() -> new Profile("a","x\nHost *","example","u",22,"","",true,false));
        assertThrows(Exception.class,() -> Profile.from(Map.of("id","x","name","x","host","h","port",22.5)));
    }
    @Test void puttyAndXshellMetadataRoundTrip() throws Exception {
        Profile p=profile("a","");
        Profile fromReg=AccountFormats.putty(AccountFormats.export(List.of(p),"reg")).get(0);
        assertEquals(p.name(),fromReg.name());assertEquals(p.host(),fromReg.host());assertEquals(2222,fromReg.port());assertEquals(p.identity(),fromReg.identity());
        Profile fromXsh=AccountFormats.xshell(AccountFormats.export(List.of(p),"xsh"),"prod.xsh").get(0);
        assertEquals("prod",fromXsh.name());assertEquals(p.user(),fromXsh.user());assertEquals(p.identity(),fromXsh.identity());
        for(String format:List.of("reg","xsh")){
            Path file=dir.resolve("中文."+format);AccountFormats.write(file,List.of(p),format);
            byte[] bytes=Files.readAllBytes(file);assertEquals((byte)0xff,bytes[0]);assertEquals((byte)0xfe,bytes[1]);assertEquals(p.host(),AccountFormats.read(file).get(0).host());
        }
        Profile duplicate=new Profile("other",p.name(),"other.internal","u",22,"","",true,false);
        List<Profile> reg=AccountFormats.putty(AccountFormats.export(List.of(p,duplicate),"reg"));assertNotEquals(reg.get(0).name(),reg.get(1).name());
    }
    @Test void opensshJumpChainsAreImportedAndReexported() throws Exception {
        Path file=dir.resolve("config");Files.writeString(file,"Host edge\n HostName edge.internal\n User admin\nHost db\n HostName db.internal\n ProxyJump edge\n");
        List<Profile> result=AccountFormats.read(file);assertEquals(2,result.size());assertEquals(result.get(0).id(),result.get(1).jumpId());
        assertTrue(AccountFormats.export(result,"openssh").contains("ProxyJump edge"));
        Files.writeString(file,"Host db\n HostName db.internal\n ProxyJump alice@edge:2222,bob@inner:2200\n");
        result=AccountFormats.read(file);assertEquals(3,result.size());DesktopApp.validateRoutes(result);
        assertEquals(result.get(2).id(),result.get(0).jumpId());assertEquals(result.get(1).id(),result.get(2).jumpId());
    }
    @Test void dangerousCommandsAreReviewedAndReadOnlyCommandsRemainFast() {
        for(String s:List.of("rm -rf /tmp/dir","sudo apt update","mkfs.ext4 /dev/sda","curl https://x | bash","dd if=x of=/dev/sda","DROP DATABASE prod"))assertTrue(CommandSafety.needsReview(s),s);
        for(String s:List.of("ls -la","git status","printf hello","cat /etc/os-release"))assertFalse(CommandSafety.needsReview(s),s);
        assertEquals(1000,SessionPane.reconnectDelayMillis(1));assertEquals(30000,SessionPane.reconnectDelayMillis(50));
    }
}
