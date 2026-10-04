package dev.ryanhcode.sable.mixinhelpers.entity.entity_swimming;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.math.LevelReusedVectors;
import dev.ryanhcode.sable.api.math.OrientedBoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LevelExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Map;
import java.util.Set;

/**
 * Sub-level aware fluid sampling, the 26.x equivalent of the 1.21.1
 * {@code entity_swimming/EntityMixin} that overwrote {@code Entity#updateFluidHeightAndDoFluidPushing}.
 *
 * <p>mc26.1 moved the fluid scan into {@code EntityFluidInteraction#update}, which samples
 * {@code level.getFluidState(...)} over the entity's bounding box in the <b>global</b> frame. Fluids
 * that live on a sub-level are stored at plot coordinates, while a moving entity (which Sable keeps
 * at global coordinates) is elsewhere entirely, so it never sees them: it walks on water, does not
 * drown and is not pushed by the current.
 *
 * <p>This runs a second scan in every intersecting sub-level's local frame, with the entity's box
 * rotated by the same orientation the model / hit box / collision box use, and reports the results
 * so {@code EntityFluidInteraction} can merge them with its own.
 */
public final class SubLevelFluidHelper {

    /** Mirrors {@code Entity#FLUIDS_WITH_CURRENT}. */
    private static final Set<TagKey<Fluid>> FLUIDS_WITH_CURRENT = Set.of(FluidTags.WATER, FluidTags.LAVA);

    /**
     * Accumulation for one fluid over one tick, mirroring
     * {@code EntityFluidInteraction.Tracker} + {@code CurrentAccumulator}.
     */
    public static final class Found {
        public double height;
        public boolean eyesInside;
        public Vec3 current = Vec3.ZERO;
        public int currentBlocks;
    }

    private SubLevelFluidHelper() {
    }

    /**
     * Scans the fluids inside every sub-level the entity's fluid interaction box intersects.
     *
     * @param fluids         receives the height / eye state per fluid holder
     * @param currents       receives the accumulated current per {@link #FLUIDS_WITH_CURRENT} tag
     */
    public static void gather(final Entity entity, final boolean ignoreCurrent,
                              final Map<Holder<Fluid>, Found> fluids,
                              final Map<TagKey<Fluid>, Found> currents) {
        fluids.clear();
        currents.clear();

        final AABB box = entity.getFluidInteractionBox();

        if (box == null) {
            return;
        }

        final Level level = entity.level();
        final LevelReusedVectors sink = ((LevelExtension) level).sable$getJOMLSink();
        final BoundingBox3d globalBounds = new BoundingBox3d(box);
        final BoundingBox3d localBounds = new BoundingBox3d();
        final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        final Vector3d boxCenter = new Vector3d();
        final Vector3d boxSize = new Vector3d();
        final Quaterniond boxOrientation = new Quaterniond();
        final Vec3 eye = entity.getEyePosition();

        for (final SubLevel subLevel : Sable.HELPER.getAllIntersecting(level, globalBounds)) {
            final Pose3dc pose = subLevel.lastPose();
            globalBounds.transformInverse(pose, localBounds);

            // Same orientation the model and hit box use for this entity in that sub-level.
            final Quaterniond localOrientation = pose.orientation().conjugate(boxOrientation);
            localOrientation.rotateY(SubLevelEntityCollision.getHitBoxYaw(pose));

            final OrientedBoundingBox3d entityBox = new OrientedBoundingBox3d(
                    pose.transformPositionInverse(globalBounds.center(boxCenter)),
                    globalBounds.size(boxSize),
                    localOrientation,
                    sink
            );
            final OrientedBoundingBox3d fluidBox = new OrientedBoundingBox3d(
                    new Vector3d(), new Vector3d(1.0, 1.0, 1.0), JOMLConversion.QUAT_IDENTITY, sink);

            final Vec3 localEye = pose.transformPositionInverse(eye);
            final int eyeLocalX = Mth.floor(localEye.x);
            final int eyeLocalZ = Mth.floor(localEye.z);

            final int minX = Mth.floor(localBounds.minX);
            final int maxX = Mth.ceil(localBounds.maxX);
            final int minY = Mth.floor(localBounds.minY);
            final int maxY = Mth.ceil(localBounds.maxY);
            final int minZ = Mth.floor(localBounds.minZ);
            final int maxZ = Mth.ceil(localBounds.maxZ);

            double minYVertex = Double.MAX_VALUE;
            boolean hasMinYVertex = false;

            for (int x = minX; x < maxX; x++) {
                for (int y = minY; y < maxY; y++) {
                    for (int z = minZ; z < maxZ; z++) {
                        mutablePos.set(x, y, z);

                        // Sampled in the sub-level frame: the plot chunks hold the sub-level's own fluids.
                        final FluidState fluidState = level.getFluidState(mutablePos);

                        if (fluidState.isEmpty()) {
                            continue;
                        }

                        final double fluidTop = (double) y + fluidState.getHeight(level, mutablePos);

                        if (!hasMinYVertex) {
                            for (final Vector3d vertex : entityBox.vertices(sink.a)) {
                                minYVertex = Math.min(minYVertex, vertex.y);
                            }

                            hasMinYVertex = true;
                        }

                        if (fluidTop < minYVertex) {
                            continue;
                        }

                        fluidBox.getPosition().set(x + 0.5, y + 0.5, z + 0.5);

                        if (OrientedBoundingBox3d.sat(entityBox, fluidBox, sink.mtv).lengthSquared() <= 0.0) {
                            continue;
                        }

                        final Holder<Fluid> holder = fluidState.typeHolder();
                        final Found found = fluids.computeIfAbsent(holder, h -> new Found());
                        found.height = Math.max(fluidTop - minYVertex, found.height);

                        if (x == eyeLocalX && z == eyeLocalZ && localEye.y >= y) {
                            final double fluidTopForCamera = (double) y + fluidState.getHeightForCamera(level, mutablePos);

                            if (localEye.y <= fluidTopForCamera) {
                                found.eyesInside = true;
                            }
                        }

                        if (ignoreCurrent) {
                            continue;
                        }

                        for (final TagKey<Fluid> tag : FLUIDS_WITH_CURRENT) {
                            if (!holder.is(tag)) {
                                continue;
                            }

                            final Found current = currents.computeIfAbsent(tag, t -> new Found());
                            current.height = Math.max(found.height, current.height);

                            Vec3 flow = fluidState.getFlow(level, mutablePos);

                            if (current.height < 0.4) {
                                flow = flow.scale(current.height);
                            }

                            // The flow is a local direction, so bring it into the global frame.
                            current.current = current.current.add(pose.transformNormal(flow));
                            current.currentBlocks++;
                        }
                    }
                }
            }
        }
    }

