package dev.lagless;

import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Executed with java.desktop completely excluded by core/pom.xml. */
@Timeout(20)
class CoreHeadlessRegressionTest {
    @TempDir Path home;

    @Test void realSshLocalEditingAndCommandGuardWorkWithoutDesktop() throws Exception {
        assertFalse(ModuleLayer.boot().findModule("java.desktop").isPresent());
        assertThrows(ClassNotFoundException.class, () -> Class.forName("javax.swing.JFrame"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("dev.lagless.desktop.DesktopApp"));
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        ExecutorService runner = Executors.newSingleThreadExecutor();
        BlockingQueue<String> commands = new LinkedBlockingQueue<>();
        AtomicReference<DelayedShell> activeShell = new AtomicReference<>();
        BlockingQueue<String> audits = new LinkedBlockingQueue<>();
        SshServer server = SshServer.setUpDefaultServer();
        try {
            server.setHost("127.0.0.1"); server.setPort(0);
            var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
            server.setKeyPairProvider(KeyPairProvider.wrap(generator.generateKeyPair()));
            server.setPasswordAuthenticator((user, password, session) -> password.equals("test"));
            server.setShellFactory(channel -> {DelayedShell shell=new DelayedShell(commands);activeShell.set(shell);return shell;}); server.start();
            Options options = new Options(); options.host = "127.0.0.1"; options.port = server.getPort(); options.user = "tester";
            options.completion = false; options.remoteHistory = false; options.quietMs = 30; options.promptRegex = "core\\$ $";
            SshConnection.Prompter prompter = new SshConnection.Prompter() {
                public String askSecret(String prompt) { return "test"; }
                public boolean askYesNo(String question) { return true; }
                public void show(String message) { }
            };
            ByteArrayOutputStream screen = new ByteArrayOutputStream();
            OutputStream display = new OutputStream() {
                @Override public void write(int b) { synchronized (screen) { screen.write(b); } }
                @Override public void write(byte[] b, int off, int len) { synchronized (screen) { screen.write(b,off,len); } }
            };
            PipedInputStream input = new PipedInputStream(65536);
            try (PipedOutputStream keys = new PipedOutputStream(input);
                 Terminal terminal = TerminalBuilder.builder().system(false).streams(input,display).type("xterm-256color")
                         .encoding(StandardCharsets.UTF_8).size(new Size(100,30)).build();
                 SshConnection connection = SshConnection.connect(options,prompter)) {
                TerminalSession engine = new TerminalSession(terminal,connection,options);
                engine.setInputAudit(audits::add);
                engine.setDisconnectListener(() -> { try { keys.close(); } catch (IOException ignored) { } });
                AtomicInteger blocked = new AtomicInteger();
                engine.setCommandGuard(command -> { if(command.equals("blocked")){blocked.incrementAndGet();return false;}return true; });
                Future<?> task = runner.submit(() -> { try { engine.run(); } catch(Exception e) { throw new CompletionException(e); } });
                await(() -> engine.mode()==TerminalSession.Mode.EDIT,5000);
                await(() -> {synchronized(screen){return promptRows(screen)>0;}},1000);
                int originalPrompts;
                synchronized(screen){originalPrompts=promptRows(screen);}
                activeShell.get().write("\r\u001b[K\rcore$ ");
                await(() -> audits.stream().anyMatch(message -> message.contains("原位重绘当前提示符")),2000);
                synchronized(screen){assertEquals(originalPrompts,promptRows(screen),"PTY 原位重绘不应成为新的历史行");}
                assertTrue(commands.isEmpty(),"PTY 原位重绘不应发送空命令");
                keys.write("echo helo\u001b[D\u001b[Dl".getBytes(StandardCharsets.UTF_8)); keys.flush();
                await(() -> { synchronized(screen) {
                    ScreenModel visible=new ScreenModel(100,30);visible.feed(screen.toString(StandardCharsets.UTF_8));
                    for(int row=0;row<visible.rows();row++)if(visible.rowText(row).contains("echo hello"))return true;
                    return false;
                } },500);
                assertTrue(commands.isEmpty(),"local editing must send no keys to the remote shell");
                keys.write('\r'); keys.flush(); assertEquals("echo hello",commands.poll(2,TimeUnit.SECONDS));
                await(() -> engine.mode()==TerminalSession.Mode.EDIT,5000);
                keys.write("blocked\r".getBytes(StandardCharsets.UTF_8));keys.flush();await(() -> blocked.get()==1,3000);
                assertTrue(commands.isEmpty(),"rejected commands must stay local");
                await(() -> engine.mode()==TerminalSession.Mode.EDIT,2000);
                await(() -> {synchronized(screen){return promptRows(screen)>0;}},1000);
                int beforeModeChange; synchronized(screen){beforeModeChange=promptRows(screen);}
                assertTrue(engine.setPassThrough(true));keys.write(0x1d);keys.flush();
                await(() -> engine.mode()==TerminalSession.Mode.RAW,2000);
                synchronized(screen){assertEquals(beforeModeChange,promptRows(screen),"切换键盘透传不应打印空白历史行");}
                assertTrue(commands.isEmpty(),"切换键盘透传不应发送空命令");
                connection.close();task.get(5,TimeUnit.SECONDS);
            }
        } finally { try { server.stop(true); } finally { runner.shutdownNow();System.setProperty("user.home",previousHome); } }
    }

    private static void await(BooleanSupplier ready,long millis)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        while(!ready.getAsBoolean()){if(System.nanoTime()>deadline)fail("condition timed out");Thread.sleep(10);}
    }

    private static int promptRows(ByteArrayOutputStream screen){
        ScreenModel visible=new ScreenModel(100,30);visible.feed(screen.toString(StandardCharsets.UTF_8));
        int count=0;for(int row=0;row<visible.rows();row++)if(visible.rowText(row).contains("core$"))count++;
        return count;
    }

    private static final class DelayedShell implements Command,Runnable {
        private final BlockingQueue<String> commands;
        private InputStream in; private OutputStream out; private ExitCallback callback; private Thread thread;
        DelayedShell(BlockingQueue<String> commands){this.commands=commands;}
        public void setInputStream(InputStream in){this.in=in;}
        public void setOutputStream(OutputStream out){this.out=out;}
        public void setErrorStream(OutputStream err){ }
        public void setExitCallback(ExitCallback callback){this.callback=callback;}
        public void start(ChannelSession channel,Environment environment){thread=new Thread(this,"headless-test-shell");thread.setDaemon(true);thread.start();}
        public void destroy(ChannelSession channel)throws Exception{if(in!=null)in.close();if(thread!=null)thread.interrupt();}
        public void run(){
            try {
                write("core$ ");StringBuilder line=new StringBuilder();int c;
                while((c=in.read())!=-1){if(c==21){line.setLength(0);continue;}if(c=='\r'){String command=line.toString();line.setLength(0);commands.add(command);Thread.sleep(600);write(command+"\r\nresult\r\ncore$ ");}else line.append((char)c);}
            }catch(Exception ignored){ }finally{callback.onExit(0);}
        }
        private void write(String text)throws IOException{out.write(text.getBytes(StandardCharsets.UTF_8));out.flush();}
    }
}
