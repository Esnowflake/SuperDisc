package dev.superdisc.client;

import dev.superdisc.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid=SuperDisc.ID, bus=EventBusSubscriber.Bus.MOD, value=Dist.CLIENT)
public final class ClientEvents {
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            Net.clientHandler=ClientPlayback::packet;
            NeoForge.EVENT_BUS.addListener(ClientEvents::tick);
            NeoForge.EVENT_BUS.addListener(ClientEvents::logout);
            NeoForge.EVENT_BUS.addListener(ClientEvents::soundStarted);
        });
    }
    public static void tick(ClientTickEvent.Post event) { ClientPlayback.tick(); }
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { ClientPlayback.reset(); }
    public static void soundStarted(PlayStreamingSourceEvent event) {
        if(event.getSound() instanceof DiscSound sound) sound.bind(event.getChannel());
    }
}
