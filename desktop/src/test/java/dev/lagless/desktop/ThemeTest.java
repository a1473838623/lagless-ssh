package dev.lagless.desktop;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ThemeTest {
    @TempDir Path dir;

    @AfterEach void restoreLight()throws Exception{SwingUtilities.invokeAndWait(()->Theme.apply(Theme.Mode.LIGHT));}

    @Test void tokensAndLookAndFeelFollowTheSwitch()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            Theme.apply(Theme.Mode.LIGHT);
            JButton primary=DesktopApp.primary(new JButton("Go"));JLabel muted=new JLabel("hint");muted.setForeground(Theme.MUTED);
            assertEquals(Theme.LIGHT.accent(),primary.getBackground().getRGB()&0xffffff);
            Theme.apply(Theme.Mode.DARK);SwingUtilities.updateComponentTreeUI(primary);
            assertTrue(UIManager.getLookAndFeel() instanceof com.formdev.flatlaf.FlatDarkLaf);
            assertEquals(Theme.DARK.accent(),primary.getBackground().getRGB()&0xffffff,"FlatLaf styles resolve $Lagless.* again");
            assertEquals(Theme.DARK.muted(),muted.getForeground().getRGB()&0xffffff,"an explicitly set token changes without rebuilding");
            assertEquals(Theme.DARK.canvas(),UIManager.getColor("Panel.background").getRGB()&0xffffff);
            assertEquals("#9a83f5",Theme.hex(Theme.ACCENT));
        });
    }
    @Test void savedModeComesFromPreferences()throws Exception{
        assertEquals(Theme.Mode.DARK,Theme.saved(dir));
        new DesktopPreferences(dir).set(Theme.KEY,"dark");assertEquals(Theme.Mode.DARK,Theme.saved(dir));
        assertEquals(Theme.Mode.SYSTEM,Theme.Mode.parse("nonsense"));
    }
    @Test void selectedTabHasNoAccentRule()throws Exception{
        for(Theme.Mode mode:new Theme.Mode[]{Theme.Mode.LIGHT,Theme.Mode.DARK})SwingUtilities.invokeAndWait(()->{
            Theme.apply(mode);
            JTabbedPane tabs=new JTabbedPane();tabs.addTab("one",new JPanel());
            SessionTabHeader header=new SessionTabHeader(tabs,"Production",()->{});tabs.setTabComponentAt(0,header);header.update(true);
            header.setSize(header.getPreferredSize());header.doLayout();
            BufferedImage image=new BufferedImage(header.getWidth(),header.getHeight(),BufferedImage.TYPE_INT_ARGB);
            Graphics2D g=image.createGraphics();g.setColor(Theme.CANVAS);g.fillRect(0,0,image.getWidth(),image.getHeight());header.paint(g);g.dispose();
            int accent=Theme.ACCENT.getRGB()&0xffffff,accentPixels=0;
            for(int y=image.getHeight()-6;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if((image.getRGB(x,y)&0xffffff)==accent)accentPixels++;
            assertEquals(0,accentPixels,mode+": no accent line along the bottom of the selected tab");
            assertEquals(Theme.palette().surface(),image.getRGB(image.getWidth()/2,4)&0xffffff,mode+": no accent line along the top of the selected tab");
            assertEquals(Theme.palette().surface(),image.getRGB(4,image.getHeight()/2)&0xffffff,mode+": selected tab merges with the content surface");
        });
    }
    @Test void menuSwitchSavesTheChoiceAndUpdatesTheToggle()throws Exception{
        DesktopApp[] app={null};
        try{SwingUtilities.invokeAndWait(()->{try{
            Theme.apply(Theme.Mode.LIGHT);app[0]=new DesktopApp(dir);JPanel root=app[0].content(null);
            assertEquals(0,countBrandLabels(root),"the OS title bar is the only top-left Lagless brand");
            JButton toggle=find(root,JButton.class,"theme-toggle");assertNotNull(toggle);assertSame(UiIcons.MOON,toggle.getIcon());
            toggle.doClick();
            assertTrue(Theme.dark());assertEquals("dark",app[0].preferences.get(Theme.KEY,""));assertSame(UiIcons.SUN,toggle.getIcon());
            app[0].setTheme(Theme.Mode.SYSTEM);assertEquals("system",app[0].preferences.get(Theme.KEY,""));
        }catch(Exception e){throw new RuntimeException(e);}});}
        finally{if(app[0]!=null){SwingUtilities.invokeAndWait(app[0]::close);assertTrue(app[0].awaitShutdown(System.nanoTime()+TimeUnit.SECONDS.toNanos(10)));}}
    }
    static <T extends Component> T find(Container root,Class<T> type,String name){
        for(Component child:root.getComponents()){
            if(type.isInstance(child)&&name.equals(child.getName()))return type.cast(child);
            if(child instanceof JMenu menu){T found=find(menu.getPopupMenu(),type,name);if(found!=null)return found;}
            if(child instanceof Container container){T found=find(container,type,name);if(found!=null)return found;}
        }
        return null;
    }
    private static int countBrandLabels(Container root){
        int count=0;for(Component child:root.getComponents()){
            if(child instanceof JLabel label&&"Lagless".equals(label.getText()))count++;
            if(child instanceof Container nested)count+=countBrandLabels(nested);
        }return count;
    }
    @Test void everyMenuItemKindSharesOneTextColumn()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            Theme.apply(Theme.Mode.LIGHT);
            java.awt.Insets plain=UIManager.getInsets("MenuItem.margin");
            for(String kind:new String[]{"Menu","CheckBoxMenuItem","RadioButtonMenuItem"})assertEquals(plain,UIManager.getInsets(kind+".margin"),kind);
        });
    }
    @Test void switchingAppsClosesOpenMenus()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            JPanel holder=new JPanel();JMenuBar bar=new JMenuBar();JMenu menu=new JMenu("View");menu.add(new JMenuItem("one"));bar.add(menu);holder.add(bar);
            try{
                MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{bar,menu});
                assertEquals(2,MenuSelectionManager.defaultManager().getSelectedPath().length);
                Theme.closePopups();
                assertEquals(0,MenuSelectionManager.defaultManager().getSelectedPath().length);
            }finally{MenuSelectionManager.defaultManager().clearSelectedPath();}
        });
    }
    @Test void rollbackKnowsWhichVersionItRestores()throws Exception{
        Path updates=dir.resolve(".updates");java.nio.file.Files.createDirectories(updates);
        assertNull(NativeUpdater.previousBackup(dir));
        java.nio.file.Files.write(updates.resolve("previous.zip"),new byte[0]);
        java.nio.file.Files.writeString(updates.resolve("previous.json"),"{\"version\":\"0.2.0-rc.8.14\",\"replacedBy\":\""+DesktopRuntime.version()+"\"}");
        assertEquals(new NativeUpdater.Backup("0.2.0-rc.8.14",true),NativeUpdater.previousBackup(dir));
        // The current version came from a hand-extracted ZIP: the backup is from an earlier update.
        java.nio.file.Files.writeString(updates.resolve("previous.json"),"{\"version\":\"0.2.0-rc.8.13\",\"replacedBy\":\"0.2.0-rc.8.14\"}");
        assertEquals(new NativeUpdater.Backup("0.2.0-rc.8.13",false),NativeUpdater.previousBackup(dir));
    }
}
