package dev.integratedfactory.blockentity;

import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.KineticNetwork;
import dev.integratedfactory.data.IFMachineData;
import dev.integratedfactory.kinetics.IFKineticNetworkSnapshot;
import dev.integratedfactory.registry.IFBlockEntities;
import dev.integratedfactory.space.IFMachineSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;
import dev.integratedfactory.config.IFServerConfig;
import org.jetbrains.annotations.Nullable;

/** Uses Create's kinetic network accounting directly; this block's capacity gates remote processing. */
public final class IFStressPortBlockEntity extends GeneratingKineticBlockEntity {
    private UUID nodeId;
    private float lastCapacity = Float.NaN;
    private float lastGeneratedSpeed = Float.NaN;
    private KineticNetwork observedRemoteNetwork;
    private float observedRemoteStress = Float.NaN;
    /** Number of ticks to let Create finish attaching restored room members. */
    private int networkRecoveryTicks;
    /** Host-side persisted snapshots have been reconciled for this load cycle. */
    private boolean hostSnapshotReconciled;

    public IFStressPortBlockEntity(BlockPos pos, BlockState state) { super(IFBlockEntities.STRESS_PORT.get(), pos, state); }
    @Override public float getGeneratedSpeed() {
        IFMachineData.Node node = node();
        return node == null || !Float.isFinite(node.hostSpeed) ? 0 : node.hostSpeed;
    }

    @Override public float calculateAddedStressCapacity() {
        IFMachineData.Node node = node();
        if (!running(node)) return 0;
        // Create multiplies this per-RPM capacity by the generator's actual speed.
        float rpm = Math.abs(getGeneratedSpeed());
        if (rpm < 0.0001f) return 0;
        return hostAvailableStress(node) / rpm;
    }

    @Override public void onLoad() {
        if (level != null && !level.isClientSide && IFMachineSpace.isFactoryDimension(level)) {
            for (IFMachineData.Node node : IFMachineData.get(level.getServer()).nodesAtRoomChunk(new net.minecraft.world.level.ChunkPos(worldPosition))) {
                if (IFMachineSpace.isInsideRoom(worldPosition, node)) { nodeId = node.id; break; }
            }
        }
        super.onLoad();
        networkRecoveryTicks = 40;
        hostSnapshotReconciled = false;
        IFMachineData.Node node = node();
        if (node != null) {
            // A room chunk can reload independently of the server. Never feed
            // the previous room total into the host while Create is rebuilding.
            node.beginRuntimeRecovery();
            IFMachineData.get(level.getServer()).changed();
        }
    }

    private IFMachineData.Node node() {
        if (level == null || level.isClientSide || nodeId == null) return null;
        return IFMachineData.get(level.getServer()).get(nodeId);
    }

