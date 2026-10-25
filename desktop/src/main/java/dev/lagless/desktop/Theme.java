package dev.lagless.desktop;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import dev.lagless.I18n;
import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntFunction;

/**
 * Light and dark palettes. Colors handed out here are live tokens: a component
 * that was given {@link #MUTED} shows the dark value after a switch, without
 * being rebuilt. Look-and-feel defaults are reinstalled for each palette.
 */
final class Theme {
    private Theme(){}
    static final String KEY="theme";

    enum Mode {
        SYSTEM("system"),LIGHT("light"),DARK("dark");
        final String code;
        Mode(String code){this.code=code;}
        static Mode parse(String value){for(Mode m:values())if(m.code.equals(value))return m;return SYSTEM;}
        String label(){return switch(this){case SYSTEM->I18n.t("跟随系统","Follow system");case LIGHT->I18n.t("浅色","Light");case DARK->I18n.t("深色","Dark");};}
    }

    /** Every color the interface uses, by role. */
    record Palette(boolean dark,int canvas,int surface,int raised,int hover,int line,int border,int ink,int text,int muted,int faint,
                   int accent,int accentHover,int accentPressed,int onAccent,int tint,int selection,int focus,int tooltip,int tooltipText,int shadow,int shadowAlpha){}

    // A quiet terminal frame: graphite surfaces, restrained violet actions,
    // and enough contrast to read controls without competing with SSH output.
    static final Palette LIGHT=new Palette(false,
            0xe9edf4,0xf7f8fb,0xffffff,0xe7eaf2,0xd7deea,0xc4cede,
            0x1b2434,0x3c495f,0x69768b,0x929caf,
            0x5d4bd8,0x4b39c2,0x3c2d9f,0xffffff,0xeae6fb,0xded8ff,0x8b79e8,
            0x202536,0xf5f6fb,0x101727,5);
    static final Palette DARK=new Palette(true,
            0x11151d,0x1a202b,0x252d3a,0x303a4b,0x303949,0x465267,
            0xf3f5fa,0xd0d8e5,0x9ba9bc,0x718198,
            0x9a83f5,0xb29fff,0x806bd2,0x171323,0x322c49,0x4c4373,0xaf9dff,
            0xe7eaf2,0x171b25,0x000000,18);

    private static volatile Palette current=DARK;
    private static Mode mode=Mode.DARK;
    private static final List<Runnable> listeners=new CopyOnWriteArrayList<>();

    static Palette palette(){return current;}
    static boolean dark(){return current.dark();}
    static Mode mode(){return mode;}

    static final Color CANVAS=token(Palette::canvas),SURFACE=token(Palette::surface),RAISED=token(Palette::raised),HOVER=token(Palette::hover),
            LINE=token(Palette::line),BORDER=token(Palette::border),INK=token(Palette::ink),TEXT=token(Palette::text),MUTED=token(Palette::muted),
            FAINT=token(Palette::faint),ACCENT=token(Palette::accent),ACCENT_HOVER=token(Palette::accentHover),ON_ACCENT=token(Palette::onAccent),
            TINT=token(Palette::tint),SELECTION=token(Palette::selection);

    static Color token(ToIntFunction<Palette> role){return new Token(role);}
    /** A translucent shadow suited to the palette; stronger on dark surfaces. */
    static Color shadow(int step){Palette p=current;int base=p.shadow();return new Color(base>>16&255,base>>8&255,base&255,Math.min(255,p.shadowAlpha()*step));}
    static String hex(Color color){return String.format("#%06x",color.getRGB()&0xffffff);}

    /** A color whose value is read from the current palette every time it is used. */
    private static final class Token extends Color {
        private final ToIntFunction<Palette> role;
        Token(ToIntFunction<Palette> role){super(0);this.role=role;}
        @Override public int getRGB(){return 0xff000000|role.applyAsInt(current);}
        @Override public int getRed(){return getRGB()>>16&255;}
        @Override public int getGreen(){return getRGB()>>8&255;}
        @Override public int getBlue(){return getRGB()&255;}
        @Override public int getAlpha(){return 255;}
        @Override public int hashCode(){return getRGB();}
    }

    static void addListener(Runnable listener){listeners.add(listener);}
    static void removeListener(Runnable listener){listeners.remove(listener);}

