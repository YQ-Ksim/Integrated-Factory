package dev.integratedfactory.block;

import dev.integratedfactory.blockentity.IFSimpleUnitBlockEntity;
import dev.integratedfactory.blockentity.IFStructureBlockEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.List;
import java.util.UUID;

/** Copies a core's node identity onto its dropped block item for safe relocation and restoration. */
public final class IFBoundBlockDrops {
    public static List<ItemStack> preserveNodeId(List<ItemStack> drops, LootParams.Builder params, Item blockItem) {
        BlockEntity blockEntity = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        UUID nodeId = null;
        if (blockEntity instanceof IFStructureBlockEntity core) nodeId = core.nodeId();
        else if (blockEntity instanceof IFSimpleUnitBlockEntity simple) nodeId = simple.nodeId();
        if (nodeId == null) return drops;
        if (blockEntity.getLevel() != null && !blockEntity.getLevel().isClientSide
                && dev.integratedfactory.data.IFMachineData.get(blockEntity.getLevel().getServer()).wasDeleted(nodeId)) return drops;

        CompoundTag data = new CompoundTag();
        data.putString("id", blockEntity instanceof IFSimpleUnitBlockEntity
                ? "integrated_factory:simple_unit" : "integrated_factory:structure");
        data.putUUID("node_id", nodeId);
        data.putBoolean("integrated_factory_bound", true);
        if (blockEntity instanceof IFStructureBlockEntity core) core.saveRebindToken(data);
        else if (blockEntity instanceof IFSimpleUnitBlockEntity simple) simple.saveRebindToken(data);
        CustomData component = CustomData.of(data);
        for (ItemStack stack : drops) {
            if (stack.is(blockItem)) {
                stack.set(DataComponents.BLOCK_ENTITY_DATA, component);
                stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            }
        }
        return drops;
    }

    private IFBoundBlockDrops() {}
}
