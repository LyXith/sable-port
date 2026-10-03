package dev.ryanhcode.sable.mixinterface.sublevel_render;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Map;

/**
 * Exposes the sub-level block-entity transform map that lives on
 * {@code LevelRenderer} so that {@code LevelExtractor} can populate it.
 *
 * <p>mc26.3 port: block entities are extracted on {@code LevelExtractor} but
 * submitted on {@code LevelRenderer}. The map used to be a plain
 * {@code @Unique} mixin field which other mixins cannot reference at compile
 * time; this interface bridges the two.
 */
public interface BlockEntityTransformsHolder {

    Map<BlockEntityRenderState, SableBlockEntityTransform> sable$getBlockEntityTransforms();

    record SableBlockEntityTransform(Matrix4f transformation, Vector3d rotationPoint, Quaternionf orientation) {
        public SableBlockEntityTransform(final Matrix4f transformation, final Vector3dc rotationPoint, final Quaternionf orientation) {
            this(new Matrix4f(transformation), new Vector3d(rotationPoint), new Quaternionf(orientation));
        }
    }
}
