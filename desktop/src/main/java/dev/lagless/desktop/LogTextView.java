package dev.lagless.desktop;

import dev.lagless.I18n;
import javax.swing.*;
import javax.swing.text.Document;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import org.jline.utils.WCWidth;

/** Read-only document with a native, bounded viewport. Search/copy keep source offsets. */
final class LogTextView extends JPanel implements AutoCloseable {
    private final JTextArea model=DesktopApp.textArea();
    private NativeTextCanvas canvas;
    private JScrollPane fallbackScroll;
    private final JScrollBar vertical=new JScrollBar(),horizontal=new JScrollBar(JScrollBar.HORIZONTAL);
    private Runnable nearStart=()->{},nearEnd=()->{},scrolledBack=()->{};
    private record Line(int start,int end,int[] characters,int[] offsets) { }
    private List<Line> lines=List.of();
    private String content="";
    private int columns=-1,cellWidth=9,cellHeight=20,ascent=15,maxColumns;
    private int anchor,selectionEnd;
    private boolean updating;
    private double position;
    LogTextView(){
        super(new BorderLayout());model.setEditable(false);model.setLineWrap(true);model.setWrapStyleWord(true);
        if(!NativeGraphics.available()){
            fallbackScroll=new EditorScrollPane(model);add(fallbackScroll);
            fallbackScroll.getVerticalScrollBar().addAdjustmentListener(e->{if(!updating)scrollEdges(fallbackScroll.getVerticalScrollBar().getValue(),fallbackScroll.getViewport().getHeight(),fallbackScroll.getVerticalScrollBar().getMaximum());});
            fallbackScroll.addMouseWheelListener(e->{if(e.getPreciseWheelRotation()<0)scrolledBack.run();});
            return;
        }
        model.setFont(DesktopFonts.monospace(14));
        canvas=new NativeTextCanvas(this::frame,this::fallback);
        canvas.enableInputMethods(false);
        canvas.setBackground(Theme.SURFACE);add(canvas);add(vertical,BorderLayout.EAST);add(horizontal,BorderLayout.SOUTH);
        vertical.addAdjustmentListener(e->{if(!updating){if(vertical.getValue()<position)scrolledBack.run();position=vertical.getValue();canvas.repaint();scrollEdges((int)position,canvas.getHeight(),totalHeight());}});
        horizontal.addAdjustmentListener(e->{if(!updating)canvas.repaint();});
        canvas.addComponentListener(new ComponentAdapter(){public void componentResized(ComponentEvent e){columns=-1;layoutLines();}});
        canvas.addMouseWheelListener(e->{
            if(e.getPreciseWheelRotation()<0)scrolledBack.run();
            if(e.isShiftDown()&&!model.getLineWrap())horizontal.setValue(horizontal.getValue()+(int)Math.round(e.getPreciseWheelRotation()*cellWidth*3));
            else{position=Math.max(0,Math.min(Math.max(0,totalHeight()-canvas.getHeight()),position+e.getPreciseWheelRotation()*cellHeight*Math.max(1,e.getScrollAmount())));updating=true;try{vertical.setValue((int)Math.round(position));}finally{updating=false;}canvas.repaint();scrollEdges((int)position,canvas.getHeight(),totalHeight());}
            e.consume();
        });
        canvas.addMouseListener(new MouseAdapter(){
            public void mousePressed(MouseEvent e){canvas.requestFocusInWindow();if(e.isPopupTrigger()||SwingUtilities.isRightMouseButton(e)){menu().show(LogTextView.this,e.getX(),e.getY());return;}if(SwingUtilities.isLeftMouseButton(e)){int at=offset(e);if(!e.isShiftDown())anchor=at;selectionEnd=at;canvas.repaint();}}
        });
        canvas.addMouseMotionListener(new MouseMotionAdapter(){public void mouseDragged(MouseEvent e){selectionEnd=offset(e);if(e.getY()<0)vertical.setValue(vertical.getValue()-cellHeight);else if(e.getY()>canvas.getHeight())vertical.setValue(vertical.getValue()+cellHeight);canvas.repaint();}});
        canvas.addKeyListener(new KeyAdapter(){public void keyPressed(KeyEvent e){
            if((e.isControlDown()||e.isMetaDown())&&e.getKeyCode()==KeyEvent.VK_C){copy();e.consume();return;}
            if((e.isControlDown()||e.isMetaDown())&&e.getKeyCode()==KeyEvent.VK_A){anchor=0;selectionEnd=content.length();canvas.repaint();e.consume();return;}
            int current=vertical.getValue(),page=Math.max(cellHeight,canvas.getHeight()-cellHeight);
            int target=switch(e.getKeyCode()){case KeyEvent.VK_UP->current-cellHeight;case KeyEvent.VK_DOWN->current+cellHeight;case KeyEvent.VK_PAGE_UP->current-page;case KeyEvent.VK_PAGE_DOWN->current+page;case KeyEvent.VK_HOME->0;case KeyEvent.VK_END->totalHeight();default->-1;};
            if(target>=0){vertical.setValue(target);e.consume();}
        }});
    }
    private JPopupMenu menu(){JPopupMenu menu=new JPopupMenu();menu.setLightWeightPopupEnabled(false);JMenuItem copy=new JMenuItem(I18n.t("复制","Copy"));copy.addActionListener(e->copy());menu.add(copy);JMenuItem all=new JMenuItem(I18n.t("全选","Select all"));all.addActionListener(e->{anchor=0;selectionEnd=content.length();canvas.repaint();});menu.add(all);return menu;}
    void onScrollEdges(Runnable older,Runnable newer,Runnable back){nearStart=older;nearEnd=newer;scrolledBack=back;}
    private void scrollEdges(int top,int height,int total){
        int threshold=Math.max(cellHeight*2,height/3);
        if(top<=threshold)nearStart.run();
        if(total-top-height<=threshold)nearEnd.run();
    }
    private void copy(){if(anchor==selectionEnd)return;try{ClipboardActions.putText(null,content.substring(Math.min(anchor,selectionEnd),Math.max(anchor,selectionEnd)));}catch(Exception error){DesktopApp.error(this,error);}}
    private void fallback(String reason){if(canvas==null)return;removeAll();canvas=null;model.setFont(DesktopFonts.editor(14));model.setText(content);model.select(Math.min(anchor,selectionEnd),Math.max(anchor,selectionEnd));fallbackScroll=new EditorScrollPane(model);fallbackScroll.getVerticalScrollBar().addAdjustmentListener(e->{if(!updating)scrollEdges(fallbackScroll.getVerticalScrollBar().getValue(),fallbackScroll.getViewport().getHeight(),fallbackScroll.getVerticalScrollBar().getMaximum());});add(fallbackScroll);revalidate();repaint();setToolTipText(I18n.t("原生显示未能启用：","Native display could not start: ")+reason);}
    void setText(String value){content=value;model.setText(value);anchor=selectionEnd=0;position=0;columns=-1;if(canvas!=null){layoutLines();updating=true;try{vertical.setValue(0);}finally{updating=false;}}}
    void setTextPreservingView(String value,int prefixChars){
        int source=0,inside=0;
        int selectedFrom=canvas==null?model.getSelectionStart():anchor,selectedTo=canvas==null?model.getSelectionEnd():selectionEnd;
        if(canvas!=null&&!lines.isEmpty()){
            int row=Math.min(lines.size()-1,Math.max(0,(int)(position/cellHeight)));
            source=lines.get(row).start();inside=(int)position%cellHeight;
        }else if(fallbackScroll!=null){source=model.viewToModel2D(fallbackScroll.getViewport().getViewPosition());}
        content=value;model.setText(value);columns=-1;
        anchor=Math.min(value.length(),Math.max(0,selectedFrom+prefixChars));
        selectionEnd=Math.min(value.length(),Math.max(0,selectedTo+prefixChars));
        if(canvas!=null){
            layoutLines();int at=Math.min(value.length(),Math.max(0,source+prefixChars));
            updating=true;try{vertical.setValue(lineAt(at)*cellHeight+inside);position=vertical.getValue();}finally{updating=false;}
            canvas.repaint();
        }else if(fallbackScroll!=null){
            int at=Math.min(value.length(),Math.max(0,source+prefixChars));
            model.select(anchor,selectionEnd);
            SwingUtilities.invokeLater(()->{try{Rectangle bounds=model.modelToView2D(at).getBounds();fallbackScroll.getViewport().setViewPosition(new Point(0,bounds.y));}catch(javax.swing.text.BadLocationException ignored){}});
        }
    }
    void scrollToBottom(){
        if(canvas!=null){updating=true;try{vertical.setValue(totalHeight());position=vertical.getValue();}finally{updating=false;}canvas.repaint();}
        else model.setCaretPosition(content.length());
    }
    String getText(){return content;}
    Document getDocument(){return model.getDocument();}
    int getSelectionEnd(){return canvas==null?model.getSelectionEnd():Math.max(anchor,selectionEnd);}
    void setLineWrap(boolean wrap){model.setLineWrap(wrap);columns=-1;if(canvas!=null)layoutLines();}
    void setWrapStyleWord(boolean word){model.setWrapStyleWord(word);columns=-1;if(canvas!=null)layoutLines();}
    void setEditable(boolean editable){model.setEditable(false);}
    void setCaretPosition(int at){select(at,at);}
    void select(int from,int to){
        anchor=Math.max(0,Math.min(content.length(),from));selectionEnd=Math.max(0,Math.min(content.length(),to));
        if(canvas==null){model.select(anchor,selectionEnd);return;}
        layoutLines();int row=lineAt(anchor),top=row*cellHeight;
        if(top<position||top+cellHeight>position+canvas.getHeight())vertical.setValue(top<position?top:top+cellHeight-canvas.getHeight());
        canvas.repaint();
    }
    @Override public boolean requestFocusInWindow(){return canvas==null?model.requestFocusInWindow():canvas.requestFocusInWindow();}
    private int lineAt(int offset){int low=0,high=Math.max(0,lines.size()-1);while(low<high){int mid=(low+high+1)/2;if(lines.get(mid).start()<=offset)low=mid;else high=mid-1;}return low;}
    private int totalHeight(){return Math.min(Integer.MAX_VALUE-1,Math.max(1,lines.size())*cellHeight);}
    private int offset(MouseEvent event){
        layoutLines();int row=Math.max(0,Math.min(lines.size()-1,(int)((event.getY()+position)/cellHeight)));
        if(lines.isEmpty())return 0;Line line=lines.get(row);int col=Math.max(0,(event.getX()+horizontal.getValue())/cellWidth);
        return col>=line.offsets().length?line.end():line.offsets()[col];
    }
    private void layoutLines(){
        if(canvas==null)return;
        FontMetrics metrics=model.getFontMetrics(model.getFont());cellWidth=Math.max(1,metrics.charWidth('M'));cellHeight=metrics.getHeight();ascent=metrics.getAscent();
        int width=Math.max(2,canvas.getWidth()/cellWidth);
        if(columns!=width){
            columns=width;List<Line> result=new ArrayList<>();maxColumns=0;
            int start=0;while(start<content.length()){
                int limit=content.indexOf('\n',start);if(limit<0)limit=content.length();
                if(start==limit)result.add(new Line(start,limit,new int[0],new int[0]));
                int at=start;
                while(at<limit){
                    int end=at,col=0,lastBreak=-1;
                    while(end<limit){int cp=content.codePointAt(end),cells=cells(cp,col);if(model.getLineWrap()&&col+cells>width&&end>at)break;col+=cells;end+=Character.charCount(cp);if(Character.isWhitespace(cp))lastBreak=end;}
                    if(end<limit&&model.getWrapStyleWord()&&lastBreak>at)end=lastBreak;
                    int capacity=0;for(int p=at;p<end;){int cp=content.codePointAt(p);capacity+=cells(cp,capacity);p+=Character.charCount(cp);}
                    int[] chars=new int[capacity],offsets=new int[capacity];int c=0;
                    for(int p=at;p<end;){int cp=content.codePointAt(p),span=cells(cp,c);for(int n=0;n<span;n++){chars[c]=cp=='\t'?' ':n==0?cp:-1;offsets[c++]=p;}p+=Character.charCount(cp);}
                    result.add(new Line(at,end,chars,offsets));maxColumns=Math.max(maxColumns,capacity);at=end;
                }
                start=limit+1;
            }
            if(content.isEmpty()||content.endsWith("\n"))result.add(new Line(content.length(),content.length(),new int[0],new int[0]));
            lines=List.copyOf(result);
        }
        updating=true;
        try{
            int height=Math.max(1,canvas.getHeight()),total=Math.max(height,totalHeight());position=Math.min(position,Math.max(0,total-height));
            vertical.setValues((int)position,height,0,total);vertical.setUnitIncrement(cellHeight);vertical.setBlockIncrement(Math.max(cellHeight,height-cellHeight));
            int extent=Math.max(1,canvas.getWidth());horizontal.setValues(model.getLineWrap()?0:horizontal.getValue(),extent,0,Math.max(extent,maxColumns*cellWidth));horizontal.setUnitIncrement(cellWidth);
            boolean visible=!model.getLineWrap()&&maxColumns*cellWidth>extent;if(horizontal.isVisible()!=visible)horizontal.setVisible(visible);
        }finally{updating=false;}
        canvas.repaint();
    }
    private static int cells(int cp,int col){return cp=='\t'?4-col%4:Math.max(1,cp>=0x1f300&&cp<=0x1faff?2:WCWidth.wcwidth(cp));}
    private NativeGraphics.Frame frame(){
        // Layout is invalidated by document, wrap and size changes, never by paint.
        if(columns<0)layoutLines();
        int first=(int)(position/cellHeight),left=horizontal.getValue()/cellWidth,cols=Math.max(2,canvas.getWidth()/cellWidth+1),rows=Math.max(1,canvas.getHeight()/cellHeight+2);
        int[] chars=new int[cols*rows],fg=new int[chars.length],bg=new int[chars.length];Arrays.fill(chars,' ');int paper=Theme.SURFACE.getRGB()&0xffffff;Arrays.fill(fg,Theme.INK.getRGB()&0xffffff);Arrays.fill(bg,paper);
        int lo=Math.min(anchor,selectionEnd),hi=Math.max(anchor,selectionEnd);
        for(int r=0;r<rows&&first+r<lines.size();r++){
            Line line=lines.get(first+r);for(int c=0;c<cols&&left+c<line.characters().length;c++){
                int p=r*cols+c;chars[p]=line.characters()[left+c];int source=line.offsets()[left+c];if(source>=lo&&source<hi)bg[p]=Theme.SELECTION.getRGB()&0xffffff;
            }
        }
        return new NativeGraphics.Frame(cols,rows,chars,fg,bg,cellWidth,cellHeight,ascent,(int)Math.round(position-first*cellHeight),-1,-1,false,model.getFont().getSize2D(),paper);
    }
    @Override public void close(){if(canvas!=null)canvas.release();}
}
