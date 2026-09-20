package dev.superdisc;

import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

public final class DiscData extends SavedData {
    public final Map<String,Track> tracks = new HashMap<>();
    public static DiscData get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(DiscData::load,DiscData::new,"super_disc"); }
    static DiscData load(CompoundTag root) {
        var data=new DiscData();
        for(Tag tag:root.getList("tracks",Tag.TAG_COMPOUND)) {
            Track track=Track.read((CompoundTag)tag); track.playing=false; track.syncing=false;
            for(Tag entry:((CompoundTag)tag).getList("listenerVolumes",Tag.TAG_COMPOUND)){
                CompoundTag v=(CompoundTag)entry;float gain=v.getFloat("volume");
                if(v.hasUUID("id")&&Float.isFinite(gain))track.listenerVolumes.put(v.getUUID("id"),VolumePolicy.clamp(gain));
            }
            data.tracks.put(track.key(),track);
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag root) {
        ListTag list=new ListTag();
        for(Track track:tracks.values()) {
            var t=track.tag(); t.putDouble("position",track.at(System.currentTimeMillis())); t.putBoolean("playing",false); t.putBoolean("syncing",false);
            ListTag volumes=new ListTag();track.listenerVolumes.forEach((id,value)->{var v=new CompoundTag();v.putUUID("id",id);v.putFloat("volume",value);volumes.add(v);});t.put("listenerVolumes",volumes);list.add(t);
        }
        root.put("tracks",list); return root;
    }
}
