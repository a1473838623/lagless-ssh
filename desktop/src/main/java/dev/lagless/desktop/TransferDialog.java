package dev.lagless.desktop;

import dev.lagless.I18n;
import com.jcraft.jsch.*;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.*;
import java.util.List;

/** Browsers observe a session-owned queue; closing this window never cancels downloads. */
final class TransferDialog {
    private final SessionPane pane;private final TransferQueue queue;
    private final JTextField localPath=DesktopApp.field(System.getProperty("user.home")),remotePath=DesktopApp.field("."),limit=DesktopApp.field("");
    private final DefaultListModel<Path> localModel=new DefaultListModel<>();private final DefaultListModel<SftpTransfers.Entry> remoteModel=new DefaultListModel<>();
    private final JList<Path> local=new JList<>(localModel);private final JList<SftpTransfers.Entry> remote=new JList<>(remoteModel);
    private final JCheckBox archive=new JCheckBox(I18n.t("文件夹打包传输","Folders as one archive"));
    private final JLabel status=new JLabel(I18n.t("默认不限速 · 关闭此窗口后队列继续，关闭 SSH 会话会取消队列","No speed limit by default · The queue keeps running after this window closes; closing the SSH session cancels it"));
    private final JDialog dialog;private final QueueModel model=new QueueModel();private final JTable tasks=new JTable(model);private final javax.swing.Timer timer;
    private boolean closed,localBusy,remoteBusy,localReload,remoteReload;private int finished;
    private Path localDirectory=Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();private String remoteDirectory=".";
    private final List<JComponent> localControls=new ArrayList<>(),remoteControls=new ArrayList<>();
    private final JProgressBar localLoading=new JProgressBar(),remoteLoading=new JProgressBar();
    private ClipboardFiles clipboard;
    private record ClipboardFiles(boolean remote,boolean cut,List<Path> local,List<String> paths){}
    TransferDialog(SessionPane pane){
        this.pane=pane;queue=pane.transferQueue();dialog=new JDialog(DesktopApp.ownerWindow(pane),pane.profile.name()+I18n.t(" · 文件管理"," · Files"),Dialog.ModalityType.MODELESS);dialog.setLayout(new BorderLayout(8,8));
        local.setCellRenderer((list,path,index,chosen,focus)->label(path.getFileName().toString(),chosen,Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS),Files.isSymbolicLink(path)));
        remote.setCellRenderer((list,entry,index,chosen,focus)->label(entry.name(),chosen,entry.directory(),entry.link()));local.setFixedCellHeight(32);remote.setFixedCellHeight(32);
        JSplitPane browsers=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,browser(I18n.t("本地","Local"),localPath,local,this::localUp,this::refreshLocal),browser(I18n.t("远端","Remote"),remotePath,remote,this::remoteUp,this::refreshRemote));browsers.setResizeWeight(.5);browsers.setDividerLocation(500);browsers.setBorder(null);
        localPath.addActionListener(e->refreshLocal());remotePath.addActionListener(e->refreshRemote());
        local.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2&&!localBusy)open(false);}});
        remote.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2&&!remoteBusy)open(true);}});
        JPanel actions=DesktopApp.row();actions.setBorder(BorderFactory.createEmptyBorder(6,8,6,8));actions.add(DesktopApp.button(I18n.t("上传 →","Upload →"),this::upload));actions.add(DesktopApp.button(I18n.t("← 加入下载队列","← Queue download"),this::download));actions.add(new JLabel(I18n.t("限速 KiB/s","Limit KiB/s")));limit.setColumns(6);limit.putClientProperty("JTextField.placeholderText",I18n.t("不限","none"));limit.setToolTipText(I18n.t("留空或 0 表示不限速","Leave empty or 0 for no limit"));actions.add(limit);
        archive.setName("transfer-archive");archive.setSelected(Boolean.parseBoolean(pane.app.preferences.get("transfer-archive","false")));archive.addActionListener(e->pane.app.preferences.set("transfer-archive",Boolean.toString(archive.isSelected())));
        archive.setToolTipText(I18n.t("文件夹作为一个压缩流（tar.gz）传输，不在任一端生成归档文件；大量小文件时更快。需要服务器有 tar 和 gzip。单个文件仍按普通方式传输。","Folders go as one compressed stream (tar.gz); no archive file is written on either side. Faster for many small files. Needs tar and gzip on the server. Single files still transfer normally."));actions.add(archive);
        JPanel top=new JPanel(new BorderLayout());top.add(browsers,BorderLayout.CENTER);top.add(actions,BorderLayout.SOUTH);
        tasks.setName("transfer-queue");tasks.setRowHeight(30);tasks.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);tasks.setDefaultRenderer(TransferQueue.Job.class,(table,value,selected,focus,row,column)->{TransferQueue.Job job=(TransferQueue.Job)value;JProgressBar progress=new JProgressBar(0,100);progress.setValue(job.percent());progress.setStringPainted(true);progress.setString(job.state==TransferQueue.State.SCANNING?I18n.t("统计中…","Counting…"):job.progressText());return progress;});
        // Long paths: show the end (where the file goes), the full path on hover.
        tasks.setDefaultRenderer(String.class,new javax.swing.table.DefaultTableCellRenderer(){@Override public Component getTableCellRendererComponent(JTable table,Object value,boolean selected,boolean focus,int row,int column){
            super.getTableCellRendererComponent(table,value,selected,focus,row,column);String text=value==null?"":value.toString();setToolTipText(text.isEmpty()?null:text);return this;}});
        int[] queueWidths={150,60,190,190,80,110,190,80};for(int i=0;i<queueWidths.length&&i<tasks.getColumnCount();i++)tasks.getColumnModel().getColumn(i).setPreferredWidth(queueWidths[i]);
        int[] widths={190,60,90,160,160,100};for(int i=0;i<widths.length;i++)tasks.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        JPanel jobs=new JPanel(new BorderLayout(4,4));JPanel controls=DesktopApp.row();controls.add(new JLabel(I18n.t("传输队列","Transfer queue")));controls.add(DesktopApp.button(I18n.t("取消选中","Cancel selected"),()->{TransferQueue.Job job=selected();if(job!=null)queue.cancel(job);}));controls.add(DesktopApp.button(I18n.t("重试选中","Retry selected"),()->{TransferQueue.Job job=selected();if(job!=null)try{queue.retry(job);refreshQueue();}catch(Exception e){DesktopApp.error(dialog,e);}}));controls.add(DesktopApp.button(I18n.t("清除已结束","Clear finished"),()->{queue.clearFinished();refreshQueue();}));controls.add(DesktopApp.button(I18n.t("打开本地目录","Open local folder"),()->pane.app.browse(Path.of(localPath.getText()),dialog)));jobs.add(controls,BorderLayout.NORTH);jobs.add(new EditorScrollPane(tasks),BorderLayout.CENTER);
        JSplitPane workspace=new JSplitPane(JSplitPane.VERTICAL_SPLIT,top,jobs);workspace.setResizeWeight(.65);workspace.setDividerLocation(410);workspace.setBorder(null);dialog.add(workspace,BorderLayout.CENTER);status.setBorder(BorderFactory.createEmptyBorder(4,8,6,8));dialog.add(status,BorderLayout.SOUTH);
        timer=new javax.swing.Timer(250,e->refreshQueue());dialog.setSize(1060,760);dialog.setLocationRelativeTo(pane);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){closed=true;timer.stop();}});
        tasks.getSelectionModel().addListSelectionListener(e->showDetails());
    }
    static JLabel label(String text,boolean selected,boolean directory,boolean link){JLabel label=new JLabel(text,UiIcons.file(directory,link),SwingConstants.LEFT);label.setIconTextGap(9);label.setFont(DesktopFonts.ui());label.setOpaque(true);label.setBorder(BorderFactory.createEmptyBorder(5,8,5,8));label.setBackground(selected?Theme.TINT:Theme.SURFACE);return label;}
    static String size(long bytes){return bytes<1024?bytes+" B":bytes<1024*1024?String.format(Locale.ROOT,"%.1f KiB",bytes/1024.0):bytes<1024L*1024*1024?String.format(Locale.ROOT,"%.1f MiB",bytes/(1024.0*1024)):String.format(Locale.ROOT,"%.2f GiB",bytes/(1024.0*1024*1024));}
    private JPanel browser(String title,JTextField path,JList<?> list,Runnable up,Runnable refresh){
        boolean server=list==remote;List<JComponent> controls=server?remoteControls:localControls;JProgressBar loading=server?remoteLoading:localLoading;
        JPanel result=new JPanel(new BorderLayout(6,6));result.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));JPanel top=new JPanel(new BorderLayout(6,6));
        JPanel heading=new JPanel(new BorderLayout(8,0));heading.add(new JLabel(title),BorderLayout.WEST);loading.setIndeterminate(true);loading.setStringPainted(true);loading.setString(I18n.t("正在加载…","Loading…"));loading.setPreferredSize(new Dimension(150,22));loading.setVisible(false);heading.add(loading,BorderLayout.EAST);top.add(heading,BorderLayout.NORTH);top.add(path,BorderLayout.CENTER);
        JPanel navigation=DesktopApp.row();JButton parent=DesktopApp.button("↑",up),reload=DesktopApp.button(I18n.t("刷新","Refresh"),refresh);controls.add(parent);controls.add(reload);controls.add(path);controls.add(list);navigation.add(parent);navigation.add(reload);top.add(navigation,BorderLayout.EAST);
        JPanel operations=new JPanel(new GridLayout(2,4,4,4));String[] names={I18n.t("新建文件夹","New folder"),I18n.t("打开","Open"),I18n.t("复制","Copy"),I18n.t("粘贴","Paste"),I18n.t("剪切","Cut"),I18n.t("重命名","Rename"),I18n.t("删除","Delete")};Runnable[] actions={()->mkdir(server),()->open(server),()->copy(server,false),()->paste(server),()->copy(server,true),()->rename(server),()->delete(server)};
        for(int i=0;i<names.length;i++){JButton button=DesktopApp.button(names[i],actions[i]);controls.add(button);operations.add(button);}result.add(top,BorderLayout.NORTH);result.add(new EditorScrollPane(list),BorderLayout.CENTER);result.add(operations,BorderLayout.SOUTH);
        bind(list,KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"open",()->open(server));bind(list,KeyStroke.getKeyStroke(KeyEvent.VK_F2,0),"rename",()->rename(server));bind(list,KeyStroke.getKeyStroke(KeyEvent.VK_DELETE,0),"delete",()->delete(server));
        bind(list,KeyStroke.getKeyStroke(KeyEvent.VK_C,InputEvent.CTRL_DOWN_MASK),"copy",()->copy(server,false));bind(list,KeyStroke.getKeyStroke(KeyEvent.VK_X,InputEvent.CTRL_DOWN_MASK),"cut",()->copy(server,true));bind(list,KeyStroke.getKeyStroke(KeyEvent.VK_V,InputEvent.CTRL_DOWN_MASK),"paste",()->paste(server));return result;
    }
    private static void bind(JComponent view,KeyStroke key,String name,Runnable action){view.getInputMap().put(key,name);view.getActionMap().put(name,new AbstractAction(){public void actionPerformed(ActionEvent e){action.run();}});}
    void show(){dialog.setVisible(true);refreshLocal();refreshRemote();refreshQueue();timer.start();}
    private boolean busy(boolean server){return closed||(server?remoteBusy:localBusy);}
    private void setBusy(boolean server,boolean value,String message){
        if(server)remoteBusy=value;else localBusy=value;
        for(JComponent control:server?remoteControls:localControls)control.setEnabled(!value);
        JProgressBar loading=server?remoteLoading:localLoading;loading.setString(message);loading.setVisible(value);
        (server?remote:local).setCursor(Cursor.getPredefinedCursor(value?Cursor.WAIT_CURSOR:Cursor.DEFAULT_CURSOR));
        if(value)status.setText((server?I18n.t("远端","Remote"):I18n.t("本地","Local"))+" · "+message);
    }
    private <T> void work(boolean server,String title,Callable<T> task,Consumer<T> done){
        if(busy(server))return;setBusy(server,true,title);
        pane.app.workers.execute(()->{T result=null;Exception failure=null;try{result=task.call();}catch(Exception error){failure=error;}T value=result;Exception error=failure;
            SwingUtilities.invokeLater(()->{if(closed)return;setBusy(server,false,"");if(error!=null){(server?remotePath:localPath).setText(server?remoteDirectory:localDirectory.toString());status.setText(title+I18n.t("失败：","Failed: ")+error.getMessage());pane.app.events.operation(pane.profile.name(),title+I18n.t("失败：","Failed: ")+error.getMessage());DesktopApp.error(dialog,error);}else{status.setText(title+I18n.t("完成","Done"));done.accept(value);}
                if(server&&remoteReload&&!remoteBusy){remoteReload=false;refreshRemote();}else if(!server&&localReload&&!localBusy){localReload=false;refreshLocal();}
            });
        });
    }
    private void localUp(){if(busy(false))return;Path p=localDirectory.getParent();if(p!=null){localPath.setText(p.toString());refreshLocal();}}
    private void remoteUp(){if(busy(true))return;remotePath.setText(remoteDirectory.replaceAll("/+$","")+"/..");refreshRemote();}
    private record LocalListing(Path path,List<Path> entries){}
    private void refreshLocal(){
        if(closed)return;if(localBusy){localReload=true;return;}String path=localPath.getText();
        work(false,I18n.t("正在加载文件夹…","Loading folder…"),()->{Path directory=Path.of(path).toAbsolutePath().normalize();if(!Files.isDirectory(directory))throw new IOException(I18n.t("本地目录不存在","Local folder does not exist"));try(var files=Files.list(directory)){return new LocalListing(directory,files.sorted(Comparator.comparing((Path p)->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).reversed().thenComparing(p->p.getFileName().toString(),String.CASE_INSENSITIVE_ORDER)).toList());}},listing->{localDirectory=listing.path();localPath.setText(localDirectory.toString());localModel.clear();listing.entries().forEach(localModel::addElement);});
    }
    private record Listing(String path,List<SftpTransfers.Entry> entries){}
    private void refreshRemote(){
        if(closed)return;if(remoteBusy){remoteReload=true;return;}String path=remotePath.getText();
        work(true,I18n.t("正在加载文件夹…","Loading folder…"),()->{ChannelSftp channel=pane.requireConnection().openSftp();try{channel.cd(path);return new Listing(channel.pwd(),SftpTransfers.list(channel,"."));}finally{channel.disconnect();}},listing->{remoteDirectory=listing.path();remotePath.setText(remoteDirectory);remoteModel.clear();listing.entries().forEach(remoteModel::addElement);});
    }
    private List<Path> selectedLocal()throws IOException{
        List<Path> selected=local.getSelectedValuesList();if(selected.isEmpty())throw new IOException(I18n.t("请选择文件或文件夹","Select a file or folder"));
        for(Path path:selected)if(!FileActions.localChild(localDirectory,path.getFileName().toString()).equals(path.toAbsolutePath().normalize()))throw new IOException(I18n.t("文件不在当前目录","File is not in the current folder"));return selected;
    }
    private List<String> selectedRemote()throws IOException{
        List<String> result=new ArrayList<>();for(var entry:remote.getSelectedValuesList())result.add(SftpTransfers.child(remoteDirectory,entry.name()));if(result.isEmpty())throw new IOException(I18n.t("请选择文件或文件夹","Select a file or folder"));return result;
    }
    private void copy(boolean server,boolean cut){if(busy(server))return;try{clipboard=new ClipboardFiles(server,cut,server?List.of():List.copyOf(selectedLocal()),server?List.copyOf(selectedRemote()):List.of());status.setText(I18n.t("已","")+(cut?I18n.t("剪切","Cut"):I18n.t("复制","Copy"))+" "+(server?clipboard.paths().size():clipboard.local().size())+I18n.t(" 项，请到目标文件夹粘贴"," item(s) ready; paste them in the target folder"));}catch(Exception e){DesktopApp.error(dialog,e);}}
    private void mkdir(boolean server){
        if(busy(server))return;String name=JOptionPane.showInputDialog(dialog,I18n.t("文件夹名称","Folder name"),I18n.t("新建文件夹","New folder"),JOptionPane.PLAIN_MESSAGE);if(name==null)return;
        try{SftpTransfers.validName(name);Path directory=localDirectory;String folder=server?SftpTransfers.child(remoteDirectory,name):"";
            work(server,I18n.t("正在新建文件夹…","Creating folder…"),()->{if(server){ChannelSftp ch=pane.requireConnection().openSftp();try{ch.mkdir(folder);}finally{ch.disconnect();}}else Files.createDirectory(FileActions.localChild(directory,name));return name;},value->{pane.app.events.operation(pane.profile.name(),I18n.t("新建","Created ")+(server?I18n.t("远端","remote"):I18n.t("本地","local"))+I18n.t("文件夹："," folder: ")+value);if(server)refreshRemote();else refreshLocal();});
        }catch(Exception e){DesktopApp.error(dialog,e);}
    }
    private void open(boolean server){
        if(busy(server))return;
        try{
            if(server){var entry=remote.getSelectedValue();if(entry==null)throw new IOException(I18n.t("请选择文件或文件夹","Select a file or folder"));if(entry.directory()&&!entry.link()){remotePath.setText(SftpTransfers.child(remoteDirectory,entry.name()));refreshRemote();return;}
                if(entry.link())throw new IOException(I18n.t("请直接选择原文件，不跟随符号链接","Select the original file; symbolic links are not followed"));String source=SftpTransfers.child(remoteDirectory,entry.name());long speed=rate();
                work(true,I18n.t("正在准备打开…","Preparing to open…"),()->{Path directory=Files.createTempDirectory("lagless-open-");Path target=directory.resolve(entry.name());queue.enqueue(false,target,source,speed,(ch,job)->{Desktop.getDesktop().open(job.local.toFile());SwingUtilities.invokeLater(()->{if(!closed)status.setText(I18n.t("已打开本地副本；修改后请上传回远端：","Opened a local copy; upload it back after editing: ")+job.local);});});return target;},target->{refreshQueue();status.setText(I18n.t("下载后自动用本地软件打开，进度见传输队列","The file opens in a local app after download; see the transfer queue for progress"));});
            }else{Path source=selectedLocal().getFirst();if(Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS)){localPath.setText(source.toString());refreshLocal();return;}work(false,I18n.t("正在打开文件…","Opening file…"),()->{Desktop.getDesktop().open(source.toFile());return source;},path->pane.app.events.operation(pane.profile.name(),I18n.t("打开本地文件：","Open local file: ")+path));}
        }catch(Exception e){DesktopApp.error(dialog,e);}
    }
    private void rename(boolean server){
        if(busy(server))return;try{
            Path file=server?null:selectedLocal().getFirst();String source=server?selectedRemote().getFirst():"";String old=server?source.substring(source.lastIndexOf('/')+1):file.getFileName().toString();
            String name=(String)JOptionPane.showInputDialog(dialog,I18n.t("新名称","New name"),I18n.t("重命名","Rename"),JOptionPane.PLAIN_MESSAGE,null,null,old);if(name==null||name.equals(old))return;SftpTransfers.validName(name);String target=server?SftpTransfers.child(remoteDirectory,name):"";Path localTarget=server?null:FileActions.localChild(localDirectory,name);
            work(server,I18n.t("正在重命名…","Renaming…"),()->{if(server){ChannelSftp ch=pane.requireConnection().openSftp();try{if(FileActions.remoteExists(ch,target))throw new IOException(I18n.t("目标已存在","Target already exists"));ch.rename(source,target);}finally{ch.disconnect();}}else Files.move(file,localTarget);return name;},value->{pane.app.events.operation(pane.profile.name(),I18n.t("重命名：","Rename: ")+old+" → "+value);if(server)refreshRemote();else refreshLocal();});
        }catch(Exception e){DesktopApp.error(dialog,e);}
    }
    private void delete(boolean server){
        if(busy(server))return;try{
            List<Path> localFiles=server?List.of():List.copyOf(selectedLocal());List<String> remoteFiles=server?List.copyOf(selectedRemote()):List.of();
            String names=String.join("\n",server?remoteFiles:localFiles.stream().map(Path::toString).toList());
            if(JOptionPane.showConfirmDialog(dialog,I18n.t("永久删除以下","Permanently delete these ")+(server?I18n.t("远端","remote"):I18n.t("本地","local"))+I18n.t("文件及文件夹？\n"," file(s) and folder(s)?\n")+names+I18n.t("\n此操作无法撤销。","\nThis cannot be undone."),I18n.t("确认删除","Confirm delete"),JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.OK_OPTION)return;
            work(server,I18n.t("正在删除…","Deleting…"),()->{if(server){ChannelSftp ch=pane.requireConnection().openSftp();try{for(String source:remoteFiles)FileActions.remoteDelete(ch,source);}finally{ch.disconnect();}}else for(Path source:localFiles)FileActions.localDelete(source);return names;},value->{pane.app.events.operation(pane.profile.name(),I18n.t("删除","Deleted ")+(server?I18n.t("远端","remote"):I18n.t("本地","local"))+I18n.t("文件："," files: ")+value);if(server)refreshRemote();else refreshLocal();});
        }catch(Exception e){DesktopApp.error(dialog,e);}
    }
    /** Bytes per second; empty or 0 is no limit. */
    private long rate(){String text=limit.getText().trim();if(text.isEmpty()||text.equals("0"))return 0;long speed=Math.multiplyExact(Long.parseLong(text),1024);if(speed<1024||speed>1024L*1024*1024)throw new IllegalArgumentException(I18n.t("限速范围：1–1048576 KiB/s，留空为不限速","Limit range: 1–1048576 KiB/s, or empty for no limit"));return speed;}
    private void paste(boolean server){
        if(busy(server))return;ClipboardFiles files=clipboard;if(files==null){status.setText(I18n.t("请先复制或剪切文件","Copy or cut files first"));return;}
        Path localTarget=localDirectory;String remoteTarget=remoteDirectory;
        try{long speed=rate();work(server,I18n.t("正在","")+(files.cut()?I18n.t("移动","Moving"):I18n.t("复制","Copying"))+I18n.t("文件…"," files…"),()->{
            if(files.remote()!=server){
                if(server){ChannelSftp ch=pane.requireConnection().openSftp();try{for(Path source:files.local()){
                    String name=source.getFileName().toString(),destination=SftpTransfers.child(remoteTarget,name);
                    if(remoteOccupied(ch,destination)){if(files.cut())throw new IOException(I18n.t("目标已存在：","Target already exists: ")+destination);int n=1;do{destination=SftpTransfers.child(remoteTarget,FileActions.uniqueName(name,n++));}while(remoteOccupied(ch,destination));}
                    queue.enqueue(true,source,destination,speed,files.cut()?cutCompletion(true,source,""):null);
                }}finally{ch.disconnect();}}
                else for(String source:files.paths()){
                    String name=source.substring(source.lastIndexOf('/')+1);Path destination=FileActions.localChild(localTarget,name);
                    if(localOccupied(destination)){if(files.cut())throw new IOException(I18n.t("目标已存在：","Target already exists: ")+destination);int n=1;do{destination=FileActions.localChild(localTarget,FileActions.uniqueName(name,n++));}while(localOccupied(destination));}
                    queue.enqueue(false,destination,source,speed,files.cut()?cutCompletion(false,null,source):null);
                }
            }else if(server){
                ChannelSftp target=pane.requireConnection().openSftp();try{ChannelSftp sourceChannel=files.cut()?null:pane.requireConnection().openSftp();try{
                    SftpTransfers copier=new SftpTransfers(target,speed,new AtomicBoolean(),bytes->{},path->{});
                    for(String source:files.paths()){
                        String original=source.substring(source.lastIndexOf('/')+1),destination=SftpTransfers.child(remoteTarget,original);
                        if(files.cut()&&destination.equals(source))continue;
                        if(destination.startsWith(source+"/"))throw new IOException(I18n.t("不能复制或移动到自己的子目录","Cannot copy or move a folder into itself"));
                        if(FileActions.remoteExists(target,destination)){if(files.cut())throw new IOException(I18n.t("目标已存在：","Target already exists: ")+destination);int n=1;do{destination=SftpTransfers.child(remoteTarget,FileActions.uniqueName(original,n++));}while(FileActions.remoteExists(target,destination));}
                        if(files.cut())target.rename(source,destination);else copier.copyRemote(sourceChannel,source,destination);
                    }
                }finally{if(sourceChannel!=null)sourceChannel.disconnect();}}finally{target.disconnect();}
            }else for(Path source:files.local()){
                String name=source.getFileName().toString();Path destination=FileActions.localChild(localTarget,name);
                if(files.cut()&&destination.equals(source.toAbsolutePath().normalize()))continue;
                if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS)){if(files.cut())throw new IOException(I18n.t("目标已存在：","Target already exists: ")+destination);int n=1;do{destination=FileActions.localChild(localTarget,FileActions.uniqueName(name,n++));}while(Files.exists(destination,LinkOption.NOFOLLOW_LINKS));}
                if(destination.startsWith(source.toAbsolutePath().normalize()))throw new IOException(I18n.t("不能复制或移动到自己的子目录","Cannot copy or move a folder into itself"));
                if(files.cut())Files.move(source,destination);else FileActions.localCopy(source,destination);
            }
            return files.remote()!=server;
        },queued->{if(files.cut()&&clipboard==files)clipboard=null;pane.app.events.operation(pane.profile.name(),(files.cut()?I18n.t("剪切粘贴","Cut and paste"):I18n.t("复制粘贴","Copy and paste"))+I18n.t("至 "," to ")+(server?remoteTarget:localTarget));if(queued){refreshQueue();status.setText(I18n.t("已加入传输队列","Added to the transfer queue")+(files.cut()?I18n.t("；传输成功且源未变化后删除源文件","; the source is deleted after a successful transfer if it has not changed"):""));}else if(server)refreshRemote();else refreshLocal();});}catch(Exception e){DesktopApp.error(dialog,e);}
    }
    private TransferQueue.Completion cutCompletion(boolean upload,Path localSource,String remoteSource){return new TransferQueue.Completion(){
        private String signature;
        public void prepare(ChannelSftp channel,TransferQueue.Job job)throws Exception{signature=upload?FileActions.localSignature(localSource):FileActions.remoteSignature(channel,remoteSource);}
        public void complete(ChannelSftp channel,TransferQueue.Job job)throws Exception{
            if(job.cancelled.get())return;
            String current=upload?FileActions.localSignature(localSource):FileActions.remoteSignature(channel,remoteSource);
            if(!signature.equals(current))throw new IOException(I18n.t("传输已完成，但源文件已变化，保留源文件未删除","Transfer finished, but the source changed, so it was kept"));
            if(upload)FileActions.localDelete(localSource);else FileActions.remoteDelete(channel,remoteSource);
        }
    };}
    private boolean remoteOccupied(ChannelSftp channel,String path)throws SftpException{return FileActions.remoteExists(channel,path)||queue.snapshot().stream().anyMatch(job->!job.terminal()&&job.upload&&job.remote.equals(path));}
    private boolean localOccupied(Path path){return Files.exists(path,LinkOption.NOFOLLOW_LINKS)||queue.snapshot().stream().anyMatch(job->!job.terminal()&&!job.upload&&job.local.equals(path));}
    private void upload(){if(busy(false)||busy(true))return;List<Path> selected=local.getSelectedValuesList();if(selected.isEmpty()){JFileChooser picker=new JFileChooser(localPath.getText());picker.setMultiSelectionEnabled(true);picker.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);if(picker.showOpenDialog(dialog)!=JFileChooser.APPROVE_OPTION)return;selected=Arrays.stream(picker.getSelectedFiles()).map(java.io.File::toPath).toList();}start(true,selected,List.of());}
    private void download(){if(busy(false)||busy(true))return;List<SftpTransfers.Entry> selected=remote.getSelectedValuesList();if(selected.isEmpty()){status.setText(I18n.t("请选择远端文件或文件夹","Select a remote file or folder"));return;}start(false,List.of(),selected);}
    private void start(boolean uploading,List<Path> sources,List<SftpTransfers.Entry> entries){
        String targetRemote=remoteDirectory;Path targetLocal;long speed;try{targetLocal=localDirectory;speed=rate();for(Path p:sources)SftpTransfers.validName(p.getFileName().toString());for(var item:entries)SftpTransfers.validName(item.name());pane.requireConnection();}catch(Exception e){DesktopApp.error(dialog,new IllegalArgumentException(I18n.t("请检查连接、文件名和限速（1–1048576 KiB/s，留空为不限速）。","Check the connection, file names and limit (1–1048576 KiB/s, or empty for none)."),e));return;}
        int count=uploading?sources.size():entries.size();if(JOptionPane.showConfirmDialog(dialog,(uploading?I18n.t("上传","Upload"):I18n.t("下载","Download"))+" "+count+I18n.t(" 项至：\n"," item(s) to:\n")+(uploading?targetRemote:targetLocal)+I18n.t("\n文件夹会合并；同名文件完成传输后替换。","\nFolders are merged; files with the same name are replaced after transfer."),I18n.t("加入传输队列","Add to transfer queue"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        boolean packed=archive.isSelected();
        try{if(uploading){for(Path source:sources)queue.enqueue(true,source,SftpTransfers.child(targetRemote,source.getFileName().toString()),speed,null,packed);}else for(var item:entries)queue.enqueue(false,targetLocal.resolve(item.name()),SftpTransfers.child(targetRemote,item.name()),speed,null,packed);refreshQueue();status.setText(I18n.t("已加入 ","Added ")+count+I18n.t(" 项；可继续添加任务"," item(s); you can keep adding tasks"));}catch(Exception e){DesktopApp.error(dialog,e);}
    }
    private TransferQueue.Job selected(){int row=tasks.getSelectedRow();return row>=0&&row<model.jobs.size()?model.jobs.get(row):null;}
    private void showDetails(){TransferQueue.Job job=selected();if(job!=null)status.setText(job.status()+" · "+job.remote+" → "+job.local+(job.error.isBlank()?"":" · "+job.error));}
    private void refreshQueue(){if(closed)return;TransferQueue.Job selected=selected();List<TransferQueue.Job> snapshot=queue.snapshot();model.jobs=snapshot;model.fireTableDataChanged();if(selected!=null){int row=snapshot.indexOf(selected);if(row>=0)tasks.setRowSelectionInterval(row,row);}int complete=(int)snapshot.stream().filter(TransferQueue.Job::terminal).count();if(complete!=finished){finished=complete;refreshLocal();refreshRemote();}showDetails();}
    private static final class QueueModel extends AbstractTableModel{
        static final int PROGRESS=5;
        List<TransferQueue.Job> jobs=List.of();private final String[] columns={I18n.t("文件 / 文件夹","File / folder"),I18n.t("方向","Direction"),I18n.t("来源","From"),I18n.t("目标","To"),I18n.t("状态","Status"),I18n.t("进度","Progress"),I18n.t("已传输 / 总量","Transferred / total"),I18n.t("速度","Speed")};public int getRowCount(){return jobs.size();}public int getColumnCount(){return columns.length;}public String getColumnName(int c){return columns[c];}public Class<?> getColumnClass(int c){return c==PROGRESS?TransferQueue.Job.class:String.class;}
        public Object getValueAt(int row,int column){TransferQueue.Job j=jobs.get(row);return switch(column){case 0->j.name();case 1->j.upload?I18n.t("上传","Upload"):I18n.t("下载","Download");case 2->j.from();case 3->j.to();case 4->j.status();case PROGRESS->j;case 6->transferred(j);default->j.state==TransferQueue.State.RUNNING?size(j.speed())+"/s":"—";};}
        private static String transferred(TransferQueue.Job j){
            // "+" while the walk is still finding files: the totals are not final yet.
            String more=j.walking?"+":"";
            String files=j.files>1?" · "+j.done+" / "+j.files+more+I18n.t(" 个文件"," files"):"";
            if(j.state==TransferQueue.State.WAITING)return I18n.t("等待","Waiting");
            return size(j.bytes)+" / "+(j.total>0?size(j.total)+more:"?")+files;
        }
    }
}
