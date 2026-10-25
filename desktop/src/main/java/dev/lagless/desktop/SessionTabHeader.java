package dev.lagless.desktop;

import dev.lagless.I18n;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/** Compact tabs with a stable width and an explicit hit target for selection. */
final class SessionTabHeader extends JPanel {
    private final JLabel title;
    private final JButton close;
    private boolean selected,hover;
    SessionTabHeader(JTabbedPane tabs,String text,Runnable closeAction){
        super(new BorderLayout(10,0));setOpaque(false);setBorder(BorderFactory.createEmptyBorder(5,12,5,closeAction==null?12:6));
        title=new JLabel(text);title.setFont(DesktopFonts.ui().deriveFont(Font.BOLD,13));add(title,BorderLayout.CENTER);
        close=closeAction==null?null:UiIcons.closeButton(I18n.t("关闭","Close ")+text,closeAction);
        if(close!=null){close.setBorder(BorderFactory.createEmptyBorder(2,2,2,2));add(close,BorderLayout.EAST);}
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));getAccessibleContext().setAccessibleName(text);
        MouseAdapter pointer=new MouseAdapter(){
            @Override public void mousePressed(MouseEvent e){if(SwingUtilities.isLeftMouseButton(e)&&e.getComponent()!=close){int index=tabs.indexOfTabComponent(SessionTabHeader.this);if(index>=0)tabs.setSelectedIndex(index);}}
            @Override public void mouseEntered(MouseEvent e){hover=true;repaint();}
            @Override public void mouseExited(MouseEvent e){hover=contains(SwingUtilities.convertPoint(e.getComponent(),e.getPoint(),SessionTabHeader.this));repaint();}
        };
        addMouseListener(pointer);title.addMouseListener(pointer);if(close!=null)close.addMouseListener(pointer);update(false);
    }
    void update(boolean active){
        selected=active;title.setForeground(active?Color.WHITE:new Color(0x475569));
        if(close!=null)close.setForeground(active?new Color(0xccfbf1):new Color(0x64748b));repaint();
    }
    @Override protected void paintComponent(Graphics graphics){
        Graphics2D g=(Graphics2D)graphics.create();try{
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(selected?new Color(0x0f172a):hover?new Color(0xeef2f6):Color.WHITE);g.fillRoundRect(0,0,getWidth()-1,getHeight()-1,12,12);
            g.setColor(selected?new Color(0x0f172a):DesktopApp.LINE);g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,12,12);
            if(selected){g.setColor(DesktopApp.ACCENT);g.fillRoundRect(10,getHeight()-4,getWidth()-21,2,2,2);}
        }finally{g.dispose();}
    }
}
