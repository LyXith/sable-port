package dev.ryanhcode.sable.mixin.block_decal_render;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Changes the distance block damage is rendered from, and transforms block damage rendering for sublevels.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    // Storage vectors to avoid repeated allocation
    private final @Unique Quaternionf sable$orientationStorage = new Quaternionf();

    @Inject(method = "submitBlockDestroyAnimation", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V", shift = At.Shift.AFTER))
    private void sable$transformBlockDamage(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, LevelRenderState levelRenderState, CallbackInfo ci, @Local final BlockBreakingRenderState renderState, @Local final BlockPos pos) {
        final Vec3 plotPos = Vec3.atLowerCornerOf(pos);
        ClientLevel level = Minecraft.getInstance().level;
        final ClientSubLevel subLevel = (ClientSubLevel) Sable.HELPER.getContaining(level, plotPos);
        if (subLevel == null) {
            return;
        }

        final Pose3dc renderPose = subLevel.renderPose();
        final Vec3 cameraPos = levelRenderState.cameraRenderState.pos;
        final Vec3 projectedPos = renderPose.transformPosition(plotPos);
        poseStack.translate(
                -(plotPos.x - cameraPos.x),
                -(plotPos.y - cameraPos.y),
                -(plotPos.z - cameraPos.z)
        );
        poseStack.translate(projectedPos.x - cameraPos.x, projectedPos.y - cameraPos.y, projectedPos.z - cameraPos.z);
        Matrix4f matrix = new Matrix4f().rotate(new Quaternionf(this.sable$orientationStorage.set(renderPose.orientation())));
        poseStack.mulPose(matrix);
    }
}
