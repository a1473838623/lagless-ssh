package dev.lagless.desktop;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;

/** Small vector icons scale with the display and never depend on installed glyphs. */
final class UiIcons {
    private UiIcons(){}
    static final Icon CLOSE=new Drawing(false,false,true);
    /** Same size as CLOSE, draws nothing: keeps a hidden close button's hit target. */
    static final Icon BLANK=new Icon(){public int getIconWidth(){return 18;}public int getIconHeight(){return 18;}public void paintIcon(Component c,Graphics g,int x,int y){}};
    static final Icon SUN=new ThemeGlyph(false),MOON=new ThemeGlyph(true);
    static final Icon PLUS=new WorkspaceGlyph(false),HOME=new WorkspaceGlyph(true);
    static final Icon CHEVRON_DOWN=new ChevronDown();
    static Icon file(boolean folder,boolean link){return new Drawing(folder,link,false);}
    static JButton closeButton(String description,Runnable action){
        JButton button=new JButton(CLOSE);button.setName(description);button.setToolTipText(description);button.getAccessibleContext().setAccessibleName(description);
        button.setFocusable(false);button.setBorder(BorderFactory.createEmptyBorder(5,5,5,5));button.setContentAreaFilled(false);button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));button.addActionListener(e->action.run());return button;
    }
    private static final class ChevronDown implements Icon {
        public int getIconWidth(){return 14;}public int getIconHeight(){return 14;}
        public void paintIcon(Component component,Graphics graphics,int x,int y){
            Graphics2D g=(Graphics2D)graphics.create();try{g.translate(x,y);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setStroke(new BasicStroke(1.8f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.setColor(component.getForeground());g.drawLine(3,5,7,9);g.drawLine(7,9,11,5);}finally{g.dispose();}
        }
    }
    private record WorkspaceGlyph(boolean home) implements Icon {
        public int getIconWidth(){return 18;}public int getIconHeight(){return 18;}
        public void paintIcon(Component component,Graphics graphics,int x,int y){
            Graphics2D g=(Graphics2D)graphics.create();try{
                g.translate(x,y);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setStroke(new BasicStroke(1.8f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.setColor(component.getForeground());
                if(home){g.drawLine(3,9,9,4);g.drawLine(9,4,15,9);g.drawLine(5,8,5,15);g.drawLine(5,15,13,15);g.drawLine(13,15,13,8);}
                else{g.drawLine(9,3,9,15);g.drawLine(3,9,15,9);}
            }finally{g.dispose();}
        }
    }
    /** Sun or moon for the theme switch, drawn in the component's foreground. */
    private record ThemeGlyph(boolean moon) implements Icon {
        public int getIconWidth(){return 18;}public int getIconHeight(){return 18;}
        public void paintIcon(Component component,Graphics graphics,int x,int y){
            Graphics2D g=(Graphics2D)graphics.create();try{g.translate(x,y);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setStroke(new BasicStroke(1.6f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.setColor(component.getForeground());
                if(moon){java.awt.geom.Area shape=new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Float(3,3,12,12));shape.subtract(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Float(7,0.5f,11,11)));g.fill(shape);}
                else{g.drawOval(6,6,6,6);for(int i=0;i<8;i++){double a=Math.PI/4*i;g.drawLine((int)Math.round(9+Math.cos(a)*5.5),(int)Math.round(9+Math.sin(a)*5.5),(int)Math.round(9+Math.cos(a)*7.5),(int)Math.round(9+Math.sin(a)*7.5));}}
            }finally{g.dispose();}
        }
    }
    private record Drawing(boolean folder,boolean link,boolean close) implements Icon {
        public int getIconWidth(){return 18;}public int getIconHeight(){return 18;}
        public void paintIcon(Component component,Graphics graphics,int x,int y){
            Graphics2D g=(Graphics2D)graphics.create();try{g.translate(x,y);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setStroke(new BasicStroke(1.35f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                if(close){g.setColor(component.isEnabled()?Theme.MUTED:Theme.FAINT);g.drawLine(5,5,13,13);g.drawLine(13,5,5,13);return;}
                if(folder){Path2D.Float back=new Path2D.Float();back.moveTo(1.5,5);back.quadTo(1.5,3,3.5,3);back.lineTo(7,3);back.lineTo(9,5);back.lineTo(14.5,5);back.quadTo(16.5,5,16.5,7);back.lineTo(16.5,14);back.lineTo(1.5,14);back.closePath();g.setColor(new Color(0xe9b861));g.fill(back);g.setColor(new Color(0xf7d58e));g.fillRoundRect(1,6,16,9,3,3);g.setColor(new Color(0xc59139));g.drawRoundRect(1,6,16,9,3,3);}
                else{Path2D.Float page=new Path2D.Float();page.moveTo(4,1.5);page.lineTo(10.5,1.5);page.lineTo(14.5,5.5);page.lineTo(14.5,16);page.lineTo(4,16);page.closePath();g.setColor(new Color(0xf2f5f8));g.fill(page);g.setColor(new Color(0x8796a8));g.draw(page);g.drawLine(10,2,10,6);g.drawLine(10,6,14,6);g.drawLine(7,9,12,9);g.drawLine(7,12,12,12);}
                if(link){g.setColor(Theme.SURFACE);g.fillRoundRect(9,10,9,8,3,3);g.setColor(DesktopApp.ACCENT);g.drawLine(11,16,16,11);g.drawLine(12,11,16,11);g.drawLine(16,11,16,15);}
            }finally{g.dispose();}
        }
    }
}
