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
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.Direction;
import dev.integratedfactory.config.IFServerConfig;
import net.minecraft.world.level.LevelReader;

public class IFStructureBlockEntity extends KineticBlockEntity {
    private UUID nodeId;
    private UUID initialOwner;
    private UUID rebindToken;
    /** Set only by a real player placement event; schematic placement has no permit. */
    private transient boolean playerPlacementAuthorized;
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
        // A core may only be activated by a real player placement (or by the
        // explicit empty-hand interaction used to migrate a legacy unbound
        // block).  Create's schematic printer places blocks directly and
        // does not emit EntityPlaceEvent, so without this gate it could
        // silently create a fresh machine node from a blueprint.
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
        // Resolve a newly introduced/relocated core before it can create a
        // node or consume a bound rebind token. This prevents a schematic,
        // piston-like mover, or copied BE from changing an existing node's
        // host position before the component is validated.
        if (be.rejectConflictingCore()) return;
        be.ensureHostNode();
        be.updateStressIfChanged();
        if (++be.scanDelay < 40) return;
        be.scanDelay = 0;
        be.scanComponent();
    }

    @Override public void onLoad() {
        super.onLoad();
        if (level == null || level.isClientSide) return;
        if (nodeId != null && IFMachineData.get(level.getServer()).wasDeleted(nodeId)) {
            // Deleted worlds must not leave UUIDs on preserved ports or on a
            // late-loaded replacement core.
            nodeId = null;
            rebindToken = null;
            setChanged();
        }
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
        if (cores.size() > 1) {
            BlockPos keeper = selectAuthoritativeCore(cores);
            if (worldPosition.equals(keeper)) {
                for (BlockPos duplicate : cores) if (!duplicate.equals(keeper)) ejectCore(level, duplicate);
            }
            // The component will be rescanned after the duplicate blocks have
            // been removed. Do not assign any ports to a multi-core component.
            return;
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

    public static boolean isCore(BlockState state) {
        return state.is(IFBlocks.INTEGRATION_UNIT.get()) || state.is(IFBlocks.SIMPLE_INTEGRATION_UNIT.get());
    }

    public static boolean isStructureBlock(BlockState state) {
        return state.is(IFBlocks.INTEGRATION_UNIT.get()) || state.is(IFBlocks.EXTENSION_INTERFACE.get()) ||
                state.is(IFBlocks.INPUT_PORT.get()) || state.is(IFBlocks.OUTPUT_PORT.get());
    }

    /** Used by placement checks to reject integration cores touching a formed structure. */
    public static boolean hasAdjacentStructure(LevelReader level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos adjacentPos = pos.relative(direction);
            BlockState adjacent = level.getBlockState(adjacentPos);
            // Simple integration units are independent nodes and deliberately
            // do not participate in the large-structure conflict rule.
            if (adjacent.is(IFBlocks.INTEGRATION_UNIT.get())) return true;
            // An orphan port/extension is not a formed machine structure.
            // Walk only its six-face structure component and stop only when a
            // real integration-core block is found; no node/BE sync race can
            // make a connected core invisible at placement time.
            if (isStructureBlock(adjacent) && componentContainsIntegrationCore(level, adjacentPos)) return true;
        }
        return false;
    }

    private static boolean componentContainsIntegrationCore(LevelReader level, BlockPos start) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty() && visited.size() <= IFServerConfig.MAX_STRUCTURE_BLOCKS.get()) {
            BlockPos at = queue.removeFirst();
            if (level.getBlockState(at).is(IFBlocks.INTEGRATION_UNIT.get())) return true;
            for (Direction direction : Direction.values()) {
                BlockPos next = at.relative(direction);
                if (visited.contains(next) || !level.hasChunkAt(next)) continue;
                if (isStructureBlock(level.getBlockState(next))) {
                    visited.add(next);
                    queue.addLast(next);
                }
            }
        }
        return false;
    }

    /**
     * Checks whether an extension placed at {@code pos} would join two
     * already separate multi-block structures.  The candidate block is not
     * present yet, so each adjacent structure is traversed independently.
     */
    public static boolean wouldConnectMultipleStructures(LevelReader level, BlockPos pos) {
        Set<BlockPos> visited = new HashSet<>();
        Set<UUID> nodeIds = new HashSet<>();
        int unboundCores = 0;
        int structureRoots = 0;
        for (Direction direction : Direction.values()) {
            BlockPos root = pos.relative(direction);
            if (!isStructureBlock(level.getBlockState(root)) || visited.contains(root)) continue;
            structureRoots++;
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(root);
            visited.add(root);
            while (!queue.isEmpty() && visited.size() <= IFServerConfig.MAX_STRUCTURE_BLOCKS.get()) {
                BlockPos at = queue.removeFirst();
                BlockState state = level.getBlockState(at);
                if (state.is(IFBlocks.INTEGRATION_UNIT.get())) {
                    if (level.getBlockEntity(at) instanceof IFStructureBlockEntity core && core.nodeId != null) {
                        nodeIds.add(core.nodeId);
                    } else {
                        unboundCores++;
                    }
                }
                for (Direction nextDirection : Direction.values()) {
                    BlockPos next = at.relative(nextDirection);
                    if (visited.contains(next) || !level.hasChunkAt(next)) continue;
                    if (isStructureBlock(level.getBlockState(next))) {
                        visited.add(next);
                        queue.addLast(next);
                    }
                }
            }
        }
        if (structureRoots < 2) return false;
        // One node may have disconnected pieces that are safely joined.  Two
        // different node IDs (or any unbound core alongside another core)
        // represent a real multi-core merge and must be rejected before the
        // extension consumes the player's item.
        return nodeIds.size() > 1 || unboundCores > 0 && (nodeIds.size() > 0 || unboundCores > 1);
    }

    private boolean rejectConflictingCore() {
        IFMachineData data = IFMachineData.get(level.getServer());
        // A core dropped before /factory delete can still carry the deleted
        // node UUID.  A player may reuse that item as a fresh core; otherwise
        // placing it and ejecting it on the next tick desynchronises the
        // inventory and makes the block appear to be swallowed.
        if (nodeId != null && data.wasDeleted(nodeId)) {
            if (playerPlacementAuthorized) {
                nodeId = null;
                rebindToken = null;
                setChanged();
                return false;
            }
            ejectCore(level, worldPosition);
            return true;
        }
        IFMachineData.Node saved = data.get(nodeId);
        // Unbound cores created without a player placement permit are almost
        // certainly schematic/contraption output.  Remove them as ordinary
        // drops before they can ever allocate a node.
        if (nodeId == null && !playerPlacementAuthorized) {
            ejectCore(level, worldPosition);
            return true;
        }
        // A newly placed player core is not yet associated with any node.  Do
        // not arbitrate it against an existing structure during this first
        // tick; let ensureHostNode create its own node.  The placement checks
        // prevent a normal player placement from touching a formed structure,
        // while a forced connection is still resolved once both cores are
        // fully initialized.
        if (nodeId == null && playerPlacementAuthorized) return false;
        // A token referring to a deleted/missing node is not a valid binding
        // anymore.  Drop it as an ordinary core instead of allocating a new
        // node under an unrelated UUID.
        if (nodeId != null && saved == null) {
            ejectCore(level, worldPosition);
            return true;
        }
        boolean isCanonical = nodeId != null && saved != null
                && saved.hostDimension.equals(level.dimension().location().toString())
                && saved.hostPos == worldPosition.asLong();
        // A canonical coordinate whose node is already marked core_removed is
        // a replacement target, not a loaded original.  Requiring the player
        // placement permit here also blocks a blueprint copy printed back at
        // the old coordinate from silently resurrecting the node.
        if (isCanonical && "core_removed".equals(saved.status) && !playerPlacementAuthorized) {
            ejectCore(level, worldPosition);
            return true;
        }
        // A copied bound BE at another position must never be allowed to
        // rebind silently. Only a player placement event can authorize that
        // operation; Create's schematic printer has no such permit.
        if (nodeId != null && saved != null && !isCanonical && !playerPlacementAuthorized) {
            ejectCore(level, worldPosition);
            return true;
        }
        // A manually placed copy may carry a valid node id.  It must not be
        // allowed to remain as a second host merely because the placement was
        // performed by a player.  A pending rebind token is the only explicit
        // exception; it is consumed by ensureHostNode after this check.
        if (nodeId != null && saved != null && !isCanonical && playerPlacementAuthorized
                && saved.pendingCoreToken == null && !"core_removed".equals(saved.status)) {
            ejectCore(level, worldPosition);
            return true;
        }
        // A rebind token is intentionally powerful, but it must never let a
        // second core take over while the original host is still present.
        // Treat an unloaded host chunk as occupied as well; this conservative
        // choice avoids a race where the old core is loaded a tick later.
        if (nodeId != null && saved != null && !isCanonical && playerPlacementAuthorized
                && saved.pendingCoreToken != null && hasBoundHostCore(level, saved, worldPosition)) {
            ejectCore(level, worldPosition);
            return true;
        }
        if (isCanonical) return false;
        Set<BlockPos> cores = findConnectedCores(level, worldPosition);
        if (cores.size() <= 1) return false;
        BlockPos keeper = selectAuthoritativeCore(cores);
        if (worldPosition.equals(keeper)) {
            for (BlockPos duplicate : cores) if (!duplicate.equals(keeper)) ejectCore(level, duplicate);
        } else {
            ejectCore(level, worldPosition);
        }
        return true;
    }

    private static Set<BlockPos> findConnectedCores(Level level, BlockPos start) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        Set<BlockPos> cores = new HashSet<>();
        queue.add(start); visited.add(start);
        int limit = IFServerConfig.MAX_STRUCTURE_BLOCKS.get();
        while (!queue.isEmpty() && visited.size() <= limit) {
            BlockPos at = queue.removeFirst();
            if (level.getBlockState(at).is(IFBlocks.INTEGRATION_UNIT.get())) cores.add(at);
            for (Direction direction : Direction.values()) {
                BlockPos next = at.relative(direction);
                if (visited.contains(next) || !level.hasChunkAt(next)) continue;
                if (isStructureBlock(level.getBlockState(next))) { visited.add(next); queue.addLast(next); }
            }
        }
        return cores;
    }

    private BlockPos selectAuthoritativeCore(Set<BlockPos> cores) {
        return selectAuthoritativeCore(level, cores);
    }

    private static BlockPos selectAuthoritativeCore(Level level, Set<BlockPos> cores) {
        IFMachineData data = IFMachineData.get(level.getServer());
        return cores.stream().filter(pos -> {
            if (!(level.getBlockEntity(pos) instanceof IFStructureBlockEntity core) || core.nodeId == null) return false;
            IFMachineData.Node node = data.get(core.nodeId);
            return node != null && node.hostDimension.equals(level.dimension().location().toString()) && node.hostPos == pos.asLong();
        }).min(Comparator.comparingInt((BlockPos pos) -> {
            // A core placed during the current session carries a transient
            // player permit.  Prefer the already-existing canonical core so
            // a forced connection cannot eject the original merely because
            // its coordinates sort after the new block.
            if (level.getBlockEntity(pos) instanceof IFStructureBlockEntity core && core.playerPlacementAuthorized) return 1;
            return 0;
        }).thenComparingLong(BlockPos::asLong)).orElseGet(() ->
                cores.stream().min(Comparator.comparingLong(BlockPos::asLong)).orElseThrow());
    }

    private static void ejectCore(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(IFBlocks.INTEGRATION_UNIT.get())) return;
        BlockEntity be = level.getBlockEntity(pos);
        net.minecraft.world.item.ItemStack drop = dev.integratedfactory.block.IFBoundBlockDrops.createCoreDrop(state, be);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Block.popResource(level, pos, drop);
    }

    /**
     * Returns true when a node's recorded host still contains its original
     * core.  Unknown (unloaded) chunks are treated as occupied so a rebind
     * cannot race the original core's chunk loading.
     */
    public static boolean hasBoundHostCore(Level level, IFMachineData.Node node, BlockPos current) {
        if (node == null || !node.hostDimension.equals(level.dimension().location().toString())) return false;
        BlockPos host = BlockPos.of(node.hostPos);
        if (host.equals(current)) return false;
        if (!level.hasChunkAt(host)) return true;
        BlockState state = level.getBlockState(host);
        if (!isCore(state)) return false;
        BlockEntity be = level.getBlockEntity(host);
        if (be instanceof IFStructureBlockEntity core) return node.id.equals(core.nodeId);
        if (be instanceof IFSimpleUnitBlockEntity simple) return node.id.equals(simple.nodeId());
        // The state is a core but its BE is not available yet.  Keep the
        // original protected until the level finishes loading it.
        return true;
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
    public void markPlayerPlacement() { playerPlacementAuthorized = true; setChanged(); }
    public void clearNodeBinding() { nodeId = null; rebindToken = null; initialOwner = null; playerPlacementAuthorized = false; setChanged(); }
    public void setRebindIdentity(UUID nodeId, UUID token) { this.nodeId = nodeId; this.rebindToken = token; setChanged(); }
    public void saveRebindToken(CompoundTag tag) { if (rebindToken != null) tag.putUUID("rebind_token", rebindToken); }
}
