package dev.lagless.desktop;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class BackgroundSessionsTest {
    @TempDir Path dir;

    @Test void checkOutputPicksAnInstallCommandForThePackageManager(){
        BackgroundSessions.Check missing=BackgroundSessions.parseCheck("pm=apt-get\nuid=1000\n");
        assertFalse(missing.tmux());assertEquals("sudo apt-get update && sudo apt-get install -y tmux",BackgroundSessions.installCommand(missing));
        BackgroundSessions.Check root=BackgroundSessions.parseCheck("pm=dnf\nuid=0\n");assertEquals("dnf install -y tmux",BackgroundSessions.installCommand(root));
        assertEquals("",BackgroundSessions.installCommand(BackgroundSessions.parseCheck("uid=1000\n")));
        BackgroundSessions.Check present=BackgroundSessions.parseCheck("tmux=tmux 3.4\npm=apt-get\nuid=1000\n");assertTrue(present.tmux());assertEquals("tmux 3.4",present.version());
    }
    @Test void onlySessionsLaglessCreatedAreListedNewestFirst(){
        List<BackgroundSessions.Session> sessions=BackgroundSessions.parseList("work|1|100|0|1\nmine||50|1|2\nother||300|0|1\nlater|1|200|0|1\ndir|mine\n");
        assertEquals(List.of("later","work","mine"),sessions.stream().map(BackgroundSessions.Session::name).toList());
        assertTrue(sessions.get(2).attached());assertEquals(2,sessions.get(2).windows());
    }
    @Test void defaultNamesAreValidTmuxNames(){
        String name=BackgroundSessions.defaultName("生产 · Tokyo/db",LocalDateTime.of(2026,10,25,9,5));
        assertEquals("Tokyo-db-1025-0905",name);assertTrue(BackgroundSessions.NAME.matcher(name).matches());
        assertEquals("lagless-1025-0905",BackgroundSessions.defaultName("中文",LocalDateTime.of(2026,10,25,9,5)));
    }
    @Test void recordingsImportFullScreenOutputIntoTheLocalLogs()throws Exception{
        String raw="plain line\r\n\u001b[?1049h\u001b[2J\u001b[1;1H\u001b[32mfull screen view\u001b[0m\u001b[5;3Hstatus 42\u001b[?1049l\r\nafter\r\n";
        Path log=BackgroundSessions.importRecording(dir,"prod","work",new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),0,80,24,true);
        String text=Files.readString(log);
        assertTrue(text.startsWith("[Background session work")||text.startsWith("[后台会话 work"),text);
        assertTrue(text.contains("plain line"),text);assertTrue(text.contains("full screen view"),text);assertTrue(text.contains("status 42"),text);assertTrue(text.contains("after"),text);
        assertEquals(raw,Files.readString(log.resolveSibling(log.getFileName()+".vt")));
    }
    @Test void serverScriptsRecordDetachAndHandOverRecordings()throws Exception{
        Assumptions.assumeFalse(System.getProperty("os.name", "").startsWith("Windows"),"tmux shell integration requires a POSIX host");
        Assumptions.assumeTrue(run("command -v tmux").exitCode()==0,"tmux not installed");
        String name="lagless-test-"+ProcessHandle.current().pid();
        try{
            Result prepared=run(BackgroundSessions.prepareCommand(name));
            BackgroundSessions.Prepared first=BackgroundSessions.parsePrepared(prepared.out());
            assertNotNull(first.pipe(),prepared.out());assertTrue(first.recordings().isEmpty());
            run("tmux send-keys -t ="+name+": 'echo marker-$((6*7))' Enter");
            assertTrue(waitFor(Path.of(first.pipe()),"marker-42"),"pipe-pane output is recorded");
            assertEquals(List.of(name),BackgroundSessions.parseList(run(BackgroundSessions.listCommand()).out()).stream().map(BackgroundSessions.Session::name).filter(name::equals).toList());
            // Clean detach: the attached output is dropped and a new recording starts.
            run(BackgroundSessions.detachCommand(name,first.pipe()));assertFalse(Files.exists(Path.of(first.pipe())));
            run("tmux send-keys -t ="+name+": 'echo away-$((5*5))' Enter");
            Thread.sleep(300);
            BackgroundSessions.Prepared second=BackgroundSessions.parsePrepared(run(BackgroundSessions.prepareCommand(name)).out());
            assertEquals(1,second.recordings().size());assertNotEquals(second.pipe(),second.recordings().getFirst());
            assertTrue(waitFor(Path.of(second.recordings().getFirst()),"away-25"),"output while detached is kept for import");
        }finally{run("tmux kill-session -t ="+name);}
    }
    private boolean waitFor(Path file,String text)throws Exception{
        for(int i=0;i<50;i++){if(Files.exists(file)&&Files.readString(file).contains(text))return true;Thread.sleep(100);}
        return false;
    }
    private record Result(int exitCode,String out){}
    private Result run(String command)throws Exception{
        ProcessBuilder builder=new ProcessBuilder("sh","-c",command).redirectErrorStream(true);
        builder.environment().put("HOME",dir.toString());builder.environment().put("TMUX_TMPDIR",dir.toString());builder.environment().remove("TMUX");
        Process process=builder.start();String out=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertTrue(process.waitFor(20,TimeUnit.SECONDS));return new Result(process.exitValue(),out);
    }
}
