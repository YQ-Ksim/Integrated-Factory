package dev.integratedfactory.block;

import dev.integratedfactory.blockentity.IFSimpleUnitBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import dev.integratedfactory.registry.IFBlockEntities;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import dev.integratedfactory.menu.IFNodeMenu;
import java.util.List;

public class IFSimpleUnitBlock extends KineticBlock implements EntityBlock, IWrenchable {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public IFSimpleUnitBlock(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()); }
    /** The front (toward the placer) is the input; the left/right sides form the shaft axis. */
    @Override public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return face.getAxis() == getRotationAxis(state);
    }
    @Override public Axis getRotationAxis(BlockState state) { return state.getValue(FACING).getClockWise().getAxis(); }
    @Override public BlockState getRotatedBlockState(BlockState state, Direction clickedFace) {
        return state.setValue(FACING, state.getValue(FACING).getClockWise());
    }
    @Override public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }
    @Override public BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new IFSimpleUnitBlockEntity(pos, state); }
    @Override public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return IFBoundBlockDrops.preserveNodeId(super.getDrops(state, params), params, this.asItem());
    }
    @Override public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof IFSimpleUnitBlockEntity unit) {
            if (serverPlayer.isShiftKeyDown() && unit.node() != null) IFNodeMenu.openSimple(serverPlayer, unit.node());
            else unit.enterRoom(serverPlayer);
        }
        return InteractionResult.CONSUME;
    }
    @SuppressWarnings("unchecked")
    @Nullable @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return type == IFBlockEntities.SIMPLE_UNIT.get() ? (BlockEntityTicker<T>) (BlockEntityTicker<IFSimpleUnitBlockEntity>) (world, pos, blockState, be) -> {
            be.tick(); IFSimpleUnitBlockEntity.tickStress(world, pos, blockState, be);
        } : null;
    }
    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && level.getServer() != null && level.getBlockEntity(pos) instanceof IFSimpleUnitBlockEntity unit) {
            var node = unit.node();
            if (node != null && node.hostDimension.equals(level.dimension().location().toString()) && node.hostPos == pos.asLong()) {
                node.valid = false; node.active = false; node.status = "core_removed";
                dev.integratedfactory.data.IFMachineData.get(level.getServer()).changed();
                dev.integratedfactory.space.IFMachineSpace.refreshForcedRooms(level.getServer());
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
