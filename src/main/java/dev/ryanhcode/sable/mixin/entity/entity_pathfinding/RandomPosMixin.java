package dev.ryanhcode.sable.mixin.entity.entity_pathfinding;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.RandomPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(RandomPos.class)
public class RandomPosMixin {

    /**
     * @author RyanH
     * @reason Wandering on sub-levels
     */
    // mc26.3: the toward-direction distance parameter is now a double.
    @Overwrite
    public static BlockPos generateRandomPosTowardDirection(final PathfinderMob mob, final double xzDist, final RandomSource random, final BlockPos direction) {
        final SubLevel trackingSubLevel = Sable.HELPER.getTrackingSubLevel(mob);
        Vec3 effectiveMobPos = mob.position();

        if (trackingSubLevel != null) {
            effectiveMobPos = trackingSubLevel.logicalPose().transformPositionInverse(effectiveMobPos);
        }

        double xt = direction.getX();
        double zt = direction.getZ();

        if (mob.hasHome() && xzDist > 1.0) {
            final BlockPos center = mob.getHomePosition();
            if (effectiveMobPos.x() > (double) center.getX()) {
                xt -= random.nextDouble() * xzDist / 2.0;
            } else {
                xt += random.nextDouble() * xzDist / 2.0;
            }

            if (effectiveMobPos.z() > (double) center.getZ()) {
                zt -= random.nextDouble() * xzDist / 2.0;
            } else {
                zt += random.nextDouble() * xzDist / 2.0;
            }
        }

        return BlockPos.containing(xt + effectiveMobPos.x(), (double) direction.getY() + effectiveMobPos.y(), zt + effectiveMobPos.z());
    }

}
