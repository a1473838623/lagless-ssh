package dev.lagless.desktop;

import java.awt.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.JComboBox;

/** Optional Windows display backend; no dependency from the SSH core. */
final class NativeGraphics {
    private static boolean attempted, loaded;
    private static boolean bridgeAttempted,bridgeLoaded;
    private static String bridgeFailure="";
    private static boolean clipboardChecked,unicodeClipboard;
    private static String reason="尚未初始化";
    private static int surfaces;
    private static final boolean WINDOWS=System.getProperty("os.name","").startsWith("Windows");
    private NativeGraphics() { }
    static synchronized boolean available() {
        if(attempted)return loaded;
        attempted=true;
        if(!WINDOWS){reason="当前平台使用兼容显示";return false;}
        try {
            DesktopPreferences prefs=new DesktopPreferences(Path.of(System.getProperty("user.home"),".lagless"));
            if("compat".equals(prefs.get("renderer","native"))){reason="已选择兼容显示（重启后生效）";return false;}
            if(!bridgeAvailable()){reason="兼容显示："+bridgeFailure;return false;}
            loaded=true;reason="DirectWrite / Direct2D · 原生文字与整帧呈现";
        }catch(Throwable error){reason="兼容显示："+error.getClass().getSimpleName()+" · "+Objects.toString(error.getMessage(),"");}
        return loaded;
    }
    /** Clipboard services remain available when the user selects compatible rendering. */
    static synchronized boolean bridgeAvailable(){
        if(bridgeAttempted)return bridgeLoaded;
        bridgeAttempted=true;if(!WINDOWS)return false;
        try{
            Path jar=DesktopRuntime.nativeImage()?null:dev.lagless.Installation.runningJar();
            Path directory=jar==null?DesktopRuntime.home():jar.getParent();
            Path dll=directory.resolve("lagless-render.dll");
            if(!Files.isRegularFile(dll))dll=DesktopRuntime.home().resolve("lagless-render.dll");
            if(!Files.isRegularFile(dll))dll=DesktopRuntime.home().resolve("desktop/target/native-renderer/lagless-render.dll");
            System.load(dll.toAbsolutePath().toString());
            bridgeLoaded=true;
        }catch(Throwable error){bridgeFailure=error.getClass().getSimpleName()+" · "+Objects.toString(error.getMessage(),"");}
        return bridgeLoaded;
    }
    static synchronized String status(){available();return reason+(surfaces>0?" · "+surfaces+" 个原生视口":"");}
    static synchronized boolean unicodeClipboardAvailable(){
        if(clipboardChecked)return unicodeClipboard;clipboardChecked=true;
        if(bridgeAvailable())try{unicodeClipboard=clipboardVersion()>=1;}catch(UnsatisfiedLinkError oldLibrary){unicodeClipboard=false;}
        return unicodeClipboard;
    }
    static String clipboardStatus(){
        if(unicodeClipboardAvailable())return "Windows Unicode 已启用";
        if(!WINDOWS)return "系统兼容方式";
        return bridgeLoaded?"兼容方式：原生库较旧，请完整更新":"兼容方式：未载入原生库，请完整更新";
    }
    static synchronized void opened(){surfaces++;reason="DirectWrite / Direct2D · 原生文字与整帧呈现";}
    static synchronized void closed(){surfaces=Math.max(0,surfaces-1);}
    static synchronized void failed(String message){reason="兼容显示："+message;}
    static void settings(Component parent,DesktopPreferences prefs){
        available();JComboBox<String> choice=new JComboBox<>(new String[]{"原生 DirectWrite / Direct2D（Windows）","兼容显示"});
        choice.setSelectedIndex("compat".equals(prefs.get("renderer","native"))?1:0);
        javax.swing.JPanel panel=new javax.swing.JPanel(new java.awt.BorderLayout(8,12));
        javax.swing.JTextArea info=new javax.swing.JTextArea("当前："+status()+"\n\n原生显示覆盖终端、终端历史和日志正文。\n选择将在下次启动时生效。");
        info.setEditable(false);info.setLineWrap(true);info.setWrapStyleWord(true);info.setOpaque(false);info.setColumns(48);panel.add(info,java.awt.BorderLayout.CENTER);panel.add(choice,java.awt.BorderLayout.SOUTH);
        if(javax.swing.JOptionPane.showConfirmDialog(parent,panel,"显示渲染",javax.swing.JOptionPane.OK_CANCEL_OPTION,javax.swing.JOptionPane.PLAIN_MESSAGE)==javax.swing.JOptionPane.OK_OPTION)prefs.set("renderer",choice.getSelectedIndex()==0?"native":"compat");
    }
    record Frame(int columns,int rows,int[] characters,int[] foreground,int[] background,
                 int cellWidth,int cellHeight,int ascent,int offset,int cursorColumn,int cursorRow,boolean cursor,float fontSize,int paper) { }
    static native long open(Canvas canvas);
    static native boolean draw(long handle,int width,int height,int columns,int rows,int[] characters,int[] foreground,int[] background,
                               int cellWidth,int cellHeight,int ascent,int offset,int cursorColumn,int cursorRow,boolean cursor,float fontSize,int paper);
    static native void dispose(long handle);
    static native String error();
    static native long[] fileUsers(String[] paths);
    static native String readClipboardText();
    static native boolean writeClipboardText(String text);
    static native String clipboardError();
    static native int clipboardVersion();
    static native byte[] protectSecret(byte[] plain);
    static native byte[] unprotectSecret(byte[] encrypted);
}
