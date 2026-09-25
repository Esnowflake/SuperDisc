package dev.superdisc;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import java.util.function.Consumer;

public final class Net {
    public static final int CHUNK = 16384;
    public static Consumer<Packet> clientHandler = p -> {};
    public static Consumer<Packet> clientSender = p -> { throw new IllegalStateException("Client not initialized"); };
    public record Packet(String op, CompoundTag tag, byte[] bytes) implements CustomPacketPayload {
        public static final Type<Packet> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SuperDisc.ID, "main_v4"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Packet> CODEC = StreamCodec.of(
            (buffer, packet) -> { buffer.writeUtf(packet.op, 32); buffer.writeNbt(packet.tag); buffer.writeByteArray(packet.bytes); },
            buffer -> new Packet(buffer.readUtf(32), buffer.readNbt(), buffer.readByteArray(CHUNK)));
        public Packet(String op, CompoundTag tag) { this(op, tag, new byte[0]); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Packet.TYPE, Packet.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Packet.TYPE, Packet.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Packet.TYPE, (packet, context) -> {
            if (packet.tag == null) return;
            try { ServerPlayback.receive(context.player(), packet); }
            catch (Exception ex) { SuperDisc.LOG.warn("Rejected Super Disc packet {}", packet.op, ex); }
        });
    }
    public static void toServer(String op, CompoundTag tag) { toServer(new Packet(op, tag)); }
    public static void toServer(Packet packet) { clientSender.accept(packet); }
    public static void toPlayer(ServerPlayer player, String op, CompoundTag tag) { toPlayer(player, new Packet(op, tag)); }
    public static void toPlayer(ServerPlayer player, Packet packet) { ServerPlayNetworking.send(player, packet); }
}
