package dev.lagless.desktop;

import dev.lagless.I18n;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.util.function.*;

/** Explicit focused-component actions avoid native Swing transfer-action lookup failures. */
final class ClipboardActions {
    private ClipboardActions(){}
    static Clipboard system(){return Toolkit.getDefaultToolkit().getSystemClipboard();}
    static String text(Clipboard clipboard)throws Exception{
        if(clipboard==null){
            if(NativeGraphics.unicodeClipboardAvailable()){
                String value=NativeGraphics.readClipboardText();
                if(value!=null)return value;
                String error=NativeGraphics.clipboardError();
                if(!error.equals("JAVA_LOCAL_CLIPBOARD")){
                    if(!error.isEmpty())throw new IllegalStateException(error);
                    throw new IllegalArgumentException(I18n.t("剪贴板没有可粘贴的文本","The clipboard has no text to paste"));
                }
            }
            clipboard=system();
        }
        Transferable contents=clipboard.getContents(null);if(contents==null||!contents.isDataFlavorSupported(DataFlavor.stringFlavor))throw new IllegalArgumentException(I18n.t("剪贴板没有可粘贴的文本","The clipboard has no text to paste"));
        return (String)contents.getTransferData(DataFlavor.stringFlavor);
    }
    static void putText(Clipboard clipboard,String value){
        if(clipboard==null){
            if(NativeGraphics.unicodeClipboardAvailable()){
                if(value.indexOf('\0')>=0)throw new IllegalArgumentException(I18n.t("文本包含无法写入剪贴板的空字符","The text contains a null character that cannot be copied"));
                String windows=value.replace("\r\n","\n").replace('\r','\n').replace("\n","\r\n");
                if(!NativeGraphics.writeClipboardText(windows))throw new IllegalStateException(NativeGraphics.clipboardError());return;
            }
            clipboard=system();
        }
        clipboard.setContents(new StringSelection(value),null);
    }
    static void install(JTextComponent editor,Consumer<String> notice){install(editor,notice,null);}
    static void install(JTextComponent editor,Consumer<String> notice,Supplier<Clipboard> clipboard){
        install(editor,notice,clipboard,null);
    }
    static void install(JTextComponent editor,Consumer<String> notice,Supplier<Clipboard> clipboard,Consumer<Image> imagePaste){
        JPopupMenu menu=new JPopupMenu();
        for(String operation:new String[]{"cut","copy","paste","select-all"}){
            String label=switch(operation){case "cut"->I18n.t("剪切","Cut");case "copy"->I18n.t("复制","Copy");case "paste"->I18n.t("粘贴","Paste");default->I18n.t("全选","Select all");};
            Action action=new AbstractAction(label){public void actionPerformed(ActionEvent e){try{
                switch(operation){
                    case "cut","copy"->{String selected=editor.getSelectedText();if(selected==null||selected.isEmpty())return;putText(clipboard==null?null:clipboard.get(),selected);if(operation.equals("cut")&&editor.isEditable())editor.replaceSelection("");}
                    case "paste"->{if(editor.isEditable()){
                        boolean pastedImage=false;
                        if(imagePaste!=null){
                            try{
                                Clipboard source=clipboard==null?system():clipboard.get();
                                Transferable contents=source.getContents(null);
                                if(contents!=null&&contents.isDataFlavorSupported(DataFlavor.imageFlavor)){
                                    imagePaste.accept((Image)contents.getTransferData(DataFlavor.imageFlavor));
                                    pastedImage=true;
                                }
                            }catch(IllegalStateException busy){/* Native Unicode text paste below can still work. */}
                        }
                        if(!pastedImage)editor.replaceSelection(text(clipboard==null?null:clipboard.get()).replace("\r\n","\n").replace('\r','\n'));
                    }}
                    default->editor.selectAll();
                }
            }catch(Exception error){notice.accept(I18n.t("剪贴板操作失败，请重试：","Clipboard action failed, try again: ")+error.getMessage());}}};
            String key="lagless-"+operation;editor.getActionMap().put(key,action);int code=switch(operation){case "cut"->KeyEvent.VK_X;case "copy"->KeyEvent.VK_C;case "paste"->KeyEvent.VK_V;default->KeyEvent.VK_A;};
            for(int modifier:new int[]{InputEvent.CTRL_DOWN_MASK,InputEvent.META_DOWN_MASK})editor.getInputMap().put(KeyStroke.getKeyStroke(code,modifier),key);
            JMenuItem item=new JMenuItem(action);item.setAccelerator(KeyStroke.getKeyStroke(code,System.getProperty("os.name").startsWith("Mac")?InputEvent.META_DOWN_MASK:InputEvent.CTRL_DOWN_MASK));menu.add(item);
        }
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT,InputEvent.CTRL_DOWN_MASK),"lagless-copy");editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT,InputEvent.SHIFT_DOWN_MASK),"lagless-paste");editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE,InputEvent.SHIFT_DOWN_MASK),"lagless-cut");editor.setComponentPopupMenu(menu);
    }
}
