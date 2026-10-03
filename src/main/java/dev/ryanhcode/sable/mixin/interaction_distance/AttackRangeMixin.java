package dev.ryanhcode.sable.mixin.interaction_distance;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AttackRange.class)
public abstract class AttackRangeMixin {
    @Shadow
    public abstract float effectiveMaxRange(Entity entity);

    @Shadow
    public abstract float effectiveMinRange(Entity entity);

    @Shadow
    public abstract float hitboxMargin();

    @Inject(method = "isInRange(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/phys/Vec3;)Z", at = @At("HEAD"), cancellable = true)
    private void sable$isInRangeVec(LivingEntity attacker, Vec3 location, CallbackInfoReturnable<Boolean> cir) {
        final SubLevel subLevel = Sable.HELPER.getContaining(attacker.level(), location);
        if (subLevel == null) return;
        final double distance = Math.sqrt(location.distanceToSqr(subLevel.logicalPose().transformPositionInverse(attacker.getEyePosition())));
        final double minRange = this.effectiveMinRange(attacker) - this.hitboxMargin();
        final double maxRange = this.effectiveMaxRange(attacker) + this.hitboxMargin();
        cir.setReturnValue(distance >= minRange && distance <= maxRange);
    }

    @Inject(method = "isInRange(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/phys/AABB;D)Z", at = @At("HEAD"), cancellable = true)
    private void sable$isInRangeBox(LivingEntity attacker, AABB boundingBox, double extraBuffer, CallbackInfoReturnable<Boolean> cir) {
        final SubLevel subLevel = Sable.HELPER.getContaining(attacker.level(), boundingBox.getBottomCenter());
        if (subLevel == null) return;
        final double distance = Math.sqrt(boundingBox.distanceToSqr(subLevel.logicalPose().transformPositionInverse(attacker.getEyePosition())));
        final double minRange = this.effectiveMinRange(attacker) - this.hitboxMargin();
        final double maxRange = this.effectiveMaxRange(attacker) + this.hitboxMargin();
        cir.setReturnValue(distance >= minRange && distance <= maxRange);
    }
}
