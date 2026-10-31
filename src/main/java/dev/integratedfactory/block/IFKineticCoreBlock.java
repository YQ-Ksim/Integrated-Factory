package dev.integratedfactory.block;

import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.integratedfactory.blockentity.IFStructureBlockEntity;
import dev.integratedfactory.registry.IFBlockEntities;
import dev.integratedfactory.space.IFMachineSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Mirror;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import dev.integratedfactory.menu.IFNodeMenu;

/** Create kinetic node core. Only the two faces along its shaft axis participate in rotation propagation. */
public final class IFKineticCoreBlock extends KineticBlock implements EntityBlock, IWrenchable {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public IFKineticCoreBlock(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        if (IFMachineSpace.isFactoryDimension(context.getLevel())) return null;
        // getClickedPos is the block that was clicked, not always the final
        // placement position. Keep this check in sync with the pre-placement
        // event so orphan ports/extensions do not cause false rejections.
        BlockPos target = context.getClickedPos();
        if (!context.getLevel().getBlockState(target).canBeReplaced())
            target = target.relative(context.getClickedFace());
        if (IFStructureBlockEntity.hasAdjacentStructure(context.getLevel(), target)) return null;
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
    @Override public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return super.canSurvive(state, level, pos);
    }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof ServerPlayer player
                && level.getBlockEntity(pos) instanceof IFStructureBlockEntity core) {
            core.setInitialOwner(player.getUUID());
            core.markPlayerPlacement();
        }
    }
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
    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new IFStructureBlockEntity(pos, state); }
    @Override public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return IFBoundBlockDrops.preserveNodeId(super.getDrops(state, params), params, this.asItem());
    }
    @Override public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof IFStructureBlockEntity core) {
            if (serverPlayer.isShiftKeyDown() && core.node() != null) {
                IFNodeMenu.showStructureStats(serverPlayer, core.node());
                dev.integratedfactory.event.IFEvents.showNodeWorldRow(serverPlayer, core.node());
            }
            else core.enterRoom(serverPlayer);
        }
        return InteractionResult.CONSUME;
    }
    @SuppressWarnings("unchecked")
    @Nullable @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != IFBlockEntities.STRUCTURE.get()) return null;
        return (BlockEntityTicker<T>) (BlockEntityTicker<IFStructureBlockEntity>) (world, pos, blockState, be) -> {
            be.tick();
            IFStructureBlockEntity.tick(world, pos, blockState, be);
        };
    }

    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && level.getServer() != null && level.getBlockEntity(pos) instanceof IFStructureBlockEntity core && core.nodeId() != null) {
            var data = dev.integratedfactory.data.IFMachineData.get(level.getServer());
            var node = data.get(core.nodeId());
            if (node != null && node.hostDimension.equals(level.dimension().location().toString()) && node.hostPos == pos.asLong()) {
                node.valid = false; node.active = false; node.status = "core_removed"; data.changed();
                dev.integratedfactory.space.IFMachineSpace.refreshForcedRooms(level.getServer());
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
