package dev.integratedfactory.client;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import dev.integratedfactory.blockentity.IFStressPortBlockEntity;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

/** Matches Create's Creative Motor renderer: only the one-sided shaft is animated. */
public final class IFStressPortRenderer extends KineticBlockEntityRenderer<IFStressPortBlockEntity> {
    public IFStressPortRenderer(BlockEntityRendererProvider.Context context) { super(context); }

    @Override
    protected SuperByteBuffer getRotatedModel(IFStressPortBlockEntity be, BlockState state) {
        return CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state);
    }
}
