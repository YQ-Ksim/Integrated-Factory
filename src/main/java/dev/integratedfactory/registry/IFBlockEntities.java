package dev.integratedfactory.registry;

import dev.integratedfactory.IntegratedFactory;
import dev.integratedfactory.block.IFSimpleUnitBlock;
import dev.integratedfactory.block.IFEntityBlock;
import dev.integratedfactory.blockentity.IFSimpleUnitBlockEntity;
import dev.integratedfactory.blockentity.IFStructureBlockEntity;
import dev.integratedfactory.blockentity.IFStressPortBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class IFBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, IntegratedFactory.MOD_ID);
    public static final Supplier<BlockEntityType<IFStructureBlockEntity>> STRUCTURE = TYPES.register("structure", () -> BlockEntityType.Builder.of(IFStructureBlockEntity::new,
            dev.integratedfactory.registry.IFBlocks.INTEGRATION_UNIT.get(), dev.integratedfactory.registry.IFBlocks.EXTENSION_INTERFACE.get(),
            dev.integratedfactory.registry.IFBlocks.INPUT_PORT.get(), dev.integratedfactory.registry.IFBlocks.OUTPUT_PORT.get()).build(null));
    public static final Supplier<BlockEntityType<IFSimpleUnitBlockEntity>> SIMPLE_UNIT = TYPES.register("simple_unit", () -> BlockEntityType.Builder.of(IFSimpleUnitBlockEntity::new,
            dev.integratedfactory.registry.IFBlocks.SIMPLE_INTEGRATION_UNIT.get()).build(null));
    public static final Supplier<BlockEntityType<IFStressPortBlockEntity>> STRESS_PORT = TYPES.register("stress_port", () -> BlockEntityType.Builder.of(IFStressPortBlockEntity::new,
            dev.integratedfactory.registry.IFBlocks.STRESS_PORT.get()).build(null));
    private IFBlockEntities() {}
}
