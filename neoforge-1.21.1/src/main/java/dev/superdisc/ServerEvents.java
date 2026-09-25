package dev.superdisc;

import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.minecraft.world.InteractionResult;

public final class ServerEvents {
    @SubscribeEvent public static void click(PlayerInteractEvent.RightClickBlock event) {
        InteractionResult result = ServerPlayback.click(event.getEntity(), event.getLevel(), event.getHand(), event.getPos());
        if (result != InteractionResult.PASS) {
            event.setCanceled(true);
            event.setCancellationResult(result);
        }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) { ServerPlayback.tick(event.getServer()); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { ServerPlayback.stopped(event.getServer()); }
}
