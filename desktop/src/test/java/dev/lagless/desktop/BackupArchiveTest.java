package dev.lagless.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BackupArchiveTest {
    @TempDir Path dir;
    private final char[] password="long-test-password".toCharArray();
    private Path source() throws Exception {
        Path home=dir.resolve("source");Files.createDirectories(home.resolve(".ssh"));Files.writeString(home.resolve(".ssh/id_test"),"PRIVATE KEY TEST");
        Files.createDirectories(home.resolve(".lagless/logs"));
        byte[] log=new byte[300000];new Random(1).nextBytes(log);Files.write(home.resolve(".lagless/logs/session.log"),log);
        new ProfileStore(home.resolve(".lagless")).save(List.of(new Profile("id","box","box.internal","u",22,"","",true,false)));
        return home;
    }
    @Test void restoresKeysAccountsAndMultiframeLog() throws Exception {
        Path source=source(), archive=dir.resolve("backup.llbackup"), target=dir.resolve("target");
        BackupArchive.create(source,archive,password,null);BackupArchive.restore(archive,target,password,null);
        assertEquals("PRIVATE KEY TEST",Files.readString(target.resolve(".ssh/id_test")));
        assertArrayEquals(Files.readAllBytes(source.resolve(".lagless/logs/session.log")),Files.readAllBytes(target.resolve(".lagless/logs/session.log")));
        assertEquals(1,new ProfileStore(target.resolve(".lagless")).load().size());
        assertFalse(new String(Files.readAllBytes(archive),java.nio.charset.StandardCharsets.ISO_8859_1).contains("PRIVATE KEY TEST"));
    }
    @Test void wrongPasswordTamperAndTruncationNeverChangeExistingFiles() throws Exception {
        Path archive=dir.resolve("backup.llbackup"), source=source(), target=dir.resolve("target");
        Files.createDirectories(target.resolve(".ssh"));Files.writeString(target.resolve(".ssh/id_test"),"unchanged");BackupArchive.create(source,archive,password,null);
        assertThrows(Exception.class,() -> BackupArchive.restore(archive,target,"wrong".toCharArray(),null));
        byte[] valid=Files.readAllBytes(archive),bad=valid.clone();bad[bad.length/2]^=1;Files.write(archive,bad);
        assertThrows(Exception.class,() -> BackupArchive.restore(archive,target,password,null));assertEquals("unchanged",Files.readString(target.resolve(".ssh/id_test")));
        Files.write(archive,Arrays.copyOf(valid,valid.length-20));
        assertThrows(Exception.class,() -> BackupArchive.restore(archive,target,password,null));assertEquals("unchanged",Files.readString(target.resolve(".ssh/id_test")));
    }
    @Test void traversalAndRecursiveBackupAreRejected() throws Exception {
        for(String path:List.of("../escape",".ssh/../../escape",".lagless/../evil","C:/escape",".ssh\\..\\evil","/etc/passwd","application/evil"))assertFalse(BackupArchive.validEntry(path),path);
        assertTrue(BackupArchive.validEntry(".ssh/config"));
        Path source=source();assertThrows(Exception.class,() -> BackupArchive.create(source,source.resolve(".lagless/archive"),password,null));
    }
    @Test void journalExportContainsFullOutputAndSurvivesClose() throws Exception {
        Path log;
        try(SessionJournal journal=new SessionJournal(dir,"生产")){
            log=journal.path();journal.append("first\n");journal.append("中文\u001b[31m\n");Path export=dir.resolve("export.log");journal.export(export);
            assertEquals("first\n中文\u001b[31m\n",Files.readString(export));
        }
        assertEquals("first\n中文\u001b[31m\n",Files.readString(log));
    }
}
