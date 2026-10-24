package dev.lagless.desktop;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceFeaturesTest {
    @TempDir Path dir;
    @Test void commandJournalRecordsOnlyAcceptedSubmissionsAndSurvivesClose()throws Exception{
        LocalCommandJournal log=new LocalCommandJournal(dir);Profile profile=new Profile("one","test","example.invalid","user",22,"","",true,false);
        try{var refused=log.prepare(profile,"session-one","not queued",false);refused.finish(false);var accepted=log.prepare(profile,"session-one","echo accepted",false);accepted.finish(true);log.force();String text=Files.readString(log.path());assertTrue(text.contains("echo accepted"));assertTrue(text.contains("session-one"));assertFalse(text.contains("not queued"));assertTrue(log.path().startsWith(dir.resolve("desktop/commands")));}finally{log.close();}
    }
    @Test void historyIsPagedAndSoftwareEventsAreSeparate()throws Exception{
        Path raw=dir.resolve("raw.log");Files.writeString(raw,"\u001b[32m你好 world\u001b[0m\n".repeat(30000)+"final line\n");
        var tail=LogViewer.readPage(raw,-1);assertTrue(tail.text().endsWith("final line\n"));assertTrue(tail.start()>0);assertTrue(tail.end()-tail.start()<=LogViewer.PAGE_BYTES);assertFalse(tail.text().contains("\u001b"));
        var first=LogViewer.readPage(raw,0);assertEquals(0,first.start());assertTrue(first.text().startsWith("你好 world"));assertTrue(first.end()<first.length());
        try(var events=new ApplicationJournal(dir)){events.record("fixture","连接中断\nforged entry");assertTrue(events.path().startsWith(dir.resolve("desktop/events")));assertTrue(Files.readString(events.path()).contains("连接中断 forged entry"));}
        assertEquals(tail.length(),Files.size(raw));
    }
    @Test void pagingPreservesLongLinesAndUtf8Boundaries()throws Exception{
        String original="字".repeat(200000)+"\nlast";Path file=dir.resolve("unicode.log");Files.writeString(file,original);long offset=0;StringBuilder joined=new StringBuilder();while(offset<Files.size(file)){var page=LogViewer.readPage(file,offset);assertTrue(page.end()>offset);assertFalse(page.text().contains("\ufffd"));joined.append(page.text());offset=page.end();}assertEquals(original,joined.toString());
    }
    @Test void automaticScrollPagesReachEveryHistoricalLine()throws Exception{
        StringBuilder original=new StringBuilder();for(int i=0;i<40000;i++)original.append("第").append(i).append("行 · full history\n");
        Path file=dir.resolve("scroll.log");Files.writeString(file,original);
        var tail=LogViewer.readPage(file,-1);var pieces=new java.util.ArrayDeque<String>();pieces.add(tail.text());
        long start=tail.start();while(start>0){var older=LogViewer.readRange(file,Math.max(0,start-LogViewer.PAGE_BYTES),start,true,false);assertTrue(older.start()<start);assertEquals(start,older.end());pieces.addFirst(older.text());start=older.start();}
        assertEquals(original.toString(),String.join("",pieces));
        StringBuilder forward=new StringBuilder();long at=0;while(at<Files.size(file)){var next=LogViewer.readRange(file,at,at+LogViewer.PAGE_BYTES,false,true);assertTrue(next.end()>at);forward.append(next.text());at=next.end();}
        assertEquals(original.toString(),forward.toString());
    }
    @Test void repeatedRefreshIsDeduplicatedButLaterRepeatedOutputIsKept()throws Exception{
        var rows=new java.util.ArrayList<TerminalHistory.Row>();ScreenCapture capture=new ScreenCapture(rows::add);
        int[] characters="same output".codePoints().toArray();String[] pens=new String[characters.length];java.util.Arrays.fill(pens,"");
        capture.scrolled(characters,pens);capture.flush();capture.scrolled(characters,pens);capture.flush();assertEquals(1,rows.size());
        Thread.sleep(2100);
        capture.scrolled(characters,pens);capture.flush();assertEquals(2,rows.size());
    }
    @Test void completeTranscriptKeepsRawRemoteOutputAndSubmittedCommands()throws Exception{
        Path log;
        try(SessionJournal journal=new SessionJournal(dir,"complete");SessionTranscript transcript=new SessionTranscript(journal,()->false)){
            log=journal.path();
            transcript.append("ordinary-output\r\n",80,24);
            transcript.recordSubmittedInput("echo first\necho second");
            transcript.append("\u001b[?1049h\u001b[HClaude frame 1",80,24);
            transcript.append("\u001b[HClaude frame 2",80,24);
        }
        String readable=Files.readString(log),raw=Files.readString(log.resolveSibling(log.getFileName()+".vt"));
        assertTrue(readable.contains("ordinary-output"));
        assertTrue(readable.contains("[本地输入 · 已提交]\necho first\necho second"));
        assertTrue(raw.contains("ordinary-output"));
        assertTrue(raw.contains("Claude frame 1"));
        assertTrue(raw.contains("Claude frame 2"));
    }
    @Test void selectingJumpImportsOnlyItsRoute()throws Exception{
        Profile parent=new Profile("parent","parent","parent.invalid","user",22,"","",true,false),jump=new Profile("jump","jump","jump.invalid","user",22,"","parent",true,false),other=new Profile("other","deleted-unrelated","other.invalid","user",22,"","",true,false);
        var current=new java.util.ArrayList<Profile>();String id=TerminalProfiles.addJumpChoice(current,java.util.List.of(parent,jump,other),jump);assertEquals(2,current.size());assertTrue(current.stream().anyMatch(p->p.id().equals(id)&&!p.jumpId().isBlank()));assertEquals(id,TerminalProfiles.addJumpChoice(current,java.util.List.of(parent,jump,other),jump));assertEquals(2,current.size());
    }
    @Test void cursorHasVisibleAndHiddenPhasesAndPreferencesPersist()throws Exception{assertTrue(SwingTerminal.cursorPhase(0));assertFalse(SwingTerminal.cursorPhase(530_000_000L));assertTrue(SwingTerminal.cursorPhase(1_060_000_000L));DesktopPreferences first=new DesktopPreferences(dir);first.set("editor-position","right");assertEquals("right",new DesktopPreferences(dir).get("editor-position","bottom"));}
}
