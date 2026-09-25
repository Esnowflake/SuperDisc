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
        dev.superdisc.Nbt.putUuid(t,"owner",owner); dev.superdisc.Nbt.putUuid(t,"revision",revision);
        dev.superdisc.Nbt.putUuid(t,"controller",controller);
        t.putDouble("duration",duration); t.putDouble("position",position); t.putLong("epoch",epoch);t.putLong("transport",transport);
        t.putBoolean("playing",playing); t.putBoolean("loop",loop); t.putBoolean("syncing",syncing); t.putFloat("volume",volume);
        return t;
    }
    public static Track read(CompoundTag t) {
        Track v = new Track(t.getStringOr("dimension", ""),BlockPos.of(t.getLongOr("pos", 0L)));
        v.hash=t.getStringOr("hash", ""); v.name=t.getStringOr("name", ""); v.extension=t.getStringOr("ext", ""); v.size=t.getLongOr("size", 0L);
        if(dev.superdisc.Nbt.hasUuid(t,"owner")) v.owner=dev.superdisc.Nbt.uuid(t,"owner"); if(dev.superdisc.Nbt.hasUuid(t,"revision")) v.revision=dev.superdisc.Nbt.uuid(t,"revision");
        if(dev.superdisc.Nbt.hasUuid(t,"controller"))v.controller=dev.superdisc.Nbt.uuid(t,"controller");
        v.duration=t.getDoubleOr("duration", 0d); v.position=t.getDoubleOr("position", 0d); v.epoch=t.getLongOr("epoch", 0L);v.transport=t.getLongOr("transport", 0L);
        v.playing=t.getBooleanOr("playing", false); v.loop=t.getBooleanOr("loop", false); v.syncing=t.getBooleanOr("syncing", false); v.volume=t.getFloatOr("volume", 0f); return v;
    }
}
