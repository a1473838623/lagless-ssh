package dev.lagless.desktop;

/** EDT-owned wheel accumulator: preserve fractions, combine reports, never replay a long tail. */
final class RemoteWheelQueue {
    record Batch(String keys,int ticks) { }
    private double rotation;
    private long generation,lastInput,lastSent;
    private int column=1,row=1;
    private boolean pages,control;
    boolean pending(){return rotation!=0;}
    void cancel(){rotation=0;lastSent=0;}
    void add(double delta,long now,RemoteWheelProtocol.Mode mode,boolean forcePages,boolean usePageKeys,int column,int row,boolean control){
        if(delta==0||!Double.isFinite(delta))return;
        boolean pageMode=forcePages||usePageKeys;
        if(generation!=mode.generation()||pages!=pageMode||rotation!=0&&Math.signum(delta)!=Math.signum(rotation))cancel();
        generation=mode.generation();pages=pageMode;this.column=column;this.row=row;this.control=control;
        rotation=Math.max(-16,Math.min(16,rotation+delta));lastInput=now;
    }
    Batch peek(long now,RemoteWheelProtocol.Mode mode){
        if(rotation==0)return null;
        if(generation!=mode.generation()||Math.abs(rotation)>=1&&now-lastInput>200_000_000L){cancel();return null;}
        if(lastSent!=0&&now-lastSent<(pages?64_000_000L:16_000_000L))return null;
        int count=Math.min(pages?1:4,(int)Math.floor(Math.abs(rotation)+1e-9));if(count==0)return null;
        boolean up=rotation<0;
        String keys=pages?(up?"\u001b[5~":"\u001b[6~"):
                mode.supported()?RemoteWheelProtocol.report(mode,up,column,row,control):(up?"\u001b[A":"\u001b[B");
        if(keys.isEmpty()){cancel();return null;}
        return new Batch(keys.repeat(count),up?-count:count);
    }
    void sent(Batch batch,long now){rotation-=batch.ticks();if(Math.abs(rotation)<1e-9)rotation=0;lastSent=now;}
}
