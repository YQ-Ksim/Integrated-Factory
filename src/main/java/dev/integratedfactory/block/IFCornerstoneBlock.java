package dev.integratedfactory.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bedrock-property foundation used by generated machine rooms.
 *
 * The vanilla wither and dangerous wither skull destruction paths consult
 * BlockState.canEntityDestroy rather than only explosion resistance.  A
 * custom block does not inherit bedrock's vanilla tag membership in every
 * datapack-loading scenario, so enforce the same protection at the block API
 * boundary as well.  Returning false also keeps the foundation safe from
 * ender-dragon block destruction.
 */
public final class IFCornerstoneBlock extends Block {
    public IFCornerstoneBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public boolean canEntityDestroy(BlockState state, BlockGetter level, BlockPos pos, Entity entity) {
        return false;
    }
}
