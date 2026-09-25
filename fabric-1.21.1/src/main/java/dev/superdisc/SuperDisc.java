package dev.superdisc;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SuperDisc implements ModInitializer {
    public static final String ID = "super_disc";
    public static final Logger LOG = LoggerFactory.getLogger(ID);
    public static final Item DISC = Registry.register(BuiltInRegistries.ITEM,
        ResourceLocation.fromNamespaceAndPath(ID, "super_disc"),
        new Item(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));
    public static Item disc() { return DISC; }
    @Override public void onInitialize() {
        Net.register();
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> entries.accept(DISC));
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> ServerPlayback.click(player, level, hand, hit.getBlockPos()));
        ServerTickEvents.END_SERVER_TICK.register(ServerPlayback::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(ServerPlayback::stopped);
    }
}