    public void tickBridge() {
        if (level == null || level.isClientSide) return;
        IFMachineData.Node node = node();
        syncHostSpeed(node);
        // The host chunk can load after the room. Keep trying until its
        // Create network is fully reattached, rather than relying on the
        // room's fixed recovery-window ordering.
        reconcileHostSnapshot(node);
        float generatedSpeed = getGeneratedSpeed();
        if (!Float.isFinite(lastGeneratedSpeed) || Math.abs(generatedSpeed - lastGeneratedSpeed) > 0.0001f) {
            updateGeneratedRotation();
            lastGeneratedSpeed = generatedSpeed;
        }
        if (!hasNetwork()) return;
        var network = getOrCreateNetwork();
        // Create can have a kinetic network id before its network object is attached.
        // Do not notify capacity or inspect stress until the authoritative network exists.
        if (network == null) return;
        boolean networkRebuilt = observedRemoteNetwork != network;
        if (networkRebuilt) {
            // Create can replace the network object after late room members
            // attach. Every replacement may carry its own persisted unloaded
            // totals, so clean and sync each new instance, not only on the
            // first tick after onLoad.
            dev.integratedfactory.kinetics.IFKineticNetworkSnapshot.clearUnloadedSnapshot(network);
            network.updateNetwork();
            network.sync();
        }
        if (networkRecoveryTicks > 0) {
            reconcileHostSnapshot(node);
            if (networkRecoveryTicks == 40) {
                dev.integratedfactory.kinetics.IFKineticNetworkSnapshot.clearUnloadedSnapshot(network);
                // The node snapshot was cleared, but the host KineticNetwork
                // may still contain the old core member value. Remove that
                // value before calculating available capacity; otherwise the
                // host stays overstressed until a generator is manually nudged.
                IFMachineData.Node recoveryNode = node();
                if (recoveryNode != null) updateHostStress(recoveryNode);
            }
            networkRecoveryTicks--;
            IFMachineData.Node recoveryNode = node();
            if (recoveryNode != null) {
                if (recoveryNode.remoteStress != 0) {
                    recoveryNode.remoteStress = 0;
                    IFMachineData.get(level.getServer()).changed();
                }
                // Re-open the source using the host's current available SU,
                // but do not publish a partial remote stress total yet.
                float recoveryCapacity = calculateAddedStressCapacity();
                if (Float.isFinite(recoveryCapacity)
                        && (!Float.isFinite(lastCapacity) || Math.abs(recoveryCapacity - lastCapacity) > 0.01f)) {
                    lastCapacity = recoveryCapacity;
                    notifyStressCapacityChange(recoveryCapacity);
                    network.sync();
                }
            }
            observedRemoteNetwork = network;
            observedRemoteStress = Float.NaN;
            return;
        }
        // The room is one fully loaded chunk. Only live members represent the
        // current remote factory; persisted unloaded totals are never bridged.
        float measured = IFKineticNetworkSnapshot.loadedStress(network);
        if (!Float.isFinite(measured)) return;
        boolean stressChanged = networkRebuilt || !Float.isFinite(observedRemoteStress)
                || Float.compare(measured, observedRemoteStress) != 0;
        observedRemoteNetwork = network;
        observedRemoteStress = measured;
        if (node == null) return;
        if (stressChanged && Math.abs(node.remoteStress - measured) > 0.0001f) {
            node.remoteStress = Math.max(0, measured);
            IFMachineData.get(level.getServer()).changed();
            updateHostStress(node);
        } else if (networkRebuilt) {
            // A rebuilt remote network needs one host-side reconciliation even if its
            // total happens to equal the last persisted snapshot.
            updateHostStress(node);
        }

        float capacity = calculateAddedStressCapacity();
        if (!Float.isFinite(lastCapacity) || Math.abs(capacity - lastCapacity) > 0.01f) {
            lastCapacity = capacity;
            notifyStressCapacityChange(capacity);
            network = getOrCreateNetwork();
            if (network == null) return;
        }
        double limit = IFServerConfig.MAX_REMOTE_STRESS.get();
        if (limit > 0 && measured > limit && !node.status.equals("stress_limit")) {
            node.status = "stress_limit";
            IFMachineData.get(level.getServer()).changed();
        } else if (node.status.equals("stress_limit")) {
            node.status = "ready";
            IFMachineData.get(level.getServer()).changed();
        }
    }

    /**
     * Clear only a fully reattached host network's Create reload snapshot.
     * KineticNetwork.calculateStress()/calculateCapacity() include these
     * private unloaded totals. If the host network has no pending members, any
     * remaining snapshot is stale and can leave every member marked over-
     * stressed, which makes the remote gauge show 0 - load. If pending members
     * remain, leave the snapshot intact so legitimate unloaded Create devices
     * continue to contribute normally.
     */
    private void reconcileHostSnapshot(IFMachineData.Node node) {
        if (hostSnapshotReconciled || node == null) return;
        KineticBlockEntity core = hostCore(node);
        if (core == null || !core.hasNetwork()) return;
        KineticNetwork hostNetwork = core.getOrCreateNetwork();
        if (hostNetwork == null || IFKineticNetworkSnapshot.unloadedMembers(hostNetwork) != 0) return;
        IFKineticNetworkSnapshot.clearUnloadedSnapshot(hostNetwork);
        hostNetwork.updateStressFor(core, core.calculateStressApplied());
        hostNetwork.updateNetwork();
        hostNetwork.sync();
        node.recoveryStress = 0;
        hostSnapshotReconciled = true;
    }

