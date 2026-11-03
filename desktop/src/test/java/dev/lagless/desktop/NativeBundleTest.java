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
    /** A bundle whose program acts as the installer: it reports ready, then waits. */
    private Path scriptBundle(String name,String version)throws Exception{
        Path root=dir.resolve(name);Files.createDirectories(root);
        Path program=root.resolve("Lagless.exe");
        Files.writeString(program,"#!/bin/sh\nname=$(basename \"$2\" .json)\nprintf '{}' > \"$(dirname \"$2\")/ready-${name#request-}.json\"\nexec sleep 300\n");program.toFile().setExecutable(true);
        Files.writeString(root.resolve("bundle.json"),Json.stringify(Map.of("format",1,"edition","desktop-native","version",version,"platform",NativeBundle.platform(),"executable","Lagless.exe",
                "files",List.of(Map.of("path","Lagless.exe","size",Files.size(program),"sha256",NativeBundle.sha256(program),"executable",true)))));
        return root;
    }
    private static void killInstaller(Path home)throws Exception{
        Object pending=Json.parse(Files.readString(home.resolve(".updates/pending.json")));
        ProcessHandle process=ProcessHandle.of(Json.number(pending,-1,"helper")).orElseThrow();
        process.destroyForcibly();process.onExit().get();
    }
    @Test void aDownloadedUpdateOutlivesItsInstaller()throws Exception{
        org.junit.jupiter.api.Assumptions.assumeFalse(NativeBundle.platform().startsWith("windows"),"the fixture installer is a shell script");
        Path home=scriptBundle("home","1"),next=scriptBundle("next","2"),zip=dir.resolve("next.zip");NativeBundle.pack(next,zip);
        try{
            NativeUpdater.stage(home,zip,true);
            assertTrue(NativeUpdater.hasPending(home));
            assertEquals("2",NativeUpdater.waiting(home).to());
            assertFalse(NativeUpdater.resume(home,false),"its installer still runs");
            // Rebooted or killed: nothing reported. A launch starts a new installer for the same files.
            Path staged=NativeUpdater.waiting(home).staged();
            killInstaller(home);assertFalse(NativeUpdater.hasPending(home));
            assertTrue(NativeUpdater.resume(home,true));assertTrue(NativeUpdater.hasPending(home));
            assertEquals(staged,NativeUpdater.waiting(home).staged(),"no second copy of the download");
            try(var installers=Files.list(home.resolve(".updates"))){assertEquals(1,installers.filter(p->p.getFileName().toString().startsWith("installer-")).count(),"the old installer's copy is removed");}
            // Launches stop retrying on their own; the Restart button still works.
            killInstaller(home);assertTrue(NativeUpdater.resume(home,true));
            killInstaller(home);assertFalse(NativeUpdater.resume(home,true),"after "+NativeUpdater.AUTO_RESUMES+" tries");
            assertTrue(NativeUpdater.resume(home,false));assertTrue(NativeUpdater.hasPending(home));
            // A failed install is not retried by a launch, only on request.
            killInstaller(home);Files.writeString(home.resolve(".updates/result.json"),Json.stringify(Map.of("ok",false,"error","files in use","nonce","x")));
            assertFalse(NativeUpdater.resume(home,true));assertTrue(NativeUpdater.waiting(home).reported());
            assertTrue(NativeUpdater.resume(home,false));assertFalse(NativeUpdater.waiting(home).reported(),"a new attempt clears the old result");
            // Damaged files are not installed; the caller says to download again.
            killInstaller(home);Files.writeString(staged.resolve("Lagless.exe"),"damaged");
            assertFalse(NativeUpdater.resume(home,false));assertNull(NativeUpdater.waiting(home));
        }finally{
            Path pending=home.resolve(".updates/pending.json");
            if(Files.isRegularFile(pending))ProcessHandle.of(Json.number(Json.parse(Files.readString(pending)),-1,"helper")).ifPresent(ProcessHandle::destroyForcibly);
        }
    }
    @Test void aWaitingUpdateIsDroppedOnceTheVersionItReplacesIsGone()throws Exception{
        org.junit.jupiter.api.Assumptions.assumeFalse(NativeBundle.platform().startsWith("windows"),"the fixture installer is a shell script");
        Path home=scriptBundle("home","1"),next=scriptBundle("next","3"),zip=dir.resolve("next.zip");NativeBundle.pack(next,zip);
        try{
            NativeUpdater.stage(home,zip,true);killInstaller(home);assertNotNull(NativeUpdater.waiting(home));
            // The user extracted a newer ZIP over the installation by hand.
            Path newer=scriptBundle("newer","2");Files.copy(newer.resolve("bundle.json"),home.resolve("bundle.json"),StandardCopyOption.REPLACE_EXISTING);
            assertNull(NativeUpdater.waiting(home));assertFalse(NativeUpdater.resume(home,true));
        }finally{
            Path pending=home.resolve(".updates/pending.json");
            if(Files.isRegularFile(pending))ProcessHandle.of(Json.number(Json.parse(Files.readString(pending)),-1,"helper")).ifPresent(ProcessHandle::destroyForcibly);
        }
    }
    @Test void theInstallerWaitsAsLongAsTheProgramRunsAndOnlyGivesUpOnHeldFiles()throws Exception{
        long hour=java.util.concurrent.TimeUnit.HOURS.toNanos(1);
        class Clock implements NativeUpdater.ExitWait{
            long now,programUntil,heldUntil;
            Clock(long programUntil,long heldUntil){this.programUntil=programUntil;this.heldUntil=heldUntil;}
            public boolean programRunning(){return now<programUntil;}
            public boolean filesHeld(){return now<heldUntil;}
            public long now(){return now;}
            public void pause(){now+=java.util.concurrent.TimeUnit.SECONDS.toNanos(30);}
        }
        Clock days=new Clock(hour*24*5,0);
        assertTrue(NativeUpdater.awaitExit(days,NativeUpdater.FILE_RELEASE_NANOS),"open for days: no time limit");
        assertTrue(days.now>=hour*24*5);
        Clock briefly=new Clock(hour*24,hour*24+java.util.concurrent.TimeUnit.MINUTES.toNanos(2));
        assertTrue(NativeUpdater.awaitExit(briefly,NativeUpdater.FILE_RELEASE_NANOS),"files freed soon after the exit");
        Clock stuck=new Clock(hour*24,Long.MAX_VALUE);
        assertFalse(NativeUpdater.awaitExit(stuck,NativeUpdater.FILE_RELEASE_NANOS),"files held long after the exit");
        assertTrue(stuck.now-hour*24<=NativeUpdater.FILE_RELEASE_NANOS+java.util.concurrent.TimeUnit.SECONDS.toNanos(60));
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
