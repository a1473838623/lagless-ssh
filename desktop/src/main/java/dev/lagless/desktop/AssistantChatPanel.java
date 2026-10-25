package dev.lagless.desktop;

import dev.lagless.I18n;
import dev.lagless.Json;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.imageio.ImageIO;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Responsive assistant with persistent conversations and a local composer. */
final class AssistantChatPanel extends JPanel {
    private static final String[] ACTIONS={I18n.t("分析 / 问答","Analyze / Q&A"),I18n.t("自然语言转命令","Natural language to command"),I18n.t("补全命令","Complete command"),I18n.t("翻译","Translate"),I18n.t("翻译命令","Translate command")};
    /** Sent when context is selected but the composer is empty; shown in the composer so it can be edited. */
    private static final String[] DEFAULT_REQUESTS={I18n.t("请分析所选上下文，说明其中的关键信息和问题。","Analyze the selected context and explain the key points and any problems."),I18n.t("根据所选上下文，给出下一步要执行的命令。","Based on the selected context, suggest the command to run next."),I18n.t("补全所选上下文中的命令。","Complete the command in the selected context."),I18n.t("翻译所选上下文。","Translate the selected context."),I18n.t("翻译所选上下文中的命令，并说明其作用。","Translate the commands in the selected context and explain what they do.")};
    private static final String[] CONTEXTS={I18n.t("不附加终端输出","No terminal output"),I18n.t("最近 20 行","Last 20 lines"),I18n.t("最近 100 行","Last 100 lines"),I18n.t("最近 500 行","Last 500 lines"),I18n.t("全部终端历史","All terminal history")};
    private final SessionPane session;
    private final JComboBox<Conversation> conversations=new JComboBox<>();
    private final JComboBox<String> action=new JComboBox<>(ACTIONS),context=new JComboBox<>(CONTEXTS);
    private final JComboBox<AssistantSettings.Service> service=new JComboBox<>();
    private final JComboBox<String> model=new JComboBox<>();
    private final JTextArea composer=DesktopApp.textArea();
    private final DefaultListModel<ReferenceItem> attachmentModel=new DefaultListModel<>();
    private final JList<ReferenceItem> attachments=new JList<>(attachmentModel);
    private final JPanel referenceContent=new JPanel(new CardLayout());
    private final JLabel referenceHint=new JLabel(I18n.t("<html><div style='width:260px;text-align:center'>暂无附件。可添加图片、PDF、TXT/MD、指定日志，或在下方粘贴图片。</div></html>","<html><div style='width:260px;text-align:center'>No attachments. Add images, PDF, TXT/MD or specific logs, or paste an image below.</div></html>"),SwingConstants.CENTER);
    private final Set<Path> pastedImages=Collections.synchronizedSet(new HashSet<>());
    private final JCheckBox includeOtherSessions=new JCheckBox(I18n.t("附加协作 SSH","Attach other SSH sessions"));
    private final JTextField sourceLanguage=DesktopApp.field(I18n.t("自动检测","Auto detect")),targetLanguage=DesktopApp.field(I18n.t("简体中文","English"));
    private final JLabel status=new JLabel(" ");
    private final JButton send=new JButton(I18n.t("发送","Send"));
    private final Path store;
    private final javax.swing.Timer streamRefresh;
    private int nextConversation=1;
    private boolean changingSelection;
    private String autoPrompt="";
    private int logRows=200;

