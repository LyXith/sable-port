package dev.ryanhcode.sable.mixin.sublevel_render.impl.vanilla;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.SableClientConfig;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.client.rope.RopeManager;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.SubLevelMovingBlockRenderState;
import dev.ryanhcode.sable.sublevel.render.SubLevelRenderData;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Mixin(value = LevelRenderer.class, priority = 1002)
public abstract class LevelRendererMixin {

    @Unique
    private final Quaternionf sable$subLevelRotation = new Quaternionf();

    @Shadow
    @Final
    private GameRenderer gameRenderer;

    @Shadow
    private void submitHitOutline(final PoseStack poseStack, final SubmitNodeCollector collector, final RenderType renderType, final BlockOutlineRenderState state, final int color, final float width, final boolean afterTerrain) {
    }

    // The selected block's outline state carries the plot-space BlockPos for
    // sub-level blocks, which vanilla then renders at that far-away coordinate
    // (i.e. invisible). Re-submit it with the sub-level pose applied.
    @Inject(method = "submitBlockOutline", at = @At("HEAD"), cancellable = true)
    private void sable$submitSubLevelBlockOutline(final PoseStack poseStack, final SubmitNodeCollector collector, final LevelRenderState levelRenderState, final CallbackInfo ci) {
        final BlockOutlineRenderState state = levelRenderState.blockOutlineRenderState;
        if (state == null) {
            return;
        }

        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        final BlockPos pos = state.pos();
        if (!(Sable.HELPER.getContaining(level, pos) instanceof final ClientSubLevel subLevel)) {
            return;
        }

        final Vec3 cameraPos = levelRenderState.cameraRenderState.pos;
        final Pose3dc pose = subLevel.renderPose();
        final Vector3dc rotationPoint = pose.rotationPoint();
        final Vector3dc scale = pose.scale();

        poseStack.pushPose();
        poseStack.translate(pose.position().x() - cameraPos.x, pose.position().y() - cameraPos.y, pose.position().z() - cameraPos.z);
        poseStack.rotate(this.sable$subLevelRotation.set(pose.orientation()));
        poseStack.scale((float) scale.x(), (float) scale.y(), (float) scale.z());
        poseStack.translate(pos.getX() - rotationPoint.x(), pos.getY() - rotationPoint.y(), pos.getZ() - rotationPoint.z());

        final boolean highContrast = state.highContrast();
        if (highContrast) {
            this.submitHitOutline(poseStack, collector, RenderTypes.secondaryBlockOutline(), state, -16777216, 7.0F, state.isTranslucent());
        }

        final int color = highContrast ? -11010079 : ARGB.black(102);
        final RenderType renderType;
        if (highContrast) {
            renderType = RenderTypes.linesDepthBias();
        } else if (this.gameRenderer.useImprovedTransparency()) {
            renderType = RenderTypes.linesTranslucentNoDepthWrite();
        } else {
            renderType = RenderTypes.linesTranslucent();
        }

        this.submitHitOutline(
                poseStack,
                collector,
                renderType,
                state,
                color,
                this.gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth,
                state.isTranslucent()
        );
        poseStack.popPose();
        ci.cancel();
    }

    @Inject(method = "compileSections", at = @At("TAIL"))
    private void sable$compileSections(CameraRenderState camera, CallbackInfo ci) {
        ClientLevel level = Minecraft.getInstance().level;
        final Iterable<ClientSubLevel> sublevels = ((ClientSubLevelContainer) ((SubLevelContainerHolder) level).sable$getPlotContainer()).getAllSubLevels();
        final RenderRegionCache renderRegionCache = new RenderRegionCache();
        final PrioritizeChunkUpdates chunkUpdates = Minecraft.getInstance().options.prioritizeChunkUpdates().get();

        final Camera camera1 = Minecraft.getInstance().gameRenderer.mainCamera();
        for (final ClientSubLevel sublevel : sublevels) {
            sublevel.getRenderData().compileSections(chunkUpdates, renderRegionCache, camera1);
        }
    }

