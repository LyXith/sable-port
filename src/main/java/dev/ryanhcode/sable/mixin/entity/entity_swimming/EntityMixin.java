package dev.ryanhcode.sable.mixin.entity.entity_swimming;

import dev.ryanhcode.sable.mixinhelpers.entity.entity_swimming.SubLevelFluidHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes the fluid lookups that decide whether an entity is swimming sub-level aware.
 *
 * <p>{@code Entity#updateSwimming} is the gate that starts swimming and it still reads
 * {@code level.getFluidState(blockPosition())} directly, i.e. the world at the entity's (global)
 * position. For an entity standing in water that lives on a sub-level that position is air, so the
 * entity can never start swimming there.
 */
@Mixin(Entity.class)
public class EntityMixin {

    @Redirect(method = "updateSwimming", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"))
    private FluidState sable$subLevelFluidAtBlockPos(final Level level, final BlockPos pos) {
        return SubLevelFluidHelper.getFluidStateAt((Entity) (Object) this, pos);
    }
}
