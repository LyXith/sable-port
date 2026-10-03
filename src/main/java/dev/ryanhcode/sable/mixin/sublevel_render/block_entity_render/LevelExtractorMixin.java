package dev.ryanhcode.sable.mixin.sublevel_render.block_entity_render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.compatibility.entityculling.EntityCullingCompat;
import dev.ryanhcode.sable.mixinterface.BlockEntityRenderDispatcherExtension;
import dev.ryanhcode.sable.mixinterface.sublevel_render.BlockEntityTransformsHolder;
import dev.ryanhcode.sable.mixinterface.sublevel_render.SubLevelBlockEntityRenderExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.render.SubLevelRenderData;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.*;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.SortedSet;

@Mixin(LevelExtractor.class)
public class LevelExtractorMixin implements SubLevelBlockEntityRenderExtension {
    @Shadow
    @Final
    private LevelRenderer levelRenderer;

    @Shadow
    private ClientLevel level;

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Inject(method = "extractVisibleBlockEntities", at = @At("RETURN"))
    private void sable$extractBlockEntities(
            final Camera camera,
            final float partialTick,
            final LevelRenderState levelRenderState,
            final CallbackInfo ci
    ) {
        this.sable$extractSubLevelBlockEntities(camera, partialTick, levelRenderState);
    }

    @Override
    public void sable$extractSubLevelBlockEntities(
            final Camera camera,
            final float partialTick,
            final LevelRenderState levelRenderState
    ) {
        ((BlockEntityTransformsHolder) this.levelRenderer).sable$getBlockEntityTransforms().clear();
        levelRenderState.blockEntityRenderStates.removeIf(state -> Sable.HELPER.getContainingClient(state.blockPos) != null);
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        final ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }

        final Vec3 cameraPosition = camera.position();
        final BlockEntityRenderDispatcherExtension dispatcherExtension = (BlockEntityRenderDispatcherExtension) this.levelRenderer.blockEntityRenderDispatcher();

        try (final EntityCullingCompat.Scope ignored = EntityCullingCompat.suspendBlockEntityCulling()) {
            for (final ClientSubLevel subLevel : container.getAllSubLevels()) {
                final SubLevelRenderData renderData = subLevel.getRenderData();
                if (renderData == null) {
                    continue;
                }

                final Vector3dc rotationPoint = subLevel.renderPose(partialTick).rotationPoint();
                final Matrix4f transformation = renderData.getTransformation(cameraPosition.x, cameraPosition.y, cameraPosition.z);
                final Vector3f localCamera = transformation.invert(new Matrix4f()).transformPosition(new Vector3f());

                dispatcherExtension.sable$setCameraPosition(new Vec3(
                        localCamera.x + rotationPoint.x(),
                        localCamera.y + rotationPoint.y(),
                        localCamera.z + rotationPoint.z()
                ));

                try {
                    for (final PlotChunkHolder holder : subLevel.getPlot().getLoadedChunks()) {
                        for (final BlockEntity blockEntity : holder.getChunk().getBlockEntities().values()) {
                            if (blockEntity.isRemoved()) {
                                continue;
                            }

                            final BlockPos blockPos = blockEntity.getBlockPos();
                            final ModelFeatureRenderer.CrumblingOverlay crumblingOverlay = this.sable$createCrumblingOverlay(blockPos, transformation, rotationPoint);
                            final BlockEntityRenderState renderState = this.levelRenderer.blockEntityRenderDispatcher().tryExtractRenderState(blockEntity, partialTick, crumblingOverlay, false);
                            if (renderState == null) {
                                continue;
                            }

                            final Vector3d physicalCenter = subLevel.renderPose(partialTick).transformPosition(
                                    new Vector3d(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5)
                            );
                            renderState.lightCoords = LightCoordsUtil.getLightCoords(
                                    level,
                                    BlockPos.containing(physicalCenter.x, physicalCenter.y, physicalCenter.z)
                            );
                            levelRenderState.blockEntityRenderStates.add(renderState);
                            ((BlockEntityTransformsHolder) this.levelRenderer).sable$getBlockEntityTransforms().put(renderState, new BlockEntityTransformsHolder.SableBlockEntityTransform(transformation, (Vector3d) rotationPoint, new Quaternionf(subLevel.renderPose(partialTick).orientation())));
                        }
                    }
                } finally {
                    dispatcherExtension.sable$setCameraPosition(null);
                }
            }
        }
    }

    @Unique
    private ModelFeatureRenderer.CrumblingOverlay sable$createCrumblingOverlay(final BlockPos blockPos, final Matrix4f transformation, final Vector3dc rotationPoint) {
        final SortedSet<BlockDestructionProgress> progress = this.level.destructionProgress().get(blockPos.asLong());
        if (progress == null || progress.isEmpty()) {
            return null;
        }

        final PoseStack poseStack = new PoseStack();
        poseStack.mulPose(transformation);
        poseStack.translate(
                blockPos.getX() - rotationPoint.x(),
                blockPos.getY() - rotationPoint.y(),
                blockPos.getZ() - rotationPoint.z()
        );
        return new ModelFeatureRenderer.CrumblingOverlay(progress.last().getProgress(), poseStack.last());
    }
}
