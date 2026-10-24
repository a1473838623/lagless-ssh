package dev.lagless.desktop;

import dev.lagless.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Native Java desktop, sharing the CLI's latency-sensitive terminal implementation. */
public final class DesktopApp implements AutoCloseable {
    static final String PROJECT_URL="https://github.com/a1473838623/lagless-ssh";
    static final Color INK = new Color(0x17253f), MUTED = new Color(0x64748b), ACCENT = new Color(0x087f70);
    final Path home;
    final ProfileStore store;
    final DefaultListModel<Profile> accounts = new DefaultListModel<>();
    final List<SessionPane> sessions = new CopyOnWriteArrayList<>();
    final List<JFrame> windows = new ArrayList<>();
    final PluginHost plugins;
    final ApplicationJournal events;
    final LocalCommandJournal localCommands;
    final DesktopPreferences preferences;
    final Map<JFrame,Workspace> workspaces=new IdentityHashMap<>();
    private boolean closed;
    final ExecutorService workers = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r,"lagless-desktop-worker"); t.setDaemon(true); return t; });
    private boolean restoring;
    private final boolean checkUpdatesOnOpen;
    private final boolean exitProcessOnClose;
    private String accountNotice="";
    public DesktopApp(Path home) throws Exception {
        this(home,true);
    }
    DesktopApp(Path home,boolean checkUpdatesOnOpen) throws Exception {
        this(home,checkUpdatesOnOpen,new ApplicationJournal(home),false);
    }
    private DesktopApp(Path home,boolean checkUpdatesOnOpen,ApplicationJournal runLog,boolean exitProcessOnClose) throws Exception {
        this.checkUpdatesOnOpen=checkUpdatesOnOpen;
        this.exitProcessOnClose=exitProcessOnClose;
        this.home = home;events=runLog;store = new ProfileStore(home); plugins = new PluginHost(home,events);
        preferences=new DesktopPreferences(home);localCommands=new LocalCommandJournal(events);
        List<Profile> saved=store.load();
        try{int count=TerminalProfiles.migrate(home,saved);if(count>0)accountNotice="已读取 "+count+" 个终端连接";}
        catch(IOException|IllegalArgumentException e){accountNotice="终端配置读取失败："+e.getMessage();}
        saved.forEach(accounts::addElement);
        events.record(null,"载入 "+saved.size()+" 个连接"+(accountNotice.isBlank()?"":"；"+accountNotice));
    }
    public static void launch() {
        DesktopRuntime.bootstrap();
        Path home=Path.of(System.getProperty("user.home"),".lagless");
        ApplicationJournal runLog;
        try{runLog=new ApplicationJournal(home);runLog.installErrorHandler();}
        catch(IOException error){throw new UncheckedIOException("无法创建软件运行日志",error);}
        if (GraphicsEnvironment.isHeadless()) { runLog.record(null,"没有图形桌面，结束本次运行");runLog.close();System.err.println("lagless gui requires a graphical desktop."); return; }
        SwingUtilities.invokeLater(() -> {
            try { theme(); new DesktopApp(home,true,runLog,true).newWindow(); }
            catch (Throwable e) { runLog.error("启动",e);runLog.record(null,"程序启动失败，结束本次运行");runLog.close();JOptionPane.showMessageDialog(null,e.getMessage(),"Lagless 启动失败",JOptionPane.ERROR_MESSAGE); }
        });
    }
    static void theme() {
        // Native display canvases are heavyweight windows. Menus/tooltips must
        // also be heavyweight to stay above them, including on separate monitors.
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        Font font=DesktopFonts.ui();
        com.formdev.flatlaf.FlatLaf.setPreferredFontFamily(font.getFamily());
        com.formdev.flatlaf.FlatLightLaf.setup();
        // A menu needs one opaque popup window above the native terminal.
        // Avoid a second translucent shadow window and its separate peer.
        UIManager.put("Popup.dropShadowPainted",false);
        UIManager.put("defaultFont",font);
        UIManager.put("Component.arc",8);UIManager.put("Button.arc",8);UIManager.put("TextComponent.arc",8);
        UIManager.put("TabbedPane.tabHeight",42);UIManager.put("TabbedPane.showTabSeparators",false);
        UIManager.put("TabbedPane.tabType","underlined");UIManager.put("TabbedPane.tabSelectionHeight",0);
        UIManager.put("TabbedPane.tabInsets",new Insets(4,6,4,6));
        UIManager.put("TabbedPane.background",new Color(0xeef2f6));UIManager.put("TabbedPane.selectedBackground",new Color(0xeef2f6));
        UIManager.put("TabbedPane.selectedForeground",INK);UIManager.put("TabbedPane.contentAreaColor",new Color(0xdbe3ed));
        UIManager.put("TabbedPane.hoverColor",new Color(0xeef2f6));UIManager.put("TabbedPane.focusColor",new Color(0xeef2f6));
        UIManager.put("TabbedPane.contentBorderInsets",new Insets(0,0,0,0));UIManager.put("MenuBar.background",new Color(0xf9fafb));
        UIManager.put("TabbedPane.underlineColor",ACCENT);UIManager.put("Component.focusColor",new Color(0x8bd4c0));
        for (Object key : Collections.list(UIManager.getDefaults().keys()))
            if (UIManager.get(key) instanceof javax.swing.plaf.FontUIResource) UIManager.put(key,font);
        UIManager.put("Panel.background",new Color(0xf4f7fb)); UIManager.put("Label.foreground",INK);
        UIManager.put("TabbedPane.selected",Color.WHITE); UIManager.put("Button.background",Color.WHITE);
        UIManager.put("SplitPane.dividerSize",5); UIManager.put("TextArea.selectionBackground",new Color(0xbfe8df));
        UIManager.put("ScrollBar.width",10);UIManager.put("List.selectionBackground",new Color(0xd8eee8));UIManager.put("List.selectionForeground",INK);
    }
    List<Profile> profiles() { return Collections.list(accounts.elements()); }
    void newWindow() {
        GraphicsConfiguration display=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        PointerInfo pointer=MouseInfo.getPointerInfo();if(pointer!=null)display=pointer.getDevice().getDefaultConfiguration();
        JFrame frame = new JFrame("Lagless",display); windows.add(frame);events.record(null,"打开工作区窗口");
        frame.setIconImage(appIcon());
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        Rectangle usable=new Rectangle(display.getBounds());Insets insets=Toolkit.getDefaultToolkit().getScreenInsets(display);
        usable.x+=insets.left;usable.y+=insets.top;usable.width-=insets.left+insets.right;usable.height-=insets.top+insets.bottom;
        frame.setContentPane(content(frame)); frame.setSize(Math.min(1440,(int)(usable.width*.92)),Math.min(900,(int)(usable.height*.92)));
        frame.setMinimumSize(new Dimension(Math.min(1000,usable.width),Math.min(650,usable.height)));
        frame.setLocation(usable.x+(usable.width-frame.getWidth())/2,usable.y+(usable.height-frame.getHeight())/2);
        frame.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent event) {
            List<SessionPane> owned = sessions.stream().filter(s -> SwingUtilities.getWindowAncestor(s) == frame).toList();
            if (!owned.isEmpty() && JOptionPane.showConfirmDialog(frame,"关闭窗口并断开其中的 " + owned.size() + " 个连接？日志和草稿会保留。","关闭窗口",JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            owned.forEach(SessionPane::close); windows.remove(frame); frame.dispose();
            events.record(null,"关闭工作区窗口");if(windows.isEmpty())close(owned);
        }});
        frame.setVisible(true);
    }
    static Image appIcon(){
        java.awt.image.BufferedImage icon=new java.awt.image.BufferedImage(32,32,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics=icon.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(ACCENT);graphics.fillRoundRect(0,0,32,32,9,9);
        graphics.setColor(Color.WHITE);graphics.setFont(new Font(Font.SANS_SERIF,Font.BOLD,23));graphics.drawString("L",9,25);
        graphics.dispose();return icon;
    }
    static Window ownerWindow(Component component){return component instanceof Window window?window:SwingUtilities.getWindowAncestor(component);}
    JPanel content(JFrame owner) {
        Workspace workspace=new Workspace(this,owner);workspaces.put(owner,workspace);return workspace.root;
    }
    boolean isRestoring(){return restoring;}
    void selectProfile(Profile profile){workspaces.values().forEach(w->w.selectProfile(profile));}
    void showLogs(Component parent,boolean software,Path initial){new LogViewer(this,parent,software,initial);}
    void showLocalLogs(Component parent){showLogs(parent,true,null);}
    void openProject(Component parent){
        background(parent,()->{
            if(!Desktop.isDesktopSupported()||!Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))throw new IOException("无法打开系统浏览器，请访问："+PROJECT_URL);
            Desktop.getDesktop().browse(java.net.URI.create(PROJECT_URL));return true;
        },opened->events.operation("打开项目 GitHub"));
    }
    void about(Component parent){
        JPanel info=new JPanel();info.setLayout(new BoxLayout(info,BoxLayout.Y_AXIS));info.setBorder(new EmptyBorder(8,8,8,8));
        JLabel title=new JLabel("Lagless");title.setFont(DesktopFonts.ui().deriveFont(Font.BOLD,26));title.setForeground(INK);info.add(title);info.add(Box.createVerticalStrut(8));
        info.add(new JLabel("版本 "+Installation.version()));info.add(new JLabel("剪贴板："+NativeGraphics.clipboardStatus()));info.add(Box.createVerticalStrut(12));info.add(new JLabel("面向高延迟网络的 SSH 工具"));
        info.add(new JLabel("本地编辑 · 多连接工作区 · 独立终端核心"));info.add(Box.createVerticalStrut(16));
        JTextField link=new JTextField(PROJECT_URL);link.setEditable(false);link.setBorder(null);link.setOpaque(false);link.setForeground(ACCENT);info.add(link);
        int action=JOptionPane.showOptionDialog(parent,info,"关于 Lagless",JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,new String[]{"打开 GitHub","关闭"},"打开 GitHub");
        if(action==0)openProject(parent);
    }
    void setAutoRemoteWheel(boolean value){preferences.set("wheel-auto-interactive",Boolean.toString(value));sessions.forEach(s->s.view.setAutoRemoteWheel(value));workspaces.values().forEach(w->w.autoRemoteWheel.setSelected(value));}
    void setPageWheelKeys(boolean value){preferences.set("wheel-page-keys",Boolean.toString(value));sessions.forEach(s->s.view.setPageKeysForRemoteWheel(value));workspaces.values().forEach(w->w.pageWheelKeys.setSelected(value));}
    void setRecordFullscreen(boolean value){
        preferences.set("record-fullscreen",Boolean.toString(value));sessions.forEach(s->s.setRecordFullscreen(value));
        workspaces.values().forEach(w->w.recordFullscreen.setSelected(value));
        events.record(null,value?"启用全屏交互记录（日志和回滚）":"关闭重复整屏记录，保留正文增量历史");
    }
    void setEditorWrap(boolean wrap){preferences.set("editor-wrap",Boolean.toString(wrap));sessions.forEach(s->s.setEditorWrap(wrap));workspaces.values().forEach(w->w.editorWrap.setSelected(wrap));}

    void open(JTabbedPane tabs, Profile profile) {
        try {
            SessionPane pane = new SessionPane(this,profile); sessions.add(pane); tabs.addTab(profile.name(),pane); tabs.setSelectedComponent(pane);
            tabs.setToolTipTextAt(tabs.indexOfComponent(pane),profile.user()+"@"+profile.host()+":"+profile.port());
            tabs.setTabComponentAt(tabs.indexOfComponent(pane),new SessionTabHeader(tabs,profile.name(),()->{if(JOptionPane.showConfirmDialog(pane,"断开此连接？草稿与日志将保留。","关闭连接",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION){pane.close();tabs.remove(pane);}}));Workspace.updateTabHeaders(tabs);pane.start();
        } catch (Exception e) { error(tabs,e); }
    }
    void edit(Component parent, Profile previous) {
        JTextField name = field(previous==null?"":previous.name()), host = field(previous==null?"":previous.host()), user = field(previous==null?"":previous.user()), port = field(previous==null?"22":Integer.toString(previous.port())), identity = field(previous==null?"":previous.identity());
        JPanel identityRow = new JPanel(new BorderLayout(8,0));
        identityRow.add(identity,BorderLayout.CENTER);
        JButton chooseIdentity = button("选择文件…",()->chooseIdentityFile(identity));
        chooseIdentity.setName("choose-identity-file");
        chooseIdentity.setToolTipText("选择此电脑上的 SSH 私钥文件");
        identityRow.add(chooseIdentity,BorderLayout.EAST);
        List<Profile> terminalChoices;try{terminalChoices=TerminalProfiles.read(home);}catch(Exception e){events.record(null,"读取跳板配置失败："+e.getMessage());terminalChoices=List.of();}
        List<Profile> candidates=profiles();try{TerminalProfiles.merge(candidates,terminalChoices);}catch(IOException e){error(parent,e);return;}
        JComboBox<Object> jump = new JComboBox<>();jump.setName("jump-account");jump.setMaximumRowCount(12); jump.addItem("直连 / OpenSSH ProxyJump");
        candidates.stream().filter(p -> previous==null || !p.id().equals(previous.id())).forEach(jump::addItem);
        if (previous!=null) profiles().stream().filter(p -> p.id().equals(previous.jumpId())).findFirst().ifPresent(jump::setSelectedItem);
        JCheckBox reconnect = new JCheckBox("网络中断后自动重连",previous==null || previous.reconnect());
        JCheckBox tmux = new JCheckBox("使用服务器已有 tmux 恢复会话（不安装软件）",previous!=null && previous.tmux());
        JPanel form = form("名称",name,"主机 / SSH 别名",host,"用户名（留空读取 SSH 配置）",user,"端口",port,"私钥文件（可选）",identityRow,"跳板账号",jump,"",reconnect,"",tmux);
        List<Profile> sourceChoices=terminalChoices;
        while (JOptionPane.showConfirmDialog(parent,form,previous==null?"添加 SSH 连接":"编辑 SSH 连接",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION) {
            try {
                List<Profile> next=profiles();String jumpId="";
                if(jump.getSelectedItem() instanceof Profile chosen)jumpId=TerminalProfiles.addJumpChoice(next,sourceChoices,chosen);
                Profile p = new Profile(previous==null?UUID.randomUUID().toString():previous.id(),name.getText().trim(),host.getText().trim(),user.getText().trim(),Integer.parseInt(port.getText().trim()),identity.getText().trim(),jumpId,reconnect.isSelected(),tmux.isSelected(),previous!=null&&previous.host().equals(host.getText().trim())?previous.sshAlias():"");
                int position=-1;if(previous!=null)for(int i=0;i<next.size();i++)if(next.get(i).id().equals(previous.id())){position=i;break;}
                if(position<0)next.add(p);else next.set(position,p);validateRoutes(next);store.save(next);
                accounts.clear(); next.forEach(accounts::addElement);selectProfile(p);events.operation("保存连接："+p.name()); return;
            } catch (Exception e) { error(parent,e); }
        }
    }
    private static void chooseIdentityFile(JTextField identity) {
        JFileChooser picker = new JFileChooser();
        picker.setDialogTitle("选择本地 SSH 私钥文件");
        picker.setFileSelectionMode(JFileChooser.FILES_ONLY);
        picker.setMultiSelectionEnabled(false);
        picker.setFileHidingEnabled(false);
        Path sshDirectory = Path.of(System.getProperty("user.home"),".ssh");
        if(Files.isDirectory(sshDirectory))picker.setCurrentDirectory(sshDirectory.toFile());
        String current = identity.getText().trim();
        if(!current.isEmpty()) {
            if(current.equals("~")||current.startsWith("~/")||current.startsWith("~\\"))current=System.getProperty("user.home")+current.substring(1);
            try {
                Path path=Path.of(current).toAbsolutePath().normalize();
                if(Files.isDirectory(path))picker.setCurrentDirectory(path.toFile());
                else if(path.getParent()!=null&&Files.isDirectory(path.getParent()))picker.setSelectedFile(path.toFile());
            } catch(InvalidPathException ignored) { /* Keep the default folder for an unfinished path. */ }
        }
        if(picker.showOpenDialog(identity)!=JFileChooser.APPROVE_OPTION)return;
        File selected=picker.getSelectedFile();
        if(selected==null||!Files.isRegularFile(selected.toPath())) {
            JOptionPane.showMessageDialog(identity,"请选择存在的本地私钥文件。","选择私钥",JOptionPane.WARNING_MESSAGE);
            return;
        }
        identity.setText(selected.toPath().toAbsolutePath().normalize().toString());
        identity.setCaretPosition(0);
    }
    static void validateRoutes(List<Profile> profiles) {
        Map<String,Profile> map = new HashMap<>(); profiles.forEach(p -> {if(map.put(p.id(),p)!=null)throw new IllegalArgumentException("重复的账号 ID，请分次导入账号文件");});
        for (Profile profile : profiles) {
            Set<String> visited = new HashSet<>(); Profile current = profile;
            while (current!=null && !current.jumpId().isBlank()) {
                if (!visited.add(current.id()) || visited.size()>8) throw new IllegalArgumentException("跳板链存在循环或超过 8 跳");
                current = map.get(current.jumpId()); if (current==null) throw new IllegalArgumentException("跳板账号不存在");
            }
        }
    }
    void reorderProfile(String id,int insertionIndex)throws IOException{
        List<Profile> next=profiles();int from=-1;for(int i=0;i<next.size();i++)if(next.get(i).id().equals(id)){from=i;break;}
        if(from<0)return;int to=Math.max(0,Math.min(insertionIndex,next.size()));if(from<to)to--;
        if(to==from)return;Profile moved=next.remove(from);next.add(to,moved);store.save(next);
        accounts.clear();next.forEach(accounts::addElement);events.operation("调整连接顺序："+moved.name());
    }
    void remove(Component parent, Profile p) {
        if (p==null) return;
        if (profiles().stream().anyMatch(q -> q.jumpId().equals(p.id()))) { error(parent,new IllegalArgumentException("其他账号正在使用此跳板，请先修改跳板配置")); return; }
        if (JOptionPane.showConfirmDialog(parent,"删除账号「"+p.name()+"」？","删除账号",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return;
        try { List<Profile> next=profiles(); next.remove(p); store.save(next); accounts.removeElement(p); } catch(Exception e) { error(parent,e); }
    }
    void importAccounts(Component parent) {
        JFileChooser picker = new JFileChooser(); picker.setMultiSelectionEnabled(true); picker.setDialogTitle("导入 JSON / OpenSSH config / PuTTY .reg / Xshell .xsh");
        if (picker.showOpenDialog(parent)!=JFileChooser.APPROVE_OPTION) return;
        File[] files = picker.getSelectedFiles();
        background(parent,() -> {
            List<Profile> imported=new ArrayList<>(); for(File file:files) imported.addAll(AccountFormats.read(file.toPath())); return imported;
        }, imported -> {
            try {
                if (imported.isEmpty()) throw new IOException("没有识别到 SSH 账号");
                List<Profile> next=profiles(); ProfileStore.merge(next,imported); validateRoutes(next); store.save(next); accounts.clear(); next.forEach(accounts::addElement);
                JOptionPane.showMessageDialog(parent,"已导入 "+imported.size()+" 个账号。厂商加密密码与私钥格式不会自动转换；请按需重新选择私钥。");
            } catch(Exception e) { error(parent,e); }
        });
    }
    void importTerminal(Component parent){
        background(parent,()->TerminalProfiles.read(home),imported->{try{
            List<Profile> next=profiles();int count=TerminalProfiles.merge(next,imported);store.save(next);accounts.clear();next.forEach(accounts::addElement);
            JOptionPane.showMessageDialog(parent,"新增 "+count+" 个终端连接；已有连接保留且不会重复导入。\n来源："+TerminalProfiles.source(home));
        }catch(Exception e){error(parent,e);}});
    }
    void exportAccounts(Component parent, Profile selected) {
        if(accounts.isEmpty()){JOptionPane.showMessageDialog(parent,"尚无可导出的连接，请先读取终端配置或添加连接。");return;}
        JComboBox<String> format = new JComboBox<>(new String[]{"json","openssh","reg","xsh"});
        JCheckBox only = new JCheckBox("仅导出选中的账号",selected!=null);
        if (JOptionPane.showConfirmDialog(parent,form("格式",format,"",only),"导出账号（不含密码 / 私钥内容）",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return;
        String type=(String)format.getSelectedItem(); Path path=chooseSave(parent,"accounts."+type); if(path==null)return;
        try {
            List<Profile> chosen=only.isSelected() && selected!=null?new ArrayList<>(List.of(selected)):profiles();
            if(type.equals("json") || type.equals("openssh")) {
                for(int i=0;i<chosen.size();i++) {
                    String jumpId=chosen.get(i).jumpId();
                    if(!jumpId.isBlank() && chosen.stream().noneMatch(p -> p.id().equals(jumpId)))
                        profiles().stream().filter(p -> p.id().equals(jumpId)).findFirst().ifPresent(chosen::add);
                }
            }
            AccountFormats.write(path,chosen,type);
        }
        catch(Exception e) { error(parent,e); }
    }
    void assistantSettings(Component parent) {
        new AssistantSettingsDialog(this,parent);
    }
    void pluginInfo(Component parent) {
        new PluginManagerDialog(this,parent);
    }
    void backupMenu(Component parent) {
        if(restoring)return;
        Object choice=JOptionPane.showInputDialog(parent,"完整备份包含配置、账号、日志、草稿、插件、~/.ssh 文件和当前应用程序包。\n备份以密码加密；外部路径私钥需另行保存。", "备份 / 恢复",JOptionPane.PLAIN_MESSAGE,null,new String[]{"创建加密备份","恢复加密备份"},"创建加密备份");
        if(choice==null)return;
        boolean restore=choice.toString().startsWith("恢复");
        if(restore && !sessions.isEmpty()) {error(parent,new IllegalStateException("恢复前请关闭所有窗口中的连接标签"));return;}
        Path archive;
        if(restore) { JFileChooser picker=new JFileChooser();picker.setDialogTitle("选择已完成的 Lagless 备份（.llbackup）");
            picker.setAcceptAllFileFilterUsed(false);picker.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Lagless 完整备份 (*.llbackup)","llbackup"));
            if(picker.showOpenDialog(parent)!=JFileChooser.APPROVE_OPTION)return;archive=picker.getSelectedFile().toPath();
            if(!archive.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".llbackup")||!Files.isRegularFile(archive)){error(parent,new IOException("请选择已完成的 .llbackup 备份文件；创建失败时产生的 .tmp 临时文件不能恢复"));return;}}
        else {archive=chooseSave(parent,"lagless-"+java.time.LocalDate.now()+".llbackup");if(archive==null)return;}
        JPasswordField password=new JPasswordField(24), repeat=new JPasswordField(24);
        JCheckBox restoreApp=new JCheckBox("同时恢复相同类型的应用程序包",true);
        JPanel form=restore?form("备份密码",password,"应用程序",restoreApp):form("密码（至少 10 位）",password,"确认密码",repeat);
        if(JOptionPane.showConfirmDialog(parent,form,restore?"恢复并覆盖同名数据，完成后需重启":"加密备份",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        char[] secret=password.getPassword(), again=repeat.getPassword();
        if(!restore && !Arrays.equals(secret,again)) {Arrays.fill(secret,'\0');Arrays.fill(again,'\0');error(parent,new IllegalArgumentException("两次密码不一致"));return;}
        Arrays.fill(again,'\0');
        List<SessionPane> active=List.copyOf(sessions);
        if(restore) {
            restoring=true;
            boolean includeApplication=restoreApp.isSelected();
            Window owner=parent instanceof Window w?w:SwingUtilities.getWindowAncestor(parent);
            JDialog loading=new JDialog(owner,"正在恢复备份",Dialog.ModalityType.APPLICATION_MODAL);
            loading.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            JPanel progressPanel=new JPanel(new BorderLayout(12,14));progressPanel.setBorder(new EmptyBorder(20,22,20,22));
            JLabel status=new JLabel("正在校验并恢复数据，请稍候…");JProgressBar progress=new JProgressBar();progress.setIndeterminate(true);
            progressPanel.add(status,BorderLayout.NORTH);progressPanel.add(progress,BorderLayout.CENTER);
            loading.setContentPane(progressPanel);loading.setResizable(false);loading.pack();loading.setLocationRelativeTo(parent);
            workers.submit(()->{
                Exception failure=null;
                try {BackupArchive.restore(archive,home.getParent(),secret,includeApplication?DesktopRuntime.applicationBackupSource():null);
                    events.operation("恢复备份完成；备份源文件保留："+archive);}
                catch(Exception e){failure=e;events.error("恢复备份",e);}
                finally {Arrays.fill(secret,'\0');}
                Exception result=failure;
                SwingUtilities.invokeLater(()->{restoring=false;loading.dispose();
                    if(result!=null)error(parent,result);
                    else JOptionPane.showMessageDialog(parent,"恢复完成，备份文件仍保留："+archive+"\n请关闭全部窗口并重新启动，以载入恢复的数据。","恢复完成",JOptionPane.INFORMATION_MESSAGE);
                });
            });
            loading.setVisible(true);
            return;
        }
        background(parent,() -> {try {for(SessionPane s:active)s.flushJournal();events.force();localCommands.force();BackupArchive.create(home.getParent(),archive,secret,DesktopRuntime.applicationBackupSource());
            events.operation("创建备份完成："+archive);return true;}
            finally {Arrays.fill(secret,'\0');}}, done -> JOptionPane.showMessageDialog(parent,"备份已保存："+archive));
    }
    private UpdateDialog updateDialog;
    private volatile Thread shutdownThread;
    void update(Component parent){if(updateDialog==null||!updateDialog.isDisplayable())updateDialog=new UpdateDialog(this,parent,checkUpdatesOnOpen);updateDialog.show();}
    void restartForUpdate(Component parent){
        if(!sessions.isEmpty()&&JOptionPane.showConfirmDialog(parent,"重启将断开当前的 "+sessions.size()+" 个连接，日志与草稿会保留。继续？","重启并应用更新",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        background(parent,()->{NativeUpdater.requestRestart();return true;},ready->close());
    }
    private void finishExit(List<SessionPane> draining){
        Thread shutdown=new Thread(()->{
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            try{for(SessionPane session:draining)session.awaitClosed(deadline);localCommands.awaitClosed(deadline);workers.awaitTermination(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
            catch(InterruptedException error){Thread.currentThread().interrupt();events.error("退出等待",error);}
            finally{events.record(null,"程序退出");events.close();}
            if(exitProcessOnClose&&DesktopRuntime.nativeImage())System.exit(0);
        },"lagless-native-shutdown");shutdownThread=shutdown;shutdown.start();
    }
    boolean awaitShutdown(long deadline)throws InterruptedException{Thread shutdown=shutdownThread;if(shutdown==null)return true;long remaining=deadline-System.nanoTime();if(remaining>0)shutdown.join(Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)));return !shutdown.isAlive();}
    void collaborate(Component parent) {
        if(sessions.isEmpty()) {JOptionPane.showMessageDialog(parent,"请先建立连接。各会话上下文默认相互隔离。");return;}
        JList<SessionPane> list=new JList<>(sessions.toArray(SessionPane[]::new)); list.setVisibleRowCount(6);
        JTextArea command=textArea(); command.setRows(3);
        JPanel form=new JPanel(new BorderLayout(8,8)); form.add(new JLabel("选择目标后审阅命令。只发送到已就绪的 shell；不会自动重放。"),BorderLayout.NORTH);form.add(new EditorScrollPane(list),BorderLayout.CENTER);form.add(new EditorScrollPane(command),BorderLayout.SOUTH);
        if(JOptionPane.showConfirmDialog(parent,form,"多 SSH 协作 · 命令分发",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION) {
            List<SessionPane> targets=list.getSelectedValuesList();String text=command.getText();
            if(targets.isEmpty())return;
            if(targets.stream().anyMatch(s -> !s.canSend())) {error(parent,new IllegalStateException("部分连接未处于本地 shell 编辑模式，未发送任何命令"));return;}
            targets.forEach(s -> s.sendCommand(text));
        }
    }
    void recoverDraft(Component parent) {
        JFileChooser picker=new JFileChooser(home.resolve("desktop/drafts").toFile());if(picker.showOpenDialog(parent)!=JFileChooser.APPROVE_OPTION)return;
        try {JTextArea text=textArea();text.setText(Files.readString(picker.getSelectedFile().toPath()));JDialog dialog=dialog(parent,"恢复的本地草稿",text,800,500);dialog.setVisible(true);}catch(Exception e){error(parent,e);}
    }
    void browse(Path dir, Component parent) {try{Files.createDirectories(dir);Desktop.getDesktop().open(dir.toFile());}catch(Exception e){error(parent,e);}}
    <T> void background(Component parent, Callable<T> action, Consumer<T> result) {
        workers.submit(() -> {try{T value=action.call();SwingUtilities.invokeLater(() -> result.accept(value));}catch(Exception e){events.error("后台操作",e);SwingUtilities.invokeLater(() -> error(parent,e));}});
    }
    static JPanel row() {return new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));}
    static JButton button(String text, Runnable action) {JButton b=new JButton(text);b.setName(text);b.setFocusPainted(false);b.setMargin(new Insets(7,10,7,10));b.addActionListener(e -> action.run());return b;}
    static JTextField field(String text) {return new ImeTextField(text,26);}
    static JTextArea textArea() {
        JTextArea text=new ImeTextArea();text.setFont(DesktopFonts.editor(14));text.setMargin(new Insets(12,12,12,12));text.setCaretColor(ACCENT);text.putClientProperty("caretWidth",2);
        javax.swing.undo.UndoManager undo=new javax.swing.undo.UndoManager();undo.setLimit(200);
        text.getDocument().putProperty("lagless.undo",undo);text.getDocument().addUndoableEditListener(undo);
        int modifiers=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")?InputEvent.META_DOWN_MASK:InputEvent.CTRL_DOWN_MASK;
        text.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z,modifiers),"lagless-undo");
        text.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z,modifiers|InputEvent.SHIFT_DOWN_MASK),"lagless-redo");
        text.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y,modifiers),"lagless-redo");
        text.getActionMap().put("lagless-undo",new AbstractAction(){public void actionPerformed(ActionEvent e){Object value=text.getDocument().getProperty("lagless.undo");if(value instanceof javax.swing.undo.UndoManager u && u.canUndo())u.undo();}});
        text.getActionMap().put("lagless-redo",new AbstractAction(){public void actionPerformed(ActionEvent e){Object value=text.getDocument().getProperty("lagless.undo");if(value instanceof javax.swing.undo.UndoManager u && u.canRedo())u.redo();}});
        ClipboardActions.install(text,text::setToolTipText);
        return text;
    }
    static JPanel form(Object... fields) {JPanel panel=new JPanel(new GridLayout(0,2,10,10));panel.setBorder(new EmptyBorder(10,10,10,10));for(int i=0;i<fields.length;i+=2){panel.add(new JLabel(fields[i].toString()));panel.add((Component)fields[i+1]);}return panel;}
    static Path chooseSave(Component parent, String name) {JFileChooser picker=new JFileChooser();picker.setSelectedFile(new File(name));if(picker.showSaveDialog(parent)!=JFileChooser.APPROVE_OPTION)return null;Path path=picker.getSelectedFile().toPath();if(Files.exists(path)&&JOptionPane.showConfirmDialog(parent,"覆盖「"+path.getFileName()+"」？","文件已存在",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return null;return path;}
    static JDialog dialog(Component parent, String title, JTextArea text, int width, int height) {JDialog dialog=new JDialog(parent instanceof Window w?w:SwingUtilities.getWindowAncestor(parent),title,Dialog.ModalityType.MODELESS);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.setLayout(new BorderLayout());dialog.add(new EditorScrollPane(text),BorderLayout.CENTER);dialog.setSize(width,height);dialog.setLocationRelativeTo(parent);return dialog;}
    static void error(Component parent, Throwable e) {JOptionPane.showMessageDialog(parent,e.getMessage()==null?e.toString():e.getMessage(),"Lagless",JOptionPane.ERROR_MESSAGE);}
    @Override public void close(){close(List.of());}
    private void close(List<SessionPane> closing){if(closed)return;closed=true;if(updateDialog!=null)updateDialog.dispose();Set<SessionPane> draining=new LinkedHashSet<>(closing);draining.addAll(sessions);for(SessionPane pane:List.copyOf(sessions))pane.close();Set<Window> owners=Collections.newSetFromMap(new IdentityHashMap<>());owners.addAll(windows);for(Workspace workspace:workspaces.values()){Window owner=SwingUtilities.getWindowAncestor(workspace.root);if(owner!=null)owners.add(owner);}owners.forEach(Window::dispose);windows.clear();workspaces.clear();events.record(null,"程序正在退出，等待连接关闭及日志落盘");plugins.close();localCommands.close();workers.shutdown();finishExit(List.copyOf(draining));}
}
