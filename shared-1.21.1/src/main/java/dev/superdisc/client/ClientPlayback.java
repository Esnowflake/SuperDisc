package dev.superdisc.client;

import dev.superdisc.*;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.*;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Client-only state, cache I/O and decoder worker. No client classes are loaded on a dedicated server. */
public final class ClientPlayback {
    static final Minecraft MC=Minecraft.getInstance();
    static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Super Disc decoder");t.setDaemon(true);return t;});
    static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Super Disc transfer");t.setDaemon(true);return t;});
    static final Map<String,Local> locals=new HashMap<>();
    private static final Map<String,Path> localPaths=new HashMap<>();
    private static final Map<String,ImportJob> imports=new HashMap<>();
    private static final Set<UUID> cancelledRevisions=new HashSet<>();
    private static final class ImportJob {
        final UUID id=UUID.randomUUID();final WorkToken work=new WorkToken();final Path source;
        boolean sent;
        ImportJob(Path source){this.source=source;}
    }
    private static long tick, connection=0;
    private static Object serverConnection;
    private static long clockOffset, rtt;
    private static final Properties prefs=new Properties();
    private static final Path prefsFile=Cache.root().getParent().resolve("client.properties");
    private static MessageSignature progressSignature;
    public static boolean notifications=true;
    public static boolean protectVolume;
    public static volatile boolean distortionProtection=true;
    public record Listener(UUID id,String name,float volume,boolean locked) {}
    private static final Map<String,Float> personalVolumes=new HashMap<>();
    private static final Map<String,List<Listener>> rosters=new HashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong pendingDownloadBytes=new java.util.concurrent.atomic.AtomicLong();
    static {
        try(var in=Files.newInputStream(prefsFile)){prefs.load(in);notifications=Boolean.parseBoolean(prefs.getProperty("notifications","true"));protectVolume=Boolean.parseBoolean(prefs.getProperty("protectVolume","false"));}catch(IOException ignored){}
        distortionProtection=Boolean.parseBoolean(prefs.getProperty("distortionProtection","true"));
    }
    public static void setDistortionProtection(boolean enabled){distortionProtection=enabled;prefs.setProperty("distortionProtection",Boolean.toString(enabled));savePrefs();}
    public static final class Local {
        public Track track;
        public AudioDecoder.Prepared pcm;
        Cache.Incoming download;
        DiscSound sound;
        boolean preparing, failed, ready, sleeping;
        final WorkToken work=new WorkToken();
        long endedTransport=-1;
        long uploadOffset=-1, soundCreated;
        String status="";
        Local(Track t){track=t;}
        void stop(){if(sound!=null){sound.finish();sound=null;}}
        void close(){work.cancel();ready=false;preparing=false;uploadOffset=-1;stop();if(download!=null){download.close();download=null;}}
    }
    public static long serverNow(){return System.currentTimeMillis()+clockOffset;}
    public static Local get(String key){return locals.get(key);}
    public static boolean importing(String key){return imports.containsKey(key);}
    public static float volume(String key){return personalVolumes.getOrDefault(key,1f);}
    public static List<Listener> listeners(String key){return rosters.getOrDefault(key,List.of());}
    public static boolean canManage(String key){Local l=get(key);return l!=null&&MC.player!=null&&l.track.controller.equals(MC.player.getUUID());}
    public static void watchVolumes(String key,boolean watch){Local l=get(key);if(l!=null){var tag=l.track.tag();tag.putBoolean("watch",watch);Net.toServer("volume_watch",tag);}}
    public static void setVolume(String key,UUID target,double value){Local l=get(key);if(l!=null){var tag=l.track.tag();tag.putUUID("target",target);tag.putFloat("volume",(float)value);Net.toServer("volume",tag);}}
    public static void setProtection(String key,boolean enabled){
        protectVolume=enabled;prefs.setProperty("protectVolume",Boolean.toString(enabled));savePrefs();
        Local l=get(key);if(l!=null){var tag=l.track.tag();tag.putBoolean("protected",enabled);Net.toServer("protect",tag);}
    }
    public static double displayedPosition(Local l) {
        if(l.endedTransport==l.track.transport)return l.track.duration;
        if(l.sound!=null&&l.sound.hasClock())return l.sound.actual();
        return l.track.at(serverNow());
    }
    private static void ready(Local l){var tag=l.track.tag();tag.putDouble("decodedDuration",l.pcm.duration());Net.toServer("ready",tag);}
    private static Local update(Track t) {
        Local l=locals.get(t.key());
        if(l==null||!l.track.revision.equals(t.revision)){
            if(l!=null)l.close();l=new Local(t);locals.put(t.key(),l);
        }else{if(l.track.transport!=t.transport||l.track.loop!=t.loop){l.stop();l.endedTransport=-1;}if(!t.playing&&t.position<t.duration)l.endedTransport=-1;l.track=t;if(l.sound!=null)l.sound.gain(volume(t.key()));}
        return l;
    }
    public static void packet(Net.Packet packet) {
        if(MC.getConnection()==null)return;
        String op=packet.op();CompoundTag tag=packet.tag();
        if(op.equals("clock")){long now=System.currentTimeMillis();rtt=Math.max(0,now-tag.getLong("sent"));clockOffset=tag.getLong("server")+rtt/2-now;return;}
        if(op.equals("message")){notice(tag.getString("text"));return;}
        Track track=Track.read(tag);String key=track.key();Local l=locals.get(key);
        if(op.equals("personal")){personalVolumes.put(key,tag.getFloat("personalVolume"));protectVolume=tag.getBoolean("protected");return;}
        if(op.equals("volumes")){
            List<Listener> entries=new ArrayList<>();var rows=tag.getList("players",10);
            for(int i=0;i<rows.size();i++){var row=rows.getCompound(i);entries.add(new Listener(row.getUUID("id"),row.getString("name"),row.getFloat("volume"),row.getBoolean("protected")));}
            rosters.put(key,List.copyOf(entries));return;
        }
        if(op.equals("clock_state")){if(l!=null&&l.track.revision.equals(track.revision)&&l.track.transport==track.transport){l.track.position=track.position;l.track.epoch=track.epoch;}return;}
        if(cancelledRevisions.contains(track.revision))return;
        if(op.equals("cancel")){if(l!=null&&l.track.revision.equals(track.revision)){l.close();locals.put(key,new Local(track));}return;}
        if(op.equals("remove")){ImportJob job=imports.remove(key);if(job!=null)job.work.cancel();if(l!=null)l.close();locals.remove(key);localPaths.remove(key);if(MC.screen instanceof DiscScreen screen && screen.key.equals(key))MC.setScreen(null);return;}
        if(op.equals("sleep")){if(l!=null){l.stop();l.sleeping=true;if(l.download!=null){l.download.close();l.download=null;}l.preparing=false;}return;}
        if(op.equals("open")){update(track);MC.setScreen(new DiscScreen(key));return;}
        if(op.equals("state")){l=update(track);if(!track.playing)l.stop();if(track.hash.isEmpty()&&!imports.containsKey(key)){localPaths.remove(key);pendingAutoplay.remove(key);}return;}
        if(op.equals("progress")) {
            if(l!=null)l.status=tag.getString("phase")+" "+Math.min(100,100*tag.getLong("done")/Math.max(1,tag.getLong("total")))+"%";
            if(notifications)progress("[Super Disc] "+track.name+" — "+(l==null?tag.getString("phase"):l.status));return;
        }
        if(op.equals("complete")){l=update(track);finishImport(track);l.status="同步完成";if(notifications)progress("[Super Disc] "+track.name+" 同步完成，开始播放。");return;}
        if(op.equals("upload")) {l=update(track);l.uploadOffset=0;sendUpload(l);return;}
        if(op.equals("upload_ack")) {
            if(l!=null&&l.track.revision.equals(track.revision)&&l.uploadOffset>=0){l.uploadOffset=tag.getLong("offset");if(l.uploadOffset<track.size)sendUpload(l);}return;
        }
        if(op.equals("uploaded")){l=update(track);l.uploadOffset=-1;l.status="上传完成，正在准备音频";return;}
        if(op.equals("cached")){l=update(track);if(!track.syncing){finishImport(track);l.status="同步完成，点击开始播放";if(notifications)progress("[Super Disc] "+track.name+" 同步完成。");}return;}
        if(op.equals("prepare")){l=update(track);if(l.work.cancelled()){l=new Local(track);locals.put(key,l);}l.sleeping=false;if(l.ready){ready(l);return;}prepare(l);return;}
        if(op.equals("download")) {
            if(l==null||!l.track.revision.equals(track.revision)||l.download==null)return;
            Cache.Incoming incoming=l.download;Local current=l;long generation=connection;
            int bytes=packet.bytes().length;
            if(pendingDownloadBytes.addAndGet(bytes)>8L*1024*1024){pendingDownloadBytes.addAndGet(-bytes);fail(l,new IOException("磁盘写入过慢，下载已暂停，请重试"));return;}
            IO.execute(()->{try{
                current.work.check();incoming.append(tag.getLong("offset"),packet.bytes());
                if(incoming.received==track.size){incoming.finish();incoming.close();MC.execute(()->{if(generation==connection&&locals.get(key)==current&&!current.work.cancelled()){current.download=null;decode(current);}});}
            }catch(Exception e){incoming.close();failAsync(current,generation,e);}finally{pendingDownloadBytes.addAndGet(-bytes);}});return;
        }
    }
    private static void finishImport(Track track){ImportJob job=imports.get(track.key());if(job!=null&&job.id.equals(track.revision))imports.remove(track.key());}
    private static void prepare(Local l) {
        if(l.preparing||l.download!=null)return;l.preparing=true;l.failed=false;long generation=connection;
        IO.execute(()->{
            boolean valid=Cache.valid(l.track);
            MC.execute(()->{
                if(generation!=connection||locals.get(l.track.key())!=l||l.work.cancelled())return;
                if(valid)decode(l);
                else try {l.download=new Cache.Incoming(l.track);l.preparing=false;Net.toServer("need",l.track.tag());}catch(Exception e){fail(l,e);}
            });
        });
    }
    private static void decode(Local l) {
        l.preparing=true;l.status="正在解码";long generation=connection;Track snapshot=l.track;
        WORKER.execute(()->{try {
            var pcm=AudioDecoder.prepare(snapshot,l.work);
            if(snapshot.duration>0&&Math.abs(pcm.duration()-snapshot.duration)>.25)throw new IOException("Decoded duration differs from manifest");
            MC.execute(()->{if(generation!=connection||locals.get(snapshot.key())!=l||l.work.cancelled())return;l.pcm=pcm;l.preparing=false;l.ready=true;l.status="已缓存并就绪";ready(l);});
        }catch(Exception e){failAsync(l,generation,e);}});
    }
    private static void sendUpload(Local l) {
        Track track=l.track;long offset=l.uploadOffset;long generation=connection;
        IO.execute(()->{try(var file=new RandomAccessFile(Cache.file(track.hash,track.name,track.extension).toFile(),"r")){
            l.work.check();
            file.seek(offset);byte[] bytes=new byte[(int)Math.min(Net.CHUNK,track.size-offset)];file.readFully(bytes);
            MC.execute(()->{if(generation!=connection||locals.get(track.key())!=l||l.work.cancelled())return;var tag=track.tag();tag.putLong("offset",offset);Net.toServer(new Net.Packet("chunk",tag,bytes));});
        }catch(Exception e){failAsync(l,generation,e);}});
    }
    public static void importFile(String key,Path source,boolean autoplay) {
        Local l=locals.get(key);if(l==null)return;
        clear(key);
        ImportJob job=new ImportJob(source.toAbsolutePath().normalize());imports.put(key,job);localPaths.put(key,job.source);
        long generation=connection;l.status="正在校验文件并开始同步…";l.preparing=true;
        IO.execute(()->{try{
            job.work.check();
            Path real=source.toRealPath();long size=Files.size(real);
            String full=real.getFileName().toString();int dot=full.lastIndexOf('.');String ext=dot<0?"":full.substring(dot+1).toLowerCase(Locale.ROOT);
            if(!ext.equals("mp3")&&!ext.equals("ogg"))throw new IOException("仅支持 MP3 或 Ogg Vorbis (.ogg)");
            if(!Files.isRegularFile(real)||size<=0||size>Cache.MAX_BYTES)throw new IOException("文件必须为 1 字节至 32 MiB");
            Track meta=new Track(l.track.dimension,l.track.pos);meta.revision=job.id;meta.size=size;meta.name=Cache.safeName(full.substring(0,dot));meta.extension=ext;meta.hash=Cache.sha256(real,job.work);
            Path cached=Cache.file(meta.hash,meta.name,ext);Files.createDirectories(Cache.root());
            if(!cached.equals(real)&&!Cache.valid(meta)){
                try(Cache.Incoming copy=new Cache.Incoming(meta);var input=Files.newInputStream(real)){
                    byte[] bytes;while((bytes=input.readNBytes(Net.CHUNK)).length>0){job.work.check();copy.append(copy.received,bytes);}
                    synchronized(job.work){job.work.check();copy.finish();}
                }
            }
            job.work.check();
            // Upload starts before full decoding. Decoded sample count is reported in ready.
            MC.execute(()->{if(generation!=connection||imports.get(key)!=job||job.work.cancelled())return;localPaths.put(key,real);
                Local current=locals.get(key);if(current==null)return;current.status="上传中";
                var tag=meta.tag();tag.putUUID("importRequest",job.id);job.sent=true;Net.toServer("begin",tag);
                pendingAutoplay.put(key,autoplay);
            });
        }catch(Exception ex){MC.execute(()->{if(generation==connection&&imports.get(key)==job&&!job.work.cancelled()){clear(key);notice("无法导入音频："+ex.getMessage());SuperDisc.LOG.warn("Audio import",ex);}});}});
    }
    private static final Map<String,Boolean> pendingAutoplay=new HashMap<>();
    public static String path(String key){Path local=localPaths.get(key);Local l=locals.get(key);return local!=null?local.toString():l==null||l.work.cancelled()||l.track.hash.isEmpty()?"":l.track.name+"."+l.track.extension;}
    public static void clear(String key) {
        Local l=locals.get(key);if(l==null)return;
        ImportJob job=imports.remove(key);if(job!=null){job.work.cancel();cancelledRevisions.add(job.id);if(job.sent){var tag=l.track.tag();tag.putUUID("importRequest",job.id);Net.toServer("cancel_import",tag);}}
        localPaths.remove(key);pendingAutoplay.remove(key);l.close();l.preparing=false;l.status="已取消同步并清空文件";Net.toServer("clear",l.track.tag());
    }
    public static void command(String key,String op,double value) {
        Local l=locals.get(key);if(l==null)return;CompoundTag tag=l.track.tag();
        if(op.equals("pause")&&l.sound!=null&&l.sound.hasClock()&&MC.player!=null&&l.track.owner.equals(MC.player.getUUID())){
            var beat=l.track.tag();beat.putDouble("actual",l.sound.actual());beat.putInt("age",(int)Math.min(1000,System.currentTimeMillis()-l.sound.actualAt()+rtt/2));Net.toServer("beat",beat);
        }
        if(op.equals("seek"))tag.putDouble("seek",value);if(op.equals("volume"))tag.putFloat("volume",(float)value);Net.toServer(op,tag);
    }
    public static void tick() {
        if(MC.getConnection()==null||MC.player==null||MC.level==null){if(tick>0||!locals.isEmpty())reset();return;}
        if(serverConnection!=MC.getConnection()){reset();serverConnection=MC.getConnection();}
        tick++;
        if(tick==1){var tag=new CompoundTag();tag.putBoolean("protected",protectVolume);Net.toServer("preferences",tag);}
        if(tick%100==1){var tag=new CompoundTag();tag.putLong("sent",System.currentTimeMillis());Net.toServer("clock",tag);}
        for(Local l:new ArrayList<>(locals.values())) {
            Track t=l.track;
            ImportJob job=imports.get(t.key());
            if(tick%20==0&&job!=null){long generation=connection;IO.execute(()->{
                boolean exists=Files.isRegularFile(job.source);
                if(!exists)MC.execute(()->{if(generation==connection&&imports.get(t.key())==job){clear(t.key());notice("源文件已删除，已取消同步并清理未完成文件。");}});
            });}
            if(tick%20==0 && l.download!=null){var tag=t.tag();tag.putLong("received",l.download.received);Net.toServer("received",tag);}
            if(l.ready && Boolean.TRUE.equals(pendingAutoplay.remove(t.key())))command(t.key(),"play",0);
            boolean sameWorld=MC.level.dimension().location().toString().equals(t.dimension);
            boolean validBlock=sameWorld && MC.level.hasChunkAt(t.pos) && MC.level.getBlockState(t.pos).is(Blocks.JUKEBOX);
            boolean owner=t.owner.equals(MC.player.getUUID());
            boolean audible=validBlock && MC.player.distanceToSqr(t.pos.getX()+.5,t.pos.getY()+.5,t.pos.getZ()+.5)<64*64;
            // Keep owner's source alive as master clock even when far away (distance makes it silent).
            if(!t.playing||l.pcm==null||l.sleeping||(!audible&&!owner)||l.failed||l.work.cancelled()||l.endedTransport==t.transport){l.stop();continue;}
            long now=serverNow();if(now<t.epoch)continue;double expected=t.at(now);
            if(l.sound!=null) {
                l.sound.gain(audible?volume(t.key()):0);
                if(tick%2==0)l.sound.poll();
                if(l.sound.ended()){
                    l.endedTransport=t.transport;l.stop();
                    if(owner)Net.toServer("ended",t.tag());
                    continue;
                }
                if(l.sound.hasClock() && System.currentTimeMillis()-l.sound.actualAt()<1500) {
                    double actual=l.sound.actual();
                    if(owner && tick%20==0) {
                        var tag=t.tag();tag.putDouble("actual",actual);tag.putInt("age",(int)Math.min(1000,System.currentTimeMillis()-l.sound.actualAt()+rtt/2));Net.toServer("beat",tag);
                    }
                    // Do not seek/recreate an almost-finished source to chase a clamped end estimate.
                    double difference=Math.abs(actual-expected);if(t.loop)difference=Math.min(difference,t.duration-difference);
                    if(!owner && actual<t.duration-2 && expected<t.duration-2 && difference>.75 && tick%40==0)l.stop();
                }
                if(l.sound!=null && System.currentTimeMillis()-l.soundCreated>3000 && !MC.getSoundManager().isActive(l.sound))l.stop();
            }
            if(l.sound==null && (expected<t.duration || t.loop)) {
                l.sound=new DiscSound(t,l.pcm,expected,audible?volume(t.key()):0);l.soundCreated=System.currentTimeMillis();MC.getSoundManager().play(l.sound);
            }
        }
    }
    public static void reset(){connection++;imports.values().forEach(j->j.work.cancel());imports.clear();cancelledRevisions.clear();locals.values().forEach(Local::close);locals.clear();personalVolumes.clear();rosters.clear();localPaths.clear();pendingAutoplay.clear();clockOffset=0;tick=0;}
    private static void failAsync(Local l,long generation,Exception e){MC.execute(()->{if(generation==connection&&locals.get(l.track.key())==l&&!l.work.cancelled())fail(l,e);});}
    private static void fail(Local l,Exception e){l.close();l.preparing=false;l.failed=true;l.ready=false;l.status="同步/解码失败";var tag=l.track.tag();tag.putString("error",String.valueOf(e.getMessage()));Net.toServer("failed",tag);notice("音频处理失败："+e.getMessage());SuperDisc.LOG.error("Audio prepare {}",l.track.hash,e);}
    public static void notice(String message){if(MC.player!=null)MC.gui.getChat().addMessage(Component.literal("[Super Disc] "+message));}
    private static void progress(String text){
        if(progressSignature!=null)replacePreviousProgress();
        byte[] signature=new byte[256];new Random().nextBytes(signature);progressSignature=new MessageSignature(signature);
        MC.gui.getChat().addMessage(Component.literal(text),progressSignature,null);
    }
    private static void replacePreviousProgress(){
        // Remove only our own signed local entry. Vanilla deleteMessage leaves a delayed deletion marker.
        try{
            var chat=MC.gui.getChat();
            for(var field:net.minecraft.client.gui.components.ChatComponent.class.getDeclaredFields())if(List.class.isAssignableFrom(field.getType())){
                field.setAccessible(true);Object object=field.get(chat);
                if(object instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof net.minecraft.client.GuiMessage){
                    list.removeIf(m->m instanceof net.minecraft.client.GuiMessage message && progressSignature.equals(message.signature()));chat.rescaleChat();break;
                }
            }
        }catch(Exception ex){SuperDisc.LOG.debug("Progress replacement unavailable",ex);}
    }
    public static void setNotifications(boolean value){notifications=value;prefs.setProperty("notifications",Boolean.toString(value));savePrefs();}
    private static void savePrefs(){Properties snapshot=new Properties();snapshot.putAll(prefs);IO.execute(()->{try{Files.createDirectories(prefsFile.getParent());try(var out=Files.newOutputStream(prefsFile)){snapshot.store(out,"Super Disc client preferences");}}catch(IOException e){SuperDisc.LOG.warn("Save client preference",e);}});}
}
