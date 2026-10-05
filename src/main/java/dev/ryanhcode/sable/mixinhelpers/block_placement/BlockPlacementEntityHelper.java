package dev.ryanhcode.sable.mixinhelpers.block_placement;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.math.LevelReusedVectors;
import dev.ryanhcode.sable.api.math.OrientedBoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.mixinhelpers.camera.camera_rotation.EntitySubLevelRotationHelper;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LevelExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/**
 * Rotation aware version of the "is this block placement blocked by an entity" test used by
 * {@code EntityGetter#isUnobstructed(Entity, VoxelShape)} (and therefore by {@code BlockItem}
 * placement, mob spawn obstruction checks and the worldgen unobstructed predicate).
 */
public final class BlockPlacementEntityHelper {

    private static final Quaterniondc IDENTITY = new Quaterniond();

    private BlockPlacementEntityHelper() {
    }

    /**
     * Full "does this entity block a placement of {@code shape}" test, including the cross frame
     * case where the entity is in the global frame but the block is placed inside a sub-level.
     */
    public static boolean obstructs(final VoxelShape shape, final Entity entity) {
        if (intersects(shape, entity)) {
            return true;
        }

        // Map the entity box into nearby sub-levels and test there: in that frame the box ends up
        // axis aligned (its orientation is the sub-level's own), so the plain AABB test is exact.
        final AABB entityBounds = entity.getBoundingBox();
        final BoundingBox3d queryBounds = new BoundingBox3d(entityBounds);
        queryBounds.expand(1.5, queryBounds);

        for (final SubLevel subLevel : Sable.HELPER.getAllIntersecting(entity.level(), queryBounds)) {
            final BoundingBox3d localBounds = new BoundingBox3d(entityBounds);
            localBounds.transformInverse(subLevel.logicalPose(), localBounds);
            localBounds.expand(-0.75 / 16.0, localBounds);

            if (Shapes.joinIsNotEmpty(shape, Shapes.create(localBounds.toMojang()), BooleanOp.AND)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Tests {@code shape} (in the world frame) against the entity's hit box.
     *
     * <p>An entity that is standing on / riding a sub-level is rendered as its axis aligned bounding
     * box rotated around its eyes by the sub-level orientation, so the plain
     * {@code Shapes.joinIsNotEmpty(shape, Shapes.create(entity.getBoundingBox()))} test compares the
     * placement shape against the wrong geometry whenever the sub-level is rotated: it reports
     * "obstructed" for positions the tilted entity does not actually cover.
     *
     * <p>Entities without an orientation keep the vanilla behaviour exactly.
     *
     * @return whether the shape overlaps the entity's (possibly rotated) hit box
     */
    public static boolean intersects(final VoxelShape shape, final Entity entity) {
        final AABB box = entity.getBoundingBox();

        // Same orientation source as the model rotation, the F3+B hitbox outline and the hit
        // detection, so the tested box is the one the player sees.
        final Quaterniondc orientation = EntitySubLevelRotationHelper.getEntityOrientation(
                entity,
                SubLevel::lastPose,
                1.0f,
                EntitySubLevelRotationHelper.Type.ENTITY
        );

        if (orientation == null) {
            return Shapes.joinIsNotEmpty(shape, Shapes.create(box), BooleanOp.AND);
        }

        final LevelReusedVectors sink = ((LevelExtension) entity.level()).sable$getJOMLSink();
        final Vec3 eye = entity.getEyePosition();

        // The box is rotated around the eyes, so its center moves along with it.
        final Vector3d center = new Vector3d(box.getCenter().x, box.getCenter().y, box.getCenter().z)
                .sub(eye.x, eye.y, eye.z)
                .rotate(orientation)
                .add(eye.x, eye.y, eye.z);

        final OrientedBoundingBox3d entityOBB = new OrientedBoundingBox3d(
                center.x, center.y, center.z,
                box.getXsize(), box.getYsize(), box.getZsize(),
                orientation,
                sink
        );

        final OrientedBoundingBox3d shapeOBB = new OrientedBoundingBox3d(sink);
        final Vector3d shapeCenter = new Vector3d();
        final Vector3d shapeDimensions = new Vector3d();

        for (final AABB part : shape.toAabbs()) {
            final Vec3 partCenter = part.getCenter();

            shapeCenter.set(partCenter.x, partCenter.y, partCenter.z);
            shapeDimensions.set(part.getXsize(), part.getYsize(), part.getZsize());
            shapeOBB.set(shapeCenter, shapeDimensions, IDENTITY);

            // `sat` zeroes the destination when the boxes are separated.
            if (OrientedBoundingBox3d.sat(entityOBB, shapeOBB, sink.mtv).lengthSquared() > 0.0) {
                return true;
            }
        }

        return false;
    }
}
