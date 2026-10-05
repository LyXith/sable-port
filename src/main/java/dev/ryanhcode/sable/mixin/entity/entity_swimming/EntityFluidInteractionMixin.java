package dev.ryanhcode.sable.mixin.entity.entity_swimming;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.ryanhcode.sable.mixinhelpers.entity.entity_swimming.SubLevelFluidHelper;
import net.minecraft.core.Holder;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

/**
 * Makes the fluid scan of {@link EntityFluidInteraction} aware of fluids that live on a sub-level.
 *
 * <p>mc26.1 replaced {@code Entity#updateFluidHeightAndDoFluidPushing} with this class; the 1.21.1
 * Sable handled sub-level water by overwriting that method (see
 * {@code entity_swimming/EntityMixin} in the upstream repository). The scan itself is the same idea,
 * but the per fluid state lives in private {@code Tracker}s here, so instead of overwriting the whole
 * scan the sub-level results are kept on the side and merged when they are read.
 *
 * @see SubLevelFluidHelper
 */
@Mixin(EntityFluidInteraction.class)
public class EntityFluidInteractionMixin {

    @Unique
    private final Map<Holder<Fluid>, SubLevelFluidHelper.Found> sable$subLevelFluids = new HashMap<>();

    @Unique
    private final Map<TagKey<Fluid>, SubLevelFluidHelper.Found> sable$subLevelCurrents = new HashMap<>();

    // Gather on HEAD: the results then feed both the read hooks below and the modified return value
    // of `update`, without depending on injector ordering at the return point.
    @Inject(method = "update(Lnet/minecraft/world/entity/Entity;Z)Z", at = @At("HEAD"))
    private void sable$gatherSubLevelFluids(final Entity entity, final boolean ignoreCurrent, final CallbackInfoReturnable<Boolean> cir) {
        SubLevelFluidHelper.gather(entity, ignoreCurrent, this.sable$subLevelFluids, this.sable$subLevelCurrents);
    }

    /**
     * {@code update} returns whether the vanilla scan found any fluid at all. An entity that is only
     * inside sub-level fluid finds none, which would make callers such as {@code ItemEntity} treat it
     * as dry.
     */
    @ModifyReturnValue(method = "update(Lnet/minecraft/world/entity/Entity;Z)Z", at = @At("RETURN"))
    private boolean sable$subLevelFluidFound(final boolean original) {
        return original || !this.sable$subLevelFluids.isEmpty();
    }

    @ModifyReturnValue(method = "getFluidHeight(Lnet/minecraft/tags/TagKey;)D", at = @At("RETURN"))
    private double sable$subLevelFluidHeight(final double original, final TagKey<Fluid> fluid) {
        double height = original;

        for (final Map.Entry<Holder<Fluid>, SubLevelFluidHelper.Found> entry : this.sable$subLevelFluids.entrySet()) {
            if (entry.getKey().is(fluid)) {
                height = Math.max(height, entry.getValue().height);
            }
        }

        return height;
    }

    @ModifyReturnValue(method = "isEyeInFluid(Lnet/minecraft/tags/TagKey;)Z", at = @At("RETURN"))
    private boolean sable$subLevelEyeInFluid(final boolean original, final TagKey<Fluid> fluid) {
        if (original) {
            return true;
        }

        for (final Map.Entry<Holder<Fluid>, SubLevelFluidHelper.Found> entry : this.sable$subLevelFluids.entrySet()) {
            if (entry.getKey().is(fluid) && entry.getValue().eyesInside) {
                return true;
            }
        }

        return false;
    }

    @Inject(method = "applyCurrentTo(Lnet/minecraft/tags/TagKey;Lnet/minecraft/world/entity/Entity;D)V", at = @At("TAIL"))
    private void sable$applySubLevelCurrent(final TagKey<Fluid> fluid, final Entity entity, final double scale, final CallbackInfo ci) {
        final SubLevelFluidHelper.Found found = this.sable$subLevelCurrents.get(fluid);

        if (found != null) {
            SubLevelFluidHelper.applyCurrent(entity, found, scale);
        }
    }
}
