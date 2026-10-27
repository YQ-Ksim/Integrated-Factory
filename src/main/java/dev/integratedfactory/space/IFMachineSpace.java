package dev.integratedfactory.space;

import dev.integratedfactory.IntegratedFactory;
import dev.integratedfactory.data.IFMachineData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Comparator;
import dev.integratedfactory.config.IFServerConfig;
import dev.integratedfactory.menu.IFNodeMenu;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Multiple node-isolated 10x10x10 rooms backed by one datapack dimension. */
public final class IFMachineSpace {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(IntegratedFactory.MOD_ID, "factory_room"));
    private static final String PLAYER_TAG = "integrated_factory_return";
    private static final int ROOM_CHUNK_SPACING = 2;
    private static final int ROOM_CHUNK_INSET = 3;
    public static final TicketController ROOM_TICKETS = new TicketController(
            ResourceLocation.fromNamespaceAndPath(IntegratedFactory.MOD_ID, "machine_rooms"));

    public static void registerTicketController(RegisterTicketControllersEvent event) {
        event.register(ROOM_TICKETS);
    }

    public static void onServerStarted(ServerStartedEvent event) {
        // Clear saved kinetic snapshots before any host core or stress port
        // ticks. The remote Create network will publish a fresh measured load.
        IFMachineData.get(event.getServer()).resetRuntimeSnapshots();
        refreshForcedRooms(event.getServer());
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().overworld().getGameTime() % 100 == 0)
            refreshForcedRooms(event.getServer());
    }

    public static void allocate(IFMachineData.Node node, MinecraftServer server) {
        IFMachineData data = IFMachineData.get(server);
        Set<Long> used = new HashSet<>();
        for (IFMachineData.Node other : data.nodes().values()) used.add(pack(other.roomX, other.roomZ));
        int slot = Math.floorMod(node.id.hashCode(), 1_000_000);
        while (true) {
            int chunkX = Math.floorMod(slot, 1000) * ROOM_CHUNK_SPACING;
            int chunkZ = Math.floorDiv(slot, 1000) * ROOM_CHUNK_SPACING;
            int roomX = chunkX * 16 + ROOM_CHUNK_INSET;
            int roomZ = chunkZ * 16 + ROOM_CHUNK_INSET;
            if (!used.contains(pack(roomX, roomZ)) && !data.isRoomRetired(roomX, roomZ)) {
                node.roomX = roomX;
                node.roomZ = roomZ;
                break;
            }
            slot = (slot + 1) % 1_000_000;
        }
        data.changed();
        data.roomsChanged();
    }

    private static long pack(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    private static java.util.List<ChunkPos> roomChunks(IFMachineData.Node node) {
        int minX = Math.floorDiv(node.roomX - 1, 16);
        int maxX = Math.floorDiv(node.roomX + 10, 16);
        int minZ = Math.floorDiv(node.roomZ - 1, 16);
        int maxZ = Math.floorDiv(node.roomZ + 10, 16);
        java.util.ArrayList<ChunkPos> chunks = new java.util.ArrayList<>();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) chunks.add(new ChunkPos(x, z));
        return chunks;
    }

    /** Reconciles persistent tickets and enforces the server's total forced-chunk limit. */
    public static Set<UUID> refreshForcedRooms(MinecraftServer server) {
        ServerLevel room = server.getLevel(DIMENSION);
        if (room == null) return Set.of();
        IFMachineData data = IFMachineData.get(server);
        int maxChunks = IFServerConfig.MAX_FORCE_LOADED_CHUNKS.get();
        java.util.List<IFMachineData.Node> candidates = data.nodes().values().stream()
                .filter(node -> node.roomReady && node.valid && node.active && isHostChunkLoaded(server, node))
                .sorted(Comparator.comparingLong((IFMachineData.Node node) -> node.lastRoomAccess).reversed()
                        .thenComparing(node -> node.id))
                .toList();
        Set<UUID> selected = new HashSet<>();
        int selectedChunks = 0;
        for (IFMachineData.Node node : candidates) {
            int needed = roomChunks(node).size();
            if (needed == 0 || selectedChunks + needed > maxChunks) continue;
            selected.add(node.id);
            selectedChunks += needed;
        }

        // Remove stale or over-limit tickets first. Keep the ticking flag consistent with add calls.
        for (IFMachineData.Node node : data.nodes().values()) {
            if (selected.contains(node.id)) continue;
            for (ChunkPos chunk : roomChunks(node))
                ROOM_TICKETS.forceChunk(room, node.id, chunk.x, chunk.z, false, true);
        }
        for (IFMachineData.Node node : candidates) {
            if (!selected.contains(node.id)) continue;
            for (ChunkPos chunk : roomChunks(node))
                ROOM_TICKETS.forceChunk(room, node.id, chunk.x, chunk.z, true, true);
        }
        return selected;
    }

    /** Deletes node metadata and tickets while deliberately leaving the room's blocks untouched. */
    public static boolean deleteNode(MinecraftServer server, IFMachineData.Node node) {
        IFMachineData data = IFMachineData.get(server);
        ServerLevel room = server.getLevel(DIMENSION);
        if (room != null) {
            for (ChunkPos chunk : roomChunks(node))
                ROOM_TICKETS.forceChunk(room, node.id, chunk.x, chunk.z, false, true);
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            var back = player.getPersistentData().getCompound(PLAYER_TAG);
            if (back.hasUUID("node_id") && node.id.equals(back.getUUID("node_id"))) returnPlayer(player);
        }
        boolean deleted = data.deleteNode(node.id);
        refreshForcedRooms(server);
        return deleted;
    }

    /** Remote Create networks cannot run while the host core is unavailable, so don't tick their rooms either. */
    private static boolean isHostChunkLoaded(MinecraftServer server, IFMachineData.Node node) {
        ResourceLocation dimensionId = ResourceLocation.tryParse(node.hostDimension);
        if (dimensionId == null) return false;
        ServerLevel host = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
        return host != null && host.hasChunkAt(BlockPos.of(node.hostPos));
    }

    public static void enter(ServerPlayer player, IFMachineData.Node node) {
        if (player.getPersistentData().contains(PLAYER_TAG)) {
            player.displayClientMessage(Component.literal("请先输入 /factory_return 返回当前工厂。"), true);
            return;
        }
        if (!IFNodeMenu.canAccess(node, player)) {
            player.displayClientMessage(Component.literal("你没有此工厂节点的访问权限。"), true);
            return;
        }
        MinecraftServer server = player.getServer();
        ServerLevel room = server == null ? null : server.getLevel(DIMENSION);
        if (room == null) {
            player.displayClientMessage(Component.literal("工厂空间维度未加载：请确认 integrated_factory:factory_room 已注册。"), true);
            return;
        }
        if (!node.roomReady) {
            buildRoom(room, new BlockPos(node.roomX, 64, node.roomZ));
            node.roomReady = true;
        }
        node.lastRoomAccess = room.getGameTime();
        IFMachineData.get(server).changed();
        Set<UUID> forcedRooms = refreshForcedRooms(server);
        if (!forcedRooms.contains(node.id))
            player.displayClientMessage(Component.literal("服务器的机器空间强加载区块额度已用完；此空间会在玩家离开后暂停加载。"), true);
        Vec3 arrival = findSafeArrival(room, player, node);
        if (arrival == null) {
            player.displayClientMessage(Component.literal("工厂空间入口被方块占满，已取消传送。请清出至少一个可站立位置后重试。"), true);
            return;
        }
        if (node.owner == null) { node.owner = player.getUUID(); IFMachineData.get(server).changed(); }
        var data = player.getPersistentData();
        var back = new net.minecraft.nbt.CompoundTag();
        back.putString("dimension", player.level().dimension().location().toString());
        back.putDouble("x", player.getX()); back.putDouble("y", player.getY()); back.putDouble("z", player.getZ());
        back.putFloat("yaw", player.getYRot()); back.putFloat("pitch", player.getXRot());
        back.putBoolean("may_fly", player.getAbilities().mayfly); back.putBoolean("flying", player.getAbilities().flying);
        back.putUUID("node_id", node.id);
        data.put(PLAYER_TAG, back);
        IFMachineData.get(server).savePlayerReturn(player.getUUID(), back);
        player.teleportTo(room, arrival.x, arrival.y, arrival.z, Set.of(), player.getYRot(), player.getXRot());
        if (IFServerConfig.ROOM_FLIGHT.get()) { player.getAbilities().mayfly = true; player.getAbilities().flying = true; player.onUpdateAbilities(); }
        player.displayClientMessage(Component.literal("已进入工厂空间 " + node.id.toString().substring(0, 8) + "。输入 /factory_return 返回。"), true);
    }

    private static void buildRoom(ServerLevel level, BlockPos floor) {
        for (int x = -1; x <= 10; x++) for (int z = -1; z <= 10; z++) {
            boolean edge = x == -1 || x == 10 || z == -1 || z == 10;
            level.setBlock(new BlockPos(floor.getX() + x, 64, floor.getZ() + z), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
            for (int y = 65; y <= 74; y++) {
                BlockPos p = new BlockPos(floor.getX() + x, y, floor.getZ() + z);
                level.setBlock(p, edge ? Blocks.BARRIER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private static Vec3 findSafeArrival(ServerLevel level, ServerPlayer player, IFMachineData.Node node) {
        ArrayList<Vec3> candidates = new ArrayList<>();
        for (int x = 0; x < 10; x++) for (int z = 0; z < 10; z++) for (int y = 65; y <= 72; y++)
            candidates.add(new Vec3(node.roomX + x + 0.5, y, node.roomZ + z + 0.5));
        candidates.sort(Comparator.comparingDouble(pos ->
                Math.abs(pos.x - (node.roomX + 5.5)) + Math.abs(pos.z - (node.roomZ + 5.5)) + Math.abs(pos.y - 66) * 2));
        AABB currentBox = player.getBoundingBox();
        for (Vec3 pos : candidates) {
            AABB targetBox = currentBox.move(pos.x - player.getX(), pos.y - player.getY(), pos.z - player.getZ());
            BlockPos feet = BlockPos.containing(pos);
            if (level.getFluidState(feet).isEmpty() && level.noCollision(player, targetBox)) return pos;
        }
        return null;
    }

    public static boolean isInsideRoom(BlockPos pos, IFMachineData.Node node) {
        return pos.getX() >= node.roomX && pos.getX() < node.roomX + 10 && pos.getZ() >= node.roomZ && pos.getZ() < node.roomZ + 10 && pos.getY() >= 65 && pos.getY() <= 74;
    }

    public static boolean isFactoryDimension(Level level) { return level.dimension().equals(DIMENSION); }

    public static void constrainPlayer(ServerPlayer player) {
        if (!isFactoryDimension(player.level())) return;
        var back = player.getPersistentData().getCompound(PLAYER_TAG);
        if (!back.hasUUID("node_id")) { returnPlayer(player); return; }
        IFMachineData.Node node = IFMachineData.get(player.getServer()).get(back.getUUID("node_id"));
        if (node == null || player.getX() < node.roomX || player.getX() >= node.roomX + 10 || player.getZ() < node.roomZ || player.getZ() >= node.roomZ + 10 || player.getY() < 65 || player.getY() > 74) {
            if (node == null) { returnPlayer(player); return; }
            ServerLevel room = player.getServer().getLevel(DIMENSION);
            Vec3 safe = room == null ? null : findSafeArrival(room, player, node);
            if (safe == null) { returnPlayer(player); return; }
            player.teleportTo(room, safe.x, safe.y, safe.z, Set.of(), player.getYRot(), player.getXRot());
        }
    }

    public static void returnPlayer(ServerPlayer player) {
        var data = player.getPersistentData();
        MinecraftServer server = player.getServer();
        net.minecraft.nbt.CompoundTag back = data.contains(PLAYER_TAG) ? data.getCompound(PLAYER_TAG).copy() : IFMachineData.get(server).getPlayerReturn(player.getUUID());
        if (back == null) {
            if (!isFactoryDimension(player.level())) return;
            ServerLevel overworld = server.overworld();
            BlockPos spawn = overworld.getSharedSpawnPos();
            player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY() + 1.0, spawn.getZ() + 0.5, Set.of(), player.getYRot(), player.getXRot());
            player.getAbilities().mayfly = player.isCreative() || player.isSpectator();
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(back.getString("dimension"));
        ResourceKey<Level> key = id == null ? Level.OVERWORLD : ResourceKey.create(Registries.DIMENSION, id);
        ServerLevel target = server.getLevel(key);
        double x = back.getDouble("x"), y = back.getDouble("y"), z = back.getDouble("z");
        if (target == null) {
            target = server.overworld();
            BlockPos spawn = target.getSharedSpawnPos();
            x = spawn.getX() + 0.5; y = spawn.getY() + 1.0; z = spawn.getZ() + 0.5;
        }
        player.teleportTo(target, x, y, z, Set.of(), back.getFloat("yaw"), back.getFloat("pitch"));
        player.getAbilities().mayfly = back.getBoolean("may_fly");
        player.getAbilities().flying = back.getBoolean("flying") && player.getAbilities().mayfly;
        player.onUpdateAbilities(); data.remove(PLAYER_TAG);
        IFMachineData.get(server).clearPlayerReturn(player.getUUID());
    }
    private IFMachineSpace() {}
}
