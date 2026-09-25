import dev.superdisc.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedDataType;

public class FabricChecks {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        CompoundTag tag = new CompoundTag();
        UUID id = UUID.randomUUID();
        check(!Nbt.hasUuid(tag,"id"), "missing UUID");
        tag.putString("id","malformed");
        check(!Nbt.hasUuid(tag,"id"), "malformed UUID");
        Nbt.putUuid(tag,"id",id);
        check(Nbt.uuid(tag,"id").equals(id), "UUID codec round trip");
        Track track = new Track("minecraft:overworld",new BlockPos(-7,64,32));
        track.owner=id;track.controller=UUID.randomUUID();track.duration=15;track.loop=true;
        track.hash="a".repeat(64);track.position=4;track.epoch=1000;track.playing=true;
        Track decoded=Track.read(track.tag());
        check(decoded.key().equals(track.key()),"dimension and position");
        check(decoded.owner.equals(id)&&decoded.controller.equals(track.controller),"ownership");
        check(decoded.revision.equals(track.revision),"revision");
        check(decoded.hash.equals(track.hash)&&decoded.at(3000)==6,"clock");
        check(decoded.at(16000)==4,"loop clock");
        DiscData data=new DiscData();
        track.listenerVolumes.put(id,3.5f);data.tracks.put(track.key(),track);
        var typeField=DiscData.class.getDeclaredField("TYPE");typeField.setAccessible(true);
        @SuppressWarnings("unchecked") var type=(SavedDataType<DiscData>)typeField.get(null);
        Tag saved=type.codec().encodeStart(NbtOps.INSTANCE,data).getOrThrow();
        DiscData loaded=type.codec().parse(NbtOps.INSTANCE,saved).getOrThrow();
        Track restored=loaded.tracks.get(track.key());
        check(restored!=null&&!restored.playing&&!restored.syncing,"restart paused");
        check(restored.listenerVolumes.get(id)==3.5f,"listener volume persistence");
        check(restored.owner.equals(id),"owner persistence");
        check(type.id().equals(Identifier.fromNamespaceAndPath("super_disc","tracks")),"save identity");
        var channel=Class.forName("com.mojang.blaze3d.audio.Channel");
        check(channel.getDeclaredMethod("attachBufferStream",Class.forName("net.minecraft.client.sounds.AudioStream"))!=null,"mixin target signature");
        check(Arrays.stream(channel.getDeclaredFields()).filter(f->f.getType()==int.class&&java.lang.reflect.Modifier.isFinal(f.getModifiers())&&!java.lang.reflect.Modifier.isStatic(f.getModifiers())).count()==1,"OpenAL source field");
        check(Arrays.stream(Class.forName("net.minecraft.client.sounds.SoundEngine").getDeclaredFields()).filter(f->java.util.concurrent.Executor.class.isAssignableFrom(f.getType())).count()==1,"audio executor");
        check(assertions>=15,"nonempty tests");
        System.out.println("PASS: "+assertions+" Fabric codec, persistence and audio-contract assertions");
    }
}
