package dev.lagless.desktop;

import javax.swing.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Exercise native clipboard initialization without changing the user's clipboard. */
final class NativeInputVerification {
    private NativeInputVerification() { }
    static void run(Path home, Map<String,Object> report)throws Exception {
        // Windows clipboard string conversion initializes both of these reflective
        // paths. A private Clipboard alone does not exercise the platform checks.
        require(!DataFlavor.stringFlavor.isRepresentationClassRemote(),"RMI clipboard flavor");
        require(ObjectStreamClass.lookup(String.class).getSerialVersionUID()!=0,"String serialization metadata");
        ClipboardActions.system().getAvailableDataFlavors();
        SwingUtilities.invokeAndWait(()->{
            Clipboard clipboard=new Clipboard("native isolated clipboard");JTextArea editor=DesktopApp.textArea();
            ClipboardActions.install(editor,message->{throw new IllegalStateException(message);},()->clipboard);
            editor.setText("hello 中文");editor.select(6,8);key(editor,"ctrl C");
            try{require(clipboard.getData(DataFlavor.stringFlavor).equals("中文"),"copy Unicode");}catch(Exception e){throw new RuntimeException(e);}
            key(editor,"ctrl X");require(editor.getText().equals("hello "),"cut");
            clipboard.setContents(new StringSelection("甲\r\n乙"),null);key(editor,"ctrl V");
            require(editor.getText().equals("hello 甲\n乙"),"paste normalized lines");key(editor,"ctrl A");require(editor.getSelectedText().equals(editor.getText()),"select all");
            var image=new java.awt.image.BufferedImage(8,6,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            clipboard.setContents(new Transferable(){
                public DataFlavor[] getTransferDataFlavors(){return new DataFlavor[]{DataFlavor.imageFlavor};}
                public boolean isDataFlavorSupported(DataFlavor flavor){return DataFlavor.imageFlavor.equals(flavor);}
                public Object getTransferData(DataFlavor flavor){return image;}
            },null);
            java.awt.Image[] attached={null};
            ClipboardActions.install(editor,message->{throw new IllegalStateException(message);},()->clipboard,pasted->attached[0]=pasted);
            editor.setText("question");key(editor,"ctrl V");
            require(attached[0]==image&&editor.getText().equals("question"),"paste image as attachment");
        });
        report.put("clipboard-shortcuts-native",true);
        report.put("assistant-image-paste-native",true);
        SwingTerminal[] view={null};TerminalHistory history=new TerminalHistory(home,"native-scroll-test");
        try{
            for(int batch=0;batch<12;batch++){for(int n=0;n<1000;n++)history.append(new TerminalHistory.Row(100,"\u001b[32m历史-"+(batch*1000+n)+"\u001b[0m"));history.force();}
            require(history.count()==12000&&history.omitted()==0,"disk history capacity");
            SwingUtilities.invokeAndWait(()->{view[0]=new SwingTerminal();view[0].setSize(900,500);view[0].setHistory(history);view[0].showHistory(0);});
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!view[0].displayedText().contains("历史-0")){require(System.nanoTime()<until,"historical page loads");Thread.sleep(10);}
            paintHistory(view[0],home.resolve("native-history-start.png"));
            SwingUtilities.invokeAndWait(()->view[0].historyScrollBar().setValue(7000));
            until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!view[0].displayedText().startsWith("历史-7000\n")){require(System.nanoTime()<until,"scrollbar changes displayed rows");Thread.sleep(10);}
            paintHistory(view[0],home.resolve("native-history-scrolled.png"));
            SwingUtilities.invokeAndWait(()->{for(int n=0;n<80;n++)view[0].historyScrollBar().setValue(500+n);view[0].historyScrollBar().setValue(0);});
            until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!view[0].displayedText().startsWith("历史-0\n")){require(System.nanoTime()<until,"rapid scroll settles on requested page");Thread.sleep(10);}
            report.put("history-scrollbar-paint",true);
            SwingUtilities.invokeAndWait(()->{view[0].display("new output\r\n".repeat(100));require(!view[0].followingLive()&&view[0].viewportStart()==0,"new output does not jump history");view[0].setLocalEditing(false);view[0].display("\u001b[?1049h\u001b[HClaude frame\r\nanswer before redraw");});
            long before=history.count();Thread.sleep(280);SwingUtilities.invokeAndWait(()->view[0].display("\u001b[2J"));history.force();
            require(history.count()==before,"full-screen redraw does not enter ordinary terminal history");
            for(long n=Math.max(0,before-512);n<history.count();n++)require(!history.read(n).model().rowText(0).contains("Claude frame"),"terminal history contains no full-screen frame");
            SwingUtilities.invokeAndWait(()->{view[0].followLatest();require(view[0].followingLive(),"return to latest output");});
            report.put("disk-scrollback-rows",12000);report.put("fullscreen-history-excluded",true);
        }finally{if(view[0]!=null)SwingUtilities.invokeAndWait(view[0]::close);else history.close();}
    }
    static void drag(BottomInputPane input,int delta){
        var handle=input.resizeHandle;long now=System.currentTimeMillis();
        handle.dispatchEvent(new MouseEvent(handle,MouseEvent.MOUSE_PRESSED,now,InputEvent.BUTTON1_DOWN_MASK,5,5,500,500,1,false,MouseEvent.BUTTON1));
        handle.dispatchEvent(new MouseEvent(handle,MouseEvent.MOUSE_DRAGGED,now,InputEvent.BUTTON1_DOWN_MASK,5+130,5+delta,630,500+delta,0,false,MouseEvent.NOBUTTON));
        // Render during dragging too: resizing must never destroy the paint surface.
        java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(Math.max(1,input.getWidth()),Math.max(1,input.getHeight()),java.awt.image.BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();input.printAll(graphics);graphics.dispose();
        handle.dispatchEvent(new MouseEvent(handle,MouseEvent.MOUSE_RELEASED,now,0,5+130,5+delta,630,500+delta,1,false,MouseEvent.BUTTON1));
    }
    private static void paintHistory(SwingTerminal view,Path path)throws Exception{
        SwingUtilities.invokeAndWait(()->{try{
            java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(1125,625,java.awt.image.BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();graphics.scale(1.25,1.25);graphics.setClip(0,0,900,500);
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,java.awt.RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.fillRoundRect(1,1,8,8,3,3);view.printAll(graphics);graphics.dispose();javax.imageio.ImageIO.write(image,"png",path.toFile());
        }catch(Exception e){throw new RuntimeException(e);}});
    }
    static void key(JTextArea editor,String stroke){Object binding=editor.getInputMap().get(KeyStroke.getKeyStroke(stroke));editor.getActionMap().get(binding).actionPerformed(new ActionEvent(editor,0,"verify"));}
    static void remoteKeys(SwingTerminal view){
        var listener=view.getKeyListeners()[0];long now=System.currentTimeMillis();
        listener.keyPressed(new KeyEvent(view,KeyEvent.KEY_PRESSED,now,InputEvent.CTRL_DOWN_MASK,KeyEvent.VK_X,'\u0018'));
        listener.keyPressed(new KeyEvent(view,KeyEvent.KEY_PRESSED,now,InputEvent.CTRL_DOWN_MASK,KeyEvent.VK_S,'\u0013'));
        listener.keyPressed(new KeyEvent(view,KeyEvent.KEY_PRESSED,now,InputEvent.ALT_DOWN_MASK,KeyEvent.VK_V,'v'));
        listener.keyTyped(new KeyEvent(view,KeyEvent.KEY_TYPED,now,InputEvent.ALT_DOWN_MASK,KeyEvent.VK_UNDEFINED,'v'));
    }
    private static void require(boolean pass,String message){if(!pass)throw new IllegalStateException("Verification failed: "+message);}
}
