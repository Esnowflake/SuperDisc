package dev.superdisc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Two explicitly directed envelopes. All commands and permissions are validated server-side. */
public final class Net {
    public static final int CHUNK = 16384;
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(SuperDisc.ID, "main"), () -> "4", "4"::equals, "4"::equals);
    public static Consumer<Packet> clientHandler = p -> {};
    public record Packet(String op, CompoundTag tag, byte[] bytes) {
        public Packet(String op, CompoundTag tag) { this(op, tag, new byte[0]); }
        static void encode(Packet p, FriendlyByteBuf b) { b.writeUtf(p.op, 32); b.writeNbt(p.tag); b.writeByteArray(p.bytes); }
        static Packet decode(FriendlyByteBuf b) { return new Packet(b.readUtf(32), b.readNbt(), b.readByteArray(CHUNK)); }
    }
    public record Clientbound(Packet p) {}
    public record Serverbound(Packet p) {}
    public static void register() {
        CHANNEL.registerMessage(0, Serverbound.class, (p,b) -> Packet.encode(p.p,b), b -> new Serverbound(Packet.decode(b)), Net::server, java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, Clientbound.class, (p,b) -> Packet.encode(p.p,b), b -> new Clientbound(Packet.decode(b)), Net::client, java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    private static void server(Serverbound packet, Supplier<NetworkEvent.Context> supplier) {
        var ctx = supplier.get(); var player = ctx.getSender();
        ctx.enqueueWork(() -> { if(player != null && packet.p.tag != null) {
            try { ServerPlayback.receive(player, packet.p); } catch(Exception ex) { SuperDisc.LOG.warn("Rejected Super Disc packet {}", packet.p.op, ex); }
        }});
        ctx.setPacketHandled(true);
    }
    private static void client(Clientbound packet, Supplier<NetworkEvent.Context> supplier) {
        var ctx = supplier.get(); ctx.enqueueWork(() -> {
            if(packet.p.tag != null) try { clientHandler.accept(packet.p); } catch(Exception ex) { SuperDisc.LOG.error("Super Disc client packet {}", packet.p.op, ex); }
        }); ctx.setPacketHandled(true);
    }
    public static void toServer(String op, CompoundTag tag) { CHANNEL.sendToServer(new Serverbound(new Packet(op,tag))); }
    public static void toServer(Packet p) { CHANNEL.sendToServer(new Serverbound(p)); }
    public static void toPlayer(ServerPlayer p, String op, CompoundTag t) { toPlayer(p,new Packet(op,t)); }
    public static void toPlayer(ServerPlayer p, Packet packet) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> p),new Clientbound(packet)); }
}