    private record Message(String role,String text){}
    /** {@code lines} is how many of a log's latest lines to send; 0 sends its latest page. */
    private record ReferenceItem(Path path,String kind,boolean temporary,int lines){
        ReferenceItem(Path path,String kind,boolean temporary){this(path,kind,temporary,0);}
        @Override public String toString(){return kind+" · "+path.getFileName()+(lines>0?I18n.t(" · 最新 "," · latest ")+lines+I18n.t(" 行"," lines"):"");}
        boolean log(){return kind.endsWith(I18n.t("日志","log"))||kind.equals(I18n.t("终端历史","Terminal history"))||kind.equals(I18n.t("全量输入输出历史","Full input/output history"));}
    }
    private static final class Conversation {
        String title;final JPanel panel=new JPanel(new BorderLayout());final JEditorPane transcript=new JEditorPane("text/html","");final JScrollPane scroll;final StringBuilder markdown=new StringBuilder();final List<Message> messages=new ArrayList<>();final ConcurrentLinkedQueue<String> pending=new ConcurrentLinkedQueue<>();
        volatile boolean busy;volatile long generation;volatile Future<?> future;
        Conversation(String title){this.title=title;transcript.setEditable(false);transcript.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES,Boolean.TRUE);transcript.setFont(DesktopFonts.ui().deriveFont(13f));transcript.setBorder(new EmptyBorder(15,18,15,18));transcript.setBackground(new Color(0xf6f8fa));transcript.setCaret(new DefaultCaret(){@Override public int getBlinkRate(){return 0;}@Override public void setVisible(boolean visible){super.setVisible(false);}});transcript.setCaretColor(transcript.getBackground());scroll=new JScrollPane(transcript);panel.add(scroll,BorderLayout.CENTER);panel.putClientProperty("conversation",this);}
        @Override public String toString(){return title;}
    }

    AssistantChatPanel(SessionPane session){
        super(new BorderLayout(8,8));this.session=session;setBorder(new EmptyBorder(8,8,8,8));
        store=session.app.home.resolve("desktop/assistant-sessions").resolve(safe(session.profile.id())+".json");

        sourceLanguage.setColumns(9);targetLanguage.setColumns(9);
        JPanel heading=new JPanel(new BorderLayout(4,4));heading.add(new JLabel(I18n.t("AI 助手对话","AI assistant chat")),BorderLayout.NORTH);
        JPanel historyActions=new JPanel(new WrapLayout(FlowLayout.LEADING,5,3));JButton fresh=button(I18n.t("新对话","New chat"),this::newConversation);JButton remove=button(I18n.t("删除历史","Delete history"),this::deleteConversation);historyActions.add(conversations);historyActions.add(fresh);historyActions.add(remove);heading.add(historyActions,BorderLayout.CENTER);add(heading,BorderLayout.NORTH);

        JPanel body=new JPanel(new BorderLayout(0,8));
        conversations.setName("assistant-conversations");conversations.setPrototypeDisplayValue(new Conversation(I18n.t("YYYY年MM月DD日 HH:mm · 对话标题","YYYY-MM-DD HH:mm · chat title")));conversations.setPreferredSize(new Dimension(220,conversations.getPreferredSize().height));conversations.addActionListener(e->{if(!changingSelection)updateSendState();});
        loadConversations();if(conversations.getItemCount()==0)newConversation();
        Conversation selected=current();if(selected!=null)body.add(selected.panel,BorderLayout.CENTER);add(body,BorderLayout.CENTER);
        conversations.addActionListener(e->showSelected(body));

        JPanel composerArea=new JPanel();composerArea.setLayout(new BoxLayout(composerArea,BoxLayout.Y_AXIS));
        JPanel options=new JPanel(new WrapLayout(FlowLayout.LEADING,6,5));options.setAlignmentX(Component.LEFT_ALIGNMENT);
        options.add(labelled(I18n.t("功能","Feature"),action));options.add(labelled(I18n.t("配置","Configure"),service));options.add(labelled(I18n.t("模型","Model"),model));options.add(labelled(I18n.t("上下文","Context"),context));options.add(includeOtherSessions);
        composerArea.add(options);
        JPanel translations=new JPanel(new WrapLayout(FlowLayout.LEADING,6,4));translations.setAlignmentX(Component.LEFT_ALIGNMENT);translations.add(new JLabel(I18n.t("源语言","Source language")));translations.add(sourceLanguage);translations.add(new JLabel(I18n.t("目标语言","Target language")));translations.add(targetLanguage);composerArea.add(translations);
        JPanel referenceArea=new JPanel(new BorderLayout(4,4));referenceArea.setAlignmentX(Component.LEFT_ALIGNMENT);referenceArea.setBorder(BorderFactory.createTitledBorder(I18n.t("参考资料（仅随本次提问发送）","References (sent with this question only)")));referenceArea.setMaximumSize(new Dimension(Integer.MAX_VALUE,120));
        JPanel fileButtons=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));fileButtons.add(button(I18n.t("添加图片 / 文件","Add image / file"),this::chooseAttachments));fileButtons.add(button(I18n.t("选择具体日志","Choose logs"),this::chooseLogs));fileButtons.add(button(I18n.t("移除选中","Remove selected"),this::removeSelectedReference));referenceArea.add(fileButtons,BorderLayout.NORTH);
        referenceHint.setForeground(DesktopApp.MUTED);referenceHint.setBorder(new EmptyBorder(7,7,7,7));referenceContent.add(referenceHint,"empty");attachments.setVisibleRowCount(2);attachments.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);attachments.setCellRenderer((list,value,index,selectedItem,focus)->{JLabel l=new JLabel(value.toString());l.setToolTipText(value.path().toString());l.setBorder(new EmptyBorder(3,6,3,6));l.setOpaque(true);l.setBackground(selectedItem?new Color(0xdcefe9):list.getBackground());l.setForeground(list.getForeground());return l;});referenceContent.add(new JScrollPane(attachments),"list");referenceArea.add(referenceContent,BorderLayout.CENTER);referenceArea.setPreferredSize(new Dimension(500,90));composerArea.add(referenceArea);attachmentModel.addListDataListener(new javax.swing.event.ListDataListener(){public void intervalAdded(javax.swing.event.ListDataEvent e){updateReferences();}public void intervalRemoved(javax.swing.event.ListDataEvent e){updateReferences();}public void contentsChanged(javax.swing.event.ListDataEvent e){updateReferences();}});updateReferences();
        composer.setLineWrap(true);composer.setWrapStyleWord(true);composer.setFont(DesktopFonts.ui().deriveFont(13f));composer.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(190,200,210)),new EmptyBorder(8,9,8,9)));ClipboardActions.install(composer,status::setText,null,this::pasteImage);
        composer.setRows(3);
        JScrollPane composeScroll=new JScrollPane(composer);composeScroll.setAlignmentX(Component.LEFT_ALIGNMENT);composeScroll.setMinimumSize(new Dimension(100,70));composeScroll.setPreferredSize(new Dimension(500,82));composeScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE,100));composerArea.add(composeScroll);
        JPanel sendRow=new JPanel(new BorderLayout(8,0));sendRow.setAlignmentX(Component.LEFT_ALIGNMENT);sendRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,42));status.setForeground(DesktopApp.MUTED);sendRow.add(status,BorderLayout.CENTER);JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,5,0));JButton cancel=button(I18n.t("停止","Stop"),this::cancelRequest);actions.add(cancel);actions.add(button(I18n.t("插入回复到本地草稿","Insert reply into local draft"),this::insertReply));actions.add(send);sendRow.add(actions,BorderLayout.EAST);composerArea.add(sendRow);add(composerArea,BorderLayout.SOUTH);

        send.addActionListener(e->sendMessage());composer.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"send-assistant");composer.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,InputEvent.SHIFT_DOWN_MASK),"insert-break");composer.getActionMap().put("send-assistant",new AbstractAction(){public void actionPerformed(ActionEvent e){sendMessage();}});composer.getActionMap().put("insert-break",new AbstractAction(){public void actionPerformed(ActionEvent e){composer.replaceSelection("\n");}});
        action.addActionListener(e->{translations.setVisible(action.getSelectedIndex()>=3);composerArea.revalidate();refreshDefaultPrompt();});translations.setVisible(false);
        context.addActionListener(e->refreshDefaultPrompt());includeOtherSessions.addActionListener(e->refreshDefaultPrompt());
        JButton settings=button(I18n.t("AI 配置","AI settings"),()->{session.app.assistantSettings(session);reloadServices();});
        options.add(settings);
        service.addActionListener(e->reloadModels());reloadServices();streamRefresh=new javax.swing.Timer(60,e->flushStreams());streamRefresh.start();
    }

    private static String safe(String value){return value.replaceAll("[^A-Za-z0-9_.-]","_");}
    private static JButton button(String text,Runnable action){JButton b=new JButton(text);b.setFocusable(false);b.addActionListener(e->action.run());return b;}
    private static JPanel labelled(String title,JComponent component){JPanel p=new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));p.add(new JLabel(title));p.add(component);return p;}
    private Conversation current(){Object item=conversations.getSelectedItem();return item instanceof Conversation c?c:null;}
    private void showSelected(JPanel body){Conversation c=current();body.removeAll();if(c!=null){body.add(c.panel,BorderLayout.CENTER);render(c);}body.revalidate();body.repaint();updateSendState();}
    private void newConversation(){Conversation c=new Conversation(I18n.t("对话 ","Chat ")+java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))+" · "+(nextConversation++));changingSelection=true;conversations.addItem(c);conversations.setSelectedItem(c);changingSelection=false;persist();updateSendState();SwingUtilities.invokeLater(this::focusComposer);}
    private void deleteConversation(){Conversation c=current();if(c==null)return;if(JOptionPane.showConfirmDialog(this,I18n.t("永久删除「","Permanently delete \"")+c.title+I18n.t("」及其中的对话记录？","\" and its messages?"),I18n.t("删除 AI 对话","Delete AI chat"),JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.OK_OPTION)return;if(c.future!=null)c.future.cancel(true);c.generation++;changingSelection=true;conversations.removeItem(c);changingSelection=false;if(conversations.getItemCount()==0)newConversation();persist();status.setText(I18n.t("已删除该对话记录。","Chat deleted."));}
    private void cancelRequest(){Conversation c=current();if(c!=null&&c.busy){c.generation++;if(c.future!=null)c.future.cancel(true);c.busy=false;status.setText(I18n.t("已停止当前对话请求。","Current request stopped."));updateSendState();}}
    private void insertReply(){Conversation c=current();if(c==null)return;String text=c.messages.stream().filter(m->m.role.equals("assistant")).reduce((a,b)->b).map(Message::text).orElse("");if(!text.isBlank()){session.draft.append(text);status.setText(I18n.t("已插入本地草稿；请检查后再发送。","Inserted into the local draft; review it before sending."));}}
    void focusComposer(){composer.requestFocusInWindow();}
    private void loadConversations(){try{if(!Files.isRegularFile(store))return;Object root=Json.parse(Files.readString(store));for(Object item:Json.list(root,"conversations")){String title=Json.string(item,"title");if(title==null)continue;Conversation c=new Conversation(title);for(Object msg:Json.list(item,"messages")){String role=Json.string(msg,"role"),text=Json.string(msg,"text");if(role!=null&&text!=null)c.messages.add(new Message(role,text));}changingSelection=true;conversations.addItem(c);changingSelection=false;}nextConversation=conversations.getItemCount()+1;if(conversations.getItemCount()>0)conversations.setSelectedIndex(conversations.getItemCount()-1);}catch(Exception e){session.app.events.operation(session.profile.name(),I18n.t("AI助手历史读取失败：","Failed to read AI assistant history: ")+e.getMessage());}}
    private void persist(){try{List<Object> values=new ArrayList<>();for(int i=0;i<conversations.getItemCount();i++){Conversation c=conversations.getItemAt(i);List<Object> messages=c.messages.stream().map(m->(Object)Map.of("role",m.role,"text",m.text)).toList();values.add(Map.of("title",c.title,"messages",messages));}Files.createDirectories(store.getParent());ProfileStore.atomicWrite(store,Json.stringify(Map.of("version",1,"conversations",values)));}catch(Exception e){session.app.events.operation(session.profile.name(),I18n.t("AI助手历史保存失败：","Failed to save AI assistant history: ")+e.getMessage());}}
    private void reloadServices(){try{AssistantSettings settings=AssistantSettings.load(session.app.home);Object previous=service.getSelectedItem();service.removeAllItems();settings.services.forEach(service::addItem);if(previous!=null)service.setSelectedItem(previous);if(service.getSelectedItem()==null&&settings.current()!=null)service.setSelectedItem(settings.current());reloadModels();}catch(Exception error){status.setText(I18n.t("AI 配置读取失败：","Failed to read AI configuration: ")+error.getMessage());}}
    private void reloadModels(){model.removeAllItems();AssistantSettings.Service selected=(AssistantSettings.Service)service.getSelectedItem();if(selected!=null)selected.models().forEach(model::addItem);}
    private void updateReferences(){((CardLayout)referenceContent.getLayout()).show(referenceContent,attachmentModel.isEmpty()?"empty":"list");referenceContent.revalidate();referenceContent.repaint();refreshDefaultPrompt();}
    private boolean hasContext(){return context.getSelectedIndex()>0||includeOtherSessions.isSelected()||!attachmentModel.isEmpty();}
    /** Shows the feature's default request while context is selected and the user has not written their own. */
    private void refreshDefaultPrompt(){
        String text=composer.getText();
        if(!text.isEmpty()&&!text.equals(autoPrompt))return;
        String next=hasContext()?DEFAULT_REQUESTS[Math.max(0,action.getSelectedIndex())]:"";
        if(!next.equals(text))composer.setText(next);
        autoPrompt=next;
    }
    private void addReference(ReferenceItem item){
        // Choosing a log again only updates how many of its lines are sent.
        for(int i=0;i<attachmentModel.size();i++)if(attachmentModel.get(i).path().equals(item.path())){if(attachmentModel.get(i).lines()!=item.lines()){attachmentModel.set(i,item);status.setText(I18n.t("已更新：","Updated: ")+item);}return;}
        if(attachmentModel.size()>=12){status.setText(I18n.t("每次最多附加 12 项参考资料。","At most 12 references per message."));return;}
        attachmentModel.addElement(item);status.setText(I18n.t("已添加：","Added: ")+item);
        session.app.events.operation(session.profile.name(),I18n.t("AI助手参考资料添加；类型=","AI assistant reference added; type=")+item.kind()+I18n.t("；文件=","; file=")+item.path().getFileName());
    }
    private void removeSelectedReference(){for(ReferenceItem item:attachments.getSelectedValuesList()){attachmentModel.removeElement(item);if(item.temporary()){pastedImages.remove(item.path());try{Files.deleteIfExists(item.path());}catch(IOException ignored){}}}}
    private void chooseAttachments(){
        JFileChooser chooser=new JFileChooser();chooser.setDialogTitle(I18n.t("添加图片、PDF 或文本文件","Add image, PDF or text file"));chooser.setMultiSelectionEnabled(true);
        chooser.setAcceptAllFileFilterUsed(false);chooser.setFileFilter(new FileNameExtensionFilter(I18n.t("支持的文件（图片、PDF、TXT、MD）","Supported files (images, PDF, TXT, MD)"),"png","jpg","jpeg","gif","webp","pdf","txt","md"));
        if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;
        for(java.io.File file:chooser.getSelectedFiles())addReference(new ReferenceItem(file.toPath(),I18n.t("文件","File"),false));
    }
    private void chooseLogs(){
        List<ReferenceItem> available=new ArrayList<>();
        for(String[] category:new String[][]{{"logs",I18n.t("全量输入输出历史","Full input/output history")},{"desktop/events",I18n.t("软件日志","Application log")},{"desktop/scrollback",I18n.t("终端历史","Terminal history")}}){
            Path directory=session.app.home.resolve(category[0]);if(!Files.isDirectory(directory))continue;
            try(var paths=Files.list(directory)){paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&p.getFileName().toString().endsWith(".log"))
                    .forEach(p->available.add(new ReferenceItem(p,category[1],false)));}
            catch(IOException error){status.setText(I18n.t("读取","Read")+category[1]+I18n.t("失败：","Failed: ")+error.getMessage());}
        }
        available.sort(Comparator.comparing((ReferenceItem r)->r.path().getFileName().toString()).reversed());
        if(available.isEmpty()){status.setText(I18n.t("尚无可选择的日志。","No logs to choose yet."));return;}
        JList<ReferenceItem> picker=new JList<>(available.toArray(ReferenceItem[]::new));picker.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);picker.setName("assistant-log-picker");
        picker.setCellRenderer((list,value,index,selected,focus)->{JLabel label=new JLabel(value.toString());label.setToolTipText(value.path().toString());label.setBorder(new EmptyBorder(5,8,5,8));label.setOpaque(true);label.setBackground(selected?new Color(0xdcefe9):list.getBackground());label.setForeground(list.getForeground());return label;});
        JScrollPane scroll=new JScrollPane(picker);scroll.setPreferredSize(new Dimension(300,360));
        JSpinner rows=new JSpinner(new SpinnerNumberModel(logRows,1,100_000,50));rows.setName("assistant-log-rows");
        JTextArea preview=DesktopApp.textArea();preview.setName("assistant-log-preview");preview.setEditable(false);preview.setFont(DesktopFonts.monospace(12));preview.setMargin(new Insets(6,8,6,8));
        JLabel previewStatus=new JLabel(I18n.t("选择左侧日志查看内容。","Select a log on the left to see its content."));previewStatus.setForeground(DesktopApp.MUTED);
        JScrollPane previewScroll=new JScrollPane(preview);previewScroll.setPreferredSize(new Dimension(520,360));
        long[] previewGeneration={0};
        Runnable showPreview=()->{
            ReferenceItem item=picker.getSelectedValue();int count=(Integer)rows.getValue();long generation=++previewGeneration[0];
            if(item==null){preview.setText("");previewStatus.setText(I18n.t("选择左侧日志查看内容。","Select a log on the left to see its content."));return;}
            previewStatus.setText(I18n.t("正在读取 ","Reading ")+item.path().getFileName()+"…");
            session.app.workers.submit(()->{
                String text,note;
                try{text=tailLines(item.path(),count);long shown=text.isEmpty()?0:text.chars().filter(c->c=='\n').count()+1;note=item.path().getFileName()+I18n.t(" · 显示最新 "," · showing the latest ")+shown+I18n.t(" 行（将发送的内容）"," lines (what will be sent)");}
                catch(Exception error){text="";note=I18n.t("读取失败：","Read failed: ")+error.getMessage();}
                String loaded=text,message=note;
                SwingUtilities.invokeLater(()->{if(generation!=previewGeneration[0])return;preview.setText(loaded);preview.setCaretPosition(loaded.length());previewStatus.setText(message);});
            });
        };
        picker.addListSelectionListener(e->{if(!e.getValueIsAdjusting())showPreview.run();});rows.addChangeListener(e->showPreview.run());
        picker.setSelectedIndex(0);
        JPanel previewPanel=new JPanel(new BorderLayout(0,4));previewPanel.add(previewStatus,BorderLayout.NORTH);previewPanel.add(previewScroll,BorderLayout.CENTER);
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,scroll,previewPanel);split.setResizeWeight(0.35);split.setBorder(null);
        JPanel rowsPanel=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));rowsPanel.add(new JLabel(I18n.t("发送每份日志最新","Send the latest")));rowsPanel.add(rows);rowsPanel.add(new JLabel(I18n.t("行","lines of each log")));
        JPanel panel=new JPanel(new BorderLayout(0,8));panel.add(new JLabel(I18n.t("可多选具体日志，右侧显示选中日志将发送的内容；内容仅随本次请求发送。","Choose one or more logs. The right side shows what will be sent from the highlighted log, with this request only.")),BorderLayout.NORTH);panel.add(split,BorderLayout.CENTER);panel.add(rowsPanel,BorderLayout.SOUTH);
        if(JOptionPane.showConfirmDialog(this,panel,I18n.t("选择 AI 参考日志","Choose logs for AI"),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION){
            logRows=(Integer)rows.getValue();
            for(ReferenceItem item:picker.getSelectedValuesList())addReference(new ReferenceItem(item.path(),item.kind(),false,logRows));
        }
        previewGeneration[0]++;
    }
    /** The last {@code lines} lines of a log, reading back page by page and stopping near the request size limit. */
    static String tailLines(Path file,int lines)throws IOException{
        LogViewer.Page page=LogViewer.readPage(file,-1);StringBuilder text=new StringBuilder(page.text());long start=page.start();
        while(start>0&&newlines(text)<=lines&&text.length()<700_000){
            LogViewer.Page earlier=LogViewer.readRange(file,Math.max(0,start-LogViewer.PAGE_BYTES),start,true,false);
            if(earlier.start()>=start||earlier.text().isEmpty())break;
            text.insert(0,earlier.text());start=earlier.start();
        }
        int end=text.length();while(end>0&&(text.charAt(end-1)=='\n'||text.charAt(end-1)=='\r'))end--;
        int from=end;for(int seen=0;from>0;from--)if(text.charAt(from-1)=='\n'&&++seen==lines)break;
        return text.substring(from,end);
    }
    private static int newlines(CharSequence text){int n=0;for(int i=0;i<text.length();i++)if(text.charAt(i)=='\n')n++;return n;}
    private void pasteImage(Image image){
        if(image==null){status.setText(I18n.t("剪贴板图片不可读取。","Cannot read the clipboard image."));return;}
        if(attachmentModel.size()>=12){status.setText(I18n.t("每次最多附加 12 项参考资料。","At most 12 references per message."));return;}
        try{
            ImageIcon loaded=new ImageIcon(image);int width=loaded.getIconWidth(),height=loaded.getIconHeight();
            if(width<=0||height<=0||((long)width*height)>30_000_000L)throw new IOException(I18n.t("剪贴板图片尺寸无效或过大","Clipboard image size is invalid or too large"));
            Path folder=session.app.home.resolve("desktop/assistant-paste");Files.createDirectories(folder);
            Path file=Files.createTempFile(folder,"clipboard-",".png");
            try{BufferedImage bitmap=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);Graphics2D graphics=bitmap.createGraphics();try{graphics.drawImage(image,0,0,null);}finally{graphics.dispose();}
                if(!ImageIO.write(bitmap,"png",file.toFile()))throw new IOException(I18n.t("无法编码 PNG 图片","Cannot encode the PNG image"));
                if(Files.size(file)>4L*1024*1024)throw new IOException(I18n.t("图片超过 4 MiB，请先缩小后重试","Image exceeds 4 MiB; shrink it and try again"));
                pastedImages.add(file);addReference(new ReferenceItem(file,I18n.t("粘贴图片","Pasted image"),true));
            }catch(Exception error){Files.deleteIfExists(file);throw error;}
        }catch(Exception error){status.setText(I18n.t("添加剪贴板图片失败：","Adding clipboard image failed: ")+error.getMessage());session.app.events.operation(session.profile.name(),I18n.t("AI助手剪贴板图片附加失败：","AI assistant clipboard image attach failed: ")+error.getMessage());}
    }
    private String selectedLogs(List<ReferenceItem> references,int existingContextLength){
        StringBuilder context=new StringBuilder();int remaining=Math.min(700_000,Math.max(0,950_000-existingContextLength));
        for(ReferenceItem reference:references)if(reference.log()&&remaining>0){
            try{String prefix,body;
                if(reference.lines()>0){body=tailLines(reference.path(),reference.lines());prefix="\n\nSelected "+reference.kind()+" ("+reference.path().getFileName()+", latest "+reference.lines()+" lines):\n";}
                else{LogViewer.Page page=LogViewer.readPage(reference.path(),-1);body=page.text();prefix="\n\nSelected "+reference.kind()+" ("+reference.path().getFileName()+", bytes "+page.start()+"-"+page.end()+" / "+page.length()+"):\n";}
                if(prefix.length()>=remaining)break;
                context.append(prefix);remaining-=prefix.length();String text=body;if(text.length()>remaining){String omitted=I18n.t("[较早或超出上下文限制的内容已省略]\n","[earlier content beyond the context limit omitted]\n");if(omitted.length()<remaining){context.append(omitted);remaining-=omitted.length();}text=text.substring(Math.max(0,text.length()-remaining));}
                context.append(text);remaining-=text.length();
            }catch(Exception error){context.append(I18n.t("\n[日志读取失败：","\n[log read failed: ")).append(reference.path().getFileName()).append(I18n.t("；","; ")).append(error.getMessage()).append("]");session.app.events.operation(session.profile.name(),I18n.t("AI助手参考日志读取失败；文件=","AI assistant reference log read failed; file=")+reference.path().getFileName()+I18n.t("；原因=","; reason=")+error.getMessage());}
        }
        if(remaining<=0&&references.stream().anyMatch(ReferenceItem::log)){
            context.append(I18n.t("\n[其余日志因上下文长度限制未附加]","\n[remaining logs not attached due to the context limit]"));
            session.app.events.operation(session.profile.name(),I18n.t("AI助手参考日志已达到上下文长度限制；其余日志未附加","AI assistant reference logs reached the context limit; remaining logs not attached"));
        }
        return context.toString();
    }
    void prepare(String actionName,String text){if(actionName.equals("translate"))action.setSelectedItem(I18n.t("翻译","Translate"));else if(actionName.equals("translate-command"))action.setSelectedItem(I18n.t("翻译命令","Translate command"));else if(actionName.equals("command"))action.setSelectedItem(I18n.t("自然语言转命令","Natural language to command"));else if(actionName.equals("complete"))action.setSelectedItem(I18n.t("补全命令","Complete command"));else action.setSelectedItem(I18n.t("分析 / 问答","Analyze / Q&A"));if(text!=null&&!text.isBlank())composer.setText(text);composer.requestFocusInWindow();}

    private void sendMessage(){
        Conversation c=current();if(c==null||c.busy)return;String question=composer.getText();if(question.isBlank()&&!hasContext()){status.setText(I18n.t("请先输入问题，或选择上下文、日志或附件。","Type a question, or select context, logs or attachments first."));return;}
        AssistantSettings.Service chosen=(AssistantSettings.Service)service.getSelectedItem();String chosenModel=(String)model.getSelectedItem();if(chosen==null||chosenModel==null){status.setText(I18n.t("请先添加 AI 服务和模型配置。","Add an AI service and model first."));return;}
        String task=switch(action.getSelectedIndex()){case 1->"command";case 2->"complete";case 3,4->"translate";default->"ask";};String from=sourceLanguage.getText().trim(),to=targetLanguage.getText().trim();if(task.equals("translate")&&(from.isBlank()||to.isBlank())){status.setText(I18n.t("请填写源语言和目标语言。","Fill in the source and target languages."));return;}
        List<ReferenceItem> references=new ArrayList<>();for(int i=0;i<attachmentModel.size();i++)references.add(attachmentModel.get(i));int contextIndex=context.getSelectedIndex();int rows=switch(contextIndex){case 1->20;case 2->100;case 3->500;case 4->-1;default->0;};String contextText=rows==0&&!includeOtherSessions.isSelected()?"":session.assistantContext(rows,includeOtherSessions.isSelected());
        if(!c.messages.isEmpty())contextText="Previous messages in this conversation:\n"+c.messages.stream().map(m->(m.role.equals("user")?"User: ":"Assistant: ")+m.text).reduce((a,b)->a+"\n"+b).orElse("")+"\n\n"+contextText;if(task.equals("translate"))contextText="Translation direction: "+from+" → "+to+"\n"+contextText;
        String sentContext=contextText;String requestText=question.isBlank()?DEFAULT_REQUESTS[Math.max(0,action.getSelectedIndex())]:question;long logCount=references.stream().filter(ReferenceItem::log).count();session.app.events.operation(session.profile.name(),I18n.t("AI助手请求开始；功能=","AI assistant request started; feature=")+ACTIONS[action.getSelectedIndex()] +I18n.t("；终端上下文=","; terminal context=")+(rows<0?I18n.t("全部","all"):rows+I18n.t("行"," lines"))+I18n.t("；指定日志数=","; logs=")+logCount+I18n.t("；协作 SSH=","; collaborating SSH=")+includeOtherSessions.isSelected()+I18n.t("；文件数=","; files=")+(references.size()-logCount)+I18n.t("；模型=","; model=")+chosenModel);
        c.messages.add(new Message("user",requestText));c.markdown.setLength(0);render(c);persist();composer.setText("");attachmentModel.clear();refreshDefaultPrompt();c.busy=true;updateSendState();status.setText(I18n.t("正在生成…","Generating…"));long generation=++c.generation;
        c.future=session.app.workers.submit(()->{try{AssistantPlugin plugin=session.app.plugins.plugins().stream().filter(AssistantPlugin.class::isInstance).map(AssistantPlugin.class::cast).findFirst().orElseThrow();List<AssistantPlugin.Attachment> parsedFiles=new ArrayList<>();for(ReferenceItem item:references)if(!item.log())parsedFiles.add(AssistantAttachments.read(item.path()));String completeContext=sentContext+selectedLogs(references,sentContext.length());plugin.assistStreamFor(chosen.id(),chosenModel,task,requestText,completeContext,from,to,parsedFiles,delta->c.pending.add(delta));SwingUtilities.invokeLater(()->{if(generation==c.generation){flushStream(c);if(c.markdown.length()>0){c.messages.add(new Message("assistant",c.markdown.toString()));c.markdown.setLength(0);}render(c);persist();c.busy=false;updateSendState();session.app.events.operation(session.profile.name(),I18n.t("AI助手请求完成；模型=","AI assistant request finished; model=")+chosenModel);status.setText(I18n.t("完成。回复可选中复制；命令确认后再发送。","Done. Select the reply to copy it; confirm commands before sending."));}});}catch(Exception error){session.app.events.operation(session.profile.name(),I18n.t("AI助手请求失败：","AI assistant request failed: ")+error.getMessage());SwingUtilities.invokeLater(()->{if(generation==c.generation){flushStream(c);c.markdown.append(I18n.t("\n\n请求失败：","\n\nRequest failed: ")).append(error.getMessage());c.messages.add(new Message("assistant",c.markdown.toString()));c.markdown.setLength(0);render(c);persist();c.busy=false;updateSendState();status.setText(I18n.t("请求失败：","Request failed: ")+error.getMessage());}});}finally{for(ReferenceItem item:references)if(item.temporary()){pastedImages.remove(item.path());try{Files.deleteIfExists(item.path());}catch(IOException ignored){}}}});
    }
    private void updateSendState(){Conversation c=current();send.setEnabled(c!=null&&!c.busy);}
    private void flushStreams(){for(int i=0;i<conversations.getItemCount();i++){Conversation c=conversations.getItemAt(i);if(c!=null)flushStream(c);}}
    private void flushStream(Conversation c){String delta;boolean changed=false;while((delta=c.pending.poll())!=null){c.markdown.append(delta);changed=true;}if(changed)render(c);}
    void close(){streamRefresh.stop();for(int i=0;i<conversations.getItemCount();i++){Conversation c=conversations.getItemAt(i);if(c!=null&&c.future!=null)c.future.cancel(true);}synchronized(pastedImages){for(Path file:pastedImages)try{Files.deleteIfExists(file);}catch(IOException ignored){}pastedImages.clear();}persist();}
    private void render(Conversation c){StringBuilder html=new StringBuilder("<html><body style='font-family:sans-serif;font-size:13px;color:#17263b;background:#f6f8fa'>");for(Message m:c.messages)html.append(bubble(m.role,m.text));if(!c.markdown.isEmpty())html.append(bubble("assistant",c.markdown.toString()));if(c.messages.isEmpty()&&c.markdown.isEmpty())html.append(I18n.t("<div style='color:#74839a;margin-top:36px' align='center'>输入问题，或附加终端输出、日志、图片和文件</div>","<div style='color:#74839a;margin-top:36px' align='center'>Ask a question, or attach terminal output, logs, images and files</div>"));html.append("</body></html>");c.transcript.setText(html.toString());SwingUtilities.invokeLater(()->c.scroll.getVerticalScrollBar().setValue(c.scroll.getVerticalScrollBar().getMaximum()));}
    private static String bubble(String role,String text){boolean user=role.equals("user");String bg=user?"#d9f1eb":"#ffffff",name=user?I18n.t("你","You"):I18n.t("AI助手","AI assistant"),align=user?"right":"left";return "<table width='100%' cellpadding='0' cellspacing='0'><tr><td align='"+align+"'><table width='78%' bgcolor='"+bg+"' cellpadding='9' cellspacing='0'><tr><td><b style='color:#168b78'>"+name+"</b><br>"+markdown(text)+"</td></tr></table></td></tr></table><br>";}
    private static String escapeHtml(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\n","<br>");}
    private static String markdown(String source){StringBuilder out=new StringBuilder();boolean code=false;for(String raw:source.split("\\R",-1)){String line=raw;if(line.startsWith("```")){out.append(code?"</code></pre>":"<pre style='background:#eef1f4;padding:8px'><code>");code=!code;continue;}String safe=escapeHtml(line);if(code){out.append(safe).append("\n");continue;}if(line.startsWith("### "))safe="<h3>"+escapeHtml(line.substring(4))+"</h3>";else if(line.startsWith("## "))safe="<h2>"+escapeHtml(line.substring(3))+"</h2>";else if(line.startsWith("# "))safe="<h1>"+escapeHtml(line.substring(2))+"</h1>";else if(line.startsWith("- ")||line.startsWith("* "))safe="• "+escapeHtml(line.substring(2));else if(line.startsWith("> "))safe="<blockquote>"+escapeHtml(line.substring(2))+"</blockquote>";else safe=safe.replaceAll("`([^`]+)`","<code style='background:#eef1f4'>$1</code>").replaceAll("\\*\\*([^*]+)\\*\\*","<b>$1</b>").replaceAll("\\*([^*]+)\\*","<i>$1</i>");out.append(safe).append("<br>");}if(code)out.append("</code></pre>");return out.toString();}

    /** FlowLayout that wraps controls instead of clipping them at narrow dock widths. */
    private static final class WrapLayout extends FlowLayout {
        WrapLayout(int align,int hgap,int vgap){super(align,hgap,vgap);}
        @Override public Dimension preferredLayoutSize(Container target){return measure(target);}
        @Override public Dimension minimumLayoutSize(Container target){synchronized(target.getTreeLock()){Insets in=target.getInsets();int height=0;for(Component c:target.getComponents())if(c.isVisible())height=Math.max(height,c.getMinimumSize().height);return new Dimension(100,height+in.top+in.bottom+getVgap()*2);}}
        private Dimension measure(Container target){synchronized(target.getTreeLock()){int width=target.getWidth();if(width<=0)width=500;Insets in=target.getInsets();int max=Math.max(100,width-in.left-in.right-getHgap()*2),row=0,total=0,line=0;for(Component c:target.getComponents()){if(!c.isVisible())continue;Dimension d=c.getPreferredSize();if(line>0&&line+d.width>max){total+=row+getVgap();row=0;line=0;}if(line>0)line+=getHgap();line+=d.width;row=Math.max(row,d.height);}total+=row;return new Dimension(Math.min(max,line)+in.left+in.right+getHgap()*2,total+in.top+in.bottom+getVgap()*2);}}
    }
}
