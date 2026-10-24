package dev.lagless.desktop;

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
    private static final String[] ACTIONS={"分析 / 问答","自然语言转命令","补全命令","翻译","翻译命令"};
    private static final String[] CONTEXTS={"不附加终端输出","最近 20 行","最近 100 行","最近 500 行","全部终端历史"};
    private final SessionPane session;
    private final JComboBox<Conversation> conversations=new JComboBox<>();
    private final JComboBox<String> action=new JComboBox<>(ACTIONS),context=new JComboBox<>(CONTEXTS);
    private final JComboBox<AssistantSettings.Service> service=new JComboBox<>();
    private final JComboBox<String> model=new JComboBox<>();
    private final JTextArea composer=DesktopApp.textArea();
    private final DefaultListModel<ReferenceItem> attachmentModel=new DefaultListModel<>();
    private final JList<ReferenceItem> attachments=new JList<>(attachmentModel);
    private final JPanel referenceContent=new JPanel(new CardLayout());
    private final JLabel referenceHint=new JLabel("<html><div style='width:260px;text-align:center'>暂无附件。可添加图片、PDF、TXT/MD、指定日志，或在下方粘贴图片。</div></html>",SwingConstants.CENTER);
    private final Set<Path> pastedImages=Collections.synchronizedSet(new HashSet<>());
    private final JCheckBox includeOtherSessions=new JCheckBox("附加协作 SSH");
    private final JTextField sourceLanguage=DesktopApp.field("自动检测"),targetLanguage=DesktopApp.field("简体中文");
    private final JLabel status=new JLabel(" ");
    private final JButton send=new JButton("发送");
    private final Path store;
    private final javax.swing.Timer streamRefresh;
    private int nextConversation=1;
    private boolean changingSelection;

    private record Message(String role,String text){}
    private record ReferenceItem(Path path,String kind,boolean temporary){
        @Override public String toString(){return kind+" · "+path.getFileName();}
        boolean log(){return kind.endsWith("日志")||kind.equals("终端历史")||kind.equals("全量输入输出历史");}
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
        JPanel heading=new JPanel(new BorderLayout(4,4));heading.add(new JLabel("AI 助手对话"),BorderLayout.NORTH);
        JPanel historyActions=new JPanel(new WrapLayout(FlowLayout.LEADING,5,3));JButton fresh=button("新对话",this::newConversation);JButton remove=button("删除历史",this::deleteConversation);historyActions.add(conversations);historyActions.add(fresh);historyActions.add(remove);heading.add(historyActions,BorderLayout.CENTER);add(heading,BorderLayout.NORTH);

        JPanel body=new JPanel(new BorderLayout(0,8));
        conversations.setName("assistant-conversations");conversations.setPrototypeDisplayValue(new Conversation("YYYY年MM月DD日 HH:mm · 对话标题"));conversations.setPreferredSize(new Dimension(220,conversations.getPreferredSize().height));conversations.addActionListener(e->{if(!changingSelection)updateSendState();});
        loadConversations();if(conversations.getItemCount()==0)newConversation();
        Conversation selected=current();if(selected!=null)body.add(selected.panel,BorderLayout.CENTER);add(body,BorderLayout.CENTER);
        conversations.addActionListener(e->showSelected(body));

        JPanel composerArea=new JPanel();composerArea.setLayout(new BoxLayout(composerArea,BoxLayout.Y_AXIS));
        JPanel options=new JPanel(new WrapLayout(FlowLayout.LEADING,6,5));options.setAlignmentX(Component.LEFT_ALIGNMENT);
        options.add(labelled("功能",action));options.add(labelled("配置",service));options.add(labelled("模型",model));options.add(labelled("上下文",context));options.add(includeOtherSessions);
        composerArea.add(options);
        JPanel translations=new JPanel(new WrapLayout(FlowLayout.LEADING,6,4));translations.setAlignmentX(Component.LEFT_ALIGNMENT);translations.add(new JLabel("源语言"));translations.add(sourceLanguage);translations.add(new JLabel("目标语言"));translations.add(targetLanguage);composerArea.add(translations);
        JPanel referenceArea=new JPanel(new BorderLayout(4,4));referenceArea.setAlignmentX(Component.LEFT_ALIGNMENT);referenceArea.setBorder(BorderFactory.createTitledBorder("参考资料（仅随本次提问发送）"));referenceArea.setMaximumSize(new Dimension(Integer.MAX_VALUE,120));
        JPanel fileButtons=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));fileButtons.add(button("添加图片 / 文件",this::chooseAttachments));fileButtons.add(button("选择具体日志",this::chooseLogs));fileButtons.add(button("移除选中",this::removeSelectedReference));referenceArea.add(fileButtons,BorderLayout.NORTH);
        referenceHint.setForeground(DesktopApp.MUTED);referenceHint.setBorder(new EmptyBorder(7,7,7,7));referenceContent.add(referenceHint,"empty");attachments.setVisibleRowCount(2);attachments.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);attachments.setCellRenderer((list,value,index,selectedItem,focus)->{JLabel l=new JLabel(value.toString());l.setToolTipText(value.path().toString());l.setBorder(new EmptyBorder(3,6,3,6));l.setOpaque(true);l.setBackground(selectedItem?new Color(0xdcefe9):list.getBackground());l.setForeground(list.getForeground());return l;});referenceContent.add(new JScrollPane(attachments),"list");referenceArea.add(referenceContent,BorderLayout.CENTER);referenceArea.setPreferredSize(new Dimension(500,90));composerArea.add(referenceArea);attachmentModel.addListDataListener(new javax.swing.event.ListDataListener(){public void intervalAdded(javax.swing.event.ListDataEvent e){updateReferences();}public void intervalRemoved(javax.swing.event.ListDataEvent e){updateReferences();}public void contentsChanged(javax.swing.event.ListDataEvent e){updateReferences();}});updateReferences();
        composer.setLineWrap(true);composer.setWrapStyleWord(true);composer.setFont(DesktopFonts.ui().deriveFont(13f));composer.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(190,200,210)),new EmptyBorder(8,9,8,9)));ClipboardActions.install(composer,status::setText,null,this::pasteImage);
        composer.setRows(3);
        JScrollPane composeScroll=new JScrollPane(composer);composeScroll.setAlignmentX(Component.LEFT_ALIGNMENT);composeScroll.setMinimumSize(new Dimension(100,70));composeScroll.setPreferredSize(new Dimension(500,82));composeScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE,100));composerArea.add(composeScroll);
        JPanel sendRow=new JPanel(new BorderLayout(8,0));sendRow.setAlignmentX(Component.LEFT_ALIGNMENT);sendRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,42));status.setForeground(DesktopApp.MUTED);sendRow.add(status,BorderLayout.CENTER);JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,5,0));JButton cancel=button("停止",this::cancelRequest);actions.add(cancel);actions.add(button("插入回复到本地草稿",this::insertReply));actions.add(send);sendRow.add(actions,BorderLayout.EAST);composerArea.add(sendRow);add(composerArea,BorderLayout.SOUTH);

        send.addActionListener(e->sendMessage());composer.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"send-assistant");composer.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,InputEvent.SHIFT_DOWN_MASK),"insert-break");composer.getActionMap().put("send-assistant",new AbstractAction(){public void actionPerformed(ActionEvent e){sendMessage();}});composer.getActionMap().put("insert-break",new AbstractAction(){public void actionPerformed(ActionEvent e){composer.replaceSelection("\n");}});
        action.addActionListener(e->{translations.setVisible(action.getSelectedIndex()>=3);composerArea.revalidate();});translations.setVisible(false);
        JButton settings=button("AI 配置",()->{session.app.assistantSettings(session);reloadServices();});
        options.add(settings);
        service.addActionListener(e->reloadModels());reloadServices();streamRefresh=new javax.swing.Timer(60,e->flushStreams());streamRefresh.start();
    }

    private static String safe(String value){return value.replaceAll("[^A-Za-z0-9_.-]","_");}
    private static JButton button(String text,Runnable action){JButton b=new JButton(text);b.setFocusable(false);b.addActionListener(e->action.run());return b;}
    private static JPanel labelled(String title,JComponent component){JPanel p=new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));p.add(new JLabel(title));p.add(component);return p;}
    private Conversation current(){Object item=conversations.getSelectedItem();return item instanceof Conversation c?c:null;}
    private void showSelected(JPanel body){Conversation c=current();body.removeAll();if(c!=null){body.add(c.panel,BorderLayout.CENTER);render(c);}body.revalidate();body.repaint();updateSendState();}
    private void newConversation(){Conversation c=new Conversation("对话 "+java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))+" · "+(nextConversation++));changingSelection=true;conversations.addItem(c);conversations.setSelectedItem(c);changingSelection=false;persist();updateSendState();SwingUtilities.invokeLater(this::focusComposer);}
    private void deleteConversation(){Conversation c=current();if(c==null)return;if(JOptionPane.showConfirmDialog(this,"永久删除「"+c.title+"」及其中的对话记录？","删除 AI 对话",JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.OK_OPTION)return;if(c.future!=null)c.future.cancel(true);c.generation++;changingSelection=true;conversations.removeItem(c);changingSelection=false;if(conversations.getItemCount()==0)newConversation();persist();status.setText("已删除该对话记录。");}
    private void cancelRequest(){Conversation c=current();if(c!=null&&c.busy){c.generation++;if(c.future!=null)c.future.cancel(true);c.busy=false;status.setText("已停止当前对话请求。");updateSendState();}}
    private void insertReply(){Conversation c=current();if(c==null)return;String text=c.messages.stream().filter(m->m.role.equals("assistant")).reduce((a,b)->b).map(Message::text).orElse("");if(!text.isBlank()){session.draft.append(text);status.setText("已插入本地草稿；请检查后再发送。");}}
    void focusComposer(){composer.requestFocusInWindow();}
    private void loadConversations(){try{if(!Files.isRegularFile(store))return;Object root=Json.parse(Files.readString(store));for(Object item:Json.list(root,"conversations")){String title=Json.string(item,"title");if(title==null)continue;Conversation c=new Conversation(title);for(Object msg:Json.list(item,"messages")){String role=Json.string(msg,"role"),text=Json.string(msg,"text");if(role!=null&&text!=null)c.messages.add(new Message(role,text));}changingSelection=true;conversations.addItem(c);changingSelection=false;}nextConversation=conversations.getItemCount()+1;if(conversations.getItemCount()>0)conversations.setSelectedIndex(conversations.getItemCount()-1);}catch(Exception e){session.app.events.operation(session.profile.name(),"AI助手历史读取失败："+e.getMessage());}}
    private void persist(){try{List<Object> values=new ArrayList<>();for(int i=0;i<conversations.getItemCount();i++){Conversation c=conversations.getItemAt(i);List<Object> messages=c.messages.stream().map(m->(Object)Map.of("role",m.role,"text",m.text)).toList();values.add(Map.of("title",c.title,"messages",messages));}Files.createDirectories(store.getParent());ProfileStore.atomicWrite(store,Json.stringify(Map.of("version",1,"conversations",values)));}catch(Exception e){session.app.events.operation(session.profile.name(),"AI助手历史保存失败："+e.getMessage());}}
    private void reloadServices(){try{AssistantSettings settings=AssistantSettings.load(session.app.home);Object previous=service.getSelectedItem();service.removeAllItems();settings.services.forEach(service::addItem);if(previous!=null)service.setSelectedItem(previous);if(service.getSelectedItem()==null&&settings.current()!=null)service.setSelectedItem(settings.current());reloadModels();}catch(Exception error){status.setText("AI 配置读取失败："+error.getMessage());}}
    private void reloadModels(){model.removeAllItems();AssistantSettings.Service selected=(AssistantSettings.Service)service.getSelectedItem();if(selected!=null)selected.models().forEach(model::addItem);}
    private void updateReferences(){((CardLayout)referenceContent.getLayout()).show(referenceContent,attachmentModel.isEmpty()?"empty":"list");referenceContent.revalidate();referenceContent.repaint();}
    private void addReference(ReferenceItem item){
        for(int i=0;i<attachmentModel.size();i++)if(attachmentModel.get(i).path().equals(item.path()))return;
        if(attachmentModel.size()>=12){status.setText("每次最多附加 12 项参考资料。");return;}
        attachmentModel.addElement(item);status.setText("已添加："+item);
        session.app.events.operation(session.profile.name(),"AI助手参考资料添加；类型="+item.kind()+"；文件="+item.path().getFileName());
    }
    private void removeSelectedReference(){for(ReferenceItem item:attachments.getSelectedValuesList()){attachmentModel.removeElement(item);if(item.temporary()){pastedImages.remove(item.path());try{Files.deleteIfExists(item.path());}catch(IOException ignored){}}}}
    private void chooseAttachments(){
        JFileChooser chooser=new JFileChooser();chooser.setDialogTitle("添加图片、PDF 或文本文件");chooser.setMultiSelectionEnabled(true);
        chooser.setAcceptAllFileFilterUsed(false);chooser.setFileFilter(new FileNameExtensionFilter("支持的文件（图片、PDF、TXT、MD）","png","jpg","jpeg","gif","webp","pdf","txt","md"));
        if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;
        for(java.io.File file:chooser.getSelectedFiles())addReference(new ReferenceItem(file.toPath(),"文件",false));
    }
    private void chooseLogs(){
        List<ReferenceItem> available=new ArrayList<>();
        for(String[] category:new String[][]{{"logs","全量输入输出历史"},{"desktop/events","软件日志"},{"desktop/scrollback","终端历史"}}){
            Path directory=session.app.home.resolve(category[0]);if(!Files.isDirectory(directory))continue;
            try(var paths=Files.list(directory)){paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&p.getFileName().toString().endsWith(".log"))
                    .forEach(p->available.add(new ReferenceItem(p,category[1],false)));}
            catch(IOException error){status.setText("读取"+category[1]+"失败："+error.getMessage());}
        }
        available.sort(Comparator.comparing((ReferenceItem r)->r.path().getFileName().toString()).reversed());
        if(available.isEmpty()){status.setText("尚无可选择的日志。");return;}
        JList<ReferenceItem> picker=new JList<>(available.toArray(ReferenceItem[]::new));picker.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        picker.setCellRenderer((list,value,index,selected,focus)->{JLabel label=new JLabel(value.toString());label.setToolTipText(value.path().toString());label.setBorder(new EmptyBorder(5,8,5,8));label.setOpaque(true);label.setBackground(selected?new Color(0xdcefe9):list.getBackground());label.setForeground(list.getForeground());return label;});
        JScrollPane scroll=new JScrollPane(picker);scroll.setPreferredSize(new Dimension(570,310));
        if(JOptionPane.showConfirmDialog(this,new Object[]{"可多选具体日志；每份读取最新 256 KiB，内容仅随本次请求发送。",scroll},"选择 AI 参考日志",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION)
            for(ReferenceItem item:picker.getSelectedValuesList())addReference(item);
    }
    private void pasteImage(Image image){
        if(image==null){status.setText("剪贴板图片不可读取。");return;}
        if(attachmentModel.size()>=12){status.setText("每次最多附加 12 项参考资料。");return;}
        try{
            ImageIcon loaded=new ImageIcon(image);int width=loaded.getIconWidth(),height=loaded.getIconHeight();
            if(width<=0||height<=0||((long)width*height)>30_000_000L)throw new IOException("剪贴板图片尺寸无效或过大");
            Path folder=session.app.home.resolve("desktop/assistant-paste");Files.createDirectories(folder);
            Path file=Files.createTempFile(folder,"clipboard-",".png");
            try{BufferedImage bitmap=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);Graphics2D graphics=bitmap.createGraphics();try{graphics.drawImage(image,0,0,null);}finally{graphics.dispose();}
                if(!ImageIO.write(bitmap,"png",file.toFile()))throw new IOException("无法编码 PNG 图片");
                if(Files.size(file)>4L*1024*1024)throw new IOException("图片超过 4 MiB，请先缩小后重试");
                pastedImages.add(file);addReference(new ReferenceItem(file,"粘贴图片",true));
            }catch(Exception error){Files.deleteIfExists(file);throw error;}
        }catch(Exception error){status.setText("添加剪贴板图片失败："+error.getMessage());session.app.events.operation(session.profile.name(),"AI助手剪贴板图片附加失败："+error.getMessage());}
    }
    private String selectedLogs(List<ReferenceItem> references,int existingContextLength){
        StringBuilder context=new StringBuilder();int remaining=Math.min(700_000,Math.max(0,950_000-existingContextLength));
        for(ReferenceItem reference:references)if(reference.log()&&remaining>0){
            try{LogViewer.Page page=LogViewer.readPage(reference.path(),-1);String prefix="\n\nSelected "+reference.kind()+" ("+reference.path().getFileName()+", bytes "+page.start()+"-"+page.end()+" / "+page.length()+"):\n";
                if(prefix.length()>=remaining)break;
                context.append(prefix);remaining-=prefix.length();String text=page.text();if(text.length()>remaining){String omitted="[较早或超出上下文限制的内容已省略]\n";if(omitted.length()<remaining){context.append(omitted);remaining-=omitted.length();}text=text.substring(Math.max(0,text.length()-remaining));}
                context.append(text);remaining-=text.length();
            }catch(Exception error){context.append("\n[日志读取失败：").append(reference.path().getFileName()).append("；").append(error.getMessage()).append("]");session.app.events.operation(session.profile.name(),"AI助手参考日志读取失败；文件="+reference.path().getFileName()+"；原因="+error.getMessage());}
        }
        if(remaining<=0&&references.stream().anyMatch(ReferenceItem::log)){
            context.append("\n[其余日志因上下文长度限制未附加]");
            session.app.events.operation(session.profile.name(),"AI助手参考日志已达到上下文长度限制；其余日志未附加");
        }
        return context.toString();
    }
    void prepare(String actionName,String text){if(actionName.equals("translate"))action.setSelectedItem("翻译");else if(actionName.equals("translate-command"))action.setSelectedItem("翻译命令");else if(actionName.equals("command"))action.setSelectedItem("自然语言转命令");else if(actionName.equals("complete"))action.setSelectedItem("补全命令");else action.setSelectedItem("分析 / 问答");if(text!=null&&!text.isBlank())composer.setText(text);composer.requestFocusInWindow();}

    private void sendMessage(){
        Conversation c=current();if(c==null||c.busy)return;String question=composer.getText();if(question.isBlank()&&attachmentModel.isEmpty()){status.setText("请先输入问题或添加附件。");return;}
        AssistantSettings.Service chosen=(AssistantSettings.Service)service.getSelectedItem();String chosenModel=(String)model.getSelectedItem();if(chosen==null||chosenModel==null){status.setText("请先添加 AI 服务和模型配置。");return;}
        String task=switch(action.getSelectedIndex()){case 1->"command";case 2->"complete";case 3,4->"translate";default->"ask";};String from=sourceLanguage.getText().trim(),to=targetLanguage.getText().trim();if(task.equals("translate")&&(from.isBlank()||to.isBlank())){status.setText("请填写源语言和目标语言。");return;}
        List<ReferenceItem> references=new ArrayList<>();for(int i=0;i<attachmentModel.size();i++)references.add(attachmentModel.get(i));int contextIndex=context.getSelectedIndex();int rows=switch(contextIndex){case 1->20;case 2->100;case 3->500;case 4->-1;default->0;};String contextText=rows==0&&!includeOtherSessions.isSelected()?"":session.assistantContext(rows,includeOtherSessions.isSelected());
        if(!c.messages.isEmpty())contextText="Previous messages in this conversation:\n"+c.messages.stream().map(m->(m.role.equals("user")?"User: ":"Assistant: ")+m.text).reduce((a,b)->a+"\n"+b).orElse("")+"\n\n"+contextText;if(task.equals("translate"))contextText="Translation direction: "+from+" → "+to+"\n"+contextText;
        String sentContext=contextText;String requestText=question.isBlank()?"Please analyze the attached reference material.":question;long logCount=references.stream().filter(ReferenceItem::log).count();session.app.events.operation(session.profile.name(),"AI助手请求开始；功能="+ACTIONS[action.getSelectedIndex()] +"；终端上下文="+(rows<0?"全部":rows+"行")+"；指定日志数="+logCount+"；协作 SSH="+includeOtherSessions.isSelected()+"；文件数="+(references.size()-logCount)+"；模型="+chosenModel);
        c.messages.add(new Message("user",requestText));c.markdown.setLength(0);render(c);persist();composer.setText("");attachmentModel.clear();c.busy=true;updateSendState();status.setText("正在生成…");long generation=++c.generation;
        c.future=session.app.workers.submit(()->{try{AssistantPlugin plugin=session.app.plugins.plugins().stream().filter(AssistantPlugin.class::isInstance).map(AssistantPlugin.class::cast).findFirst().orElseThrow();List<AssistantPlugin.Attachment> parsedFiles=new ArrayList<>();for(ReferenceItem item:references)if(!item.log())parsedFiles.add(AssistantAttachments.read(item.path()));String completeContext=sentContext+selectedLogs(references,sentContext.length());plugin.assistStreamFor(chosen.id(),chosenModel,task,requestText,completeContext,from,to,parsedFiles,delta->c.pending.add(delta));SwingUtilities.invokeLater(()->{if(generation==c.generation){flushStream(c);if(c.markdown.length()>0){c.messages.add(new Message("assistant",c.markdown.toString()));c.markdown.setLength(0);}render(c);persist();c.busy=false;updateSendState();session.app.events.operation(session.profile.name(),"AI助手请求完成；模型="+chosenModel);status.setText("完成。回复可选中复制；命令确认后再发送。");}});}catch(Exception error){session.app.events.operation(session.profile.name(),"AI助手请求失败："+error.getMessage());SwingUtilities.invokeLater(()->{if(generation==c.generation){flushStream(c);c.markdown.append("\n\n请求失败：").append(error.getMessage());c.messages.add(new Message("assistant",c.markdown.toString()));c.markdown.setLength(0);render(c);persist();c.busy=false;updateSendState();status.setText("请求失败："+error.getMessage());}});}finally{for(ReferenceItem item:references)if(item.temporary()){pastedImages.remove(item.path());try{Files.deleteIfExists(item.path());}catch(IOException ignored){}}}});
    }
    private void updateSendState(){Conversation c=current();send.setEnabled(c!=null&&!c.busy);}
    private void flushStreams(){for(int i=0;i<conversations.getItemCount();i++){Conversation c=conversations.getItemAt(i);if(c!=null)flushStream(c);}}
    private void flushStream(Conversation c){String delta;boolean changed=false;while((delta=c.pending.poll())!=null){c.markdown.append(delta);changed=true;}if(changed)render(c);}
    void close(){streamRefresh.stop();for(int i=0;i<conversations.getItemCount();i++){Conversation c=conversations.getItemAt(i);if(c!=null&&c.future!=null)c.future.cancel(true);}synchronized(pastedImages){for(Path file:pastedImages)try{Files.deleteIfExists(file);}catch(IOException ignored){}pastedImages.clear();}persist();}
    private void render(Conversation c){StringBuilder html=new StringBuilder("<html><body style='font-family:sans-serif;font-size:13px;color:#17263b;background:#f6f8fa'>");for(Message m:c.messages)html.append(bubble(m.role,m.text));if(!c.markdown.isEmpty())html.append(bubble("assistant",c.markdown.toString()));if(c.messages.isEmpty()&&c.markdown.isEmpty())html.append("<div style='color:#74839a;margin-top:36px' align='center'>输入问题，或附加终端输出、日志、图片和文件</div>");html.append("</body></html>");c.transcript.setText(html.toString());SwingUtilities.invokeLater(()->c.scroll.getVerticalScrollBar().setValue(c.scroll.getVerticalScrollBar().getMaximum()));}
    private static String bubble(String role,String text){boolean user=role.equals("user");String bg=user?"#d9f1eb":"#ffffff",name=user?"你":"AI助手",align=user?"right":"left";return "<table width='100%' cellpadding='0' cellspacing='0'><tr><td align='"+align+"'><table width='78%' bgcolor='"+bg+"' cellpadding='9' cellspacing='0'><tr><td><b style='color:#168b78'>"+name+"</b><br>"+markdown(text)+"</td></tr></table></td></tr></table><br>";}
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
