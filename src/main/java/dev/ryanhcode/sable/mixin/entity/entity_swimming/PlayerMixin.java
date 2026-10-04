package dev.ryanhcode.sable.mixin.entity.entity_swimming;

import dev.ryanhcode.sable.mixinhelpers.entity.entity_swimming.SubLevelFluidHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Player.class)
public abstract class PlayerMixin {
    @Redirect(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"))
    private FluidState sable$subLevelFluidAtBlockPos(Level instance, BlockPos pos) {
        return SubLevelFluidHelper.getFluidStateAt((Player) (Object) this, pos);
    }
}
