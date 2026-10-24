package dev.lagless.desktop;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Release gate: execute actual button actions and modal loops on isolated data. */
final class NativeDialogVerification {
    private NativeDialogVerification(){ }
    static List<String> run(Path work)throws Exception{
        Path home=work.resolve("dialog-user/.lagless"),source=TerminalProfiles.source(home);Files.createDirectories(source.getParent());
        Files.writeString(source,"Host saved-terminal\n HostName 192.0.2.23\n User fixture\n Port 2222\n");
        DesktopApp[] app={null};JFrame[] frame={null};List<String> verified=new ArrayList<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();Thread.setDefaultUncaughtExceptionHandler((thread,error)->{failure.compareAndSet(null,error);error.printStackTrace(System.err);});
        try{
            SwingUtilities.invokeAndWait(()->{try{app[0]=new DesktopApp(home,false);app[0].newWindow();frame[0]=app[0].windows.getFirst();}catch(Exception e){throw new RuntimeException(e);}});
            check(app[0].accounts.size()==1,"saved terminal connection was visible on startup");verified.add("terminal-auto-import");
            // getLocationOnScreen returns a Point created by native code. This
            // catches JNI metadata omissions even before a modal is opened.
            SwingUtilities.invokeAndWait(()->{frame[0].getLocationOnScreen();for(JMenu menu:all(frame[0],JMenu.class)){if(menu.isShowing()){menu.setPopupMenuVisible(true);menu.setPopupMenuVisible(false);}}});
            for(String label:List.of("协作","AI 设置","插件","备份 / 恢复","更新","导入账号","恢复草稿","全量输入输出历史","软件日志","终端历史","连接库")){
                probe(frame[0],label,List.of(NativeDialogVerification::cancel),failure);verified.add(label);
            }
            libraryFirstFrame(frame[0],work);verified.add("连接库首次绘制（无需点击）");
            probe(frame[0],"添加",List.of(dialog->{List<JTextField> fields=all(dialog,JTextField.class);check(fields.size()>=5,"account editor fields");fields.get(0).setText("native-ui-account");fields.get(1).setText("127.0.0.1");fields.get(2).setText("tester");fields.get(3).setText("2222");JComboBox<?> jump=all(dialog,JComboBox.class).stream().filter(c->"jump-account".equals(c.getName())).findFirst().orElseThrow();jump.setPopupVisible(true);check(jump.getItemCount()>1,"jump offers saved SSH profiles");jump.setSelectedIndex(1);jump.setPopupVisible(false);choose(dialog,JOptionPane.OK_OPTION);}),failure);
            check(app[0].profiles().stream().anyMatch(p->p.name().equals("native-ui-account")),"add persisted connection");check(!app[0].accounts.lastElement().jumpId().isBlank(),"selected jump persisted");verified.add("添加并保存 / 跳板选择");
            SwingUtilities.invokeAndWait(()->app[0].selectProfile(app[0].accounts.lastElement()));
            probe(frame[0],"编辑",List.of(dialog->{all(dialog,JTextField.class).getFirst().setText("native-ui-edited");choose(dialog,JOptionPane.OK_OPTION);}),failure);
            check(new ProfileStore(home).load().stream().anyMatch(p->p.name().equals("native-ui-edited")),"edit persisted connection");verified.add("编辑并保存");
            Path exported=work.resolve("ui-export.json");
            probe(frame[0],"导出账号",List.of(dialog->choose(dialog,JOptionPane.OK_OPTION),dialog->{JFileChooser picker=all(dialog,JFileChooser.class).getFirst();picker.setSelectedFile(exported.toFile());picker.approveSelection();}),failure);
            check(!AccountFormats.read(exported).isEmpty(),"export produced a valid account file");verified.add("导出文件");
            Path imported=work.resolve("ui-import.json");AccountFormats.write(imported,List.of(new Profile("import","native-ui-imported","192.0.2.24","fixture",22,"","",true,false)),"json");
            probe(frame[0],"导入账号",List.of(dialog->{JFileChooser picker=all(dialog,JFileChooser.class).getFirst();picker.setSelectedFiles(new File[]{imported.toFile()});picker.approveSelection();},NativeDialogVerification::cancel),failure);
            check(app[0].profiles().stream().anyMatch(p->p.name().equals("native-ui-imported")),"import persisted connection");verified.add("导入文件");
            Files.writeString(source,"\nHost added-in-cli\n HostName 192.0.2.25\n User fixture\n",StandardOpenOption.APPEND);
            probe(frame[0],"读取终端配置",List.of(NativeDialogVerification::cancel),failure);
            check(app[0].profiles().stream().anyMatch(p->p.name().equals("added-in-cli")),"refresh discovered new CLI connection");int size=app[0].accounts.size();
            probe(frame[0],"读取终端配置",List.of(NativeDialogVerification::cancel),failure);check(app[0].accounts.size()==size,"refresh did not duplicate connections");verified.add("终端配置刷新与去重");
            SwingUtilities.invokeAndWait(()->app[0].selectProfile(app[0].profiles().stream().filter(p->p.name().equals("native-ui-edited")).findFirst().orElseThrow()));
            probe(frame[0],"删除",List.of(dialog->choose(dialog,JOptionPane.OK_OPTION)),failure);check(app[0].accounts.size()==size-1,"delete persisted selected fixture connection");verified.add("删除测试连接");
            SwingUtilities.invokeAndWait(()->frame[0].dispatchEvent(new java.awt.event.WindowEvent(frame[0],java.awt.event.WindowEvent.WINDOW_CLOSING)));
            check(!frame[0].isDisplayable(),"close handler");if(failure.get()!=null)throw new IOException("Native dialog callback failed",failure.get());return verified;
        }finally{
            SwingUtilities.invokeAndWait(()->{if(frame[0]!=null){for(Window child:frame[0].getOwnedWindows())child.dispose();frame[0].dispose();}if(app[0]!=null){app[0].close();}});
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }
    private static void libraryFirstFrame(JFrame frame,Path work)throws Exception{
        for(int attempt=0;attempt<3;attempt++){
            ConnectionLibrary[] library={null};
            SwingUtilities.invokeAndWait(()->{
                JMenu menu=all(frame,JMenu.class).stream().filter(m->"连接".equals(m.getText())).findFirst().orElseThrow();menu.setPopupMenuVisible(true);
                all(frame,JMenuItem.class).stream().filter(m->"连接库".equals(m.getText())).findFirst().orElseThrow().doClick();
            });
            long openDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(library[0]==null&&System.nanoTime()<openDeadline){
                SwingUtilities.invokeAndWait(()->{for(Window child:frame.getOwnedWindows())if(child instanceof JDialog dialog&&dialog.isShowing()&&"connection-library".equals(dialog.getName()))library[0]=(ConnectionLibrary)dialog.getRootPane().getClientProperty("lagless.library");});
                if(library[0]==null)Thread.sleep(10);
            }
            check(library[0]!=null,"library opened from dropdown");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(!library[0].firstPaintCompleted()&&System.nanoTime()<deadline)Thread.sleep(10);
            check(library[0].firstPaintCompleted(),"library rendered without mouse or keyboard input");
            SwingUtilities.invokeAndWait(()->{try{
                JDialog dialog=library[0].dialog;JList<?> list=all(dialog,JList.class).getFirst();check(list.getModel().getSize()>0&&list.getWidth()>100&&list.getCellBounds(0,0).height>0,"visible initial connections");check(MenuSelectionManager.defaultManager().getSelectedPath().length==0,"dropdown dismissed before dialog");
                java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(dialog.getWidth(),dialog.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();dialog.printAll(g);g.dispose();javax.imageio.ImageIO.write(image,"png",work.resolve("connection-library.png").toFile());dialog.dispose();
            }catch(Exception e){throw new RuntimeException(e);}});
        }
    }
    private static void probe(JFrame frame,String label,List<Consumer<JDialog>> steps,AtomicReference<Throwable> failure)throws Exception{
        CountDownLatch shown=new CountDownLatch(1),returned=new CountDownLatch(1);AtomicInteger handled=new AtomicInteger();Set<Window> visited=Collections.newSetFromMap(new IdentityHashMap<>());
        javax.swing.Timer[] poll={null};
        SwingUtilities.invokeLater(()->{
            poll[0]=new javax.swing.Timer(40,e->{try{
                for(Window window:Window.getWindows())if(window instanceof JDialog dialog&&dialog.isShowing()&&ownedBy(dialog,frame)&&visited.add(dialog)){
                    steps.get(handled.get()).accept(dialog);if(handled.incrementAndGet()==steps.size()){poll[0].stop();shown.countDown();break;}
                }
            }catch(Throwable error){failure.compareAndSet(null,error);poll[0].stop();shown.countDown();}});poll[0].start();
            try{AbstractButton button=all(frame,AbstractButton.class).stream().filter(b->label.equals(b.getText())).findFirst().orElseThrow();check(button.isEnabled(),"button enabled: "+label);button.doClick();}
            catch(Throwable error){failure.compareAndSet(null,error);shown.countDown();}finally{returned.countDown();}
        });
        try{
            if(!shown.await(10,TimeUnit.SECONDS)||!returned.await(3,TimeUnit.SECONDS)){
                String visible=Arrays.stream(Window.getWindows()).filter(Window::isShowing).map(w->{
                    String item=w instanceof Dialog d?d.getTitle():w.getClass().getSimpleName();
                    if(w instanceof Dialog d)item+=" owner="+(d.getOwner()==null?"null":d.getOwner().getClass().getSimpleName()+"@"+System.identityHashCode(d.getOwner()));
                    return item;
                }).toList().toString();
                visible+="; expected owner="+frame.getClass().getSimpleName()+"@"+System.identityHashCode(frame);
                throw new IOException("Button did not complete: "+label+"; visible windows="+visible);
            }
            if(failure.get()!=null)throw new IOException("Button failed: "+label,failure.get());check(handled.get()==steps.size(),"expected dialogs for "+label);
        }finally{SwingUtilities.invokeAndWait(()->{if(poll[0]!=null)poll[0].stop();for(Window child:frame.getOwnedWindows())if(child instanceof JDialog)child.dispose();});}
    }
    private static boolean ownedBy(Window window,Window owner){for(Window p=window.getOwner();p!=null;p=p.getOwner())if(p==owner)return true;return false;}
    private static void choose(JDialog dialog,int value){JOptionPane pane=all(dialog,JOptionPane.class).stream().findFirst().orElseThrow();pane.setValue(value);}
    private static void cancel(JDialog dialog){List<JFileChooser> pickers=all(dialog,JFileChooser.class);if(!pickers.isEmpty())pickers.getFirst().cancelSelection();else{List<JOptionPane> panes=all(dialog,JOptionPane.class);if(panes.isEmpty())dialog.dispose();else panes.getFirst().setValue(JOptionPane.CLOSED_OPTION);}}
    private static <T extends Component> List<T> all(Container root,Class<T> type){List<T> result=new ArrayList<>();collect(root,type,result);return result;}
    private static <T extends Component> void collect(Component component,Class<T> type,List<T> result){if(type.isInstance(component))result.add(type.cast(component));if(component instanceof JMenu menu){for(Component child:menu.getMenuComponents())collect(child,type,result);}else if(component instanceof Container container){for(Component child:container.getComponents())collect(child,type,result);}}
    private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException("UI verification failed: "+message);}
}
