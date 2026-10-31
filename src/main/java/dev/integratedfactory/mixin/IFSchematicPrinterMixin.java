package dev.integratedfactory.mixin;

import com.simibubi.create.content.schematics.SchematicPrinter;
import dev.integratedfactory.space.IFMachineSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the machine-room boundary before SchematicannonBlockEntity can
 * inspect the current requirement and extract it from attached inventories.
 * This also covers entity targets, for which Create's cannon bypasses its
 * block-only shouldPlace predicate.
 */
@Mixin(SchematicPrinter.class)
public abstract class IFSchematicPrinterMixin {
    // SchematicPrinter also exposes shouldPlaceCurrent(Level).  The explicit
    // descriptor is required so Mixin does not bind this handler to that
    // overload at runtime.
    @Inject(method = "shouldPlaceCurrent(Lnet/minecraft/world/level/Level;Lcom/simibubi/create/content/schematics/SchematicPrinter$PlacementPredicate;)Z", at = @At("HEAD"), cancellable = true)
    private void integratedFactory$restrictRoomTarget(Level level,
                                                       SchematicPrinter.PlacementPredicate predicate,
                                                       CallbackInfoReturnable<Boolean> callback) {
        if (level == null || level.isClientSide || !IFMachineSpace.isFactoryDimension(level)) return;
        SchematicPrinter printer = (SchematicPrinter) (Object) this;
        BlockPos target = printer.getCurrentTarget();
        if (target != null && !IFMachineSpace.isInsideAnyRoom(level, target))
            callback.setReturnValue(false);
    }
}
