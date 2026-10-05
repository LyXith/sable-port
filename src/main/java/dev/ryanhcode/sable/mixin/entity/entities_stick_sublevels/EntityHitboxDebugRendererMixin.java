package dev.ryanhcode.sable.mixin.entity.entities_stick_sublevels;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinhelpers.camera.camera_rotation.EntitySubLevelRotationHelper;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer;
import net.minecraft.gizmos.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jspecify.annotations.NonNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityHitboxDebugRenderer.class)
public class EntityHitboxDebugRendererMixin {
    @WrapOperation(method = "showHitboxes", at = @At(value = "INVOKE", target = "Lnet/minecraft/gizmos/Gizmos;cuboid(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/gizmos/GizmoStyle;)Lnet/minecraft/gizmos/GizmoProperties;", ordinal = 0))
    private GizmoProperties sable$rotateSubLevelHitBox(
            final AABB aabb,
            final GizmoStyle style,
            final Operation<GizmoProperties> original,
            @Local(argsOnly = true, name = "entity") final Entity entity,
            @Local(argsOnly = true, name = "partialTicks") final float partialTicks
    ) {
        return this.sable$drawRotatedBox(aabb, style, original, entity, partialTicks);
    }

    @WrapOperation(method = "showHitboxes", at = @At(value = "INVOKE", target = "Lnet/minecraft/gizmos/Gizmos;cuboid(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/gizmos/GizmoStyle;)Lnet/minecraft/gizmos/GizmoProperties;", ordinal = 1))
    private GizmoProperties sable$rotateSubLevelRidingStrap(
            AABB aabb,
            GizmoStyle style,
            Operation<GizmoProperties> original,
            @Local(argsOnly = true, name = "entity") Entity entity,
            @Local(argsOnly = true, name = "partialTicks") float partialTicks
    ) {
        final Entity vehicle = entity.getVehicle();
        if (vehicle == null) return original.call(aabb, style);
        final ClientSubLevel vehicleSubLevel = Sable.HELPER.getContainingClient(vehicle);
        if (vehicleSubLevel == null) return original.call(aabb, style);

        final Vec3[] corners = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            final Vector3d pos = new Vector3d(
                    this.sable$cornerX(aabb, i),
                    this.sable$cornerY(aabb, i),
                    this.sable$cornerZ(aabb, i)
            );
            vehicleSubLevel.renderPose(partialTicks).transformPosition(pos);
            corners[i] = new Vec3(
                    pos.x,
                    pos.y,
                    pos.z
            );
        }

