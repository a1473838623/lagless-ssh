package dev.lagless.desktop;

import dev.lagless.I18n;
import dev.lagless.ScreenModel;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.Size;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.awt.im.InputMethodRequests;
import java.awt.font.TextHitInfo;
import java.text.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Swing view of the existing terminal model. Keyboard delivery never waits on a socket. */
public final class SwingTerminal extends JComponent implements AutoCloseable,InputMethodRequests {
    private final ScreenModel screen = new ScreenModel(100, 30);
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE);
    private ByteBuffer pending = ByteBuffer.allocate(32768);
    private volatile KeyInput input;
    private volatile Terminal terminal;
    private volatile boolean accepting;
    private volatile Consumer<String> inputAudit=message->{};
    private final java.util.concurrent.atomic.AtomicReference<Size> pendingResize = new java.util.concurrent.atomic.AtomicReference<>();
    private final ScheduledExecutorService resizer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t=new Thread(r,"lagless-desktop-resize");t.setDaemon(true);return t; });
    private final Timer redraw;
    private int cellWidth = 9, cellHeight = 20, ascent = 15;
    private volatile boolean dirty = true;
    private final java.util.concurrent.atomic.AtomicBoolean frameDirty=new java.util.concurrent.atomic.AtomicBoolean(true);
    private final TerminalRenderer renderer=new TerminalRenderer();
    private PaintFrame cachedFrame;
    private PaintFrame completedLiveFrame;
    private boolean presentationPending;
    private long lastOutputAt,redrawSince,synchronizedSince,captureSince;
    private boolean capturePending;
    private int cursorCapHeight=11;
    private NativeTextCanvas nativeCanvas;
    private final java.util.Map<String,Color[]> nativeColors=new java.util.LinkedHashMap<>(256,.75f,true){
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,Color[]> e){return size()>256;}
    };
    private double wheelPosition;private boolean wheelPositionValid;
    private int scrollOffset;private long lastBarUpdate,lastPresented;
    private long observedTailRevision=-1;
    private long cursorEpoch=System.nanoTime();
    private boolean cursorOn=true;
    private String composition="";
    private int compositionCaret;
    static boolean cursorPhase(long elapsedNanos){return elapsedNanos/530_000_000L%2==0;}
    static Rectangle cursorBounds(int column,int row,int cellWidth,int cellHeight,int ascent,int capHeight){
        int height=Math.min(cellHeight,Math.max(2,capHeight+6));
        // Center on visible capital/digit ink, not the line box's descent space.
        // Integer edges keep the bar crisp at the current display resolution.
        int top=Math.max(0,Math.min(cellHeight-height,Math.round(ascent-capHeight/2f-height/2f)));
        return new Rectangle(column*cellWidth+1,row*cellHeight+top,2,height);
    }
    private String queryTail = "";
    private volatile boolean appCursor, bracketedPaste;
    private volatile boolean interactiveRedraw;
    private boolean autoRemoteWheel=true;
    private boolean pageKeysForRemoteWheel;
    private volatile boolean remoteHistoryNavigation,alternateScreen;
    private final RemoteWheelProtocol wheelProtocol=new RemoteWheelProtocol();
    private final RemoteWheelQueue remoteWheelQueue=new RemoteWheelQueue();
    private Consumer<String> notice = text -> { };
    private java.util.function.Supplier<Clipboard> clipboard;
    private Point selectionStart, selectionEnd;
    private final JPanel surface=new JPanel(new BorderLayout());
    private final JScrollBar historyBar=new JScrollBar(JScrollBar.VERTICAL);
    private final ThreadPoolExecutor historyReader=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"lagless-scrollback-reader");t.setDaemon(true);return t;},new ThreadPoolExecutor.DiscardOldestPolicy());
    private TerminalHistory history;private volatile boolean localEditing=true,closed;
    private volatile boolean layoutResizing;private boolean historyLoading,historyPageStale;
    private boolean followLive=true,adjustingBar;private long viewportStart,lastOmitted,pageStart=-1,displayStart;
    private volatile java.util.List<ScreenModel> historyPage=java.util.List.of();
    // Reader-thread-only decoded rows; nearby wheel moves reuse the same models.
    private final java.util.LinkedHashMap<Long,ScreenModel> decodedHistory=new java.util.LinkedHashMap<>(1024,.75f,true){
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<Long,ScreenModel> entry){return size()>1024;}
    };
    private static final java.util.regex.Pattern SGR_PATTERN=java.util.regex.Pattern.compile("\\u001b\\[([0-9;]*)m");
    private static final java.util.regex.Pattern REDRAW_PATTERN=java.util.regex.Pattern.compile("\\u001b\\[[0-9;]*[HfABJK]");
    public JComponent scrollable(){return surface;}
    void setLocalEditing(boolean value){synchronized(screen){
        if(!value&&localEditing&&history!=null&&!realtimeHistory(screen))updateOrdinaryTail();
        localEditing=value;if(value){interactiveRedraw=false;remoteHistoryNavigation=false;}
    }}
    void setAutoRemoteWheel(boolean value){autoRemoteWheel=value;remoteWheelQueue.cancel();}
    void setPageKeysForRemoteWheel(boolean value){pageKeysForRemoteWheel=value;remoteWheelQueue.cancel();}
    void observeRemoteModes(String text){wheelProtocol.feed(text);if(!localEditing&&REDRAW_PATTERN.matcher(text).find())interactiveRedraw=true;}
    boolean followingLive(){return followLive;}
    long viewportStart(){return followLive&&history!=null?history.count():viewportStart;}
    void setHistory(TerminalHistory history){this.history=history;synchronized(screen){screen.setHistoryListener(new ScreenModel.HistoryListener(){
        public void scrolled(int[] characters,String[] pens){scrolled(characters,pens,false);}
        public void scrolled(int[] characters,String[] pens,boolean wrapped){beginRedraw(screen);if(!realtimeHistory(screen))appendHistory(TerminalHistory.Row.cells(characters,pens,wrapped));}
        public void beforeClear(ScreenModel model){beginRedraw(model);if(!realtimeHistory(model)){for(TerminalHistory.Row row:ordinaryRows())appendHistory(row);history.setPendingTail(java.util.List.of());}}
        public void beforeOverwrite(ScreenModel model,int row){beginRedraw(model);}
        public void beforeEraseRow(ScreenModel model,int row){beginRedraw(model);}
        public void frameStarted(ScreenModel model){interactiveRedraw=true;synchronizedSince=System.nanoTime();if(completedLiveFrame==null)completedLiveFrame=livePaintFrame();}
        public void frameCompleted(ScreenModel model){synchronizedSince=0;publishLiveFrame();}
    });}}
    private void beginRedraw(ScreenModel model){
        if((remoteHistoryNavigation||model.altScreen()||!localEditing&&interactiveRedraw)&&redrawSince==0){
            redrawSince=System.nanoTime();if(completedLiveFrame==null)completedLiveFrame=livePaintFrame();
        }
    }
    private void publishLiveFrame(){
        completedLiveFrame=livePaintFrame();presentationPending=false;redrawSince=0;
        if(followLive)invalidateFrame();
    }
    private void presentPending(long now){
        if(!presentationPending)return;
        if(screen.synchronizedOutput()&&synchronizedSince!=0&&now-synchronizedSince<2_000_000_000L)return;
        if(redrawSince!=0){
            long age=now-redrawSince,quiet=now-lastOutputAt;
            // A remote TUI often sends one visible line per network chunk.
            // Present one coherent response instead of every intermediate row.
            long quietWindow=remoteHistoryNavigation?30_000_000L:12_000_000L;
            long maximumWait=remoteHistoryNavigation?90_000_000L:48_000_000L;
            if(quiet<quietWindow&&age<maximumWait)return;
            // Legacy applications do not always fence their redraw. Keep the
            // previous frame through a temporary clear/partially refilled page.
            if(completedLiveFrame!=null&&age<150_000_000L&&quiet<80_000_000L){
                int previous=0,current=0;for(int cp:completedLiveFrame.characters())if(cp>32)previous++;
                for(int r=0;r<screen.rows();r++)for(int c=0;c<screen.cols();c++)if(screen.charAt(r,c)>32)current++;
                if(previous>20&&current<previous/2)return;
            }
        }
        publishLiveFrame();
    }
    private void appendHistory(TerminalHistory.Row row){history.append(row);}
    private java.util.List<TerminalHistory.Row> ordinaryRows(){
        int last=-1;for(int row=0;row<screen.rows();row++)if(!screen.rowText(row).isBlank())last=row;
        java.util.List<TerminalHistory.Row> rows=new java.util.ArrayList<>(last+1);
        for(int row=0;row<=last;row++){
            int[] chars=new int[screen.cols()];String[] pens=new String[chars.length];
            for(int col=0;col<chars.length;col++){chars[col]=screen.charAt(row,col);pens[col]=screen.penAt(row,col);}
            rows.add(TerminalHistory.Row.cells(chars,pens,screen.wrapped(row)));
        }
        return rows;
    }
    private void updateOrdinaryTail(){if(history!=null&&!realtimeHistory(screen))history.setPendingTail(ordinaryRows());}
    private void beginRemoteHistory(){remoteHistoryNavigation=true;}
    private void flushPendingCapture(long now){
        synchronized(screen){
            if(capturePending&&history!=null&&!realtimeHistory(screen)&&
                    (now-lastOutputAt>=40_000_000L||now-captureSince>=250_000_000L)){
                updateOrdinaryTail();capturePending=false;
            }
        }
    }
    private void dispatchRemoteWheel(long now){
        if(!remoteWheelQueue.pending())return;
        KeyInput current=input;
        if(!accepting||current==null){remoteWheelQueue.cancel();return;}
        RemoteWheelQueue.Batch batch=remoteWheelQueue.peek(now,wheelProtocol.mode());
        if(batch==null||!current.offerNavigation(batch.keys()))return;
        beginRemoteHistory();remoteWheelQueue.sent(batch,now);
        if(!followLive)followLatest();
        // Do not repaint an unchanged screen for a wheel request. The arriving
        // remote frame invalidates the view; keyboard/local editing stays free.
    }
    private boolean realtimeHistory(ScreenModel model){return remoteHistoryNavigation||model.altScreen()||model.synchronizedOutput()||!localEditing&&interactiveRedraw;}
    boolean localEditing(){return localEditing;}
    int[] gridSize(){synchronized(screen){return new int[]{screen.cols(),screen.rows()};}}
    void flushHistory()throws Exception{synchronized(screen){updateOrdinaryTail();}if(history!=null)history.force();}
    void scrollHistory(long lines){if(history==null)return;showHistory(viewportStart()+lines);}
    void followLatest(){wheelPositionValid=false;scrollOffset=0;followLive=true;invalidateFrame();updateHistoryBar();}
    void showHistory(long start){remoteWheelQueue.cancel();wheelPositionValid=false;scrollOffset=0;moveHistory(start);}
    private void moveHistory(long start){if(history==null)return;viewportStart=Math.max(0,Math.min(start,history.count()));followLive=viewportStart>=history.count();invalidateFrame();loadHistoryPage();updateHistoryBar();}
    private void wheelScroll(double rows){
        if(history==null||rows==0)return;
        if(!wheelPositionValid)wheelPosition=viewportStart()+scrollOffset/(double)Math.max(1,cellHeight);
        wheelPosition=Math.max(0,Math.min(history.count(),wheelPosition+rows));wheelPositionValid=true;
        long first=(long)Math.floor(wheelPosition);
        moveHistory(first);
        scrollOffset=followLive?0:(int)Math.round((wheelPosition-first)*cellHeight);
        dirty=true;
    }
    private void loadHistoryPage(){
        if(followLive||history==null||closed)return;
        long start=viewportStart,count=history.count();int rows;synchronized(screen){rows=screen.rows()+1;}
        int needed=(int)Math.min(rows,Math.max(0,count-start));
        if(!historyPageStale&&pageStart<=start&&pageStart>=0&&pageStart+historyPage.size()>=start+needed){
            if(displayStart!=start){displayStart=start;invalidateFrame();}return;
        }
        if(historyLoading)return;
        // A single loader serializes requests. Do not time-throttle the retry
        // after a stale page completes: the viewport may have moved while the
        // read was in flight and no later wheel event is guaranteed to arrive.
        historyLoading=true;
        long from=Math.max(0,start-rows),to=Math.min(count,start+rows*3L),requestedRevision=history.tailRevision();
        historyReader.execute(()->{
            java.util.List<ScreenModel> page=new java.util.ArrayList<>();Exception failure=null;
            try{for(long at=from;at<to;at++){
                boolean stable=at<history.stableCount();ScreenModel model=stable?decodedHistory.get(at):null;
                if(model==null){TerminalHistory.Row row=history.read(at);if(row==null)break;model=row.model();if(stable)decodedHistory.put(at,model);}
                page.add(model);
            }}catch(Exception e){failure=e;}
            Exception error=failure;
            SwingUtilities.invokeLater(()->{
                historyLoading=false;if(closed)return;
                if(error!=null){notice.accept(I18n.t("读取终端历史失败：","Failed to read terminal history: ")+error.getMessage());return;}
                if(!followLive&&viewportStart>=from&&viewportStart<from+page.size()){
                    pageStart=from;displayStart=viewportStart;historyPage=java.util.List.copyOf(page);historyPageStale=history.tailRevision()!=requestedRevision;invalidateFrame();
                }
                loadHistoryPage();
            });
        });
    }
    private void updateHistoryBar(){
        if(history==null)return;
        if(history.omitted()>0&&lastOmitted==0){lastOmitted=history.omitted();notice.accept(I18n.t("终端历史写入缓冲过载，部分展示行省略；远端输出仍在全量输入输出历史中。","Terminal history buffer overflowed and some display rows were skipped; remote output is still in the full input/output history."));}
long revision=history.tailRevision();if(revision!=observedTailRevision){observedTailRevision=revision;if(pageStart>=0&&pageStart+historyPage.size()>Math.max(0,history.stableCount()-512))historyPageStale=true;}
        long count=history.count();int extent; synchronized(screen){extent=Math.max(1,screen.rows());}
        long max=count+extent;int maximum=(int)Math.min(Integer.MAX_VALUE,max);
        int value=(int)(max<=Integer.MAX_VALUE?viewportStart():Math.round(viewportStart()*(double)(maximum-extent)/Math.max(1,count)));
        adjustingBar=true;try{historyBar.setValues(value,extent,0,maximum);historyBar.setUnitIncrement(3);historyBar.setBlockIncrement(Math.max(1,extent-2));}finally{adjustingBar=false;}
        if(!followLive)loadHistoryPage();
    }
    private int visibleChar(int row,int col,long count){
        if(followLive||history==null||historyPage.isEmpty())return screen.charAt(row,col);
        long at=displayStart+row;if(at>=count)return screen.charAt((int)(at-count),col);
        int cached=(int)(at-pageStart);return cached>=0&&cached<historyPage.size()?historyPage.get(cached).charAt(0,col):' ';
    }
    private String visiblePen(int row,int col,long count){
        if(followLive||history==null||historyPage.isEmpty())return screen.penAt(row,col);
        long at=displayStart+row;if(at>=count)return screen.penAt((int)(at-count),col);
        int cached=(int)(at-pageStart);return cached>=0&&cached<historyPage.size()?historyPage.get(cached).penAt(0,col):"";
    }
    void beginLayoutResize(){layoutResizing=true;}
    void endLayoutResize(){layoutResizing=false;resizeTerminal();}
    JScrollBar historyScrollBar(){return historyBar;}

    public SwingTerminal() {
        surface.add(this,BorderLayout.CENTER);surface.add(historyBar,BorderLayout.EAST);historyBar.setToolTipText(I18n.t("本地长期历史 · Shift+滚轮 / Shift+PageUp · Ctrl+End 回到当前应用画面","Local long-term history · Shift+wheel / Shift+PageUp · Ctrl+End returns to the live screen"));
        historyBar.addAdjustmentListener(e->{if(!adjustingBar&&history!=null){long count=history.count();int range=historyBar.getMaximum()-historyBar.getVisibleAmount();showHistory(range<=0?count:Math.round(e.getValue()*(double)count/range));}});
        addMouseWheelListener(e->{
            RemoteWheelProtocol.Mode mode=wheelProtocol.mode();
            boolean interactive=alternateScreen||!localEditing&&(interactiveRedraw||mode.supported());
            boolean remote=e.isAltDown()||autoRemoteWheel&&interactive&&followLive;
            if(remote&&!e.isShiftDown()&&accepting){
                int columns=cachedFrame==null?Math.max(1,getWidth()/cellWidth):cachedFrame.columns();
                int rows=cachedFrame==null?Math.max(1,getHeight()/cellHeight):Math.max(1,cachedFrame.rows()-1);
                remoteWheelQueue.add(e.getPreciseWheelRotation(),System.nanoTime(),mode,e.isAltDown(),pageKeysForRemoteWheel,Math.max(1,Math.min(columns,e.getX()/cellWidth+1)),Math.max(1,Math.min(rows,e.getY()/cellHeight+1)),e.isControlDown());
            }else{
                remoteWheelQueue.cancel();wheelScroll(e.getPreciseWheelRotation()*(e.getScrollType()==MouseWheelEvent.WHEEL_BLOCK_SCROLL?Math.max(1,getHeight()/cellHeight-2):Math.max(1,e.getScrollAmount())));
            }
            e.consume();
        });
        setOpaque(true);setDoubleBuffered(true);
        setFont(DesktopFonts.monospace(16));
        setBackground(new Color(0x0d1424)); setForeground(new Color(0xd7e2f2));
        setFocusable(true); setFocusTraversalKeysEnabled(false); enableInputMethods(true);
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                // A heavyweight Canvas can still be the AWT focus owner while
                // a Swing menu is active. In that case the menu may already
                // have consumed Enter (or another key) to activate an item.
                // Respect that ownership before translating physical keys to
                // SSH input; otherwise the menu's Enter can become a remote
                // blank command.
                if(e.isConsumed()||MenuSelectionManager.defaultManager().getSelectedPath().length>0)return;
                remoteWheelQueue.cancel();
                // The heavyweight Canvas does not run JComponent's key-binding
                // dispatch. Only explicit workspace shortcuts take precedence;
                // unmodified Ctrl keys and Alt letters still belong to SSH.
                if(e.isControlDown()&&e.isShiftDown()&&!e.isAltDown()&&
                        (e.getKeyCode()==KeyEvent.VK_L||e.getKeyCode()==KeyEvent.VK_E||e.getKeyCode()==KeyEvent.VK_H||e.getKeyCode()==KeyEvent.VK_N)&&
                        SwingUtilities.processKeyBindings(e)){e.consume();return;}
                if(!composition.isEmpty()&&!e.isControlDown()&&!e.isMetaDown())return;
                if(e.isShiftDown()&&(e.getKeyCode()==KeyEvent.VK_PAGE_UP||e.getKeyCode()==KeyEvent.VK_PAGE_DOWN)){scrollHistory((e.getKeyCode()==KeyEvent.VK_PAGE_UP?-1:1)*Math.max(1,screen.rows()-2));e.consume();return;}
                if(e.isControlDown()&&e.getKeyCode()==KeyEvent.VK_END){followLatest();e.consume();return;}
                if ((e.isControlDown() || e.isMetaDown()) && e.isShiftDown() && e.getKeyCode() == KeyEvent.VK_C) { copy(); e.consume(); return; }
                // Ctrl+C copies a selection and clears it, so the next Ctrl+C
                // reaches SSH as an interrupt again.
                if ((e.isControlDown() || e.isMetaDown()) && !e.isShiftDown() && !e.isAltDown() && e.getKeyCode() == KeyEvent.VK_C && hasSelection()) {
                    copy(); selectionStart = selectionEnd = null; dirty = true; e.consume(); return;
                }
                if ((e.isControlDown() || e.isMetaDown()) && e.isShiftDown() && e.getKeyCode() == KeyEvent.VK_V) { paste(); e.consume(); return; }
                if(e.isShiftDown()&&e.getKeyCode()==KeyEvent.VK_INSERT){paste();e.consume();return;}
                // Encode Alt letters on KEY_PRESSED: Windows menu processing can
                // swallow KEY_TYPED. AltGr remains a text-input modifier.
                if(e.isAltDown()&&!e.isAltGraphDown()&&!e.isControlDown()&&e.getKeyCode()>=KeyEvent.VK_A&&e.getKeyCode()<=KeyEvent.VK_Z){
                    char letter=(char)((e.isShiftDown()?'A':'a')+e.getKeyCode()-KeyEvent.VK_A);sendKeys("\u001b"+letter);e.consume();return;
                }
                String seq = switch (e.getKeyCode()) {
                    case KeyEvent.VK_ENTER -> "\r"; case KeyEvent.VK_BACK_SPACE -> "\u007f";
                    case KeyEvent.VK_TAB -> e.isShiftDown() ? "\u001b[Z" : "\t";
                    case KeyEvent.VK_ESCAPE -> "\u001b";
                    case KeyEvent.VK_UP -> arrow('A'); case KeyEvent.VK_DOWN -> arrow('B');
                    case KeyEvent.VK_RIGHT -> arrow('C'); case KeyEvent.VK_LEFT -> arrow('D');
                    case KeyEvent.VK_HOME -> "\u001b[H"; case KeyEvent.VK_END -> "\u001b[F";
                    case KeyEvent.VK_DELETE -> "\u001b[3~"; case KeyEvent.VK_INSERT -> "\u001b[2~";
                    case KeyEvent.VK_PAGE_UP -> "\u001b[5~"; case KeyEvent.VK_PAGE_DOWN -> "\u001b[6~";
                    case KeyEvent.VK_F1 -> "\u001bOP"; case KeyEvent.VK_F2 -> "\u001bOQ";
                    case KeyEvent.VK_F3 -> "\u001bOR"; case KeyEvent.VK_F4 -> "\u001bOS";
                    case KeyEvent.VK_F5 -> "\u001b[15~"; case KeyEvent.VK_F6 -> "\u001b[17~";
                    case KeyEvent.VK_F7 -> "\u001b[18~"; case KeyEvent.VK_F8 -> "\u001b[19~";
                    case KeyEvent.VK_F9 -> "\u001b[20~"; case KeyEvent.VK_F10 -> "\u001b[21~";
                    case KeyEvent.VK_F11 -> "\u001b[23~"; case KeyEvent.VK_F12 -> "\u001b[24~";
                    default -> null;
                };
                if (seq == null && e.isControlDown() && !e.isAltDown()) {
                    int k = e.getKeyCode();
                    if (k >= KeyEvent.VK_A && k <= KeyEvent.VK_Z) seq = String.valueOf((char)(k - KeyEvent.VK_A + 1));
                    else if (k == KeyEvent.VK_CLOSE_BRACKET) seq = "\u001d";
                    else if (k == KeyEvent.VK_SPACE) seq = "\u0000";
                }
                if (seq != null) { sendKeys(seq,e.getKeyCode()==KeyEvent.VK_ENTER?"terminal-enter":"terminal-key"); e.consume(); }
            }
            @Override public void keyTyped(KeyEvent e) {
                if(e.isConsumed()||MenuSelectionManager.defaultManager().getSelectedPath().length>0)return;
                if(!composition.isEmpty())return;
                boolean altLetter=e.isAltDown()&&!e.isAltGraphDown()&&Character.toUpperCase(e.getKeyChar())>='A'&&Character.toUpperCase(e.getKeyChar())<='Z';
                if (!e.isConsumed() && !altLetter && (!e.isControlDown() || e.isAltGraphDown()) && !e.isMetaDown() && e.getKeyChar() >= 32 && e.getKeyChar() != 127)
                    sendKeys((e.isAltDown()&&!e.isAltGraphDown() ? "\u001b" : "") + e.getKeyChar());
            }
        });
        addInputMethodListener(new InputMethodListener() {
            @Override public void inputMethodTextChanged(InputMethodEvent event) {
                StringBuilder text=new StringBuilder();var iterator=event.getText();
                if(iterator!=null)for(char c=iterator.first();c!=AttributedCharacterIterator.DONE;c=iterator.next())text.append(c);
                int committed=Math.min(event.getCommittedCharacterCount(),text.length());
                if(!followLive&&!text.isEmpty())followLatest();
                composition=text.substring(committed);compositionCaret=event.getCaret()==null?composition.length():Math.min(composition.length(),event.getCaret().getInsertionIndex());
                if(committed>0)sendKeys(text.substring(0,committed));
                dirty=true;repaintSurface();event.consume();
            }
            @Override public void caretPositionChanged(InputMethodEvent event) {compositionCaret=event.getCaret()==null?composition.length():Math.min(composition.length(),event.getCaret().getInsertionIndex());dirty=true;event.consume();}
        });
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                remoteWheelQueue.cancel();
                requestFocusInWindow();wheelPositionValid=false;scrollOffset=0;
                if (SwingUtilities.isLeftMouseButton(e)) {
                    Point at=documentCell(e);
                    if(!e.isShiftDown()||selectionStart==null)selectionStart=at;
                    selectionEnd=at;dirty=true;
                }
                if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
                    popupMenu().show(SwingTerminal.this,e.getX(),e.getY());
                }
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) { selectionEnd = documentCell(e); dirty = true; }
        });
        addComponentListener(new ComponentAdapter() { @Override public void componentResized(ComponentEvent e) { resizeTerminal(); } });
        addFocusListener(new FocusAdapter(){public void focusGained(FocusEvent e){cursorEpoch=System.nanoTime();cursorOn=true;dirty=true;}public void focusLost(FocusEvent e){remoteWheelQueue.cancel();composition="";compositionCaret=0;dirty=true;}});
        redraw = new Timer(8,e->{
            if(!isShowing())return;
            long now=System.nanoTime();
            dispatchRemoteWheel(now);
            synchronized(screen){presentPending(now);}
            if(now-lastBarUpdate>=32_000_000L){lastBarUpdate=now;updateHistoryBar();}
            boolean phase=cursorPhase(now-cursorEpoch);
            if(terminalFocus()&&phase!=cursorOn){cursorOn=phase;if(!dirty&&!frameDirty.get()&&cachedFrame!=null&&cachedFrame.cursorVisible())repaintSurface();}
            // The native surface can lose its image while the window is minimized,
            // covered or the GPU is reset. Present the current frame now and then,
            // so the terminal never stays black until the next remote output.
            if(nativeCanvas!=null&&now-lastPresented>=500_000_000L)dirty=true;
            if(dirty||frameDirty.get()){dirty=false;lastPresented=now;repaintSurface();}
        });redraw.setCoalesce(true);redraw.start();
        addHierarchyListener(e->{if((e.getChangeFlags()&HierarchyEvent.SHOWING_CHANGED)!=0&&isShowing())invalidateFrame();});
        resizer.scheduleWithFixedDelay(() -> {
            flushPendingCapture(System.nanoTime());
            if(layoutResizing)return;
            Size size = pendingResize.getAndSet(null); Terminal t = terminal;
            if(size!=null&&t!=null){
                if(layoutResizing){pendingResize.compareAndSet(null,size);return;}
                try{t.setSize(size);t.raise(Terminal.Signal.WINCH);}catch(Exception ignored){}
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
        if(NativeGraphics.available()){
            setLayout(new BorderLayout());
            nativeCanvas=new NativeTextCanvas(this::nativeFrame,this::fallbackRenderer);
            nativeCanvas.setInputRequests(this);
            nativeCanvas.setBackground(getBackground());
            for(KeyListener listener:getKeyListeners())nativeCanvas.addKeyListener(listener);
            for(MouseListener listener:getMouseListeners())nativeCanvas.addMouseListener(listener);
            for(MouseMotionListener listener:getMouseMotionListeners())nativeCanvas.addMouseMotionListener(listener);
            for(MouseWheelListener listener:getMouseWheelListeners())nativeCanvas.addMouseWheelListener(listener);
            for(InputMethodListener listener:getInputMethodListeners())nativeCanvas.addInputMethodListener(listener);
            for(FocusListener listener:getFocusListeners())nativeCanvas.addFocusListener(listener);
            add(nativeCanvas,BorderLayout.CENTER);
        }
    }
    private boolean terminalFocus(){return hasFocus()||nativeCanvas!=null&&nativeCanvas.hasFocus();}
    @Override public InputMethodRequests getInputMethodRequests(){return this;}
    private Point compositionCell(){return compositionCell(compositionCaret);}
    private Point compositionCell(int offset){
        synchronized(screen){int col=screen.col()+compositionColumns(offset);return new Point(col%screen.cols(),Math.min(screen.rows()-1,screen.row()+col/screen.cols()));}
    }
    private int compositionColumns(int end){int columns=0;for(int i=0;i<Math.min(end,composition.length());){int cp=composition.codePointAt(i);columns+=Math.max(1,org.jline.utils.WCWidth.wcwidth(cp));i+=Character.charCount(cp);}return columns;}
    @Override public Rectangle getTextLocation(TextHitInfo hit){
        Point caret=compositionCell(hit==null?compositionCaret:hit.getInsertionIndex()),origin=new Point();Component target=nativeCanvas==null?this:nativeCanvas;
        try{origin=target.getLocationOnScreen();}catch(IllegalComponentStateException ignored){}
        return new Rectangle(origin.x+caret.x*cellWidth,origin.y+caret.y*cellHeight,0,cellHeight);
    }
    @Override public TextHitInfo getLocationOffset(int x,int y){return null;}
    @Override public int getInsertPositionOffset(){return 0;}
    @Override public AttributedCharacterIterator getCommittedText(int begin,int end,AttributedCharacterIterator.Attribute[] attributes){if(begin!=0||end!=0)throw new IllegalArgumentException("Terminal has no local committed document");return new AttributedString("").getIterator();}
    @Override public int getCommittedTextLength(){return 0;}
    @Override public AttributedCharacterIterator cancelLatestCommittedText(AttributedCharacterIterator.Attribute[] attributes){return null;}
    @Override public AttributedCharacterIterator getSelectedText(AttributedCharacterIterator.Attribute[] attributes){try{return new AttributedString(selectionStart==null||selectionStart.equals(selectionEnd)?"":selectedText()).getIterator();}catch(IOException e){return new AttributedString("").getIterator();}}
    @Override public boolean requestFocusInWindow(){return nativeCanvas==null?super.requestFocusInWindow():nativeCanvas.requestFocusInWindow();}
    private void repaintSurface(){if(nativeCanvas==null)repaint();else nativeCanvas.repaint();}
    private void fallbackRenderer(String reason){
        if(nativeCanvas==null)return;remove(nativeCanvas);nativeCanvas=null;revalidate();invalidateFrame();repaint();
        notice.accept(I18n.t("原生显示未能启用，已回退兼容显示：","Native display could not start; using compatible display: ")+reason);
    }
    private NativeGraphics.Frame nativeFrame(){
        refreshCachedFrame();
        PaintFrame frame=cachedFrame;int columns=frame.columns(),length=frame.characters().length;
        SelectionRange selection=selectionRange(frame);int from=selection.from(),to=selection.to();
        int lo=Math.min(from,to),hi=Math.max(from,to);int[] fg=new int[length],bg=new int[length];
        for(int i=0;i<length;i++){
            Color[] pair=nativeColors.computeIfAbsent(frame.pens()[i],SwingTerminal::colors);
            fg[i]=pair[0].getRGB();bg[i]=from>=0&&from!=to&&i>=lo&&i<=hi?0x345170:pair[1].getRGB();
        }
        int offset=!followLive&&frame.firstLine()==viewportStart?scrollOffset:0;
        int[] chars=frame.characters();int cx=frame.cursorColumn(),cy=frame.cursorRow();
        if(!composition.isEmpty()&&followLive){
            chars=chars.clone();int at=cy*columns+cx;
            for(int i=0;i<composition.length()&&at<chars.length;){int cp=composition.codePointAt(i),span=Math.max(1,org.jline.utils.WCWidth.wcwidth(cp));for(int n=0;n<span&&at<chars.length;n++,at++){chars[at]=n==0?cp:-1;fg[at]=0xb5f0dc;bg[at]=0x23334b;}i+=Character.charCount(cp);}
            Point caret=compositionCell();cx=caret.x;cy=caret.y;
        }
        return new NativeGraphics.Frame(columns,frame.rows(),chars,fg,bg,cellWidth,cellHeight,ascent,offset,
                cx,cy,frame.cursorVisible()&&terminalFocus()&&(cursorOn||!composition.isEmpty()),getFont().getSize2D(),getBackground().getRGB());
    }
    public void setNotice(Consumer<String> notice) { this.notice = notice;notice.accept(I18n.t("显示后端：","Display backend: ")+NativeGraphics.status()); }
    public void accepting(boolean value) { accepting = value; }
    public void endInput() { accepting=false; KeyInput current=input; if(current!=null)current.close(); }
    private String arrow(char c) { return "\u001b" + (appCursor ? "O" : "[") + c; }
    public Terminal attach() throws IOException {
        input = new KeyInput(); accepting = false;
        wheelProtocol.reset();
        synchronized (screen) { decoder.reset(); pending.clear(); queryTail = ""; appCursor = false; bracketedPaste = false;interactiveRedraw=false;remoteHistoryNavigation=false;alternateScreen=false;presentationPending=false;redrawSince=synchronizedSince=0; }
        KeyInput current = input;
        terminal = TerminalBuilder.builder().system(false).streams(current, output()).type("xterm-256color")
                .encoding(StandardCharsets.UTF_8).size(new Size(Math.max(20, getWidth()/cellWidth),Math.max(5,getHeight()/cellHeight))).build();
        return terminal;
    }
    void setInputAudit(Consumer<String> audit){inputAudit=audit==null?message->{}:audit;}
    public boolean sendKeys(String text){return sendKeys(text,"terminal-input");}
    boolean sendKeys(String text,String origin) {
        if(SwingUtilities.isEventDispatchThread())remoteWheelQueue.cancel();
        boolean onlyBreaks=text.chars().allMatch(ch->ch=='\r'||ch=='\n');
        if(text.indexOf('\r')>=0||text.indexOf('\n')>=0)inputAudit.accept(I18n.t("终端提交按键；来源=","Terminal submit key; source=")+origin+I18n.t("，CR=",", CR=")+text.chars().filter(ch->ch=='\r').count()+I18n.t("，LF=",", LF=")+text.chars().filter(ch->ch=='\n').count()+I18n.t("，仅换行=",", LF only=")+onlyBreaks+I18n.t("，字符数=",", chars=")+text.length());
        if (!accepting || input == null) { notice.accept(I18n.t("连接未就绪；请在本地编辑窗保留命令。","Connection is not ready; keep the command in the local editor.")); return false; }
        boolean historyKey=text.contains("\u001b[5~")||text.contains("\u001b[6~");
        if(historyKey)beginRemoteHistory();
        if (!input.offer(text)) { notice.accept(I18n.t("输入队列已满；此次输入未发送，请减小粘贴量。","Input queue is full; this input was not sent. Paste less at once.")); return false; }
        if(!historyKey&&remoteHistoryNavigation)remoteHistoryNavigation=false;
        if(!followLive)SwingUtilities.invokeLater(this::followLatest);selectionStart = selectionEnd = null; cursorEpoch=System.nanoTime();cursorOn=true;dirty = true; return true;
    }
    public void display(String text) { synchronized (screen) { screen.feed(text); handleQueries(text);markOutputDirty(); } }
    public String screenText() {
        synchronized (screen) {
            StringBuilder text = new StringBuilder();
            for (int r = 0; r < screen.rows(); r++) text.append(screen.rowText(r).stripTrailing()).append('\n');
            return text.toString();
        }
    }
    String historyText(int lastRows)throws IOException{
        TerminalHistory source=history;if(source!=null)return source.plainText(lastRows,1_000_000);
        String current=screenText();if(lastRows<=0)return current;String[] rows=current.split("\\R",-1);return String.join("\n",java.util.Arrays.copyOfRange(rows,Math.max(0,rows.length-lastRows),rows.length));
    }
    private OutputStream output() {
        return new OutputStream() {
            @Override public void write(int b) throws IOException { write(new byte[]{(byte)b}, 0, 1); }
            @Override public void write(byte[] bytes, int offset, int length) {
                synchronized (screen) {
                    while (length > 0) {
                        int count = Math.min(length, pending.remaining()); pending.put(bytes,offset,count); offset += count; length -= count;
                        pending.flip(); CharBuffer chars = CharBuffer.allocate(32768); decoder.decode(pending,chars,false); pending.compact(); chars.flip();
                        String text = chars.toString(); screen.feed(text); handleQueries(text); markOutputDirty();
                    }
                }
            }
        };
    }
    private void handleQueries(String text) {
        String data = queryTail + text;
        if(!localEditing&&REDRAW_PATTERN.matcher(data).find())interactiveRedraw=true;
        int old = queryTail.length();
        for (int i = 0; i < data.length(); i++) {
            if (i + 4 > old && data.startsWith("\u001b[6n", i) && input != null) input.offer("\u001b["+(screen.row()+1)+";"+(screen.col()+1)+"R");
            if (i + 3 > old && data.startsWith("\u001b[c", i) && input != null) input.offer("\u001b[?1;2c");
            if(i+9>old&&data.startsWith("\u001b[?2026$p",i)&&input!=null)input.offer("\u001b[?2026;"+(screen.synchronizedOutput()?1:2)+"$y");
            if (i + 5 > old && data.startsWith("\u001b[?1h",i)) appCursor = true;
            if (i + 5 > old && data.startsWith("\u001b[?1l",i)) appCursor = false;
            if (i + 8 > old && data.startsWith("\u001b[?2004h",i)) bracketedPaste = true;
            if (i + 8 > old && data.startsWith("\u001b[?2004l",i)) bracketedPaste = false;
        }
        queryTail = data.substring(Math.max(0, data.length()-16));
    }
    private void resizeTerminal() {
        if(layoutResizing)return;
        FontMetrics fm = getFontMetrics(getFont()); cellWidth = fm.charWidth('M');int topPadding=Math.max(2,fm.getMaxAscent()-fm.getAscent()+2);cellHeight=fm.getHeight()+topPadding;ascent=fm.getAscent()+topPadding;
        cursorCapHeight=Math.max(2,(int)Math.ceil(getFont().createGlyphVector(fm.getFontRenderContext(),"H").getVisualBounds().getHeight()));
        int cols = Math.max(20, getWidth()/cellWidth), rows = Math.max(5,getHeight()/cellHeight);
        synchronized (screen) { if(screen.cols()==cols&&screen.rows()==rows)return;screen.resize(cols,rows);publishLiveFrame();invalidateFrame(); }
        if(!followLive){pageStart=-1;historyPage=java.util.List.of();loadHistoryPage();}
        pendingResize.set(new Size(cols,rows));
    }
    private Point cell(MouseEvent e) { return new Point(Math.max(0,e.getX()/cellWidth), Math.max(0,e.getY()/cellHeight)); }
    private Point documentCell(MouseEvent e){
        Point cell=cell(e);int rows,columns;synchronized(screen){rows=screen.rows();columns=screen.cols();}
        int row=cell.y;if(!followLive)row=Math.max(0,(e.getY()+scrollOffset)/Math.max(1,cellHeight));
        long first=followLive?(history==null?0:history.count()):displayStart;
        return new Point(Math.min(columns-1,cell.x),(int)Math.min(Integer.MAX_VALUE,first+Math.min(rows-1,row)));
    }
    private record SelectionRange(int from,int to){}
    private SelectionRange selectionRange(PaintFrame frame){
        if(selectionStart==null||selectionEnd==null||selectionStart.equals(selectionEnd))return new SelectionRange(-1,-1);
        Point start=compare(selectionStart,selectionEnd)<=0?selectionStart:selectionEnd;
        Point end=start==selectionStart?selectionEnd:selectionStart;
        long base=frame.firstLine(),length=(long)frame.columns()*frame.rows();
        long from=((long)start.y-base)*frame.columns()+start.x;
        long to=((long)end.y-base)*frame.columns()+end.x;
        if(to<0||from>=length)return new SelectionRange(-1,-1);
        return new SelectionRange((int)Math.max(0,from),(int)Math.min(length-1,to));
    }
    boolean hasSelection(){return selectionStart!=null&&selectionEnd!=null&&!selectionStart.equals(selectionEnd);}
    private static int compare(Point a,Point b){int row=Integer.compare(a.y,b.y);return row!=0?row:Integer.compare(a.x,b.x);}
    private String selectedText()throws IOException{
        if(selectionStart==null||selectionEnd==null||selectionStart.equals(selectionEnd))return displayedText();
        Point start=compare(selectionStart,selectionEnd)<=0?selectionStart:selectionEnd;
        Point end=start==selectionStart?selectionEnd:selectionStart;
        long count=history==null?0:history.count();int columns;synchronized(screen){columns=screen.cols();}
        StringBuilder text=new StringBuilder();
        for(int row=start.y;row<=end.y;row++){
            int firstColumn=row==start.y?start.x:0,lastColumn=row==end.y?end.x:columns-1;
            ScreenModel historical=null;int screenRow=-1;boolean wrapped=false;
            if(row<count){TerminalHistory.Row stored=history.read(row);if(stored!=null){historical=stored.model();wrapped=stored.wrapped();}}
            else screenRow=(int)(row-count);
            StringBuilder line=new StringBuilder();
            synchronized(screen){
                for(int col=firstColumn;col<=lastColumn;col++){
                    int cp=historical!=null?historical.charAt(0,col):screenRow>=0&&screenRow<screen.rows()?screen.charAt(screenRow,col):' ';
                    if(cp>=0)line.appendCodePoint(cp);
                }
                if(historical==null)wrapped=screen.wrapped(screenRow);
            }
            // An automatically wrapped row continues on the next one: no line
            // break, and its trailing spaces are part of the text.
            wrapped&=lastColumn>=(historical!=null?historical.cols():columns)-1;
            text.append(wrapped?line:line.toString().stripTrailing());
            if(row<end.y&&!wrapped)text.append('\n');
        }
        return text.toString();
    }
    void setClipboard(java.util.function.Supplier<Clipboard> supplier){clipboard=supplier;}
    JPopupMenu popupMenu(){JPopupMenu menu=new JPopupMenu();JMenuItem copy=new JMenuItem(I18n.t("复制选区 / 当前屏幕","Copy selection / screen"));copy.addActionListener(e->{MenuSelectionManager.defaultManager().clearSelectedPath();copy();});menu.add(copy);JMenuItem paste=new JMenuItem(I18n.t("粘贴","Paste"));paste.addActionListener(e->{MenuSelectionManager.defaultManager().clearSelectedPath();paste();});menu.add(paste);menu.addSeparator();JMenuItem latest=new JMenuItem(I18n.t("回到最新输出","Jump to latest output"));latest.addActionListener(e->followLatest());menu.add(latest);return menu;}
    private void copy(){try{ClipboardActions.putText(clipboard==null?null:clipboard.get(),selectedText());}catch(Exception e){notice.accept(I18n.t("复制失败，请重试：","Copy failed, try again: ")+e.getMessage());}}
    private void paste(){try{pasteText(ClipboardActions.text(clipboard==null?null:clipboard.get()));}catch(Exception e){notice.accept(I18n.t("粘贴失败，请重试：","Paste failed, try again: ")+e.getMessage());}}
    boolean pasteText(String original){
        if(original.length()>65536){notice.accept(I18n.t("一次粘贴最多 64 KiB","Paste at most 64 KiB at once"));return false;}
        if(!accepting){notice.accept(I18n.t("连接未就绪，粘贴未发送；剪贴板内容保留。","Connection is not ready; paste not sent. The clipboard is unchanged."));return false;}
        String text=MultilineInput.normalize(original);while(text.endsWith("\n"))text=text.substring(0,text.length()-1);
        if(text.isEmpty())return true;
        if(MultilineInput.unsupportedControls(text)){notice.accept(I18n.t("粘贴内容包含控制字符，请检查后重试。","Pasted text contains control characters; check it and try again."));return false;}
        Terminal target=terminal;KeyInput targetInput=input;
        text=MultilineInput.prepare(this,text);if(text==null)return false;
        if(terminal!=target||input!=targetInput){notice.accept(I18n.t("连接已变化，请重新粘贴。","Connection changed; paste again."));return false;}
        boolean sent=sendKeys(pasteSequence(text),"clipboard-paste");if(sent)SwingUtilities.invokeLater(this::requestFocusInWindow);return sent;
    }
    String pasteSequence(String text){return localEditing||bracketedPaste?"\u001b[200~"+text+"\u001b[201~":text;}
    private void invalidateFrame(){frameDirty.set(true);dirty=true;}
    private void markOutputDirty(){
        alternateScreen=screen.altScreen();
        if(realtimeHistory(screen))capturePending=false;
        else if(history!=null){if(!capturePending)captureSince=System.nanoTime();capturePending=true;}
        lastOutputAt=System.nanoTime();presentationPending=true;
        if(remoteHistoryNavigation&&redrawSince==0)redrawSince=lastOutputAt;
        if(screen.synchronizedOutput()&&synchronizedSince==0)synchronizedSince=lastOutputAt;
        if(!screen.synchronizedOutput()&&(localEditing||redrawSince==0))publishLiveFrame();
        if(!followLive&&(history==null||historyPage.isEmpty()||displayStart+screen.rows()>history.count()))invalidateFrame();
    }
    static record PaintFrame(int columns,int rows,int[] characters,String[] pens,int cursorRow,int cursorColumn,boolean cursorVisible,long firstLine){}
    private PaintFrame livePaintFrame(){
        int columns=screen.cols(),rows=screen.rows()+1;int[] chars=new int[columns*rows];String[] pens=new String[chars.length];
        for(int r=0;r<rows;r++)for(int c=0;c<columns;c++){int i=r*columns+c;chars[i]=screen.charAt(r,c);pens[i]=screen.penAt(r,c);}
        return new PaintFrame(columns,rows,chars,pens,screen.row(),screen.col(),screen.cursorVisible(),history==null?0:history.count());
    }
    private void refreshCachedFrame(){
        synchronized(screen){
            presentPending(System.nanoTime());
            if(frameDirty.getAndSet(false)||cachedFrame==null){
                if(followLive){if(completedLiveFrame==null)completedLiveFrame=livePaintFrame();cachedFrame=completedLiveFrame;}
                else cachedFrame=paintFrame();
            }
        }
    }
    private PaintFrame paintFrame(){
        long count=history==null?0:history.count();
        synchronized(screen){
            int columns=screen.cols(),rows=screen.rows()+1;int[] characters=new int[columns*rows];String[] pens=new String[characters.length];
            for(int r=0;r<rows;r++)for(int c=0;c<columns;c++){int at=r*columns+c;characters[at]=visibleChar(r,c,count);pens[at]=visiblePen(r,c,count);}
            return new PaintFrame(columns,rows,characters,pens,screen.row(),screen.col(),followLive&&screen.cursorVisible(),history==null?0:followLive||historyPage.isEmpty()?count:displayStart);
        }
    }
    @Override protected void paintComponent(Graphics graphics) {
        if(nativeCanvas!=null)return;
        // Immutable snapshots are rebuilt only when content changes. The cached
        // device-resolution rows survive caret blinks and pixel-scroll frames.
        refreshCachedFrame();
        PaintFrame frame=cachedFrame;int columns=frame.columns();
        SelectionRange selection=selectionRange(frame);int from=selection.from(),to=selection.to();
        Graphics2D g=(Graphics2D)graphics.create();
        try{
            // An unfinished read may still display the preceding page. Never
            // translate that old page by the new page's fractional offset.
            int offset=!followLive&&frame.firstLine()==viewportStart?scrollOffset:0;
            renderer.paint(g,this,frame,cellWidth,cellHeight,ascent,from,to,offset);
            if(!composition.isEmpty()&&followLive){
                DesktopFonts.textHints(g);g.setFont(getFont());int col=frame.cursorColumn(),row=frame.cursorRow();
                for(int i=0;i<composition.length();){int cp=composition.codePointAt(i),span=Math.max(1,org.jline.utils.WCWidth.wcwidth(cp));if(col+span>columns){col=0;row++;}g.setColor(new Color(0x23334b));g.fillRect(col*cellWidth,row*cellHeight,span*cellWidth,cellHeight);g.setColor(new Color(0xb5f0dc));g.drawString(new String(Character.toChars(cp)),col*cellWidth,row*cellHeight+ascent);g.drawLine(col*cellWidth,(row+1)*cellHeight-2,(col+span)*cellWidth-1,(row+1)*cellHeight-2);col+=span;i+=Character.charCount(cp);}
            }
            if(frame.cursorVisible()&&hasFocus()&&(cursorOn||!composition.isEmpty())){Point caret=composition.isEmpty()?new Point(frame.cursorColumn(),frame.cursorRow()):compositionCell();Rectangle cursor=cursorBounds(caret.x,caret.y,cellWidth,cellHeight,ascent,cursorCapHeight);g.setColor(new Color(0xb5d8d0));g.fillRect(cursor.x,cursor.y,cursor.width,cursor.height);}
            if(!followLive){DesktopFonts.textHints(g);g.setColor(new Color(0x23334b));g.fillRect(Math.max(0,getWidth()-250),4,246,24);g.setColor(new Color(0xcbd5e1));g.setFont(DesktopFonts.ui().deriveFont(11f));g.drawString(I18n.t("历史视图 · Ctrl+End 回到最新","History view · Ctrl+End for latest"),Math.max(4,getWidth()-240),21);}
        }finally{g.dispose();}
    }
    String displayedText(){long count=history==null?0:history.count();synchronized(screen){StringBuilder text=new StringBuilder();for(int r=0;r<screen.rows();r++){StringBuilder row=new StringBuilder();for(int c=0;c<screen.cols();c++){int cp=visibleChar(r,c,count);if(cp>=0)row.appendCodePoint(cp);}text.append(row.toString().stripTrailing()).append('\n');}return text.toString();}}
    private static final int[] PALETTE = {0x111827,0xf87171,0x6ee7b7,0xfbbf24,0x60a5fa,0xc084fc,0x67e8f9,0xd7e2f2,0x64748b,0xfca5a5,0xa7f3d0,0xfde68a,0x93c5fd,0xe9d5ff,0xa5f3fc,0xffffff};
    static Color[] colors(String pen) {
        Color fg = new Color(0xd7e2f2), bg = new Color(0x0d1424); boolean inverse = false;
        var matcher = SGR_PATTERN.matcher(pen);
        while (matcher.find()) {
            String[] parts = matcher.group(1).isEmpty() ? new String[]{"0"} : matcher.group(1).split(";",-1);
            for (int i=0;i<parts.length;i++) {
                int n; try { n = Integer.parseInt(parts[i].isEmpty()?"0":parts[i]); } catch (NumberFormatException e) { continue; }
                if (n == 0) { fg = new Color(0xd7e2f2); bg = new Color(0x0d1424); inverse = false; }
                else if (n == 7) inverse = true; else if (n == 27) inverse = false;
                else if (n == 39) fg = new Color(0xd7e2f2); else if (n == 49) bg = new Color(0x0d1424);
                else if ((n >= 30 && n <= 37) || (n >= 90 && n <= 97)) fg = new Color(PALETTE[n >= 90 ? n-90+8 : n-30]);
                else if ((n >= 40 && n <= 47) || (n >= 100 && n <= 107)) bg = new Color(PALETTE[n >= 100 ? n-100+8 : n-40]);
                else if ((n == 38 || n == 48) && i+2 < parts.length) {
                    try {
                        Color value = null;
                        if (parts[i+1].equals("5")) { value = indexed(Integer.parseInt(parts[i+2])); i += 2; }
                        else if (parts[i+1].equals("2") && i+4 < parts.length) { value = new Color(Integer.parseInt(parts[i+2]),Integer.parseInt(parts[i+3]),Integer.parseInt(parts[i+4])); i += 4; }
                        if (value != null) { if (n == 38) fg = value; else bg = value; }
                    } catch (IllegalArgumentException ignored) { }
                }
            }
        }
        return inverse ? new Color[]{bg,fg} : new Color[]{fg,bg};
    }
    private static Color indexed(int n) {
        if (n < 0 || n > 255) return new Color(PALETTE[7]);
        if (n < 16) return new Color(PALETTE[n]);
        if (n >= 232) { int v = 8+(n-232)*10; return new Color(v,v,v); }
        n -= 16; int[] levels = {0,95,135,175,215,255}; return new Color(levels[n/36],levels[n/6%6],levels[n%6]);
    }
    @Override public void close() { closed=true;redraw.stop();if(nativeCanvas!=null)nativeCanvas.release();renderer.close();resizer.shutdownNow();historyReader.shutdown();synchronized(screen){updateOrdinaryTail();screen.setHistoryListener(null);}if(history!=null)history.close();endInput(); }
    /** Atomic chunks ensure a rejected paste never turns into a partially executed command. */
    static final class KeyInput extends InputStream {
        private final BlockingQueue<byte[]> chunks = new ArrayBlockingQueue<>(128);
        private byte[] current = new byte[0]; private int offset; private volatile boolean closed;
        boolean offer(String text) { return !closed && text.length() <= 65536 && chunks.offer(text.getBytes(StandardCharsets.UTF_8)); }
        boolean offerNavigation(String text){return chunks.size()<8&&offer(text);}
        @Override public int read() throws IOException {
            while (offset >= current.length) {
                if (closed) return -1;
                try { byte[] next = chunks.poll(100,TimeUnit.MILLISECONDS); if (next == null) continue; current = next; offset = 0; }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
            }
            return current[offset++] & 255;
        }
        @Override public int read(byte[] bytes,int off,int len) throws IOException {
            if (len == 0) return 0;
            int first = read(); if (first < 0) return -1; bytes[off] = (byte)first;
            int count = Math.min(len-1,current.length-offset); System.arraycopy(current,offset,bytes,off+1,count); offset += count; return count+1;
        }
        @Override public int available() { return current.length-offset + (chunks.isEmpty()?0:1); }
        @Override public void close() { closed = true; chunks.clear(); }
    }
}
