package dev.ryanhcode.sable.mixin.block_decal_render;

import dev.ryanhcode.sable.Sable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {

    @Shadow
    @Nullable
    private ClientLevel level;

    @Redirect(method = "extractBlockDestroyAnimation", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;distToCenterSqr(DDD)D"))
    private double sable$blockDamageDistance(final BlockPos pos, final double cameraX, final double cameraY, final double cameraZ) {
        return Sable.HELPER.distanceSquaredWithSubLevels(this.level, Vec3.atCenterOf(pos), cameraX, cameraY, cameraZ);
    }
}
