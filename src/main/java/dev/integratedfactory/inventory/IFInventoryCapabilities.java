package dev.integratedfactory.inventory;

import dev.integratedfactory.blockentity.IFSimpleUnitBlockEntity;
import dev.integratedfactory.blockentity.IFStructureBlockEntity;
import dev.integratedfactory.data.IFMachineData;
import dev.integratedfactory.registry.IFBlocks;
import dev.integratedfactory.space.IFMachineSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

public final class IFInventoryCapabilities {
    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlock(Capabilities.ItemHandler.BLOCK, IFInventoryCapabilities::portHandler,
                IFBlocks.INPUT_PORT.get(), IFBlocks.OUTPUT_PORT.get());
        event.registerBlock(Capabilities.ItemHandler.BLOCK, IFInventoryCapabilities::simpleHandler, IFBlocks.SIMPLE_INTEGRATION_UNIT.get());
    }

    private static IItemHandler simpleHandler(Level level, BlockPos pos, BlockState state, net.minecraft.world.level.block.entity.BlockEntity be, Direction side) {
        if (!(be instanceof IFSimpleUnitBlockEntity simple) || side == null || level.isClientSide) return null;
        IFMachineData.Node node = simple.node();
        if (node == null || !node.valid || !node.active) return null;
        Direction inputFace = state.getValue(dev.integratedfactory.block.IFSimpleUnitBlock.FACING);
        if (side == inputFace) return new RestrictedHandler(node.input, true, false);
        if (side == inputFace.getOpposite()) return new RestrictedHandler(node.output, false, true);
        return null;
    }

    private static IItemHandler portHandler(Level level, BlockPos pos, BlockState state, net.minecraft.world.level.block.entity.BlockEntity be, Direction side) {
        if (!(be instanceof IFStructureBlockEntity part) || part.nodeId() == null || level.isClientSide) return null;
        IFMachineData.Node node = IFMachineData.get(level.getServer()).get(part.nodeId());
        if (node == null || !node.valid || !node.active) return null;
        boolean input = state.is(IFBlocks.INPUT_PORT.get());
        boolean room = IFMachineSpace.isFactoryDimension(level);
        boolean host = level.dimension().location().toString().equals(node.hostDimension);
        if (!room && !host) return null;
        if (input) return new RestrictedHandler(node.input, host, !host);
        return new RestrictedHandler(node.output, !host, host);
    }

    private record RestrictedHandler(IItemHandler backing, boolean canInsert, boolean canExtract) implements IItemHandlerModifiable {
        @Override public int getSlots() { return backing.getSlots(); }
        @Override public net.minecraft.world.item.ItemStack getStackInSlot(int slot) { return backing.getStackInSlot(slot); }
        @Override public net.minecraft.world.item.ItemStack insertItem(int slot, net.minecraft.world.item.ItemStack stack, boolean simulate) {
            return canInsert ? backing.insertItem(slot, stack, simulate) : stack;
        }
        @Override public net.minecraft.world.item.ItemStack extractItem(int slot, int amount, boolean simulate) {
            return canExtract ? backing.extractItem(slot, amount, simulate) : net.minecraft.world.item.ItemStack.EMPTY;
        }
        @Override public int getSlotLimit(int slot) { return backing.getSlotLimit(slot); }
        @Override public boolean isItemValid(int slot, net.minecraft.world.item.ItemStack stack) { return canInsert && backing.isItemValid(slot, stack); }
        @Override public void setStackInSlot(int slot, net.minecraft.world.item.ItemStack stack) {
            if (canInsert && backing instanceof IItemHandlerModifiable modifiable)
                modifiable.setStackInSlot(slot, stack);
        }
    }
    private IFInventoryCapabilities() {}
}
