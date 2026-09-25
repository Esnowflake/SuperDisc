package dev.superdisc;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;

public final class Nbt {
    private Nbt() {}
    public static boolean hasUuid(CompoundTag tag, String key) { return tag.read(key, UUIDUtil.CODEC).isPresent(); }
    public static UUID uuid(CompoundTag tag, String key) { return tag.read(key, UUIDUtil.CODEC).orElseThrow(); }
    public static void putUuid(CompoundTag tag, String key, UUID value) { tag.store(key, UUIDUtil.CODEC, value); }
}
