package dev.superdisc.client;

import dev.superdisc.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class ClientEvents implements ClientModInitializer {
    @Override public void onInitializeClient() {
        Net.clientHandler = ClientPlayback::packet;
        Net.clientSender = ClientPlayNetworking::send;
        ClientPlayNetworking.registerGlobalReceiver(Net.Packet.TYPE, (packet, context) -> {
            if (packet.tag() == null) return;
            try { Net.clientHandler.accept(packet); }
            catch (Exception ex) { SuperDisc.LOG.error("Super Disc client packet {}", packet.op(), ex); }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientPlayback.tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientPlayback.reset());
    }
}
