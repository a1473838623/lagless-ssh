package dev.lagless.desktop;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.function.*;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;

/** A real HWND: Direct2D presents it directly, without a Java2D image copy. */
final class NativeTextCanvas extends Canvas {
    private final Supplier<NativeGraphics.Frame> frames;
    private final Consumer<String> fallback;
    private long handle;
    private boolean failed;
    private java.awt.im.InputMethodRequests inputRequests;
    NativeTextCanvas(Supplier<NativeGraphics.Frame> frames,Consumer<String> fallback){
        this.frames=frames;this.fallback=fallback;setFocusable(true);setFocusTraversalKeysEnabled(false);enableInputMethods(true);
    }
    void setInputRequests(java.awt.im.InputMethodRequests requests){inputRequests=requests;}
    @Override public java.awt.im.InputMethodRequests getInputMethodRequests(){return inputRequests;}
    @Override protected void processKeyEvent(KeyEvent event){
        // Canvas is not a JComponent: route keys to an open Swing menu before
        // the terminal listeners can send arrows, Enter or Escape to SSH.
        MenuSelectionManager menus=MenuSelectionManager.defaultManager();
        if(menus.getSelectedPath().length>0){menus.processKeyEvent(event);event.consume();return;}
        super.processKeyEvent(event);
    }
    @Override public void addNotify(){
        // WCanvasPeer clears damaged pixels before Canvas.paint(), including
        // ancestor resize paints. Overriding update() alone cannot suppress it.
        // Its creation flags are per-peer; scope these properties to creation
        // under AWT's tree lock, then restore them for other components.
        synchronized(getTreeLock()){
            String erase=System.getProperty("sun.awt.noerasebackground"),resize=System.getProperty("sun.awt.erasebackgroundonresize");
            try{System.setProperty("sun.awt.noerasebackground","true");System.setProperty("sun.awt.erasebackgroundonresize","false");super.addNotify();}
            finally{restoreProperty("sun.awt.noerasebackground",erase);restoreProperty("sun.awt.erasebackgroundonresize",resize);}
        }
        try{failed=false;handle=NativeGraphics.open(this);if(handle==0)fail(NativeGraphics.error());else NativeGraphics.opened();}
        catch(Throwable error){fail(error.toString());}
    }
    private static void restoreProperty(String name,String value){if(value==null)System.clearProperty(name);else System.setProperty(name,value);}
    private void fail(String reason){
        if(failed)return;failed=true;NativeGraphics.failed(reason);
        SwingUtilities.invokeLater(()->fallback.accept(reason));
    }
    @Override public void update(Graphics graphics){paint(graphics);}
    @Override public void paint(Graphics ignored){
        if(handle==0||failed||getWidth()<1||getHeight()<1)return;
        try{
            NativeGraphics.Frame f=frames.get();
            if(!NativeGraphics.draw(handle,getWidth(),getHeight(),f.columns(),f.rows(),f.characters(),f.foreground(),f.background(),
                    f.cellWidth(),f.cellHeight(),f.ascent(),f.offset(),f.cursorColumn(),f.cursorRow(),f.cursor(),f.fontSize(),f.paper()))fail(NativeGraphics.error());
        }catch(Throwable error){fail(error.toString());}
    }
    @Override public void removeNotify(){release();super.removeNotify();}
    void release(){if(handle!=0){NativeGraphics.dispose(handle);handle=0;NativeGraphics.closed();}}
}
