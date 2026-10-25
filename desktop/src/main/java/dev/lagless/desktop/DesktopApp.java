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
    static final Color INK = new Color(0x0f172a), MUTED = new Color(0x64748b), ACCENT = new Color(0x0d9488), ACCENT_DARK = new Color(0x0f766e),
            SURFACE = new Color(0xf6f8fb), LINE = new Color(0xe2e8f0), TINT = new Color(0xe6f6f3);
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
        try{int count=TerminalProfiles.migrate(home,saved);if(count>0)accountNotice=I18n.t("已读取 ","Loaded ")+count+I18n.t(" 个终端连接"," terminal connection(s)");}
        catch(IOException|IllegalArgumentException e){accountNotice=I18n.t("终端配置读取失败：","Reading terminal config failed: ")+e.getMessage();}
        saved.forEach(accounts::addElement);
        events.record(null,I18n.t("载入 ","Loaded ")+saved.size()+I18n.t(" 个连接"," connection(s)")+(accountNotice.isBlank()?"":I18n.t("；","; ")+accountNotice));
    }
    public static void launch() {
        DesktopRuntime.bootstrap();
        Path home=Path.of(System.getProperty("user.home"),".lagless");
        ApplicationJournal runLog;
        try{runLog=new ApplicationJournal(home);runLog.installErrorHandler();}
        catch(IOException error){throw new UncheckedIOException(I18n.t("无法创建软件运行日志","Cannot create the application log"),error);}
        if (GraphicsEnvironment.isHeadless()) { runLog.record(null,I18n.t("没有图形桌面，结束本次运行","No graphical desktop; exiting"));runLog.close();System.err.println("lagless gui requires a graphical desktop."); return; }
        SwingUtilities.invokeLater(() -> {
            try { theme(); LanguageDialog.apply(home,true); new DesktopApp(home,true,runLog,true).newWindow(); }
            catch (Throwable e) { runLog.error(I18n.t("启动","Startup"),e);runLog.record(null,I18n.t("程序启动失败，结束本次运行","Startup failed; exiting"));runLog.close();JOptionPane.showMessageDialog(null,e.getMessage(),I18n.t("Lagless 启动失败","Lagless failed to start"),JOptionPane.ERROR_MESSAGE); }
        });
    }
    static void theme() {
        // Native display canvases are heavyweight windows. Menus/tooltips must
        // also be heavyweight to stay above them, including on separate monitors.
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        Font font=DesktopFonts.ui();
        com.formdev.flatlaf.FlatLaf.setPreferredFontFamily(font.getFamily());
        com.formdev.flatlaf.FlatLaf.setGlobalExtraDefaults(Map.of("@accentColor","#0d9488","@background","#f6f8fb"));
        com.formdev.flatlaf.FlatLightLaf.setup();
        // A menu needs one opaque popup window above the native terminal.
        // Avoid a second translucent shadow window and its separate peer.
        UIManager.put("Popup.dropShadowPainted",false);
        UIManager.put("defaultFont",font);
        UIManager.put("Component.arc",10);UIManager.put("Button.arc",10);UIManager.put("TextComponent.arc",10);UIManager.put("CheckBox.arc",6);UIManager.put("ProgressBar.arc",999);
        UIManager.put("Component.focusWidth",1);UIManager.put("Component.innerFocusWidth",0);UIManager.put("Component.focusColor",new Color(0x99e0d4));UIManager.put("Component.borderColor",new Color(0xd5dde6));
        UIManager.put("TabbedPane.tabHeight",42);UIManager.put("TabbedPane.showTabSeparators",false);
        UIManager.put("TabbedPane.tabType","underlined");UIManager.put("TabbedPane.tabSelectionHeight",0);
        UIManager.put("TabbedPane.tabInsets",new Insets(4,6,4,6));
        UIManager.put("TabbedPane.background",SURFACE);UIManager.put("TabbedPane.selectedBackground",SURFACE);
        UIManager.put("TabbedPane.selectedForeground",INK);UIManager.put("TabbedPane.contentAreaColor",LINE);
        UIManager.put("TabbedPane.hoverColor",SURFACE);UIManager.put("TabbedPane.focusColor",SURFACE);
        UIManager.put("TabbedPane.contentBorderInsets",new Insets(0,0,0,0));UIManager.put("TabbedPane.underlineColor",ACCENT);
        UIManager.put("MenuBar.background",Color.WHITE);UIManager.put("MenuBar.borderColor",LINE);UIManager.put("MenuBar.hoverBackground",new Color(0xeef2f6));
        UIManager.put("PopupMenu.background",Color.WHITE);UIManager.put("PopupMenu.borderColor",new Color(0xd5dde6));UIManager.put("MenuItem.margin",new Insets(5,8,5,8));
        for(String menu:new String[]{"Menu","MenuItem","CheckBoxMenuItem","RadioButtonMenuItem"}){UIManager.put(menu+".selectionBackground",TINT);UIManager.put(menu+".selectionForeground",INK);}
        UIManager.put("MenuItem.acceleratorForeground",MUTED);UIManager.put("MenuItem.acceleratorSelectionForeground",ACCENT_DARK);
        for (Object key : Collections.list(UIManager.getDefaults().keys()))
            if (UIManager.get(key) instanceof javax.swing.plaf.FontUIResource) UIManager.put(key,font);
        UIManager.put("Panel.background",SURFACE); UIManager.put("Label.foreground",INK);UIManager.put("OptionPane.background",SURFACE);
        UIManager.put("TabbedPane.selected",Color.WHITE); UIManager.put("Button.background",Color.WHITE);UIManager.put("Button.borderColor",new Color(0xd5dde6));
        UIManager.put("Button.hoverBackground",new Color(0xf1f5f9));UIManager.put("Button.pressedBackground",new Color(0xe2e8f0));
        UIManager.put("Button.default.background",ACCENT);UIManager.put("Button.default.foreground",Color.WHITE);UIManager.put("Button.default.borderColor",ACCENT_DARK);
        UIManager.put("Button.default.hoverBackground",ACCENT_DARK);UIManager.put("Button.default.focusedBackground",ACCENT);UIManager.put("Button.default.boldText",true);
        UIManager.put("SplitPane.dividerSize",6);UIManager.put("SplitPaneDivider.gripColor",new Color(0xb6c2d0));
        UIManager.put("TextArea.selectionBackground",new Color(0xccefe9));UIManager.put("TextField.selectionBackground",new Color(0xccefe9));UIManager.put("TextPane.selectionBackground",new Color(0xccefe9));
        UIManager.put("ScrollBar.width",10);UIManager.put("ScrollBar.thumbArc",999);UIManager.put("ScrollBar.thumbInsets",new Insets(2,2,2,2));UIManager.put("ScrollBar.showButtons",false);
        UIManager.put("ScrollBar.thumb",new Color(0xc5ceda));UIManager.put("ScrollBar.hoverThumbColor",new Color(0xa7b3c2));UIManager.put("ScrollBar.track",SURFACE);
        UIManager.put("List.selectionBackground",TINT);UIManager.put("List.selectionForeground",INK);UIManager.put("List.selectionInactiveBackground",new Color(0xeef2f6));
        UIManager.put("Table.selectionBackground",TINT);UIManager.put("Table.selectionForeground",INK);UIManager.put("Table.rowHeight",28);UIManager.put("Table.showHorizontalLines",true);UIManager.put("Table.gridColor",new Color(0xeef2f6));
        UIManager.put("TableHeader.background",SURFACE);UIManager.put("TableHeader.separatorColor",LINE);UIManager.put("TableHeader.bottomSeparatorColor",LINE);UIManager.put("TableHeader.height",30);
        UIManager.put("ToolTip.background",new Color(0x1e293b));UIManager.put("ToolTip.foreground",new Color(0xf1f5f9));UIManager.put("ToolTip.border",BorderFactory.createEmptyBorder(6,9,6,9));
        UIManager.put("OptionPane.buttonMinimumWidth",88);UIManager.put("OptionPane.messageAreaBorder",BorderFactory.createEmptyBorder(4,4,8,4));
    }
    /** Accent filled button for the one main action on a surface. */
    static <B extends AbstractButton> B primary(B button){
        button.putClientProperty("FlatLaf.style","background:#0d9488;foreground:#ffffff;hoverBackground:#0f766e;pressedBackground:#115e59;borderColor:#0f766e;focusedBorderColor:#0f766e;font:bold");
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));return button;
    }
    List<Profile> profiles() { return Collections.list(accounts.elements()); }
    void newWindow() {
        GraphicsConfiguration display=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        PointerInfo pointer=MouseInfo.getPointerInfo();if(pointer!=null)display=pointer.getDevice().getDefaultConfiguration();
        JFrame frame = new JFrame("Lagless",display); windows.add(frame);events.record(null,I18n.t("打开工作区窗口","Opened workspace window"));
        frame.setIconImage(appIcon());
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        Rectangle usable=new Rectangle(display.getBounds());Insets insets=Toolkit.getDefaultToolkit().getScreenInsets(display);
        usable.x+=insets.left;usable.y+=insets.top;usable.width-=insets.left+insets.right;usable.height-=insets.top+insets.bottom;
        frame.setContentPane(content(frame)); frame.setSize(Math.min(1440,(int)(usable.width*.92)),Math.min(900,(int)(usable.height*.92)));
        frame.setMinimumSize(new Dimension(Math.min(1000,usable.width),Math.min(650,usable.height)));
        frame.setLocation(usable.x+(usable.width-frame.getWidth())/2,usable.y+(usable.height-frame.getHeight())/2);
        frame.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent event) {
            List<SessionPane> owned = sessions.stream().filter(s -> SwingUtilities.getWindowAncestor(s) == frame).toList();
            if (!owned.isEmpty() && JOptionPane.showConfirmDialog(frame,I18n.t("关闭窗口并断开其中的 ","Close the window and disconnect its ") + owned.size() + I18n.t(" 个连接？日志和草稿会保留。"," connection(s)? Logs and drafts are kept."),I18n.t("关闭窗口","Close window"),JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            owned.forEach(SessionPane::close); windows.remove(frame); frame.dispose();
            events.record(null,I18n.t("关闭工作区窗口","Closed workspace window"));if(windows.isEmpty())close(owned);
        }});
        frame.setVisible(true);
    }
    static Image appIcon(){return appIcon(32);}
    static Image appIcon(int size){
        java.awt.image.BufferedImage icon=new java.awt.image.BufferedImage(size,size,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics=icon.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setPaint(new GradientPaint(0,0,new Color(0x14b8a6),size,size,ACCENT_DARK));graphics.fillRoundRect(0,0,size,size,size*9/32,size*9/32);
        graphics.setColor(Color.WHITE);graphics.setFont(new Font(Font.SANS_SERIF,Font.BOLD,size*23/32));
        FontMetrics metrics=graphics.getFontMetrics();graphics.drawString("L",(size-metrics.stringWidth("L"))/2,(size-metrics.getHeight())/2+metrics.getAscent());
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
            if(!Desktop.isDesktopSupported()||!Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))throw new IOException(I18n.t("无法打开系统浏览器，请访问：","Cannot open the system browser; visit: ")+PROJECT_URL);
            Desktop.getDesktop().browse(java.net.URI.create(PROJECT_URL));return true;
        },opened->events.operation(I18n.t("打开项目 GitHub","Opened project on GitHub")));
    }
    void about(Component parent){
        JPanel info=new JPanel();info.setLayout(new BoxLayout(info,BoxLayout.Y_AXIS));info.setBorder(new EmptyBorder(8,8,8,8));
        JLabel title=new JLabel("Lagless");title.setFont(DesktopFonts.ui().deriveFont(Font.BOLD,26));title.setForeground(INK);info.add(title);info.add(Box.createVerticalStrut(8));
        info.add(new JLabel(I18n.t("版本 ","Version ")+Installation.version()));info.add(new JLabel(I18n.t("剪贴板：","Clipboard: ")+NativeGraphics.clipboardStatus()));info.add(Box.createVerticalStrut(12));info.add(new JLabel(I18n.t("面向高延迟网络的 SSH 工具","SSH for high-latency networks")));
        info.add(new JLabel(I18n.t("本地编辑 · 多连接工作区 · 独立终端核心","Local editing · multi-connection workspace · standalone terminal core")));info.add(Box.createVerticalStrut(16));
        JTextField link=new JTextField(PROJECT_URL);link.setEditable(false);link.setBorder(null);link.setOpaque(false);link.setForeground(ACCENT);info.add(link);
        int action=JOptionPane.showOptionDialog(parent,info,I18n.t("关于 Lagless","About Lagless"),JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,new String[]{I18n.t("打开 GitHub","Open GitHub"),I18n.t("关闭","Close")},I18n.t("打开 GitHub","Open GitHub"));
        if(action==0)openProject(parent);
    }
    void setAutoRemoteWheel(boolean value){preferences.set("wheel-auto-interactive",Boolean.toString(value));sessions.forEach(s->s.view.setAutoRemoteWheel(value));workspaces.values().forEach(w->w.autoRemoteWheel.setSelected(value));}
    void setPageWheelKeys(boolean value){preferences.set("wheel-page-keys",Boolean.toString(value));sessions.forEach(s->s.view.setPageKeysForRemoteWheel(value));workspaces.values().forEach(w->w.pageWheelKeys.setSelected(value));}
    void setRecordFullscreen(boolean value){
        preferences.set("record-fullscreen",Boolean.toString(value));sessions.forEach(s->s.setRecordFullscreen(value));
        workspaces.values().forEach(w->w.recordFullscreen.setSelected(value));
        events.record(null,value?I18n.t("启用全屏交互记录（日志和回滚）","Full-screen interaction recording on (logs and scrollback)"):I18n.t("关闭重复整屏记录，保留正文增量历史","Repeated full-screen recording off; incremental text history kept"));
    }
    void setEditorWrap(boolean wrap){preferences.set("editor-wrap",Boolean.toString(wrap));sessions.forEach(s->s.setEditorWrap(wrap));workspaces.values().forEach(w->w.editorWrap.setSelected(wrap));}

    void open(JTabbedPane tabs, Profile profile) {open(tabs,profile,null);}
    void open(JTabbedPane tabs, Profile profile, String background) {
        try {
            String title=background==null?profile.name():profile.name()+" · "+background;
            SessionPane pane = new SessionPane(this,profile,null,background); sessions.add(pane); tabs.addTab(title,pane); tabs.setSelectedComponent(pane);
            tabs.setToolTipTextAt(tabs.indexOfComponent(pane),profile.user()+"@"+profile.host()+":"+profile.port());
            tabs.setTabComponentAt(tabs.indexOfComponent(pane),new SessionTabHeader(tabs,title,()->{if(JOptionPane.showConfirmDialog(pane,background==null?I18n.t("断开此连接？草稿与日志将保留。","Disconnect this connection? Drafts and logs are kept."):I18n.t("断开此连接？后台会话会继续在服务器上运行，之后可重新连接。","Disconnect? The background session keeps running on the server and you can reconnect later."),I18n.t("关闭连接","Close connection"),JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION){pane.close();tabs.remove(pane);}}));Workspace.updateTabHeaders(tabs);pane.start();
        } catch (Exception e) { error(tabs,e); }
    }
    void edit(Component parent, Profile previous) {
        JTextField name = field(previous==null?"":previous.name()), host = field(previous==null?"":previous.host()), user = field(previous==null?"":previous.user()), port = field(previous==null?"22":Integer.toString(previous.port())), identity = field(previous==null?"":previous.identity());
        JPanel identityRow = new JPanel(new BorderLayout(8,0));
        identityRow.add(identity,BorderLayout.CENTER);
        JButton chooseIdentity = button(I18n.t("选择文件…","Browse…"),()->chooseIdentityFile(identity));
        chooseIdentity.setName("choose-identity-file");
        chooseIdentity.setToolTipText(I18n.t("选择此电脑上的 SSH 私钥文件","Choose an SSH private key on this computer"));
        identityRow.add(chooseIdentity,BorderLayout.EAST);
        List<Profile> terminalChoices;try{terminalChoices=TerminalProfiles.read(home);}catch(Exception e){events.record(null,I18n.t("读取跳板配置失败：","Reading jump host config failed: ")+e.getMessage());terminalChoices=List.of();}
        List<Profile> candidates=profiles();try{TerminalProfiles.merge(candidates,terminalChoices);}catch(IOException e){error(parent,e);return;}
        JComboBox<Object> jump = new JComboBox<>();jump.setName("jump-account");jump.setMaximumRowCount(12); jump.addItem(I18n.t("直连 / OpenSSH ProxyJump","Direct / OpenSSH ProxyJump"));
        candidates.stream().filter(p -> previous==null || !p.id().equals(previous.id())).forEach(jump::addItem);
        if (previous!=null) profiles().stream().filter(p -> p.id().equals(previous.jumpId())).findFirst().ifPresent(jump::setSelectedItem);
        JCheckBox reconnect = new JCheckBox(I18n.t("网络中断后自动重连","Reconnect automatically after network loss"),previous==null || previous.reconnect());
        JCheckBox tmux = new JCheckBox(I18n.t("使用服务器已有 tmux 恢复会话（不安装软件）","Resume with tmux already on the server (installs nothing)"),previous!=null && previous.tmux());
        JPanel form = form(I18n.t("名称","Name"),name,I18n.t("主机 / SSH 别名","Host / SSH alias"),host,I18n.t("用户名（留空读取 SSH 配置）","User (blank reads SSH config)"),user,I18n.t("端口","Port"),port,I18n.t("私钥文件（可选）","Private key (optional)"),identityRow,I18n.t("跳板账号","Jump host"),jump,"",reconnect,"",tmux);
        List<Profile> sourceChoices=terminalChoices;
        while (JOptionPane.showConfirmDialog(parent,form,previous==null?I18n.t("添加 SSH 连接","Add SSH connection"):I18n.t("编辑 SSH 连接","Edit SSH connection"),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION) {
            try {
                List<Profile> next=profiles();String jumpId="";
                if(jump.getSelectedItem() instanceof Profile chosen)jumpId=TerminalProfiles.addJumpChoice(next,sourceChoices,chosen);
                Profile p = new Profile(previous==null?UUID.randomUUID().toString():previous.id(),name.getText().trim(),host.getText().trim(),user.getText().trim(),Integer.parseInt(port.getText().trim()),identity.getText().trim(),jumpId,reconnect.isSelected(),tmux.isSelected(),previous!=null&&previous.host().equals(host.getText().trim())?previous.sshAlias():"");
                int position=-1;if(previous!=null)for(int i=0;i<next.size();i++)if(next.get(i).id().equals(previous.id())){position=i;break;}
                if(position<0)next.add(p);else next.set(position,p);validateRoutes(next);store.save(next);
                accounts.clear(); next.forEach(accounts::addElement);selectProfile(p);events.operation(I18n.t("保存连接：","Saved connection: ")+p.name()); return;
            } catch (Exception e) { error(parent,e); }
        }
    }
    private static void chooseIdentityFile(JTextField identity) {
        JFileChooser picker = new JFileChooser();
        picker.setDialogTitle(I18n.t("选择本地 SSH 私钥文件","Choose a local SSH private key"));
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
            JOptionPane.showMessageDialog(identity,I18n.t("请选择存在的本地私钥文件。","Choose an existing local private key file."),I18n.t("选择私钥","Choose private key"),JOptionPane.WARNING_MESSAGE);
            return;
        }
        identity.setText(selected.toPath().toAbsolutePath().normalize().toString());
        identity.setCaretPosition(0);
    }
    static void validateRoutes(List<Profile> profiles) {
        Map<String,Profile> map = new HashMap<>(); profiles.forEach(p -> {if(map.put(p.id(),p)!=null)throw new IllegalArgumentException(I18n.t("重复的账号 ID，请分次导入账号文件","Duplicate account ID; import account files one at a time"));});
        for (Profile profile : profiles) {
            Set<String> visited = new HashSet<>(); Profile current = profile;
            while (current!=null && !current.jumpId().isBlank()) {
                if (!visited.add(current.id()) || visited.size()>8) throw new IllegalArgumentException(I18n.t("跳板链存在循环或超过 8 跳","Jump host chain loops or exceeds 8 hops"));
                current = map.get(current.jumpId()); if (current==null) throw new IllegalArgumentException(I18n.t("跳板账号不存在","Jump host account does not exist"));
            }
        }
    }
    void reorderProfile(String id,int insertionIndex)throws IOException{
        List<Profile> next=profiles();int from=-1;for(int i=0;i<next.size();i++)if(next.get(i).id().equals(id)){from=i;break;}
        if(from<0)return;int to=Math.max(0,Math.min(insertionIndex,next.size()));if(from<to)to--;
        if(to==from)return;Profile moved=next.remove(from);next.add(to,moved);store.save(next);
        accounts.clear();next.forEach(accounts::addElement);events.operation(I18n.t("调整连接顺序：","Reordered connection: ")+moved.name());
    }
    void remove(Component parent, Profile p) {
        if (p==null) return;
        if (profiles().stream().anyMatch(q -> q.jumpId().equals(p.id()))) { error(parent,new IllegalArgumentException(I18n.t("其他账号正在使用此跳板，请先修改跳板配置","Another account uses this jump host; change its jump host first"))); return; }
        if (JOptionPane.showConfirmDialog(parent,I18n.t("删除账号「","Delete account \"")+p.name()+I18n.t("」？","\"?"),I18n.t("删除账号","Delete account"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return;
        try { List<Profile> next=profiles(); next.remove(p); store.save(next); accounts.removeElement(p); } catch(Exception e) { error(parent,e); }
    }
    void importAccounts(Component parent) {
        JFileChooser picker = new JFileChooser(); picker.setMultiSelectionEnabled(true); picker.setDialogTitle(I18n.t("导入 JSON / OpenSSH config / PuTTY .reg / Xshell .xsh","Import JSON / OpenSSH config / PuTTY .reg / Xshell .xsh"));
        if (picker.showOpenDialog(parent)!=JFileChooser.APPROVE_OPTION) return;
        File[] files = picker.getSelectedFiles();
        background(parent,() -> {
            List<Profile> imported=new ArrayList<>(); for(File file:files) imported.addAll(AccountFormats.read(file.toPath())); return imported;
        }, imported -> {
            try {
                if (imported.isEmpty()) throw new IOException(I18n.t("没有识别到 SSH 账号","No SSH accounts recognized"));
                List<Profile> next=profiles(); ProfileStore.merge(next,imported); validateRoutes(next); store.save(next); accounts.clear(); next.forEach(accounts::addElement);
                JOptionPane.showMessageDialog(parent,I18n.t("已导入 ","Imported ")+imported.size()+I18n.t(" 个账号。厂商加密密码与私钥格式不会自动转换；请按需重新选择私钥。"," account(s). Vendor-encrypted passwords and key formats are not converted; choose private keys again if needed."));
            } catch(Exception e) { error(parent,e); }
        });
    }
    void importTerminal(Component parent){
        background(parent,()->TerminalProfiles.read(home),imported->{try{
            List<Profile> next=profiles();int count=TerminalProfiles.merge(next,imported);store.save(next);accounts.clear();next.forEach(accounts::addElement);
            JOptionPane.showMessageDialog(parent,I18n.t("新增 ","Added ")+count+I18n.t(" 个终端连接；已有连接保留且不会重复导入。\n来源："," terminal connection(s); existing connections are kept and not duplicated.\nSource: ")+TerminalProfiles.source(home));
        }catch(Exception e){error(parent,e);}});
    }
    void exportAccounts(Component parent, Profile selected) {
        if(accounts.isEmpty()){JOptionPane.showMessageDialog(parent,I18n.t("尚无可导出的连接，请先读取终端配置或添加连接。","No connections to export yet; read the terminal config or add a connection first."));return;}
        JComboBox<String> format = new JComboBox<>(new String[]{"json","openssh","reg","xsh"});
        JCheckBox only = new JCheckBox(I18n.t("仅导出选中的账号","Export only the selected account"),selected!=null);
        if (JOptionPane.showConfirmDialog(parent,form(I18n.t("格式","Format"),format,"",only),I18n.t("导出账号（不含密码 / 私钥内容）","Export accounts (no passwords or key contents)"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return;
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
        Object choice=JOptionPane.showInputDialog(parent,I18n.t("完整备份包含配置、账号、日志、草稿、插件、~/.ssh 文件和当前应用程序包。\n备份以密码加密；外部路径私钥需另行保存。","A full backup contains settings, accounts, logs, drafts, plugins, ~/.ssh files and the current application package.\nThe backup is password-encrypted; keys stored outside these folders must be saved separately."), I18n.t("备份 / 恢复","Backup / restore"),JOptionPane.PLAIN_MESSAGE,null,new String[]{I18n.t("创建加密备份","Create encrypted backup"),I18n.t("恢复加密备份","Restore encrypted backup")},I18n.t("创建加密备份","Create encrypted backup"));
        if(choice==null)return;
        boolean restore=choice.toString().startsWith(I18n.t("恢复","Restore"));
        if(restore && !sessions.isEmpty()) {error(parent,new IllegalStateException(I18n.t("恢复前请关闭所有窗口中的连接标签","Close the connection tabs in every window before restoring")));return;}
        Path archive;
        if(restore) { JFileChooser picker=new JFileChooser();picker.setDialogTitle(I18n.t("选择已完成的 Lagless 备份（.llbackup）","Choose a finished Lagless backup (.llbackup)"));
            picker.setAcceptAllFileFilterUsed(false);picker.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(I18n.t("Lagless 完整备份 (*.llbackup)","Lagless full backup (*.llbackup)"),"llbackup"));
            if(picker.showOpenDialog(parent)!=JFileChooser.APPROVE_OPTION)return;archive=picker.getSelectedFile().toPath();
            if(!archive.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".llbackup")||!Files.isRegularFile(archive)){error(parent,new IOException(I18n.t("请选择已完成的 .llbackup 备份文件；创建失败时产生的 .tmp 临时文件不能恢复","Choose a finished .llbackup file; .tmp files left by a failed backup cannot be restored")));return;}}
        else {archive=chooseSave(parent,"lagless-"+java.time.LocalDate.now()+".llbackup");if(archive==null)return;}
        JPasswordField password=new JPasswordField(24), repeat=new JPasswordField(24);
        JCheckBox restoreApp=new JCheckBox(I18n.t("同时恢复相同类型的应用程序包","Also restore the application package of the same type"),true);
        JPanel form=restore?form(I18n.t("备份密码","Backup password"),password,I18n.t("应用程序","Application"),restoreApp):form(I18n.t("密码（至少 10 位）","Password (at least 10 characters)"),password,I18n.t("确认密码","Confirm password"),repeat);
        if(JOptionPane.showConfirmDialog(parent,form,restore?I18n.t("恢复并覆盖同名数据，完成后需重启","Restore and overwrite matching data (restart required)"):I18n.t("加密备份","Encrypted backup"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        char[] secret=password.getPassword(), again=repeat.getPassword();
        if(!restore && !Arrays.equals(secret,again)) {Arrays.fill(secret,'\0');Arrays.fill(again,'\0');error(parent,new IllegalArgumentException(I18n.t("两次密码不一致","Passwords do not match")));return;}
        Arrays.fill(again,'\0');
        List<SessionPane> active=List.copyOf(sessions);
        if(restore) {
            restoring=true;
            boolean includeApplication=restoreApp.isSelected();
            Window owner=parent instanceof Window w?w:SwingUtilities.getWindowAncestor(parent);
            JDialog loading=new JDialog(owner,I18n.t("正在恢复备份","Restoring backup"),Dialog.ModalityType.APPLICATION_MODAL);
            loading.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            JPanel progressPanel=new JPanel(new BorderLayout(12,14));progressPanel.setBorder(new EmptyBorder(20,22,20,22));
            JLabel status=new JLabel(I18n.t("正在校验并恢复数据，请稍候…","Verifying and restoring data, please wait…"));JProgressBar progress=new JProgressBar();progress.setIndeterminate(true);
            progressPanel.add(status,BorderLayout.NORTH);progressPanel.add(progress,BorderLayout.CENTER);
            loading.setContentPane(progressPanel);loading.setResizable(false);loading.pack();loading.setLocationRelativeTo(parent);
            workers.submit(()->{
                Exception failure=null;
                try {BackupArchive.restore(archive,home.getParent(),secret,includeApplication?DesktopRuntime.applicationBackupSource():null);
                    events.operation(I18n.t("恢复备份完成；备份源文件保留：","Backup restored; source file kept: ")+archive);}
                catch(Exception e){failure=e;events.error(I18n.t("恢复备份","Restore backup"),e);}
                finally {Arrays.fill(secret,'\0');}
                Exception result=failure;
                SwingUtilities.invokeLater(()->{restoring=false;loading.dispose();
                    if(result!=null)error(parent,result);
                    else JOptionPane.showMessageDialog(parent,I18n.t("恢复完成，备份文件仍保留：","Restore finished; the backup file is kept: ")+archive+I18n.t("\n请关闭全部窗口并重新启动，以载入恢复的数据。","\nClose all windows and restart to load the restored data."),I18n.t("恢复完成","Restore finished"),JOptionPane.INFORMATION_MESSAGE);
                });
            });
            loading.setVisible(true);
            return;
        }
        background(parent,() -> {try {for(SessionPane s:active)s.flushJournal();events.force();localCommands.force();BackupArchive.create(home.getParent(),archive,secret,DesktopRuntime.applicationBackupSource());
            events.operation(I18n.t("创建备份完成：","Backup created: ")+archive);return true;}
            finally {Arrays.fill(secret,'\0');}}, done -> JOptionPane.showMessageDialog(parent,I18n.t("备份已保存：","Backup saved: ")+archive));
    }
    private UpdateDialog updateDialog;
    private volatile Thread shutdownThread;
    void update(Component parent){if(updateDialog==null||!updateDialog.isDisplayable())updateDialog=new UpdateDialog(this,parent,checkUpdatesOnOpen);updateDialog.show();}
    void restartForUpdate(Component parent){
        if(!sessions.isEmpty()&&JOptionPane.showConfirmDialog(parent,I18n.t("重启将断开当前的 ","Restarting will disconnect the current ")+sessions.size()+I18n.t(" 个连接，日志与草稿会保留。继续？"," connection(s); logs and drafts are kept. Continue?"),I18n.t("重启并应用更新","Restart and apply update"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        background(parent,()->{NativeUpdater.requestRestart();return true;},ready->close());
    }
    private void finishExit(List<SessionPane> draining){
        Thread shutdown=new Thread(()->{
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            try{for(SessionPane session:draining)session.awaitClosed(deadline);localCommands.awaitClosed(deadline);workers.awaitTermination(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
            catch(InterruptedException error){Thread.currentThread().interrupt();events.error(I18n.t("退出等待","Waiting to exit"),error);}
            finally{events.record(null,I18n.t("程序退出","Exited"));events.close();}
            if(exitProcessOnClose&&DesktopRuntime.nativeImage())System.exit(0);
        },"lagless-native-shutdown");shutdownThread=shutdown;shutdown.start();
    }
    boolean awaitShutdown(long deadline)throws InterruptedException{Thread shutdown=shutdownThread;if(shutdown==null)return true;long remaining=deadline-System.nanoTime();if(remaining>0)shutdown.join(Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)));return !shutdown.isAlive();}
    void collaborate(Component parent) {
        if(sessions.isEmpty()) {JOptionPane.showMessageDialog(parent,I18n.t("请先建立连接。各会话上下文默认相互隔离。","Connect first. Session contexts are isolated by default."));return;}
        JList<SessionPane> list=new JList<>(sessions.toArray(SessionPane[]::new)); list.setVisibleRowCount(6);
        JTextArea command=textArea(); command.setRows(3);
        JPanel form=new JPanel(new BorderLayout(8,8)); form.add(new JLabel(I18n.t("选择目标后审阅命令。只发送到已就绪的 shell；不会自动重放。","Choose targets, then review the command. It is sent only to ready shells and never replayed automatically.")),BorderLayout.NORTH);form.add(new EditorScrollPane(list),BorderLayout.CENTER);form.add(new EditorScrollPane(command),BorderLayout.SOUTH);
        if(JOptionPane.showConfirmDialog(parent,form,I18n.t("多 SSH 协作 · 命令分发","Multi-SSH collaboration · broadcast command"),JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION) {
            List<SessionPane> targets=list.getSelectedValuesList();String text=command.getText();
            if(targets.isEmpty())return;
            if(targets.stream().anyMatch(s -> !s.canSend())) {error(parent,new IllegalStateException(I18n.t("部分连接未处于本地 shell 编辑模式，未发送任何命令","Some connections are not in local shell editing mode; nothing was sent")));return;}
            targets.forEach(s -> s.sendCommand(text));
        }
    }
    void recoverDraft(Component parent) {
        JFileChooser picker=new JFileChooser(home.resolve("desktop/drafts").toFile());if(picker.showOpenDialog(parent)!=JFileChooser.APPROVE_OPTION)return;
        try {JTextArea text=textArea();text.setText(Files.readString(picker.getSelectedFile().toPath()));JDialog dialog=dialog(parent,I18n.t("恢复的本地草稿","Recovered local draft"),text,800,500);dialog.setVisible(true);}catch(Exception e){error(parent,e);}
    }
    void browse(Path dir, Component parent) {try{Files.createDirectories(dir);Desktop.getDesktop().open(dir.toFile());}catch(Exception e){error(parent,e);}}
    <T> void background(Component parent, Callable<T> action, Consumer<T> result) {
        workers.submit(() -> {try{T value=action.call();SwingUtilities.invokeLater(() -> result.accept(value));}catch(Exception e){events.error(I18n.t("后台操作","Background task"),e);SwingUtilities.invokeLater(() -> error(parent,e));}});
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
    static Path chooseSave(Component parent, String name) {JFileChooser picker=new JFileChooser();picker.setSelectedFile(new File(name));if(picker.showSaveDialog(parent)!=JFileChooser.APPROVE_OPTION)return null;Path path=picker.getSelectedFile().toPath();if(Files.exists(path)&&JOptionPane.showConfirmDialog(parent,I18n.t("覆盖「","Overwrite \"")+path.getFileName()+I18n.t("」？","\"?"),I18n.t("文件已存在","File exists"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return null;return path;}
    static JDialog dialog(Component parent, String title, JTextArea text, int width, int height) {JDialog dialog=new JDialog(parent instanceof Window w?w:SwingUtilities.getWindowAncestor(parent),title,Dialog.ModalityType.MODELESS);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.setLayout(new BorderLayout());dialog.add(new EditorScrollPane(text),BorderLayout.CENTER);dialog.setSize(width,height);dialog.setLocationRelativeTo(parent);return dialog;}
    static void error(Component parent, Throwable e) {JOptionPane.showMessageDialog(parent,e.getMessage()==null?e.toString():e.getMessage(),"Lagless",JOptionPane.ERROR_MESSAGE);}
    @Override public void close(){close(List.of());}
    private void close(List<SessionPane> closing){if(closed)return;closed=true;if(updateDialog!=null)updateDialog.dispose();Set<SessionPane> draining=new LinkedHashSet<>(closing);draining.addAll(sessions);for(SessionPane pane:List.copyOf(sessions))pane.close();Set<Window> owners=Collections.newSetFromMap(new IdentityHashMap<>());owners.addAll(windows);for(Workspace workspace:workspaces.values()){Window owner=SwingUtilities.getWindowAncestor(workspace.root);if(owner!=null)owners.add(owner);}owners.forEach(Window::dispose);windows.clear();workspaces.clear();events.record(null,I18n.t("程序正在退出，等待连接关闭及日志落盘","Exiting; waiting for connections to close and logs to flush"));plugins.close();localCommands.close();workers.shutdown();finishExit(List.copyOf(draining));}
}