    /** The saved choice, or -Dlagless.theme for screenshots and tests. */
    static Mode saved(Path home){
        String forced=System.getProperty("lagless.theme");
        if(forced!=null)return Mode.parse(forced);
        try{return Mode.parse(new DesktopPreferences(home).get(KEY,"dark"));}catch(Exception e){return Mode.DARK;}
    }
    static Palette resolve(Mode mode){return switch(mode){case LIGHT->LIGHT;case DARK->DARK;case SYSTEM->systemDark()?DARK:LIGHT;};}

    /** Installs the palette for {@code mode}; open windows update in place. */
    static void apply(Mode chosen){apply(chosen,resolve(chosen));}
    static void apply(Mode chosen,Palette palette){
        mode=chosen;
        boolean change=current!=palette||!(UIManager.getLookAndFeel() instanceof FlatLaf);
        current=palette;
        if(!change)return;
        Font font=DesktopFonts.ui();
        FlatLaf.setPreferredFontFamily(font.getFamily());
        FlatLaf.setGlobalExtraDefaults(Map.of("@accentColor",hex(ACCENT),"@background",hex(CANVAS)));
        if(palette.dark())FlatDarkLaf.setup();else FlatLightLaf.setup();
        install(palette,font);
        if(Window.getWindows().length>0){FlatLaf.updateUI();for(Window window:Window.getWindows())window.repaint();}
        listeners.forEach(Runnable::run);
    }

