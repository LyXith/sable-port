package dev.ryanhcode.sable.mixin.entity.entities_stick_sublevels.player;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin extends Entity {

    @Unique
    private final Vector3d sable$trackedSubLevelPos = new Vector3d();

    public ServerPlayerMixin(final EntityType<?> entityType, final Level level) {
        super(entityType, level);
    }

    @Shadow
    public abstract ServerLevel level();

    @Inject(method = "tick", at = @At("HEAD"))
    public void tick(final CallbackInfo ci) {
        final SubLevel trackingSubLevel = Sable.HELPER.getTrackingSubLevel(this);

        if (trackingSubLevel != null && !trackingSubLevel.isRemoved()) {
            final Vector3d entityCenter = JOMLConversion.getAABBCenter(this.getBoundingBox(), this.sable$trackedSubLevelPos);
            final double entityCenterX = entityCenter.x();
            final double entityCenterY = entityCenter.y();
            final double entityCenterZ = entityCenter.z();

            final Pose3dc pose = trackingSubLevel.logicalPose();
            final Pose3dc lastPose = trackingSubLevel.lastPose();

            final Vector3d inherited = pose.transformPosition(lastPose.transformPositionInverse(entityCenter, entityCenter));

            final Vec3 position = this.position();
            this.setPos(new Vec3(
                    position.x + inherited.x - entityCenterX,
                    position.y + inherited.y - entityCenterY,
                    position.z + inherited.z - entityCenterZ));
        }
    }

    @WrapOperation(method = "setCamera", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z"))
    private boolean sable$fixSpectatorSetCamera(
            final ServerPlayer instance,
            final ServerLevel level,
            final double x,
            final double y,
            final double z,
            final Set<Relative> relatives,
            final float newYRot,
            final float newXRot,
            final boolean resetCamera,
            final Operation<Boolean> original
    ) {
        final SubLevel subLevel = Sable.HELPER.getContaining(level, x, z);
        if (subLevel == null) {
            return original.call(instance, level, x, y, z, relatives, newYRot, newXRot, resetCamera);
        } else {
            final Vector3d pos = new Vector3d(x, y, z);
            subLevel.lastPose().transformPosition(pos);
            return original.call(instance, level, pos.x, pos.y, pos.z, relatives, newYRot, newXRot, resetCamera);
        }
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;absSnapTo(DDDFF)V"))
    private void sable$fixSpectatorTick(
            final ServerPlayer instance,
            final double x,
            final double y,
            final double z,
            final float yRot,
            final float xRot,
            final Operation<Void> original
    ) {
        final SubLevel subLevel = Sable.HELPER.getContaining(instance.level(), x, z);
        if (subLevel == null) {
            original.call(instance, x, y, z, yRot, xRot);
        } else {
            final Vector3d pos = new Vector3d(x, y, z);
            subLevel.lastPose().transformPosition(pos);
            original.call(instance, pos.x, pos.y, pos.z, yRot, xRot);
        }
    }
}
