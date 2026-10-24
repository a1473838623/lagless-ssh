package dev.lagless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class UpdateRollbackTest {
    @TempDir Path dir;
    @Test void rejectsTruncatedZipAndStagesValidatedRollback() throws Exception {
        Path jar=dir.resolve("lagless.jar");Files.writeString(jar,"PKbroken");assertFalse(Updater.looksLikeJar(jar));
        assertThrows(Exception.class,() -> Updater.stageRollback(jar));
        Manifest manifest=new Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");manifest.getMainAttributes().putValue("Main-Class","dev.lagless.Main");
        Path rollback=dir.resolve("lagless.jar.rollback");try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(rollback),manifest)){out.putNextEntry(new JarEntry("dev/lagless/Main.class"));out.write(new byte[]{1,2,3});out.closeEntry();}
        Files.copy(rollback,jar,StandardCopyOption.REPLACE_EXISTING);byte[] original=Files.readAllBytes(jar);
        assertTrue(Updater.looksLikeJar(rollback));Updater.stageRollback(jar);assertArrayEquals(Files.readAllBytes(rollback),Files.readAllBytes(dir.resolve("lagless.jar.new")));assertArrayEquals(original,Files.readAllBytes(jar));
    }
}
