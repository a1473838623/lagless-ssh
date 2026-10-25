package dev.lagless.desktop;

import dev.lagless.*;
import org.jline.terminal.Terminal;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** One independent connection; no global terminal, history, shell or reconnect buffer. */
public final class SessionPane extends JPanel implements AutoCloseable {
    final DesktopApp app;
    final Profile profile;
    final String id = UUID.randomUUID().toString();
    final SwingTerminal view = new SwingTerminal();
    final JTextArea draft = DesktopApp.textArea(), mirror = DesktopApp.textArea(), assistant = DesktopApp.textArea();
    private JTextArea commandEditor=draft;
    final JTextArea inlineDraft = DesktopApp.textArea();
    final JTabbedPane tools = new JTabbedPane();
    private final JPanel editorPanel = new JPanel(new BorderLayout(0,6)), inlineBar = new JPanel(new BorderLayout(8,0));
    private final JPanel mirrorPanel=new JPanel(new BorderLayout()),assistantPanel=new JPanel(new BorderLayout()),toolDock=new JPanel(new BorderLayout());
    private AssistantChatPanel assistantChat;
    private final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    final BottomInputPane inputArea;
    final JScrollPane inlineScroll=new EditorScrollPane(inlineDraft);
    private record Approval(TerminalSession session,String command){}
    private final ConcurrentLinkedQueue<Approval> approvals=new ConcurrentLinkedQueue<>();
    private final List<JTextArea> mirrorViews = new ArrayList<>();
    private final List<JTextArea> commandViews = new ArrayList<>();
    private final JCheckBoxMenuItem editorWrap=new JCheckBoxMenuItem(I18n.t("自动换行","Wrap lines"));
    private TransferQueue transfers;
    private final JLabel status = new JLabel(I18n.t("准备连接…","Preparing connection…")), message = new JLabel(" ");
    private final JCheckBoxMenuItem interactiveSend = new JCheckBoxMenuItem(I18n.t("交互程序发送（逐次确认）","Send to interactive programs (confirm each time)"),true);
    private final SessionJournal journal;
    private final SessionTranscript transcript;
    private final Path draftFile;
    private final javax.swing.Timer uiTimer;
    private final StringBuilder mirrorPending = new StringBuilder();
    private final AtomicReference<String> lastNotice = new AtomicReference<>();
    private final List<JDialog> detached = new ArrayList<>();
    private final Set<String> contextSessions = new HashSet<>();
    private final SshConnection.Prompter suppliedPrompter;
    private final ExecutorService connectionWorker = Executors.newSingleThreadExecutor(r -> new Thread(r,"lagless-session"));
    private final ExecutorService draftWriter = Executors.newSingleThreadExecutor(r -> new Thread(r,"lagless-draft-writer"));
    private volatile SshConnection connection;
    private volatile Terminal terminal;
    private volatile TerminalSession engine;
    private volatile boolean stopped, connecting, logFailed;
    private volatile boolean passThrough;
    private boolean draftDirty;
    private boolean disposed;
    private int draftTicks;
    private long omittedMirror;
    private final StringBuilder escape = new StringBuilder();
    public SessionPane(DesktopApp app, Profile profile) throws Exception {
        this(app,profile,null);
    }
    SessionPane(DesktopApp app, Profile profile, SshConnection.Prompter suppliedPrompter) throws Exception {
        super(new BorderLayout(0,8)); this.app=app;this.profile=profile;
        tools.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);tools.addChangeListener(e->Workspace.updateTabHeaders(tools));
        this.suppliedPrompter=suppliedPrompter;
        journal=new SessionJournal(app.home,profile.name());view.setHistory(new TerminalHistory(app.home,id));draftFile=app.home.resolve("desktop/drafts/"+id+".txt");
        view.setInputAudit(this::auditTerminalInput);
        transcript=new SessionTranscript(journal,view::localEditing);
        setRecordFullscreen(Boolean.parseBoolean(app.preferences.get("record-fullscreen","true")));
        view.setAutoRemoteWheel(Boolean.parseBoolean(app.preferences.get("wheel-auto-interactive","true")));
        view.setPageKeysForRemoteWheel(Boolean.parseBoolean(app.preferences.get("wheel-page-keys","false")));
        setBorder(new EmptyBorder(8,8,8,8));
        JPanel top=new JPanel(new BorderLayout());status.setForeground(DesktopApp.ACCENT);top.add(status,BorderLayout.WEST);
        JLabel target=new JLabel(profile.user()+"@"+profile.host());target.setForeground(DesktopApp.MUTED);top.add(target,BorderLayout.EAST);add(top,BorderLayout.NORTH);
        JMenuBar editBar=new JMenuBar();JMenu editActions=new JMenu(I18n.t("本地编辑 · 操作","Local editor · Actions"));editBar.add(editActions);
        Workspace.add(editActions,I18n.t("独立窗口","Separate window"),()->detach(I18n.t("本地命令编辑","Local command editor"),draft,true),null);Workspace.add(editActions,I18n.t("保存草稿","Save draft"),this::saveDraftAs,null);Workspace.add(editActions,I18n.t("翻译命令","Translate command"),()->assist("translate-command"),null);Workspace.add(editActions,I18n.t("AI 补全","AI completion"),()->assist("complete"),null);
        editorWrap.setSelected(Boolean.parseBoolean(app.preferences.get("editor-wrap","false")));editorWrap.addActionListener(e->app.setEditorWrap(editorWrap.isSelected()));editActions.add(editorWrap);
        editActions.add(interactiveSend);
        JPanel editorHeader=new JPanel(new BorderLayout());editorHeader.add(editBar,BorderLayout.NORTH);JLabel hint=new JLabel(I18n.t("  Enter 发送全部 · Shift+Enter 换行","  Enter sends all · Shift+Enter new line"));hint.setForeground(DesktopApp.MUTED);editorHeader.add(hint,BorderLayout.SOUTH);editorPanel.add(editorHeader,BorderLayout.NORTH);editorPanel.add(new EditorScrollPane(draft),BorderLayout.CENTER);
        mirror.setLineWrap(Boolean.parseBoolean(app.preferences.get("mirror-wrap","true")));mirror.setWrapStyleWord(true);mirrorViews.add(mirror);
        JPanel mirrored=mirrorPanel;mirrored.add(new EditorScrollPane(mirror),BorderLayout.CENTER);
        JMenuBar mirrorBar=new JMenuBar();JMenu mirrorActions=new JMenu(I18n.t("镜像 · 操作","Mirror · Actions"));mirrorBar.add(mirrorActions);Workspace.add(mirrorActions,I18n.t("独立镜像窗","Separate mirror window"),()->detach(I18n.t("输出镜像 · 可编辑","Output mirror · editable"),mirror,false),null);Workspace.add(mirrorActions,I18n.t("翻译选区","Translate selection"),()->assist("translate"),null);
        JCheckBoxMenuItem wrap=new JCheckBoxMenuItem(I18n.t("自动换行","Wrap lines"),mirror.getLineWrap());wrap.addActionListener(e->{mirrorViews.forEach(v->v.setLineWrap(wrap.isSelected()));app.preferences.set("mirror-wrap",Boolean.toString(wrap.isSelected()));});mirrorActions.add(wrap);mirrored.add(mirrorBar,BorderLayout.NORTH);
        assistantChat=new AssistantChatPanel(this);assistantPanel.add(assistantChat,BorderLayout.CENTER);
        JPanel terminalColumn=new JPanel(new BorderLayout());inlineDraft.setDocument(draft.getDocument());inlineDraft.setRows(1);inlineDraft.setMargin(new Insets(4,8,4,28));inlineDraft.setName("bottom-editor");inlineBar.setBorder(new EmptyBorder(6,8,6,8));
        JLabel prompt=new JLabel(">");prompt.setForeground(DesktopApp.ACCENT);inlineBar.add(prompt,BorderLayout.WEST);
        inputArea=new BottomInputPane(view,inlineBar,inlineScroll);inlineBar.add(inputArea.editorSurface,BorderLayout.CENTER);
        JPanel inlineActions=new JPanel(new BorderLayout());JLabel inlineHint=new JLabel(I18n.t("Enter 发送 · Shift+Enter 换行","Enter sends · Shift+Enter new line"));inlineHint.setForeground(DesktopApp.MUTED);inlineActions.add(inlineHint,BorderLayout.CENTER);inlineBar.add(inlineActions,BorderLayout.EAST);
        terminalColumn.add(inputArea,BorderLayout.CENTER);
        JPanel dockHeader=new JPanel(new BorderLayout());JLabel dockTitle=new JLabel(I18n.t("  辅助视图","  Side views"));dockTitle.setForeground(DesktopApp.MUTED);dockTitle.setFont(DesktopFonts.ui().deriveFont(11f));dockHeader.add(dockTitle,BorderLayout.WEST);dockHeader.add(UiIcons.closeButton(I18n.t("收起侧边视图","Hide side view"),this::hideTools),BorderLayout.EAST);toolDock.add(dockHeader,BorderLayout.NORTH);toolDock.add(tools,BorderLayout.CENTER);
        split.setLeftComponent(terminalColumn);split.setRightComponent(toolDock);split.setResizeWeight(.70);split.setBorder(null);add(split,BorderLayout.CENTER);setEditorPlacement(app.preferences.get("editor-position","bottom"));
        message.setForeground(DesktopApp.MUTED);add(message,BorderLayout.SOUTH);
        view.setNotice(lastNotice::set);draft.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){draftDirty=true;}public void removeUpdate(DocumentEvent e){draftDirty=true;}public void changedUpdate(DocumentEvent e){draftDirty=true;}});
        bindDraft(draft);bindDraft(inlineDraft);
        uiTimer=new javax.swing.Timer(150,e -> refresh());uiTimer.start();
    }
    public void start() {
        if(connecting || stopped)return;
        connecting=true;List<Profile> routes=app.profiles();
        connectionWorker.submit(() -> {
            int failures=0;
            try {
                while(!stopped) {
                    long connectedAt=0;
                    List<SshConnection> owned=new ArrayList<>();
                    boolean retry=true;
                    try {
                        updateStatus(failures==0?I18n.t("正在连接…","Connecting…"):I18n.t("重连中 · 第 ","Reconnecting · attempt ")+failures+I18n.t(" 次",""));
                        app.events.record(profile.name(),failures==0?I18n.t("开始连接","Start connection"):I18n.t("尝试重新连接 · 第 ","Reconnecting · attempt ")+failures+I18n.t(" 次",""));
                        connection=connectRoute(profile,routes,new HashSet<>(),owned);
                        connectedAt=System.nanoTime();
                        if(stopped)break;
                        terminal=view.attach();
                        Options options=profile.options();LaglessConfig.load(LaglessConfig.defaultPath()).applyTo(options,options.host);
                        options.resumeId=profile.tmux()?"lagless_"+id.replace("-",""):null;
                        options.historySuffix=id;
                        TerminalSession session=new TerminalSession(terminal,connection,options);engine=session;session.setPassThrough(passThrough);
                        session.setInputAudit(message->app.events.operation(profile.name(),message));
                        session.setSubmittedInputListener(line->{
                            try{transcript.recordSubmittedInput(line);}
                            catch(RuntimeException error){logFailed=true;lastNotice.set(error.getMessage());throw error;}
                        });
                        session.setModeListener(mode -> {view.setLocalEditing(mode==TerminalSession.Mode.EDIT);updateStatus(mode==TerminalSession.Mode.EDIT?I18n.t("● 本地编辑 · 输入不等待网络","● Local editing · typing never waits for the network"):I18n.t("● 透传 / 预测 · 全屏及交互程序","● Pass-through / prediction · full-screen and interactive programs"));});
                        session.setOutputListener(text -> {
                            view.observeRemoteModes(text);
                            try { int[] grid=view.gridSize();transcript.append(text,grid[0],grid[1]); } catch(RuntimeException error) {logFailed=true;lastNotice.set(error.getMessage());throw error;}
                            appendMirror(text);app.plugins.output(id,text);
                        });
                        session.setCommandGuard(command -> !CommandSafety.needsReview(command) || takeApproval(session,command) || review(command));
                        session.setDisconnectListener(view::endInput);
                        app.events.record(profile.name(),I18n.t("SSH 已连接","SSH connected"));
                        view.accepting(true);session.run();
                        int exit=connection.shellExitStatus();
                        if(exit>=0) {app.events.record(profile.name(),I18n.t("远程 shell 退出 · 状态 ","Remote shell exits · status ")+exit);updateStatus(I18n.t("远程 shell 已退出 · 状态 ","Remote shell exited · status ")+exit);retry=false;}
                    } catch(Exception e) {app.events.record(profile.name(),I18n.t("连接中断：","Connection lost: ")+e.getMessage());lastNotice.set(I18n.t("连接中断：","Connection lost: ")+e.getMessage());}
                    finally {
                        try{transcript.finish();}catch(RuntimeException e){logFailed=true;lastNotice.set(e.getMessage());}
                        view.accepting(false);engine=null;approvals.clear();
                        if(connectedAt>0)app.events.record(profile.name(),I18n.t("SSH 通道已关闭","SSH channel closed"));
                        SshConnection old=connection;connection=null;if(old!=null)old.close();
                        if(terminal!=null)try{terminal.close();}catch(Exception ignored){}terminal=null;
                        Collections.reverse(owned);owned.forEach(SshConnection::close);
                    }
                    if(stopped || !retry || !profile.reconnect() || logFailed)break;
                    if(connectedAt>0 && System.nanoTime()-connectedAt>TimeUnit.SECONDS.toNanos(30))failures=0;
                    failures++;
                    long delay=reconnectDelayMillis(failures);
                    app.events.record(profile.name(),I18n.t("连接断开 · ","Disconnected · ")+delay/1000+I18n.t(" 秒后重连","s until reconnect"));
                    updateStatus(I18n.t("连接断开 · ","Disconnected · ")+delay/1000+I18n.t(" 秒后重连 · 未发送的草稿仍在本地","s until reconnect · unsent drafts stay local"));
                    long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(delay);
                    while(!stopped && System.nanoTime()<until)Thread.sleep(100);
                }
            } catch(InterruptedException e){Thread.currentThread().interrupt();}
            finally {connecting=false;view.accepting(false);if(stopped)updateStatus(I18n.t("已断开 · 日志和草稿已保留","Disconnected · logs and drafts kept"));else if(logFailed)updateStatus(I18n.t("日志写入失败，已停止自动重连","Log write failed; auto-reconnect stopped"));}
        });
    }
    static long reconnectDelayMillis(int failures) {return Math.min(30_000,1000L << Math.min(5,Math.max(0,failures-1)));}
    private SshConnection connectRoute(Profile target,List<Profile> routes,Set<String> visited,List<SshConnection> owned) throws Exception {
        if(stopped)throw new InterruptedException();
        if(!visited.add(target.id()) || visited.size()>8)throw new IllegalArgumentException(I18n.t("跳板链循环或过长","Jump host chain loops or is too long"));
        if(target.jumpId().isBlank())return SshConnection.connect(target.options(),prompter());
        Profile jump=routes.stream().filter(p -> p.id().equals(target.jumpId())).findFirst().orElseThrow(() -> new IllegalArgumentException(I18n.t("跳板账号不存在","Jump host account does not exist")));
        SshConnection parent=app.sessions.stream().filter(s -> s!=this && s.profile.id().equals(jump.id()))
                .map(s -> s.connection).filter(c -> c!=null && c.isConnected()).findFirst().orElse(null);
        if(parent==null){parent=connectRoute(jump,routes,visited,owned);owned.add(parent);}
        return SshConnection.connectVia(target.options(),prompter(),parent);
    }
    private SshConnection.Prompter prompter() {if(suppliedPrompter!=null)return suppliedPrompter;return new SshConnection.Prompter(){
        public String askSecret(String prompt){return onEdt(() -> {if(stopped)return null;JPasswordField field=new JPasswordField(28);int answer=JOptionPane.showConfirmDialog(SessionPane.this,DesktopApp.form(prompt,field),profile.name()+I18n.t(" · SSH 认证"," · SSH authentication"),JOptionPane.OK_CANCEL_OPTION);if(answer!=JOptionPane.OK_OPTION){stopped=true;return null;}char[] secret=field.getPassword();try{return new String(secret);}finally{Arrays.fill(secret,'\0');}});}
        public boolean askYesNo(String question){boolean accepted=Boolean.TRUE.equals(onEdt(() -> !stopped && JOptionPane.showConfirmDialog(SessionPane.this,question,profile.name()+I18n.t(" · 验证主机指纹"," · Verify host fingerprint"),JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION));if(!accepted)stopped=true;return accepted;}
        public void show(String message){lastNotice.set(message);}
    };}
    private boolean review(String command) {return Boolean.TRUE.equals(onEdt(() -> {
        JTextArea text=DesktopApp.textArea();text.setText(command);text.setRows(7);text.setColumns(60);text.setEditable(false);
        return !stopped && JOptionPane.showConfirmDialog(this,new Object[]{I18n.t("可能有破坏性或提升权限的操作。核对目标：","This may be destructive or raise privileges. Check the target: ")+profile.name()+" ("+profile.host()+")",new EditorScrollPane(text)},I18n.t("危险命令拦截 · 确认后才发送","Dangerous command held · confirm before sending"),JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE)==JOptionPane.OK_OPTION;
    }));}
    static <T> T onEdt(Callable<T> task) {
        try {if(SwingUtilities.isEventDispatchThread())return task.call();FutureTask<T> future=new FutureTask<>(task);SwingUtilities.invokeAndWait(future);return future.get();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    boolean passThrough(){return passThrough;}
    private void auditTerminalInput(String message){try{app.workers.execute(()->app.events.operation(profile.name(),message));}catch(RejectedExecutionException ignored){}}
    void setRecordFullscreen(boolean value){transcript.setFull(value);}
    void setPassThrough(boolean enabled){
        TerminalSession session=engine;
        boolean previous=passThrough;
        boolean wake;try{wake=session!=null&&session.setPassThrough(enabled);}catch(IllegalStateException e){lastNotice.set(e.getMessage());return;}
        passThrough=enabled;
        if(wake&&!view.sendKeys("\u001d")){
            passThrough=previous;session.setPassThrough(previous);lastNotice.set(I18n.t("输入队列暂不可用，请稍后切换模式。","Input queue is unavailable; switch modes later."));return;
        }
        app.events.record(profile.name(),enabled?I18n.t("启用交互程序透传","Enable interactive pass-through"):I18n.t("恢复 Lagless 自动本地编辑","Restore Lagless automatic local editing"));
        lastNotice.set(enabled?I18n.t("交互按键直接发送；本地编辑框仍完全本地。终端内尚未提交的文字会交给远端，不附加回车。","Keys go straight to the remote side; the local editor stays local. Text not yet submitted in the terminal is handed to the remote side without Enter."):I18n.t("已恢复自动识别提示符和本地编辑。","Prompt detection and local editing restored."));
    }
    public boolean canSend(){TerminalSession session=engine;return !stopped && session!=null && session.mode()==TerminalSession.Mode.EDIT;}
    private boolean takeApproval(TerminalSession session,String command){for(Approval approval:approvals)if(approval.session()==session&&approval.command().equals(command)&&approvals.remove(approval))return true;return false;}
    public boolean sendCommand(String command) {
        TerminalSession source=engine;SshConnection target=connection;
        if(stopped||source==null||target==null||!target.isShellOpen()){lastNotice.set(I18n.t("当前连接未就绪；命令保留在本地。","This connection is not ready; the command stays local."));return false;}
        TerminalSession.Mode mode=source.mode();boolean interactive=mode!=TerminalSession.Mode.EDIT;
        if(interactive&&!interactiveSend.isSelected()){lastNotice.set(I18n.t("交互程序发送已关闭，请在「本地编辑 · 操作」中启用；内容保留在本地。","Sending to interactive programs is off; enable it in Local editor · Actions. The text stays local."));return false;}
        if(command.isBlank())return false;
        if(MultilineInput.unsupportedControls(MultilineInput.normalize(command))){lastNotice.set(I18n.t("内容包含不支持的控制字符，请检查后重试。","The text contains unsupported control characters; check it and try again."));return false;}
        if(command.length()>16384){lastNotice.set(I18n.t("命令超过 16 KiB，请改为脚本文件上传。","Command exceeds 16 KiB; upload it as a script file instead."));return false;}
        String line=MultilineInput.prepare(this,command);if(line==null)return false;
        Approval approval=null;
        if(interactive){
            if(JOptionPane.showConfirmDialog(this,I18n.t("目标：","Target: ")+profile.name()+I18n.t("\n当前为透传 / tmux / 交互程序；输入可能交给密码框、编辑器或正在运行的程序。\n请先确认远程输入框为空。将发送：\n\n","\nPass-through / tmux / interactive program is active; input may go to a password prompt, an editor or a running program.\nMake sure the remote input is empty first. Will send:\n\n")+line,I18n.t("审阅向交互程序发送的内容","Review text sent to the interactive program"),JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.OK_OPTION)return false;
        }else if(CommandSafety.needsReview(line)){
            if(!review(line))return false;approval=new Approval(source,line);
        }
        if(engine!=source||connection!=target||stopped||!target.isShellOpen()||source.mode()!=mode){lastNotice.set(I18n.t("连接或输入模式已变化，内容保留在本地，请重试。","Connection or input mode changed; the text stays local. Try again."));return false;}
        LocalCommandJournal.Pending pending;
        try{pending=app.localCommands.prepare(profile,id,line,interactive);}catch(RuntimeException e){lastNotice.set(I18n.t("软件日志暂不可写，命令未发送并保留在编辑框：","Application log is not writable; the command was not sent and stays in the editor: ")+e.getMessage());return false;}
        boolean queued=false;try{if(approval!=null)approvals.add(approval);queued=view.sendKeys((interactive?"":"\u0015")+view.pasteSequence(line)+"\r","local-editor-command");if(queued&&interactive)try{transcript.recordSubmittedInput(line);}catch(RuntimeException error){logFailed=true;lastNotice.set(I18n.t("输入已排队，但会话日志写入失败：","Input queued, but writing the session log failed: ")+error.getMessage());}return queued;}
        finally{pending.finish(queued);if(!queued&&approval!=null)approvals.remove(approval);}
    }
    void sendEditor(JTextArea editor){
        String before=editor.getText();
        if(sendCommand(before)&&before.equals(editor.getText())){editor.setText("");persistDraft(draft.getText());}
    }
    static String currentCommand(JTextArea editor){String selected=editor.getSelectedText();if(selected!=null && !selected.isBlank())return selected;try{int line=editor.getLineOfOffset(editor.getCaretPosition());return editor.getText(editor.getLineStartOffset(line),editor.getLineEndOffset(line)-editor.getLineStartOffset(line));}catch(Exception e){return "";}}
    private void bindDraft(JTextArea editor){
        commandViews.add(editor);editor.setLineWrap(editorWrap.isSelected());editor.setWrapStyleWord(true);ClipboardActions.install(editor,lastNotice::set);
        JCheckBoxMenuItem wrap=new JCheckBoxMenuItem(I18n.t("自动换行","Wrap lines"),editor.getLineWrap());wrap.addActionListener(e->app.setEditorWrap(wrap.isSelected()));editor.getComponentPopupMenu().addSeparator();editor.getComponentPopupMenu().add(wrap);editor.getComponentPopupMenu().addPopupMenuListener(new javax.swing.event.PopupMenuListener(){public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e){wrap.setSelected(editor.getLineWrap());}public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e){}public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e){}});
        editor.addFocusListener(new FocusAdapter(){public void focusGained(FocusEvent e){commandEditor=editor;}});
        for(int modifiers:new int[]{0,InputEvent.CTRL_DOWN_MASK})editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,modifiers),"send-lagless-command");
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,InputEvent.SHIFT_DOWN_MASK),"insert-break");
        editor.getActionMap().put("send-lagless-command",new AbstractAction(){public void actionPerformed(ActionEvent e){sendEditor(editor);}});
        editor.getCaret().setBlinkRate(530);
    }
    private void setInlineVisible(boolean visible){inputArea.showEditor(visible);}
    private void addTool(String title,JPanel panel){
        if(tools.indexOfComponent(panel)>=0)return;
        if(panel==editorPanel)tools.insertTab(title,null,panel,null,0);else tools.addTab(title,panel);
        tools.setTabComponentAt(tools.indexOfComponent(panel),new SessionTabHeader(tools,title,()->closeTool(panel)));Workspace.updateTabHeaders(tools);
    }
    void closeTool(Component panel){
        tools.remove(panel);
        app.events.operation(profile.name(),I18n.t("关闭辅助视图：","Close side view: ")+(panel==mirrorPanel?I18n.t("输出镜像","Output mirror"):panel==assistantPanel?I18n.t("AI助手","AI assistant"):I18n.t("本地命令","Local command")));
        if(panel==editorPanel)setInlineVisible(true);
        if(tools.getTabCount()==0)hideTools();
        revalidate();repaint();SwingUtilities.invokeLater(()->focusTool(tools.getSelectedComponent()));
    }
    void hideTools(){view.beginLayoutResize();tools.setVisible(false);toolDock.setVisible(false);split.setDividerSize(0);setInlineVisible(true);revalidate();
        SwingUtilities.invokeLater(()->{view.endLayoutResize();repaint();view.requestFocusInWindow();});}
    private void revealTools(){
        view.beginLayoutResize();tools.setVisible(true);toolDock.setVisible(true);split.setDividerSize(7);setInlineVisible(tools.indexOfComponent(editorPanel)<0);
        revalidate();SwingUtilities.invokeLater(()->{try{if(!disposed&&toolDock.isVisible()&&split.getWidth()>0)split.setDividerLocation(.68);}finally{view.endLayoutResize();repaint();}});
    }
    void setEditorPlacement(String where){
        if(where.equals("right")){addTool(I18n.t("本地命令","Local command"),editorPanel);tools.setSelectedComponent(editorPanel);revealTools();}
        else{tools.remove(editorPanel);setInlineVisible(true);if(tools.getTabCount()==0)hideTools();else revealTools();}
    }
    private void focusTool(Component panel){if(panel==assistantPanel)assistantChat.focusComposer();else if(panel==mirrorPanel)mirror.requestFocusInWindow();else if(panel==editorPanel)draft.requestFocusInWindow();else view.requestFocusInWindow();}
    void showTool(String name){JPanel panel=name.equals("mirror")?mirrorPanel:assistantPanel;String title=name.equals("mirror")?I18n.t("输出镜像","Output mirror"):I18n.t("AI助手","AI assistant");boolean hidden=!toolDock.isVisible();addTool(title,panel);tools.setSelectedComponent(panel);app.events.operation(profile.name(),I18n.t("打开辅助视图：","Open side view: ")+title);if(hidden)revealTools();SwingUtilities.invokeLater(()->focusTool(panel));}
    void showFiles(){new TransferDialog(this).show();}
    synchronized TransferQueue transferQueue(){if(transfers==null)transfers=new TransferQueue(this::requireConnection,message->app.events.operation(profile.name(),message),app.workers);return transfers;}
    void setEditorWrap(boolean wrap){editorWrap.setSelected(wrap);commandViews.forEach(editor->{editor.setLineWrap(wrap);editor.setWrapStyleWord(true);});}
    void detachEditor(){detach(I18n.t("本地命令编辑","Local command editor"),draft,true);}
    private void appendMirror(String text) {
        // Streaming escape removal keeps split OSC/CSI payloads out of the editable mirror.
        synchronized(mirrorPending) {
            for(char c:text.toCharArray()) {
                if(!escape.isEmpty()) {
                    escape.append(c);
                    boolean done=escape.length()>8192;
                    if(escape.length()>=2){char kind=escape.charAt(1);
                        if(kind=='[')done|=escape.length()>2 && c>=0x40 && c<=0x7e;
                        else if(kind==']'||kind=='P'||kind=='_'||kind=='^')done|=c==7 || escape.length()>2 && escape.charAt(escape.length()-2)==27 && c=='\\';
                        else done|=escape.length()>=((kind=='('||kind==')')?3:2);
                    }
                    if(done)escape.setLength(0);
                } else if(c==27)escape.append(c);
                else if(c=='\n'||c=='\t'||c>=32 && c!=127)mirrorPending.append(c);
            }
            if(mirrorPending.length()>128000){int count=mirrorPending.length()-128000;omittedMirror+=count;mirrorPending.delete(0,count);}
        }
    }
    private void refresh() {
        String incoming;long omitted;
        synchronized(mirrorPending){incoming=mirrorPending.toString();mirrorPending.setLength(0);omitted=omittedMirror;omittedMirror=0;}
        if(!incoming.isEmpty()||omitted>0){if(omitted>0)mirror.append(I18n.t("\n[镜像视图省略 ","\n[mirror view omits ")+omitted+I18n.t(" 字符；完整内容请导出日志]\n"," characters; export the log for full content]\n"));mirror.append(incoming);if(mirror.getDocument().getLength()>300000)try{mirror.getDocument().remove(0,mirror.getDocument().getLength()-250000);}catch(Exception ignored){}}
        String note=lastNotice.getAndSet(null);if(note!=null)message.setText(note);
        if(draftDirty && ++draftTicks>=10){draftTicks=0;draftDirty=false;persistDraft(draft.getText());}
    }
    private void persistDraft(String text){draftWriter.submit(() -> {try{ProfileStore.atomicWrite(draftFile,text);}catch(Exception e){lastNotice.set(I18n.t("草稿保存失败：","Saving draft failed: ")+e.getMessage());}});}
    private void updateStatus(String text){SwingUtilities.invokeLater(() -> status.setText(text));}
    private void detach(String title,JTextArea source,boolean commands) {
        JTextArea copy=DesktopApp.textArea();copy.setDocument(source.getDocument());if(commands)bindDraft(copy);else{copy.setLineWrap(mirror.getLineWrap());copy.setWrapStyleWord(true);mirrorViews.add(copy);}
        JDialog dialog=DesktopApp.dialog(this,profile.name()+" · "+title,copy,850,550);
        JPanel row=DesktopApp.row();
        if(commands){row.add(DesktopApp.button(I18n.t("发送全部","Send all"),() -> sendEditor(copy)));row.add(DesktopApp.button(I18n.t("翻译命令","Translate command"),() -> assist("translate-command",copy)));row.add(DesktopApp.button(I18n.t("AI 补全","AI completion"),() -> assist("complete",copy)));}
        else row.add(DesktopApp.button(I18n.t("翻译选区","Translate selection"),() -> assist("translate",copy)));
        dialog.add(row,BorderLayout.SOUTH);
        detached.add(dialog);dialog.addWindowListener(new WindowAdapter(){@Override public void windowClosed(WindowEvent e){detached.remove(dialog);mirrorViews.remove(copy);commandViews.remove(copy);}});dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.setVisible(true);
    }
    private void saveDraftAs(){Path path=DesktopApp.chooseSave(this,profile.name()+"-commands.txt");if(path!=null)try{ProfileStore.atomicWrite(path,draft.getText());}catch(Exception e){DesktopApp.error(this,e);}}
    void exportLog(){Path path=DesktopApp.chooseSave(this,profile.name()+"-session.log");if(path!=null)app.background(this,() -> {transcript.snapshot();journal.export(path);return path;},p -> lastNotice.set(I18n.t("日志已导出：","Log exported: ")+p));}
    void flushJournal() throws Exception {transcript.snapshot();journal.force();view.flushHistory();}
    SshConnection requireConnection(){SshConnection c=connection;if(c==null || !c.isConnected())throw new IllegalStateException(I18n.t("SSH 尚未连接","SSH is not connected yet"));return c;}
    void selectContexts(){
        SessionPane[] candidates=app.sessions.stream().filter(s -> s!=this).toArray(SessionPane[]::new);JList<SessionPane> list=new JList<>(candidates);
        for(int i=0;i<candidates.length;i++)if(contextSessions.contains(candidates[i].id))list.addSelectionInterval(i,i);
        if(JOptionPane.showConfirmDialog(this,new Object[]{I18n.t("仅在主动请求 AI 时加入所选会话的当前屏幕；发送前可再次编辑。","The current screen of the chosen sessions is added only when you ask AI; you can edit it again before sending."),new EditorScrollPane(list)},I18n.t("多 SSH 协作上下文","Multi-SSH collaboration context"),JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION){contextSessions.clear();list.getSelectedValuesList().forEach(s -> contextSessions.add(s.id));}
    }
    private void assist(String action){assist(action,null);}
    private void assist(String action,JTextArea source){
        String text=action.equals("translate")?(source==null?mirror:source).getSelectedText():
                action.equals("complete")||action.equals("translate-command")?currentCommand(source==null?commandEditor:source):"";
        if(action.equals("translate")&&(text==null||text.isBlank())){lastNotice.set(I18n.t("请先选中需要翻译的镜像文本。","Select mirror text to translate first."));return;}
        assistantChat.prepare(action,text==null?"":text);
        showTool("assistant");
    }
    String assistantContext(int rows,boolean includeOtherSessions){
        StringBuilder context=new StringBuilder();
        if(rows!=0)try{context.append("SSH ").append(profile.name()).append(" terminal output:\n").append(rows<0?view.historyText(-1):view.historyText(rows));}
        catch(Exception error){context.append(I18n.t("[终端历史读取失败：","[Failed to read terminal history: ")).append(error.getMessage()).append("]");}
        if(includeOtherSessions)for(SessionPane s:app.sessions)if(s!=this&&contextSessions.contains(s.id))context.append("\n\nSSH ").append(s.profile.name()).append(" current screen:\n").append(s.view.screenText());
        return context.toString();
    }
    String currentCommandText(){return currentCommand(commandEditor);}
    void sendAssistantCommand(String command){draft.setText(command);sendEditor(draft);}
    void disconnect(){app.events.record(profile.name(),I18n.t("用户断开连接","Disconnected by user"));stopped=true;view.accepting(false);SshConnection c=connection;if(c!=null)app.workers.submit(c::close);updateStatus(I18n.t("正在断开…","Disconnecting…"));}
    void reconnect(){if(connecting){lastNotice.set(I18n.t("连接正在运行或重试中，请先关闭连接并等待结束。","The connection is running or retrying; close it and wait for it to end."));return;}stopped=false;logFailed=false;start();}
    @Override public void close(){
        if(disposed)return;disposed=true;
        if(assistantChat!=null)assistantChat.close();
        if(transfers!=null)transfers.close();
        disconnect();uiTimer.stop();view.close();new ArrayList<>(detached).forEach(Window::dispose);detached.clear();app.sessions.remove(this);persistDraft(draft.getText());draftWriter.shutdown();
        connectionWorker.submit(() -> {try{try{transcript.close();}finally{journal.close();}}catch(Exception e){SwingUtilities.invokeLater(() -> DesktopApp.error(app.windows.isEmpty()?null:app.windows.get(0),e));}});connectionWorker.shutdown();
    }
    @Override public String toString(){return profile.name()+" · "+profile.host()+" · "+id.substring(0,6);}
    void awaitClosed(long deadline)throws InterruptedException{connectionWorker.awaitTermination(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);draftWriter.awaitTermination(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
}
