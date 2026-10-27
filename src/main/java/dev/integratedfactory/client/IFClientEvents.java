package dev.integratedfactory.client;

import dev.integratedfactory.IntegratedFactory;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.OrientedRotatingVisual;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import dev.engine_room.flywheel.lib.model.Models;
import dev.integratedfactory.block.IFKineticCoreBlock;
import dev.integratedfactory.block.IFSimpleUnitBlock;
import dev.integratedfactory.registry.IFBlockEntities;
import dev.integratedfactory.registry.IFBlocks;
import dev.integratedfactory.blockentity.IFStructureBlockEntity;
import net.minecraft.core.Direction;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Uses Create's Creative Motor style renderer for the one-ended stress-port shaft. */
@EventBusSubscriber(modid = IntegratedFactory.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class IFClientEvents {
    @SubscribeEvent
    public static void registerPartialModels(ModelEvent.RegisterAdditional event) {
        event.register(ModelResourceLocation.standalone(IFPartialModels.CORE_SHAFT.modelLocation()));
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(IFBlockEntities.STRESS_PORT.get(), IFStressPortRenderer::new);
        event.registerBlockEntityRenderer(IFBlockEntities.STRUCTURE.get(), IFCoreShaftRenderer::new);
        event.registerBlockEntityRenderer(IFBlockEntities.SIMPLE_UNIT.get(), IFSimpleUnitShaftRenderer::new);
    }

    @SubscribeEvent
    public static void registerFlywheelVisuals(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            SimpleBlockEntityVisualizer.builder(IFBlockEntities.STRESS_PORT.get())
                    .factory(OrientedRotatingVisual.of(AllPartialModels.SHAFT_HALF))
                    .apply();
            SimpleBlockEntityVisualizer.builder(IFBlockEntities.STRUCTURE.get())
                    .factory(IFClientEvents::structureVisual)
                    .skipVanillaRender(be -> be.getBlockState().is(IFBlocks.INTEGRATION_UNIT.get()))
                    .apply();
            SimpleBlockEntityVisualizer.builder(IFBlockEntities.SIMPLE_UNIT.get())
                    .factory(IFClientEvents::simpleUnitVisual)
                    .skipVanillaRender(be -> true)
                    .apply();
        });
    }

    private static BlockEntityVisual<? super IFStructureBlockEntity> structureVisual(
            VisualizationContext context, IFStructureBlockEntity be, float partialTick) {
        if (be.getBlockState().is(IFBlocks.INTEGRATION_UNIT.get())) {
            Direction shaftFace = be.getBlockState().getValue(IFKineticCoreBlock.FACING).getClockWise();
            // CORE_SHAFT is authored along the model's UP/Y axis. Tell Flywheel
            // that canonical direction before rotating it onto the kinetic axis.
            return new OrientedRotatingVisual<>(context, be, partialTick, Direction.UP, shaftFace,
                    Models.partial(IFPartialModels.CORE_SHAFT));
        }
        return new BlockEntityVisual<>() {
            @Override public void update(float partialTick) {}
            @Override public void delete() {}
            @Override public void collectCrumblingInstances(java.util.function.Consumer<dev.engine_room.flywheel.api.instance.Instance> consumer) {}
        };
    }

    private static BlockEntityVisual<? super dev.integratedfactory.blockentity.IFSimpleUnitBlockEntity> simpleUnitVisual(
            VisualizationContext context, dev.integratedfactory.blockentity.IFSimpleUnitBlockEntity be, float partialTick) {
        Direction shaftFace = be.getBlockState().getValue(IFSimpleUnitBlock.FACING).getClockWise();
        return new OrientedRotatingVisual<>(context, be, partialTick, Direction.UP, shaftFace,
                Models.partial(IFPartialModels.CORE_SHAFT));
    }

    private IFClientEvents() {}
}
