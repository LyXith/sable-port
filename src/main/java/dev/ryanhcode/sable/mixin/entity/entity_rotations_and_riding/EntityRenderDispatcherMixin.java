package dev.ryanhcode.sable.mixin.entity.entity_rotations_and_riding;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinhelpers.camera.camera_rotation.EntitySubLevelRotationHelper;
import dev.ryanhcode.sable.mixinterface.entity.entity_rotations_and_riding.EntityTransformationExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Rotates entity rendering to match the sub-level's rotation
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {

    @Unique
    private final Map<EntityRenderState, SableRenderData> sable$renderData = new IdentityHashMap<>();
    @Unique
    private boolean sable$rotated = false;
    @Unique
    private Quaternionf sable$cameraOrientation;

    @Inject(method = "extractEntity", at = @At("RETURN"))
    private <E extends Entity> void sable$captureEntity(final E entity, final float partialTicks, final CallbackInfoReturnable<EntityRenderState> cir) {
        final EntityRenderState renderState = cir.getReturnValue();
        final ClientSubLevel clientSubLevel = Sable.HELPER.getContainingClient(entity);
        final EntityTransformationExtension transformation = (EntityTransformationExtension) renderState;
        final Quaterniond orientation = EntitySubLevelRotationHelper.getEntityOrientation(
                entity,
                subLevel -> ((ClientSubLevel) subLevel).renderPose(partialTicks),
                partialTicks,
                EntitySubLevelRotationHelper.Type.ENTITY
        );

        if (clientSubLevel != null) {
            final Pose3dc pose = clientSubLevel.renderPose(partialTicks);
            final Vector3d pos = new Vector3d(
                    Mth.lerp(partialTicks, entity.xo, entity.getX()),
                    Mth.lerp(partialTicks, entity.yo, entity.getY()),
                    Mth.lerp(partialTicks, entity.zo, entity.getZ())
            );

            pose.transformPosition(pos);
            renderState.x = pos.x;
            renderState.y = pos.y;
            renderState.z = pos.z;

            transformation.sable$setSubLevelOrientation(new Quaternionf(pose.orientation()));
            transformation.sable$setSubLevelScale(new Vector3f(pose.scale()));
            transformation.sable$setSubLevelPivot(new Vector3f(0, 0, 0));
        } else if (orientation != null) {
            transformation.sable$setSubLevelOrientation(new Quaternionf(orientation));
            transformation.sable$setSubLevelScale(new Vector3f(1, 1, 1));
            transformation.sable$setSubLevelPivot(new Vector3f(entity.getEyePosition(partialTicks).subtract(entity.position()).toVector3f()));
        } else {
            transformation.sable$setSubLevelOrientation(new Quaternionf());
            transformation.sable$setSubLevelScale(new Vector3f(1, 1, 1));
            transformation.sable$setSubLevelPivot(new Vector3f(0, 0, 0));
        }

        this.sable$adjustLeashes(entity, renderState);
        this.sable$renderData.put(renderState, new SableRenderData(entity, partialTicks));
    }

    @Inject(method = "submit", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V", shift = At.Shift.AFTER, ordinal = 0))
    private <S extends EntityRenderState> void sable$rotateEntity(S renderState, net.minecraft.client.renderer.state.level.CameraRenderState camera, double x, double y, double z, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
        final SableRenderData renderData = this.sable$renderData.remove(renderState);
        if (renderData == null) {
            return;
        }

        final Entity entity = renderData.entity();
        if (!EntitySubLevelUtil.shouldKick(entity)) {
            return;
        }

        final float partialTick = renderData.partialTick();
        final Quaterniond orientation = EntitySubLevelRotationHelper.getEntityOrientation(entity, level -> ((ClientSubLevel) level).renderPose(), partialTick, EntitySubLevelRotationHelper.Type.ENTITY);

        if (orientation == null) {
            return;
        }

        // The model rotation is handled by entity_rotations_and_riding.LevelRendererMixin,
        // which rotates the pose stack around the entity's (global) render position. Applying
        // the same sub-level orientation here as well rotated every kicked entity twice as soon
        // as the sub-level had any orientation (retained entities skip this method, which is why
        // only arrows / mobs looked wrong). Only the billboard/camera correction is kept here.
        this.sable$cameraOrientation = new Quaternionf(camera.orientation);
        camera.orientation = new Quaternionf(orientation).conjugate().mul(camera.orientation);
        this.sable$rotated = true;
    }

    @Inject(method = "submit", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V", shift = At.Shift.BEFORE))
    private <S extends EntityRenderState> void sable$popPose(S renderState, net.minecraft.client.renderer.state.level.CameraRenderState camera, double x, double y, double z, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
        if (this.sable$rotated) {
            camera.orientation = this.sable$cameraOrientation;
            this.sable$cameraOrientation = null;
            this.sable$rotated = false;
        }
    }

    @Unique
    private void sable$adjustLeashes(final Entity entity, final EntityRenderState renderState) {
        if (renderState.leashStates == null) {
            return;
        }

        final SubLevel entitySubLevel = Sable.HELPER.getContaining(entity);
        for (final EntityRenderState.LeashState leashState : renderState.leashStates) {
            final Vector3d end = new Vector3d(leashState.end.x, leashState.end.y, leashState.end.z);
            final SubLevel holderSubLevel = Sable.HELPER.getContaining(entity.level(), end);
            if (holderSubLevel != null) {
                holderSubLevel.logicalPose().transformPosition(end);
            }
            if (entitySubLevel != null) {
                entitySubLevel.logicalPose().transformPositionInverse(end);
            }
            leashState.end = new Vec3(end.x, end.y, end.z);
        }
    }

    @Unique
    private record SableRenderData(Entity entity, float partialTick) {
    }
}
