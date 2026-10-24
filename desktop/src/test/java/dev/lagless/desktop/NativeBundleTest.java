package dev.lagless.desktop;

import dev.lagless.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeBundleTest {
    @TempDir Path dir;
    private Path bundle(String name,String version)throws Exception{
        Path root=dir.resolve(name);Files.createDirectories(root.resolve("lib"));Files.writeString(root.resolve("Lagless.exe"),"native fixture "+version);Files.writeString(root.resolve("lib/config"),version);
        List<Object> files=new ArrayList<>();for(String path:List.of("Lagless.exe","lib/config")){Path f=root.resolve(path);files.add(Map.of("path",path,"size",Files.size(f),"sha256",NativeBundle.sha256(f),"executable",path.endsWith("exe")));}
        Files.writeString(root.resolve("bundle.json"),Json.stringify(Map.of("format",1,"edition","desktop-native","version",version,"platform",NativeBundle.platform(),"executable","Lagless.exe","files",files)));return root;
    }
    @Test void validatesInstallsAndRollsBackWholeManagedBundle()throws Exception{
        Path old=bundle("old","1"),next=bundle("next","2");Files.writeString(old.resolve("personal.txt"),"keep");
        Path zip=dir.resolve("next.zip");NativeBundle.pack(next,zip);Path stage=NativeUpdater.prepare(old,zip);assertEquals("1",Files.readString(old.resolve("lib/config")));
        NativeUpdater.apply(old,stage);assertEquals("2",NativeBundle.validate(old,true).version());assertEquals("2",Files.readString(old.resolve("lib/config")));assertEquals("keep",Files.readString(old.resolve("personal.txt")));
        Path previous=NativeUpdater.prepare(old,old.resolve(".updates/previous.zip"));NativeUpdater.apply(old,previous);assertEquals("1",NativeBundle.validate(old,true).version());
    }
    @Test void rejectsTamperingBeforeChangingInstalledFiles()throws Exception{
        Path old=bundle("old","1"),next=bundle("next","2");Files.writeString(next.resolve("Lagless.exe"),"corrupted");assertThrows(Exception.class,()->NativeUpdater.apply(old,next));assertEquals("1",NativeBundle.validate(old,true).version());
    }
    @Test void rollsBackFilesWhenAnInstallationWriteFails()throws Exception{
        Path old=bundle("old","1"),next=bundle("next","2");
        // An undeletable nonempty directory replaces a managed target after validation.
        // A blocked additional destination fails only after another managed file changed.
        Files.createDirectories(old.resolve("blocked"));Files.writeString(old.resolve("blocked/keep"),"personal");
        Files.writeString(next.resolve("blocked"),"new file");Object manifest=Json.parse(Files.readString(next.resolve("bundle.json")));
        @SuppressWarnings("unchecked") List<Object> files=(List<Object>)Json.get(manifest,"files");files.add(Map.of("path","blocked","size",8,"sha256",NativeBundle.sha256(next.resolve("blocked")),"executable",false));
        Files.writeString(next.resolve("bundle.json"),Json.stringify(manifest));assertThrows(Exception.class,()->NativeUpdater.apply(old,next));assertEquals("1",NativeBundle.validate(old,true).version());assertEquals("personal",Files.readString(old.resolve("blocked/keep")));
    }
    @Test void rejectsTraversalAndUnlistedPayload()throws Exception{
        assertFalse(NativeBundle.safe(".UPDATES/request.json"));assertFalse(NativeBundle.safe(".Rollback/old"));
        Path bad=dir.resolve("bad.zip");try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(bad))){out.putNextEntry(new ZipEntry("../escape"));out.write(1);}
        assertThrows(Exception.class,()->NativeBundle.extract(bad,dir.resolve("extract"),true));assertFalse(Files.exists(dir.resolve("escape")));
        Path root=bundle("valid","1"),zip=dir.resolve("valid.zip");NativeBundle.pack(root,zip);
        try(FileSystem fs=FileSystems.newFileSystem(zip)){Files.writeString(fs.getPath("/extra.txt"),"unexpected");}
        assertThrows(Exception.class,()->NativeBundle.extract(zip,dir.resolve("extra"),true));
    }
    @Test void nativeApplicationCanBeBackedUpAndRestoredAsDataOnAnotherEdition()throws Exception{
        Path app=bundle("app","1"),home=dir.resolve("user");Files.createDirectories(home.resolve(".lagless"));Files.writeString(home.resolve(".lagless/config"),"setting = value");Path backup=dir.resolve("backup.llbackup");char[] secret="backup-password".toCharArray();BackupArchive.create(home,backup,secret,app);
        Path restored=dir.resolve("restored");BackupArchive.restore(backup,restored,secret,null);assertEquals("setting = value",Files.readString(restored.resolve(".lagless/config")));assertFalse(Files.exists(restored.resolve("application")));
    }
}
