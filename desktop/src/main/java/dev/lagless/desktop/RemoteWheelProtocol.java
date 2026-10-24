package dev.lagless.desktop;

/** Observes remote mouse negotiation before the CLI's selection-preserving filter. */
final class RemoteWheelProtocol {
    record Mode(long generation,boolean sgr,boolean urxvt){boolean supported(){return sgr||urxvt;}}
    private enum State {GROUND,ESC,CSI,STRING,STRING_ESC}
    private State state=State.GROUND;
    private final StringBuilder sequence=new StringBuilder();
    private int tracking;
    private boolean sgr,urxvt,pixels;
    private long generation;
    synchronized Mode mode(){return new Mode(generation,tracking!=0&&sgr&&!pixels,tracking!=0&&!sgr&&urxvt&&!pixels);}
    synchronized void reset(){tracking=0;sgr=urxvt=pixels=false;state=State.GROUND;sequence.setLength(0);generation++;}
    synchronized void feed(String text){
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            switch(state){
                case GROUND->{if(c==27)state=State.ESC;else if(c==0x9b){state=State.CSI;sequence.setLength(0);}else if(c==0x9d||c==0x90||c==0x98||c==0x9e||c==0x9f)state=State.STRING;}
                case ESC->{
                    if(c=='['){state=State.CSI;sequence.setLength(0);}
                    else if(c==']'||c=='P'||c=='X'||c=='^'||c=='_')state=State.STRING;
                    else{state=State.GROUND;if(c=='c')reset();else if(c==27)state=State.ESC;}
                }
                case CSI->{
                    if(c==27){state=State.ESC;sequence.setLength(0);}
                    else if(c>=0x40&&c<=0x7e){if((c=='h'||c=='l')&&!sequence.isEmpty()&&sequence.charAt(0)=='?')modes(sequence.substring(1),c=='h');sequence.setLength(0);state=State.GROUND;}
                    else if(c==0x18||c==0x1a||sequence.length()>=64){sequence.setLength(0);state=State.GROUND;}
                    else sequence.append(c);
                }
                case STRING->{if(c==7||c==0x9c)state=State.GROUND;else if(c==27)state=State.STRING_ESC;}
                case STRING_ESC->{if(c=='\\')state=State.GROUND;else state=c==27?State.STRING_ESC:State.STRING;}
            }
        }
    }
    private void modes(String text,boolean enabled){
        int oldTracking=tracking;boolean oldSgr=sgr,oldUrxvt=urxvt,oldPixels=pixels;
        for(String part:text.split(";")){
            int mode;try{mode=Integer.parseInt(part);}catch(NumberFormatException ignored){continue;}
            switch(mode){
                case 1000,1002,1003->{int bit=mode==1000?1:mode==1002?2:4;tracking=enabled?tracking|bit:tracking&~bit;}
                case 1006->sgr=enabled;
                case 1015->urxvt=enabled;
                case 1016->pixels=enabled;
                case 47,1047,1049->{if(!enabled){tracking=0;sgr=urxvt=pixels=false;}}
                default->{}
            }
        }
        if(tracking!=oldTracking||sgr!=oldSgr||urxvt!=oldUrxvt||pixels!=oldPixels)generation++;
    }
    static String report(Mode mode,boolean up,int column,int row,boolean control){
        int button=(up?64:65)+(control?16:0);
        return mode.sgr()?"\u001b[<"+button+";"+column+";"+row+"M":mode.urxvt()?"\u001b["+(button+32)+";"+column+";"+row+"M":"";
    }
}
