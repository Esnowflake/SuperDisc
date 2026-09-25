package dev.superdisc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.*;
import net.minecraftforge.event.network.CustomPayloadEvent;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Two explicitly directed envelopes. All commands and permissions are validated server-side. */
public final class Net {
    public static final int CHUNK = 16384;
    public static final SimpleChannel CHANNEL = ChannelBuilder.named(ResourceLocation.fromNamespaceAndPath(SuperDisc.ID, "main")).networkProtocolVersion(4).simpleChannel();
    public static Consumer<Packet> clientHandler = p -> {};
    public record Packet(String op, CompoundTag tag, byte[] bytes) {
        public Packet(String op, CompoundTag tag) { this(op, tag, new byte[0]); }
        static void encode(Packet p, FriendlyByteBuf b) { b.writeUtf(p.op, 32); b.writeNbt(p.tag); b.writeByteArray(p.bytes); }
        static Packet decode(FriendlyByteBuf b) { return new Packet(b.readUtf(32), b.readNbt(), b.readByteArray(CHUNK)); }
    }
    public record Clientbound(Packet p) {}
    public record Serverbound(Packet p) {}
    public static void register() {
        CHANNEL.messageBuilder(Serverbound.class, 0, NetworkDirection.PLAY_TO_SERVER)
            .encoder((p,b) -> Packet.encode(p.p,b)).decoder(b -> new Serverbound(Packet.decode(b)))
            .consumerMainThread(Net::server).add();
        CHANNEL.messageBuilder(Clientbound.class, 1, NetworkDirection.PLAY_TO_CLIENT)
            .encoder((p,b) -> Packet.encode(p.p,b)).decoder(b -> new Clientbound(Packet.decode(b)))
            .consumerMainThread(Net::client).add();
        CHANNEL.build();
    }
    private static void server(Serverbound packet, CustomPayloadEvent.Context ctx) {
        var player = ctx.getSender();
        ctx.enqueueWork(() -> { if(player != null && packet.p.tag != null) {
            try { ServerPlayback.receive(player, packet.p); } catch(Exception ex) { SuperDisc.LOG.warn("Rejected Super Disc packet {}", packet.p.op, ex); }
        }});
        ctx.setPacketHandled(true);
    }
    private static void client(Clientbound packet, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            if(packet.p.tag != null) try { clientHandler.accept(packet.p); } catch(Exception ex) { SuperDisc.LOG.error("Super Disc client packet {}", packet.p.op, ex); }
        }); ctx.setPacketHandled(true);
    }
    public static void toServer(String op, CompoundTag tag) { toServer(new Packet(op,tag)); }
    public static void toServer(Packet p) { CHANNEL.send(new Serverbound(p),PacketDistributor.SERVER.noArg()); }
    public static void toPlayer(ServerPlayer p, String op, CompoundTag t) { toPlayer(p,new Packet(op,t)); }
    public static void toPlayer(ServerPlayer p, Packet packet) { CHANNEL.send(new Clientbound(packet),PacketDistributor.PLAYER.with(p)); }
}
