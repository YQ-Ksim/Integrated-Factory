package dev.integratedfactory.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import dev.integratedfactory.block.IFSimpleUnitBlock;
import dev.integratedfactory.blockentity.IFSimpleUnitBlockEntity;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

/** Fallback renderer for the simple unit's transverse Create shaft. */
public final class IFSimpleUnitShaftRenderer extends KineticBlockEntityRenderer<IFSimpleUnitBlockEntity> {
    public IFSimpleUnitShaftRenderer(BlockEntityRendererProvider.Context context) { super(context); }

    @Override
    protected void renderSafe(IFSimpleUnitBlockEntity be, float partialTick, PoseStack poseStack,
                              MultiBufferSource buffer, int packedLight, int packedOverlay) {
        super.renderSafe(be, partialTick, poseStack, buffer, packedLight, packedOverlay);
    }

    @Override
    protected SuperByteBuffer getRotatedModel(IFSimpleUnitBlockEntity be, BlockState state) {
        Direction shaftFace = state.getValue(IFSimpleUnitBlock.FACING).getClockWise();
        return CachedBuffers.partialFacing(IFPartialModels.CORE_SHAFT, state, shaftFace);
    }
}
