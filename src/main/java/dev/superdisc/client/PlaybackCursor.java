package dev.superdisc.client;

import java.util.ArrayList;
import java.util.List;

/** OpenAL offsets are relative to all buffers still queued, including processed ones. */
public final class PlaybackCursor {
    private final List<Integer> buffers=new ArrayList<>();
    private long frames;
    public synchronized void queued(int count) {
        if(count<=0)throw new IllegalArgumentException("Do not queue empty PCM buffers");
        buffers.add(count);frames+=count;
    }
    public synchronized long position(int queued,int offset) {
        long remaining=0;
        for(int i=Math.max(0,buffers.size()-queued);i<buffers.size();i++)remaining+=buffers.get(i);
        return frames-remaining+Math.max(0,offset);
    }
}
