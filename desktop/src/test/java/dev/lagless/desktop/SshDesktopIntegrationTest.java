package dev.lagless.desktop;

import dev.lagless.*;
import org.apache.sshd.server.*;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.jline.terminal.Terminal;
import javax.swing.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** A real SSH transport on loopback, with deliberately delayed remote echoes. No installed sshd. */
@Timeout(30)
class SshDesktopIntegrationTest {
    @TempDir Path dir;
    private SshServer server;
    private String previousHome;
    private final BlockingQueue<String> commands=new LinkedBlockingQueue<>();
    private final AtomicInteger confirmations=new AtomicInteger();
    @BeforeEach void start() throws Exception {
        previousHome=System.getProperty("user.home");System.setProperty("user.home",dir.toString());
        server=SshServer.setUpDefaultServer();server.setHost("127.0.0.1");server.setPort(0);
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);server.setKeyPairProvider(KeyPairProvider.wrap(generator.generateKeyPair()));
        server.setPasswordAuthenticator((u,p,s)->p.equals("test-password"));server.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        server.setFileSystemFactory(new org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(dir));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));server.setShellFactory(channel -> new DelayedShell());server.start();
    }
    @AfterEach void stop() throws Exception {try{if(server!=null)server.stop(true);}finally{System.setProperty("user.home",previousHome);}}
    private Options options(){Options o=new Options();o.host="127.0.0.1";o.port=server.getPort();o.user="tester";o.completion=false;o.remoteHistory=false;o.quietMs=30;o.promptRegex="test\\$ $";return o;}
    private SshConnection.Prompter prompter(){return new SshConnection.Prompter(){public String askSecret(String p){return "test-password";}public boolean askYesNo(String q){confirmations.incrementAndGet();return true;}public void show(String m){}};}
    @Test void jumpTransportSftpAndKnownHostPersistence() throws Exception {
        try(SshConnection jump=SshConnection.connect(options(),prompter());SshConnection target=SshConnection.connectVia(options(),prompter(),jump)){
            assertEquals(1,confirmations.get(),"accepted host key should be persisted before connecting again");
            target.openShell("xterm-256color",80,24);assertEquals("test$ ",new String(target.shellInput().readNBytes(6),StandardCharsets.UTF_8));
            var sftp=target.openSftp();try{String path="/sftp-test.txt";sftp.put(new ByteArrayInputStream("through-jump".getBytes(StandardCharsets.UTF_8)),path);try(InputStream in=sftp.get(path)){assertEquals("through-jump",new String(in.readAllBytes(),StandardCharsets.UTF_8));}sftp.rm(path);}finally{sftp.disconnect();}
            assertTrue(jump.isConnected());
        }
    }
    @Test void downloadQueueProgressCancellationAndFailureDoNotBlockNextJob()throws Exception{
        byte[] payload=new byte[128*1024];new Random(7).nextBytes(payload);Files.write(dir.resolve("queue-big.bin"),payload);Files.writeString(dir.resolve("queue-next.txt"),"next");ExecutorService cancels=Executors.newCachedThreadPool();
        try(SshConnection connection=SshConnection.connect(options(),prompter());TransferQueue queue=new TransferQueue(()->connection,event->{},cancels)){
            Path destination=dir.resolve("downloads");var first=queue.enqueue(false,destination.resolve("big.bin"),"/queue-big.bin",65536);await(()->first.state==TransferQueue.State.RUNNING&&first.bytes>0,5000);assertTrue(first.total>first.bytes);assertTrue(first.percent()>0&&first.percent()<100);
            var second=queue.enqueue(false,destination.resolve("next.txt"),"/queue-next.txt",65536);var cancelled=queue.enqueue(false,destination.resolve("cancelled.txt"),"/queue-next.txt",65536);queue.cancel(cancelled);assertFalse(Files.exists(second.local));await(()->second.terminal(),8000);assertEquals(TransferQueue.State.COMPLETE,first.state);assertEquals(100,first.percent());assertArrayEquals(payload,Files.readAllBytes(first.local));assertEquals(TransferQueue.State.COMPLETE,second.state);assertEquals(TransferQueue.State.CANCELLED,cancelled.state);assertFalse(Files.exists(cancelled.local));
            var failed=queue.enqueue(false,destination.resolve("missing"),"/no-such-file",65536);var after=queue.enqueue(false,destination.resolve("after.txt"),"/queue-next.txt",65536);await(()->after.terminal(),5000);assertEquals(TransferQueue.State.FAILED,failed.state);assertFalse(failed.error.isBlank());assertEquals(TransferQueue.State.COMPLETE,after.state);assertTrue(connection.isConnected());
        }finally{cancels.shutdown();}
    }
    @Test void terminalPasteMenuAcceptsTrailingNewlineWithoutExecuting()throws Exception{
        SwingTerminal[] holder={null};java.awt.datatransfer.Clipboard clipboard=new java.awt.datatransfer.Clipboard("terminal fixture");clipboard.setContents(new java.awt.datatransfer.StringSelection("echo pasted\r\n"),null);SwingUtilities.invokeAndWait(()->{holder[0]=new SwingTerminal();holder[0].setSize(900,500);holder[0].setClipboard(()->clipboard);});SwingTerminal view=holder[0];ExecutorService runner=Executors.newSingleThreadExecutor();
        try(SshConnection connection=SshConnection.connect(options(),prompter());Terminal terminal=view.attach()){
            TerminalSession engine=new TerminalSession(terminal,connection,options());engine.setDisconnectListener(view::endInput);view.accepting(true);Future<?> task=runner.submit(()->{try{engine.run();}catch(Exception e){throw new CompletionException(e);}});await(()->engine.mode()==TerminalSession.Mode.EDIT,5000);
            SwingUtilities.invokeAndWait(()->((JMenuItem)view.popupMenu().getComponent(1)).doClick());await(()->view.screenText().contains("echo pasted"),1000);assertTrue(commands.isEmpty());view.sendKeys("\r");assertEquals("echo pasted",commands.poll(2,TimeUnit.SECONDS));await(()->engine.mode()==TerminalSession.Mode.EDIT,5000);SwingUtilities.invokeAndWait(()->{javax.swing.Timer choice=new javax.swing.Timer(20,e->{for(java.awt.Window window:java.awt.Window.getWindows())if(window instanceof JDialog dialog&&dialog.isShowing()&&dialog.getTitle().equals("发送前检查换行"))setOption(dialog,"合并为一行（软换行）");});choice.start();try{assertTrue(view.pasteText("first\nsecond\n"));}finally{choice.stop();}});await(()->view.screenText().contains("first second"),1000);assertTrue(commands.isEmpty());connection.close();task.get(5,TimeUnit.SECONDS);
        }finally{runner.shutdownNow();SwingUtilities.invokeAndWait(view::close);}
    }
    @Test void passthroughForwardsChordsAndAltLettersWithoutLocalBindings()throws Exception{
        SwingTerminal[] holder={null};SwingUtilities.invokeAndWait(()->{holder[0]=new SwingTerminal();holder[0].setSize(900,500);});SwingTerminal view=holder[0];ExecutorService runner=Executors.newSingleThreadExecutor();
        try(SshConnection connection=SshConnection.connect(options(),prompter());Terminal terminal=view.attach()){
            List<String> writes=new CopyOnWriteArrayList<>();TerminalSession engine=new TerminalSession(terminal,connection,options());engine.setInputAudit(writes::add);engine.setDisconnectListener(view::endInput);view.accepting(true);Future<?> task=runner.submit(()->{try{engine.run();}catch(Exception e){throw new CompletionException(e);}});
            await(()->engine.mode()==TerminalSession.Mode.EDIT,5000);view.sendKeys("unfinished");await(()->view.screenText().contains("unfinished"),1000);
            assertTrue(engine.setPassThrough(true));view.sendKeys("\u001d");await(()->engine.mode()==TerminalSession.Mode.RAW,1000);view.sendKeys("\u0015");
            SwingUtilities.invokeAndWait(()->{
                var listener=view.getKeyListeners()[0];long now=System.currentTimeMillis();
                listener.keyPressed(new java.awt.event.KeyEvent(view,java.awt.event.KeyEvent.KEY_PRESSED,now,java.awt.event.InputEvent.CTRL_DOWN_MASK,java.awt.event.KeyEvent.VK_X,'\u0018'));
                listener.keyPressed(new java.awt.event.KeyEvent(view,java.awt.event.KeyEvent.KEY_PRESSED,now,java.awt.event.InputEvent.CTRL_DOWN_MASK,java.awt.event.KeyEvent.VK_S,'\u0013'));
                listener.keyPressed(new java.awt.event.KeyEvent(view,java.awt.event.KeyEvent.KEY_PRESSED,now,java.awt.event.InputEvent.ALT_DOWN_MASK,java.awt.event.KeyEvent.VK_V,'v'));
                listener.keyTyped(new java.awt.event.KeyEvent(view,java.awt.event.KeyEvent.KEY_TYPED,now,java.awt.event.InputEvent.ALT_DOWN_MASK,java.awt.event.KeyEvent.VK_UNDEFINED,'v'));
            });
            assertTrue(commands.isEmpty());assertTrue(writes.stream().noneMatch(x->x.contains("仅换行=true")),"compatibility toggle and wake key must not write a blank line to SSH");view.sendKeys("\r");assertEquals("\u0018\u0013\u001bv",commands.poll(2,TimeUnit.SECONDS));assertTrue(writes.stream().anyMatch(x->x.contains("来源=terminal-keyboard")&&x.contains("CR=1")&&x.contains("LF=0")),"audit counts Enter even when the terminal batches it with preceding shortcut keys");Thread.sleep(900);assertEquals(TerminalSession.Mode.RAW,engine.mode(),"prompt recognition stays disabled until explicitly restored");engine.setPassThrough(false);view.sendKeys("\u0003");await(()->engine.mode()==TerminalSession.Mode.EDIT,5000);connection.close();task.get(5,TimeUnit.SECONDS);
        }finally{runner.shutdownNow();SwingUtilities.invokeAndWait(view::close);}
    }
    @Test void recursiveFoldersRoundTripAndCancellationPreservesOriginal() throws Exception {
        Path source=dir.resolve("upload"),dest=dir.resolve("download");Files.createDirectories(source.resolve("子目录/empty"));Files.writeString(source.resolve("子目录/你好.txt"),"中文\n".repeat(20000));Files.writeString(source.resolve("root.txt"),"root");
        try(SshConnection connection=SshConnection.connect(options(),prompter())){
            var channel=connection.openSftp();try{
                var cancelled=new AtomicBoolean();var transfer=new SftpTransfers(channel,0,cancelled,n->{});transfer.upload(source,"/remote");transfer.download("/remote",dest);
                assertEquals(Files.readString(source.resolve("子目录/你好.txt")),Files.readString(dest.resolve("子目录/你好.txt")));assertTrue(Files.isDirectory(dest.resolve("子目录/empty")));
                Path original=dir.resolve("original.txt");Files.writeString(original,"must survive");cancelled.set(true);assertThrows(InterruptedIOException.class,()->transfer.download("/remote/root.txt",original));assertEquals("must survive",Files.readString(original));
                cancelled.set(false);var midFile=new SftpTransfers(channel,0,cancelled,n->cancelled.set(true));assertThrows(InterruptedIOException.class,()->midFile.download("/remote/子目录/你好.txt",original));assertEquals("must survive",Files.readString(original));
                assertThrows(IOException.class,()->SftpTransfers.child("/safe","../escape"));assertThrows(IOException.class,()->SftpTransfers.validName("CON.txt"));
            }finally{channel.disconnect();}
        }
    }
    @Test void localEditorEnterSendsOneLineWhilePastedNewlinesStayLocal() throws Exception {
        Files.createDirectories(dir.resolve(".lagless"));Files.writeString(dir.resolve(".lagless/config"),"completion = false\nremote-history = false\nquiet-ms = 30\n");
        DesktopApp[] app={null};SessionPane[] pane={null};SwingUtilities.invokeAndWait(()->{try{app[0]=new DesktopApp(dir.resolve(".lagless"));Profile p=new Profile("editor","editor","127.0.0.1","tester",server.getPort(),"","",true,false);pane[0]=new SessionPane(app[0],p,prompter());app[0].sessions.add(pane[0]);pane[0].view.setSize(900,500);pane[0].start();}catch(Exception e){throw new RuntimeException(e);}});
        try{
            await(pane[0]::canSend,5000);
            SwingUtilities.invokeAndWait(()->{pane[0].inlineDraft.setText("echo first\necho second");pane[0].inlineDraft.setCaretPosition(0);});
            assertNull(commands.poll(200,TimeUnit.MILLISECONDS),"pasted line breaks never send");
            javax.swing.Timer[] confirm={null};SwingUtilities.invokeAndWait(()->{confirm[0]=new javax.swing.Timer(20,e->{for(java.awt.Window window:java.awt.Window.getWindows())if(window instanceof JDialog dialog&&dialog.isShowing()&&dialog.getTitle().equals("发送前检查换行"))setOption(dialog,"合并为一行（软换行）");});confirm[0].start();});
            try{SwingUtilities.invokeAndWait(()->{JTextArea editor=pane[0].inlineDraft;Object binding=editor.getInputMap().get(KeyStroke.getKeyStroke("ENTER"));editor.getActionMap().get(binding).actionPerformed(new java.awt.event.ActionEvent(editor,0,""));assertEquals("",editor.getText(),"submitted editor content clears immediately after the whole draft is queued");assertEquals(editor.getText(),pane[0].draft.getText());});}finally{SwingUtilities.invokeAndWait(()->confirm[0].stop());}
            assertEquals("echo first echo second",commands.poll(2,TimeUnit.SECONDS));assertNull(commands.poll(200,TimeUnit.MILLISECONDS));await(pane[0]::canSend,5000);
            SwingUtilities.invokeAndWait(()->{pane[0].setEditorPlacement("right");pane[0].draft.setText("echo right");pane[0].sendEditor(pane[0].draft);assertEquals("",pane[0].draft.getText());assertEquals("",pane[0].inlineDraft.getText());});
            assertEquals("echo right",commands.poll(2,TimeUnit.SECONDS));await(pane[0]::canSend,5000);app[0].localCommands.force();String log=Files.readString(app[0].localCommands.path());assertTrue(log.contains("echo first echo second"));assertTrue(log.contains("echo right"));assertTrue(log.contains("editor"));
            SwingUtilities.invokeAndWait(()->{pane[0].draft.setText("unsent");pane[0].draft.setCaretPosition(pane[0].draft.getDocument().getLength());JTextArea editor=pane[0].draft;Object binding=editor.getInputMap().get(KeyStroke.getKeyStroke("shift ENTER"));editor.getActionMap().get(binding).actionPerformed(new java.awt.event.ActionEvent(editor,0,""));assertTrue(editor.getText().endsWith("\n"));});
            assertNull(commands.poll(200,TimeUnit.MILLISECONDS),"Shift+Enter never sends");
        }finally{SwingUtilities.invokeAndWait(app[0]::close);assertTrue(app[0].awaitShutdown(System.nanoTime()+TimeUnit.SECONDS.toNanos(15)),"desktop shutdown must finish before temporary home cleanup");}
    }
    @Test void failedSubmissionRetainsEditorAndCreatesNoCommandEntry()throws Exception{
        DesktopApp[] app={null};SessionPane[] pane={null};SwingUtilities.invokeAndWait(()->{try{app[0]=new DesktopApp(dir.resolve(".lagless"));pane[0]=new SessionPane(app[0],new Profile("offline","offline","example.invalid","user",22,"","",true,false));app[0].sessions.add(pane[0]);pane[0].draft.setText("keep offline");pane[0].sendEditor(pane[0].draft);assertEquals("keep offline",pane[0].draft.getText());}catch(Exception e){throw new RuntimeException(e);}});
        try{app[0].localCommands.force();assertFalse(Files.readString(app[0].localCommands.path()).contains("keep offline"));}finally{SwingUtilities.invokeAndWait(app[0]::close);assertTrue(app[0].awaitShutdown(System.nanoTime()+TimeUnit.SECONDS.toNanos(15)),"desktop shutdown must finish before temporary home cleanup");}
    }
    @Test void cancellingDangerReviewRetainsDraftAndApprovalIsRequestedOnlyOnce()throws Exception{
        Files.createDirectories(dir.resolve(".lagless"));Files.writeString(dir.resolve(".lagless/config"),"completion = false\nremote-history = false\nquiet-ms = 30\n");DesktopApp[] app={null};SessionPane[] pane={null};AtomicInteger prompts=new AtomicInteger(),answer=new AtomicInteger(JOptionPane.CANCEL_OPTION);javax.swing.Timer[] timer={null};
        SwingUtilities.invokeAndWait(()->{try{app[0]=new DesktopApp(dir.resolve(".lagless"));pane[0]=new SessionPane(app[0],new Profile("review","review","127.0.0.1","tester",server.getPort(),"","",true,false),prompter());app[0].sessions.add(pane[0]);pane[0].view.setSize(900,500);pane[0].start();timer[0]=new javax.swing.Timer(30,e->{for(java.awt.Window window:java.awt.Window.getWindows())if(window instanceof JDialog dialog&&dialog.isShowing()&&dialog.getTitle().startsWith("危险命令拦截")){prompts.incrementAndGet();setOption(dialog,answer.get());}});timer[0].start();}catch(Exception e){throw new RuntimeException(e);}});
        try{await(pane[0]::canSend,5000);String command="rm -rf /tmp/review-fixture";SwingUtilities.invokeAndWait(()->{pane[0].draft.setText(command);pane[0].sendEditor(pane[0].draft);assertEquals(command,pane[0].draft.getText());});assertTrue(commands.isEmpty());app[0].localCommands.force();assertFalse(Files.readString(app[0].localCommands.path()).contains(command));answer.set(JOptionPane.OK_OPTION);SwingUtilities.invokeAndWait(()->{pane[0].sendEditor(pane[0].draft);assertEquals("",pane[0].draft.getText());});assertEquals(command,commands.poll(2,TimeUnit.SECONDS));await(pane[0]::canSend,5000);assertEquals(2,prompts.get(),"one cancelled review and one accepted review, no duplicate core prompt");}finally{SwingUtilities.invokeAndWait(()->{timer[0].stop();app[0].close();});assertTrue(app[0].awaitShutdown(System.nanoTime()+TimeUnit.SECONDS.toNanos(15)),"desktop shutdown must finish before temporary home cleanup");}
    }
    private static void setOption(java.awt.Container root,Object answer){for(java.awt.Component child:root.getComponents()){if(child instanceof JOptionPane option){option.setValue(answer);return;}if(child instanceof java.awt.Container nested)setOption(nested,answer);}}
    @Test void localEditingDoesNotWaitForDelayedRemoteAndDisconnectUnblocksReadLine() throws Exception {
        SwingTerminal[] holder={null};SwingUtilities.invokeAndWait(() -> {holder[0]=new SwingTerminal();holder[0].setSize(900,500);});SwingTerminal view=holder[0];
        ExecutorService runner=Executors.newSingleThreadExecutor();
        try(SshConnection connection=SshConnection.connect(options(),prompter());Terminal terminal=view.attach()){
            TerminalSession engine=new TerminalSession(terminal,connection,options());engine.setDisconnectListener(view::endInput);view.accepting(true);
            Future<?> task=runner.submit(() -> {try{engine.run();}catch(Exception e){throw new CompletionException(e);}});
            await(() -> engine.mode()==TerminalSession.Mode.EDIT,5000);long start=System.nanoTime();
            SwingUtilities.invokeAndWait(() -> assertTrue(view.sendKeys("echo helo\u001b[D\u001b[Dl")));
            await(() -> view.screenText().contains("echo hello"),500);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<500);assertTrue(commands.isEmpty(),"unsubmitted local edits must never reach server");
            view.sendKeys("\r");assertEquals("echo hello",commands.poll(2,TimeUnit.SECONDS));await(() -> engine.mode()==TerminalSession.Mode.EDIT,5000);
            view.sendKeys("do-not-replay");await(() -> view.screenText().contains("do-not-replay"),1000);
            for(var session:server.getActiveSessions())session.close(true);
            task.get(5,TimeUnit.SECONDS);assertTrue(commands.isEmpty(),"disconnect must not submit the unfinished line");assertFalse(view.sendKeys("unsafe\r"));
        }finally{runner.shutdownNow();SwingUtilities.invokeAndWait(view::close);}
    }
    @Test void guardRejectsCommandBeforeNetworkWrite() throws Exception {
        SwingTerminal[] holder={null};SwingUtilities.invokeAndWait(() -> {holder[0]=new SwingTerminal();holder[0].setSize(900,500);});SwingTerminal view=holder[0];ExecutorService runner=Executors.newSingleThreadExecutor();
        try(SshConnection connection=SshConnection.connect(options(),prompter());Terminal terminal=view.attach()){
            TerminalSession engine=new TerminalSession(terminal,connection,options());engine.setDisconnectListener(view::endInput);AtomicInteger blocked=new AtomicInteger();engine.setCommandGuard(line -> {blocked.incrementAndGet();return false;});view.accepting(true);
            Future<?> task=runner.submit(() -> {try{engine.run();}catch(Exception e){throw new CompletionException(e);}});await(() -> engine.mode()==TerminalSession.Mode.EDIT,5000);view.sendKeys("rm -rf /tmp/example\r");await(() -> blocked.get()==1,3000);assertTrue(commands.isEmpty());connection.close();task.get(5,TimeUnit.SECONDS);
        }finally{runner.shutdownNow();SwingUtilities.invokeAndWait(view::close);}
    }
    @Test void reconnectKeepsDraftAndDoesNotReplayAnything() throws Exception {
        Files.createDirectories(dir.resolve(".lagless"));Files.writeString(dir.resolve(".lagless/config"),"completion = false\nremote-history = false\nquiet-ms = 30\n");
        DesktopApp[] app={null};SessionPane[] pane={null};
        SwingUtilities.invokeAndWait(() -> {try{
            app[0]=new DesktopApp(dir.resolve(".lagless"));Profile profile=new Profile("test","loopback","127.0.0.1","tester",server.getPort(),"","",true,false);
            app[0].accounts.addElement(profile);pane[0]=new SessionPane(app[0],profile,prompter());pane[0].view.setSize(900,500);app[0].sessions.add(pane[0]);pane[0].draft.setText("echo never-auto-send");pane[0].start();
        }catch(Exception e){throw new RuntimeException(e);}});
        try {
            await(pane[0]::canSend,5000);SshConnection first=pane[0].requireConnection();
            for(var session:server.getActiveSessions())session.close(true);
            await(() -> {try{return pane[0].requireConnection()!=first && pane[0].canSend();}catch(Exception e){return false;}},8000);
            assertTrue(commands.isEmpty());SwingUtilities.invokeAndWait(() -> assertEquals("echo never-auto-send",pane[0].draft.getText()));
            String events=Files.readString(app[0].events.path());assertTrue(events.contains("SSH 已连接"));assertTrue(events.contains("重连"));
            try(var logs=Files.list(dir.resolve(".lagless/logs"))){for(Path file:logs.toList())assertFalse(Files.readString(file).contains("[lagless] connection opened"));}
        } finally {
            SwingUtilities.invokeAndWait(() -> {pane[0].close();app[0].close();});
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            pane[0].awaitClosed(deadline);
            assertTrue(app[0].workers.awaitTermination(15,TimeUnit.SECONDS),"background SSH close must finish before the temporary home is removed");
            assertTrue(app[0].awaitShutdown(System.nanoTime()+TimeUnit.SECONDS.toNanos(15)),"desktop shutdown must finish before temporary home cleanup");
        }
    }
    private static void await(java.util.function.BooleanSupplier condition,long ms)throws Exception{long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(ms);while(!condition.getAsBoolean()){if(System.nanoTime()>end)fail("condition timed out");Thread.sleep(10);}}
    private final class DelayedShell implements Command,Runnable {
        InputStream input;OutputStream output;ExitCallback exit;Thread thread;
        public void setInputStream(InputStream in){input=in;}public void setOutputStream(OutputStream out){output=out;}public void setErrorStream(OutputStream out){}public void setExitCallback(ExitCallback callback){exit=callback;}
        public void start(ChannelSession channel,Environment environment){thread=new Thread(this,"test-delayed-shell");thread.setDaemon(true);thread.start();}
        public void destroy(ChannelSession channel)throws Exception{if(input!=null)input.close();if(thread!=null)thread.interrupt();}
        public void run(){try{write("test$ ");StringBuilder line=new StringBuilder();int b;while((b=input.read())!=-1){if(b==21){line.setLength(0);continue;}if(b==3){line.setLength(0);write("^C\r\ntest$ ");continue;}if(b==4)break;if(b=='\r'){String command=line.toString();line.setLength(0);commands.add(command);Thread.sleep(600);write(command+"\r\n[executed] "+command+"\r\ntest$ ");}else line.append((char)b);}}catch(Exception ignored){}finally{exit.onExit(0);}}
        void write(String text)throws IOException{output.write(text.getBytes(StandardCharsets.UTF_8));output.flush();}
    }
}
