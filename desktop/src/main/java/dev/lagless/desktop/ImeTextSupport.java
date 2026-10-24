package dev.lagless.desktop;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.InputMethodEvent;
import java.awt.geom.Rectangle2D;
import java.text.AttributedCharacterIterator;

/** Paints IME preedit text when native-image Swing leaves its glyphs blank. */
final class ImeTextSupport {
    private String preedit="";
    private int anchor;

    void capture(JTextComponent component,InputMethodEvent event){
        AttributedCharacterIterator iterator=event.getText();
        if(iterator==null)return;
        StringBuilder incoming=new StringBuilder();
        int committed=event.getCommittedCharacterCount(),at=0;
        for(char value=iterator.first();value!=AttributedCharacterIterator.DONE;value=iterator.next(),at++)if(at>=committed)incoming.append(value);
        if(preedit.isEmpty())anchor=component.getCaretPosition();
        else if(committed>0)anchor+=committed;
        preedit=incoming.toString();
        component.repaint();
    }

    void paint(JTextComponent component,Graphics graphics){
        if(preedit.isEmpty()||!DesktopRuntime.nativeImage())return;
        try{
            Rectangle2D caret=component.modelToView2D(Math.min(anchor,component.getDocument().getLength()));
            if(caret==null)return;
            Graphics2D g=(Graphics2D)graphics.create();
            try{
                FontMetrics metrics=g.getFontMetrics(component.getFont());
                int x=(int)caret.getX(),baseline=(int)caret.getY()+metrics.getAscent(),width=metrics.stringWidth(preedit);
                g.setColor(component.getBackground());g.fillRect(x,baseline-metrics.getAscent(),width+3,metrics.getHeight());
                g.setColor(component.getForeground());g.setFont(component.getFont());g.drawString(preedit,x,baseline);
                g.drawLine(x,baseline+2,x+width,baseline+2);
            }finally{g.dispose();}
        }catch(javax.swing.text.BadLocationException ignored){}
    }
}

final class ImeTextField extends JTextField {
    private final ImeTextSupport ime=new ImeTextSupport();
    ImeTextField(){super();enableInputMethods(true);}
    ImeTextField(String text,int columns){super(text,columns);enableInputMethods(true);}
    ImeTextField(int columns){super(columns);enableInputMethods(true);}
    @Override protected void processInputMethodEvent(InputMethodEvent event){ime.capture(this,event);super.processInputMethodEvent(event);}
    @Override protected void paintComponent(Graphics graphics){super.paintComponent(graphics);ime.paint(this,graphics);}
}

final class ImeTextArea extends JTextArea {
    private final ImeTextSupport ime=new ImeTextSupport();
    ImeTextArea(){super();enableInputMethods(true);}
    @Override protected void processInputMethodEvent(InputMethodEvent event){ime.capture(this,event);super.processInputMethodEvent(event);}
    @Override protected void paintComponent(Graphics graphics){super.paintComponent(graphics);ime.paint(this,graphics);}
}
