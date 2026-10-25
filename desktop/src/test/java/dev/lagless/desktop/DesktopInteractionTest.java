package dev.lagless.desktop;

import dev.lagless.ScreenModel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.datatransfer.*;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.event.*;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopInteractionTest {
    @TempDir Path dir;
    static void key(JTextArea editor,String stroke){Object binding=editor.getInputMap().get(KeyStroke.getKeyStroke(stroke));assertNotNull(binding);editor.getActionMap().get(binding).actionPerformed(new ActionEvent(editor,0,"test"));}
    @Test void focusedClipboardShortcutsSupportUnicodeAndMultiline()throws Exception{
        SwingUtilities.invokeAndWait(()->{JTextArea editor=DesktopApp.textArea();Clipboard clipboard=new Clipboard("isolated test");ClipboardActions.install(editor,message->fail(message),()->clipboard);editor.setText("hello 中文");editor.select(6,8);key(editor,"ctrl C");assertEquals("hello 中文",editor.getText());try{assertEquals("中文",clipboard.getData(DataFlavor.stringFlavor));}catch(Exception e){throw new RuntimeException(e);}key(editor,"ctrl X");assertEquals("hello ",editor.getText());clipboard.setContents(new StringSelection("甲\r\n乙"),null);key(editor,"ctrl V");assertEquals("hello 甲\n乙",editor.getText());key(editor,"ctrl A");assertEquals(editor.getText(),editor.getSelectedText());});
    }
    @Test void assistantComposerPasteShortcutUsesClipboardImageAsAttachment()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            JTextArea editor=DesktopApp.textArea();Clipboard clipboard=new Clipboard("isolated image");
            BufferedImage image=new BufferedImage(8,6,BufferedImage.TYPE_INT_ARGB);
            clipboard.setContents(new Transferable(){
                public DataFlavor[] getTransferDataFlavors(){return new DataFlavor[]{DataFlavor.imageFlavor};}
                public boolean isDataFlavorSupported(DataFlavor flavor){return DataFlavor.imageFlavor.equals(flavor);}
                public Object getTransferData(DataFlavor flavor){return image;}
            },null);
            Image[] attached={null};ClipboardActions.install(editor,message->fail(message),()->clipboard,pasted->attached[0]=pasted);
            editor.setText("question");key(editor,"ctrl V");assertSame(image,attached[0]);assertEquals("question",editor.getText());
        });
    }
    static Object field(Object owner,String name)throws Exception{var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    static java.awt.event.InputMethodEvent ime(java.awt.Component source,String text,int committed){
        return new java.awt.event.InputMethodEvent(source,java.awt.event.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,text==null?null:new java.text.AttributedString(text).getIterator(),committed,null,null);
    }
    @Test void imePreeditOverlayNeverOutlivesTheComposition()throws Exception{
        SwingUtilities.invokeAndWait(()->{try{
            ImeTextArea area=(ImeTextArea)DesktopApp.textArea();Object support=field(area,"ime");
            area.dispatchEvent(ime(area,"ni",0));assertEquals("ni",field(support,"preedit"));
            // Ending or cancelling a composition sends an event without text.
            area.dispatchEvent(ime(area,null,0));assertEquals("",field(support,"preedit"));
            area.dispatchEvent(ime(area,"hao",0));area.dispatchEvent(ime(area,"好",1));assertEquals("",field(support,"preedit"));assertEquals("好",area.getText());
            area.dispatchEvent(ime(area,"ma",0));area.setText("");assertEquals("",field(support,"preedit"));
            ImeTextField text=(ImeTextField)DesktopApp.field("");Object fieldSupport=field(text,"ime");
            text.dispatchEvent(ime(text,"zh",0));assertEquals("zh",field(fieldSupport,"preedit"));
            for(var listener:text.getFocusListeners())listener.focusLost(new java.awt.event.FocusEvent(text,java.awt.event.FocusEvent.FOCUS_LOST));assertEquals("",field(fieldSupport,"preedit"));
        }catch(Exception e){throw new RuntimeException(e);}});
    }
    @Test void logTailKeepsExactlyTheLatestLinesAcrossPages()throws Exception{
        Path log=dir.resolve("big.log");StringBuilder content=new StringBuilder();for(int i=0;i<20_000;i++)content.append("第 ").append(i).append(" 行 ").append("x".repeat(20)).append('\n');Files.writeString(log,content);
        String tail=AssistantChatPanel.tailLines(log,15_000);String[] lines=tail.split("\n",-1);
        assertEquals(15_000,lines.length);assertEquals("第 5000 行 "+"x".repeat(20),lines[0]);assertEquals("第 19999 行 "+"x".repeat(20),lines[lines.length-1]);
        assertEquals("第 19999 行 "+"x".repeat(20),AssistantChatPanel.tailLines(log,1));
        assertEquals(20_000,AssistantChatPanel.tailLines(log,100_000).split("\n",-1).length);
    }
    @Test void assistantShowsAnEditableDefaultPromptOnlyWhileContextIsSelected()throws Exception{
        DesktopApp[] app={null};
        try{SwingUtilities.invokeAndWait(()->{try{
            app[0]=new DesktopApp(dir);SessionPane pane=new SessionPane(app[0],new Profile("ai","ai","example.invalid","user",22,"","",true,false));app[0].sessions.add(pane);
            Object chat=field(pane,"assistantChat");JTextArea composer=(JTextArea)field(chat,"composer");
            @SuppressWarnings("unchecked") JComboBox<String> action=(JComboBox<String>)field(chat,"action"),context=(JComboBox<String>)field(chat,"context");
            JLabel status=(JLabel)field(chat,"status");JButton send=(JButton)field(chat,"send");
            assertEquals("",composer.getText());send.doClick();assertFalse(status.getText().isBlank());assertEquals("",composer.getText());
            action.setSelectedIndex(3);context.setSelectedIndex(1);
            String translate=composer.getText();assertFalse(translate.isBlank());
            action.setSelectedIndex(4);assertNotEquals(translate,composer.getText());assertFalse(composer.getText().isBlank());
            context.setSelectedIndex(0);assertEquals("",composer.getText());
            context.setSelectedIndex(2);composer.setText("my own words");action.setSelectedIndex(0);context.setSelectedIndex(0);assertEquals("my own words",composer.getText());
            JCheckBox wrap=(JCheckBox)field(chat,"wrapReplies");assertTrue(wrap.isSelected());
            @SuppressWarnings("unchecked") JComboBox<Object> chats=(JComboBox<Object>)field(chat,"conversations");JEditorPane reply=(JEditorPane)field(chats.getSelectedItem(),"transcript");
            assertTrue(reply.getScrollableTracksViewportWidth());wrap.doClick();assertFalse(reply.getScrollableTracksViewportWidth());assertEquals("false",app[0].preferences.get("assistant-wrap","true"));wrap.doClick();assertTrue(reply.getScrollableTracksViewportWidth());
        }catch(Exception e){throw new RuntimeException(e);}});}finally{if(app[0]!=null){SwingUtilities.invokeAndWait(app[0]::close);assertTrue(app[0].awaitShutdown(System.nanoTime()+TimeUnit.SECONDS.toNanos(10)));}}
    }
    @Test void editorWrappingSharesTextAndDoesNotCreateLogicalNewlines()throws Exception{
        DesktopApp[] app={null};
        try{SwingUtilities.invokeAndWait(()->{try{app[0]=new DesktopApp(dir);SessionPane pane=new SessionPane(app[0],new Profile("wrap","wrap","example.invalid","user",22,"","",true,false));app[0].sessions.add(pane);pane.draft.setText("echo "+"word ".repeat(100));String original=pane.draft.getText();app[0].setEditorWrap(true);assertTrue(pane.draft.getLineWrap());assertTrue(pane.inlineDraft.getLineWrap());assertEquals(original,SessionPane.currentCommand(pane.draft));assertEquals(1,pane.draft.getLineCount());app[0].setEditorWrap(false);assertFalse(pane.draft.getLineWrap());assertEquals(original,pane.inlineDraft.getText());}catch(Exception e){throw new RuntimeException(e);}});}finally{if(app[0]!=null){SwingUtilities.invokeAndWait(app[0]::close);assertTrue(app[0].awaitShutdown(System.nanoTime()+TimeUnit.SECONDS.toNanos(10)),"desktop shutdown must finish before temporary home cleanup");}}
    }
    @Test void diskHistoryHasNoSmallLineCapAndRetainsAnsiUnicode()throws Exception{
        try(TerminalHistory history=new TerminalHistory(dir,"test")){for(int batch=0;batch<12;batch++){for(int i=0;i<1000;i++)assertTrue(history.append(new TerminalHistory.Row(80,"\u001b[32m行-"+(batch*1000+i)+"\u001b[0m")));history.force();}assertEquals(12000,history.count());assertEquals(0,history.omitted());assertTrue(history.read(0).model().rowText(0).contains("行-0"));assertTrue(history.read(11999).model().rowText(0).contains("行-11999"));assertEquals("\u001b[32m",history.read(0).model().penAt(0,0));assertTrue(Files.size(history.path())>100000);}
    }
    @Test void terminalHistoryKeepsAutomaticWrapMarks()throws Exception{
        try(TerminalHistory history=new TerminalHistory(dir,"wrap")){
            assertTrue(history.append(new TerminalHistory.Row(10,"0123456789",true)));assertTrue(history.append(new TerminalHistory.Row(10,"abc")));history.force();
            assertTrue(history.read(0).wrapped());assertFalse(history.read(1).wrapped());assertEquals(10,history.read(0).columns());
            assertEquals("0123456789abc\n",history.plainText(0,1000));
        }
    }
    @Test void ctrlCCopiesSelectionJoiningOnlyAutomaticWraps()throws Exception{
        java.awt.datatransfer.Clipboard clipboard=new java.awt.datatransfer.Clipboard("copy fixture");SwingTerminal[] view={null};
        SwingUtilities.invokeAndWait(()->{view[0]=new SwingTerminal();view[0].setClipboard(()->clipboard);});
        try{
            java.lang.reflect.Field screenField=SwingTerminal.class.getDeclaredField("screen");screenField.setAccessible(true);
            int columns=((dev.lagless.ScreenModel)screenField.get(view[0])).cols();
            String full="F".repeat(columns),wrapped="W".repeat(columns)+"tail";
            SwingUtilities.invokeAndWait(()->view[0].display(full+"\r\n"+wrapped+"\r\nend"));
            java.lang.reflect.Field start=SwingTerminal.class.getDeclaredField("selectionStart"),end=SwingTerminal.class.getDeclaredField("selectionEnd");start.setAccessible(true);end.setAccessible(true);
            start.set(view[0],new java.awt.Point(0,0));end.set(view[0],new java.awt.Point(2,3));
            assertTrue(view[0].hasSelection());
            java.awt.event.KeyEvent ctrlC=new java.awt.event.KeyEvent(view[0],java.awt.event.KeyEvent.KEY_PRESSED,System.currentTimeMillis(),java.awt.event.InputEvent.CTRL_DOWN_MASK,java.awt.event.KeyEvent.VK_C,'\u0003');
            SwingUtilities.invokeAndWait(()->{for(java.awt.event.KeyListener listener:view[0].getKeyListeners())listener.keyPressed(ctrlC);});
            assertTrue(ctrlC.isConsumed());
            assertEquals(full+"\n"+wrapped+"\nend",clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor));
            assertFalse(view[0].hasSelection());
        }finally{SwingUtilities.invokeAndWait(view[0]::close);}
    }
    @Test void scrollingPausesFollowAndTerminalHistoryExcludesFullScreenRedraw()throws Exception{
        SwingTerminal[] view={null};TerminalHistory history=new TerminalHistory(dir,"screen");SwingUtilities.invokeAndWait(()->{view[0]=new SwingTerminal();view[0].setSize(900,400);view[0].setHistory(history);view[0].display("line-zero\r\n"+"stream\r\n".repeat(100));view[0].showHistory(0);});
        try{await(()->view[0].displayedText().contains("line-zero"));long start=view[0].viewportStart();SwingUtilities.invokeAndWait(()->view[0].display("new-output\r\n".repeat(20)));assertFalse(view[0].followingLive());assertEquals(start,view[0].viewportStart());SwingUtilities.invokeAndWait(()->view[0].setLocalEditing(false));long old=history.count();SwingUtilities.invokeAndWait(()->view[0].display("\u001b[?1049h\u001b[HClaude frame\r\nanswer before redraw"));Thread.sleep(280);SwingUtilities.invokeAndWait(()->view[0].display("\u001b[2J"));history.force();assertEquals(old,history.count());for(long i=0;i<history.count();i++)assertFalse(history.read(i).model().rowText(0).contains("Claude frame"));SwingUtilities.invokeAndWait(view[0]::followLatest);assertTrue(view[0].followingLive());}finally{SwingUtilities.invokeAndWait(view[0]::close);}
    }
    @Test void ordinaryTerminalHistoryKeepsRepeatedRowsAndCurrentScreen()throws Exception{
        TerminalHistory history=new TerminalHistory(dir,"ordinary");SwingTerminal[] view={null};
        try{
            SwingUtilities.invokeAndWait(()->{view[0]=new SwingTerminal();view[0].setSize(900,400);view[0].setHistory(history);view[0].display("same output\r\n".repeat(90)+"current line");});
            view[0].flushHistory();
            long repeated=0;for(long i=0;i<history.stableCount();i++)if(history.read(i).model().rowText(0).contains("same output"))repeated++;
            assertTrue(repeated>1,"separate ordinary output lines must not be deduplicated");
            assertTrue(LogViewer.readPage(history.path(),-1).text().contains("current line"));
        }finally{if(view[0]!=null)SwingUtilities.invokeAndWait(view[0]::close);else history.close();assertTrue(history.awaitClosed(5,TimeUnit.SECONDS));}
    }
    @Test void terminalSelectionSurvivesScrollAndCopiesAcrossHistoryRows()throws Exception{
        TerminalHistory history=new TerminalHistory(dir,"selection");for(int i=0;i<80;i++)history.append(new TerminalHistory.Row(80,String.format("row-%03d",i)));history.force();
        SwingTerminal[] view={null};SwingUtilities.invokeAndWait(()->{view[0]=new SwingTerminal();view[0].setSize(900,400);view[0].setHistory(history);view[0].showHistory(5);});
        try{
            await(()->view[0].displayedText().startsWith("row-005"));SwingTerminal terminal=view[0];
            SwingUtilities.invokeAndWait(()->{
                var metrics=terminal.getFontMetrics(terminal.getFont());int cellWidth=metrics.charWidth('M');
                int cellHeight=metrics.getHeight()+Math.max(2,metrics.getMaxAscent()-metrics.getAscent()+2);
                terminal.dispatchEvent(new MouseEvent(terminal,MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),InputEvent.BUTTON1_DOWN_MASK,1,1,1,false,MouseEvent.BUTTON1));
                terminal.dispatchEvent(new MouseEvent(terminal,MouseEvent.MOUSE_DRAGGED,System.currentTimeMillis(),InputEvent.BUTTON1_DOWN_MASK,cellWidth*5+1,cellHeight+1,0,false,MouseEvent.NOBUTTON));
                terminal.dispatchEvent(new MouseWheelEvent(terminal,MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,200,200,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,1));
            });
            assertTrue(terminal.viewportStart()>5,"wheel scroll changes the displayed page");
            var selected=terminal.getSelectedText(null);StringBuilder copied=new StringBuilder();for(char c=selected.first();c!=java.text.CharacterIterator.DONE;c=selected.next())copied.append(c);
            assertEquals("row-005\nrow-00",copied.toString());
        }finally{SwingUtilities.invokeAndWait(view[0]::close);history.close();}
    }
    @Test void cornerHandleOnlyChangesHeightAndOneLineHasNoScrollbars()throws Exception{
        DesktopApp[] app={null};
        try{SwingUtilities.invokeAndWait(()->{try{
            app[0]=new DesktopApp(dir);
            SessionPane pane=new SessionPane(app[0],new Profile("resize","resize","example.invalid","user",22,"","",true,false));app[0].sessions.add(pane);pane.setSize(1000,650);layout(pane);
            pane.inlineDraft.setText("keep local text");int width=pane.inputArea.getWidth();assertEquals(44,pane.inputArea.editorHeight());assertFalse(pane.inlineScroll.getVerticalScrollBar().isVisible());assertFalse(pane.inlineScroll.getHorizontalScrollBar().isVisible());
            NativeInputVerification.drag(pane.inputArea,100);layout(pane);assertEquals(144,pane.inputArea.editorHeight());assertEquals(width,pane.inputArea.getWidth());assertEquals("keep local text",pane.inlineDraft.getText());
            NativeInputVerification.drag(pane.inputArea,-2000);layout(pane);assertEquals(44,pane.inputArea.editorHeight());assertEquals("keep local text",pane.inlineDraft.getText());assertFalse(pane.inlineScroll.getVerticalScrollBar().isVisible());assertFalse(pane.inlineScroll.getHorizontalScrollBar().isVisible());assertEquals(22,pane.inputArea.resizeHandle.getWidth());assertEquals(pane.inputArea.editorSurface.getWidth(),pane.inputArea.resizeHandle.getX()+pane.inputArea.resizeHandle.getWidth());assertEquals(pane.inputArea.editorSurface.getHeight(),pane.inputArea.resizeHandle.getY()+pane.inputArea.resizeHandle.getHeight());
        }catch(Exception e){throw new RuntimeException(e);}});}finally{if(app[0]!=null){SwingUtilities.invokeAndWait(app[0]::close);assertTrue(app[0].awaitShutdown(System.nanoTime()+TimeUnit.SECONDS.toNanos(10)),"desktop shutdown must finish before temporary home cleanup");}}
    }
    @Test void actualScrollbarChangesPaintedOutputDuringContinuousUpdates()throws Exception{
        TerminalHistory history=new TerminalHistory(dir,"paint");for(int n=0;n<8000;n++)history.append(new TerminalHistory.Row(100,"history-row-"+n));history.force();SwingTerminal[] view={null};
        SwingUtilities.invokeAndWait(()->{view[0]=new SwingTerminal();view[0].setSize(900,500);view[0].setHistory(history);view[0].historyScrollBar().setMaximum(8000+30);view[0].historyScrollBar().setVisibleAmount(30);view[0].showHistory(0);});
        javax.swing.Timer[] stream={null};
        try{await(()->view[0].displayedText().startsWith("history-row-0\n"));java.util.concurrent.atomic.AtomicInteger frames=new java.util.concurrent.atomic.AtomicInteger();
            SwingUtilities.invokeAndWait(()->{stream[0]=new javax.swing.Timer(5,e->{view[0].display("live-"+frames.incrementAndGet()+"\r\n".repeat(50));});stream[0].start();for(int n=0;n<100;n++)view[0].historyScrollBar().setValue(2000+n);view[0].historyScrollBar().setValue(4000);});
            await(()->view[0].displayedText().startsWith("history-row-4000\n"));Thread.sleep(150);assertFalse(view[0].followingLive());assertTrue(view[0].displayedText().startsWith("history-row-4000\n"));
            SwingUtilities.invokeAndWait(()->{var image=new java.awt.image.BufferedImage(900,500,java.awt.image.BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();view[0].printAll(graphics);graphics.dispose();long ink=0;for(int y=35;y<100;y++)for(int x=0;x<350;x++)if((image.getRGB(x,y)&0xffffff)!=0x0d1424)ink++;assertTrue(ink>100,"historical text is actually painted");});
        }finally{SwingUtilities.invokeAndWait(()->{if(stream[0]!=null)stream[0].stop();view[0].close();});}
    }
    private static void layout(java.awt.Container root){root.doLayout();for(java.awt.Component child:root.getComponents())if(child instanceof java.awt.Container nested)layout(nested);}
    private static void await(java.util.function.BooleanSupplier test)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(!test.getAsBoolean()){if(System.nanoTime()>end)fail("history page timed out");Thread.sleep(10);}}
}
