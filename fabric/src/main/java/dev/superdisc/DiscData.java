package dev.superdisc;

import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

public final class DiscData extends SavedData {
    public final Map<String,Track> tracks = new HashMap<>();
    private static final net.minecraft.world.level.saveddata.SavedDataType<DiscData> TYPE =
        new net.minecraft.world.level.saveddata.SavedDataType<>(
            net.minecraft.resources.Identifier.fromNamespaceAndPath(SuperDisc.ID, "tracks"), DiscData::new,
            CompoundTag.CODEC.xmap(DiscData::load, data -> data.save(new CompoundTag())), null);
    public static DiscData get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(TYPE); }
    static DiscData load(CompoundTag root) {
        var data=new DiscData();
        for(Tag tag:root.getListOrEmpty("tracks")) {
            Track track=Track.read((CompoundTag)tag); track.playing=false; track.syncing=false;
            for(Tag entry:((CompoundTag)tag).getListOrEmpty("listenerVolumes")){
                CompoundTag v=(CompoundTag)entry;float gain=v.getFloatOr("volume", 0f);
                if(dev.superdisc.Nbt.hasUuid(v,"id")&&Float.isFinite(gain))track.listenerVolumes.put(dev.superdisc.Nbt.uuid(v,"id"),VolumePolicy.clamp(gain));
            }
            data.tracks.put(track.key(),track);
        }
        return data;
    }
    public CompoundTag save(CompoundTag root) {
        ListTag list=new ListTag();
        for(Track track:tracks.values()) {
            var t=track.tag(); t.putDouble("position",track.at(System.currentTimeMillis())); t.putBoolean("playing",false); t.putBoolean("syncing",false);
            ListTag volumes=new ListTag();track.listenerVolumes.forEach((id,value)->{var v=new CompoundTag();dev.superdisc.Nbt.putUuid(v,"id",id);v.putFloat("volume",value);volumes.add(v);});t.put("listenerVolumes",volumes);list.add(t);
        }
        root.put("tracks",list); return root;
    }
}
