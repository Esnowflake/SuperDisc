package dev.superdisc;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

/** State is serializable; playing time uses the server clock, updated by the owner's audio device. */
public final class Track {
    public String dimension, hash = "", name = "", extension = "";
    public BlockPos pos;
    public UUID owner = new UUID(0,0), revision = UUID.randomUUID();
    public UUID controller = new UUID(0,0);
    public long size;
    public double duration, position;
    public long epoch, transport;
    public boolean playing, loop, syncing;
    public float volume = 1;
    public final java.util.Map<UUID,Float> listenerVolumes=new java.util.HashMap<>();
    public Track(String dimension, BlockPos pos) { this.dimension = dimension; this.pos = pos.immutable(); }
    public String key() { return dimension + "/" + pos.asLong(); }
    public double at(long now) {
        double time = position + (playing ? Math.max(0, now - epoch)/1000.0 : 0);
        return loop&&duration>0 ? Math.max(0,time)%duration : Math.min(duration, Math.max(0,time));
    }
    public CompoundTag tag() {
        var t = new CompoundTag();
        t.putString("dimension",dimension); t.putLong("pos",pos.asLong());
        t.putString("hash",hash); t.putString("name",name); t.putString("ext",extension); t.putLong("size",size);
        t.putUUID("owner",owner); t.putUUID("revision",revision);
        t.putUUID("controller",controller);
        t.putDouble("duration",duration); t.putDouble("position",position); t.putLong("epoch",epoch);t.putLong("transport",transport);
        t.putBoolean("playing",playing); t.putBoolean("loop",loop); t.putBoolean("syncing",syncing); t.putFloat("volume",volume);
        return t;
    }
    public static Track read(CompoundTag t) {
        Track v = new Track(t.getString("dimension"),BlockPos.of(t.getLong("pos")));
        v.hash=t.getString("hash"); v.name=t.getString("name"); v.extension=t.getString("ext"); v.size=t.getLong("size");
        if(t.hasUUID("owner")) v.owner=t.getUUID("owner"); if(t.hasUUID("revision")) v.revision=t.getUUID("revision");
        if(t.hasUUID("controller"))v.controller=t.getUUID("controller");
        v.duration=t.getDouble("duration"); v.position=t.getDouble("position"); v.epoch=t.getLong("epoch");v.transport=t.getLong("transport");
        v.playing=t.getBoolean("playing"); v.loop=t.getBoolean("loop"); v.syncing=t.getBoolean("syncing"); v.volume=t.getFloat("volume"); return v;
    }
}
