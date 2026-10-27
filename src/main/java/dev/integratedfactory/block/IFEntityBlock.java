package dev.integratedfactory.block;

import dev.integratedfactory.blockentity.IFStructureBlockEntity;
import dev.integratedfactory.registry.IFBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import dev.integratedfactory.menu.IFNodeMenu;
import com.simibubi.create.content.equipment.wrench.IWrenchable;

public class IFEntityBlock extends BaseEntityBlock implements IWrenchable {
    public IFEntityBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return simpleCodec(IFEntityBlock::new); }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new IFStructureBlockEntity(pos, state); }
    @SuppressWarnings("unchecked")
    @Nullable @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != IFBlockEntities.STRUCTURE.get()) return null;
        return (world, pos, blockState, be) -> IFStructureBlockEntity.tick(world, pos, blockState, (IFStructureBlockEntity) be);
    }

    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && state.is(dev.integratedfactory.registry.IFBlocks.INTEGRATION_UNIT.get()) && !level.isClientSide && level.getServer() != null) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof IFStructureBlockEntity core && core.nodeId() != null) {
                var data = dev.integratedfactory.data.IFMachineData.get(level.getServer());
                var node = data.get(core.nodeId());
                if (node != null) { node.valid = false; node.active = false; node.status = "core_removed"; data.changed(); }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (state.is(dev.integratedfactory.registry.IFBlocks.INTEGRATION_UNIT.get())) {
            IFStructureBlockEntity be = (IFStructureBlockEntity) level.getBlockEntity(pos);
            if (be != null && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                if (serverPlayer.isShiftKeyDown() && be.node() != null) IFNodeMenu.showStructureStats(serverPlayer, be.node());
                else if (!serverPlayer.isShiftKeyDown()) be.enterRoom(serverPlayer);
            }
            return InteractionResult.CONSUME;
        }
        if ((state.is(dev.integratedfactory.registry.IFBlocks.INPUT_PORT.get()) || state.is(dev.integratedfactory.registry.IFBlocks.OUTPUT_PORT.get()))
                && level.getBlockEntity(pos) instanceof IFStructureBlockEntity part && part.node() != null && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            if (serverPlayer.isShiftKeyDown()) {
                IFNodeMenu.openPort(serverPlayer, part.node(), state.is(dev.integratedfactory.registry.IFBlocks.INPUT_PORT.get()));
                return InteractionResult.CONSUME;
            }
        }
        return InteractionResult.PASS;
    }
}
