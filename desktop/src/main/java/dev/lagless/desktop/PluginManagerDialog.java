package dev.lagless.desktop;

import dev.lagless.I18n;
import dev.lagless.Json;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Local plugin lifecycle. Process bundles work in both JVM and native builds. */
final class PluginManagerDialog {
    private static final String GUIDE="""
            插件开发指南（详细协议也见项目 docs/plugins.md）

            原生版和 JVM 版都支持独立进程插件；JVM 版还支持 Java SPI JAR。
            创建「名称.plugin.json」，例如：
            {"version":1,"name":I18n.t("我的插件","My plugins"),"command":["plugin.exe"],"timeout-ms":30000,"output-events":true}
            command[0] 可以是与描述文件同目录的相对路径。将描述文件和程序放在同一个 ZIP 根目录，使用「安装」导入。
            不通过 shell 运行。插件拥有当前用户权限，只安装可信代码。

            进程使用 UTF-8 JSON-lines：stdin 收请求，stdout 每行回一个 JSON 对象；stderr 可写诊断。
            输出事件：{"version":1,"id":0,"type":"output","session":"ID","text":I18n.t("远端文本","Remote text")}
            事件不需响应，文本可能含 ANSI 或被分片。它不保证每批必达；完整数据请读会话日志。
            主动请求：{"version":1,"id":1,"type":"assist","action":"ask","text":I18n.t("请求","Requests"),"context":I18n.t("会话内容","Session content")}
            成功响应：{"version":1,"id":1,"ok":true,"text":I18n.t("本地草稿","Local draft")}
            错误响应：{"version":1,"id":1,"ok":false,"error":I18n.t("原因","Reason")}
            action：ask / command / complete / translate。按 id 匹配响应，允许乱序。
            单行最大 1 MiB；超时 100–120000 ms，最多 32 个等待请求、128 个排队事件。
            没有 SSH 命令执行接口；回复进入本地草稿，由用户审阅后才可发送。

            「配置」编辑 JSON 对象；启动进程时通过 LAGLESS_PLUGIN_CONFIG 环境变量传入。
            Java SPI 实现 LaglessPlugin，并在 JAR 的 META-INF/services 中登记类名；configure(Map) 接收配置。
            安装、禁用、启用、卸载会立即重新加载插件。被停用的进程会结束，进行中的请求会失败。
            导出 ZIP 包含插件目录的文件与配置；单文件插件直接导出原文件。
            """;
    private record Item(Path path,String label){@Override public String toString(){return label;}}
    private final DesktopApp app;private final Component parent;private final DefaultListModel<Item> items=new DefaultListModel<>();
    private final JList<Item> list=new JList<>(items);private final JTextArea status=DesktopApp.textArea();
    PluginManagerDialog(DesktopApp app,Component parent){this.app=app;this.parent=parent;show();}
    private void show(){JDialog dialog=new JDialog(DesktopApp.ownerWindow(parent),I18n.t("插件管理","Plugins"),Dialog.ModalityType.MODELESS);
        JPanel root=new JPanel(new BorderLayout(8,8));root.setBorder(new EmptyBorder(12,12,12,12));
        dialog.setIconImage(DesktopApp.appIcon());
        list.setCellRenderer((view,value,index,selected,focus)->{JLabel label=new JLabel(value.path()==null?"● "+value.label():(app.plugins.enabled(value.path())?"● ":"○ ")+value.label());
            label.setOpaque(true);label.setBackground(selected?new Color(0xd8eee8):view.getBackground());label.setForeground(selected?DesktopApp.INK:view.getForeground());return label;});
        JPanel actions=new JPanel(new GridLayout(0,2,6,6));
        actions.add(DesktopApp.button(I18n.t("安装 / 导入","Install / import"),this::install));actions.add(DesktopApp.button(I18n.t("导出","Export"),this::export));
        actions.add(DesktopApp.button(I18n.t("启用 / 禁用","Enable / disable"),this::toggle));actions.add(DesktopApp.button(I18n.t("配置","Configure"),this::configure));
        actions.add(DesktopApp.button(I18n.t("卸载","Uninstall"),this::uninstall));actions.add(DesktopApp.button(I18n.t("开发指南","Developer guide"),this::guide));
        root.add(new JLabel(I18n.t("进程插件支持原生版；JAR 仅 JVM 版支持。插件以当前用户权限运行。","Process plugins work in the native build; JAR plugins need the JVM build. Plugins run with your user permissions.")),BorderLayout.NORTH);
        root.add(new JScrollPane(list),BorderLayout.CENTER);root.add(actions,BorderLayout.EAST);
        status.setEditable(false);status.setRows(3);status.setLineWrap(true);root.add(new JScrollPane(status),BorderLayout.SOUTH);
        dialog.setContentPane(root);dialog.setSize(710,380);dialog.setLocationRelativeTo(parent);refresh();dialog.setVisible(true);
    }
    private Path selectedPath(){Item selected=list.getSelectedValue();return selected==null?null:selected.path();}
    private void refresh(){Path selected=selectedPath();items.clear();items.addElement(new Item(null,I18n.t("AI助手 · 内置插件","AI assistant · built-in")));
        try{app.plugins.installed().forEach(path->items.addElement(new Item(path,path.getFileName().toString())));}catch(IOException error){DesktopApp.error(parent,error);}
        if(selected!=null)for(int i=0;i<items.size();i++)if(selected.equals(items.get(i).path())){list.setSelectedIndex(i);break;}
        status.setText(I18n.t("插件目录：","Plugin folder: ")+app.plugins.directory()+I18n.t("\n已加载：","\nLoaded: ")+app.plugins.plugins().stream().map(LaglessPlugin::name).toList()+I18n.t("\n过载省略事件：","\nEvents dropped under load: ")+app.plugins.skippedEvents()+I18n.t("（原始日志保留）\n"," (raw logs kept)\n")+String.join("\n",app.plugins.errors()));}
    private void changed(String operation){try{app.plugins.reload();app.events.operation(operation);refresh();}catch(Exception error){DesktopApp.error(parent,error);refresh();}}
    private void install(){JFileChooser picker=new JFileChooser();picker.setDialogTitle(I18n.t("安装插件文件或 ZIP 包","Install a plugin file or ZIP package"));if(picker.showOpenDialog(parent)!=JFileChooser.APPROVE_OPTION)return;
        Path source=picker.getSelectedFile().toPath();String name=source.getFileName().toString();Path directory=app.plugins.directory();
        try{Path installed;
            if(name.endsWith(".zip")){String base=name.substring(0,name.length()-4).replaceAll("[^A-Za-z0-9._-]","-");if(base.isBlank())throw new IOException(I18n.t("插件包名称无效","Invalid plugin package name"));
                Path target=directory.resolve(base).normalize();if(Files.exists(target))throw new IOException(I18n.t("同名插件已安装：","A plugin with this name is already installed: ")+base);
                Path temp=Files.createTempDirectory(directory,".install-");try{
                    long total=0;int count=0;boolean descriptor=false;
                    try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(source))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){
                        if(++count>256)throw new IOException(I18n.t("插件包文件过多","Plugin package has too many files"));String entryName=entry.getName();Path relative=Path.of(entryName.replace('\\','/')).normalize();
                        if(relative.isAbsolute()||relative.startsWith("..")||relative.getNameCount()>2||entryName.contains(":"))throw new IOException(I18n.t("插件包包含不安全路径","Plugin package contains an unsafe path"));
                        Path output=temp.resolve(relative).normalize();if(!output.startsWith(temp))throw new IOException(I18n.t("插件包路径越界","Plugin package path is out of bounds"));
                        if(entry.isDirectory()){Files.createDirectories(output);continue;}Files.createDirectories(output.getParent());
                        if(Files.exists(output))throw new IOException(I18n.t("插件包内文件重复","Plugin package contains duplicate files"));try(OutputStream out=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW)){
                            byte[] bytes=new byte[8192];int read;while((read=zip.read(bytes))!=-1){total+=read;if(total>100L*1024*1024)throw new IOException(I18n.t("插件包超过 100 MiB","Plugin package exceeds 100 MiB"));out.write(bytes,0,read);}}
                        if(relative.getNameCount()==1&&entryName.endsWith(".plugin.json"))descriptor=true;
                    }}
                    if(!descriptor)throw new IOException(I18n.t("ZIP 根目录缺少 .plugin.json 描述文件","ZIP root is missing the .plugin.json descriptor"));Files.move(temp,target);installed=target;
                }finally{if(Files.exists(temp))deleteTree(temp);}
            }else if(name.endsWith(".plugin.json")||name.endsWith(".jar")){
                if(name.endsWith(".jar")&&DesktopRuntime.nativeImage())throw new IOException(I18n.t("原生版只能安装进程插件","The native build can install process plugins only"));
                Path target=directory.resolve(name);if(Files.exists(target))throw new IOException(I18n.t("同名插件已安装：","A plugin with this name is already installed: ")+name);Files.copy(source,target);installed=target;
            }else throw new IOException(I18n.t("请选择 .zip、.plugin.json 或 .jar 文件","Choose a .zip, .plugin.json or .jar file"));
            app.plugins.forget(installed);app.events.operation(I18n.t("安装插件 ","Install plugin ")+name);refresh();
        }catch(Exception error){DesktopApp.error(parent,error);}
    }
    private void export(){Path selected=selectedPath();if(selected==null)return;JFileChooser picker=new JFileChooser();picker.setSelectedFile(new File(selected.getFileName()+(Files.isDirectory(selected)?".zip":"")));
        if(picker.showSaveDialog(parent)!=JFileChooser.APPROVE_OPTION)return;Path target=picker.getSelectedFile().toPath();
        if(Files.exists(target)&&JOptionPane.showConfirmDialog(parent,I18n.t("覆盖现有文件？\n","Overwrite existing file?\n")+target,I18n.t("导出插件","Export plugin"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        try{if(Files.isDirectory(selected)){try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(target))){try(var files=Files.walk(selected)){
            for(Path file:files.filter(Files::isRegularFile).toList()){if(Files.isSymbolicLink(file))continue;zip.putNextEntry(new ZipEntry(selected.relativize(file).toString().replace('\\','/')));Files.copy(file,zip);zip.closeEntry();}}}}
            else Files.copy(selected,target,StandardCopyOption.REPLACE_EXISTING);app.events.operation(I18n.t("导出插件 ","Export plugin ")+selected.getFileName());
        }catch(Exception error){DesktopApp.error(parent,error);}}
    private void toggle(){Path selected=selectedPath();if(selected==null)return;try{boolean next=!app.plugins.enabled(selected);app.plugins.setEnabled(selected,next);app.events.operation((next?I18n.t("启用","Enable"):I18n.t("禁用","Disable"))+I18n.t("插件 ","Plugin ")+selected.getFileName());refresh();}catch(Exception error){DesktopApp.error(parent,error);}}
    private void configure(){Path selected=selectedPath();if(selected==null)return;Path config=app.plugins.configPath(selected);JTextArea editor=DesktopApp.textArea();editor.setRows(12);editor.setColumns(55);
        try{editor.setText(Files.isRegularFile(config)?Files.readString(config):"{}");}catch(Exception error){DesktopApp.error(parent,error);return;}
        if(JOptionPane.showConfirmDialog(parent,new Object[]{I18n.t("输入插件配置 JSON 对象。进程插件在启动时读取 LAGLESS_PLUGIN_CONFIG。","Enter the plugin config as a JSON object. Process plugins read it from LAGLESS_PLUGIN_CONFIG at startup."),new JScrollPane(editor)},I18n.t("配置插件","Configure plugin"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        try{Object parsed=Json.parse(editor.getText());if(!(parsed instanceof Map<?,?>))throw new IOException(I18n.t("配置须为 JSON 对象","Config must be a JSON object"));ProfileStore.atomicWrite(config,Json.stringify(parsed));changed(I18n.t("配置插件 ","Configure plugin ")+selected.getFileName());}catch(Exception error){DesktopApp.error(parent,error);}}
    private void uninstall(){Path selected=selectedPath();if(selected==null)return;
        if(JOptionPane.showConfirmDialog(parent,I18n.t("卸载插件「","Uninstall plugin \"")+selected.getFileName()+I18n.t("」及其配置？","\" and its config?"),I18n.t("卸载插件","Uninstall plugin"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        try{Path root=app.plugins.directory().toRealPath();Path absolute=selected.toRealPath();if(!absolute.getParent().equals(root))throw new IOException(I18n.t("插件路径不在安装目录","Plugin path is outside the install folder"));
            app.plugins.setEnabled(selected,false);if(Files.isDirectory(selected))deleteTree(selected);else{Files.delete(selected);Files.deleteIfExists(app.plugins.configPath(selected));}
            app.plugins.forget(selected);app.events.operation(I18n.t("卸载插件 ","Uninstall plugin ")+selected.getFileName());refresh();}catch(Exception error){DesktopApp.error(parent,error);}}
    private static void deleteTree(Path root) throws IOException {try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    private void guide(){JTextArea text=DesktopApp.textArea();text.setText(GUIDE);text.setEditable(false);text.setCaretPosition(0);text.setRows(23);text.setColumns(77);
        JOptionPane.showMessageDialog(parent,new JScrollPane(text),I18n.t("插件开发指南","Plugin developer guide"),JOptionPane.INFORMATION_MESSAGE);}
}
