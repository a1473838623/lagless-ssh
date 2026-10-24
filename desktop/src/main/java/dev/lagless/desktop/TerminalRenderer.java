package dev.lagless.desktop;

import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.geom.Point2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.*;

/** Device-resolution row cache. Caret and scrolling never rasterize unchanged text. */
final class TerminalRenderer implements AutoCloseable {
    private BufferedImage image;
    private SwingTerminal.PaintFrame previous;
    private Font font;
    private int width,height,cellWidth,cellHeight,ascent,selectionFrom=-1,selectionTo=-1;
    private double scaleX,scaleY;
    private final Map<String,Color[]> colors=new LinkedHashMap<>(256,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Color[]> entry){return size()>256;}
    };
    private final Map<String,GlyphVector> glyphs=new LinkedHashMap<>(512,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,GlyphVector> entry){return size()>512;}
    };

    void paint(Graphics2D target,SwingTerminal view,SwingTerminal.PaintFrame frame,int cellWidth,int cellHeight,int ascent,int from,int to,int offset){
        int width=Math.max(1,view.getWidth()),height=Math.max(1,view.getHeight()+cellHeight);
        double sx=Math.max(1,Math.abs(target.getTransform().getScaleX())),sy=Math.max(1,Math.abs(target.getTransform().getScaleY()));
        boolean reset=image==null||this.width!=width||this.height!=height||scaleX!=sx||scaleY!=sy||!view.getFont().equals(font)||this.cellWidth!=cellWidth||this.cellHeight!=cellHeight||this.ascent!=ascent;
        if(reset){
            if(image!=null)image.flush();
            image=new BufferedImage((int)Math.ceil(width*sx),(int)Math.ceil(height*sy),BufferedImage.TYPE_INT_RGB);
            this.width=width;this.height=height;this.scaleX=sx;this.scaleY=sy;this.font=view.getFont();this.cellWidth=cellWidth;this.cellHeight=cellHeight;this.ascent=ascent;
            previous=null;glyphs.clear();
        }
        boolean dimensions=previous==null||previous.columns()!=frame.columns()||previous.rows()!=frame.rows();
        boolean selection=selectionFrom!=from||selectionTo!=to;
        Graphics2D g=null;
        try{
            for(int r=0;r<frame.rows();r++){
                int start=r*frame.columns(),end=start+frame.columns();
                boolean changed=dimensions||!Arrays.equals(frame.characters(),start,end,previous.characters(),start,end)||!Arrays.equals(frame.pens(),start,end,previous.pens(),start,end)
                        ||selection&&(intersects(from,to,start,end)||intersects(selectionFrom,selectionTo,start,end));
                if(!changed)continue;
                if(g==null){g=image.createGraphics();g.scale(sx,sy);g.setFont(font);DesktopFonts.textHints(g);g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_OFF);if(reset){g.setColor(view.getBackground());g.fillRect(0,0,width,height);}}
                drawRow(g,view,frame,r,from,to);
            }
        }finally{if(g!=null)g.dispose();}
        previous=frame;selectionFrom=from;selectionTo=to;
        // Replace the entire exposed viewport. A cached row can be shorter
        // than the previous one; uncovered pixels must never retain old ink.
        Composite composite=target.getComposite();
        try{
            target.setComposite(AlphaComposite.Src);target.setColor(view.getBackground());target.fillRect(0,0,view.getWidth(),view.getHeight());
            target.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            AffineTransform device=target.getTransform(),copy=new AffineTransform();
            // The cache is already at device resolution. Scaling it again to
            // rounded logical dimensions blurs text at 125%/150% DPI. Align
            // both origin and scroll displacement to whole physical pixels.
            copy.translate((Math.rint(device.getTranslateX())-device.getTranslateX())/sx,
                    (Math.rint(device.getTranslateY())-device.getTranslateY()-Math.round(offset*sy))/sy);
            copy.scale(1/sx,1/sy);
            target.drawImage(image,copy,null);
        }finally{target.setComposite(composite);}
    }
    private static boolean intersects(int from,int to,int start,int end){return from>=0&&from!=to&&Math.max(from,to)>=start&&Math.min(from,to)<end;}
    private void drawRow(Graphics2D g,SwingTerminal view,SwingTerminal.PaintFrame frame,int row,int from,int to){
        Graphics2D rowGraphics=(Graphics2D)g.create();
        try{drawClippedRow(rowGraphics,view,frame,row,from,to);}finally{rowGraphics.dispose();}
    }
    private void drawClippedRow(Graphics2D g,SwingTerminal view,SwingTerminal.PaintFrame frame,int row,int from,int to){
        int columns=frame.columns(),base=row*columns;
        // Font fallback can extend above/below its nominal cell. Clip the ink
        // to its own row so a partial update cannot stain an unchanged row.
        g.clipRect(0,row*cellHeight,width,cellHeight);
        g.setComposite(AlphaComposite.Src);g.setColor(view.getBackground());g.fillRect(0,row*cellHeight,width,cellHeight);
        g.setComposite(AlphaComposite.SrcOver);
        for(int c=0;c<columns;){
            String pen=frame.pens()[base+c];Color[] pair=colors.computeIfAbsent(pen,SwingTerminal::colors);
            int end=c+1;while(end<columns&&Objects.equals(pen,frame.pens()[base+end]))end++;
            g.setColor(pair[1]);g.fillRect(c*cellWidth,row*cellHeight,(end-c)*cellWidth,cellHeight);
            if(intersects(from,to,base+c,base+end)){
                int lo=Math.max(base+c,Math.min(from,to)),hi=Math.min(base+end-1,Math.max(from,to));
                g.setColor(new Color(0x345170));g.fillRect((lo-base)*cellWidth,row*cellHeight,(hi-lo+1)*cellWidth,cellHeight);
            }
            g.setColor(pair[0]);
            for(int at=c;at<end;){
                int cp=frame.characters()[base+at];if(cp<0){at++;continue;}
                if(cp>=32&&cp<127){
                    int stop=at+1;while(stop<end&&stop-at<1024&&frame.characters()[base+stop]>=32&&frame.characters()[base+stop]<127)stop++;
                    int ink=stop;while(ink>at&&frame.characters()[base+ink-1]==' ')ink--;
                    if(ink>at){char[] text=new char[ink-at];for(int n=at;n<ink;n++)text[n-at]=(char)frame.characters()[base+n];
                        String key=new String(text);GlyphVector vector=glyphs.get(key);
                        if(vector==null){
                            vector=font.createGlyphVector(g.getFontRenderContext(),text);
                            // Native font advances and the terminal grid must agree.
                            // Otherwise long runs leave gaps before the cursor/command.
                            for(int n=0;n<=vector.getNumGlyphs();n++)vector.setGlyphPosition(n,new Point2D.Float(n*cellWidth,0));
                            glyphs.put(key,vector);
                        }
                        g.drawGlyphVector(vector,at*cellWidth,row*cellHeight+ascent);
                    }
                    at=stop;
                }else{g.drawString(new String(Character.toChars(cp)),at*cellWidth,row*cellHeight+ascent);at++;}
            }
            c=end;
        }
    }
    @Override public void close(){if(image!=null){image.flush();image=null;}previous=null;glyphs.clear();colors.clear();}
}