    /**
     * The fluid state the entity should "feel" at a global block position: the world's own fluid if
     * there is one, otherwise the fluid of a sub-level covering that position.
     *
     * <p>Needed because mc26.1 still consults {@code level.getFluidState(blockPosition())} directly
     * in a few places that decide whether an entity travels/swims in fluid at all, and for an entity
     * on a sub-level that world position is air.
     */
    public static FluidState getFluidStateAt(final Entity entity, final BlockPos globalPos) {
        final Level level = entity.level();
        final FluidState worldFluid = level.getFluidState(globalPos);

        if (!worldFluid.isEmpty()) {
            return worldFluid;
        }

        final Vec3 center = Vec3.atCenterOf(globalPos);
        final BoundingBox3d query = new BoundingBox3d(globalPos).expand(0.5);

        for (final SubLevel subLevel : Sable.HELPER.getAllIntersecting(level, query)) {
            final BlockPos localPos = BlockPos.containing(subLevel.lastPose().transformPositionInverse(center));
            final FluidState localFluid = level.getFluidState(localPos);

            if (!localFluid.isEmpty()) {
                return localFluid;
            }
        }

        return worldFluid;
    }

    /**
     * Mirrors {@code EntityFluidInteraction.CurrentAccumulator#applyTo}.
     */
    public static void applyCurrent(final Entity entity, final Found found, final double scale) {
        if (found.currentBlocks == 0 || found.current.lengthSqr() < 1.0E-5F) {
            return;
        }

        Vec3 impulse = entity instanceof Player
                ? found.current.scale(1.0 / found.currentBlocks)
                : found.current.normalize();

        final Vec3 oldMovement = entity.getDeltaMovement();
        impulse = impulse.scale(scale);

        if (Math.abs(oldMovement.x) < 0.003 && Math.abs(oldMovement.z) < 0.003 && impulse.length() < 0.0045000000000000005) {
            impulse = impulse.normalize().scale(0.0045000000000000005);
        }

        entity.addDeltaMovement(impulse);
    }
}
