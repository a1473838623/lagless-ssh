package dev.lagless.desktop;

import dev.lagless.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcessPluginTest {
    @TempDir Path dir;
    private ProcessPlugin plugin(String mode,int timeout)throws Exception{
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").contains("Windows")?"java.exe":"java").toString();
        Path manifest=dir.resolve("test.plugin.json");Files.writeString(manifest,Json.stringify(Map.of("version",1,"name","test","command",List.of(java,"-cp",System.getProperty("java.class.path"),Child.class.getName(),mode),"timeout-ms",timeout,"output-events",true)));return new ProcessPlugin(manifest);
    }
    @Test void communicatesWithRealProcessAndClosesRequests()throws Exception{
        ProcessPlugin p=plugin("normal",5000);try(p){p.onOutput("session","output");assertEquals("draft:你好",p.assist("command","你好","context"));}assertThrows(IOException.class,()->p.assist("command","text",""));
    }
    @Test void boundsRequestsAndTimeouts()throws Exception{
        try(ProcessPlugin p=plugin("sleep",200)){assertThrows(IOException.class,()->p.assist("command","text",""));assertThrows(IOException.class,()->p.assist("command","x".repeat(1024*1024+1),""));}
        assertThrows(IOException.class,()->ProcessPlugin.readLine(new StringReader("x".repeat(1024*1024+1))));
    }
    @Test void pluginBackpressureDoesNotBlockOutputProducer()throws Exception{
        try(ProcessPlugin p=plugin("sleep",200)){long start=System.nanoTime();for(int i=0;i<400;i++)p.onOutput("session","x".repeat(8192));assertTrue(p.skippedEvents()>0);assertTrue(System.nanoTime()-start<2_000_000_000L);}
    }
    public static final class Child {
        public static void main(String[]args)throws Exception{
            if(args[0].equals("sleep")){Thread.sleep(60000);return;}
            try(BufferedReader in=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8));PrintWriter out=new PrintWriter(new OutputStreamWriter(System.out,java.nio.charset.StandardCharsets.UTF_8),true)){
                String line;while((line=in.readLine())!=null){Object request=Json.parse(line);if(!"assist".equals(Json.string(request,"type")))continue;out.println(Json.stringify(Map.of("id",Json.number(request,0,"id"),"ok",true,"text","draft:"+Json.string(request,"text"))));}
            }
        }
    }
}
