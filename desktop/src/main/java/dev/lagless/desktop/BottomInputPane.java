package dev.lagless.desktop;

import dev.lagless.I18n;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/** A bottom editor with an explicit vertical-only handle, never a terminal divider. */
final class BottomInputPane extends JPanel {
    static final int SINGLE_LINE_HEIGHT=44;
    private final SwingTerminal terminal;
    private final JComponent terminalSurface;
    private final JComponent editor;
    private final JScrollPane scroll;
    final JLayeredPane editorSurface;
    final JComponent resizeHandle=new JComponent(){
        @Override protected void paintComponent(Graphics graphics){
            graphics.setColor(new Color(0x6b8993));
            int x=getWidth()-5,y=getHeight()-5;
            for(int n=0;n<3;n++)graphics.drawLine(x-3-n*4,y,x,y-3-n*4);
        }
    };
    private int editorHeight=SINGLE_LINE_HEIGHT,dragY,dragHeight;
    private boolean dragging;
    private int requestedHeight;
    private final Timer dragUpdates=new Timer(16,e->{if(dragging)resizeBelow(requestedHeight);});
    private Window dragWindow;private Rectangle dragBounds;private int dragTerminalHeight;
    BottomInputPane(SwingTerminal terminal,JComponent editor,JScrollPane scroll){
        super(null);this.terminal=terminal;this.editor=editor;this.scroll=scroll;this.terminalSurface=terminal.scrollable();dragUpdates.setRepeats(false);
        editorSurface=new JLayeredPane(){
            @Override public void doLayout(){scroll.setBounds(0,0,getWidth(),getHeight());resizeHandle.setBounds(Math.max(0,getWidth()-22),Math.max(0,getHeight()-18),22,18);}
            @Override public Dimension getPreferredSize(){return scroll.getPreferredSize();}
        };
        editorSurface.add(scroll,JLayeredPane.DEFAULT_LAYER);editorSurface.add(resizeHandle,JLayeredPane.PALETTE_LAYER);
        setName("terminal-input-area");add(terminalSurface);add(editor);
        resizeHandle.setName("local-editor-resize");resizeHandle.setPreferredSize(new Dimension(22,18));resizeHandle.setMinimumSize(new Dimension(22,18));
        resizeHandle.setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));resizeHandle.setFocusable(false);resizeHandle.setToolTipText(I18n.t("上下拖动调节高度 · 向下扩展窗口和编辑区，向上缩小","Drag up or down to resize · down grows the window and editor, up shrinks them"));
        MouseAdapter drag=new MouseAdapter(){
            @Override public void mousePressed(MouseEvent e){if(!SwingUtilities.isLeftMouseButton(e))return;dragging=true;dragY=e.getYOnScreen();dragHeight=editorHeight;requestedHeight=editorHeight;dragTerminalHeight=terminalSurface.getHeight();dragWindow=SwingUtilities.getWindowAncestor(BottomInputPane.this);dragBounds=dragWindow==null?null:dragWindow.getBounds();terminal.beginLayoutResize();e.consume();}
            @Override public void mouseDragged(MouseEvent e){if(!dragging)return;requestedHeight=dragHeight+e.getYOnScreen()-dragY;if(!dragUpdates.isRunning())dragUpdates.start();e.consume();}
            @Override public void mouseReleased(MouseEvent e){if(!dragging)return;finishDrag(true);e.consume();}
        };
        resizeHandle.addMouseListener(drag);resizeHandle.addMouseMotionListener(drag);
        addHierarchyListener(e->{if(dragging&&(e.getChangeFlags()&HierarchyEvent.SHOWING_CHANGED)!=0&&!isShowing())finishDrag(false);});
        updateEditor();
    }
    int editorHeight(){return editorHeight;}
    void setEditorHeight(int height){editorHeight=Math.max(SINGLE_LINE_HEIGHT,Math.min(height,Math.max(SINGLE_LINE_HEIGHT,getHeight()-100)));updateEditor();revalidate();doLayout();repaint();}
    private void resizeBelow(int requested){
        if(dragWindow==null||dragBounds==null){setEditorHeight(requested);return;}
        // A maximized frame cannot extend its lower edge without restoring and
        // moving the terminal. Keep it fixed; the user can restore it first.
        if(dragWindow instanceof Frame frame&&(frame.getExtendedState()&Frame.MAXIMIZED_VERT)!=0)return;
        Rectangle usable=dragWindow.getGraphicsConfiguration().getBounds();Insets insets=Toolkit.getDefaultToolkit().getScreenInsets(dragWindow.getGraphicsConfiguration());
        int bottom=usable.y+usable.height-insets.bottom;
        int maximum=dragHeight+Math.max(0,bottom-dragBounds.y-dragBounds.height);
        int minimum=Math.max(SINGLE_LINE_HEIGHT,dragHeight+dragWindow.getMinimumSize().height-dragBounds.height);
        int height=Math.max(minimum,Math.min(requested,maximum));int delta=height-dragHeight;
        if(editorHeight==height)return;
        editorHeight=height;updateEditor();
        dragWindow.setBounds(dragBounds.x,dragBounds.y,dragBounds.width,dragBounds.height+delta);
        // Window validation can run while its old height is still observable.
        // Our layout reserves the original terminal bounds throughout the drag,
        // so neither its heavyweight canvas nor the SSH grid gets resized.
        dragWindow.validate();editor.revalidate();editor.repaint();
    }
    private void finishDrag(boolean commit){dragUpdates.stop();if(commit)resizeBelow(requestedHeight);dragging=false;doLayout();terminal.endLayoutResize();dragWindow=null;dragBounds=null;}
    void showEditor(boolean visible){editor.setVisible(visible);revalidate();repaint();}
    private void updateEditor(){
        editor.setPreferredSize(new Dimension(0,editorHeight));editor.setMinimumSize(new Dimension(0,SINGLE_LINE_HEIGHT));
        boolean single=editorHeight==SINGLE_LINE_HEIGHT;
        scroll.setVerticalScrollBarPolicy(single?ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER:ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.setHorizontalScrollBarPolicy(single?ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER:ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    }
    @Override public void doLayout(){
        int height=editor.isVisible()?Math.min(editorHeight,Math.max(SINGLE_LINE_HEIGHT,getHeight()-100)):0;
        int terminalHeight=dragging?dragTerminalHeight:Math.max(0,getHeight()-height);
        // Avoid even a transient native HWND resize when changing only the editor.
        setBoundsIfChanged(terminalSurface,0,0,getWidth(),terminalHeight);
        setBoundsIfChanged(editor,0,terminalHeight,getWidth(),dragging?editorHeight:height);
    }
    private static void setBoundsIfChanged(Component component,int x,int y,int width,int height){if(component.getX()!=x||component.getY()!=y||component.getWidth()!=width||component.getHeight()!=height)component.setBounds(x,y,width,height);}
    @Override public Dimension getPreferredSize(){Dimension size=terminalSurface.getPreferredSize();return new Dimension(size.width,size.height+(editor.isVisible()?editorHeight:0));}
}
