package dev.lagless.desktop;

import dev.lagless.I18n;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;

/** A terminal-first window. Connection management is a transient library, not a sidebar. */
final class Workspace {
    final DesktopApp app;final JFrame owner;final JPanel root=new JPanel(new BorderLayout());
    final JTabbedPane tabs=new JTabbedPane();final JComboBox<Profile> quick=new JComboBox<>();
    final JCheckBoxMenuItem editorWrap=new JCheckBoxMenuItem(I18n.t("自动换行","Wrap lines"));
    final JCheckBoxMenuItem recordFullscreen=new JCheckBoxMenuItem(I18n.t("保存原始远端输出","Save raw remote output"));
    final JCheckBoxMenuItem autoRemoteWheel=new JCheckBoxMenuItem(I18n.t("交互程序中用滚轮滚动内容","Scroll interactive programs with the wheel"));
    final JCheckBoxMenuItem pageWheelKeys=new JCheckBoxMenuItem(I18n.t("交互程序滚轮使用 PageUp / PageDown","Use PageUp / PageDown for the wheel in interactive programs"));
    private final java.util.List<JMenuItem> sessionItems=new ArrayList<>(),profileItems=new ArrayList<>();
    private final JCheckBoxMenuItem passThrough=new JCheckBoxMenuItem(I18n.t("兼容模式：直接传按键（当前连接）","Compatibility: send keys directly (this connection)"));
    private String selectedId="";private boolean syncing;
    Workspace(DesktopApp app,JFrame owner){
        this.app=app;this.owner=owner;root.setName("workspace");root.add(menu(),BorderLayout.NORTH);tabs.setName("sessions");tabs.addTab(I18n.t("起始页","Start"),welcome());root.add(tabs,BorderLayout.CENTER);
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);tabs.setTabComponentAt(0,new SessionTabHeader(tabs,I18n.t("起始页","Start"),null));updateTabHeaders(tabs);
        JLabel foot=new JLabel(I18n.t("  ● 本地优先    ·    输入不等待网络                         ","  ● Local first    ·    typing never waits for the network                          ")+dev.lagless.Installation.version());foot.setFont(DesktopFonts.ui().deriveFont(11f));foot.setForeground(DesktopApp.MUTED);foot.setOpaque(true);foot.setBackground(Color.WHITE);foot.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,0,0,0,DesktopApp.LINE),new EmptyBorder(5,8,5,8)));root.add(foot,BorderLayout.SOUTH);
        quick.setName("quick-connect");quick.addActionListener(e->{if(!syncing&&quick.getSelectedItem() instanceof Profile p)selectedId=p.id();updateActions();});tabs.addChangeListener(e->updateActions());
        ListDataListener listener=new ListDataListener(){private boolean pending;private void changed(){if(pending)return;pending=true;SwingUtilities.invokeLater(()->{pending=false;syncAccounts();});}public void intervalAdded(ListDataEvent e){changed();}public void intervalRemoved(ListDataEvent e){changed();}public void contentsChanged(ListDataEvent e){changed();}};
        app.accounts.addListDataListener(listener);if(owner!=null)owner.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){app.accounts.removeListDataListener(listener);app.workspaces.remove(owner);}});syncAccounts();
    }
    private JMenuBar menu(){
        JMenuBar bar=new JMenuBar();bar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,DesktopApp.LINE),new EmptyBorder(5,10,5,8)));
        JMenu connections=new JMenu(I18n.t("连接","Connections"));bar.add(connections);
        add(connections,I18n.t("连接库","Connection library"),()->new ConnectionLibrary(this),KeyStroke.getKeyStroke(KeyEvent.VK_L,InputEvent.CTRL_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK));
        add(connections,I18n.t("添加","Add"),()->app.edit(owner,null),KeyStroke.getKeyStroke(KeyEvent.VK_N,InputEvent.CTRL_DOWN_MASK));
        profileItems.add(add(connections,I18n.t("编辑","Edit"),()->app.edit(owner,currentProfile()),null));profileItems.add(add(connections,I18n.t("删除","Delete"),()->app.remove(owner,currentProfile()),null));
        connections.addSeparator();add(connections,I18n.t("导入账号","Import"),()->app.importAccounts(owner),null);add(connections,I18n.t("导出账号","Export"),()->app.exportAccounts(owner,currentProfile()),null);add(connections,I18n.t("读取终端配置","Read terminal config"),()->app.importTerminal(owner),null);
        connections.addSeparator();add(connections,I18n.t("新窗口","New window"),app::newWindow,KeyStroke.getKeyStroke(KeyEvent.VK_N,InputEvent.CTRL_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK));
        JMenu session=new JMenu(I18n.t("会话","Session"));bar.add(session);
        sessionItems.add(add(session,I18n.t("文件管理","Files"),()->active().showFiles(),KeyStroke.getKeyStroke(KeyEvent.VK_E,InputEvent.CTRL_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK)));
        passThrough.setToolTipText(I18n.t("仅供未被自动识别的交互程序排查兼容问题。Codex / Claude 通常已自动透传；开启后每个按键需等待网络，可能更慢。","Only for troubleshooting interactive programs that are not detected automatically. Codex / Claude are usually passed through already; when on, every key waits for the network and may be slower."));
        passThrough.addActionListener(e->{SessionPane target=active();boolean enabled=passThrough.isSelected();if(target!=null)SwingUtilities.invokeLater(()->{target.setPassThrough(enabled);passThrough.setSelected(target.passThrough());});});session.add(passThrough);sessionItems.add(passThrough);
        sessionItems.add(add(session,I18n.t("后台会话…","Background sessions…"),()->BackgroundSessions.show(active()),KeyStroke.getKeyStroke(KeyEvent.VK_B,InputEvent.CTRL_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK)));
        sessionItems.add(add(session,I18n.t("重新连接","Reconnect"),()->active().reconnect(),null));sessionItems.add(add(session,I18n.t("断开连接","Disconnect"),()->active().disconnect(),null));
        sessionItems.add(add(session,I18n.t("导出当前日志","Export current log"),()->active().exportLog(),null));session.addSeparator();
        sessionItems.add(add(session,I18n.t("关闭标签","Close tab"),()->closeSession(active()),KeyStroke.getKeyStroke(KeyEvent.VK_W,InputEvent.CTRL_DOWN_MASK)));
        JMenu view=new JMenu(I18n.t("视图","View"));bar.add(view);JMenu editor=new JMenu(I18n.t("本地编辑器","Local editor"));view.add(editor);ButtonGroup placement=new ButtonGroup();
        for(String where:new String[]{"bottom","right"}){JRadioButtonMenuItem item=new JRadioButtonMenuItem(where.equals("bottom")?I18n.t("底部单行","Bottom line"):I18n.t("右侧编辑","Right side"),app.preferences.get("editor-position","bottom").equals(where));placement.add(item);editor.add(item);item.addActionListener(e->{app.preferences.set("editor-position",where);app.sessions.forEach(s->s.setEditorPlacement(where));});}
        editorWrap.setSelected(Boolean.parseBoolean(app.preferences.get("editor-wrap","false")));editorWrap.addActionListener(e->{boolean selected=editorWrap.isSelected();SwingUtilities.invokeLater(()->app.setEditorWrap(selected));});editor.add(editorWrap);editor.addSeparator();sessionItems.add(add(editor,I18n.t("独立编辑窗口","Separate window"),()->active().detachEditor(),null));
        sessionItems.add(add(view,I18n.t("输出镜像","Output mirror"),()->active().showTool("mirror"),null));sessionItems.add(add(view,I18n.t("AI助手","AI assistant"),()->active().showTool("assistant"),null));
        autoRemoteWheel.setSelected(Boolean.parseBoolean(app.preferences.get("wheel-auto-interactive","true")));
        autoRemoteWheel.setToolTipText(I18n.t("仅在识别到交互程序时把滚轮交给远端；普通 shell 始终滚动 Lagless 的终端历史。Shift+滚轮也滚动本地历史。","Hand the wheel to the remote side only when an interactive program is detected; a normal shell always scrolls Lagless terminal history. Shift+wheel also scrolls local history."));
        autoRemoteWheel.addActionListener(e->{boolean selected=autoRemoteWheel.isSelected();SwingUtilities.invokeLater(()->app.setAutoRemoteWheel(selected));});view.add(autoRemoteWheel);
        pageWheelKeys.setSelected(Boolean.parseBoolean(app.preferences.get("wheel-page-keys","false")));
        pageWheelKeys.setToolTipText(I18n.t("开启后，需同时启用「交互程序中用滚轮滚动内容」，交互程序中的滚轮才会向远端发送 PageUp / PageDown；关闭时使用远端鼠标协议或逐行方向键。普通 shell 仍滚动本地历史。","When on, and \"Scroll interactive programs with the wheel\" is also on, the wheel sends PageUp / PageDown to interactive programs; when off, the remote mouse protocol or arrow keys are used. A normal shell still scrolls local history."));
        pageWheelKeys.addActionListener(e->{boolean selected=pageWheelKeys.isSelected();SwingUtilities.invokeLater(()->app.setPageWheelKeys(selected));});view.add(pageWheelKeys);
        add(view,I18n.t("显示渲染…","Display rendering…"),()->NativeGraphics.settings(owner,app.preferences),null);
        recordFullscreen.setSelected(Boolean.parseBoolean(app.preferences.get("record-fullscreen","true")));
        recordFullscreen.setToolTipText(I18n.t("开启后，全量输入输出历史还会保存完整原始远端控制流；终端历史始终只保存普通命令行内容。","When on, the full input/output history also keeps the raw remote control stream; terminal history always keeps plain command-line content only."));
        recordFullscreen.addActionListener(e->{boolean selected=recordFullscreen.isSelected();SwingUtilities.invokeLater(()->app.setRecordFullscreen(selected));});view.add(recordFullscreen);
        view.addSeparator();add(view,I18n.t("终端历史","Terminal history"),()->new LogViewer(app,owner,0,null),null);add(view,I18n.t("全量输入输出历史","Full input/output history"),()->app.showLogs(owner,false,null),KeyStroke.getKeyStroke(KeyEvent.VK_H,InputEvent.CTRL_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK));add(view,I18n.t("软件日志","Application log"),()->app.showLogs(owner,true,null),null);add(view,I18n.t("恢复草稿","Recover draft"),()->app.recoverDraft(owner),null);add(view,I18n.t("日志目录","Log folder"),()->app.browse(app.home.resolve("logs"),owner),null);
        JMenu tools=new JMenu(I18n.t("工具","Tools"));bar.add(tools);add(tools,I18n.t("协作","Collaborate"),()->app.collaborate(owner),null);add(tools,I18n.t("AI 设置","AI settings"),()->app.assistantSettings(owner),null);add(tools,I18n.t("插件","Plugins"),()->app.pluginInfo(owner),null);tools.addSeparator();add(tools,I18n.t("备份 / 恢复","Backup / restore"),()->app.backupMenu(owner),null);add(tools,I18n.t("更新","Updates"),()->app.update(owner),null);
        JMenu about=new JMenu(I18n.t("关于","About"));bar.add(about);add(about,I18n.t("GitHub 项目","GitHub project"),()->app.openProject(owner),null);add(about,I18n.t("关于 Lagless","About Lagless"),()->app.about(owner),null);about.addSeparator();add(about,"Language / 语言…",()->LanguageDialog.change(owner,app.preferences),null);
        bar.add(Box.createHorizontalGlue());JLabel hint=new JLabel(I18n.t("SSH 工作区","SSH workspace"));hint.setForeground(DesktopApp.MUTED);hint.setBorder(new EmptyBorder(0,8,0,8));bar.add(hint);return bar;
    }
    static JMenuItem add(JMenu menu,String label,Runnable action,KeyStroke shortcut){JMenuItem item=new JMenuItem(label);item.setName(label);if(shortcut!=null)item.setAccelerator(shortcut);item.addActionListener(e->{MenuSelectionManager.defaultManager().clearSelectedPath();SwingUtilities.invokeLater(action::run);});menu.add(item);return item;}
    private JPanel welcome(){
        JPanel background=new JPanel(new GridBagLayout()){@Override protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();try{g.setPaint(new GradientPaint(0,0,new Color(0xf8fafc),0,getHeight(),new Color(0xecf3f6)));g.fillRect(0,0,getWidth(),getHeight());}finally{g.dispose();}}};
        JPanel card=new RoundedCard();card.setLayout(new BoxLayout(card,BoxLayout.Y_AXIS));card.setBorder(new EmptyBorder(40,48,40,48));
        JLabel logo=new JLabel(new ImageIcon(DesktopApp.appIcon(44)));card.add(logo);card.add(Box.createVerticalStrut(18));
        JLabel eyebrow=new JLabel("READY WHEN YOU ARE");eyebrow.setForeground(DesktopApp.ACCENT);eyebrow.setFont(DesktopFonts.ui().deriveFont(Font.BOLD,11));card.add(eyebrow);card.add(Box.createVerticalStrut(14));
        JLabel title=new JLabel(I18n.t("从一个连接开始","Start with a connection"));title.setFont(DesktopFonts.ui().deriveFont(Font.BOLD,28));title.setForeground(DesktopApp.INK);card.add(title);card.add(Box.createVerticalStrut(10));JLabel description=new JLabel(I18n.t("本地编辑、顺畅输入。让终端跟上你的思路。","Edit locally, type smoothly. Let the terminal keep up with your thinking."));description.setForeground(DesktopApp.MUTED);card.add(description);card.add(Box.createVerticalStrut(28));
        JPanel choose=new JPanel(new BorderLayout(8,0));choose.setOpaque(false);quick.setPreferredSize(new Dimension(330,38));choose.add(quick,BorderLayout.CENTER);choose.add(DesktopApp.button(I18n.t("连接","Connect"),()->open(currentProfile())),BorderLayout.EAST);choose.setMaximumSize(new Dimension(470,40));card.add(choose);card.add(Box.createVerticalStrut(16));
        JButton library=DesktopApp.button(I18n.t("管理连接  ·  Ctrl+Shift+L","Manage connections  ·  Ctrl+Shift+L"),()->new ConnectionLibrary(this));
        DesktopApp.primary(library);library.setMargin(new Insets(10,22,10,22));
        card.add(library);
        card.add(Box.createVerticalStrut(18));JLabel note=new JLabel(I18n.t("连接库随用随开，终端始终是主角。","Open the connection library when you need it; the terminal stays center stage."));note.setForeground(DesktopApp.MUTED);note.setFont(DesktopFonts.ui().deriveFont(12f));card.add(note);
        // BoxLayout aligns children on a shared axis. Mixing the labels' and
        // button's left alignment with the connection row's center shifts the
        // row sideways and unnecessarily widens the card, especially at HiDPI.
        for(Component child:card.getComponents())if(child instanceof JComponent component)component.setAlignmentX(Component.CENTER_ALIGNMENT);
        background.add(card);return background;
    }
    void syncAccounts(){if(owner!=null&&!owner.isDisplayable()&&!app.windows.contains(owner))return;syncing=true;try{quick.removeAllItems();app.profiles().forEach(quick::addItem);Profile chosen=app.profiles().stream().filter(p->p.id().equals(selectedId)).findFirst().orElse(app.accounts.isEmpty()?null:app.accounts.firstElement());if(chosen!=null){quick.setSelectedItem(chosen);selectedId=chosen.id();}}finally{syncing=false;}updateActions();}
    void selectProfile(Profile profile){if(profile!=null){selectedId=profile.id();syncAccounts();}}
    Profile currentProfile(){SessionPane pane=active();return pane!=null?pane.profile:app.profiles().stream().filter(p->p.id().equals(selectedId)).findFirst().orElse(null);}
    SessionPane active(){return tabs.getSelectedComponent() instanceof SessionPane pane?pane:null;}
    static void updateTabHeaders(JTabbedPane tabs){for(int i=0;i<tabs.getTabCount();i++)if(tabs.getTabComponentAt(i) instanceof SessionTabHeader header)header.update(i==tabs.getSelectedIndex());}
    private void updateActions(){updateTabHeaders(tabs);boolean connected=active()!=null;passThrough.setSelected(connected&&active().passThrough());sessionItems.forEach(i->i.setEnabled(connected));profileItems.forEach(i->i.setEnabled(currentProfile()!=null));}
    void open(Profile profile){if(profile==null||app.isRestoring())return;selectProfile(profile);app.open(tabs,profile);updateActions();}
    void closeSession(SessionPane pane){if(pane==null)return;if(JOptionPane.showConfirmDialog(root,I18n.t("关闭此会话？草稿和日志会保留。","Close this session? Drafts and logs are kept."),I18n.t("关闭连接","Close connection"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;pane.close();tabs.remove(pane);updateActions();}
    /** White card with a soft shadow, drawn so it needs no translucent window. */
    private static final class RoundedCard extends JPanel {
        RoundedCard(){setOpaque(false);}
        @Override public Insets getInsets(){Insets inner=super.getInsets();return new Insets(inner.top+6,inner.left+6,inner.bottom+10,inner.right+6);}
        @Override protected void paintComponent(Graphics graphics){
            Graphics2D g=(Graphics2D)graphics.create();try{
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                int w=getWidth()-12,h=getHeight()-16;
                for(int i=6;i>0;i--){g.setColor(new Color(15,23,42,5));g.fillRoundRect(6-i,6-i+3,w+2*i,h+2*i,22+i,22+i);}
                g.setColor(Color.WHITE);g.fillRoundRect(6,6,w,h,20,20);
                g.setColor(DesktopApp.LINE);g.drawRoundRect(6,6,w,h,20,20);
            }finally{g.dispose();}
        }
    }
}
