package dev.ryanhcode.sable.mixin.block_placement;

import dev.ryanhcode.sable.mixinhelpers.block_placement.BlockPlacementEntityHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/**
 * Disallows placing blocks on sub-levels inside of entities
 */
@Mixin(EntityGetter.class)
public interface EntityGetterMixin {

    @Shadow
    List<Entity> getEntities(@org.jetbrains.annotations.Nullable Entity pEntity, AABB pArea);

    @Shadow
    List<? extends Player> players();

    /**
     * @author RyanH
     * @reason Taking sub-levels into account
     */
    @Overwrite
    default boolean isUnobstructed(@Nullable final Entity pEntity, final VoxelShape voxelShape) {
        if (!voxelShape.isEmpty()) {
            for (final Entity entity : this.getEntities(pEntity, voxelShape.bounds())) {
                if (entity.isRemoved() || !entity.blocksBuilding || (pEntity != null && entity.isPassengerOfSameVehicle(pEntity))) {
                    continue;
                }
                if (BlockPlacementEntityHelper.obstructs(voxelShape, entity)) {
                    return false;
                }
            }
        }

        return true;
    }
}
