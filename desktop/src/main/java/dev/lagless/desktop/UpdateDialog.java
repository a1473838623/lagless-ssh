package dev.lagless.desktop;

import dev.lagless.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/** Worker reports are coalesced; neither network reads nor validation run on AWT. */
final class UpdateDialog {
    private final DesktopApp app;
    private final JDialog dialog;
    private final JTextArea output=DesktopApp.textArea();
    private final JLabel status=new JLabel(I18n.t("准备就绪","Ready")),details=new JLabel(" ");
    private final JProgressBar progress=new JProgressBar(0,1000);
    private final JButton download,check,importBundle,rollback,restart;
    private final AtomicReference<Updater.Progress> latest=new AtomicReference<>();
    private final Timer timer;
    private boolean busy,disposed;
    private long started,lastReceivedAt,received=-1,initialReceived;
    private Updater.Progress displayed;
    private record Result(String text,boolean ok){}

    UpdateDialog(DesktopApp app,Component parent,boolean checkOnOpen){
        this.app=app;output.setEditable(false);output.setLineWrap(true);output.setWrapStyleWord(true);
        dialog=DesktopApp.dialog(parent,I18n.t("应用更新","Apply update"),output,780,460);
        // Reopening the menu brings back this operation and its current progress.
        dialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        JPanel top=new JPanel(new BorderLayout(0,6));top.setBorder(BorderFactory.createEmptyBorder(12,14,10,14));
        JPanel labels=new JPanel(new BorderLayout(0,4));labels.add(status,BorderLayout.NORTH);labels.add(details,BorderLayout.SOUTH);
        progress.setStringPainted(true);progress.setString(I18n.t("准备就绪","Ready"));top.add(labels,BorderLayout.NORTH);top.add(progress,BorderLayout.SOUTH);dialog.add(top,BorderLayout.NORTH);
        JPanel buttons=DesktopApp.row();
        check=DesktopApp.button(I18n.t("检查更新","Check for updates"),()->runUpdate(true));download=DesktopApp.button(I18n.t("下载更新","Download update"),()->runUpdate(false));
        importBundle=DesktopApp.button(I18n.t("导入原生更新包","Import native update package"),this::importBundle);
        rollback=DesktopApp.button(I18n.t("回滚上一版本","Roll back to previous version"),()->{if(confirmRollback())start(I18n.t("正在准备回滚…","Preparing rollback…"),()->{if(DesktopRuntime.nativeImage())NativeUpdater.rollback();else Updater.stageRollback(Installation.runningJar());return new Result(I18n.t("回滚已准备好，请重启以应用。","Rollback is ready; restart to apply it."),true);});});
        restart=DesktopApp.button(I18n.t("重启并应用更新","Restart and apply update"),()->app.restartForUpdate(dialog));
        buttons.add(check);buttons.add(download);if(DesktopRuntime.nativeImage()){buttons.add(importBundle);buttons.add(restart);output.setText(NativeUpdater.status()+"\n\n");}buttons.add(rollback);dialog.add(buttons,BorderLayout.SOUTH);
        timer=new Timer(150,event->renderProgress());
        dialog.addWindowListener(new WindowAdapter(){@Override public void windowClosed(WindowEvent event){disposed=true;timer.stop();}});
        refresh();if(checkOnOpen)runUpdate(true);
    }
    /** Names the version rollback restores, and warns when it is not the one used just before. */
    private boolean confirmRollback(){
        if(!DesktopRuntime.nativeImage())return true;
        NativeUpdater.Backup backup=NativeUpdater.previousBackup(DesktopRuntime.home());
        if(backup==null)return true; // rollback() reports the missing backup
        String version=backup.version()==null?I18n.t("（版本未知）","(unknown version)"):backup.version();
        String message=backup.immediate()?I18n.t("回滚到上一版本 ","Roll back to the previous version, ")+version+I18n.t("？","?")
                :I18n.t("保存的备份是 ","The saved backup is ")+version+I18n.t("。当前版本 ",". The current version, ")+DesktopRuntime.version()
                        +I18n.t(" 不是通过程序内更新安装的（例如手动解压 ZIP），因此没有保存它之前的那个版本。\n仍要回滚到 "," was not installed by the in-app updater (for example, by extracting a ZIP), so the version you used just before it was not saved.\nRoll back to ")+version+I18n.t("？","anyway?");
        return JOptionPane.showConfirmDialog(dialog,message,I18n.t("回滚","Roll back"),JOptionPane.OK_CANCEL_OPTION,backup.immediate()?JOptionPane.QUESTION_MESSAGE:JOptionPane.WARNING_MESSAGE)==JOptionPane.OK_OPTION;
    }
    boolean isDisplayable(){return !disposed;}
    void show(){dialog.setVisible(true);dialog.toFront();}
    void dispose(){dialog.dispose();timer.stop();disposed=true;}
    private void refresh(){check.setEnabled(!busy);download.setEnabled(!busy);importBundle.setEnabled(!busy);rollback.setEnabled(!busy);restart.setEnabled(!busy&&DesktopRuntime.nativeImage()&&NativeUpdater.hasPending());}
    private void runUpdate(boolean onlyCheck){start(onlyCheck?I18n.t("正在检查更新…","Checking for updates…"):I18n.t("正在检查并准备下载更新…","Checking and preparing the download…"),()->{
        if(DesktopRuntime.nativeImage())return new Result(NativeUpdater.update(onlyCheck,latest::set),true);
        LaglessConfig config=LaglessConfig.load(LaglessConfig.defaultPath());StringWriter text=new StringWriter();
        Updater updater=new Updater(new PrintWriter(text,true),config.get(null,"update-repo"),Updater.findToken(config),latest::set);
        int code=onlyCheck?updater.check():updater.update();return new Result(text.toString(),code==0);
    });}
    private void importBundle(){JFileChooser picker=new JFileChooser();if(picker.showOpenDialog(dialog)!=JFileChooser.APPROVE_OPTION)return;
        Path archive=picker.getSelectedFile().toPath();start(I18n.t("正在校验并准备本地更新包…","Verifying and preparing the local update package…"),()->{NativeUpdater.stage(DesktopRuntime.home(),archive,true);return new Result(I18n.t("原生包已校验，安装助手已就绪，请点击重启并应用更新。","Native package verified and the installer is ready. Click Restart and apply update."),true);});}
    private void start(String message,Callable<Result> action){
        if(busy||disposed)return;busy=true;received=-1;started=lastReceivedAt=System.nanoTime();displayed=null;latest.set(new Updater.Progress(message,-1,-1));
        progress.setValue(0);details.setText(" ");output.append(message+"\n");renderProgress();refresh();timer.start();app.events.operation(message);
        try{app.workers.submit(()->{try{Result result=action.call();app.events.operation(result.ok()?I18n.t("更新操作完成","Update finished"):I18n.t("更新操作未完成","Update did not finish"));SwingUtilities.invokeLater(()->finish(result,null));}
            catch(Exception|LinkageError error){app.events.error(I18n.t("应用更新","Apply update"),error);SwingUtilities.invokeLater(()->finish(null,error));}});}
        catch(RuntimeException error){app.events.error(I18n.t("应用更新","Apply update"),error);finish(null,error);}
    }
    private void renderProgress(){
        Updater.Progress event=latest.get();if(event==null)return;long now=System.nanoTime();
        if(event.received()<0){if(event!=displayed){status.setText(event.message());progress.setIndeterminate(true);progress.setString(event.message());output.append(event.message()+"\n");details.setText(" ");}displayed=event;return;}
        if(received<0){initialReceived=event.received();started=lastReceivedAt=now;output.append(I18n.t("下载文件：","Downloading file: ")+event.message()+"\n");}
        if(event.received()!=received){received=event.received();lastReceivedAt=now;}
        double seconds=Math.max(.5,(now-started)/1e9);long idle=(now-lastReceivedAt)/1_000_000_000L;
        status.setText(I18n.t("正在下载更新包","Downloading update package"));boolean known=event.total()>0;progress.setIndeterminate(!known);
        if(known){double fraction=Math.min(1,event.received()/(double)event.total());progress.setValue((int)Math.round(fraction*1000));progress.setString(String.format(Locale.ROOT,"%.1f%%",fraction*100));}else progress.setString(I18n.t("正在下载…","Downloading…"));
        details.setText(bytes(event.received())+(known?" / "+bytes(event.total()):I18n.t(" · 总大小未知"," · total size unknown"))+I18n.t(" · 平均 "," · average ")+bytes((long)((event.received()-initialReceived)/seconds))+"/s"+(idle>=2?I18n.t(" · 等待数据 "," · waiting for data ")+idle+I18n.t(" 秒"," s"):""));displayed=event;
    }
    private void finish(Result result,Throwable error){
        busy=false;timer.stop();if(disposed)return;renderProgress();progress.setIndeterminate(false);boolean ok=error==null&&result.ok();
        String message=ok?I18n.t("操作完成","Done"):error==null?I18n.t("更新未完成","Update did not finish"):I18n.t("更新失败：","Update failed: ")+(error.getMessage()==null?error.toString():error.getMessage());
        status.setText(message);progress.setString(ok?I18n.t("已完成","Finished"):I18n.t("未完成","Not finished"));if(ok)progress.setValue(progress.getMaximum());
        output.append((result==null?message:result.text())+"\n\n");output.setCaretPosition(output.getDocument().getLength());refresh();
    }
    private static String bytes(long value){if(value<1024)return value+" B";if(value<1024L*1024)return String.format(Locale.ROOT,"%.1f KiB",value/1024d);if(value<1024L*1024*1024)return String.format(Locale.ROOT,"%.1f MiB",value/(1024d*1024));return String.format(Locale.ROOT,"%.2f GiB",value/(1024d*1024*1024));}
}
