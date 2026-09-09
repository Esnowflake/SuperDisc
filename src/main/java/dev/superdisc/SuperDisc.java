package dev.superdisc;

import com.mojang.logging.LogUtils;
import net.minecraft.world.item.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.*;
import org.slf4j.Logger;
import java.util.List;

@Mod(SuperDisc.ID)
public final class SuperDisc {
    public static final String ID = "super_disc";
    public static final Logger LOG = LogUtils.getLogger();
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ID);
    public static final RegistryObject<Item> DISC = ITEMS.register("super_disc", () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)) {
        @Override public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
            lines.add(Component.translatable("item.super_disc.super_disc.desc"));
        }
    });
    public SuperDisc() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(bus);
        bus.addListener(this::creative);
        Net.register();
        MinecraftForge.EVENT_BUS.register(ServerPlayback.class);
        LOG.info("Super Disc 1.0.0 initialized; shared MP3 / Ogg Vorbis, cache {}", Cache.root());
    }
    private void creative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.TOOLS_AND_UTILITIES)) event.accept(DISC.get());
    }
}
