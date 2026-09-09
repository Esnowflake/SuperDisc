package dev.superdisc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Main server thread owns all mutable playback state. Audio bytes use a bounded, paced transfer. */
public final class ServerPlayback {
    private static final Map<String,Session> sessions = new HashMap<>();
    private static final Map<UUID,String> viewers = new HashMap<>();
    private static long tick;
    private static final int AUDIENCE_RANGE = 64;
    private static final int MAX_ACTIVE = 32;
    private static final class Session {
        final Track track;
        Cache.Incoming upload;
        UUID uploader;
        long lastUpload, syncStart, lastBeat;
        UUID importRequest;
        boolean wantPlay;
        final Set<UUID> audience=new HashSet<>(), ready=new HashSet<>(), waiting=new HashSet<>();
        final Map<UUID,Download> downloads=new HashMap<>();
        final Map<UUID,Long> progress=new HashMap<>();
        Session(Track t) { track=t; }
        void closeTransfers() { if(upload!=null){upload.close();upload=null;} downloads.values().forEach(Download::close); downloads.clear(); }
    }
    private static final class Download {
        final InputStream in; long sent;
        Download(Track t) throws IOException { in=Files.newInputStream(Cache.file(t.hash,t.name,t.extension)); }
        void close(){try{in.close();}catch(IOException ignored){}}
    }
    private static Session session(MinecraftServer server,Track t) { return sessions.computeIfAbsent(t.key(),k->new Session(t)); }
    private static ServerLevel level(MinecraftServer server,Track t) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION,new ResourceLocation(t.dimension)));
    }
    private static boolean exists(MinecraftServer server,Track t) {
        var level=level(server,t); return level!=null && level.hasChunkAt(t.pos) && level.getBlockState(t.pos).is(Blocks.JUKEBOX);
    }
    private static boolean near(ServerPlayer p,Track t) { return p.level().dimension().location().toString().equals(t.dimension) && p.distanceToSqr(t.pos.getX()+.5,t.pos.getY()+.5,t.pos.getZ()+.5)<=64; }
    private static void message(ServerPlayer player,String text) { var t=new CompoundTag();t.putString("text",text);Net.toPlayer(player,"message",t); }
    private static void dirty(MinecraftServer server) { DiscData.get(server).setDirty(); }
    private static void sendState(MinecraftServer server,Session s) {
        for(ServerPlayer p:server.getPlayerList().getPlayers()) if(s.audience.contains(p.getUUID()) || s.track.key().equals(viewers.get(p.getUUID()))) Net.toPlayer(p,"state",s.track.tag());
        dirty(server);
    }
    private static void pause(MinecraftServer server,Session s) {
        s.track.position=s.track.at(System.currentTimeMillis());s.track.playing=false;s.wantPlay=false;s.track.syncing=false;sendState(server,s);
    }
    private static boolean revision(Session s,CompoundTag t) { return t.hasUUID("revision") && s.track.revision.equals(t.getUUID("revision")); }
    @SubscribeEvent public static void click(PlayerInteractEvent.RightClickBlock event) {
        if(event.getHand()!=InteractionHand.MAIN_HAND || !event.getLevel().getBlockState(event.getPos()).is(Blocks.JUKEBOX)) return;
        if(event.getLevel().isClientSide) return;
        if(!(event.getEntity() instanceof ServerPlayer player))return;
        MinecraftServer server=player.server;
        String key=player.level().dimension().location()+"/"+event.getPos().asLong();
        boolean held=player.getMainHandItem().is(SuperDisc.DISC.get());
        Track track=DiscData.get(server).tracks.get(key);
        if(!held && track==null) return;
        event.setCanceled(true);event.setCancellationResult(InteractionResult.SUCCESS);
        if(player.isSpectator())return;
        if(player.level().getBlockEntity(event.getPos()) instanceof JukeboxBlockEntity box && !box.getItem(0).isEmpty()) {
            message(player,"请先取出唱片机中的原版唱片。");return;
        }
        if(track==null) {
            if(DiscData.get(server).tracks.size()>=256) { message(player,"已达到本世界 256 台唱片机的绑定上限。");return; }
            track=new Track(player.level().dimension().location().toString(),event.getPos());
            DiscData.get(server).tracks.put(key,track);dirty(server);
        }
        viewers.put(player.getUUID(),key);session(server,track);
        Net.toPlayer(player,"open",track.tag());
    }
    public static void receive(ServerPlayer p,Net.Packet packet) throws Exception {
        MinecraftServer server=p.server;CompoundTag t=packet.tag();String op=packet.op();
        if(op.equals("clock")) { var reply=new CompoundTag();reply.putLong("sent",t.getLong("sent"));reply.putLong("server",System.currentTimeMillis());Net.toPlayer(p,"clock",reply);return; }
        if(op.equals("close")) { viewers.remove(p.getUUID());return; }
        Track request=Track.read(t);Track track=DiscData.get(server).tracks.get(request.key());
        if(track==null)return;
        Session s=session(server,track);
        if(op.equals("beat") || op.equals("ended")) {
            if(!revision(s,t)||t.getLong("transport")!=track.transport||!track.owner.equals(p.getUUID())||!track.playing||!s.ready.contains(p.getUUID()))return;
            s.lastBeat=System.currentTimeMillis();
            if(op.equals("ended")){finish(server,s);return;}
            double position=t.getDouble("actual");
            if(Double.isFinite(position)&&position>=0&&position<=track.duration) {
                track.position=position;track.epoch=System.currentTimeMillis()-Math.min(1000,Math.max(0,t.getInt("age")));
                sendState(server,s);
            }
            return;
        }
        if(op.equals("ready") || op.equals("need") || op.equals("failed") || op.equals("received")) {
            if(!revision(s,t)||!s.audience.contains(p.getUUID()))return;
            if(op.equals("ready")) {
                double duration=t.getDouble("decodedDuration");
                if(!Double.isFinite(duration)||duration<=0||duration>Cache.MAX_SECONDS)return;
                if(track.duration<=0||track.owner.equals(p.getUUID()))track.duration=duration;
                else if(track.duration>0&&Math.abs(track.duration-duration)>.25)return;
                s.ready.add(p.getUUID());s.waiting.remove(p.getUUID());s.progress.put(p.getUUID(),track.size);
                if(track.syncing && s.ready.containsAll(s.audience)) {
                    if(s.wantPlay)start(server,s);else synced(server,s);
                }
                else Net.toPlayer(p,track.playing?"complete":"cached",track.tag());
            } else if(op.equals("need")) {
                if(s.upload==null && !s.downloads.containsKey(p.getUUID()))s.downloads.put(p.getUUID(),new Download(track));
            } else if(op.equals("received")) s.progress.put(p.getUUID(),Math.max(0,Math.min(track.size,t.getLong("received"))));
            else {
                s.waiting.remove(p.getUUID());pause(server,s);
                for(ServerPlayer listener:server.getPlayerList().getPlayers()) if(s.audience.contains(listener.getUUID()))message(listener,p.getGameProfile().getName()+" 的音频解码/同步失败，播放已暂停。");
                SuperDisc.LOG.warn("Client {} failed to prepare {}: {}",p.getUUID(),track.hash,t.getString("error"));
            }
            return;
        }
        // An already-authorized upload may finish after closing the screen or walking away.
        boolean uploading=(op.equals("chunk")&&p.getUUID().equals(s.uploader)&&s.upload!=null)
            || (op.equals("cancel_import")&&p.getUUID().equals(s.uploader)&&t.hasUUID("importRequest")&&t.getUUID("importRequest").equals(s.importRequest));
        if(!uploading && (!near(p,track) || !exists(server,track) || !track.key().equals(viewers.get(p.getUUID())) || p.isSpectator()))return;
        if(op.equals("begin")) {
            if(request.size<=0||request.size>Cache.MAX_BYTES||!Double.isFinite(request.duration)||request.duration<0||request.duration>Cache.MAX_SECONDS||!t.hasUUID("importRequest")) {message(p,"音频限制：32 MiB、20 分钟以内。");return;}
            Cache.file(request.hash,request.name,request.extension); // validate before mutating state
            cancelTransfers(server,s);s.ready.clear();s.waiting.clear();s.progress.clear();
            track.hash=request.hash;track.name=Cache.safeName(request.name);track.extension=request.extension;track.size=request.size;track.duration=request.duration;
            track.owner=p.getUUID();track.revision=t.getUUID("importRequest");track.position=0;
            track.playing=false;track.syncing=true;s.wantPlay=false;s.syncStart=System.currentTimeMillis();s.importRequest=t.getUUID("importRequest");
            s.uploader=p.getUUID();s.lastUpload=System.currentTimeMillis();
            if(Cache.valid(track)) { Net.toPlayer(p,"uploaded",track.tag());prepare(server,s,false); }
            else { s.upload=new Cache.Incoming(track);Net.toPlayer(p,"upload",track.tag());sendState(server,s); }
            return;
        }
        if(op.equals("cancel_import")) {
            if(p.getUUID().equals(s.uploader)&&t.hasUUID("importRequest")&&t.getUUID("importRequest").equals(s.importRequest))clear(server,s);
            return;
        }
        if(!revision(s,t))return;
        if(op.equals("chunk")) {
            if(s.upload==null||!p.getUUID().equals(s.uploader))return;
            try {
                s.upload.append(t.getLong("offset"),packet.bytes());s.lastUpload=System.currentTimeMillis();
                var ack=track.tag();ack.putLong("offset",s.upload.received);Net.toPlayer(p,"upload_ack",ack);
                if(s.upload.received==track.size) {
                    s.upload.finish();s.upload.close();s.upload=null;Net.toPlayer(p,"uploaded",track.tag());prepare(server,s,s.wantPlay);
                    SuperDisc.LOG.info("Audio upload verified {} {} bytes",track.hash,track.size);
                }
            }catch(Exception e){s.closeTransfers();message(p,"音频上传失败："+e.getMessage());pause(server,s);}
        } else if(op.equals("clear")) {
            clear(server,s);
        } else if(op.equals("play") || op.equals("restart") || op.equals("seek")) {
            if(track.hash.isEmpty()) {message(p,"请先选择音频文件。");return;}
            if(op.equals("seek")&&!p.getUUID().equals(track.owner)){message(p,"只有导入音乐的玩家可以调整进度。");return;}
            if(op.equals("seek")) {
                double at=t.getDouble("seek");if(!Double.isFinite(at))return;
                boolean resume=track.playing||s.wantPlay;pause(server,s);track.position=Math.max(0,Math.min(track.duration,at));
                if(resume)prepare(server,s,true);else sendState(server,s);
            } else {
                if(sessions.values().stream().filter(x->x.track.playing||x.wantPlay).count()>=MAX_ACTIVE && !track.playing) {message(p,"最多同时播放 32 台唱片机。");return;}
                if(op.equals("restart")){pause(server,s);track.position=0;}
                if(track.position>=track.duration)track.position=0;
                if(!track.playing)prepare(server,s,true);
            }
        } else if(op.equals("pause")) pause(server,s);
        else if(op.equals("mode")) {track.loop=!track.loop;sendState(server,s);}
        else if(op.equals("volume")) {float volume=t.getFloat("volume");if(Float.isFinite(volume)){track.volume=Math.max(0,Math.min(2,volume));sendState(server,s);}}
        else if(op.equals("unbind")) {
            pause(server,s);s.closeTransfers();var state=track.tag();state.putBoolean("removed",true);
            for(ServerPlayer listener:server.getPlayerList().getPlayers())Net.toPlayer(listener,"remove",state);
            DiscData.get(server).tracks.remove(track.key());sessions.remove(track.key());dirty(server);
        }
    }
    private static Set<UUID> audience(MinecraftServer server,Track track) {
        Set<UUID> result=new HashSet<>();
        for(ServerPlayer p:server.getPlayerList().getPlayers()) {
            if(p.getUUID().equals(track.owner) || (p.level().dimension().location().toString().equals(track.dimension) && p.distanceToSqr(track.pos.getX()+.5,track.pos.getY()+.5,track.pos.getZ()+.5)<AUDIENCE_RANGE*AUDIENCE_RANGE)) result.add(p.getUUID());
        }
        return result;
    }
    private static void prepare(MinecraftServer server,Session s,boolean play) {
        s.wantPlay=play;s.syncStart=System.currentTimeMillis();s.track.syncing=true;
        if(s.upload!=null){sendState(server,s);return;}
        if(!Files.isRegularFile(Cache.file(s.track.hash,s.track.name,s.track.extension))) {
            s.wantPlay=false;s.track.syncing=false;sendState(server,s);
            for(var p:server.getPlayerList().getPlayers())if(s.track.key().equals(viewers.get(p.getUUID())))message(p,"服务器音频缓存已丢失，请重新选择该文件。");
            return;
        }
        s.audience.clear();s.audience.addAll(audience(server,s.track));
        for(UUID id:s.audience) if(!s.ready.contains(id)) {
            ServerPlayer listener=server.getPlayerList().getPlayer(id); if(listener!=null) {Net.toPlayer(listener,"prepare",s.track.tag());s.waiting.add(id);}
        }
        sendState(server,s);
        if(play && s.ready.containsAll(s.audience))start(server,s);
        else if(s.ready.containsAll(s.audience))synced(server,s);
    }
    private static void synced(MinecraftServer server,Session s) {
        s.track.syncing=false;sendState(server,s);
        for(UUID id:s.audience){var p=server.getPlayerList().getPlayer(id);if(p!=null)Net.toPlayer(p,"cached",s.track.tag());}
    }
    private static void cancelTransfers(MinecraftServer server,Session s) {
        for(var p:server.getPlayerList().getPlayers())if(s.audience.contains(p.getUUID())||s.track.key().equals(viewers.get(p.getUUID()))||p.getUUID().equals(s.uploader))Net.toPlayer(p,"cancel",s.track.tag());
        s.closeTransfers();
    }
    private static void clear(MinecraftServer server,Session s) {
        cancelTransfers(server,s);Track t=s.track;s.ready.clear();s.waiting.clear();s.progress.clear();s.wantPlay=false;
        t.playing=false;t.syncing=false;t.revision=UUID.randomUUID();t.hash="";t.name="";t.extension="";t.size=0;t.duration=0;t.position=0;t.owner=new UUID(0,0);sendState(server,s);
    }
    private static void finish(MinecraftServer server,Session s) {
        if(s.track.loop){s.track.position=0;start(server,s,0,false);}
        else{s.track.position=s.track.duration;s.track.playing=false;sendState(server,s);}
    }
    private static void start(MinecraftServer server,Session s) {
        start(server,s,1000,true);
    }
    private static void start(MinecraftServer server,Session s,long delay,boolean notify) {
        if(s.track.duration<=0)return;
        s.wantPlay=false;s.track.syncing=false;s.track.playing=true;s.track.transport++;s.track.epoch=System.currentTimeMillis()+delay;s.lastBeat=s.track.epoch;
        sendState(server,s);
        if(notify)for(UUID id:s.audience) {var p=server.getPlayerList().getPlayer(id);if(p!=null)Net.toPlayer(p,"complete",s.track.tag());}
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END)return;
        MinecraftServer server=event.getServer();tick++;
        int budget=64; // <= 1 MiB/tick globally; uploads are stop-and-wait
        for(Session s:new ArrayList<>(sessions.values())) {
            Track t=s.track;long now=System.currentTimeMillis();
            try {
                for(var it=s.downloads.entrySet().iterator();it.hasNext() && budget>0;) {
                    var entry=it.next();var p=server.getPlayerList().getPlayer(entry.getKey());var d=entry.getValue();
                    if(p==null||!s.audience.contains(entry.getKey())){d.close();it.remove();continue;}
                    for(int n=0;n<4&&budget>0;n++,budget--) {
                        byte[] bytes=d.in.readNBytes(Net.CHUNK);
                        if(bytes.length==0){d.close();it.remove();break;}
                        var tag=t.tag();tag.putLong("offset",d.sent);Net.toPlayer(p,new Net.Packet("download",tag,bytes));d.sent+=bytes.length;
                    }
                }
                if(tick%20!=0)continue;
                if(!exists(server,t)) {
                    var l=level(server,t);
                    if(l!=null && l.hasChunkAt(t.pos)) {
                        s.closeTransfers();for(var p:server.getPlayerList().getPlayers())Net.toPlayer(p,"remove",t.tag());
                        DiscData.get(server).tracks.remove(t.key());sessions.remove(t.key());dirty(server);
                    } else if(t.playing||s.wantPlay)pause(server,s);
                    continue;
                }
                if(s.upload!=null && now-s.lastUpload>30000){s.closeTransfers();pause(server,s);}
                if(s.upload!=null) {
                    var p=server.getPlayerList().getPlayer(s.uploader);if(p!=null)progress(p,t,"上传",s.upload.received,t.size);
                }
                if(!t.syncing&&!s.waiting.isEmpty()) {
                    s.waiting.removeIf(id->server.getPlayerList().getPlayer(id)==null);
                    long done=s.audience.stream().mapToLong(id->s.progress.getOrDefault(id,0L)).sum();
                    for(UUID id:s.audience){var p=server.getPlayerList().getPlayer(id);if(p!=null)progress(p,t,"同步 / 解码 ("+s.ready.size()+"/"+s.audience.size()+")",done,Math.max(1,s.audience.size())*t.size);}
                }
                if((t.playing||t.syncing)&&s.upload==null) {
                    Set<UUID> desired=audience(server,t);
                    for(UUID old:new HashSet<>(s.audience))if(!desired.contains(old)){
                        s.ready.remove(old);s.waiting.remove(old);var p=server.getPlayerList().getPlayer(old);if(p!=null)Net.toPlayer(p,"sleep",t.tag());
                    }
                    for(UUID id:desired)if(!s.audience.contains(id)){
                        s.waiting.add(id);var p=server.getPlayerList().getPlayer(id);if(p!=null)Net.toPlayer(p,"prepare",t.tag());
                    }
                    s.audience.clear();s.audience.addAll(desired);
                    if(t.syncing) {
                        long done=s.audience.stream().mapToLong(id->s.progress.getOrDefault(id,0L)).sum();
                        for(UUID id:s.audience){var p=server.getPlayerList().getPlayer(id);if(p!=null)progress(p,t,"同步 / 解码 ("+s.ready.size()+"/"+s.audience.size()+")",done,Math.max(1,s.audience.size())*t.size);}
                        if(s.ready.containsAll(s.audience)){if(s.wantPlay)start(server,s);else synced(server,s);}
                        else if(now-s.syncStart>120000){pause(server,s);for(UUID id:s.audience){var p=server.getPlayerList().getPlayer(id);if(p!=null)message(p,"同步超时，已暂停。可重试播放。");}}
                    }
                    if(t.playing && now>=t.epoch) {
                        // A healthy owner's device decides EOF. Wall-clock estimates must not cut its tail.
                        if(t.at(now)>=t.duration && now-s.lastBeat>5000)finish(server,s);
                        else {var l=level(server,t);l.sendParticles(ParticleTypes.NOTE,t.pos.getX()+.5,t.pos.getY()+1.2,t.pos.getZ()+.5,0,(tick/20%24)/24.0,0,0,1);}
                    }
                }
            }catch(Exception e){SuperDisc.LOG.error("Super Disc session {}",t.key(),e);s.closeTransfers();pause(server,s);}
        }
    }
    private static void progress(ServerPlayer p,Track t,String phase,long done,long total){var tag=t.tag();tag.putString("phase",phase);tag.putLong("done",done);tag.putLong("total",total);Net.toPlayer(p,"progress",tag);}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){sessions.values().forEach(Session::closeTransfers);sessions.clear();viewers.clear();tick=0;}
}
