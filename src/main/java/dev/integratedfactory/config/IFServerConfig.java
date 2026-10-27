package dev.integratedfactory.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-authoritative limits shared by all factory nodes. */
public final class IFServerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.IntValue MAX_STRUCTURE_BLOCKS;
    public static final ModConfigSpec.DoubleValue MAX_REMOTE_STRESS;
    public static final ModConfigSpec.BooleanValue ROOM_FLIGHT;
    public static final ModConfigSpec.IntValue MAX_FORCE_LOADED_CHUNKS;
    static final ModConfigSpec SPEC;

    static {
        BUILDER.push("factory");
        MAX_STRUCTURE_BLOCKS = BUILDER.comment("Maximum connected structure members per integration core.").defineInRange("maxStructureBlocks", 128, 1, 4096);
        MAX_REMOTE_STRESS = BUILDER.comment("Maximum remote Create stress load allowed per machine node; set to 0 for no limit.").defineInRange("maxRemoteStress", 0.0, 0.0, 1.0E12);
        ROOM_FLIGHT = BUILDER.comment("Allow players to fly while inside a machine room.").define("roomFlight", true);
        MAX_FORCE_LOADED_CHUNKS = BUILDER.comment("Maximum number of machine-space chunks kept block-ticking by this mod; each current room uses one chunk. Set to 0 to disable forced loading.").defineInRange("maxForceLoadedChunks", 256, 0, 4096);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    public static ModConfigSpec spec() { return SPEC; }
    private IFServerConfig() {}
}
