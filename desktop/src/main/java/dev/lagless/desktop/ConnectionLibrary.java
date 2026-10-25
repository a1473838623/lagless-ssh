package dev.lagless.desktop;

import dev.lagless.I18n;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Locale;

/** Searchable connection picker, shared by all windows and closed after connecting. */
final class ConnectionLibrary {
    private final DefaultListModel<Profile> filtered=new DefaultListModel<>();
    private final JList<Profile> list=new JList<>(filtered);
    private final JTextField search=new ImeTextField();
    final JDialog dialog;
    private volatile boolean firstPaintCompleted;
    boolean firstPaintCompleted(){return firstPaintCompleted;}
    ConnectionLibrary(Workspace workspace){
        DesktopApp app=workspace.app;dialog=new JDialog(workspace.owner,I18n.t("连接库","Connections"),Dialog.ModalityType.MODELESS);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setIconImage(DesktopApp.appIcon());
        dialog.setName("connection-library");dialog.getRootPane().putClientProperty("lagless.library",this);
        JPanel content=new JPanel(new BorderLayout()){@Override protected void paintChildren(Graphics g){super.paintChildren(g);firstPaintCompleted=true;}};content.setBackground(Theme.CANVAS);dialog.setContentPane(content);
        search.putClientProperty("JTextField.placeholderText",I18n.t("搜索连接、主机或用户","Search connections, hosts or users"));
        JPopupMenu menu=new JPopupMenu();
        popupAction(menu,I18n.t("编辑所选","Edit"),()->{Profile p=list.getSelectedValue();if(p!=null)app.edit(dialog,p);});
        popupAction(menu,I18n.t("删除所选","Delete"),()->app.remove(dialog,list.getSelectedValue()));
        menu.addSeparator();
        popupAction(menu,I18n.t("导入账号","Import"),()->app.importAccounts(dialog));
        popupAction(menu,I18n.t("导出账号","Export"),()->app.exportAccounts(dialog,list.getSelectedValue()));
        popupAction(menu,I18n.t("读取终端配置","Read terminal config"),()->app.importTerminal(dialog));
        JButton management=new JButton(I18n.t("管理","Manage"),UiIcons.CHEVRON_DOWN);management.setHorizontalTextPosition(SwingConstants.LEFT);management.setIconTextGap(8);management.setFocusPainted(false);management.setMargin(new Insets(8,13,8,13));
        management.setBackground(Theme.RAISED);management.setForeground(Theme.TEXT);
        management.addActionListener(e->menu.show(management,0,management.getHeight()));
        JPanel header=new JPanel(new BorderLayout(0,14));header.setBackground(Theme.CANVAS);header.setBorder(new EmptyBorder(22,22,14,22));
        JPanel heading=new JPanel(new BorderLayout());heading.setOpaque(false);
        JLabel title=new JLabel(I18n.t("连接库","Connections"));title.setFont(DesktopFonts.ui().deriveFont(Font.BOLD,22));title.setForeground(Theme.INK);heading.add(title,BorderLayout.WEST);
        JPanel headingActions=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));headingActions.setOpaque(false);headingActions.add(management);
        JButton create=DesktopApp.primary(DesktopApp.button(I18n.t("新建连接","New connection"),()->app.edit(dialog,null)));create.setIcon(UiIcons.PLUS);headingActions.add(create);heading.add(headingActions,BorderLayout.EAST);
        header.add(heading,BorderLayout.NORTH);header.add(search,BorderLayout.SOUTH);
        list.setFixedCellHeight(70);list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);list.setCellRenderer((view,p,index,selected,focus)->{JPanel cell=new JPanel(new GridLayout(2,1,0,2));cell.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,3,0,0,selected?Theme.ACCENT:Theme.SURFACE),new EmptyBorder(9,14,9,12)));cell.setBackground(selected?Theme.TINT:Theme.SURFACE);JLabel name=new JLabel(p.name());name.setFont(DesktopFonts.ui().deriveFont(Font.BOLD,13.5f));name.setForeground(Theme.INK);cell.add(name);JLabel host=new JLabel(p.user()+"@"+p.host()+":"+p.port());host.setForeground(Theme.MUTED);host.setFont(DesktopFonts.ui().deriveFont(11.5f));cell.add(host);return cell;});
        Runnable connect=()->{Profile p=list.getSelectedValue();if(p!=null){dialog.dispose();workspace.open(p);}};
        list.addMouseListener(new MouseAdapter(){
            @Override public void mousePressed(MouseEvent e){
                if(!SwingUtilities.isLeftMouseButton(e))return;
                int index=list.locationToIndex(e.getPoint());
                if(index>=0&&list.getCellBounds(index,index).contains(e.getPoint())){
                    list.setSelectedIndex(index);list.requestFocusInWindow();
                }
            }
            @Override public void mouseClicked(MouseEvent e){if(e.getClickCount()==2&&SwingUtilities.isLeftMouseButton(e))connect.run();}
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"connect");list.getActionMap().put("connect",new AbstractAction(){public void actionPerformed(ActionEvent e){connect.run();}});
        Runnable refresh=()->{String term=search.getText().toLowerCase(Locale.ROOT);Profile previous=list.getSelectedValue();String id=previous==null?"":previous.id();filtered.clear();Profile selection=null;for(Profile p:app.profiles())if((p.name()+" "+p.host()+" "+p.user()).toLowerCase(Locale.ROOT).contains(term)){filtered.addElement(p);if(p.id().equals(id))selection=p;}if(selection!=null)list.setSelectedValue(selection,true);else if(!filtered.isEmpty())list.setSelectedIndex(0);};
        search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){refresh.run();}public void removeUpdate(DocumentEvent e){refresh.run();}public void changedUpdate(DocumentEvent e){refresh.run();}});
        ListDataListener listener=new ListDataListener(){private boolean pending;private void refreshLater(){if(pending)return;pending=true;SwingUtilities.invokeLater(()->{pending=false;refresh.run();});}public void intervalAdded(ListDataEvent e){refreshLater();}public void intervalRemoved(ListDataEvent e){refreshLater();}public void contentsChanged(ListDataEvent e){refreshLater();}};app.accounts.addListDataListener(listener);dialog.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){app.accounts.removeListDataListener(listener);}});
        JPanel bottom=new JPanel(new BorderLayout(8,0));bottom.setBackground(Theme.CANVAS);bottom.setBorder(new EmptyBorder(13,22,18,22));JLabel tip=new JLabel(I18n.t("双击连接 · 上移/下移调整顺序","Double-click to connect · reorder with Up/Down"));tip.setForeground(Theme.MUTED);tip.setFont(DesktopFonts.ui().deriveFont(11));bottom.add(tip,BorderLayout.WEST);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
        JButton up=DesktopApp.button(I18n.t("上移","Move up"),()->move(app,-1)),down=DesktopApp.button(I18n.t("下移","Move down"),()->move(app,1));
        JButton edit=DesktopApp.button(I18n.t("编辑所选","Edit"),()->{Profile p=list.getSelectedValue();if(p!=null)app.edit(dialog,p);});
        JButton open=DesktopApp.primary(DesktopApp.button(I18n.t("连接","Connect"),connect));
        actions.add(up);actions.add(down);actions.add(edit);actions.add(open);bottom.add(actions,BorderLayout.EAST);
        list.addListSelectionListener(e->{if(e.getValueIsAdjusting())return;int index=list.getSelectedIndex();up.setEnabled(index>0);down.setEnabled(index>=0&&index<filtered.size()-1);edit.setEnabled(index>=0);open.setEnabled(index>=0);});
        up.setEnabled(false);down.setEnabled(false);edit.setEnabled(false);open.setEnabled(false);
        dialog.add(header,BorderLayout.NORTH);dialog.add(new EditorScrollPane(list),BorderLayout.CENTER);dialog.add(bottom,BorderLayout.SOUTH);
        refresh.run();dialog.pack();dialog.setSize(720,590);dialog.setLocationRelativeTo(workspace.owner);
        dialog.addWindowListener(new WindowAdapter(){public void windowOpened(WindowEvent e){SwingUtilities.invokeLater(()->{
            if(!dialog.isShowing())return;
            // Finish native peer creation and popup dismissal before painting the first frame.
            // A repaint alone can stay coalesced until the next input on Windows Native Image.
            dialog.validate();content.revalidate();content.paintImmediately(0,0,content.getWidth(),content.getHeight());content.repaint();search.requestFocusInWindow();
        });}});dialog.setVisible(true);
    }
    private static void popupAction(JPopupMenu menu,String title,Runnable action){
        JMenuItem item=new JMenuItem(title);item.addActionListener(e->action.run());menu.add(item);
    }
    private void move(DesktopApp app,int direction){
        Profile selected=list.getSelectedValue();if(selected==null)return;
        var profiles=app.profiles();int from=-1;
        for(int i=0;i<profiles.size();i++)if(profiles.get(i).id().equals(selected.id())){from=i;break;}
        if(from<0||from+direction<0||from+direction>=profiles.size())return;
        try{app.reorderProfile(selected.id(),direction<0?from-1:from+2);}
        catch(Exception error){DesktopApp.error(dialog,error);}
    }
}
