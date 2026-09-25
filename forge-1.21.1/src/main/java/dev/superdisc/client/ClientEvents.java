package dev.superdisc.client;

import dev.superdisc.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraft.client.resources.model.ModelResourceLocation;

@Mod.EventBusSubscriber(modid=SuperDisc.ID,bus=Mod.EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class ClientEvents {
    @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->{
        Net.clientHandler=ClientPlayback::packet;
        MinecraftForge.EVENT_BUS.addListener(ClientEvents::tick);
        MinecraftForge.EVENT_BUS.addListener(ClientEvents::logout);
        MinecraftForge.EVENT_BUS.addListener(ClientEvents::soundStarted);
    });}
    @SubscribeEvent public static void models(ModelEvent.BakingCompleted event){
        var model=event.getModels().get(ModelResourceLocation.inventory(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(SuperDisc.ID,"super_disc")));
        SuperDisc.LOG.info("Super Disc item model baked: {}",model==null?"MISSING":model.getClass().getName());
    }
    public static void tick(TickEvent.ClientTickEvent event){if(event.phase==TickEvent.Phase.END)ClientPlayback.tick();}
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event){ClientPlayback.reset();}
    public static void soundStarted(net.minecraftforge.client.event.sound.PlayStreamingSourceEvent event){if(event.getSound() instanceof DiscSound sound)sound.bind(event.getChannel());}
}
