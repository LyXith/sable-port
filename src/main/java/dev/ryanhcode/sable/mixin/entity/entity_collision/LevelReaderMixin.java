package dev.ryanhcode.sable.mixin.entity.entity_collision;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.blockscan.BlockMatcher;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelReader.class)
public interface LevelReaderMixin {
    @Shadow
    BlockMatcher findBlocksIn(final AABB box);

    @Inject(method = "containsAnyLiquid", at = @At("HEAD"), cancellable = true)
    default void containsAnyLiquid(AABB box, CallbackInfoReturnable<Boolean> cir) {
        if (this instanceof Level) {
            final BoundingBox3d globalBounds = new BoundingBox3d(box);
            final BoundingBox3d localBounds = new BoundingBox3d();

            for (final SubLevel subLevel : Sable.HELPER.getAllIntersecting((Level) this, globalBounds)) {
                globalBounds.transformInverse(subLevel.lastPose(), localBounds);
                final boolean containsLiquid = this
                        .findBlocksIn(localBounds.toMojang())
                        .filterState(state -> !state.getFluidState().isEmpty())
                        .anyMatched();
                if (containsLiquid) cir.setReturnValue(true);
            }
        }
    }
}
