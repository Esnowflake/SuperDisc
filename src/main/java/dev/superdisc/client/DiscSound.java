package dev.superdisc.client;

import dev.superdisc.*;
import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.*;
import net.minecraft.client.sounds.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.BufferUtils;
import javax.sound.sampled.AudioFormat;
import java.io.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/** Normal Minecraft RECORDS source with vanilla linear attenuation, independent for each jukebox. */
public final class DiscSound extends AbstractTickableSoundInstance {
    private final AudioDecoder.Prepared pcm;
    private final Track track;
    private final double startPosition;
    private volatile PcmStream stream;
    private volatile int alSource=-1;
    private volatile double actual;
    private volatile long actualAt;
    private volatile boolean valid=true;
    private volatile boolean drained;
    private volatile float targetVolume;
    public DiscSound(Track track,AudioDecoder.Prepared pcm,double position) {
        super(net.minecraft.sounds.SoundEvent.createVariableRangeEvent(new ResourceLocation(SuperDisc.ID,"audio")),SoundSource.RECORDS,RandomSource.create());
        this.track=track;this.pcm=pcm;startPosition=position;actual=position;
        x=track.pos.getX()+.5;y=track.pos.getY()+.5;z=track.pos.getZ()+.5;gain(track.volume);volume=targetVolume;pitch=1;looping=false;relative=false;attenuation=Attenuation.LINEAR;
    }
    @Override public WeighedSoundEvents resolve(SoundManager manager) {
        // Dynamic PCM stream: resolve a real Sound instead of EMPTY_SOUND, no resource-pack sound lookup.
        sound=new Sound(SuperDisc.ID+":audio",ConstantFloat.of(1),ConstantFloat.of(1),1,Sound.Type.FILE,true,false,16);
        var events=new WeighedSoundEvents(location,null);events.addSound(sound);return events;
    }
    @Override public CompletableFuture<AudioStream> getStream(SoundBufferLibrary library,Sound sound,boolean loop) {
        try{stream=new PcmStream(pcm,startPosition);return CompletableFuture.completedFuture(stream);}catch(IOException ex){return CompletableFuture.failedFuture(ex);}
    }
    @Override public boolean canStartSilent(){return true;}
    @Override public void tick(){volume+=Math.max(-.1f,Math.min(.1f,targetVolume-volume));}
    public void gain(float value){targetVolume=PcmGain.sourceGain(value,pcm.boost());}
    public void finish(){valid=false;stop();Minecraft.getInstance().getSoundManager().stop(this);}
    public double actual(){return actual;}
    public long actualAt(){return actualAt;}
    public boolean hasClock(){return actualAt!=0;}
    public boolean ended(){return drained;}
    public void bind(Channel channel) {
        // Channel has exactly one final int: its OpenAL source. Structural reflection works in SRG and dev mappings.
        try {
            Field field=Arrays.stream(Channel.class.getDeclaredFields()).filter(f->f.getType()==int.class && Modifier.isFinal(f.getModifiers())&&!Modifier.isStatic(f.getModifiers())).findFirst().orElseThrow();
            field.setAccessible(true);alSource=field.getInt(channel);
        }catch(Exception e){SuperDisc.LOG.error("Cannot obtain actual audio playback cursor",e);}
    }
    public void poll() {
        if(!valid||alSource<0||stream==null)return;
        AudioThread.execute(() -> {
            if(!valid||alSource<0||stream==null)return;
            try {
                // A closed stream can also mean device reload or explicit cancellation, not EOF.
                if(stream.closed)return;
                int queued=AL10.alGetSourcei(alSource,AL10.AL_BUFFERS_QUEUED);
                int state=AL10.alGetSourcei(alSource,AL10.AL_SOURCE_STATE);
                int offset=AL10.alGetSourcei(alSource,AL11.AL_SAMPLE_OFFSET);
                double measured=stream.position(queued,offset);
                if(state==AL10.AL_STOPPED && stream.eof && AL10.alGetSourcei(alSource,AL10.AL_BUFFERS_PROCESSED)==queued){drained=true;measured=pcm.duration();}
                if(state==AL10.AL_PLAYING||state==AL10.AL_PAUSED||state==AL10.AL_STOPPED){actual=Math.max(0,Math.min(pcm.duration(),measured));actualAt=System.currentTimeMillis();}
            }catch(Exception ex){SuperDisc.LOG.warn("Audio clock read failed",ex);}
        });
    }
    private final class PcmStream implements AudioStream {
        final RandomAccessFile file;final AudioDecoder.Prepared pcm;final long start;
        final PlaybackCursor cursor=new PlaybackCursor();long readFrames;volatile boolean eof,closed;
        PcmStream(AudioDecoder.Prepared pcm,double position)throws IOException {
            this.pcm=pcm;start=Math.min(pcm.frames(),Math.max(0,(long)(position*pcm.rate())));file=new RandomAccessFile(pcm.path().toFile(),"r");file.seek(16+start*2);
        }
        @Override public AudioFormat getFormat(){return new AudioFormat(pcm.rate(),16,1,true,false);}
        @Override public synchronized ByteBuffer read(int size)throws IOException {
            int count=(int)Math.min(size-size%2,(pcm.frames()-start-readFrames)*2);
            // Channel queues every non-null result, including an empty buffer. Empty buffers
            // used to corrupt the queue/frame ledger near EOF and move the cursor early.
            if(count<=0){
                eof=true;
                // updateStream removes processed buffers before asking for more data.
                // Catch the natural end here, before ChannelAccess releases the source.
                if(valid&&alSource>=0&&AL10.alGetSourcei(alSource,AL10.AL_BUFFERS_QUEUED)==0){drained=true;actual=pcm.duration();actualAt=System.currentTimeMillis();}
                return null;
            }
            byte[] data=new byte[count];file.readFully(data);
            for(int i=0;i<count;i+=2){short v=(short)((data[i]&255)|(data[i+1]<<8));short boosted=PcmGain.sample(v,pcm.boost());data[i]=(byte)boosted;data[i+1]=(byte)(boosted>>8);}
            readFrames+=count/2;cursor.queued(count/2);eof=start+readFrames>=pcm.frames();
            ByteBuffer result=BufferUtils.createByteBuffer(count);result.put(data).flip();return result;
        }
        synchronized double position(int queued,int offset){
            return (start+cursor.position(queued,offset))/(double)pcm.rate();
        }
        @Override public void close()throws IOException{closed=true;file.close();}
    }
    private static final class AudioThread {
        private static Executor executor;
        static void execute(Runnable action) {
            try {
                if(executor==null){
                    SoundManager manager=Minecraft.getInstance().getSoundManager();Field f=Arrays.stream(SoundManager.class.getDeclaredFields()).filter(x->x.getType()==SoundEngine.class).findFirst().orElseThrow();f.setAccessible(true);Object engine=f.get(manager);
                    Field e=Arrays.stream(SoundEngine.class.getDeclaredFields()).filter(x->Executor.class.isAssignableFrom(x.getType())).findFirst().orElseThrow();e.setAccessible(true);executor=(Executor)e.get(engine);
                }
                executor.execute(action);
            }catch(Exception ex){SuperDisc.LOG.error("Could not schedule OpenAL cursor query",ex);}
        }
    }
}
