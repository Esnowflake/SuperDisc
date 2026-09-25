package dev.superdisc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.function.Consumer;

public final class Net {
    public static final int CHUNK = 16384;
    public static Consumer<Packet> clientHandler = p -> {};
    public record Packet(String op, CompoundTag tag, byte[] bytes) implements CustomPacketPayload {
        public static final Type<Packet> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SuperDisc.ID, "main_v4"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Packet> CODEC = StreamCodec.of(
            (buffer, packet) -> { buffer.writeUtf(packet.op, 32); buffer.writeNbt(packet.tag); buffer.writeByteArray(packet.bytes); },
            buffer -> new Packet(buffer.readUtf(32), buffer.readNbt(), buffer.readByteArray(CHUNK)));
        public Packet(String op, CompoundTag tag) { this(op, tag, new byte[0]); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("4").playBidirectional(Packet.TYPE, Packet.CODEC, (packet, context) -> {
            if (packet.tag == null) return;
            try {
                if (context.flow() == PacketFlow.SERVERBOUND) ServerPlayback.receive((ServerPlayer)context.player(), packet);
                else clientHandler.accept(packet);
            } catch (Exception ex) { SuperDisc.LOG.warn("Rejected Super Disc packet {}", packet.op, ex); }
        });
    }
    public static void toServer(String op, CompoundTag tag) { toServer(new Packet(op, tag)); }
    public static void toServer(Packet packet) { PacketDistributor.sendToServer(packet); }
    public static void toPlayer(ServerPlayer player, String op, CompoundTag tag) { toPlayer(player, new Packet(op, tag)); }
    public static void toPlayer(ServerPlayer player, Packet packet) { PacketDistributor.sendToPlayer(player, packet); }
}
