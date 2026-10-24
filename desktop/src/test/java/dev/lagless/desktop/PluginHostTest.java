package dev.lagless.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

public class PluginHostTest {
    @TempDir Path dir;
    public static class SlowPlugin implements LaglessPlugin {
        static CountDownLatch started,release;
        public String name(){return "slow-test";}
        public void onOutput(String session,String text)throws Exception{started.countDown();release.await();}
        public String assist(String action,String text,String context){return "draft:"+text;}
    }
    @Test void discoversProviderAndSlowEventsCannotBlockTerminalCaller() throws Exception {
        SlowPlugin.started=new CountDownLatch(1);SlowPlugin.release=new CountDownLatch(1);
        Path plugins=dir.resolve("plugins");Files.createDirectories(plugins);
        try(JarOutputStream jar=new JarOutputStream(Files.newOutputStream(plugins.resolve("test.jar")))){
            jar.putNextEntry(new JarEntry("META-INF/services/dev.lagless.desktop.LaglessPlugin"));jar.write(SlowPlugin.class.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));jar.closeEntry();
        }
        try(PluginHost host=new PluginHost(dir)){
            assertEquals(2,host.plugins().size());assertEquals("draft:ls",host.plugins().get(1).assist("complete","ls",""));
            host.output("s","one");assertTrue(SlowPlugin.started.await(2,TimeUnit.SECONDS));long start=System.nanoTime();
            for(int i=0;i<300;i++)host.output("s","more");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<500);assertTrue(host.skippedEvents()>0);
        }finally{SlowPlugin.release.countDown();}
    }
}
