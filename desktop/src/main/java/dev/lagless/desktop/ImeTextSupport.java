package dev.lagless.desktop;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputMethodEvent;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.geom.Rectangle2D;
import java.text.AttributedCharacterIterator;

/** Paints IME preedit text when native-image Swing leaves its glyphs blank. */
final class ImeTextSupport {
    private String preedit="";
    private int anchor;
    private boolean composing;

    /** Drops the overlay whenever the composition can no longer be live. */
    void install(JTextComponent component){
        component.addFocusListener(new FocusAdapter(){@Override public void focusLost(FocusEvent e){clear(component);}});
        component.addPropertyChangeListener("document",e->{clear(component);listen(component);});
        listen(component);
    }
    private void listen(JTextComponent component){
        component.getDocument().addDocumentListener(new DocumentListener(){
            public void insertUpdate(DocumentEvent e){edited(component,e);}
            public void removeUpdate(DocumentEvent e){edited(component,e);}
            public void changedUpdate(DocumentEvent e){}
        });
    }
    // setText, paste or a send that empties the field leaves no composition behind.
    private void edited(JTextComponent component,DocumentEvent e){if(!composing&&e.getDocument()==component.getDocument())clear(component);}
    void clear(JTextComponent component){if(preedit.isEmpty())return;preedit="";component.repaint();}

    void process(JTextComponent component,InputMethodEvent event,java.util.function.Consumer<InputMethodEvent> next){
        composing=true;
        try{capture(component,event);next.accept(event);}finally{composing=false;}
    }

    void capture(JTextComponent component,InputMethodEvent event){
        if(event.getID()!=InputMethodEvent.INPUT_METHOD_TEXT_CHANGED)return;
        AttributedCharacterIterator iterator=event.getText();
        // The input method ends or cancels a composition with an event that carries no text.
        if(iterator==null){clear(component);return;}
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
    ImeTextField(){super();enableInputMethods(true);ime.install(this);}
    ImeTextField(String text,int columns){super(text,columns);enableInputMethods(true);ime.install(this);}
    ImeTextField(int columns){super(columns);enableInputMethods(true);ime.install(this);}
    @Override protected void processInputMethodEvent(InputMethodEvent event){ime.process(this,event,super::processInputMethodEvent);}
    @Override protected void paintComponent(Graphics graphics){super.paintComponent(graphics);ime.paint(this,graphics);}
}

final class ImeTextArea extends JTextArea {
    private final ImeTextSupport ime=new ImeTextSupport();
    ImeTextArea(){super();enableInputMethods(true);ime.install(this);}
    @Override protected void processInputMethodEvent(InputMethodEvent event){ime.process(this,event,super::processInputMethodEvent);}
    @Override protected void paintComponent(Graphics graphics){super.paintComponent(graphics);ime.paint(this,graphics);}
}