    private boolean running(IFMachineData.Node node) {
        if (node == null || !node.active || !node.valid || !hostRunning(node)) return false;
        double limit = IFServerConfig.MAX_REMOTE_STRESS.get();
        return limit <= 0 || node.remoteStress <= limit;
    }

    private boolean hostRunning(IFMachineData.Node node) {
        KineticBlockEntity core = hostCore(node);
        // Actual speed is zero while Create reports overstress. Using it as the
        // bridge gate can deadlock a stale restored network at zero capacity.
        // Theoretical speed remains available for reconciliation; remaining SU is
        // still enforced by hostAvailableStress().
        return core != null && Math.abs(core.getTheoreticalSpeed()) > 0.0001f;
    }

    /** Snapshot the host network's real theoretical RPM; keep the last value if its chunk unloads. */
    private void syncHostSpeed(IFMachineData.Node node) {
        KineticBlockEntity core = hostCore(node);
        if (node == null || core == null) return;
        float speed = core.hasNetwork() ? core.getTheoreticalSpeed() : 0;
        if (!Float.isFinite(speed)) speed = 0;
        if (Math.abs(node.hostSpeed - speed) > 0.0001f) {
            node.hostSpeed = speed;
            IFMachineData.get(level.getServer()).changed();
        }
    }

    private KineticBlockEntity hostCore(IFMachineData.Node node) {
        if (node == null || level == null || level.getServer() == null) return null;
        ResourceLocation id = ResourceLocation.tryParse(node.hostDimension);
        if (id == null) return null;
        ServerLevel host = level.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (host == null) return null;
        BlockPos corePos = BlockPos.of(node.hostPos);
        if (!host.hasChunkAt(corePos)) return null;
        return host.getBlockEntity(corePos) instanceof KineticBlockEntity core ? core : null;
    }

    private float hostAvailableStress(IFMachineData.Node node) {
        KineticBlockEntity core = hostCore(node);
        if (core == null || !core.hasNetwork()) return 0;
        var network = core.getOrCreateNetwork();
        if (network == null) return 0;

        // Do not use KineticNetwork.calculateStress()/calculateCapacity() here:
        // Create adds its persisted unloadedStress/unloadedCapacity snapshots to
        // those totals. During a world reload that stale pair can make the host
        // look overstressed, which gates this source to zero and then reports
        // the remote load as a negative remaining capacity (0 - load).
        //
        // The bridge budget is based on the currently live host network. The
        // core itself is excluded because its mapped remote load is the amount
        // this source is being asked to provide, not an unrelated consumer.
        float unrelatedStress = IFKineticNetworkSnapshot.loadedStress(network, core);
        float liveCapacity = IFKineticNetworkSnapshot.loadedCapacity(network);
        return Math.max(0, liveCapacity - unrelatedStress);
    }

    private void updateHostStress(IFMachineData.Node node) {
        if (level == null || level.getServer() == null) return;
        ResourceLocation id = ResourceLocation.tryParse(node.hostDimension);
        if (id == null) return;
        ServerLevel host = level.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (host != null) {
            BlockPos pos = BlockPos.of(node.hostPos);
            if (host.hasChunkAt(pos) && host.getBlockEntity(pos) instanceof KineticBlockEntity core && core.hasNetwork()) {
                var network = core.getOrCreateNetwork();
                if (network != null) {
                    boolean hadCoreMember = network.members.containsKey(core);
                    if (!hadCoreMember && node.recoveryStress > 0)
                        dev.integratedfactory.kinetics.IFKineticNetworkSnapshot.subtractUnloadedStress(network, node.recoveryStress);
                    network.updateStressFor(core, core.calculateStressApplied());
                    network.updateNetwork();
            network.sync();
                    node.recoveryStress = 0;
                }
            }
        }
    }

    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (nodeId != null) tag.putUUID("node_id", nodeId);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("node_id")) nodeId = tag.getUUID("node_id");
    }
}
