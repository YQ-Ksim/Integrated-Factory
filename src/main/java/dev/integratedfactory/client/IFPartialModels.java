package dev.integratedfactory.client;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

/** Flywheel partials owned by Integrated Factory. */
public final class IFPartialModels {
    /**
     * A full shaft using Create's official axis/axis_top textures. The model
     * extends 0.5 pixels past both block sides so its end faces do not z-fight
     * with the clutch/gearbox body.
     */
    public static final PartialModel CORE_SHAFT = PartialModel.of(
            ResourceLocation.fromNamespaceAndPath("integrated_factory", "block/core_shaft"));

    private IFPartialModels() {}
}
