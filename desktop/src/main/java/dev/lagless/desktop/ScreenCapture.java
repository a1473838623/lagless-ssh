package dev.lagless.desktop;

import dev.lagless.ScreenModel;
import java.util.*;
import java.util.function.Consumer;

/** Mutable recent logical lines: a streaming prefix replaces its previous rendition. */
final class ScreenCapture {
    private static final int MAX_LINES=512,MAX_CHARS=2_000_000;
    private static final long DUPLICATE_WINDOW=2_000_000_000L;
    private static final class Line {
        String text;TerminalHistory.Row row;int position;long seenAt;
        Line(String text,TerminalHistory.Row row,int position,long seenAt){this.text=text;this.row=row;this.position=position;this.seenAt=seenAt;}
    }
    private final Consumer<TerminalHistory.Row> sink;
    private final Consumer<List<TerminalHistory.Row>> tail;
    private final ArrayList<Line> growing=new ArrayList<>();
    private final LinkedHashMap<String,Long> recent=new LinkedHashMap<>(512,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Long> e){
            if(size()<=512&&recentChars<=MAX_CHARS)return false;
            recentChars-=e.getKey().length();return true;
        }
    };
    private long recentChars,growingChars;
    private boolean dirty;
    private ScreenModel tracked;
    private long[] rowRevisions=new long[0];
    private int trackedColumns;
    ScreenCapture(Consumer<TerminalHistory.Row> sink){this(sink,rows->{});}
    ScreenCapture(Consumer<TerminalHistory.Row> sink,Consumer<List<TerminalHistory.Row>> tail){this.sink=sink;this.tail=tail;}
    void reset(){flush();recent.clear();recentChars=0;Arrays.fill(rowRevisions,Long.MIN_VALUE);}
    void remember(TerminalHistory.Row row){remember(dev.lagless.AnsiUtil.strip(row.ansi()).stripTrailing());}
    private void remember(String text){
        if(text.isBlank())return;
        if(!recent.containsKey(text))recentChars+=text.length();
        recent.put(text,System.nanoTime());
    }
    private boolean recentlySeen(String text,long now){Long seen=recent.get(text);if(seen==null||now-seen>=DUPLICATE_WINDOW)return false;recent.put(text,now);return true;}
    void changedRows(ScreenModel screen){for(int row=0;row<screen.rows();row++)changedRow(screen,row);publish();}
    void changedRow(ScreenModel screen,int row){
        if(tracked!=screen||rowRevisions.length!=screen.rows()||trackedColumns!=screen.cols()){
            tracked=screen;trackedColumns=screen.cols();rowRevisions=new long[screen.rows()];Arrays.fill(rowRevisions,Long.MIN_VALUE);
        }
        long revision=screen.rowRevision(row);
        if(revision!=0&&rowRevisions[row]==revision)return;
        rowRevisions[row]=revision;
        String text=screen.rowText(row).stripTrailing();if(text.isBlank())return;
        int[] chars=new int[screen.cols()];String[] pens=new String[chars.length];
        for(int col=0;col<chars.length;col++){chars[col]=screen.charAt(row,col);pens[col]=screen.penAt(row,col);}
        stage(text,TerminalHistory.Row.cells(chars,pens),row);
    }
    private void stage(String text,TerminalHistory.Row row,int position){
        long now=System.nanoTime();Line match=null;int score=-1;
        for(Line line:growing){
            if(now-line.seenAt>=DUPLICATE_WINDOW)continue;
            if(line.text.equals(text)){line.position=position;line.seenAt=now;return;}
            // A row may move during reflow. Tiny prefixes only bind to their own row.
            if((text.startsWith(line.text)||line.text.startsWith(text))&&
                    (line.position==position||Math.min(text.length(),line.text.length())>=8)){
                int candidate=Math.min(text.length(),line.text.length());
                if(candidate>score){score=candidate;match=line;}
            }
        }
        if(match!=null){
            match.position=position;match.seenAt=now;
            // Clearing and repainting can temporarily shorten the same line.
            if(text.length()>match.text.length()){growingChars+=text.length()-match.text.length();match.text=text;match.row=row;dirty=true;}
        }else if(!recentlySeen(text,now)){growing.add(new Line(text,row,position,now));growingChars+=text.length();dirty=true;}
        while(growing.size()>MAX_LINES||growingChars>MAX_CHARS)commitFirst();
    }
    void scrolled(int[] chars,String[] pens){
        StringBuilder text=new StringBuilder();for(int cp:chars)if(cp>=0)text.appendCodePoint(cp);
        String key=text.toString().stripTrailing();if(!key.isBlank())stage(key,TerminalHistory.Row.cells(chars,pens),0);
    }
    private void commitFirst(){Line line=growing.removeFirst();growingChars-=line.text.length();if(!recentlySeen(line.text,System.nanoTime())){sink.accept(line.row);remember(line.text);}dirty=true;}
    void publish(){if(dirty){tail.accept(growing.stream().map(line->line.row).toList());dirty=false;}}
    void flush(){while(!growing.isEmpty())commitFirst();publish();}
}
