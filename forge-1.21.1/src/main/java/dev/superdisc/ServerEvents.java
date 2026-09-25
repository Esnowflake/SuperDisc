package dev.superdisc;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.world.InteractionResult;

public final class ServerEvents {
    @SubscribeEvent public static void click(PlayerInteractEvent.RightClickBlock event) {
        InteractionResult result = ServerPlayback.click(event.getEntity(), event.getLevel(), event.getHand(), event.getPos());
        if (result != InteractionResult.PASS) {
            event.setCanceled(true);
            event.setCancellationResult(result);
        }
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) ServerPlayback.tick(event.getServer());
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { ServerPlayback.stopped(event.getServer()); }
}
