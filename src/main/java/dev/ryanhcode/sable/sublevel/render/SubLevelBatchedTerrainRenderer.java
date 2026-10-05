package dev.ryanhcode.sable.sublevel.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.ryanhcode.sable.SableClientConfig;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Option "C" of the sub-level rendering optimisation work: instead of submitting every
 * sub-level block as an individual moving block (one quad rebuild per block per frame),
 * whole plot sections are drawn through vanilla's terrain pipeline using the section
 * meshes that {@link VanillaChunkedSubLevelRenderData} already compiles.
 *
 * <h2>How the plot transform reaches the GPU</h2>
 *
 * <p>{@code terrain.vsh} computes
 * <pre>vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;</pre>
 * {@code CameraBlockPos} is {@code floor(cameraPos)} and {@code CameraOffset} is
 * {@code CameraBlockPos - cameraPos} (see {@code GlobalSettingsUniform#update}), so
 * {@code pos = worldPos - cameraPos} exactly. The vertex is finally placed by
 * {@code gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0)}, where {@code ModelViewMat}
 * comes from the {@code TerrainUniform} UBO that {@link ChunkSectionsToRender} binds once
 * per group.
 *
 * <p>Vanilla therefore only ever needs a pure view-rotation matrix there. To draw a plot
 * we hand {@link ChunkSectionsToRender.DrawSeparate} its own UBO holding
 * {@code viewRotation * T(position - camera) * R * S * T(camera - rotationPoint - B)},
 * and offset every section origin by the integer vector {@code B = round(position - rotationPoint)}
 * through {@link DynamicGpuData.ChunkSectionInfo}. With {@code q = p + B - camera} the
 * matrix evaluates to {@code position + R * S * (p - rotationPoint) - camera}, i.e. exactly
 * the transform the per-block path applied, while {@code q} stays small (and therefore
 * precise in float32) and close to the real rendered position so fog still behaves.
 *
 * <p>The groups built here are drawn from {@code ChunkSectionsToRenderMixin} at the end of
 * vanilla's own {@code renderGroup}/{@code renderOit} calls, so they land in the correct
 * render pass, after vanilla terrain and before features.
 */
public final class SubLevelBatchedTerrainRenderer {

    /**
     * The {@link ChunkSectionsToRender} groups built for this frame, one per visible sub-level.
     */
    private static final List<ChunkSectionsToRender> FRAME_GROUPS = new ArrayList<>();

    /**
     * Re-entrancy guard: {@link #renderGroup} is invoked from a mixin on
     * {@link ChunkSectionsToRender#renderGroup}, so drawing our own groups must not recurse.
     */
    private static boolean insideRender;

    /**
     * How often visible plot sections are asked to re-sort their translucent geometry.
     *
     * <p>Vanilla asks every visible section every frame; the resulting tasks short-circuit
     * on {@code isDifferentPointOfView}, but they still cost one allocation and one queue
     * push each, which is the wrong trade for a renderer whose job is to save frames.
     * Half a second keeps water and glass from visibly lagging behind the camera without
     * putting per-frame work back into the path this class exists to shrink.
     */
    private static final long RESORT_INTERVAL_MILLIS = 500L;

    private static long lastResortMillis;

    /**
     * One occlusion result per sub-level per frame.
     *
     * <p>The scan visits every loaded section and tests a full 16x16 face of it per
     * direction, so doing it once in the submit pass and again when the batches are built
     * would double its cost for no new information. Sharing the result also guarantees the
     * two passes agree on which sections are visible, which they have to: the submit pass
     * skips exactly the sections the batched pass then draws.
     */
    private static final Map<LevelPlot, LongSet> FRAME_VISIBLE_SECTIONS = new IdentityHashMap<>();

    /**
     * This renderer's own UBO storage, deliberately not {@code RenderSystem.getDynamicUniforms()}.
     *
     * <p>Vanilla writes its chunk-section infos into the shared instance while building its
     * draw groups and keeps the returned slices alive for the whole frame; writing a second
     * time reallocates the ring buffer behind those slices, and vanilla's indirect draw then
     * binds a closed buffer ({@code "Vertex buffer at slot 1 has been closed!"}). Owning a
     * private {@link DynamicGpuData} means our resize can only ever invalidate our own frames.
     */
    private static DynamicGpuData ownUniforms;

    /**
     * Starts a new frame's occlusion results, and drops the groups drawn last frame.
     *
     * <p>Called from the submit pass, which always runs before the batches are built, so
     * both passes read the same scan. Clearing the groups here as well as in
     * {@link #build} means a frame that submits but never builds draws nothing rather than
     * replaying the previous frame's draws against recycled uniform buffers.
     */
    public static void beginSubmitFrame() {
        FRAME_VISIBLE_SECTIONS.clear();
        FRAME_GROUPS.clear();
    }

    private SubLevelBatchedTerrainRenderer() {
    }

    /**
     * @return whether batched section rendering is enabled in the config
     */
    public static boolean isEnabled() {
        return SableClientConfig.SUB_LEVEL_BATCHED_SECTIONS.get();
    }

    /**
     * Whether a plot section can be drawn through the batched terrain path this frame.
     *
     * <p>The submit loop uses the exact same predicate, so a section is either drawn by this
     * class or by the per-block moving-block fallback, never both and never neither.
     */
    public static boolean isSectionBatchable(final SectionRenderDispatcher dispatcher,
                                             final SectionRenderDispatcher.RenderSection renderSection) {
        if (dispatcher == null || renderSection == null) {
            return false;
        }

        final SectionMesh mesh = renderSection.getSectionMesh();
        if (mesh == null || mesh == CompiledSectionMesh.UNCOMPILED) {
            return false;
        }

        // The uber buffers are mutated by compile tasks on the worker threads, which is why
        // vanilla holds the dispatcher's copy lock across draw-group extraction. Re-entrant,
        // so holding it again around the whole build below is fine.
        dispatcher.lock();
        try {
            for (final ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                if (mesh.getSectionDraw(layer) == null) {
                    continue;
                }
                if (dispatcher.getRenderSectionSlice(mesh, layer) != null) {
                    return true;
                }
            }
        } finally {
            dispatcher.unlock();
        }

        return false;
    }

    /**
     * Builds this frame's terrain groups. Always clears first so a disabled config or an
     * unloaded level can never leave stale draws behind.
     */
    public static void build(final Matrix4fc viewRotation, final boolean reverseTranslucent,
                             final Vec3 cameraPos, final long fadeMillis,
                             final SectionRenderDispatcher dispatcher,
                             final GpuTextureView atlasView) {
        FRAME_GROUPS.clear();

        if (!isEnabled() || viewRotation == null || cameraPos == null
                || dispatcher == null || atlasView == null) {
            return;
        }

        final int atlasWidth = atlasView.getWidth(0);
        final int atlasHeight = atlasView.getHeight(0);

        // Recycle last frame's UBO space. Safe because those slices were only ever bound
        // during last frame's render pass, which has long finished by now.
        final DynamicGpuData uniforms = ownUniforms();
        uniforms.reset();

        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        final ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }

        final boolean occlusionCulling = SableClientConfig.SUB_LEVEL_OCCLUSION_CULLING.get();
        final double renderDistance = SableClientConfig.SUB_LEVEL_RENDER_DISTANCE.get();
        final double renderDistanceSq = renderDistance <= 0.0 ? Double.POSITIVE_INFINITY : renderDistance * renderDistance;

        final Frustum frustum = Minecraft.getInstance().gameRenderer.mainCamera().getCullFrustum();
        final long now = Util.getMillis();

        final boolean resortTranslucency = now - lastResortMillis >= RESORT_INTERVAL_MILLIS;
        if (resortTranslucency) {
            lastResortMillis = now;
        }

        dispatcher.lock();
        try {
            for (final ClientSubLevel subLevel : container.getAllSubLevels()) {
                final ChunkSectionsToRender group = sable$buildGroup(
                        subLevel, viewRotation, reverseTranslucent, cameraPos, fadeMillis,
                        dispatcher, frustum, now, occlusionCulling, renderDistanceSq,
                        atlasWidth, atlasHeight, resortTranslucency, uniforms);

                if (group != null) {
                    FRAME_GROUPS.add(group);
                }
            }
        } finally {
            dispatcher.unlock();
        }
    }

    private static ChunkSectionsToRender sable$buildGroup(final ClientSubLevel subLevel,
                                                          final Matrix4fc viewRotation,
                                                          final boolean reverseTranslucent,
                                                          final Vec3 cameraPos,
                                                          final long fadeMillis,
                                                          final SectionRenderDispatcher dispatcher,
                                                          final Frustum frustum,
                                                          final long now,
                                                          final boolean occlusionCulling,
                                                          final double renderDistanceSq,
                                                          final int atlasWidth,
                                                          final int atlasHeight,
                                                          final boolean resortTranslucency,
                                                          final DynamicGpuData uniforms) {
        final LevelPlot plot = subLevel.getPlot();
        final BoundingBox3ic bounds = plot.getBoundingBox();
        if (bounds == null || bounds.volume() <= 0) {
            return null;
        }

        final SubLevelRenderData renderData = subLevel.getRenderData();
        if (!(renderData instanceof final VanillaChunkedSubLevelRenderData chunkedRenderData)) {
            return null;
        }

        final Pose3dc pose = subLevel.renderPose();
        final Vector3dc position = pose.position();
        final Vector3dc rotationPoint = pose.rotationPoint();
        final Vector3dc scale = pose.scale();

        // 距离剔除：整个子关卡远到看不见时直接跳过。
        final Vec3 worldCenter = pose.transformPosition(new Vec3(
                (bounds.minX() + bounds.maxX() + 1) * 0.5,
                (bounds.minY() + bounds.maxY() + 1) * 0.5,
                (bounds.minZ() + bounds.maxZ() + 1) * 0.5
        ));
        if (worldCenter.distanceToSqr(cameraPos) > renderDistanceSq) {
            return null;
        }

        // 遮挡剔除：从相机所在 section 出发，只穿过非全不透明面扩散。
        final LongSet visibleSections = occlusionCulling ? computeVisibleSections(plot, pose, cameraPos) : null;

        // Plot-space integer offset B = round(position - rotationPoint). See the class javadoc.
        final int offX = (int) Math.round(position.x() - rotationPoint.x());
        final int offY = (int) Math.round(position.y() - rotationPoint.y());
        final int offZ = (int) Math.round(position.z() - rotationPoint.z());

        // T(position - camera) * R * S * T(camera - rotationPoint - B)
        final Matrix4f plotTransform = new Matrix4f()
                .translate((float) (position.x() - cameraPos.x),
                        (float) (position.y() - cameraPos.y),
                        (float) (position.z() - cameraPos.z))
                .rotate(new Quaternionf().set(pose.orientation()))
                .scale((float) scale.x(), (float) scale.y(), (float) scale.z())
                .translate((float) (cameraPos.x - rotationPoint.x() - offX),
                        (float) (cameraPos.y - rotationPoint.y() - offY),
                        (float) (cameraPos.z - rotationPoint.z() - offZ));
        final Matrix4f modelView = new Matrix4f(viewRotation).mul(plotTransform);

        final List<DynamicGpuData.ChunkSectionInfo> infos = new ArrayList<>();
        final Map<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> drawsPerLayer =
                Util.makeEnumMap(ChunkSectionLayer.class, layer -> new ArrayList<>());
        int maxIndices = 0;

        final LongArrayList drawOrder = sable$sectionDrawOrder(plot, visibleSections, pose, cameraPos, frustum);

        for (int orderIndex = 0; orderIndex < drawOrder.size(); orderIndex++) {
            final long sectionNode = drawOrder.getLong(orderIndex);

            final SectionRenderDispatcher.RenderSection renderSection =
                    chunkedRenderData.getRenderSection(SectionPos.of(sectionNode));
            if (!isSectionBatchable(dispatcher, renderSection)) {
                continue;
            }

            final SectionMesh mesh = renderSection.getSectionMesh();
            final BlockPos origin = renderSection.getRenderOrigin();
            int infoIndex = -1;

            // Water and glass keep the sort they were compiled with until something asks
            // for a new one; vanilla does it every frame, we do it on a timer.
            if (resortTranslucency && renderSection.hasTranslucentGeometry()) {
                renderSection.resortTransparency();
            }

            for (final ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                final SectionMesh.SectionDraw sectionDraw = mesh.getSectionDraw(layer);
                if (sectionDraw == null) {
                    continue;
                }

                final SectionRenderDispatcher.RenderSectionBufferSlice slice =
                        dispatcher.getRenderSectionSlice(mesh, layer);
                if (slice == null) {
                    continue;
                }
                if (sectionDraw.hasCustomIndexBuffer() && slice.indexBuffer() == null) {
                    continue;
                }

                if (infoIndex == -1) {
                    infoIndex = infos.size();
                    infos.add(new DynamicGpuData.ChunkSectionInfo(
                            origin.getX() + offX,
                            origin.getY() + offY,
                            origin.getZ() + offZ,
                            renderSection.getVisibility(now, fadeMillis)
                    ));
                }

                final VertexFormat vertexFormat = layer.pipeline(false).getVertexFormatBinding(0);
                final GpuBuffer vertexBuffer = slice.vertexBuffer();
                final GpuBuffer indexBuffer;
                final IndexType indexType;
                final int firstIndex;

                if (sectionDraw.hasCustomIndexBuffer()) {
                    indexBuffer = slice.indexBuffer();
                    indexType = sectionDraw.indexType();
                    firstIndex = (int) (slice.indexBufferOffset() / indexType.bytes);
                } else {
                    indexBuffer = null;
                    indexType = null;
                    firstIndex = 0;
                    maxIndices = Math.max(maxIndices, sectionDraw.indexCount());
                }

                final int baseVertex = (int) (slice.vertexBufferOffset() / vertexFormat.getVertexSize());
                final int uniformIndex = infoIndex;

                drawsPerLayer.get(layer).add(new RenderPass.Draw<>(
                        0,
                        vertexBuffer,
                        indexBuffer,
                        indexType,
                        firstIndex,
                        sectionDraw.indexCount(),
                        baseVertex,
                        (sectionInfos, uploader) -> uploader.setUniform("ChunkSection", sectionInfos[uniformIndex])
                ));
            }
        }

        if (infos.isEmpty()) {
            return null;
        }

        for (final ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            if (reverseTranslucent && layer.translucent()) {
                drawsPerLayer.put(layer, new ArrayList<>(drawsPerLayer.get(layer).reversed()));
            }
        }

        if (maxIndices > 0) {
            RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).requestIndexCount(maxIndices);
        }

        final GpuBufferSlice terrainTransform = uniforms.writeTerrainTransform(modelView, atlasWidth, atlasHeight);
        final GpuBufferSlice[] chunkSectionInfos = uniforms.writeChunkSections(infos.toArray(new DynamicGpuData.ChunkSectionInfo[0]));

        return new ChunkSectionsToRender.DrawSeparate(terrainTransform, drawsPerLayer, maxIndices, chunkSectionInfos);
    }

    /**
     * 本帧要画的 plot section，<b>按到相机的距离从近到远</b>排序。
     *
     * <p>原版这条链是：{@code LevelRenderer.visibleSections} 由遮挡图从相机所在 section 出发
     * 广播而来，天然是近到远；{@code prepareChunkRenders} 只在<b>经典透明</b>（OIT 关闭，也就是
     * {@code reverseTranslucent == true}）时把它反转成远到近，好让水/玻璃按后往前混色。
     *
     * <p>这里原先按 {@code plot.getLoadedChunks()} 的顺序走，跟相机毫无关系：同一层里谁先画
     * 取决于区块加载顺序。半透明层是<b>写深度</b>的，近的先画就会把远的挡在深度测试之外 ——
     * 在子维度上表现为「水里的方块看不见」「某一片水后面直接看穿」。
     *
     * <p>距离在 plot 坐标系里算（相机先经 renderPose 反投影），子关卡的渲染变换是刚体变换，
     * 距离次序与世界坐标一致。
     *
     * @see dev.ryanhcode.sable.mixin.sublevel_render.RenderSectionMixin（单个 section 内部的排序键）
     */
    private static LongArrayList sable$sectionDrawOrder(final LevelPlot plot, final LongSet visibleSections,
                                                        final Pose3dc pose, final Vec3 cameraPos, final Frustum frustum) {
        final Vec3 cameraPlot = pose.transformPositionInverse(cameraPos);
        final LongArrayList nodes = new LongArrayList();

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

                if (frustum != null && !frustum.isVisible(sectionBounds(pose, chunkPos, sectionY << 4))) {
                    continue;
                }

                nodes.add(sectionNode);
            }
        }

        nodes.sort((a, b) -> sable$compareSectionDistance(a, b, cameraPlot));
        return nodes;
    }

    /**
     * @return section 中心到相机的距离平方，两个点都在 plot 坐标系里
     */
    private static double sable$sectionDistanceSq(final long sectionNode, final Vec3 cameraPlot) {
        final double dx = (SectionPos.x(sectionNode) + 8.0) - cameraPlot.x;
        final double dy = (SectionPos.y(sectionNode) + 8.0) - cameraPlot.y;
        final double dz = (SectionPos.z(sectionNode) + 8.0) - cameraPlot.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static int sable$compareSectionDistance(final long a, final long b, final Vec3 cameraPlot) {
        return Double.compare(sable$sectionDistanceSq(a, cameraPlot), sable$sectionDistanceSq(b, cameraPlot));
    }

    private static DynamicGpuData ownUniforms() {
        if (ownUniforms == null) {
            ownUniforms = new DynamicGpuData();
        }
        return ownUniforms;
    }

    /**
     * Draws every built group for the given layer group; invoked at the tail of vanilla's
     * {@link ChunkSectionsToRender#renderGroup}.
     */
    public static void renderGroup(final ChunkSectionLayerGroup layerGroup, final RenderPass renderPass,
                                   final GpuSampler sampler, final GpuTextureView atlasView, final boolean wireframe) {
        if (insideRender || FRAME_GROUPS.isEmpty()) {
            return;
        }

        insideRender = true;
        try {
            for (int i = 0; i < FRAME_GROUPS.size(); i++) {
                FRAME_GROUPS.get(i).renderGroup(layerGroup, renderPass, sampler, atlasView, wireframe);
            }
        } finally {
            insideRender = false;
        }
    }

    /**
     * Draws every built group through OIT; invoked at the tail of vanilla's
     * {@link ChunkSectionsToRender#renderOit}.
     */
    public static void renderOit(final GpuSampler sampler, final OitStage stage,
                                 final OitRenderPassProvider.Parameters parameters,
                                 final GpuTextureView colorView, final GpuTextureView depthView) {
        if (insideRender || FRAME_GROUPS.isEmpty()) {
            return;
        }

        insideRender = true;
        try {
            for (int i = 0; i < FRAME_GROUPS.size(); i++) {
                FRAME_GROUPS.get(i).renderOit(sampler, stage, parameters, colorView, depthView);
            }
        } finally {
            insideRender = false;
        }
    }

    /**
     * 从相机所在 section 出发，只穿过「非全不透明面」扩散，得到可见 section 集合。
     * 相机不在结构内时返回全部非空 section（仍会走视锥/距离剔除），避免从外部误剔。
     *
     * <p>结果按子关卡缓存到帧末，见 {@link #FRAME_VISIBLE_SECTIONS}。
     */
    public static LongSet computeVisibleSections(final LevelPlot plot, final Pose3dc pose, final Vec3 cameraPos) {
        final LongSet cached = FRAME_VISIBLE_SECTIONS.get(plot);
        if (cached != null) {
            return cached;
        }

        final LongSet visible = sable$scanVisibleSections(plot, pose, cameraPos);
        FRAME_VISIBLE_SECTIONS.put(plot, visible);
        return visible;
    }

    private static LongSet sable$scanVisibleSections(final LevelPlot plot, final Pose3dc pose, final Vec3 cameraPos) {
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
                if (faceOpaque(section, direction)) {
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

    private static boolean faceOpaque(final LevelChunkSection section, final Direction direction) {
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

    /**
     * @return the world-space AABB of a plot section after the sub-level pose is applied
     * (used for frustum culling)
     */
    public static AABB sectionBounds(final Pose3dc pose, final ChunkPos chunkPos, final int sectionMinY) {
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
}
