package dev.ryanhcode.sable.mixin.entity.entity_swimming;

import dev.ryanhcode.sable.mixinhelpers.entity.entity_swimming.SubLevelFluidHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes {@code LivingEntity#travel} pick the fluid branch for water that lives on a sub-level.
 *
 * <p>{@code travel} chooses between land and fluid movement with
 * {@code shouldTravelInFluid(level.getFluidState(blockPosition()))}. An entity on a sub-level is at
 * global coordinates, where the world is air, so it would walk on sub-level water instead of
 * swimming through it.
 */
@Mixin(LivingEntity.class)
public class LivingEntityMixin {

    @Redirect(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"))
    private FluidState sable$subLevelFluidAtBlockPos(final Level level, final BlockPos pos) {
        return SubLevelFluidHelper.getFluidStateAt((LivingEntity) (Object) this, pos);
    }
}
