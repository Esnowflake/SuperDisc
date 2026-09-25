import dev.superdisc.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

public class Minecraft121Checks {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if(!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Track track=new Track("minecraft:overworld",new BlockPos(-9,72,12));
        track.owner=UUID.randomUUID();track.controller=UUID.randomUUID();
        track.hash="a".repeat(64);track.duration=12;track.position=3;track.epoch=1000;
        track.loop=true;track.playing=true;
        Track roundtrip=Track.read(track.tag());
        check(roundtrip.key().equals(track.key()),"position");
        check(roundtrip.owner.equals(track.owner),"owner");
        check(roundtrip.controller.equals(track.controller),"controller");
        check(roundtrip.revision.equals(track.revision),"revision");
        check(roundtrip.at(3000)==5,"clock");
        check(roundtrip.at(14000)==4,"loop");
        DiscData data=new DiscData();data.tracks.put(track.key(),track);
        track.listenerVolumes.put(track.owner,3.25f);
        CompoundTag saved=data.save(new CompoundTag(),null);
        var load=DiscData.class.getDeclaredMethod("load",CompoundTag.class);load.setAccessible(true);
        DiscData restored=(DiscData)load.invoke(null,saved);
        Track recovered=restored.tracks.get(track.key());
        check(recovered!=null,"saved track");
        check(!recovered.playing&&!recovered.syncing,"restart paused");
        check(recovered.listenerVolumes.get(track.owner)==3.25f,"saved volume");
        check(recovered.owner.equals(track.owner),"saved owner");
        var channel=Class.forName("com.mojang.blaze3d.audio.Channel");
        check(channel.getDeclaredMethod("attachBufferStream",Class.forName("net.minecraft.client.sounds.AudioStream"))!=null,"stream hook");
        check(Arrays.stream(channel.getDeclaredFields()).filter(f->f.getType()==int.class&&java.lang.reflect.Modifier.isFinal(f.getModifiers())&&!java.lang.reflect.Modifier.isStatic(f.getModifiers())).count()==1,"OpenAL source");
        check(Arrays.stream(Class.forName("net.minecraft.client.sounds.SoundEngine").getDeclaredFields()).filter(f->java.util.concurrent.Executor.class.isAssignableFrom(f.getType())).count()==1,"audio executor");
        check(assertions>=13,"nonempty suite");
        System.out.println("PASS: "+assertions+" Minecraft 1.21.1 persistence and audio-contract assertions");
    }
}
