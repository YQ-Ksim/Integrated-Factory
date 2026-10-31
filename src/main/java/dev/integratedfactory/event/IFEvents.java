package dev.integratedfactory.event;

import dev.integratedfactory.data.IFMachineData;
import dev.integratedfactory.space.IFMachineSpace;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionResult;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.EntityArgument;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.integratedfactory.menu.IFNodeMenu;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.entity.BlockEntity;
import com.simibubi.create.content.schematics.cannon.LaunchedItem;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

@EventBusSubscriber(modid = "integrated_factory")
public final class IFEvents {
    @SubscribeEvent public static void registerCommands(RegisterCommandsEvent event) {
        var returnCommand = Commands.literal("factory_return").executes(ctx -> {
            if (ctx.getSource().getEntity() instanceof ServerPlayer player) IFMachineSpace.returnPlayer(player);
            return 1;
        });
        event.getDispatcher().register(returnCommand);
        var factory = Commands.literal("factory");
        factory.then(Commands.literal("return").executes(ctx -> {
            if (ctx.getSource().getEntity() instanceof ServerPlayer player) IFMachineSpace.returnPlayer(player);
            return 1;
        }));
        factory.then(Commands.literal("inventory").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            IFMachineData.Node node = currentNode(player);
            if (node == null) { player.displayClientMessage(Component.literal("请先进入一个工厂空间。"), false); return 0; }
            IFNodeMenu.open(player, node); return 1;
        }));
        factory.then(Commands.literal("status").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            IFMachineData.Node node = currentNode(player);
            if (node == null) { player.displayClientMessage(Component.literal("请先进入一个工厂空间。"), false); return 0; }
            if (!IFNodeMenu.canAccess(node, player)) { player.displayClientMessage(Component.literal("你没有此节点的访问权限。"), false); return 0; }
            player.displayClientMessage(Component.literal("节点 " + node.id + " | 状态=" + node.status + " | 远端负载=" + node.remoteStress + " SU | 协作者=" + node.collaborators.size()), false);
            return 1;
        }));
        factory.then(Commands.literal("worlds").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            var owned = IFMachineData.get(player.getServer()).nodes().values().stream()
                    .filter(node -> player.getUUID().equals(node.owner))
                    .sorted(java.util.Comparator.comparing(node -> node.id.toString())).toList();
            player.displayClientMessage(Component.literal("你创建的机器世界：" + owned.size()), false);
            if (owned.isEmpty()) {
                player.displayClientMessage(Component.literal("尚无已绑定到你名下的机器世界。"), false);
                return 0;
            }
            for (int i = 0; i < owned.size(); i++) {
                IFMachineData.Node node = owned.get(i);
                player.displayClientMessage(worldRow(node, (i + 1) + ". "), false);
            }
            return owned.size();
        }));
        factory.then(Commands.literal("enter").then(Commands.argument("node", StringArgumentType.word()).executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            IFMachineData.Node node = findNode(player, StringArgumentType.getString(ctx, "node"));
            if (node == null) { player.displayClientMessage(Component.literal("找不到该机器世界，或你没有访问权限。"), false); return 0; }
            IFMachineSpace.enter(player, node);
            return 1;
        })));
        factory.then(Commands.literal("delete").then(Commands.argument("node", StringArgumentType.word())
                .executes(ctx -> requestDelete(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "node")))
                .then(Commands.literal("confirm").executes(ctx -> deleteNode(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "node"))))));
        factory.then(Commands.literal("note").then(Commands.argument("node", StringArgumentType.word())
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    IFMachineData.Node node = findNode(player, StringArgumentType.getString(ctx, "node"));
                    if (node == null || !player.getUUID().equals(node.owner)) {
                        player.displayClientMessage(Component.literal("只有机器世界的创建者可以查看或修改备注。"), false); return 0;
                    }
                    player.displayClientMessage(Component.literal("备注：" + (node.note.isBlank() ? "（无）" : node.note)), false);
                    return 1;
                })
                .then(Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    IFMachineData.Node node = findNode(player, StringArgumentType.getString(ctx, "node"));
                    if (node == null || !player.getUUID().equals(node.owner)) {
                        player.displayClientMessage(Component.literal("只有机器世界的创建者可以修改备注。"), false); return 0;
                    }
                    String note = StringArgumentType.getString(ctx, "text").trim();
                    if (note.length() > 128) {
                        player.displayClientMessage(Component.literal("备注最多 128 个字符。"), false); return 0;
                    }
                    node.note = note;
                    IFMachineData.get(player.getServer()).changed();
                    player.displayClientMessage(Component.literal("机器世界备注已更新。"), false);
                    return 1;
                }))));
        factory.then(Commands.literal("allow").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            IFMachineData.Node node = currentNode(player);
            if (node == null || !IFNodeMenu.isOwner(node, player)) { player.displayClientMessage(Component.literal("只有节点所有者能管理协作者，请在工厂空间内操作。"), false); return 0; }
            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
            if (node.collaborators.add(target.getUUID())) IFMachineData.get(player.getServer()).changed();
            player.displayClientMessage(Component.literal("已授权 " + target.getGameProfile().getName() + " 访问此工厂节点。"), false); return 1;
        })));
        factory.then(Commands.literal("deny").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            IFMachineData.Node node = currentNode(player);
            if (node == null || !IFNodeMenu.isOwner(node, player)) { player.displayClientMessage(Component.literal("只有节点所有者能管理协作者，请在工厂空间内操作。"), false); return 0; }
            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
            if (node.collaborators.remove(target.getUUID())) IFMachineData.get(player.getServer()).changed();
            player.displayClientMessage(Component.literal("已撤销 " + target.getGameProfile().getName() + " 的节点访问权限。"), false); return 1;
        })));
        event.getDispatcher().register(factory);
    }

    @SubscribeEvent public static void protectRoom(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || !IFMachineSpace.isFactoryDimension(player.level())) return;
        if (!isCurrentNodeRoom(player, event.getPos())) event.setCanceled(true);
    }
    @SubscribeEvent public static void protectCorePlacementAttempt(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide || IFMachineSpace.isFactoryDimension(event.getLevel())) return;
        ItemStack stack = event.getItemStack();
        boolean integrationCore = stack.is(dev.integratedfactory.registry.IFBlocks.INTEGRATION_UNIT.get().asItem());
        boolean extension = stack.is(dev.integratedfactory.registry.IFBlocks.EXTENSION_INTERFACE.get().asItem());
        boolean inputPort = stack.is(dev.integratedfactory.registry.IFBlocks.INPUT_PORT.get().asItem());
        boolean outputPort = stack.is(dev.integratedfactory.registry.IFBlocks.OUTPUT_PORT.get().asItem());
        boolean connector = extension || inputPort || outputPort;
        if (!integrationCore && !connector) return;
        BlockPos clicked = event.getPos();
        BlockPos target = event.getLevel().getBlockState(clicked).canBeReplaced()
                ? clicked : clicked.relative(event.getFace());
        if (integrationCore && dev.integratedfactory.blockentity.IFStructureBlockEntity.hasAdjacentStructure(event.getLevel(), target)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            event.getEntity().displayClientMessage(Component.literal("集成核心不能放置在已有核心或已成型机器结构旁边。"), true);
            return;
        }
        if (connector && dev.integratedfactory.blockentity.IFStructureBlockEntity.wouldConnectMultipleStructures(event.getLevel(), target)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            event.getEntity().displayClientMessage(Component.literal("端口或拓展接口不能连接两个已有的机器结构。"), true);
        }
    }
    @SubscribeEvent public static void bindCoreFromMachineRoom(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getEntity().isShiftKeyDown() || !IFMachineSpace.isFactoryDimension(event.getLevel())) return;
        var stack = event.getItemStack();
        boolean isCore = stack.is(dev.integratedfactory.registry.IFBlocks.INTEGRATION_UNIT.get().asItem())
                || stack.is(dev.integratedfactory.registry.IFBlocks.SIMPLE_INTEGRATION_UNIT.get().asItem());
        if (!isCore) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        IFMachineData.Node node = currentNode(player);
        if (node == null || !IFMachineSpace.isInsideRoom(player.blockPosition(), node)) {
            player.displayClientMessage(Component.literal("无法识别你所在的机器空间。"), true);
            return;
        }
        if (!IFNodeMenu.isOwner(node, player)) {
            player.displayClientMessage(Component.literal("只有该机器世界的所有者可以重新绑定核心。"), true);
            return;
        }
        if (stack.getCount() != 1) {
            player.displayClientMessage(Component.literal("请先将核心单独放入快捷栏，再进行绑定。"), true);
            return;
        }

        java.util.UUID token = java.util.UUID.randomUUID();
        node.pendingCoreToken = token;
        node.valid = false;
        node.active = false;
        node.status = "awaiting_rebind";
        IFMachineData.get(player.getServer()).changed();

        net.minecraft.nbt.CompoundTag boundData = new net.minecraft.nbt.CompoundTag();
        boundData.putString("id", stack.is(dev.integratedfactory.registry.IFBlocks.INTEGRATION_UNIT.get().asItem())
                ? "integrated_factory:structure" : "integrated_factory:simple_unit");
        boundData.putUUID("node_id", node.id);
        boundData.putUUID("rebind_token", token);
        boundData.putBoolean("integrated_factory_bound", true);
        stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(boundData));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        player.displayClientMessage(Component.literal("核心物品已绑定此机器世界。请带回主世界放置；放置后会接管此世界及其库存。"), true);
    }
    @SubscribeEvent public static void constrainRoomPlayers(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        repairOldCoreItems(player);
        if (IFMachineSpace.isFactoryDimension(player.level())) IFMachineSpace.constrainPlayer(player);
    }
    private static void repairOldCoreItems(ServerPlayer player) {
        IFMachineData data = IFMachineData.get(player.getServer());
        boolean repaired = repairCoreItems(player.getInventory().items, data)
                | repairCoreItems(player.getInventory().armor, data)
                | repairCoreItems(player.getInventory().offhand, data);
        if (repaired) player.getInventory().setChanged();
    }
    private static boolean repairCoreItems(Iterable<ItemStack> stacks, IFMachineData data) {
        boolean repaired = false;
        for (ItemStack stack : stacks) {
            boolean integrationCore = stack.is(dev.integratedfactory.registry.IFBlocks.INTEGRATION_UNIT.get().asItem());
            boolean simpleCore = stack.is(dev.integratedfactory.registry.IFBlocks.SIMPLE_INTEGRATION_UNIT.get().asItem());
            if (!integrationCore && !simpleCore) continue;
            CustomData blockData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
            if (blockData == null) continue;
            net.minecraft.nbt.CompoundTag tag = blockData.copyTag();
            if (!tag.hasUUID("node_id")) continue;
            if (data.wasDeleted(tag.getUUID("node_id"))) {
                // A bound item whose node was deleted is safe to reuse as a
                // fresh core. Remove stale binding/glint before placement.
                stack.remove(DataComponents.BLOCK_ENTITY_DATA);
                stack.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
                repaired = true;
                continue;
            }
            if (!tag.contains("id")) {
                tag.putString("id", integrationCore ? "integrated_factory:structure" : "integrated_factory:simple_unit");
                stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(tag));
                repaired = true;
            }
            if (!Boolean.TRUE.equals(stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE))) {
                stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                repaired = true;
            }
        }
        return repaired;
    }
    @SubscribeEvent public static void protectPlacement(BlockEvent.EntityPlaceEvent event) {
        boolean integrationCore = event.getPlacedBlock().is(dev.integratedfactory.registry.IFBlocks.INTEGRATION_UNIT.get());
        boolean simpleCore = event.getPlacedBlock().is(dev.integratedfactory.registry.IFBlocks.SIMPLE_INTEGRATION_UNIT.get());
        boolean extension = event.getPlacedBlock().is(dev.integratedfactory.registry.IFBlocks.EXTENSION_INTERFACE.get());
        boolean inputPort = event.getPlacedBlock().is(dev.integratedfactory.registry.IFBlocks.INPUT_PORT.get());
        boolean outputPort = event.getPlacedBlock().is(dev.integratedfactory.registry.IFBlocks.OUTPUT_PORT.get());
        if ((extension || inputPort || outputPort) && event.getEntity() instanceof ServerPlayer player
                && dev.integratedfactory.blockentity.IFStructureBlockEntity.wouldConnectMultipleStructures(event.getLevel(), event.getPos())) {
            event.setCanceled(true);
            player.displayClientMessage(Component.literal("端口或拓展接口不能连接两个已有的机器结构。"), true);
            return;
        }
        if (integrationCore || simpleCore) {
            // Create's Schematicannon and other non-player placement paths do
            // not have a ServerPlayer source. Reject them explicitly instead
            // of allowing a copied block-entity NBT to rebind a node.
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                event.setCanceled(true);
                return;
            }
            // Core conflict checks run before placement in
            // protectCorePlacementAttempt and IFKineticCoreBlock. Cancelling
            // EntityPlaceEvent here is too late: vanilla has already changed
            // the world and decremented the stack, which can make the item
            // appear swallowed until a relog.
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!IFMachineSpace.isFactoryDimension(player.level())) {
            if (event.getLevel().getBlockEntity(event.getPos()) instanceof dev.integratedfactory.blockentity.IFStructureBlockEntity core
                    && integrationCore) { core.setInitialOwner(player.getUUID()); core.markPlayerPlacement(); }
            if (event.getLevel().getBlockEntity(event.getPos()) instanceof dev.integratedfactory.blockentity.IFSimpleUnitBlockEntity simple
                    && simpleCore) { simple.setInitialOwner(player.getUUID()); simple.markPlayerPlacement(); }
            return;
        }
        if (integrationCore || simpleCore) {
            event.setCanceled(true);
            player.displayClientMessage(Component.literal("机器空间内不能创建新的集成节点。"), true);
            return;
        }
        if (!isCurrentNodeRoom(player, event.getPos())) event.setCanceled(true);
    }

    /**
     * A schematicannon and other Create printers can place blocks without a
     * player-backed placement event.  Keep the normal event protection as a
     * second layer for fake/non-player placement paths that do emit an event:
     * only the registered 10x10x10 build volume is writable in the factory
     * dimension.  Room floors, barriers and room creation use direct server
     * placement and are therefore unaffected by this event.
     */
    @SubscribeEvent public static void protectFactoryRoomPlacement(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof net.minecraft.world.level.Level level)
                || !IFMachineSpace.isFactoryDimension(level)) return;
        if (!IFMachineSpace.isInsideAnyRoom(level, event.getPos())) {
            event.setCanceled(true);
            if (event.getEntity() instanceof ServerPlayer player)
                player.displayClientMessage(Component.literal("机器空间外禁止放置方块。"), true);
        }
    }

    /**
     * Create's schematic cannon ultimately calls Level#setBlock from a
     * LaunchedItem, bypassing EntityPlaceEvent.  Validate the already flying
     * items immediately before Create updates them; checking the printer's
     * cursor itself would reject harmless air/floor/deferred scan targets and
     * prevent otherwise valid blueprints from printing.  This leaves a cannon
     * fully usable inside a room, but it cannot modify the barrier or any
     * position outside the room's 10x10x10 build volume.
     */
    @SubscribeEvent public static void guardFactorySchematicCannons(ServerTickEvent.Pre event) {
        ServerLevel room = event.getServer().getLevel(IFMachineSpace.DIMENSION);
        if (room == null) return;

        var data = IFMachineData.get(event.getServer());
        Set<Long> scannedChunks = new HashSet<>();
        for (IFMachineData.Node node : data.nodes().values()) {
            if (!node.roomReady) continue;
            // Current rooms fit one chunk, but inspect the full historical
            // room footprint as well so pre-alignment rooms cannot bypass the
            // same guard after an upgrade.
            int minChunkX = Math.floorDiv(node.roomX - 1, 16);
            int maxChunkX = Math.floorDiv(node.roomX + 10, 16);
            int minChunkZ = Math.floorDiv(node.roomZ - 1, 16);
            int maxChunkZ = Math.floorDiv(node.roomZ + 10, 16);
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    net.minecraft.world.level.ChunkPos chunkPos = new net.minecraft.world.level.ChunkPos(chunkX, chunkZ);
                    if (!scannedChunks.add(chunkPos.toLong())) continue;
                    LevelChunk chunk = room.getChunkSource().getChunkNow(chunkX, chunkZ);
                    if (chunk == null) continue;
                    for (BlockEntity blockEntity : new java.util.ArrayList<>(chunk.getBlockEntities().values())) {
                        if (blockEntity instanceof SchematicannonBlockEntity cannon)
                            guardSchematicannon(room, cannon);
                    }
                }
            }
        }
    }

    private static void guardSchematicannon(ServerLevel room, SchematicannonBlockEntity cannon) {
        IFMachineData.Node roomNode = IFMachineSpace.nodeAtRoom(room, cannon.getBlockPos());
        // The cannon itself may sit on the room's top layer or on an edge
        // support block. If it is not inside a registered room, derive the
        // room from a currently flying target instead; only the destination
        // is security-critical for this guard.
        if (roomNode == null) {
            for (LaunchedItem launched : cannon.flyingBlocks) {
                if (launched.target == null) continue;
                IFMachineData.Node candidate = IFMachineSpace.nodeAtRoom(room, launched.target);
                if (candidate != null) { roomNode = candidate; break; }
            }
        }
        boolean blocked = false;

        // A target may have been launched on the previous tick.  Remove it
        // before LaunchedItem.update() can invoke BlockHelper.placeSchematicBlock.
        // Return the consumed stack at the cannon so rejecting an out-of-bounds
        // shot does not silently delete the material.
        Iterator<LaunchedItem> iterator = cannon.flyingBlocks.iterator();
        while (iterator.hasNext()) {
            LaunchedItem launched = iterator.next();
            if (roomNode != null && launched.target != null
                    && IFMachineSpace.isInsideRoom(launched.target, roomNode)) continue;
            iterator.remove();
            if (launched.stack != null && !launched.stack.isEmpty())
                net.minecraft.world.level.block.Block.popResource(room, cannon.getBlockPos(), launched.stack.copy());
            blocked = true;
        }

        if (blocked) cannon.setChanged();
    }

    @SubscribeEvent public static void protectVanillaPiston(PistonEvent.Pre event) {
        var resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) return;
        for (var pos : resolver.getToPush()) {
            if (event.getLevel().getBlockState(pos).is(dev.integratedfactory.registry.IFBlocks.INTEGRATION_UNIT.get())
                    || event.getLevel().getBlockState(pos).is(dev.integratedfactory.registry.IFBlocks.SIMPLE_INTEGRATION_UNIT.get())) {
                event.setCanceled(true);
                return;
            }
        }
    }
    private static boolean isCurrentNodeRoom(ServerPlayer player, net.minecraft.core.BlockPos pos) {
        IFMachineData.Node node = currentNode(player);
        return node != null && IFMachineSpace.isInsideRoom(pos, node) && IFNodeMenu.canAccess(node, player);
    }
    private static IFMachineData.Node currentNode(ServerPlayer player) {
        var tag = player.getPersistentData().getCompound("integrated_factory_return");
        if (!tag.hasUUID("node_id")) return null;
        return IFMachineData.get(player.getServer()).get(tag.getUUID("node_id"));
    }
    private static IFMachineData.Node findNode(ServerPlayer player, String idText) {
        try {
            IFMachineData.Node node = IFMachineData.get(player.getServer()).get(java.util.UUID.fromString(idText));
            return node != null && IFNodeMenu.canAccess(node, player) ? node : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
    private static Component action(String label, String command, ClickEvent.Action action, ChatFormatting color) {
        return Component.literal(label).withStyle(style -> style.withColor(color).withUnderlined(true)
                .withClickEvent(new ClickEvent(action, command)));
    }
    /** Displays the same clickable node row used by /factory worlds. */
    public static void showNodeWorldRow(ServerPlayer player, IFMachineData.Node node) {
        if (node == null) return;
        if (!IFNodeMenu.canAccess(node, player)) {
            player.displayClientMessage(Component.literal("你没有此工厂节点的访问权限。"), true);
            return;
        }
        String prefix = "机器世界：";
        if (player.getUUID().equals(node.owner)) {
            var owned = IFMachineData.get(player.getServer()).nodes().values().stream()
                    .filter(candidate -> player.getUUID().equals(candidate.owner))
                    .sorted(java.util.Comparator.comparing(candidate -> candidate.id.toString())).toList();
            int index = owned.indexOf(node);
            if (index >= 0) prefix = (index + 1) + ". ";
        }
        player.displayClientMessage(worldRow(node, prefix), false);
    }
    private static Component worldRow(IFMachineData.Node node, String prefix) {
        String note = node.note.isBlank() ? "（无备注）" : node.note;
        String id = node.id.toString();
        return Component.literal(prefix + node.id.toString().substring(0, 8)
                        + " | " + node.status + " | " + note + " ")
                .append(action("[进入]", "/factory enter " + id, ClickEvent.Action.RUN_COMMAND, ChatFormatting.GREEN))
                .append(Component.literal(" "))
                .append(action("[删除]", "/factory delete " + id, ClickEvent.Action.RUN_COMMAND, ChatFormatting.RED))
                .append(Component.literal(" "))
                .append(action("[备注]", "/factory note " + id + " ", ClickEvent.Action.SUGGEST_COMMAND, ChatFormatting.YELLOW));
    }
    private static int requestDelete(ServerPlayer player, String idText) {
        IFMachineData.Node node = findNode(player, idText);
        if (node == null || !player.getUUID().equals(node.owner)) {
            player.displayClientMessage(Component.literal("只有该机器世界的创建者可以删除它。"), false);
            return 0;
        }
        Component prompt = Component.literal("将删除节点数据与库存，内部方块会保留。确认？ ")
                .append(action("[确认删除]", "/factory delete " + node.id + " confirm", ClickEvent.Action.RUN_COMMAND, ChatFormatting.RED))
                .append(Component.literal(" "))
                .append(action("[取消]", "/factory worlds", ClickEvent.Action.RUN_COMMAND, ChatFormatting.GRAY));
        player.displayClientMessage(prompt, false);
        return 1;
    }
    private static int deleteNode(ServerPlayer player, String idText) {
        IFMachineData.Node node = findNode(player, idText);
        if (node == null || !player.getUUID().equals(node.owner)) {
            player.displayClientMessage(Component.literal("只有该机器世界的创建者可以删除它。"), false);
            return 0;
        }
        IFMachineSpace.deleteNode(player.getServer(), node);
        player.displayClientMessage(Component.literal("机器世界已删除；内部方块未清理，强加载已释放。"), false);
        return 1;
    }
    private IFEvents() {}
}
