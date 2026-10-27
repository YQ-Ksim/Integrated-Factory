package dev.integratedfactory.registry;

import dev.integratedfactory.IntegratedFactory;
import dev.integratedfactory.block.IFEntityBlock;
import dev.integratedfactory.block.IFSimpleUnitBlock;
import dev.integratedfactory.block.IFKineticCoreBlock;
import dev.integratedfactory.block.IFStressPortBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

import java.util.function.Supplier;

public final class IFBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(IntegratedFactory.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(IntegratedFactory.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, IntegratedFactory.MOD_ID);

    public static final DeferredBlock<Block> INTEGRATION_UNIT = block("integration_unit", () -> new IFKineticCoreBlock(props()));
    public static final DeferredBlock<Block> EXTENSION_INTERFACE = block("extension_interface", () -> new IFEntityBlock(props().strength(2.0f)));
    public static final DeferredBlock<Block> INPUT_PORT = block("input_port", () -> new IFEntityBlock(props().strength(2.0f)));
    public static final DeferredBlock<Block> OUTPUT_PORT = block("output_port", () -> new IFEntityBlock(props().strength(2.0f)));
    public static final DeferredBlock<Block> SIMPLE_INTEGRATION_UNIT = block("simple_integration_unit", () -> new IFSimpleUnitBlock(props()));
    public static final DeferredBlock<Block> STRESS_PORT = block("stress_port", () -> new IFStressPortBlock(props().strength(2.0f).noOcclusion().dynamicShape()));
    public static final net.neoforged.neoforge.registries.DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.integrated_factory"))
            .icon(() -> new ItemStack(INTEGRATION_UNIT.get()))
            .displayItems((parameters, output) -> {
                output.accept(INTEGRATION_UNIT.get()); output.accept(SIMPLE_INTEGRATION_UNIT.get());
                output.accept(EXTENSION_INTERFACE.get()); output.accept(INPUT_PORT.get());
                output.accept(OUTPUT_PORT.get()); output.accept(STRESS_PORT.get());
            }).build());

    private static BlockBehaviour.Properties props() {
        return BlockBehaviour.Properties.of().strength(3.0f).sound(SoundType.METAL).noOcclusion();
    }

    private static DeferredBlock<Block> block(String name, Supplier<Block> block) {
        DeferredBlock<Block> registered = BLOCKS.register(name, block);
        ITEMS.register(name, () -> new BlockItem(registered.get(), new Item.Properties()));
        return registered;
    }

    private IFBlocks() {}
}
