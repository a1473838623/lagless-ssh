package dev.lagless.desktop;

import javax.swing.*;
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
        DesktopApp app=workspace.app;dialog=new JDialog(workspace.owner,"连接库",Dialog.ModalityType.MODELESS);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setIconImage(DesktopApp.appIcon());
        dialog.setName("connection-library");dialog.getRootPane().putClientProperty("lagless.library",this);
        JPanel content=new JPanel(new BorderLayout()){@Override protected void paintChildren(Graphics g){super.paintChildren(g);firstPaintCompleted=true;}};dialog.setContentPane(content);
        JPanel header=new JPanel(new BorderLayout(8,8));header.setBorder(BorderFactory.createEmptyBorder(12,12,8,12));search.putClientProperty("JTextField.placeholderText","搜索名称、主机或用户");header.add(search,BorderLayout.CENTER);header.add(DesktopApp.button("＋",()->app.edit(dialog,null)),BorderLayout.EAST);
        JPopupMenu menu=new JPopupMenu();
        popupAction(menu,"编辑所选",()->{Profile p=list.getSelectedValue();if(p!=null)app.edit(dialog,p);});
        popupAction(menu,"删除所选",()->app.remove(dialog,list.getSelectedValue()));
        menu.addSeparator();
        popupAction(menu,"导入账号",()->app.importAccounts(dialog));
        popupAction(menu,"导出账号",()->app.exportAccounts(dialog,list.getSelectedValue()));
        popupAction(menu,"读取终端配置",()->app.importTerminal(dialog));
        JButton management=new JButton("管理连接",UiIcons.CHEVRON_DOWN);management.setHorizontalTextPosition(SwingConstants.LEFT);management.setIconTextGap(10);management.setFocusPainted(false);management.setMargin(new Insets(8,14,8,14));
        management.setBackground(DesktopApp.ACCENT);management.setForeground(Color.WHITE);management.setOpaque(true);
        management.addActionListener(e->menu.show(management,0,management.getHeight()));
        header.add(management,BorderLayout.WEST);
        list.setFixedCellHeight(60);list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);list.setCellRenderer((view,p,index,selected,focus)->{JPanel cell=new JPanel(new GridLayout(2,1));cell.setBorder(BorderFactory.createEmptyBorder(8,12,8,12));cell.setBackground(selected?new Color(0xe0eee9):Color.WHITE);JLabel name=new JLabel(p.name());name.setFont(DesktopFonts.ui().deriveFont(Font.BOLD,14));cell.add(name);JLabel host=new JLabel(p.user()+"@"+p.host()+":"+p.port());host.setForeground(DesktopApp.MUTED);cell.add(host);return cell;});
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
        JPanel bottom=new JPanel(new BorderLayout(8,0));bottom.setBorder(BorderFactory.createEmptyBorder(8,12,12,12));bottom.add(new JLabel("单击选择 · 双击连接 · 上下移动排序"),BorderLayout.WEST);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
        JButton up=DesktopApp.button("上移",()->move(app,-1)),down=DesktopApp.button("下移",()->move(app,1));
        JButton edit=DesktopApp.button("编辑所选",()->{Profile p=list.getSelectedValue();if(p!=null)app.edit(dialog,p);});
        JButton open=DesktopApp.button("连接",connect);
        actions.add(up);actions.add(down);actions.add(edit);actions.add(open);bottom.add(actions,BorderLayout.EAST);
        list.addListSelectionListener(e->{if(e.getValueIsAdjusting())return;int index=list.getSelectedIndex();up.setEnabled(index>0);down.setEnabled(index>=0&&index<filtered.size()-1);edit.setEnabled(index>=0);open.setEnabled(index>=0);});
        up.setEnabled(false);down.setEnabled(false);edit.setEnabled(false);open.setEnabled(false);
        dialog.add(header,BorderLayout.NORTH);dialog.add(new EditorScrollPane(list),BorderLayout.CENTER);dialog.add(bottom,BorderLayout.SOUTH);
        refresh.run();dialog.pack();dialog.setSize(680,560);dialog.setLocationRelativeTo(workspace.owner);
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
