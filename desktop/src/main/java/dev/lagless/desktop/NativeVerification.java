package dev.lagless.desktop;

import dev.lagless.*;
import org.jline.terminal.Terminal;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Opt-in release verification against an isolated loopback fixture; never reads real accounts. */
public final class NativeVerification {
    private NativeVerification(){ }
    public static void run(Path scenario)throws Exception {
        Object config=Json.parse(Files.readString(scenario));
        Path work=Path.of(Objects.requireNonNull(Json.string(config,"work"))).toAbsolutePath();Files.createDirectories(work);
        System.setProperty("user.home",work.toString());
        Path home=work.resolve(".lagless");Files.createDirectories(home);
        Files.writeString(home.resolve("config"),"completion = false\nremote-history = false\nquiet-ms = 30\n");
        Map<String,Object> report=new LinkedHashMap<>();report.put("native",DesktopRuntime.nativeImage());
        render(home,work);report.put("ui",true);
        firstRunLanguage();report.put("first-run-language",true);
        themeSwitch();report.put("theme-switch",true);
        windowEvents(home);report.put("native-window-events",true);
        verifyPdfAttachment(work);report.put("pdf-attachment-text",true);
        report.put("dialog-actions",NativeDialogVerification.run(work));
        NativeInputVerification.run(home,report);
        Profile profile=new Profile("fixture","Loopback · 本地测试","127.0.0.1","tester",(int)Json.number(config,0,"ssh-port"),"","",true,false);
        for(String type:List.of("json","reg","xsh","config")){
            Path file=work.resolve("accounts."+type);AccountFormats.write(file,List.of(profile),type.equals("config")?"openssh":type);
            require(AccountFormats.read(file).size()==1,"account roundtrip "+type);
        }
        report.put("account-formats",true);
        try(SessionJournal journal=new SessionJournal(home,"native")){journal.append("日志 · output\n");journal.export(work.resolve("export.log"));}
        char[] password="native-test-password".toCharArray();Path archive=work.resolve("backup.llbackup");
        BackupArchive.create(work,archive,password,null);Path recovered=work.resolve("restored");BackupArchive.restore(archive,recovered,password,null);
        require(Files.exists(recovered.resolve(".lagless/config")),"encrypted restore");Arrays.fill(password,'\0');report.put("encrypted-backup",true);
        List<String> command;
        if(DesktopRuntime.nativeImage())command=List.of(ProcessHandle.current().info().command().orElseThrow(),"--plugin-example");
        else command=List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").contains("Windows")?"java.exe":"java").toString(),"-cp",System.getProperty("java.class.path"),NativeDesktopMain.class.getName(),"--plugin-example");
        Path descriptor=work.resolve("example.plugin.json");Files.writeString(descriptor,Json.stringify(Map.of("version",1,"name","Native example","command",command)));
        try(ProcessPlugin plugin=new ProcessPlugin(descriptor)){plugin.onOutput("test","output");require(plugin.assist("command","git status","").contains("git status"),"process plugin RPC");}
        report.put("process-plugin",true);
        Files.createDirectories(home.resolve("desktop"));Files.writeString(home.resolve("desktop/assistant.properties"),"endpoint="+Json.string(config,"http")+"/ai\nmodel=fixture\n");
        require(new AssistantPlugin(home).assist("translate","hello","").equals("你好"),"AI translation HTTP");report.put("ai-http",true);
        ssh(config,profile,home,report);
        // A native bundle backup contains only managed application files, never a JRE or JAR.
        if(DesktopRuntime.nativeImage()&&Files.exists(DesktopRuntime.home().resolve(NativeBundle.MANIFEST))){
            Path bundle=work.resolve("application.zip");NativeBundle.pack(DesktopRuntime.home(),bundle);NativeBundle.extract(bundle,work.resolve("application-copy"),true);report.put("native-bundle",true);
            BackupArchive.create(work,work.resolve("full.llbackup"),"native-test-password".toCharArray(),DesktopRuntime.home());
            BackupArchive.restore(work.resolve("full.llbackup"),work.resolve("full-restored"),"native-test-password".toCharArray(),null);report.put("native-backup",true);
        }
        Files.writeString(work.resolve("report.json"),Json.stringify(report));System.out.println(Json.stringify(report));
    }
    private static void ssh(Object config,Profile profile,Path home,Map<String,Object> report)throws Exception{
        Options options=new Options();options.host="127.0.0.1";options.port=profile.port();options.user="tester";options.completion=false;options.remoteHistory=false;options.quietMs=30;options.promptRegex="test\\$ $";
        SshConnection.Prompter prompt=new SshConnection.Prompter(){public String askSecret(String q){return "test-password";}public boolean askYesNo(String q){return true;}public void show(String m){}};
        try(SshConnection jump=SshConnection.connect(options,prompt);SshConnection target=SshConnection.connectVia(options,prompt,jump)){
            var sftp=target.openSftp();try{sftp.put(new ByteArrayInputStream(new byte[128*1024]),"/queue-progress.bin");sftp.put(new ByteArrayInputStream("native-sftp".getBytes(StandardCharsets.UTF_8)),"/native.txt");try(InputStream in=sftp.get("/native.txt")){require(new String(in.readAllBytes(),StandardCharsets.UTF_8).equals("native-sftp"),"SFTP");}sftp.rm("/native.txt");
                Path source=home.resolve("native-upload"),dest=home.resolve("native-download");Files.createDirectories(source.resolve("子目录/empty"));Files.writeString(source.resolve("子目录/test.txt"),"原生文件夹传输");
                SftpTransfers transfer=new SftpTransfers(sftp,0,new java.util.concurrent.atomic.AtomicBoolean(),n->{});transfer.upload(source,"/native-tree");transfer.download("/native-tree",dest);require(Files.readString(dest.resolve("子目录/test.txt")).equals("原生文件夹传输")&&Files.isDirectory(dest.resolve("子目录/empty")),"recursive directory transfer");report.put("sftp-directories",true);}finally{sftp.disconnect();}
        }report.put("ssh-jump-sftp",true);
        options.identity=Json.string(config,"identity");options.user="key-tester";
        try(SshConnection key=SshConnection.connect(options,prompt)){require(key.isConnected(),"encrypted private key authentication");}
        options.identity=null;options.user="tester";report.put("encrypted-key-auth",true);
        SwingTerminal[] holder={null};SwingUtilities.invokeAndWait(()->{holder[0]=new SwingTerminal();holder[0].setSize(900,500);});SwingTerminal view=holder[0];ExecutorService runner=Executors.newSingleThreadExecutor();
        try(SshConnection connection=SshConnection.connect(options,prompt);Terminal terminal=view.attach()){
            TerminalSession engine=new TerminalSession(terminal,connection,options);engine.setDisconnectListener(view::endInput);view.accepting(true);
            Future<?> task=runner.submit(()->{try{engine.run();}catch(Exception e){throw new CompletionException(e);}});
            await(()->engine.mode()==TerminalSession.Mode.EDIT,10000);
            var clipboard=new java.awt.datatransfer.Clipboard("native terminal paste");clipboard.setContents(new java.awt.datatransfer.StringSelection("echo pasted\r\n"),null);
            SwingUtilities.invokeAndWait(()->{view.setClipboard(()->clipboard);((JMenuItem)view.popupMenu().getComponent(1)).doClick();});
            await(()->view.screenText().contains("echo pasted"),1000);require(get(config,"/commands").isBlank(),"popup paste does not execute");require(view.sendKeys("\u0015"),"clear pasted input accepted");report.put("terminal-paste",true);
            long start=System.nanoTime();
            SwingUtilities.invokeAndWait(()->require(view.sendKeys("echo helo\u001b[D\u001b[Dl"),"local input accepted"));
            await(()->view.screenText().contains("echo hello"),500);report.put("local-edit-ms",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
            require(get(config,"/commands").isBlank(),"local edit did not touch network");require(view.sendKeys("\r"),"terminal Enter accepted");
            try{await(()->view.screenText().contains("[executed] echo hello"),15000);}
            catch(IOException timeout){String screen=view.screenText().replace('\n','|');if(screen.length()>1000)screen=screen.substring(screen.length()-1000);String remote;try{remote=get(config,"/commands");}catch(Exception error){remote="unavailable: "+error;}throw new IOException("SSH output timeout; mode="+engine.mode()+", inputTaskDone="+task.isDone()+", remoteCommands="+remote+", screen="+screen,timeout);}
            await(()->engine.mode()==TerminalSession.Mode.EDIT,5000);
            engine.setCommandGuard(line->false);view.sendKeys("rm -rf /tmp/example\r");Thread.sleep(200);require(!get(config,"/commands").contains("rm -rf"),"danger guard");report.put("danger-guard",true);
            require(engine.setPassThrough(true),"pass-through wake required");view.sendKeys("\u001d");await(()->engine.mode()==TerminalSession.Mode.RAW,1000);
            SwingUtilities.invokeAndWait(()->NativeInputVerification.remoteKeys(view));view.sendKeys("\r");
            await(()->{try{return get(config,"/commands").contains("\u0018\u0013\u001bv");}catch(Exception e){return false;}},5000);
            engine.setPassThrough(false);view.sendKeys("\u0003");await(()->engine.mode()==TerminalSession.Mode.EDIT,5000);report.put("ctrl-chords-alt-passthrough",true);
            view.sendKeys("never-replay");get(config,"/drop");task.get(5,TimeUnit.SECONDS);
            require(!get(config,"/commands").contains("never-replay"),"disconnect did not replay draft");
        }finally{runner.shutdownNow();SwingUtilities.invokeAndWait(view::close);}
        report.put("delayed-ssh-local-edit",true);
        DesktopApp[] app={null};SessionPane[] pane={null};SwingUtilities.invokeAndWait(()->{try{app[0]=new DesktopApp(home);app[0].accounts.addElement(profile);pane[0]=new SessionPane(app[0],profile,prompt);app[0].sessions.add(pane[0]);pane[0].view.setSize(900,500);pane[0].draft.setText("draft survives");pane[0].start();}catch(Exception e){throw new RuntimeException(e);}});
        try{await(pane[0]::canSend,10000);
            TransferQueue queue=pane[0].transferQueue();TransferQueue.Job download=queue.enqueue(false,home.resolve("queued-download.bin"),"/queue-progress.bin",32768);
            TransferQueue.Job next=queue.enqueue(false,home.resolve("queued-next.bin"),"/queue-progress.bin",0);
            SwingUtilities.invokeAndWait(()->{app[0].newWindow();JFrame frame=app[0].windows.getFirst();Workspace workspace=app[0].workspaces.get(frame);workspace.tabs.addTab("Loopback",pane[0]);workspace.tabs.setSelectedComponent(pane[0]);pane[0].setEditorPlacement("right");require(pane[0].tools.isVisible(),"right editor");pane[0].setEditorPlacement("bottom");require(pane[0].inlineDraft.getDocument()==pane[0].draft.getDocument(),"shared editor draft");pane[0].showTool("mirror");require(pane[0].mirror.getLineWrap(),"mirror wrap");
                JTabbedPane tabs=pane[0].tools;Component mirror=tabs.getSelectedComponent();pane[0].mirror.setText("retained mirror");Container header=(Container)tabs.getTabComponentAt(tabs.getSelectedIndex());for(Component child:header.getComponents())if(child instanceof JButton close)close.doClick();require(tabs.indexOfComponent(mirror)<0&&!tabs.isVisible(),"view close button collapses empty dock");pane[0].showTool("mirror");require(pane[0].mirror.getText().equals("retained mirror"),"closed view retains contents");pane[0].hideTools();require(!tabs.isVisible()&&pane[0].canSend(),"hiding views keeps SSH active");pane[0].showFiles();});report.put("closable-views",true);
            await(()->download.state==TransferQueue.State.RUNNING&&download.bytes>0,5000);require(download.percent()>0&&download.percent()<100,"download progress");require(next.state==TransferQueue.State.WAITING,"serial download queue");
            Thread.sleep(300);
            SwingUtilities.invokeAndWait(()->{app[0].setEditorWrap(true);require(pane[0].inlineDraft.getLineWrap()&&pane[0].draft.getLineWrap(),"editor wrapping synchronized");app[0].setEditorWrap(false);
                for(Window window:Window.getWindows())if(window instanceof JDialog dialog&&dialog.getTitle().contains("文件管理")){
                    JTable table=findQueue(dialog);require(table!=null&&table.getRowCount()==2,"visible download queue");Component progress=table.prepareRenderer(table.getCellRenderer(0,3),0,3);require(progress instanceof JProgressBar,"progress renderer");
                    BufferedImage image=new BufferedImage(dialog.getWidth(),dialog.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D graphics=image.createGraphics();dialog.printAll(graphics);graphics.dispose();try{javax.imageio.ImageIO.write(image,"png",home.getParent().resolve("transfer-queue.png").toFile());}catch(IOException e){throw new UncheckedIOException(e);}
                }
            });report.put("editor-wrap",true);
            long transferEditStart=System.nanoTime();SwingUtilities.invokeAndWait(()->pane[0].view.sendKeys("echo concurrent-transfer"));await(()->pane[0].view.screenText().contains("echo concurrent-transfer"),500);require(!get(config,"/commands").contains("echo concurrent-transfer"),"typing during transfer remains local");report.put("transfer-local-edit-ms",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-transferEditStart));pane[0].view.sendKeys("\u0015");
            String commandsBeforeDrag=get(config,"/commands");
            SwingUtilities.invokeAndWait(()->{BottomInputPane input=pane[0].inputArea;require(input.editorHeight()==44,"default one-line editor height");require(pane[0].inlineScroll.getVerticalScrollBarPolicy()==ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER&&pane[0].inlineScroll.getHorizontalScrollBarPolicy()==ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,"single-line editor has no scrollbars");NativeInputVerification.drag(input,85);require(input.editorHeight()>44&&input.editorHeight()<=129,"corner handle increases height vertically within the screen bounds");require(input.resizeHandle.isShowing(),"resize handle remains visible");NativeInputVerification.drag(input,-1000);require(input.editorHeight()==44,"minimum height remains one line");});
            Thread.sleep(250);require(get(config,"/commands").equals(commandsBeforeDrag),"drag never enters a command or newline");report.put("resizable-local-editor",true);report.put("corner-drag-no-input",true);
            SwingUtilities.invokeAndWait(()->{for(Window window:Window.getWindows())if(window instanceof JDialog dialog&&dialog.getTitle().contains("文件管理"))dialog.dispose();pane[0].inlineDraft.setText("echo native-editor\necho second-editor");pane[0].inlineDraft.setCaretPosition(0);});
            require(!get(config,"/commands").contains("native-editor"),"pasted multiline remains local");
            SwingUtilities.invokeAndWait(()->{javax.swing.Timer choice=new javax.swing.Timer(20,e->{for(Window window:Window.getWindows())if(window instanceof JDialog dialog&&dialog.isShowing()&&dialog.getTitle().equals("发送前检查换行"))chooseMergedLine(dialog);});choice.start();try{JTextArea editor=pane[0].inlineDraft;Object binding=editor.getInputMap().get(KeyStroke.getKeyStroke("ENTER"));editor.getActionMap().get(binding).actionPerformed(new java.awt.event.ActionEvent(editor,0,""));require(editor.getText().isEmpty(),"entire editor clears immediately after submission");}finally{choice.stop();}});
            await(()->{try{return get(config,"/commands").contains("echo native-editor");}catch(Exception e){return false;}},5000);await(pane[0]::canSend,5000);require(get(config,"/commands").contains("echo native-editor echo second-editor"),"all editor text sent with reviewed soft wrapping");
            app[0].localCommands.force();require(Files.readString(app[0].localCommands.path()).contains("echo native-editor"),"local command journal");report.put("local-command-log",true);
            await(next::terminal,10000);require(download.state==TransferQueue.State.COMPLETE&&next.state==TransferQueue.State.COMPLETE,"queue survives file dialog close");require(Files.size(download.local)==128*1024,"queued file size");report.put("download-queue-progress",true);
            SwingUtilities.invokeAndWait(()->pane[0].draft.setText("draft survives"));report.put("local-editor-layouts",true);report.put("file-browser-window",true);
            SshConnection first=pane[0].requireConnection();get(config,"/drop");await(()->{try{return pane[0].requireConnection()!=first&&pane[0].canSend();}catch(Exception e){return false;}},15000);require(pane[0].draft.getText().equals("draft survives"),"reconnect retained draft");report.put("automatic-reconnect",true);require(Files.readString(app[0].events.path()).contains("重连"),"software connection events");require(!LogViewer.readPage(app[0].events.path(),-1).text().isBlank(),"paged log viewer");report.put("software-events",true);}
        finally{SwingUtilities.invokeAndWait(()->{pane[0].close();app[0].windows.forEach(Window::dispose);app[0].close();});require(app[0].awaitShutdown(System.nanoTime()+TimeUnit.SECONDS.toNanos(15)),"SSH verification desktop shutdown");}
    }
    private static String get(Object config,String path)throws Exception{
        URLConnection connection=URI.create(Json.string(config,"http")+path).toURL().openConnection();connection.setConnectTimeout(3000);connection.setReadTimeout(3000);try(InputStream in=connection.getInputStream()){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}
    }
    private static void render(Path home,Path work)throws Exception{
        DesktopApp[] appHolder={null};
        SwingUtilities.invokeAndWait(()->{DesktopApp app=null;SessionPane pane=null;JFrame frame=null;try{
            DesktopApp.theme();app=new DesktopApp(home);appHolder[0]=app;Profile p=new Profile("demo","Production · Tokyo","10.24.0.12","deploy",22,"","",true,false);app.accounts.addElement(p);
            JPanel content=app.content(null);pane=new SessionPane(app,p);JTabbedPane tabs=findTabs(content);tabs.addTab(p.name(),pane);tabs.setSelectedComponent(pane);pane.draft.setText("# 本地编辑 · 网络延迟不影响输入\ngit status\n");pane.view.display("\u001b[32mLagless native desktop\u001b[0m\r\nConnected · 本地编辑已就绪\r\ndeploy@tokyo:~$ ");
            frame=new JFrame("Lagless native verification");frame.setContentPane(content);frame.setSize(1440,900);frame.pack();frame.setFocusableWindowState(false);frame.setLocation(-10000,-10000);frame.setVisible(true);content.setSize(1440,900);layout(content);layout(content);layout(content);
            BufferedImage image=new BufferedImage(1440,900,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();content.printAll(g);g.dispose();javax.imageio.ImageIO.write(image,"png",work.resolve("desktop.png").toFile());
            new JFileChooser(work.toFile()).getPreferredSize();new JOptionPane("测试",JOptionPane.QUESTION_MESSAGE,JOptionPane.OK_CANCEL_OPTION).getPreferredSize();new JPasswordField("password").getPreferredSize();new JSpinner(new SpinnerNumberModel(22,1,65535,1)).getPreferredSize();new JProgressBar().getPreferredSize();new JComboBox<>(new String[]{"SSH","SFTP"}).getPreferredSize();new JTable(new Object[][]{{"file","1 KiB"}},new String[]{"Name","Size"}).getPreferredSize();
        }catch(Exception e){throw new RuntimeException(e);}finally{if(frame!=null)frame.dispose();if(pane!=null)pane.close();if(app!=null){app.close();}}});
        require(appHolder[0].awaitShutdown(System.nanoTime()+TimeUnit.SECONDS.toNanos(15)),"render verification desktop shutdown");
    }
    /** Switch to the dark theme and back with a window open, as View ▸ Theme does. */
    private static void themeSwitch()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            JFrame frame=new JFrame("theme");try{
                JButton primary=DesktopApp.primary(new JButton("ok"));frame.add(primary);frame.pack();frame.setVisible(true);
                Theme.apply(Theme.Mode.DARK);
                require(UIManager.getLookAndFeel() instanceof com.formdev.flatlaf.FlatDarkLaf,"dark look and feel");
                require(primary.getBackground().getRGB()==Theme.ACCENT.getRGB(),"primary button follows the dark accent");
                Theme.apply(Theme.Mode.LIGHT);
                require(UIManager.getLookAndFeel() instanceof com.formdev.flatlaf.FlatLightLaf,"light look and feel");
            }finally{frame.dispose();}
        });
    }
    /** Exercise the modal screen shown before a new user's first workspace. */
    private static void firstRunLanguage()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            java.util.concurrent.atomic.AtomicBoolean dismissed=new java.util.concurrent.atomic.AtomicBoolean();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            javax.swing.Timer dismiss=new javax.swing.Timer(100,null);
            dismiss.addActionListener(event->{
                for(Window window:Window.getWindows()){
                    if(!(window instanceof JDialog dialog)||!dialog.isShowing()||!dialog.getTitle().startsWith("Lagless · Language"))continue;
                    JButton button=findButton(dialog,"language-continue");
                    if(button!=null){dismissed.set(true);dismiss.stop();button.doClick();return;}
                    if(System.nanoTime()>deadline){dismiss.stop();dialog.dispose();return;}
                }
            });
            dismiss.start();
            try{LanguageDialog.ask(null,dev.lagless.I18n.Language.system());require(dismissed.get(),"first-run language dialog dismissed");}
            finally{dismiss.stop();}
        });
    }
    private static JButton findButton(Container root,String name){
        for(Component child:root.getComponents()){
            if(child instanceof JButton button&&name.equals(button.getName()))return button;
            if(child instanceof Container nested){JButton result=findButton(nested,name);if(result!=null)return result;}
        }
        return null;
    }
    private static void chooseMergedLine(Container root){for(Component child:root.getComponents()){if(child instanceof JOptionPane option){option.setValue("合并为一行（软换行）");return;}if(child instanceof Container nested)chooseMergedLine(nested);}}
    private static JTable findQueue(Container container){for(Component child:container.getComponents()){if(child instanceof JTable table&&"transfer-queue".equals(table.getName()))return table;if(child instanceof Container nested){JTable table=findQueue(nested);if(table!=null)return table;}}return null;}
    private static JTabbedPane findTabs(Container c){for(Component child:c.getComponents()){if(child instanceof JTabbedPane t)return t;if(child instanceof Container n){JTabbedPane t=findTabs(n);if(t!=null)return t;}}return null;}
    /** A real on-screen peer must pump native messages, not just render offscreen. */
    private static void windowEvents(Path home)throws Exception{
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{failure.compareAndSet(null,error);error.printStackTrace(System.err);});
        DesktopApp[] app={null};JFrame[] frame={null};
        try{
            SwingUtilities.invokeAndWait(()->{try{app[0]=new DesktopApp(home);app[0].newWindow();frame[0]=app[0].windows.getFirst();frame[0].setLocationRelativeTo(null);frame[0].toFront();}catch(Exception e){throw new RuntimeException(e);}});
            Thread.sleep(750);
            SwingUtilities.invokeAndWait(()->{frame[0].setLocation(frame[0].getX()+12,frame[0].getY()+12);frame[0].setSize(frame[0].getWidth()-12,frame[0].getHeight()-12);frame[0].repaint();});
            Thread.sleep(750);
            if(failure.get()!=null)throw new IOException("Native window event thread failed",failure.get());
            SwingUtilities.invokeAndWait(()->frame[0].dispatchEvent(new java.awt.event.WindowEvent(frame[0],java.awt.event.WindowEvent.WINDOW_CLOSING)));
            require(!frame[0].isDisplayable(),"window close handler disposed native peer");
            if(failure.get()!=null)throw new IOException("Native window close failed",failure.get());
        }finally{
            try{SwingUtilities.invokeAndWait(()->{if(frame[0]!=null)frame[0].dispose();if(app[0]!=null){app[0].close();}});
                if(app[0]!=null)require(app[0].awaitShutdown(System.nanoTime()+TimeUnit.SECONDS.toNanos(15)),"window event verification desktop shutdown");}
            finally{Thread.setDefaultUncaughtExceptionHandler(previous);}
        }
    }
    private static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container n)layout(n);}
    private static void await(BooleanSupplier test,long millis)throws Exception{long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);while(!test.getAsBoolean()){if(System.nanoTime()>until)throw new IOException("Verification timed out");Thread.sleep(10);}}
    private static void require(boolean pass,String message){if(!pass)throw new IllegalStateException("Verification failed: "+message);}
    private static void verifyPdfAttachment(Path work)throws Exception{
        Path pdf=work.resolve("assistant-searchable-test.pdf");
        try(org.apache.pdfbox.pdmodel.PDDocument document=new org.apache.pdfbox.pdmodel.PDDocument()){
            org.apache.pdfbox.pdmodel.PDPage page=new org.apache.pdfbox.pdmodel.PDPage();document.addPage(page);
            try(org.apache.pdfbox.pdmodel.PDPageContentStream content=new org.apache.pdfbox.pdmodel.PDPageContentStream(document,page)){
                content.beginText();content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);
                content.newLineAtOffset(72,700);content.showText("Native PDF attachment extraction");content.endText();
            }
            document.save(pdf.toFile());
        }
        AssistantPlugin.Attachment attachment=AssistantAttachments.read(pdf);
        require("application/pdf".equals(attachment.mime())&&attachment.text().contains("Native PDF attachment extraction")&&attachment.bytes()==null,"searchable PDF is extracted before AI upload");
    }
}
