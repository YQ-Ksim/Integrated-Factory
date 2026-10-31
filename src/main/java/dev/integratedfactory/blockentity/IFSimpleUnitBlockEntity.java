package dev.integratedfactory.blockentity;

import dev.integratedfactory.data.IFMachineData;
import dev.integratedfactory.registry.IFBlockEntities;
import dev.integratedfactory.space.IFMachineSpace;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.KineticNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public class IFSimpleUnitBlockEntity extends KineticBlockEntity {
    private UUID nodeId;
    private UUID initialOwner;
    private UUID rebindToken;
    private transient boolean playerPlacementAuthorized;
    private KineticNetwork lastStressNetwork;
    private float lastSubmittedStress = Float.NaN;
    public IFSimpleUnitBlockEntity(BlockPos pos, BlockState state) { super(IFBlockEntities.SIMPLE_UNIT.get(), pos, state); }
    public IFMachineData.Node node() {
        if (level == null || level.isClientSide || nodeId == null) return null;
        IFMachineData.Node node = IFMachineData.get(level.getServer()).get(nodeId);
        if (node != null && !IFMachineSpace.isFactoryDimension(level)
                && (!node.hostDimension.equals(level.dimension().location().toString()) || node.hostPos != worldPosition.asLong())) return null;
        return node;
    }
    public UUID nodeId() { return nodeId; }
    @Override public float calculateStressApplied() {
        IFMachineData.Node node = node();
        float rpm = Math.abs(getTheoreticalSpeed());
        return node == null || rpm < 0.0001f ? 0 : node.remoteStress / rpm;
    }
    public static void tickStress(net.minecraft.world.level.Level level, BlockPos pos, BlockState state, IFSimpleUnitBlockEntity be) {
        if (level.isClientSide) return;
        if (be.rejectUncanonicalCopy()) return;
        be.ensureNode();
        be.updateStressIfChanged();
    }
    private void updateStressIfChanged() {
        IFMachineData.Node node = node();
        if (node == null || !hasNetwork()) return;
        KineticNetwork network = getOrCreateNetwork();
        if (network == null) return;
        boolean hadMember = network.members.containsKey(this);
        if (!hadMember && node.recoveryStress > 0) {
            dev.integratedfactory.kinetics.IFKineticNetworkSnapshot.subtractUnloadedStress(network, node.recoveryStress);
            node.recoveryStress = 0;
            IFMachineData.get(level.getServer()).changed();
        }
        float applied = calculateStressApplied();
        if (network != lastStressNetwork || !network.members.containsKey(this)
                || !Float.isFinite(lastSubmittedStress) || Math.abs(applied - lastSubmittedStress) > 0.0001f) {
            network.updateStressFor(this, applied);
            // Reconcile the restored network's capacity and stress together when
            // this block entity reattaches after a world reload.
            if (network != lastStressNetwork || !network.members.containsKey(this)) {
                network.updateNetwork();
                network.sync();
            }
            lastStressNetwork = network;
            lastSubmittedStress = applied;
        }
    }
    public void enterRoom(net.minecraft.server.level.ServerPlayer player) {
        IFMachineData.Node node = node();
        if (node != null && node.valid) IFMachineSpace.enter(player, node);
    }
    @Override public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide && nodeId != null
                && IFMachineData.get(level.getServer()).wasDeleted(nodeId)) {
            nodeId = null;
            rebindToken = null;
            setChanged();
        }
        if (level != null && !level.isClientSide && !IFMachineSpace.isFactoryDimension(level) && nodeId != null) {
            IFMachineData.Node hostNode = node();
            if (hostNode != null && hostNode.beginRuntimeRecovery())
                IFMachineData.get(level.getServer()).changed();
        }
    }
    private void ensureNode() {
        if (level == null || level.isClientSide) return;
        // Only an actual player placement (or explicit empty-hand migration)
        // may activate an unbound simple unit.  This blocks Create schematic
        // printing, which places blocks without EntityPlaceEvent.
        if (nodeId == null && !playerPlacementAuthorized) return;
        IFMachineData data = IFMachineData.get(level.getServer());
        if (data.wasDeleted(nodeId)) return;
        IFMachineData.Node node = data.get(nodeId);
        boolean changed = false;
        boolean authorizedRebind = false;
        if (node != null && node.pendingCoreToken != null) {
            if (rebindToken == null || !rebindToken.equals(node.pendingCoreToken)) return;
            authorizedRebind = true;
            node.pendingCoreToken = null;
            node.valid = true;
            node.active = true;
            node.status = "rebuilding";
            changed = true;
        }
        if (node != null && "awaiting_rebind".equals(node.status) && !authorizedRebind) return;
        if (node != null && !authorizedRebind && !"core_removed".equals(node.status)
                && (!node.hostDimension.equals(level.dimension().location().toString()) || node.hostPos != worldPosition.asLong())) return;
        if (node == null) {
            node = data.create(initialOwner);
            nodeId = node.id;
            initialOwner = null;
            IFMachineSpace.allocate(node, level.getServer());
            changed = true;
        }
        String hostDimension = level.dimension().location().toString();
        if (!hostDimension.equals(node.hostDimension) || node.hostPos != worldPosition.asLong()) {
            node.hostDimension = hostDimension;
            node.hostPos = worldPosition.asLong();
            changed = true;
        }
        if ("core_removed".equals(node.status)) {
            node.valid = true;
            node.active = true;
            node.status = "ready";
            changed = true;
        }
        if (changed) {
            data.changed();
            setChanged();
        }
    }

    private boolean rejectUncanonicalCopy() {
        if (level == null || level.isClientSide) return false;
        IFMachineData data = IFMachineData.get(level.getServer());
        if (nodeId != null && data.wasDeleted(nodeId)) {
            if (playerPlacementAuthorized) {
                nodeId = null;
                rebindToken = null;
                setChanged();
                return false;
            }
            ItemStack drop = new ItemStack(getBlockState().getBlock());
            level.setBlock(worldPosition, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            Block.popResource(level, worldPosition, drop);
            return true;
        }
        IFMachineData.Node saved = data.get(nodeId);
        if (nodeId == null && !playerPlacementAuthorized) {
            ItemStack drop = dev.integratedfactory.block.IFBoundBlockDrops.createCoreDrop(getBlockState(), this);
            level.setBlock(worldPosition, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            Block.popResource(level, worldPosition, drop);
            return true;
        }
        if (nodeId != null && saved == null) {
            ItemStack drop = dev.integratedfactory.block.IFBoundBlockDrops.createCoreDrop(getBlockState(), this);
            level.setBlock(worldPosition, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            Block.popResource(level, worldPosition, drop);
            return true;
        }
        if (nodeId != null && saved != null
                && saved.hostDimension.equals(level.dimension().location().toString())
                && saved.hostPos == worldPosition.asLong()
                && "core_removed".equals(saved.status)
                && !playerPlacementAuthorized) {
            ItemStack drop = dev.integratedfactory.block.IFBoundBlockDrops.createCoreDrop(getBlockState(), this);
            level.setBlock(worldPosition, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            Block.popResource(level, worldPosition, drop);
            return true;
        }
        if (nodeId == null || playerPlacementAuthorized) {
            // A player may place a bound item only through the explicit
            // rebind flow.  A normal bound item with no pending token must
            // not create a second host for an existing machine world.
            if (nodeId != null && saved != null
                    && (!saved.hostDimension.equals(level.dimension().location().toString())
                    || saved.hostPos != worldPosition.asLong())) {
                if (saved.pendingCoreToken == null && !"core_removed".equals(saved.status)
                        || saved.pendingCoreToken != null
                        && IFStructureBlockEntity.hasBoundHostCore(level, saved, worldPosition)) {
                    ItemStack drop = dev.integratedfactory.block.IFBoundBlockDrops.createCoreDrop(getBlockState(), this);
                    level.setBlock(worldPosition, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    Block.popResource(level, worldPosition, drop);
                    return true;
                }
            }
            return false;
        }
        boolean canonical = saved != null
                && saved.hostDimension.equals(level.dimension().location().toString())
                && saved.hostPos == worldPosition.asLong();
        if (saved == null || canonical) return false;
        ItemStack drop = dev.integratedfactory.block.IFBoundBlockDrops.createCoreDrop(getBlockState(), this);
        level.setBlock(worldPosition, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Block.popResource(level, worldPosition, drop);
        return true;
    }
    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (nodeId != null) tag.putUUID("node_id", nodeId);
        if (initialOwner != null) tag.putUUID("initial_owner", initialOwner);
        if (rebindToken != null) tag.putUUID("rebind_token", rebindToken);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) { super.loadAdditional(tag, registries); if (tag.hasUUID("node_id")) nodeId = tag.getUUID("node_id"); if (tag.hasUUID("initial_owner")) initialOwner = tag.getUUID("initial_owner"); if (tag.hasUUID("rebind_token")) rebindToken = tag.getUUID("rebind_token"); }
    public void setInitialOwner(UUID owner) { if (nodeId == null && initialOwner == null) { initialOwner = owner; setChanged(); } }
    public void markPlayerPlacement() { playerPlacementAuthorized = true; setChanged(); }
    public void clearNodeBinding() { nodeId = null; rebindToken = null; initialOwner = null; playerPlacementAuthorized = false; setChanged(); }
    public void setRebindIdentity(UUID nodeId, UUID token) { this.nodeId = nodeId; this.rebindToken = token; setChanged(); }
    public void saveRebindToken(CompoundTag tag) { if (rebindToken != null) tag.putUUID("rebind_token", rebindToken); }
}
