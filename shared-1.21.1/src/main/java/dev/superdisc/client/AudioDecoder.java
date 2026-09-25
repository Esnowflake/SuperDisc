package dev.superdisc.client;

import dev.superdisc.*;
import net.minecraft.client.sounds.JOrbisAudioStream;
import javazoom.jl.decoder.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;

/** Decode once to disk, mono signed 16-bit PCM. Mono enables positional attenuation in OpenAL. */
public final class AudioDecoder {
    private static final int MAGIC=0x53445031;
    private record Cached(Prepared pcm,long size,java.nio.file.attribute.FileTime modified) {}
    private static final java.util.Map<Path,Cached> prepared=new java.util.LinkedHashMap<>(16,.75f,true);
    public record Prepared(Path path,int rate,long frames,double boost) { public double duration(){return frames/(double)rate;} }
    public static Prepared prepare(Track track) throws Exception {
        return prepare(track,new WorkToken());
    }
    public static Prepared prepare(Track track,WorkToken token) throws Exception {
        token.check();
        Path input=Cache.file(track.hash,track.name,track.extension);
        Path target=Cache.root().resolve(track.hash+".mono-v1.pcm");
        synchronized(prepared){Cached cached=prepared.get(target);
            if(cached!=null&&Files.isRegularFile(target)&&Files.size(target)==cached.size&&Files.getLastModifiedTime(target).equals(cached.modified))return cached.pcm;
        }
        if(Files.isRegularFile(target))try(var f=new RandomAccessFile(target.toFile(),"r")){
            if(f.readInt()==MAGIC){int rate=f.readInt();long frames=f.readLong();if(rate>=8000&&rate<=192000&&frames>0&&f.length()==16+frames*2)return inspect(target,rate,frames,token);}
        }
        Path tmp=Files.createTempFile(Cache.root(),"decode-",".part");
        int rate=0;long frames=0;
        try(var output=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp),262144))) {
            output.writeInt(MAGIC);output.writeInt(0);output.writeLong(0);
            if(track.extension.equals("mp3")) {
                try(InputStream stream=Files.newInputStream(input)) {
                    Bitstream bitstream=new Bitstream(stream);Decoder decoder=new Decoder();Header header;
                    try {
                        while((header=bitstream.readFrame())!=null) {
                            token.check();
                            SampleBuffer samples=(SampleBuffer)decoder.decodeFrame(header,bitstream);
                            int hz=samples.getSampleFrequency(),channels=samples.getChannelCount();
                            if(rate!=0&&hz!=rate)throw new IOException("MP3 sample rate changed mid-stream");rate=hz;
                            short[] data=samples.getBuffer();
                            for(int i=0;i<samples.getBufferLength();i+=channels){int value=0;for(int c=0;c<channels;c++)value+=data[i+c];writeShort(output,value/channels);frames++;}
                            bitstream.closeFrame();check(rate,frames);
                        }
                    }finally{bitstream.close();}
                }
            } else {
                try(JOrbisAudioStream ogg=new JOrbisAudioStream(Files.newInputStream(input))) {
                    var format=ogg.getFormat();rate=(int)format.getSampleRate();int channels=format.getChannels();
                    if(format.getSampleSizeInBits()!=16||channels<1||channels>2)throw new IOException("Only mono/stereo Ogg Vorbis supported");
                    while(true){token.check();ByteBuffer data=ogg.read(65536);if(!data.hasRemaining())break;data.order(format.isBigEndian()?ByteOrder.BIG_ENDIAN:ByteOrder.LITTLE_ENDIAN);
                        while(data.remaining()>=channels*2){int value=0;for(int c=0;c<channels;c++)value+=data.getShort();writeShort(output,value/channels);frames++;}check(rate,frames);}
                }
            }
            check(rate,frames);if(frames==0)throw new IOException("Empty audio");
        }catch(Exception ex){Files.deleteIfExists(tmp);throw ex;}
        try {
            token.check();
            try(var header=new RandomAccessFile(tmp.toFile(),"rw")){header.seek(4);header.writeInt(rate);header.writeLong(frames);}
            synchronized(token){token.check();Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);}
            SuperDisc.LOG.info("Decoded {}: {} Hz, {} frames",track.hash,rate,frames);
            return inspect(target,rate,frames,token);
        } finally {Files.deleteIfExists(tmp);}
    }
    private static Prepared inspect(Path file,int rate,long frames,WorkToken token)throws IOException {
        int peak=0;
        try(var in=new BufferedInputStream(Files.newInputStream(file))){
            in.skipNBytes(16);byte[] bytes=new byte[65536];int n;
            while((n=in.readNBytes(bytes,0,bytes.length))>0){token.check();for(int i=0;i+1<n;i+=2)peak=Math.max(peak,Math.abs((short)((bytes[i]&255)|(bytes[i+1]<<8))));}
        }
        Prepared result=new Prepared(file,rate,frames,PcmGain.boost(peak));
        synchronized(prepared){prepared.put(file,new Cached(result,Files.size(file),Files.getLastModifiedTime(file)));while(prepared.size()>64)prepared.remove(prepared.keySet().iterator().next());}
        return result;
    }
    private static void check(int rate,long frames) throws IOException {
        if(rate<8000||rate>192000||frames>rate*Cache.MAX_SECONDS||frames*2>256L*1024*1024)throw new IOException("Audio exceeds duration/PCM size limit");
    }
    private static void writeShort(OutputStream output,int value)throws IOException {output.write(value&255);output.write((value>>8)&255);}
}
