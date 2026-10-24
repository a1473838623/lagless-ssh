package dev.lagless.desktop;

import dev.lagless.AnsiUtil;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, paged history browser; a multi-gigabyte transcript never loads into the EDT. */
final class LogViewer {
    static final int PAGE_BYTES=256*1024;
    record Page(String text,long start,long end,long length){ }
    private final DesktopApp app;
    private final JDialog dialog;
    private final DefaultListModel<Path> files=new DefaultListModel<>();
    private final JList<Path> list=new JList<>(files);
    private final LogTextView text=new LogTextView();
    private final JLabel status=new JLabel("选择一份日志查看");
    private final JComboBox<String> kind=new JComboBox<>(new String[]{"终端历史","全量输入输出历史","软件日志"});
    private final JCheckBox follow=new JCheckBox("跟随最新"),wrap=new JCheckBox("自动换行",true);
    private final JButton rawExport=DesktopApp.button("导出原始远端输出",this::exportRaw);
    private final JTextField search=new ImeTextField(18);
    private final AtomicLong generation=new AtomicLong();
    private final javax.swing.Timer timer;
    private static final int MAX_WINDOW_CHARS=1_200_000;
    private final Deque<Page> loaded=new ArrayDeque<>();
    private Page page,lastTail;private Path selected;private boolean closed,loading;private long reloadRequest;
    LogViewer(DesktopApp app,Component parent,boolean events,Path initial){
        this(app,parent,events?2:1,initial);
    }
    LogViewer(DesktopApp app,Component parent,int category,Path initial){
        this.app=app;dialog=new JDialog(parent instanceof Window w?w:SwingUtilities.getWindowAncestor(parent),"日志查看",Dialog.ModalityType.MODELESS);
        text.setEditable(false);text.setLineWrap(true);text.setWrapStyleWord(true);list.setFixedCellHeight(54);list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((view,path,index,chosen,focus)->{String name=path.getFileName().toString();String caption=name;
            if(kind.getSelectedIndex()==2){int end=name.indexOf("-application-");if(end>0)try{String stamp=name.substring(0,end);String time=stamp.substring(0,10)+"T"+stamp.substring(11).replaceFirst("-",":").replaceFirst("-",":");caption=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.parse(time))+" · 软件运行";}catch(DateTimeException ignored){}
                if(path.equals(app.events.path()))caption="本次运行 · "+caption;}
            JLabel label=new JLabel("<html>"+escape(caption)+"</html>");label.setToolTipText(path.toString());label.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));label.setOpaque(true);label.setBackground(chosen?new Color(0xdcefe9):Color.WHITE);return label;});
        JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT,8,8));top.add(kind);top.add(DesktopApp.button("刷新",this::reload));top.add(wrap);top.add(follow);top.add(search);top.add(DesktopApp.button("查找下一个",this::find));
        JPanel bottom=new JPanel(new BorderLayout());JPanel actions=DesktopApp.row();actions.add(DesktopApp.button("导出当前记录",this::export));actions.add(rawExport);rawExport.setEnabled(false);bottom.add(actions,BorderLayout.NORTH);bottom.add(status,BorderLayout.SOUTH);
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new EditorScrollPane(list),text);split.setDividerLocation(310);split.setResizeWeight(.25);split.setBorder(null);
        dialog.add(top,BorderLayout.NORTH);dialog.add(split,BorderLayout.CENTER);dialog.add(bottom,BorderLayout.SOUTH);dialog.setSize(1120,720);dialog.setLocationRelativeTo(parent);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        list.addListSelectionListener(e->{if(!e.getValueIsAdjusting()){Path choice=list.getSelectedValue();if(!Objects.equals(selected,choice)){selected=choice;loaded.clear();page=lastTail=null;loading=false;updateRawExport();if(selected!=null)loadLatest(false);}}});kind.setSelectedIndex(Math.max(0,Math.min(category,2)));kind.addActionListener(e->{updateRawExport();reload();});wrap.addActionListener(e->{text.setLineWrap(wrap.isSelected());text.setWrapStyleWord(wrap.isSelected());});search.addActionListener(e->find());
        text.onScrollEdges(this::loadOlder,this::loadNewer,()->follow.setSelected(false));
        timer=new javax.swing.Timer(1500,e->{if(follow.isSelected()&&selected!=null)loadLatest(true);});dialog.addWindowListener(new java.awt.event.WindowAdapter(){public void windowClosed(java.awt.event.WindowEvent e){closed=true;generation.incrementAndGet();timer.stop();text.close();}});
        if(kind.getSelectedIndex()==2)follow.setSelected(true);
        dialog.setVisible(true);reload(initial==null&&kind.getSelectedIndex()==2?app.events.path():initial);timer.start();
    }
    private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
    private void reload(){reload(null);}
    private void reload(Path initial){long request=++reloadRequest;generation.incrementAndGet();loading=false;int category=kind.getSelectedIndex();app.background(dialog,()->{
        List<Path> result=new ArrayList<>();for(String folder:new String[]{category==0?"desktop/scrollback":category==1?"logs":"desktop/events"}){
            Path directory=app.home.resolve(folder);if(!Files.isDirectory(directory))continue;try(var paths=Files.list(directory)){paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&p.getFileName().toString().endsWith(".log")).forEach(result::add);}
        }result.sort(Comparator.comparing((Path p)->p.getFileName().toString()).reversed());
        if(category==2){Path current=app.events.path();if(result.remove(current))result.add(0,current);}return result;
    },paths->{if(closed||request!=reloadRequest)return;Path previous=initial==null?selected:initial;files.clear();paths.forEach(files::addElement);if(previous!=null&&paths.contains(previous)){list.setSelectedValue(previous,true);if(Objects.equals(selected,previous)&&!loading)loadLatest(false);}else if(!paths.isEmpty())list.setSelectedIndex(0);else{selected=null;loaded.clear();page=lastTail=null;text.setText("");status.setText("暂无日志");}});}
    private void loadLatest(boolean onlyIfChanged){
        Path file=selected;if(file==null||closed||loading)return;
        loading=true;long request=generation.incrementAndGet();
        app.background(dialog,()->readPage(file,-1),result->{
            if(closed||request!=generation.get()||!file.equals(selected))return;
            loading=false;
            if(onlyIfChanged&&lastTail!=null&&lastTail.text().equals(result.text())){
                lastTail=result;
                if(page!=null&&page.length()!=result.length()){
                    page=new Page(page.text(),page.start(),page.end(),result.length());
                    status.setText(selected.getFileName()+" · 已载入 "+page.start()+"–"+page.end()+" / "+page.length()+" 字节 · 滚动时自动读取其余内容");
                }
                return;
            }
            lastTail=result;loaded.clear();loaded.add(result);renderWindow(false,0);
        });
    }
    private void loadOlder(){
        Path file=selected;if(file==null||page==null||page.start()==0||closed||loading)return;
        long end=page.start(),start=Math.max(0,end-PAGE_BYTES),request=generation.incrementAndGet();loading=true;
        app.background(dialog,()->readRange(file,start,end,true,false),result->{
            if(closed||request!=generation.get()||!file.equals(selected))return;
            loading=false;if(result.start()>=end)return;
            loaded.addFirst(result);trimWindow(false);renderWindow(true,result.text().length());
        });
    }
    private void loadNewer(){
        Path file=selected;if(file==null||page==null||closed||loading)return;
        if(page.end()>=page.length()){if(!follow.isSelected())loadLatest(true);return;}
        long start=page.end(),request=generation.incrementAndGet();loading=true;
        app.background(dialog,()->readRange(file,start,start+PAGE_BYTES,false,true),result->{
            if(closed||request!=generation.get()||!file.equals(selected))return;
            loading=false;if(result.end()<=start)return;
            loaded.addLast(result);int removed=trimWindow(true);renderWindow(true,-removed);
        });
    }
    private int trimWindow(boolean removeOldest){
        int total=loaded.stream().mapToInt(p->p.text().length()).sum();
        int removedChars=0;
        while(total>MAX_WINDOW_CHARS&&loaded.size()>1){Page removed=removeOldest?loaded.removeFirst():loaded.removeLast();total-=removed.text().length();removedChars+=removed.text().length();}
        return removedChars;
    }
    private void renderWindow(boolean preserve,int prefixChars){
        StringBuilder content=new StringBuilder();for(Page part:loaded)content.append(part.text());
        Page first=loaded.peekFirst(),last=loaded.peekLast();
        if(first==null||last==null)return;
        page=new Page(content.toString(),first.start(),last.end(),lastTail==null?last.length():Math.max(last.length(),lastTail.length()));
        if(preserve)text.setTextPreservingView(page.text(),prefixChars);else{text.setText(page.text());text.scrollToBottom();}
        status.setText(selected.getFileName()+" · 已载入 "+page.start()+"–"+page.end()+" / "+page.length()+" 字节 · 滚动时自动读取其余内容");
    }
    static Page readPage(Path file,long offset)throws IOException{
        try(FileChannel in=FileChannel.open(file,StandardOpenOption.READ)){
            JournalTail.Snapshot snapshot=JournalTail.snapshot(file,in.size());long base=snapshot.length(),length=base+snapshot.suffix().length,start=offset<0?Math.max(0,length-PAGE_BYTES):Math.min(Math.max(0,offset),length);
            byte[] raw=new byte[(int)Math.min(PAGE_BYTES,length-start)];int disk=(int)Math.min(raw.length,Math.max(0,base-start));
            if(disk>0){ByteBuffer bytes=ByteBuffer.wrap(raw,0,disk);in.position(start);while(bytes.hasRemaining()){if(in.read(bytes)<0)throw new EOFException("日志意外截断");}}
            if(disk<raw.length)System.arraycopy(snapshot.suffix(),(int)Math.max(0,start-base),raw,disk,raw.length-disk);
            int from=0,to=raw.length;
            if(offset<0&&start>0){for(int i=0;i<to;i++)if(raw[i]=='\n'){from=i+1;break;}}
            // Forward paging resumes at the exact consumed byte, including long lines.
            // Tail/random offsets may land in a UTF-8 character; omit its continuation bytes.
            while(from<to&&(raw[from]&0xc0)==0x80)from++;
            if(start+to<length&&to>from){int lead=to-1;while(lead>from&&(raw[lead]&0xc0)==0x80)lead--;int first=raw[lead]&255;int expected=first<128?1:first<224?2:first<240?3:4;if(to-lead<expected)to=lead;}
            return new Page(AnsiUtil.strip(new String(raw,from,to-from,StandardCharsets.UTF_8)),start+from,start+to,length);
        }
    }
    static Page readRange(Path file,long requestedStart,long requestedEnd,boolean alignStart,boolean alignEnd)throws IOException{
        try(FileChannel in=FileChannel.open(file,StandardOpenOption.READ)){
            JournalTail.Snapshot snapshot=JournalTail.snapshot(file,in.size());long base=snapshot.length(),length=base+snapshot.suffix().length;
            long start=Math.max(0,Math.min(requestedStart,length)),end=Math.max(start,Math.min(requestedEnd,length));
            end=Math.min(end,start+PAGE_BYTES);
            byte[] raw=new byte[(int)(end-start)];int disk=(int)Math.min(raw.length,Math.max(0,base-start));
            if(disk>0){ByteBuffer bytes=ByteBuffer.wrap(raw,0,disk);in.position(start);while(bytes.hasRemaining())if(in.read(bytes)<0)throw new EOFException("日志意外截断");}
            if(disk<raw.length)System.arraycopy(snapshot.suffix(),(int)Math.max(0,start-base),raw,disk,raw.length-disk);
            int from=0,to=raw.length;
            if(alignStart&&start>0)for(int i=0;i<to;i++)if(raw[i]=='\n'){from=i+1;break;}
            if(alignEnd&&end<length)for(int i=to-1;i>=from;i--)if(raw[i]=='\n'){to=i+1;break;}
            while(from<to&&(raw[from]&0xc0)==0x80)from++;
            if(start+to<length&&to>from){int lead=to-1;while(lead>from&&(raw[lead]&0xc0)==0x80)lead--;int first=raw[lead]&255;int expected=first<128?1:first<224?2:first<240?3:4;if(to-lead<expected)to=lead;}
            return new Page(AnsiUtil.strip(new String(raw,from,to-from,StandardCharsets.UTF_8)),start+from,start+to,length);
        }
    }
    private void find(){String term=search.getText();if(term.isEmpty())return;String content=text.getText();int at=content.indexOf(term,text.getSelectionEnd());if(at<0)at=content.indexOf(term);if(at>=0){text.requestFocusInWindow();text.select(at,at+term.length());}else status.setText("当前已载入内容没有找到；滚动可继续读取更多内容。");}
    private void export(){Path file=selected;if(file==null)return;Path target=DesktopApp.chooseSave(dialog,file.getFileName().toString());if(target==null)return;app.background(dialog,()->{JournalTail.export(file,target);return target;},saved->status.setText("已导出："+saved));}
    private void updateRawExport(){rawExport.setEnabled(kind.getSelectedIndex()==1&&selected!=null&&Files.isRegularFile(rawPath(selected)));}
    private static Path rawPath(Path log){return log.resolveSibling(log.getFileName()+".vt");}
    private void exportRaw(){Path file=selected==null?null:rawPath(selected);if(file==null||!Files.isRegularFile(file))return;Path target=DesktopApp.chooseSave(dialog,file.getFileName().toString());if(target==null)return;app.background(dialog,()->{if(!file.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize()))Files.copy(file,target,StandardCopyOption.REPLACE_EXISTING);return target;},saved->status.setText("已导出原始远端输出："+saved));}
}
