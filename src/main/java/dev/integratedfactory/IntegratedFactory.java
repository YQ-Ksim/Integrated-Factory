package dev.integratedfactory;

import dev.integratedfactory.registry.IFBlocks;
import dev.integratedfactory.registry.IFBlockEntities;
import dev.integratedfactory.inventory.IFInventoryCapabilities;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import dev.integratedfactory.config.IFServerConfig;
import dev.integratedfactory.space.IFMachineSpace;
import net.neoforged.neoforge.common.NeoForge;

@Mod(IntegratedFactory.MOD_ID)
public final class IntegratedFactory {
    public static final String MOD_ID = "integrated_factory";

    public IntegratedFactory(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, IFServerConfig.spec(), "integrated_factory-server.toml");
        modBus.addListener(IFMachineSpace::registerTicketController);
        NeoForge.EVENT_BUS.addListener(IFMachineSpace::onServerStarted);
        NeoForge.EVENT_BUS.addListener(IFMachineSpace::onServerTick);
        IFBlocks.BLOCKS.register(modBus);
        IFBlocks.ITEMS.register(modBus);
        IFBlocks.TABS.register(modBus);
        IFBlockEntities.TYPES.register(modBus);
        modBus.addListener(IFInventoryCapabilities::register);
    }
}