        return Gizmos.addGizmo(new BoxGizmo(corners, style));
    }

    @WrapOperation(method = "showHitboxes", at = @At(value = "INVOKE", target = "Lnet/minecraft/gizmos/Gizmos;cuboid(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/gizmos/GizmoStyle;)Lnet/minecraft/gizmos/GizmoProperties;", ordinal = 2))
    private GizmoProperties sable$rotateSubLevelEyeBox(
            final AABB aabb,
            final GizmoStyle style,
            final Operation<GizmoProperties> original,
            @Local(argsOnly = true, name = "entity") final Entity entity,
            @Local(argsOnly = true, name = "partialTicks") final float partialTicks
    ) {
        return this.sable$drawRotatedBox(aabb, style, original, entity, partialTicks);
    }

    @Unique
    private GizmoProperties sable$drawRotatedBox(
            final AABB aabb,
            final GizmoStyle style,
            final Operation<GizmoProperties> original,
            final Entity entity,
            final float partialTicks
    ) {
        final Vec3[] corners = new Vec3[8];
        final ClientSubLevel containingSubLevel = Sable.HELPER.getContainingClient(entity);
        final Quaterniond orientation = EntitySubLevelRotationHelper.getEntityOrientation(
                entity,
                level -> ((ClientSubLevel) level).renderPose(partialTicks),
                partialTicks,
                EntitySubLevelRotationHelper.Type.ENTITY
        );

        if (containingSubLevel != null) {
            for (int i = 0; i < 8; i++) {
                final Vector3d pos = new Vector3d(
                        this.sable$cornerX(aabb, i),
                        this.sable$cornerY(aabb, i),
                        this.sable$cornerZ(aabb, i)
                );
                containingSubLevel.renderPose(partialTicks).transformPosition(pos);
                corners[i] = new Vec3(
                        pos.x,
                        pos.y,
                        pos.z
                );
            }
        } else if (orientation != null) {
            for (int i = 0; i < 8; i++) {
                final Vector3d pos = new Vector3d(
                        this.sable$cornerX(aabb, i) - entity.getEyePosition().x,
                        this.sable$cornerY(aabb, i) - entity.getEyePosition().y,
                        this.sable$cornerZ(aabb, i) - entity.getEyePosition().z
                );
                orientation.transform(pos);
                corners[i] = new Vec3(
                        pos.x + entity.getEyePosition().x,
                        pos.y + entity.getEyePosition().y,
                        pos.z + entity.getEyePosition().z
                );
            }
        } else {
            return original.call(aabb, style);
        }

        return Gizmos.addGizmo(new BoxGizmo(corners, style));
    }

    @Unique
    private double sable$cornerX(final AABB aabb, final int corner) {
        return (corner & 4) == 0 ? aabb.minX : aabb.maxX;
    }

    @Unique
    private double sable$cornerY(final AABB aabb, final int corner) {
        return (corner & 2) == 0 ? aabb.minY : aabb.maxY;
    }

    @Unique
    private double sable$cornerZ(final AABB aabb, final int corner) {
        return (corner & 1) == 0 ? aabb.minZ : aabb.maxZ;
    }

    record BoxGizmo(Vec3[] corners, GizmoStyle style) implements Gizmo {
        @Override
        public void emit(@NonNull GizmoPrimitives primitives, float alphaMultiplier) {
            if (this.style.hasFill()) {
                final int color = this.style.multipliedFill(alphaMultiplier);
                primitives.addQuad(this.corners[4], this.corners[6], this.corners[7], this.corners[5], color);
                primitives.addQuad(this.corners[0], this.corners[1], this.corners[3], this.corners[2], color);
                primitives.addQuad(this.corners[0], this.corners[2], this.corners[6], this.corners[4], color);
                primitives.addQuad(this.corners[1], this.corners[5], this.corners[7], this.corners[3], color);
                primitives.addQuad(this.corners[0], this.corners[4], this.corners[5], this.corners[1], color);
                primitives.addQuad(this.corners[2], this.corners[3], this.corners[7], this.corners[6], color);
            }

            if (this.style.hasStroke()) {
                final int color = this.style.multipliedStroke(alphaMultiplier);
                primitives.addLine(this.corners[0], this.corners[4], color, this.style.strokeWidth());
                primitives.addLine(this.corners[0], this.corners[2], color, this.style.strokeWidth());
                primitives.addLine(this.corners[0], this.corners[1], color, this.style.strokeWidth());
                primitives.addLine(this.corners[4], this.corners[6], color, this.style.strokeWidth());
                primitives.addLine(this.corners[6], this.corners[2], color, this.style.strokeWidth());
                primitives.addLine(this.corners[2], this.corners[3], color, this.style.strokeWidth());
                primitives.addLine(this.corners[3], this.corners[1], color, this.style.strokeWidth());
                primitives.addLine(this.corners[1], this.corners[5], color, this.style.strokeWidth());
                primitives.addLine(this.corners[5], this.corners[4], color, this.style.strokeWidth());
                primitives.addLine(this.corners[3], this.corners[7], color, this.style.strokeWidth());
                primitives.addLine(this.corners[5], this.corners[7], color, this.style.strokeWidth());
                primitives.addLine(this.corners[6], this.corners[7], color, this.style.strokeWidth());
            }
        }
    }
}
