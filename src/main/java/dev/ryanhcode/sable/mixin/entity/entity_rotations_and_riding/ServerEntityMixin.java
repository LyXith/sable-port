package dev.ryanhcode.sable.mixin.entity.entity_rotations_and_riding;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ServerEntity.class)
public abstract class ServerEntityMixin {

    @Shadow private List<Entity> lastPassengers;

    @Shadow @Final private Entity entity;

    // mc26.3: the old Synchronizer#sendToTrackingPlayersFiltered call is gone;
    // refreshing lastPassengers at the head of sendChanges has the same effect.
    @Inject(method = "sendChanges", at = @At("HEAD"))
    private void sable$beforeSendChanges(final CallbackInfo ci) {
        final List<Entity> passengers = this.entity.getPassengers();

        if (Sable.HELPER.getContaining(this.entity) != null) {
            this.lastPassengers = passengers;
        }
    }
}
