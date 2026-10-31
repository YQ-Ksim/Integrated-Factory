package dev.integratedfactory.mixin;

import dev.integratedfactory.space.IFMachineSpace;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Runs before Create checks materials for a schematicannon target.  This is
 * intentionally earlier than SchematicannonBlockEntity's inventory extraction
 * so out-of-room targets never consume an item and never need a dropped-item
 * refund.
 */
@Mixin(SchematicannonBlockEntity.class)
public abstract class IFSchematicannonMixin {
    @Inject(method = "shouldPlace", at = @At("HEAD"), cancellable = true)
    private void integratedFactory$restrictRoomTarget(BlockPos target,
                                                       BlockState schematicState,
                                                       BlockEntity schematicBlockEntity,
                                                       BlockState currentState,
                                                       BlockState previousState,
                                                       boolean currentBlockIsAir,
                                                       CallbackInfoReturnable<Boolean> callback) {
        SchematicannonBlockEntity cannon = (SchematicannonBlockEntity) (Object) this;
        Level level = cannon.getLevel();
        if (level != null && !level.isClientSide
                && IFMachineSpace.isFactoryDimension(level)
                && !IFMachineSpace.isInsideAnyRoom(level, target)) {
            callback.setReturnValue(false);
        }
    }
}
