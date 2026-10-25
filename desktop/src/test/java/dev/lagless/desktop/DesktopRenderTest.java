package dev.lagless.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopRenderTest {
    @TempDir Path dir;
    @Test void rendersWorkbenchAndKeepsKeyboardChunksAtomic() throws Exception {
        DesktopApp[] appHolder={null};SessionPane[] paneHolder={null};
        SwingUtilities.invokeAndWait(() -> {
            DesktopApp app=null;SessionPane pane=null;
            try {
                DesktopApp.theme();app=new DesktopApp(dir);appHolder[0]=app;Profile p=new Profile("prod","Production · Tokyo","10.24.0.12","deploy",22,"","",true,false);app.accounts.addElement(p);
                app.accounts.addElement(new Profile("staging","Staging · Singapore","10.24.0.18","ops",2222,"","",true,false));
                JPanel content=app.content(null);JTabbedPane tabs=findTabs(content);pane=new SessionPane(app,p);paneHolder[0]=pane;tabs.addTab("Production · Tokyo",pane);tabs.setSelectedComponent(pane);
                pane.draft.setText("# 完全在本地编辑，网络中断也能保留\ngit status\nsystemctl status nginx\n");
                pane.view.display("\u001b[32mConnected to Production · Tokyo\u001b[0m\r\n\r\ndeploy@tokyo:~$ git status\r\nOn branch main\r\nYour branch is up to date.\r\n\r\n\u001b[36mLocal editing ready · RTT does not affect typing\u001b[0m\r\ndeploy@tokyo:~$ ");
                content.setSize(1440,900);layout(content);layout(content);layout(content);
                assertNotNull(findList((Container)tabs.getComponentAt(0)),"start page should show saved connections");
                assertNull(findList(pane),"connection list should not occupy the terminal session");assertSame(pane.draft.getDocument(),pane.inlineDraft.getDocument());assertFalse(pane.tools.isVisible());pane.setEditorPlacement("right");assertTrue(pane.tools.isVisible());assertEquals("本地命令",pane.tools.getTitleAt(0));assertTrue(pane.mirror.getLineWrap());
                pane.setEditorPlacement("right");layout(content);layout(content);assertTrue(pane.tools.getWidth()>100,"right editor has usable width: "+pane.tools.getBounds());
                BufferedImage image=new BufferedImage(1440,900,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();content.printAll(g);g.dispose();
                Files.createDirectories(Path.of("target"));javax.imageio.ImageIO.write(image,"png",Path.of("target/desktop-preview.png").toFile());
                assertTrue(pane.view.screenText().contains("git status"));assertEquals(1440,image.getWidth());
            } catch(Exception e){throw new RuntimeException(e);} finally {if(pane!=null)pane.close();if(app!=null){app.close();}}
        });
        awaitShutdown(appHolder[0],paneHolder[0]);
        SwingTerminal.KeyInput input=new SwingTerminal.KeyInput();
        assertFalse(input.offer("x".repeat(65537)));assertEquals(0,input.available());
        assertTrue(input.offer("中文\r"));assertEquals("中文\r",new String(input.readNBytes(7),java.nio.charset.StandardCharsets.UTF_8));input.close();assertEquals(-1,input.read());
    }
    private static JTabbedPane findTabs(Container container){for(Component c:container.getComponents()){if(c instanceof JTabbedPane t)return t;if(c instanceof Container nested){JTabbedPane t=findTabs(nested);if(t!=null)return t;}}return null;}
    private static JList<?> findList(Container container){for(Component c:container.getComponents()){if(c instanceof JList<?> t)return t;if(c instanceof Container nested){JList<?> t=findList(nested);if(t!=null)return t;}}return null;}
    private static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container nested)layout(nested);}
    @Test void decodesAnsiColorsAndUnicodeAcrossWrites() throws Exception {
        Color[] colors=SwingTerminal.colors("\u001b[38;2;12;34;56m\u001b[48;5;196m");assertEquals(new Color(12,34,56),colors[0]);assertEquals(Color.RED,colors[1]);
        SwingTerminal[] view={null};SwingUtilities.invokeAndWait(() -> {view[0]=new SwingTerminal();view[0].setSize(800,400);});
        try(var terminal=view[0].attach()){
            byte[] bytes="你好，世界".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for(byte b:bytes)terminal.output().write(b);terminal.output().flush();long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);while(!view[0].screenText().contains("你好，世界")&&System.nanoTime()<deadline)Thread.sleep(10);assertTrue(view[0].screenText().contains("你好，世界"));
        }finally{SwingUtilities.invokeAndWait(view[0]::close);}
    }
    @Test void viewCloseButtonsRetainContentsAndRestoreLocalInput()throws Exception{
        DesktopApp[] appHolder={null};SessionPane[] paneHolder={null};
        SwingUtilities.invokeAndWait(()->{try(DesktopApp app=new DesktopApp(dir)){
            appHolder[0]=app;SessionPane pane=new SessionPane(app,new Profile("view","view","example.invalid","user",22,"","",true,false));paneHolder[0]=pane;app.sessions.add(pane);
            pane.draft.setText("keep draft");pane.mirror.setText("keep mirror");pane.assistant.setText("keep assistant");
            for(String name:new String[]{"mirror","assistant"}){pane.showTool(name);Component body=pane.tools.getSelectedComponent();Container header=(Container)pane.tools.getTabComponentAt(pane.tools.getSelectedIndex());Arrays.stream(header.getComponents()).filter(JButton.class::isInstance).map(JButton.class::cast).findFirst().orElseThrow().doClick();assertEquals(-1,pane.tools.indexOfComponent(body));assertFalse(pane.tools.isVisible());pane.showTool(name);assertSame(body,pane.tools.getSelectedComponent());pane.closeTool(body);}
            assertEquals("keep mirror",pane.mirror.getText());assertEquals("keep assistant",pane.assistant.getText());assertEquals("keep draft",pane.draft.getText());assertSame(pane.draft.getDocument(),pane.inlineDraft.getDocument());
            pane.setEditorPlacement("right");Container header=(Container)pane.tools.getTabComponentAt(0);Arrays.stream(header.getComponents()).filter(JButton.class::isInstance).map(JButton.class::cast).findFirst().orElseThrow().doClick();assertTrue(pane.inlineDraft.isVisible());assertTrue(pane.inlineDraft.getParent().getParent().getParent().isVisible());
        }catch(Exception e){throw new RuntimeException(e);}});awaitShutdown(appHolder[0],paneHolder[0]);
    }
    private static void awaitShutdown(DesktopApp app,SessionPane pane)throws InterruptedException{long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);if(pane!=null)pane.awaitClosed(deadline);if(app!=null)assertTrue(app.awaitShutdown(deadline),"desktop shutdown must finish before temporary home cleanup");}
    @Test void vectorFileIconsRenderAtBothScalesAndCursorIsASlimBar()throws Exception{
        Rectangle cursor=SwingTerminal.cursorBounds(3,2,9,20,15,11);assertEquals(2,cursor.width);assertEquals(17,cursor.height);assertEquals(41,cursor.y);assertEquals(49.5,cursor.getCenterY(),"caret is centered on the visible capital ink");
        BufferedImage preview=new BufferedImage(280,64,BufferedImage.TYPE_INT_ARGB);Graphics2D sample=preview.createGraphics();sample.setColor(Color.WHITE);sample.fillRect(0,0,280,64);
        int position=0;for(boolean folder:new boolean[]{true,false})for(boolean link:new boolean[]{false,true}){
            JLabel label=TransferDialog.label("Documents",false,folder,link);assertEquals("Documents",label.getText());assertNotNull(label.getIcon());
            for(int scale:new int[]{1,2}){BufferedImage image=new BufferedImage(18*scale,18*scale,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();g.scale(scale,scale);label.getIcon().paintIcon(label,g,0,0);g.dispose();long visible=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if(image.getRGB(x,y)>>>24!=0)visible++;assertTrue(visible>60*scale*scale,"icon must contain a visible drawing without a font");sample.drawImage(image,8+position*68,8,null);}
            position++;
        }sample.dispose();javax.imageio.ImageIO.write(preview,"png",Path.of("target/file-icons.png").toFile());
    }
}