    private static ColorUIResource c(int rgb){return new ColorUIResource(rgb);}
    private static void install(Palette p,Font font){
        // Native display canvases are heavyweight windows. Menus/tooltips must
        // also be heavyweight to stay above them, including on separate monitors.
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        // A menu needs one opaque popup window above the native terminal.
        // Avoid a second translucent shadow window and its separate peer.
        UIManager.put("Popup.dropShadowPainted",false);
        UIManager.put("defaultFont",font);
        for(Object key:Collections.list(UIManager.getDefaults().keys()))
            if(UIManager.get(key) instanceof javax.swing.plaf.FontUIResource)UIManager.put(key,font);
        // Named roles, for FlatLaf styles such as "background:$Lagless.accent".
        UIManager.put("Lagless.accent",c(p.accent()));UIManager.put("Lagless.accentHover",c(p.accentHover()));UIManager.put("Lagless.accentPressed",c(p.accentPressed()));
        UIManager.put("Lagless.onAccent",c(p.onAccent()));UIManager.put("Lagless.tint",c(p.tint()));UIManager.put("Lagless.ink",c(p.ink()));
        UIManager.put("Lagless.surface",c(p.surface()));UIManager.put("Lagless.hover",c(p.hover()));UIManager.put("Lagless.line",c(p.line()));UIManager.put("Lagless.border",c(p.border()));
        UIManager.put("Component.arc",6);UIManager.put("Button.arc",6);UIManager.put("TextComponent.arc",6);UIManager.put("CheckBox.arc",4);UIManager.put("ProgressBar.arc",6);
        UIManager.put("Component.focusWidth",1);UIManager.put("Component.innerFocusWidth",0);UIManager.put("Component.focusColor",c(p.focus()));UIManager.put("Component.focusedBorderColor",c(p.accent()));
        UIManager.put("Component.borderColor",c(p.border()));UIManager.put("Component.disabledBorderColor",c(p.line()));
        UIManager.put("Panel.background",c(p.canvas()));UIManager.put("OptionPane.background",c(p.surface()));UIManager.put("RootPane.background",c(p.canvas()));
        UIManager.put("Label.foreground",c(p.ink()));UIManager.put("Label.disabledForeground",c(p.faint()));
        // The tab headers paint flat workspace tabs, integrated with the canvas.
        UIManager.put("TabbedPane.tabHeight",43);UIManager.put("TabbedPane.showTabSeparators",false);UIManager.put("TabbedPane.tabType","card");
        UIManager.put("TabbedPane.tabSelectionHeight",0);UIManager.put("TabbedPane.contentSeparatorHeight",0);UIManager.put("TabbedPane.tabInsets",new Insets(4,3,4,3));
        UIManager.put("TabbedPane.tabAreaInsets",new Insets(0,8,0,8));UIManager.put("TabbedPane.contentBorderInsets",new Insets(0,0,0,0));
        for(String key:new String[]{"background","selectedBackground","hoverColor","focusColor","contentAreaColor","underlineColor","inactiveUnderlineColor","disabledUnderlineColor"})
            UIManager.put("TabbedPane."+key,c(p.canvas()));
        UIManager.put("TabbedPane.selectedForeground",c(p.ink()));UIManager.put("TabbedPane.foreground",c(p.text()));UIManager.put("TabbedPane.buttonHoverBackground",c(p.hover()));
        UIManager.put("MenuBar.background",c(p.surface()));UIManager.put("MenuBar.borderColor",c(p.line()));UIManager.put("MenuBar.hoverBackground",c(p.hover()));UIManager.put("MenuBar.itemMargins",new Insets(4,10,4,10));
        UIManager.put("PopupMenu.background",c(p.surface()));UIManager.put("PopupMenu.borderColor",c(p.border()));UIManager.put("PopupMenu.borderInsets",new Insets(5,1,5,1));
        UIManager.put("MenuItem.margin",new Insets(6,10,6,10));UIManager.put("Menu.margin",new Insets(6,10,6,10));UIManager.put("MenuItem.selectionArc",8);UIManager.put("MenuItem.selectionInsets",new Insets(0,5,0,5));
        for(String menu:new String[]{"Menu","MenuItem","CheckBoxMenuItem","RadioButtonMenuItem"}){
            UIManager.put(menu+".background",c(p.surface()));UIManager.put(menu+".foreground",c(p.ink()));
            UIManager.put(menu+".selectionBackground",c(p.tint()));UIManager.put(menu+".selectionForeground",c(p.ink()));
        }
        UIManager.put("MenuItem.acceleratorForeground",c(p.faint()));UIManager.put("MenuItem.acceleratorSelectionForeground",c(p.accentHover()));
        UIManager.put("Separator.foreground",c(p.line()));
        UIManager.put("Button.background",c(p.raised()));UIManager.put("Button.foreground",c(p.ink()));UIManager.put("Button.borderColor",c(p.border()));
        UIManager.put("Button.hoverBackground",c(p.hover()));UIManager.put("Button.pressedBackground",c(p.line()));UIManager.put("Button.focusedBorderColor",c(p.accent()));
        UIManager.put("Button.toolbar.hoverBackground",c(p.hover()));UIManager.put("Button.toolbar.pressedBackground",c(p.line()));
        UIManager.put("Button.default.background",c(p.accent()));UIManager.put("Button.default.foreground",c(p.onAccent()));UIManager.put("Button.default.borderColor",c(p.accent()));
        UIManager.put("Button.default.hoverBackground",c(p.accentHover()));UIManager.put("Button.default.pressedBackground",c(p.accentPressed()));UIManager.put("Button.default.focusedBackground",c(p.accent()));UIManager.put("Button.default.boldText",true);
        UIManager.put("Button.margin",new Insets(6,13,6,13));
        UIManager.put("ToggleButton.selectedBackground",c(p.tint()));UIManager.put("ToggleButton.selectedForeground",c(p.ink()));
        for(String text:new String[]{"TextField","TextArea","TextPane","EditorPane","PasswordField","FormattedTextField","ComboBox","Spinner"}){
            UIManager.put(text+".background",c(p.raised()));UIManager.put(text+".selectionBackground",c(p.selection()));UIManager.put(text+".selectionForeground",c(p.ink()));
        }
        UIManager.put("TextField.foreground",c(p.ink()));UIManager.put("TextArea.foreground",c(p.ink()));UIManager.put("TextComponent.selectionBackground",c(p.selection()));
        UIManager.put("TextField.placeholderForeground",c(p.faint()));UIManager.put("ComboBox.buttonBackground",c(p.raised()));UIManager.put("ComboBox.popupBackground",c(p.surface()));
        UIManager.put("CheckBox.icon.checkmarkColor",c(p.onAccent()));UIManager.put("CheckBox.icon.selectedBackground",c(p.accent()));UIManager.put("CheckBox.icon.selectedBorderColor",c(p.accent()));
        UIManager.put("CheckBox.icon.focusedBorderColor",c(p.accent()));UIManager.put("RadioButton.icon.centerColor",c(p.onAccent()));
        UIManager.put("SplitPane.dividerSize",6);UIManager.put("SplitPane.background",c(p.canvas()));UIManager.put("SplitPaneDivider.gripColor",c(p.faint()));UIManager.put("SplitPaneDivider.draggingColor",c(p.accent()));
        UIManager.put("ScrollPane.background",c(p.surface()));UIManager.put("ScrollPane.borderColor",c(p.line()));UIManager.put("Viewport.background",c(p.surface()));
        UIManager.put("ScrollBar.width",10);UIManager.put("ScrollBar.thumbArc",999);UIManager.put("ScrollBar.thumbInsets",new Insets(2,2,2,2));UIManager.put("ScrollBar.showButtons",false);
        UIManager.put("ScrollBar.thumb",c(p.border()));UIManager.put("ScrollBar.hoverThumbColor",c(p.faint()));UIManager.put("ScrollBar.track",c(p.canvas()));UIManager.put("ScrollBar.hoverTrackColor",c(p.canvas()));
        UIManager.put("List.background",c(p.surface()));UIManager.put("List.foreground",c(p.ink()));
        UIManager.put("List.selectionBackground",c(p.tint()));UIManager.put("List.selectionForeground",c(p.ink()));UIManager.put("List.selectionInactiveBackground",c(p.hover()));UIManager.put("List.selectionInactiveForeground",c(p.ink()));
        UIManager.put("Table.background",c(p.surface()));UIManager.put("Table.foreground",c(p.ink()));
        UIManager.put("Table.selectionBackground",c(p.tint()));UIManager.put("Table.selectionForeground",c(p.ink()));UIManager.put("Table.selectionInactiveBackground",c(p.hover()));UIManager.put("Table.selectionInactiveForeground",c(p.ink()));
        UIManager.put("Table.rowHeight",30);UIManager.put("Table.showHorizontalLines",true);UIManager.put("Table.showVerticalLines",false);UIManager.put("Table.gridColor",c(p.line()));UIManager.put("Table.intercellSpacing",new Dimension(0,1));
        UIManager.put("TableHeader.background",c(p.canvas()));UIManager.put("TableHeader.foreground",c(p.muted()));UIManager.put("TableHeader.separatorColor",c(p.line()));UIManager.put("TableHeader.bottomSeparatorColor",c(p.line()));UIManager.put("TableHeader.height",32);
        UIManager.put("ToolTip.background",c(p.tooltip()));UIManager.put("ToolTip.foreground",c(p.tooltipText()));UIManager.put("ToolTip.border",BorderFactory.createEmptyBorder(6,9,6,9));
        UIManager.put("OptionPane.buttonMinimumWidth",88);UIManager.put("OptionPane.messageAreaBorder",BorderFactory.createEmptyBorder(4,4,8,4));
        UIManager.put("TitlePane.background",c(p.surface()));UIManager.put("TitlePane.inactiveBackground",c(p.surface()));UIManager.put("TitlePane.foreground",c(p.ink()));
        UIManager.put("ProgressBar.foreground",c(p.accent()));UIManager.put("ProgressBar.background",c(p.line()));
        UIManager.put("TitledBorder.titleColor",c(p.muted()));UIManager.put("TitledBorder.border",BorderFactory.createLineBorder(new Color(p.line())));
    }

