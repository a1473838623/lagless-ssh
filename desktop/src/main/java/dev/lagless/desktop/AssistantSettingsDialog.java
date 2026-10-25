package dev.lagless.desktop;

import dev.lagless.I18n;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.*;
import java.util.List;

/** Manages local AI service credentials, models, and task prompts. */
final class AssistantSettingsDialog {
    private final DesktopApp app;private final Component parent;
    private AssistantSettings settings;
    private final DefaultListModel<AssistantSettings.Service> listModel=new DefaultListModel<>();
    private final JList<AssistantSettings.Service> list=new JList<>(listModel);
    private final JLabel current=new JLabel();
    AssistantSettingsDialog(DesktopApp app,Component parent){this.app=app;this.parent=parent;
        try{settings=AssistantSettings.load(app.home);}catch(Exception error){DesktopApp.error(parent,error);return;}
        show();
    }
    private void show(){
        JDialog dialog=new JDialog(DesktopApp.ownerWindow(parent),I18n.t("AI 设置","AI settings"),Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setIconImage(DesktopApp.appIcon());
        JPanel root=new JPanel(new BorderLayout(10,10));root.setBorder(new EmptyBorder(14,14,14,14));
        root.add(new JLabel(I18n.t("添加服务配置，在 AI 助手中按需选择模型。","Add a service, then choose a model in the AI assistant.")),BorderLayout.NORTH);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);list.setVisibleRowCount(7);list.setSelectionBackground(Theme.TINT);list.setSelectionForeground(DesktopApp.INK);refresh();
        JPanel actions=new JPanel(new GridLayout(0,1,6,6));
        actions.add(DesktopApp.button(I18n.t("添加","Add"),()->edit(null)));
        actions.add(DesktopApp.button(I18n.t("编辑","Edit"),()->edit(list.getSelectedValue())));
        actions.add(DesktopApp.button(I18n.t("测试可用性","Test"),this::testService));
        actions.add(DesktopApp.button(I18n.t("设为当前","Set as current"),()->{AssistantSettings.Service service=list.getSelectedValue();if(service!=null){settings.selected=service.id();save();refresh();}}));
        actions.add(DesktopApp.button(I18n.t("删除","Delete"),()->{AssistantSettings.Service service=list.getSelectedValue();if(service==null)return;
            if(JOptionPane.showConfirmDialog(dialog,I18n.t("删除 AI 配置「","Delete AI configuration \"")+service.name()+I18n.t("」？","\"?"),I18n.t("删除配置","Delete configuration"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            settings.services.remove(service);if(settings.selected.equals(service.id()))settings.selected="";save();refresh();}));
        actions.add(DesktopApp.button(I18n.t("提示词","Prompts"),this::editPrompts));
        JPanel center=new JPanel(new BorderLayout(8,8));center.add(new JScrollPane(list),BorderLayout.CENTER);center.add(actions,BorderLayout.EAST);
        root.add(center,BorderLayout.CENTER);
        JPanel bottom=new JPanel(new BorderLayout());bottom.add(current,BorderLayout.NORTH);
        JLabel security=new JLabel(I18n.t("API 密钥保存在本机用户目录的 assistant.json；完整加密备份会包含它。仅安装可信插件。","API keys are stored in assistant.json in your user folder; a full encrypted backup includes it. Install only plugins you trust.") );
        security.setForeground(DesktopApp.MUTED);bottom.add(security,BorderLayout.CENTER);
        bottom.add(DesktopApp.button(I18n.t("关闭","Close"),dialog::dispose),BorderLayout.EAST);root.add(bottom,BorderLayout.SOUTH);
        dialog.setContentPane(root);dialog.setSize(700,370);dialog.setLocationRelativeTo(parent);dialog.setVisible(true);
    }
    private void refresh(){AssistantSettings.Service chosen=list.getSelectedValue();listModel.clear();
        for(AssistantSettings.Service service:settings.services)listModel.addElement(service);
        if(chosen!=null){AssistantSettings.Service updated=settings.byId(chosen.id());if(updated!=null)list.setSelectedValue(updated,true);}
        if(list.getSelectedIndex()<0&&listModel.size()>0)list.setSelectedIndex(0);
        AssistantSettings.Service active=settings.current();current.setText(I18n.t("当前配置：","Current: ")+(active==null?I18n.t("尚未设置","Not set"):active.name()));
    }
    private void edit(AssistantSettings.Service old){
        JTextField name=DesktopApp.field(old==null?"":old.name()),endpoint=DesktopApp.field(old==null?"":old.endpoint());
        JTextArea models=DesktopApp.textArea();models.setRows(4);models.setColumns(38);models.setText(old==null?"":String.join("\n",old.models()));
        JPasswordField key=new JPasswordField(old==null?"":old.key(),32);JCheckBox visible=new JCheckBox(I18n.t("显示密钥","Show key"));
        char echo=key.getEchoChar();visible.addActionListener(e->key.setEchoChar(visible.isSelected()?(char)0:echo));
        JPanel keyRow=new JPanel(new BorderLayout(6,0));keyRow.add(key,BorderLayout.CENTER);keyRow.add(visible,BorderLayout.EAST);
        Object[] form={I18n.t("配置名称","Name"),name,I18n.t("API 根地址（/v1）或完整 chat/completions 地址","API base URL (/v1) or full chat/completions URL"),endpoint,I18n.t("模型（每行一个）","Models (one per line)"),new JScrollPane(models),I18n.t("API 密钥（直接输入）","API key"),keyRow,
                I18n.t("本机和私有局域网 IP 可使用 HTTP；局域网 HTTP 会明文传输密钥。","Plain HTTP is allowed for this computer and private LAN IPs; LAN HTTP sends the key unencrypted.")};
        while(JOptionPane.showConfirmDialog(parent,form,old==null?I18n.t("添加 AI 配置","Add AI configuration"):I18n.t("编辑 AI 配置","Edit AI configuration"),JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION){
            List<String> names=Arrays.stream(models.getText().split("\\R")).map(String::trim).filter(s->!s.isEmpty()).distinct().toList();
            char[] secret=key.getPassword();String token=new String(secret);Arrays.fill(secret,'\0');
            AssistantSettings.Service service=new AssistantSettings.Service(old==null?UUID.randomUUID().toString():old.id(),name.getText().trim(),AssistantSettings.normalizeEndpoint(endpoint.getText()),names,token,token.isBlank()&&old!=null?old.legacyEnv():"");
            try{AssistantSettings.validate(service);int at=old==null?settings.services.size():settings.services.indexOf(old);if(at<0)at=settings.services.size();
                if(old!=null)settings.services.remove(old);settings.services.add(at,service);if(settings.selected.isBlank())settings.selected=service.id();
                save();refresh();list.setSelectedValue(service,true);return;
            }catch(Exception error){DesktopApp.error(parent,error);}
        }
    }
    private void testService(){
        AssistantSettings.Service service=list.getSelectedValue();if(service==null)return;
        JComboBox<String> model=new JComboBox<>(service.models().toArray(String[]::new));
        if(JOptionPane.showConfirmDialog(parent,new Object[]{I18n.t("测试配置：","Configuration: ")+service.name(),I18n.t("实际请求地址：","Request URL: ")+AssistantSettings.normalizeEndpoint(service.endpoint()),I18n.t("测试模型","Model to test"),model},I18n.t("测试 AI 服务","Test AI service"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        String selected=(String)model.getSelectedItem();if(selected==null)return;
        app.events.operation(I18n.t("测试 AI 配置：","Test AI configuration: ")+service.name()+" / "+selected);
        app.background(parent,()->new AssistantPlugin(app.home).assistFor(service.id(),selected,"ask",I18n.t("仅回复 OK。","Reply with OK only."),"",I18n.t("自动检测","Auto detect"),I18n.t("简体中文","English")),
                result->JOptionPane.showMessageDialog(parent,I18n.t("连接成功，模型「","Connected. Model \"")+selected+I18n.t("」已返回：\n","\" replied:\n")+result,I18n.t("AI 服务可用","AI service works"),JOptionPane.INFORMATION_MESSAGE));
    }
    private void editPrompts(){
        String[] actions={"ask","command","complete","translate"};
        JComboBox<String> choice=new JComboBox<>(actions);JTextArea prompt=DesktopApp.textArea();prompt.setRows(9);prompt.setColumns(55);
        Map<String,String> drafts=new HashMap<>(settings.prompts);String[] previous={"ask"};
        choice.addActionListener(e->{drafts.put(previous[0],prompt.getText());previous[0]=choice.getSelectedItem().toString();prompt.setText(drafts.get(previous[0]));});
        choice.setSelectedIndex(0);prompt.setText(settings.prompts.get("ask"));
        JPanel panel=new JPanel(new BorderLayout(6,6));panel.add(choice,BorderLayout.NORTH);panel.add(new JScrollPane(prompt),BorderLayout.CENTER);
        while(true){int result=JOptionPane.showConfirmDialog(parent,new Object[]{I18n.t("编辑功能提示词。翻译支持 {source} 和 {target} 变量。","Edit the prompt for each feature. Translation supports the {source} and {target} variables."),panel},I18n.t("AI 功能提示词","AI prompts"),JOptionPane.OK_CANCEL_OPTION);
            if(result!=JOptionPane.OK_OPTION)return;String value=prompt.getText().trim();if(value.isEmpty()){JOptionPane.showMessageDialog(parent,I18n.t("提示词不能为空","Prompt cannot be empty"));continue;}
            drafts.put(previous[0],value);settings.prompts.putAll(drafts);save();return;}
    }
    private void save(){try{settings.save();app.events.operation(I18n.t("更新 AI 配置","Update AI configuration"));}catch(Exception error){DesktopApp.error(parent,error);}}
}
