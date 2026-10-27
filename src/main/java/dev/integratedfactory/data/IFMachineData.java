package dev.integratedfactory.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

/** Authoritative, overworld-persisted inventory and room registry for every logical machine node. */
public final class IFMachineData extends SavedData {
    private static final String NAME = "integrated_factory_nodes";
    private final Map<UUID, Node> nodes = new HashMap<>();
    private final Map<UUID, CompoundTag> playerReturns = new HashMap<>();
    private final Set<UUID> deletedNodes = new HashSet<>();
    private final Set<Long> retiredRooms = new HashSet<>();
    private Map<Long, java.util.List<Node>> roomChunkIndex;

    public static IFMachineData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(new Factory<>(IFMachineData::new, IFMachineData::load), NAME);
    }

    public Node create(UUID owner) {
        UUID id = UUID.randomUUID();
        Node node = new Node(id, owner, this);
        nodes.put(id, node);
        roomChunkIndex = null;
        setDirty();
        return node;
    }

    public Node get(UUID id) { return id == null ? null : nodes.get(id); }
    public boolean wasDeleted(UUID id) { return id != null && deletedNodes.contains(id); }
    public boolean deleteNode(UUID id) {
        Node node = nodes.remove(id);
        if (node == null) return false;
        deletedNodes.add(id);
        retiredRooms.add(packRoom(node.roomX, node.roomZ));
        roomsChanged();
        setDirty();
        return true;
    }
    public boolean isRoomRetired(int x, int z) { return retiredRooms.contains(packRoom(x, z)); }
    private static long packRoom(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    public Map<UUID, Node> nodes() { return Map.copyOf(nodes); }
    /** Candidate rooms intersecting a chunk; callers still check exact room bounds. */
    public java.util.List<Node> nodesAtRoomChunk(ChunkPos chunk) {
        if (roomChunkIndex == null) {
            Map<Long, java.util.List<Node>> index = new HashMap<>();
            for (Node node : nodes.values()) {
                int minX = Math.floorDiv(node.roomX, 16), maxX = Math.floorDiv(node.roomX + 9, 16);
                int minZ = Math.floorDiv(node.roomZ, 16), maxZ = Math.floorDiv(node.roomZ + 9, 16);
                for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
                    index.computeIfAbsent(ChunkPos.asLong(x, z), ignored -> new java.util.ArrayList<>()).add(node);
            }
            Map<Long, java.util.List<Node>> immutable = new HashMap<>();
            index.forEach((key, value) -> immutable.put(key, java.util.List.copyOf(value)));
            roomChunkIndex = immutable;
        }
        return roomChunkIndex.getOrDefault(chunk.toLong(), java.util.List.of());
    }
    public void roomsChanged() { roomChunkIndex = null; }
    public void changed() { setDirty(); }

    /**
     * Runtime kinetic values are snapshots, not authoritative inventory data.
     * They must not be fed back into Create before the remote network has had a
     * chance to rebuild after a server/world reload.
     */
    public void resetRuntimeSnapshots() {
        boolean changed = false;
        for (Node node : nodes.values()) {
            if (node.beginRuntimeRecovery()) {
                changed = true;
            }
            if ("stress_limit".equals(node.status)) {
                node.status = "rebuilding";
                changed = true;
            }
        }
        if (changed) setDirty();
    }
    public void savePlayerReturn(UUID player, CompoundTag destination) { playerReturns.put(player, destination.copy()); setDirty(); }
    public CompoundTag getPlayerReturn(UUID player) { CompoundTag tag = playerReturns.get(player); return tag == null ? null : tag.copy(); }
    public void clearPlayerReturn(UUID player) { if (playerReturns.remove(player) != null) setDirty(); }

    private static IFMachineData load(CompoundTag tag, HolderLookup.Provider lookup) {
        IFMachineData data = new IFMachineData();
        ListTag list = tag.getList("nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Node node = Node.load(list.getCompound(i), lookup, data);
            data.nodes.put(node.id, node);
        }
        ListTag returns = tag.getList("player_returns", Tag.TAG_COMPOUND);
        for (int i = 0; i < returns.size(); i++) {
            CompoundTag entry = returns.getCompound(i);
            if (entry.hasUUID("player")) data.playerReturns.put(entry.getUUID("player"), entry.getCompound("destination").copy());
        }
        ListTag deleted = tag.getList("deleted_nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < deleted.size(); i++)
            if (deleted.getCompound(i).hasUUID("id")) data.deletedNodes.add(deleted.getCompound(i).getUUID("id"));
        for (long room : tag.getLongArray("retired_rooms")) data.retiredRooms.add(room);
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        ListTag list = new ListTag();
        nodes.values().forEach(node -> list.add(node.save(lookup)));
        tag.put("nodes", list);
        ListTag returns = new ListTag();
        playerReturns.forEach((player, destination) -> {
            CompoundTag entry = new CompoundTag(); entry.putUUID("player", player); entry.put("destination", destination.copy()); returns.add(entry);
        });
        tag.put("player_returns", returns);
        ListTag deleted = new ListTag();
        for (UUID id : deletedNodes) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", id);
            deleted.add(entry);
        }
        tag.put("deleted_nodes", deleted);
        tag.putLongArray("retired_rooms", retiredRooms.stream().mapToLong(Long::longValue).toArray());
        return tag;
    }

    public static final class Node {
        public final UUID id;
        public UUID owner;
        public final Set<UUID> collaborators = new HashSet<>();
        public final ItemStackHandler input;
        public final ItemStackHandler output;
        public String hostDimension = "minecraft:overworld";
        public long hostPos;
        public int roomX;
        public int roomZ;
        public boolean active = true;
        public boolean valid = true;
        public boolean roomReady;
        public float remoteStress;
        /** Old host-side stress retained transiently while Create rebuilds a network. */
        public float recoveryStress;
        /** Last known theoretical host RPM, retained while the host chunk is unloaded. */
        public float hostSpeed;
        public long lastRoomAccess;
        public String status = "unlinked";
        public String note = "";
        /** One-use authorization for a core item linked from inside this machine room. */
        public UUID pendingCoreToken;
        public final Set<Long> structureMembers = new HashSet<>();

        private Node(UUID id, UUID owner, IFMachineData storage) {
            this.id = id; this.owner = owner;
            this.input = new ItemStackHandler(27) {
                @Override protected void onContentsChanged(int slot) { storage.setDirty(); }
            };
            this.output = new ItemStackHandler(27) {
                @Override protected void onContentsChanged(int slot) { storage.setDirty(); }
            };
        }

        public boolean beginRuntimeRecovery() {
            boolean changed = remoteStress != 0 || hostSpeed != 0;
            recoveryStress = Math.max(recoveryStress, Math.max(0, remoteStress));
            remoteStress = 0;
            hostSpeed = 0;
            return changed;
        }
        private CompoundTag save(HolderLookup.Provider lookup) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("id", id); if (owner != null) tag.putUUID("owner", owner);
            tag.putLongArray("collaborators", collaborators.stream().mapToLong(UUID::getMostSignificantBits).toArray());
            long[] collaboratorLeast = collaborators.stream().mapToLong(UUID::getLeastSignificantBits).toArray();
            tag.putLongArray("collaborator_least", collaboratorLeast);
            tag.put("input", input.serializeNBT(lookup)); tag.put("output", output.serializeNBT(lookup));
            tag.putString("host_dimension", hostDimension); tag.putLong("host_pos", hostPos);
            tag.putInt("room_x", roomX); tag.putInt("room_z", roomZ);
            tag.putBoolean("active", active); tag.putBoolean("valid", valid);
            tag.putBoolean("room_ready", roomReady);
            tag.putLong("last_room_access", lastRoomAccess);
            tag.putLongArray("structure_members", structureMembers.stream().mapToLong(Long::longValue).toArray());
            if (pendingCoreToken != null) tag.putUUID("pending_core_token", pendingCoreToken);
            tag.putFloat("remote_stress", remoteStress); tag.putFloat("host_speed", hostSpeed); tag.putString("status", status); tag.putString("note", note);
            return tag;
        }
        private static Node load(CompoundTag tag, HolderLookup.Provider lookup, IFMachineData storage) {
            Node n = new Node(tag.getUUID("id"), tag.hasUUID("owner") ? tag.getUUID("owner") : null, storage);
            long[] most = tag.getLongArray("collaborators"), least = tag.getLongArray("collaborator_least");
            for (int i = 0; i < Math.min(most.length, least.length); i++) n.collaborators.add(new UUID(most[i], least[i]));
            n.input.deserializeNBT(lookup, tag.getCompound("input")); n.output.deserializeNBT(lookup, tag.getCompound("output"));
            n.hostDimension = tag.getString("host_dimension"); n.hostPos = tag.getLong("host_pos");
            n.roomX = tag.getInt("room_x"); n.roomZ = tag.getInt("room_z");
            n.active = tag.getBoolean("active"); n.valid = tag.getBoolean("valid");
            n.roomReady = tag.getBoolean("room_ready");
            n.lastRoomAccess = tag.getLong("last_room_access");
            for (long member : tag.getLongArray("structure_members")) n.structureMembers.add(member);
            if (tag.hasUUID("pending_core_token")) n.pendingCoreToken = tag.getUUID("pending_core_token");
            n.remoteStress = tag.getFloat("remote_stress"); n.hostSpeed = tag.getFloat("host_speed"); n.status = tag.getString("status"); n.note = tag.getString("note");
            return n;
        }
    }
}
