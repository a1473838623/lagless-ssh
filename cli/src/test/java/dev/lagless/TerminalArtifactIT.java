package dev.lagless;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import static org.junit.jupiter.api.Assertions.*;

/** Inspect and execute the real shaded artifact, after the package phase. */
class TerminalArtifactIT {
    private final Path artifact=Path.of("target/lagless-cli-standalone.jar").toAbsolutePath();

    @Test void terminalContainsCoreAndNoDesktopClassesOrTheme()throws Exception{
        try(JarFile jar=new JarFile(artifact.toFile())){
            assertNotNull(jar.getEntry("dev/lagless/Main.class"));
            assertNotNull(jar.getEntry("dev/lagless/TerminalSession.class"));
            assertNotNull(jar.getEntry("dev/lagless/LocalEcho.class"));
            assertEquals("terminal",jar.getManifest().getMainAttributes().getValue("Lagless-Distribution"));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().startsWith("dev/lagless/desktop/") || entry.getName().startsWith("com/formdev/")));
            assertNull(jar.getEntry("META-INF/services/dev.lagless.ApplicationLauncher"));
        }
        try(JarFile core=new JarFile(Path.of("../core/target/lagless-core.jar").toFile())){
            assertNotNull(core.getEntry("dev/lagless/SshConnection.class"));assertNull(core.getEntry("dev/lagless/Main.class"));
        }
    }
    @Test void terminalHelpStartsWithGuiModuleUnavailable()throws Exception{
        Result result=run("--help");assertEquals(0,result.code(),result.text());assertTrue(result.text().contains("--no-predict"));
    }
    @Test void optionalGuiHasFriendlyDiagnosticWithoutLoadingSwing()throws Exception{
        Result result=run("gui");assertEquals(2,result.code(),result.text());assertTrue(result.text().contains("terminal-only"));assertFalse(result.text().contains("NoClassDefFoundError"));
    }
    private record Result(int code,String text){ }
    private Result run(String argument)throws Exception{
        String executable=System.getProperty("os.name").toLowerCase().contains("win")?"java.exe":"java";
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),
                "--limit-modules=java.base,java.logging,java.management,java.net.http,java.security.jgss,jdk.crypto.ec,jdk.unsupported",
                "-jar",artifact.toString(),argument).redirectErrorStream(true).start();
        if(!process.waitFor(20,TimeUnit.SECONDS)){process.destroyForcibly();fail("terminal did not exit");}
        return new Result(process.exitValue(),new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
    }
}