    private static volatile long checkedAt;private static volatile boolean systemDark;
    /** Whether the operating system asks apps for dark windows. Never throws; light when unknown. */
    static boolean systemDark(){
        String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);
        String[] command=os.startsWith("windows")?new String[]{"reg","query","HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize","/v","AppsUseLightTheme"}:
                os.contains("mac")?new String[]{"defaults","read","-g","AppleInterfaceStyle"}:new String[]{"gsettings","get","org.gnome.desktop.interface","color-scheme"};
        try{
            Process process=new ProcessBuilder(command).redirectErrorStream(true).start();
            if(!process.waitFor(1500,TimeUnit.MILLISECONDS)){process.destroyForcibly();return systemDark;}
            String out=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            systemDark=os.startsWith("windows")?out.matches("(?s).*apps?uselighttheme\\s+reg_dword\\s+0x0\\b.*"):out.contains("dark");
        }catch(Exception e){systemDark=false;}
        checkedAt=System.nanoTime();
        return systemDark;
    }
    /** Re-reads the system setting at most every few seconds, off the event thread. */
    static void followSystem(java.util.concurrent.Executor executor){
        if(mode!=Mode.SYSTEM||System.nanoTime()-checkedAt<TimeUnit.SECONDS.toNanos(3))return;
        checkedAt=System.nanoTime();
        executor.execute(()->{Palette wanted=systemDark()?DARK:LIGHT;if(wanted!=current)SwingUtilities.invokeLater(()->{if(mode==Mode.SYSTEM)apply(Mode.SYSTEM,wanted);});});
    }
}
