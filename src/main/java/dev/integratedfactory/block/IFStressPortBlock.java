package dev.integratedfactory.block;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import dev.integratedfactory.blockentity.IFStressPortBlockEntity;
import dev.integratedfactory.registry.IFBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import org.jetbrains.annotations.Nullable;

/** A fixed-speed Create source that reports the remote network's actual Create stress total. */
public final class IFStressPortBlock extends DirectionalKineticBlock implements EntityBlock, IWrenchable {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public IFStressPortBlock(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) { builder.add(FACING); }
    /** Use Create's directional kinetic placement: connect to a neighboring shaft when possible. */
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return super.getStateForPlacement(context); }
    /** Create's Creative Motor is a one-sided generator; only its output face connects. */
    @Override public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) { return face == state.getValue(FACING); }
    @Override public net.minecraft.core.Direction.Axis getRotationAxis(BlockState state) { return state.getValue(FACING).getAxis(); }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new IFStressPortBlockEntity(pos, state); }
    @SuppressWarnings("unchecked")
    @Nullable @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return type == IFBlockEntities.STRESS_PORT.get() ? (BlockEntityTicker<T>) (BlockEntityTicker<IFStressPortBlockEntity>) (world, pos, blockState, be) -> {
            be.tick(); be.tickBridge();
        } : null;
    }
}
