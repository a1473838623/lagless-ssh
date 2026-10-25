package dev.lagless.desktop;

import dev.lagless.I18n;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * Flat workspace tab. The active surface is distinguished by its background
 * and label weight, without an accent rule above or below the title.
 */
final class SessionTabHeader extends JPanel {
    private final JLabel title;
    private final JButton close;
    private final boolean session;
    private boolean selected,hover;
    SessionTabHeader(JTabbedPane tabs,String text,Runnable closeAction){
        super(new BorderLayout(8,0));setOpaque(false);session=closeAction!=null;
        setBorder(BorderFactory.createEmptyBorder(8,session?27:14,6,session?8:14));
        title=new JLabel(text);if(!session){title.setIcon(UiIcons.HOME);title.setIconTextGap(8);}title.setFont(DesktopFonts.ui().deriveFont(Font.PLAIN,13));add(title,BorderLayout.CENTER);
        close=closeAction==null?null:UiIcons.closeButton(I18n.t("关闭","Close ")+text,closeAction);
        if(close!=null){close.setBorder(BorderFactory.createEmptyBorder(1,1,1,1));add(close,BorderLayout.EAST);}
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));getAccessibleContext().setAccessibleName(text);
        MouseAdapter pointer=new MouseAdapter(){
            @Override public void mousePressed(MouseEvent e){if(SwingUtilities.isLeftMouseButton(e)&&e.getComponent()!=close){int index=tabs.indexOfTabComponent(SessionTabHeader.this);if(index>=0)tabs.setSelectedIndex(index);}}
            @Override public void mouseEntered(MouseEvent e){hover=true;update(selected);}
            @Override public void mouseExited(MouseEvent e){hover=contains(SwingUtilities.convertPoint(e.getComponent(),e.getPoint(),SessionTabHeader.this));update(selected);}
        };
        addMouseListener(pointer);title.addMouseListener(pointer);if(close!=null)close.addMouseListener(pointer);update(false);
    }
    void update(boolean active){
        selected=active;
        title.setForeground(active?Theme.INK:hover?Theme.TEXT:Theme.MUTED);
        title.setFont(title.getFont().deriveFont(active?Font.BOLD:Font.PLAIN));
        // The close button stays clickable but only shows on the active or hovered tab.
        if(close!=null)close.setIcon(active||hover?UiIcons.CLOSE:UiIcons.BLANK);
        repaint();
    }
    @Override protected void paintComponent(Graphics graphics){
        Graphics2D g=(Graphics2D)graphics.create();try{
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            int w=getWidth()-1,h=getHeight()-1;
            if(selected){
                g.setColor(Theme.SURFACE);g.fillRoundRect(0,3,w,h+8,10,10);
                g.setColor(Theme.LINE);g.drawLine(0,6,0,h);g.drawLine(w,6,w,h);
            }else if(hover){g.setColor(Theme.HOVER);g.fillRoundRect(0,4,w,h+7,8,8);}
            if(session){g.setColor(selected?Theme.ACCENT:Theme.FAINT);int d=7;g.fillOval(12,(h-d)/2+1,d,d);}
        }finally{g.dispose();}
    }
}
