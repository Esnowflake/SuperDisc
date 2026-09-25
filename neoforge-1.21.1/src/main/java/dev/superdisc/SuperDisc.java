package dev.superdisc;

import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.world.item.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(SuperDisc.ID)
public final class SuperDisc {
    public static final String ID = "super_disc";
    public static final Logger LOG = LoggerFactory.getLogger(ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    public static final java.util.function.Supplier<Item> DISC = ITEMS.register("super_disc",
        () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));
    public static Item disc() { return DISC.get(); }
    public SuperDisc(IEventBus bus) {
        ITEMS.register(bus);
        bus.addListener(Net::register);
        bus.addListener(SuperDisc::creative);
        NeoForge.EVENT_BUS.register(ServerEvents.class);
    }
    private static void creative(BuildCreativeModeTabContentsEvent event) {
        if(event.getTabKey().equals(CreativeModeTabs.TOOLS_AND_UTILITIES)) event.accept(disc());
    }
}
