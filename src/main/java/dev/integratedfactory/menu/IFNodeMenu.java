package dev.integratedfactory.menu;

import dev.integratedfactory.data.IFMachineData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Native chest screen backed directly by the node's authoritative shared inventories. */
public final class IFNodeMenu {
    public static boolean canAccess(IFMachineData.Node node, ServerPlayer player) {
        if (node.owner == null) { node.owner = player.getUUID(); return true; }
        return node.owner.equals(player.getUUID()) || node.collaborators.contains(player.getUUID());
    }

    public static boolean isOwner(IFMachineData.Node node, ServerPlayer player) {
        return node.owner != null && node.owner.equals(player.getUUID());
    }

    public static void open(ServerPlayer player, IFMachineData.Node node) {
        openCombined(player, node, Component.literal("输入端口库存（上） · 输出端口库存（下）"));
    }

    public static void openSimple(ServerPlayer player, IFMachineData.Node node) {
        openCombined(player, node, Component.literal("上方：输入端口库存　下方：输出端口库存"));
    }

    public static void openPort(ServerPlayer player, IFMachineData.Node node, boolean input) {
        if (!claimOrCheck(player, node)) return;
        ItemStackHandler handler = input ? node.input : node.output;
        Component title = Component.literal(input ? "输入端口库存" : "输出端口库存");
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> ChestMenu.threeRows(id, inventory, new NodeContainer(handler)), title));
    }

    public static void showStructureStats(ServerPlayer player, IFMachineData.Node node) {
        if (!claimOrCheck(player, node)) return;
        int inputs = 0, outputs = 0, extensions = 0, cores = 0;
        var level = player.getServer().getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(node.hostDimension)));
        if (level != null) for (long member : node.structureMembers) {
            var pos = net.minecraft.core.BlockPos.of(member);
            if (!level.hasChunkAt(pos)) continue;
            var state = level.getBlockState(pos);
            if (state.is(dev.integratedfactory.registry.IFBlocks.INPUT_PORT.get())) inputs++;
            else if (state.is(dev.integratedfactory.registry.IFBlocks.OUTPUT_PORT.get())) outputs++;
            else if (state.is(dev.integratedfactory.registry.IFBlocks.EXTENSION_INTERFACE.get())) extensions++;
            else if (state.is(dev.integratedfactory.registry.IFBlocks.INTEGRATION_UNIT.get())) cores++;
        }
        player.displayClientMessage(Component.literal("集成结构统计 | 输入端口 " + inputs + " | 输出端口 " + outputs
                + " | 拓展接口 " + extensions + " | 集成单元 " + cores + " | 状态 " + node.status), false);
    }

    private static void openCombined(ServerPlayer player, IFMachineData.Node node, Component title) {
        if (!claimOrCheck(player, node)) return;
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> ChestMenu.sixRows(id, inventory, new NodeContainer(node.input, node.output)), title));
    }

    private static boolean claimOrCheck(ServerPlayer player, IFMachineData.Node node) {
        boolean unclaimed = node.owner == null;
        if (!canAccess(node, player)) {
            player.displayClientMessage(Component.literal("你没有此工厂节点的访问权限。"), true);
            return false;
        }
        if (unclaimed) dev.integratedfactory.data.IFMachineData.get(player.getServer()).changed();
        return true;
    }

    private static final class NodeContainer implements Container {
        private final ItemStackHandler input;
        private final ItemStackHandler output;
        NodeContainer(ItemStackHandler handler) { this.input = handler; this.output = null; }
        NodeContainer(ItemStackHandler input, ItemStackHandler output) { this.input = input; this.output = output; }
        private ItemStackHandler handler(int slot) { return output == null || slot < 27 ? input : output; }
        private int index(int slot) { return output == null ? slot : slot % 27; }
        @Override public int getContainerSize() { return output == null ? 27 : 54; }
        @Override public boolean isEmpty() {
            for (int i = 0; i < getContainerSize(); i++) if (!getItem(i).isEmpty()) return false;
            return true;
        }
        @Override public ItemStack getItem(int slot) { return slot < 0 || slot >= getContainerSize() ? ItemStack.EMPTY : handler(slot).getStackInSlot(index(slot)); }
        @Override public ItemStack removeItem(int slot, int amount) { return slot < 0 || slot >= getContainerSize() ? ItemStack.EMPTY : handler(slot).extractItem(index(slot), amount, false); }
        @Override public ItemStack removeItemNoUpdate(int slot) { return slot < 0 || slot >= getContainerSize() ? ItemStack.EMPTY : handler(slot).extractItem(index(slot), Integer.MAX_VALUE, false); }
        @Override public void setItem(int slot, ItemStack stack) {
            if (slot < 0 || slot >= getContainerSize()) return;
            ItemStackHandler handler = handler(slot); int index = index(slot);
            handler.setStackInSlot(index, stack.copy());
        }
        @Override public void setChanged() {}
        @Override public boolean stillValid(Player player) { return player.isAlive(); }
        @Override public boolean canPlaceItem(int slot, ItemStack stack) { return slot >= 0 && slot < getContainerSize(); }
        @Override public void clearContent() { for (int i = 0; i < getContainerSize(); i++) setItem(i, ItemStack.EMPTY); }
    }
    private IFNodeMenu() {}
}