    // mc26.3: the terrain pipeline (ChunkSectionsToRender / ChunkSectionInfo) no
    // longer carries a per-section matrix, so sub-level sections cannot be
    // rotated through it. Vanilla's moving-block path (used for pistons) does
    // support a full PoseStack, so submit each sub-level block as a moving block
    // instead. This gives correct rotation, scaling and sub-block positions.
    @Inject(method = "submitTransientBlocks", at = @At("TAIL"))
    private void sable$submitSubLevelBlocks(final PoseStack poseStack, final SubmitNodeCollector collector, final LevelRenderState levelRenderState, final CallbackInfo ci) {
        final ClientLevel clientLevel = Minecraft.getInstance().level;
        if (clientLevel == null) {
            return;
        }

        final ClientSubLevelContainer container = SubLevelContainer.getContainer(clientLevel);
        if (container == null) {
            return;
        }

        final Vec3 cameraPos = levelRenderState.cameraRenderState.pos;
        final double camX = cameraPos.x;
        final double camY = cameraPos.y;
        final double camZ = cameraPos.z;

        final Frustum frustum = Minecraft.getInstance().gameRenderer.mainCamera().getCullFrustum();

        final boolean occlusionCulling = SableClientConfig.SUB_LEVEL_OCCLUSION_CULLING.get();
        final boolean cullEnclosedBlocks = SableClientConfig.SUB_LEVEL_CULL_ENCLOSED_BLOCKS.get();
        final double renderDistance = SableClientConfig.SUB_LEVEL_RENDER_DISTANCE.get();
        final double renderDistanceSq = renderDistance <= 0.0 ? Double.POSITIVE_INFINITY : renderDistance * renderDistance;

        for (final ClientSubLevel subLevel : container.getAllSubLevels()) {
            final LevelPlot plot = subLevel.getPlot();
            final BoundingBox3ic bounds = plot.getBoundingBox();
            if (bounds == null || bounds.volume() <= 0.0) {
                continue;
            }

            final Pose3dc pose = subLevel.renderPose();
            final Vector3dc position = pose.position();
            final Vector3dc rotationPoint = pose.rotationPoint();
            final Vector3dc scale = pose.scale();
            this.sable$subLevelRotation.set(pose.orientation());

            final Level plotLevel = subLevel.getLevel();
            final SubLevelRenderData renderData = subLevel.getRenderData();
            if (!(renderData instanceof final VanillaChunkedSubLevelRenderData chunkedRenderData)) {
                continue;
            }
            final CardinalLighting cardinalLighting = ((ClientLevel) plotLevel).cardinalLighting();
            final LevelLightEngine lightEngine = plotLevel.getLightEngine();

            // 距离剔除：整个子关卡远到看不见时直接跳过。
            final Vec3 worldCenter = pose.transformPosition(new Vec3(
                    (bounds.minX() + bounds.maxX() + 1) * 0.5,
                    (bounds.minY() + bounds.maxY() + 1) * 0.5,
                    (bounds.minZ() + bounds.maxZ() + 1) * 0.5
            ));
            if (worldCenter.distanceToSqr(cameraPos) > renderDistanceSq) {
                continue;
            }

            // 遮挡剔除：从相机所在 section 出发，只穿过非全不透明面扩散。
            final LongSet visibleSections = occlusionCulling ? sable$computeVisibleSections(plot, pose, cameraPos) : null;

            // 只遍历已加载区块中的非空 section，并按视锥剔除。
            for (final PlotChunkHolder holder : plot.getLoadedChunks()) {
                final LevelChunk chunk = holder.getChunk();
                if (chunk == null) {
                    continue;
                }

                final ChunkPos chunkPos = chunk.getPos();
                final LevelChunkSection[] sections = chunk.getSections();

                for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                    final LevelChunkSection section = sections[sectionIndex];
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }

                    final int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
                    final long sectionNode = SectionPos.asLong(chunkPos.x, sectionY, chunkPos.z);
                    if (visibleSections != null && !visibleSections.contains(sectionNode)) {
                        continue;
                    }

                    final int sectionMinY = sectionY << 4;
                    if (frustum != null && !frustum.isVisible(sable$sectionBounds(pose, chunkPos, sectionMinY))) {
                        continue;
                    }

                    // 复用缓存的可见方块列表：仅在 section 内容变化时重建，避免每帧重新扫描 16³ 个方块、
                    // 重复做封闭剔除与生物群系查找。提交仍是逐方块（26.3 的地形管线不支持逐 section 旋转）。
                    final VanillaChunkedSubLevelRenderData.VisibleBlocks visible = chunkedRenderData.sable$getVisibleBlocks(
                            sectionNode, section, sectionMinY, chunkPos.x << 4, chunkPos.z << 4, cullEnclosedBlocks);
                    final BlockPos[] visiblePositions = visible.positions;
                    if (visiblePositions.length == 0) {
                        continue;
                    }
                    final BlockState[] visibleStates = visible.states;
                    final Holder<Biome>[] visibleBiomes = visible.biomes;

                    // 每 section 只 push/pop 一次 PoseStack；每个方块只做局部平移/撤销。
                    // submitMovingBlock 会拷贝当前 pose 矩阵，因此修改 PoseStack 不会影响已提交节点。
                    poseStack.pushPose();
                    // plot -> world (camera relative): T(position - camera) * R * S
                    poseStack.translate(position.x() - camX, position.y() - camY, position.z() - camZ);
                    poseStack.rotate(this.sable$subLevelRotation);
                    poseStack.scale((float) scale.x(), (float) scale.y(), (float) scale.z());

                    for (int i = 0; i < visiblePositions.length; i++) {
                        final BlockPos blockPos = visiblePositions[i];
                        final double localX = blockPos.getX() - rotationPoint.x();
                        final double localY = blockPos.getY() - rotationPoint.y();
                        final double localZ = blockPos.getZ() - rotationPoint.z();
                        poseStack.translate(localX, localY, localZ);

                        final SubLevelMovingBlockRenderState state = new SubLevelMovingBlockRenderState();
                        state.sable$setLevel(plotLevel);
                        state.blockPos = blockPos;
                        state.randomSeedPos = blockPos;
                        state.blockState = visibleStates[i];
                        state.biome = visibleBiomes[i];
                        state.cardinalLighting = cardinalLighting;
                        state.lightEngine = lightEngine;

                        collector.submitMovingBlock(poseStack, state, 0);

                        poseStack.translate(-localX, -localY, -localZ);
                    }

                    poseStack.popPose();
                }
            }
        }

        RopeManager.renderAll(poseStack, collector, cameraPos);
    }

    /**
     * 从相机所在 section 出发，只穿过「非全不透明面」扩散，得到可见 section 集合。
     * 相机不在结构内时返回全部非空 section（仍会走视锥/距离剔除），避免从外部误剔。
     */
    @Unique
    private static LongSet sable$computeVisibleSections(final LevelPlot plot, final Pose3dc pose, final Vec3 cameraPos) {
        final Long2ObjectMap<LevelChunkSection> sections = new Long2ObjectOpenHashMap<>();

        for (final PlotChunkHolder holder : plot.getLoadedChunks()) {
            final LevelChunk chunk = holder.getChunk();
            if (chunk == null) {
                continue;
            }

            final LevelChunkSection[] chunkSections = chunk.getSections();
            for (int i = 0; i < chunkSections.length; i++) {
                final LevelChunkSection section = chunkSections[i];
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }

                sections.put(SectionPos.asLong(chunk.getPos().x, chunk.getSectionYFromSectionIndex(i), chunk.getPos().z), section);
            }
        }

        if (sections.isEmpty()) {
            return LongSets.emptySet();
        }

        final Vec3 cameraPlot = pose.transformPositionInverse(cameraPos);
        final long start = SectionPos.asLong(
                Mth.floor(cameraPlot.x) >> 4,
                Mth.floor(cameraPlot.y) >> 4,
                Mth.floor(cameraPlot.z) >> 4
        );

        if (!sections.containsKey(start)) {
            return new LongOpenHashSet(sections.keySet());
        }

        final LongSet visible = new LongOpenHashSet();
        final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();

        visible.add(start);
        queue.enqueue(start);

        while (!queue.isEmpty()) {
            final long node = queue.dequeueLong();
            final LevelChunkSection section = sections.get(node);

            final int x = SectionPos.x(node);
            final int y = SectionPos.y(node);
            final int z = SectionPos.z(node);

            for (final Direction direction : Direction.values()) {
                if (sable$faceOpaque(section, direction)) {
                    continue;
                }

                final long next = SectionPos.asLong(x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ());

                if (sections.containsKey(next) && visible.add(next)) {
                    queue.enqueue(next);
                }
            }
        }

        return visible;
    }

    @Unique
    private static boolean sable$faceOpaque(final LevelChunkSection section, final Direction direction) {
        switch (direction) {
            case DOWN -> {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (!section.getBlockState(x, 0, z).canOcclude()) return false;
                    }
                }
            }
            case UP -> {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (!section.getBlockState(x, 15, z).canOcclude()) return false;
                    }
                }
            }
            case NORTH -> {
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        if (!section.getBlockState(x, y, 0).canOcclude()) return false;
                    }
                }
            }
            case SOUTH -> {
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        if (!section.getBlockState(x, y, 15).canOcclude()) return false;
                    }
                }
            }
            case WEST -> {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        if (!section.getBlockState(0, y, z).canOcclude()) return false;
                    }
                }
            }
            case EAST -> {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        if (!section.getBlockState(15, y, z).canOcclude()) return false;
                    }
                }
            }
        }

        return true;
    }

    @Unique
    private static AABB sable$sectionBounds(final Pose3dc pose, final ChunkPos chunkPos, final int sectionMinY) {
        final int minX = chunkPos.x << 4;
        final int minZ = chunkPos.z << 4;
        double x0 = Double.POSITIVE_INFINITY;
        double y0 = Double.POSITIVE_INFINITY;
        double z0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY;
        double y1 = Double.NEGATIVE_INFINITY;
        double z1 = Double.NEGATIVE_INFINITY;

        for (int dx = 0; dx <= 16; dx += 16) {
            for (int dy = 0; dy <= 16; dy += 16) {
                for (int dz = 0; dz <= 16; dz += 16) {
                    final Vec3 corner = pose.transformPosition(new Vec3(minX + dx, sectionMinY + dy, minZ + dz));
                    x0 = Math.min(x0, corner.x);
                    y0 = Math.min(y0, corner.y);
                    z0 = Math.min(z0, corner.z);
                    x1 = Math.max(x1, corner.x);
                    y1 = Math.max(y1, corner.y);
                    z1 = Math.max(z1, corner.z);
                }
            }
        }

        return new AABB(x0, y0, z0, x1, y1, z1);
    }

    @Inject(method = "isSectionCompiledAndVisible", at = @At("HEAD"), cancellable = true)
    private void sable$isSectionCompiled(BlockPos blockPos, long chunkFadeDuration, CallbackInfoReturnable<Boolean> cir) {
        ClientLevel level = Minecraft.getInstance().level;
        final ClientSubLevelContainer container = SubLevelContainer.getContainer(level);

        if (container == null) {
            return;
        }

        if (container.inBounds(blockPos)) {
            final ClientSubLevel subLevel = (ClientSubLevel) Sable.HELPER.getContaining(level, blockPos);

            if (subLevel == null) {
                cir.setReturnValue(false);
            } else {
                final SubLevelRenderData renderData = subLevel.getRenderData();
                final SectionPos sectionPos = SectionPos.of(blockPos);
                cir.setReturnValue(renderData.isSectionCompiled(sectionPos.x(), sectionPos.y(), sectionPos.z()));
            }
        }
    }

}
