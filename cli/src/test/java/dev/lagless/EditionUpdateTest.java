package dev.lagless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class EditionUpdateTest {
    @TempDir Path dir;
    private Path jar(String name,String edition)throws Exception{
        Manifest manifest=new Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");manifest.getMainAttributes().putValue("Main-Class","dev.lagless.Main");
        if(edition!=null)manifest.getMainAttributes().putValue("Lagless-Distribution",edition);
        Path path=dir.resolve(name);
        try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(path),manifest)){out.putNextEntry(new JarEntry("dev/lagless/Main.class"));out.write(new byte[]{1,2,3});out.closeEntry();}
        return path;
    }
    @Test void editionsUseSeparateReleaseAssetsAndNeverSubstituteDesktop()throws Exception{
        assertEquals("lagless-terminal.jar",Installation.updateAssetName("terminal"));assertEquals("lagless.jar",Installation.updateAssetName("desktop"));
        Path terminal=jar("terminal.jar","terminal"),desktop=jar("desktop.jar","desktop"),legacy=jar("legacy.jar",null);
        assertTrue(Updater.isCompatibleUpdate(terminal,jar("terminal-new.jar","terminal")));
        assertFalse(Updater.isCompatibleUpdate(terminal,desktop));assertTrue(Updater.isCompatibleUpdate(terminal,legacy));
        assertFalse(Updater.isCompatibleUpdate(desktop,legacy));assertEquals("terminal",Installation.distribution(legacy));
    }
    @Test void rollbackCannotInstallAnotherEdition()throws Exception{
        Path installed=jar("lagless.jar","terminal");jar("lagless.jar.rollback","desktop");
        assertThrows(Exception.class,() -> Updater.stageRollback(installed));assertFalse(Files.exists(dir.resolve("lagless.jar.new")));
    }
}
