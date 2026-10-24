package dev.lagless.desktop;

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
    final JCheckBoxMenuItem editorWrap=new JCheckBoxMenuItem("自动换行");
    final JCheckBoxMenuItem recordFullscreen=new JCheckBoxMenuItem("保存原始远端输出");
    final JCheckBoxMenuItem autoRemoteWheel=new JCheckBoxMenuItem("交互程序中用滚轮滚动内容");
    final JCheckBoxMenuItem pageWheelKeys=new JCheckBoxMenuItem("交互程序滚轮使用 PageUp / PageDown");
    private final java.util.List<JMenuItem> sessionItems=new ArrayList<>(),profileItems=new ArrayList<>();
    private final JCheckBoxMenuItem passThrough=new JCheckBoxMenuItem("兼容模式：直接传按键（当前连接）");
    private String selectedId="";private boolean syncing;
    Workspace(DesktopApp app,JFrame owner){
        this.app=app;this.owner=owner;root.setName("workspace");root.add(menu(),BorderLayout.NORTH);tabs.setName("sessions");tabs.addTab("起始页",welcome());root.add(tabs,BorderLayout.CENTER);
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);tabs.setTabComponentAt(0,new SessionTabHeader(tabs,"起始页",null));updateTabHeaders(tabs);
        JLabel foot=new JLabel("  ● 本地优先    ·    输入不等待网络                         "+dev.lagless.Installation.version());foot.setFont(DesktopFonts.ui().deriveFont(11f));foot.setForeground(DesktopApp.MUTED);foot.setBorder(new EmptyBorder(6,8,6,8));root.add(foot,BorderLayout.SOUTH);
        quick.setName("quick-connect");quick.addActionListener(e->{if(!syncing&&quick.getSelectedItem() instanceof Profile p)selectedId=p.id();updateActions();});tabs.addChangeListener(e->updateActions());
        ListDataListener listener=new ListDataListener(){private boolean pending;private void changed(){if(pending)return;pending=true;SwingUtilities.invokeLater(()->{pending=false;syncAccounts();});}public void intervalAdded(ListDataEvent e){changed();}public void intervalRemoved(ListDataEvent e){changed();}public void contentsChanged(ListDataEvent e){changed();}};
        app.accounts.addListDataListener(listener);if(owner!=null)owner.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){app.accounts.removeListDataListener(listener);app.workspaces.remove(owner);}});syncAccounts();
    }
    private JMenuBar menu(){
        JMenuBar bar=new JMenuBar();bar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,new Color(0xe4e8ec)),new EmptyBorder(5,10,5,8)));
        JMenu connections=new JMenu("连接");bar.add(connections);
        add(connections,"连接库",()->new ConnectionLibrary(this),KeyStroke.getKeyStroke(KeyEvent.VK_L,InputEvent.CTRL_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK));
        add(connections,"添加",()->app.edit(owner,null),KeyStroke.getKeyStroke(KeyEvent.VK_N,InputEvent.CTRL_DOWN_MASK));
        profileItems.add(add(connections,"编辑",()->app.edit(owner,currentProfile()),null));profileItems.add(add(connections,"删除",()->app.remove(owner,currentProfile()),null));
        connections.addSeparator();add(connections,"导入账号",()->app.importAccounts(owner),null);add(connections,"导出账号",()->app.exportAccounts(owner,currentProfile()),null);add(connections,"读取终端配置",()->app.importTerminal(owner),null);
        connections.addSeparator();add(connections,"新窗口",app::newWindow,KeyStroke.getKeyStroke(KeyEvent.VK_N,InputEvent.CTRL_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK));
        JMenu session=new JMenu("会话");bar.add(session);
        sessionItems.add(add(session,"文件管理",()->active().showFiles(),KeyStroke.getKeyStroke(KeyEvent.VK_E,InputEvent.CTRL_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK)));
        passThrough.setToolTipText("仅供未被自动识别的交互程序排查兼容问题。Codex / Claude 通常已自动透传；开启后每个按键需等待网络，可能更慢。");
        passThrough.addActionListener(e->{SessionPane target=active();boolean enabled=passThrough.isSelected();if(target!=null)SwingUtilities.invokeLater(()->{target.setPassThrough(enabled);passThrough.setSelected(target.passThrough());});});session.add(passThrough);sessionItems.add(passThrough);
        sessionItems.add(add(session,"重新连接",()->active().reconnect(),null));sessionItems.add(add(session,"断开连接",()->active().disconnect(),null));
        sessionItems.add(add(session,"导出当前日志",()->active().exportLog(),null));session.addSeparator();
        sessionItems.add(add(session,"关闭标签",()->closeSession(active()),KeyStroke.getKeyStroke(KeyEvent.VK_W,InputEvent.CTRL_DOWN_MASK)));
        JMenu view=new JMenu("视图");bar.add(view);JMenu editor=new JMenu("本地编辑器");view.add(editor);ButtonGroup placement=new ButtonGroup();
        for(String where:new String[]{"bottom","right"}){JRadioButtonMenuItem item=new JRadioButtonMenuItem(where.equals("bottom")?"底部单行":"右侧编辑",app.preferences.get("editor-position","bottom").equals(where));placement.add(item);editor.add(item);item.addActionListener(e->{app.preferences.set("editor-position",where);app.sessions.forEach(s->s.setEditorPlacement(where));});}
        editorWrap.setSelected(Boolean.parseBoolean(app.preferences.get("editor-wrap","false")));editorWrap.addActionListener(e->{boolean selected=editorWrap.isSelected();SwingUtilities.invokeLater(()->app.setEditorWrap(selected));});editor.add(editorWrap);editor.addSeparator();sessionItems.add(add(editor,"独立编辑窗口",()->active().detachEditor(),null));
        sessionItems.add(add(view,"输出镜像",()->active().showTool("mirror"),null));sessionItems.add(add(view,"AI助手",()->active().showTool("assistant"),null));
        autoRemoteWheel.setSelected(Boolean.parseBoolean(app.preferences.get("wheel-auto-interactive","true")));
        autoRemoteWheel.setToolTipText("仅在识别到交互程序时把滚轮交给远端；普通 shell 始终滚动 Lagless 的终端历史。Shift+滚轮也滚动本地历史。");
        autoRemoteWheel.addActionListener(e->{boolean selected=autoRemoteWheel.isSelected();SwingUtilities.invokeLater(()->app.setAutoRemoteWheel(selected));});view.add(autoRemoteWheel);
        pageWheelKeys.setSelected(Boolean.parseBoolean(app.preferences.get("wheel-page-keys","false")));
        pageWheelKeys.setToolTipText("开启后，需同时启用「交互程序中用滚轮滚动内容」，交互程序中的滚轮才会向远端发送 PageUp / PageDown；关闭时使用远端鼠标协议或逐行方向键。普通 shell 仍滚动本地历史。");
        pageWheelKeys.addActionListener(e->{boolean selected=pageWheelKeys.isSelected();SwingUtilities.invokeLater(()->app.setPageWheelKeys(selected));});view.add(pageWheelKeys);
        add(view,"显示渲染…",()->NativeGraphics.settings(owner,app.preferences),null);
        recordFullscreen.setSelected(Boolean.parseBoolean(app.preferences.get("record-fullscreen","true")));
        recordFullscreen.setToolTipText("开启后，全量输入输出历史还会保存完整原始远端控制流；终端历史始终只保存普通命令行内容。");
        recordFullscreen.addActionListener(e->{boolean selected=recordFullscreen.isSelected();SwingUtilities.invokeLater(()->app.setRecordFullscreen(selected));});view.add(recordFullscreen);
        view.addSeparator();add(view,"终端历史",()->new LogViewer(app,owner,0,null),null);add(view,"全量输入输出历史",()->app.showLogs(owner,false,null),KeyStroke.getKeyStroke(KeyEvent.VK_H,InputEvent.CTRL_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK));add(view,"软件日志",()->app.showLogs(owner,true,null),null);add(view,"恢复草稿",()->app.recoverDraft(owner),null);add(view,"日志目录",()->app.browse(app.home.resolve("logs"),owner),null);
        JMenu tools=new JMenu("工具");bar.add(tools);add(tools,"协作",()->app.collaborate(owner),null);add(tools,"AI 设置",()->app.assistantSettings(owner),null);add(tools,"插件",()->app.pluginInfo(owner),null);tools.addSeparator();add(tools,"备份 / 恢复",()->app.backupMenu(owner),null);add(tools,"更新",()->app.update(owner),null);
        JMenu about=new JMenu("关于");bar.add(about);add(about,"GitHub 项目",()->app.openProject(owner),null);add(about,"关于 Lagless",()->app.about(owner),null);
        bar.add(Box.createHorizontalGlue());JLabel hint=new JLabel("SSH 工作区");hint.setForeground(DesktopApp.MUTED);hint.setBorder(new EmptyBorder(0,8,0,8));bar.add(hint);return bar;
    }
    static JMenuItem add(JMenu menu,String label,Runnable action,KeyStroke shortcut){JMenuItem item=new JMenuItem(label);item.setName(label);if(shortcut!=null)item.setAccelerator(shortcut);item.addActionListener(e->{MenuSelectionManager.defaultManager().clearSelectedPath();SwingUtilities.invokeLater(action::run);});menu.add(item);return item;}
    private JPanel welcome(){
        JPanel background=new JPanel(new GridBagLayout());background.setBackground(new Color(0xf6f8fa));JPanel card=new JPanel();card.setLayout(new BoxLayout(card,BoxLayout.Y_AXIS));card.setBackground(Color.WHITE);card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0xe1e7ec)),new EmptyBorder(36,40,36,40)));
        JLabel eyebrow=new JLabel("READY WHEN YOU ARE");eyebrow.setForeground(DesktopApp.ACCENT);eyebrow.setFont(DesktopFonts.ui().deriveFont(Font.BOLD,11));card.add(eyebrow);card.add(Box.createVerticalStrut(14));
        JLabel title=new JLabel("从一个连接开始");title.setFont(DesktopFonts.ui().deriveFont(Font.BOLD,28));card.add(title);card.add(Box.createVerticalStrut(10));JLabel description=new JLabel("本地编辑、顺畅输入。让终端跟上你的思路。");description.setForeground(DesktopApp.MUTED);card.add(description);card.add(Box.createVerticalStrut(28));
        JPanel choose=new JPanel(new BorderLayout(8,0));choose.setOpaque(false);quick.setPreferredSize(new Dimension(330,38));choose.add(quick,BorderLayout.CENTER);choose.add(DesktopApp.button("连接",()->open(currentProfile())),BorderLayout.EAST);choose.setMaximumSize(new Dimension(470,40));card.add(choose);card.add(Box.createVerticalStrut(16));
        JButton library=DesktopApp.button("管理连接  ·  Ctrl+Shift+L",()->new ConnectionLibrary(this));
        library.setOpaque(true);library.setBackground(DesktopApp.ACCENT);library.setForeground(Color.WHITE);
        library.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(DesktopApp.ACCENT.darker(),1,true),new EmptyBorder(8,18,8,18)));
        card.add(library);
        card.add(Box.createVerticalStrut(18));JLabel note=new JLabel("连接库随用随开，终端始终是主角。");note.setForeground(DesktopApp.MUTED);note.setFont(DesktopFonts.ui().deriveFont(12f));card.add(note);
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
    void closeSession(SessionPane pane){if(pane==null)return;if(JOptionPane.showConfirmDialog(root,"关闭此会话？草稿和日志会保留。","关闭连接",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;pane.close();tabs.remove(pane);updateActions();}
}
