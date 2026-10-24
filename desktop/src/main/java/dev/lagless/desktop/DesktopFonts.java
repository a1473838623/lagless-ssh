package dev.lagless.desktop;

import java.awt.*;
import java.util.*;

/** Platform UI fonts, rendered at the device scale rather than bitmap-enlarged. */
final class DesktopFonts {
    private DesktopFonts(){ }
    private static final class UiFont {static final Font VALUE=createUiFont();}
    static Font ui(){return UiFont.VALUE;}
    private static final String EDITOR_GLYPHS="中文测试汉字，。！？";
    private static final class EditorFont {static final Font VALUE=createEditorFont();}
    static Font editor(float size){return EditorFont.VALUE.deriveFont(size);}
    private static Font createEditorFont(){
        // A physical Latin monospace face (e.g. Consolas) has no CJK glyphs.
        // JTextArea does not inherit DirectWrite's terminal font fallback.
        Set<String> available=new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ENGLISH)));
        for(String name:new String[]{"Sarasa Mono SC","Sarasa Term SC","Noto Sans Mono CJK SC"}){
            Font font=new Font(name,Font.PLAIN,14);if(available.contains(name)&&font.canDisplayUpTo(EDITOR_GLYPHS)<0)return font;
        }
        Font platform=ui().deriveFont(Font.PLAIN,14f);if(platform.canDisplayUpTo(EDITOR_GLYPHS)<0)return platform;
        for(String name:new String[]{"Microsoft YaHei UI","Microsoft YaHei","PingFang SC","Noto Sans CJK SC","SimSun"}){
            Font font=new Font(name,Font.PLAIN,14);if(available.contains(name)&&font.canDisplayUpTo(EDITOR_GLYPHS)<0)return font;
        }
        return new Font(Font.DIALOG,Font.PLAIN,14);
    }
    static Font monospace(float size){return MonoFont.VALUE.deriveFont(size);}
    private static final class MonoFont {
        static final Font VALUE=createMonoFont();
        private static Font createMonoFont(){
            Set<String> fonts=new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            for(String name:new String[]{"Consolas","Menlo","DejaVu Sans Mono"})if(fonts.contains(name))return new Font(name,Font.PLAIN,16);
            return new Font(Font.MONOSPACED,Font.PLAIN,16);
        }
    }
    private static Font createUiFont(){
        Set<String> available=new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        String os=System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String[] preferred=os.contains("win")?new String[]{"Microsoft YaHei UI","Microsoft YaHei","Segoe UI"}:
                os.contains("mac")?new String[]{"PingFang SC","Helvetica Neue"}:new String[]{"Noto Sans CJK SC","Noto Sans","DejaVu Sans"};
        for(String family:preferred)if(available.contains(family))return new Font(family,Font.PLAIN,14);
        return new Font(Font.DIALOG,Font.PLAIN,14);
    }
    static void textHints(Graphics2D graphics){
        Object hints=Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
        if(hints instanceof Map<?,?> map)graphics.addRenderingHints(map);
        else graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }
}
