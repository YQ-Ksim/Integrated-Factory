package dev.integratedfactory.client;

import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import dev.integratedfactory.blockentity.IFStructureBlockEntity;
import dev.integratedfactory.registry.IFBlocks;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import dev.integratedfactory.block.IFKineticCoreBlock;
import com.mojang.blaze3d.vertex.PoseStack;

/** Renders only the integration core's transverse shaft, leaving structure ports untouched. */
public final class IFCoreShaftRenderer extends KineticBlockEntityRenderer<IFStructureBlockEntity> {
    public IFCoreShaftRenderer(BlockEntityRendererProvider.Context context) { super(context); }

    @Override
    protected void renderSafe(IFStructureBlockEntity be, float partialTick, PoseStack poseStack,
                              MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (!be.getBlockState().is(IFBlocks.INTEGRATION_UNIT.get())) return;
        super.renderSafe(be, partialTick, poseStack, buffer, packedLight, packedOverlay);
    }

    @Override
    protected SuperByteBuffer getRotatedModel(IFStructureBlockEntity be, BlockState state) {
        Direction shaftFace = state.getValue(IFKineticCoreBlock.FACING).getClockWise();
        return CachedBuffers.partialFacing(IFPartialModels.CORE_SHAFT, state, shaftFace);
    }
}
