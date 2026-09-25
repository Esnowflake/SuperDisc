package dev.superdisc.client;
import java.io.*;
import java.nio.file.Path;

/** Fills one buffer across as many loop boundaries as necessary, with no inserted samples. */
public final class PcmReader implements AutoCloseable {
    private final RandomAccessFile file;
    private final long frames;
    private final byte[] shortClip;
    private long position;
    public PcmReader(Path path,long frames,long start)throws IOException {
        if(frames<=0)throw new IOException("Empty PCM");
        this.frames=frames;position=Math.min(frames,Math.max(0,start));
        file=new RandomAccessFile(path.toFile(),"r");file.seek(16+position*2);
        if(frames<=32768){shortClip=new byte[(int)frames*2];file.seek(16);file.readFully(shortClip);file.seek(16+position*2);}else shortClip=null;
    }
    public int read(byte[] data,boolean loop)throws IOException {
        int filled=0,limit=data.length-data.length%2;
        while(filled<limit){
            if(position==frames){if(!loop)break;position=0;if(shortClip==null)file.seek(16);}
            int n=(int)Math.min(limit-filled,(frames-position)*2);
            if(shortClip!=null)System.arraycopy(shortClip,(int)position*2,data,filled,n);else file.readFully(data,filled,n);
            position+=n/2;filled+=n;
        }
        return filled;
    }
    public boolean eof(){return position==frames;}
    @Override public void close()throws IOException{file.close();}
}
