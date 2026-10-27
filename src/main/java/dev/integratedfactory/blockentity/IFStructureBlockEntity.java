package dev.integratedfactory.blockentity;

import dev.integratedfactory.data.IFMachineData;
import dev.integratedfactory.registry.IFBlockEntities;
import dev.integratedfactory.registry.IFBlocks;
import dev.integratedfactory.space.IFMachineSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.KineticNetwork;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.Direction;
import dev.integratedfactory.config.IFServerConfig;

public class IFStructureBlockEntity extends KineticBlockEntity {
    private UUID nodeId;
    private UUID initialOwner;
    private UUID rebindToken;
    private int scanDelay;
    private KineticNetwork lastStressNetwork;
    private float lastSubmittedStress = Float.NaN;
    public IFStructureBlockEntity(BlockPos pos, BlockState state) { super(IFBlockEntities.STRUCTURE.get(), pos, state); }
    public @Nullable UUID nodeId() { return nodeId; }
    public @Nullable IFMachineData.Node node() {
        if (level == null || level.isClientSide || nodeId == null) return null;
        IFMachineData.Node node = IFMachineData.get(level.getServer()).get(nodeId);
        if (node != null && !IFMachineSpace.isFactoryDimension(level)
                && level.getBlockState(worldPosition).is(IFBlocks.INTEGRATION_UNIT.get())
                && (!node.hostDimension.equals(level.dimension().location().toString()) || node.hostPos != worldPosition.asLong())) return null;
        return node;
    }

    private void ensureHostNode() {
        if (level == null || level.isClientSide || IFMachineSpace.isFactoryDimension(level)
                || !level.getBlockState(worldPosition).is(IFBlocks.INTEGRATION_UNIT.get())) return;
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
        if (node.structureMembers.add(worldPosition.asLong())) changed = true;
        if ("core_removed".equals(node.status)) {
            node.valid = true;
            node.active = true;
            node.status = "rebuilding";
            changed = true;
        }
        if (changed) {
            data.changed();
            setChanged();
        }
    }

    /** Convert the saved remote SU total into the local base-impact value Create multiplies by local RPM. */
    @Override public float calculateStressApplied() {
        IFMachineData.Node node = node();
        float rpm = Math.abs(getTheoreticalSpeed());
        if (node == null || rpm < 0.0001f) return 0;
        return node.remoteStress / rpm;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, IFStructureBlockEntity be) {
        if (level.isClientSide || !state.is(IFBlocks.INTEGRATION_UNIT.get())) return;
        be.ensureHostNode();
        be.updateStressIfChanged();
        if (++be.scanDelay < 40) return;
        be.scanDelay = 0;
        be.scanComponent();
    }

    @Override public void onLoad() {
        super.onLoad();
        if (level == null || level.isClientSide) return;
        if (nodeId != null && !IFMachineSpace.isFactoryDimension(level)) {
            IFMachineData.Node hostNode = node();
            if (hostNode != null && hostNode.beginRuntimeRecovery())
                IFMachineData.get(level.getServer()).changed();
            return;
        }
        if (nodeId != null) return;
        if (IFMachineSpace.isFactoryDimension(level)) {
            IFMachineData data = IFMachineData.get(level.getServer());
            for (IFMachineData.Node candidate : data.nodesAtRoomChunk(new net.minecraft.world.level.ChunkPos(worldPosition))) {
                if (IFMachineSpace.isInsideRoom(worldPosition, candidate)) { nodeId = candidate.id; setChanged(); break; }
            }
        } else if (level.getBlockState(worldPosition).is(IFBlocks.INTEGRATION_UNIT.get()) && nodeId != null) {
            ensureHostNode();
        }
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
            // Create restores a network with unloaded stress/capacity snapshots.
            // Re-registering this core must reconcile both totals, otherwise a
            // stale capacity can leave the network overstressed until a source changes.
            if (network != lastStressNetwork || !network.members.containsKey(this)) {
                network.updateNetwork();
                // updateNetwork() may be a no-op when restored totals already
                // match. sync() is still required to clear a persisted
                // overStressed flag on this and the other members.
                network.sync();
            }
            lastStressNetwork = network;
            lastSubmittedStress = applied;
        }
    }

    private void scanComponent() {
        if (level == null || nodeId == null) return;
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        Set<BlockPos> cores = new HashSet<>();
        queue.add(worldPosition); visited.add(worldPosition);
        while (!queue.isEmpty() && visited.size() <= IFServerConfig.MAX_STRUCTURE_BLOCKS.get()) {
            BlockPos at = queue.removeFirst();
            if (level.getBlockState(at).is(IFBlocks.INTEGRATION_UNIT.get())) cores.add(at);
            for (Direction direction : Direction.values()) {
                BlockPos next = at.relative(direction);
                if (visited.contains(next) || !level.hasChunkAt(next)) continue;
                if (isStructureBlock(level.getBlockState(next))) { visited.add(next); queue.addLast(next); }
            }
        }
        IFMachineData.Node node = node();
        if (node == null) return;
        boolean valid = visited.size() <= IFServerConfig.MAX_STRUCTURE_BLOCKS.get() && cores.size() == 1;
        Set<Long> nextMembers = new HashSet<>();
        visited.forEach(member -> nextMembers.add(member.asLong()));
        for (long oldMember : new HashSet<>(node.structureMembers)) {
            if (nextMembers.contains(oldMember)) continue;
            BlockPos oldPos = BlockPos.of(oldMember);
            if (level.hasChunkAt(oldPos) && level.getBlockEntity(oldPos) instanceof IFStructureBlockEntity part && nodeId.equals(part.nodeId)) {
                part.nodeId = null;
                part.setChanged();
            }
        }
        node.structureMembers.clear();
        node.structureMembers.addAll(nextMembers);
        node.valid = valid;
        node.status = valid ? "ready" : "invalid_structure";
        for (BlockPos member : visited) {
            if (member.equals(worldPosition)) continue;
            if (level.getBlockEntity(member) instanceof IFStructureBlockEntity part) {
                part.nodeId = valid ? nodeId : null;
                part.setChanged();
            }
        }
        IFMachineData.get(level.getServer()).changed();
    }

    private static boolean isStructureBlock(BlockState state) {
        return state.is(IFBlocks.INTEGRATION_UNIT.get()) || state.is(IFBlocks.EXTENSION_INTERFACE.get()) ||
                state.is(IFBlocks.INPUT_PORT.get()) || state.is(IFBlocks.OUTPUT_PORT.get());
    }

    public void enterRoom(ServerPlayer player) {
        if (level == null || level.isClientSide || nodeId == null) return;
        IFMachineData.Node n = node();
        if (n != null && n.valid) IFMachineSpace.enter(player, n);
    }

    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (nodeId != null) tag.putUUID("node_id", nodeId);
        if (initialOwner != null) tag.putUUID("initial_owner", initialOwner);
        if (rebindToken != null) tag.putUUID("rebind_token", rebindToken);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("node_id")) nodeId = tag.getUUID("node_id");
        if (tag.hasUUID("initial_owner")) initialOwner = tag.getUUID("initial_owner");
        if (tag.hasUUID("rebind_token")) rebindToken = tag.getUUID("rebind_token");
    }
    public void setInitialOwner(UUID owner) { if (nodeId == null && initialOwner == null) { initialOwner = owner; setChanged(); } }
    public void setRebindIdentity(UUID nodeId, UUID token) { this.nodeId = nodeId; this.rebindToken = token; setChanged(); }
    public void saveRebindToken(CompoundTag tag) { if (rebindToken != null) tag.putUUID("rebind_token", rebindToken); }
}
