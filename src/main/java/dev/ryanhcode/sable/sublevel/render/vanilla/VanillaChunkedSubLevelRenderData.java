package dev.ryanhcode.sable.sublevel.render.vanilla;

import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.mixinterface.sublevel_render.vanilla.RenderSectionExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.render.SubLevelRenderData;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.joml.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A renderer and view area for a {@link dev.ryanhcode.sable.sublevel.SubLevel}.
 *
 * <p>mc26.1 port: drawing no longer happens here directly. Plot sections are
 * appended into vanilla's {@code ChunkSectionsToRender} draw groups inside
 * {@code LevelRenderer#prepareChunkRenders} (see the impl.vanilla
 * LevelRendererMixin) 鈥?each section carries its own model-view matrix, which
 * is how the plot's rotation/translation is applied.
 */
public class VanillaChunkedSubLevelRenderData implements SubLevelRenderData {

    private final Vector3d origin = new Vector3d();
    /**
     * The origin(minimum) of the render section grid
     */
    private final Vector3i chunkOrigin = new Vector3i();
    /**
     * The sub-level this renderer is for
     */
    private final ClientSubLevel subLevel;
    /**
     * The size of the render section grid
     */
    private final Vector3i size = new Vector3i();
    /**
     * All render sections this renderer stores
     */
    private final ObjectList<SectionRenderDispatcher.RenderSection> allRenderSections = new ObjectArrayList<>();
    /**
     * All dirty render sections this renderer stores
     */
    private final ObjectList<SectionRenderDispatcher.RenderSection> dirtyRenderSections = new ObjectArrayList<>();
    /**
     * Section nodes that were marked dirty because a player changed them
     */
    private final LongSet playerChangedSections = new LongOpenHashSet();
    /**
     * The grid of render sections
     */
    private SectionRenderDispatcher.RenderSection[] renderSections = null;
    /**
     * The section render dispatcher to build sections through
     */
    private final SectionRenderDispatcher sectionRenderDispatcher;

    /**
     * 每 section 缓存的「可见方块列表」：避免每帧重新扫描 16³ 个方块、重复做封闭剔除与生物群系查找。
     * 方块变化时通过 {@link LevelPlot#sable$getRenderRevision(long)} 失效。
     */
    private final Long2ObjectMap<VisibleBlocks> sable$visibleBlocks = new Long2ObjectOpenHashMap<>();

    /**
     * 一个 section 内需要渲染的方块（已剔除空气与完全封闭的方块），配合版本号做缓存复用。
     */
    public static final class VisibleBlocks {
        public final long revision;
        public final boolean cullEnclosed;
        public final BlockPos[] positions;
        public final BlockState[] states;
        public final Holder<Biome>[] biomes;

        VisibleBlocks(final long revision, final boolean cullEnclosed, final BlockPos[] positions, final BlockState[] states, final Holder<Biome>[] biomes) {
            this.revision = revision;
            this.cullEnclosed = cullEnclosed;
            this.positions = positions;
            this.states = states;
            this.biomes = biomes;
        }
    }

    /**
     * Creates a new renderer for the given sub-level
     *
     * @param subLevel the sub-level to render
     */
    public VanillaChunkedSubLevelRenderData(final ClientSubLevel subLevel, final SectionRenderDispatcher sectionRenderDispatcher) {
        this.subLevel = subLevel;
        this.sectionRenderDispatcher = sectionRenderDispatcher;
        this.resize();
    }

    /**
     * Gets a section in global section coordinates
     */
    private static SectionRenderDispatcher.RenderSection getSection(final SectionRenderDispatcher.RenderSection[] sections, final Vector3i size, final Vector3i origin, final int x, final int y, final int z) {
        final int relX = (x - origin.x());
        final int relY = (y - origin.y());
        final int relZ = (z - origin.z());

        if (relX < 0 || relY < 0 || relZ < 0) {
            return null;
        }

        if (relX >= size.x() || relY >= size.y() || relZ >= size.z()) {
            return null;
        }

        return sections[relX + relY * size.x() + relZ * size.x() * size.y()];
    }

    /**
     * Gets an index in the render section grid from a global position
     */
    private int getIndex(final int x, final int y, final int z) {
        return (x - this.chunkOrigin.x()) + (y - this.chunkOrigin.y()) * this.size.x() + (z - this.chunkOrigin.z()) * this.size.x() * this.size.y();
    }

    /**
     * Checks if a global section coordinate is in bounds
     */
    private boolean inBounds(final int x, final int y, final int z) {
        final int localX = x - this.chunkOrigin.x();
        final int localY = y - this.chunkOrigin.y();
        final int localZ = z - this.chunkOrigin.z();
        return localX >= 0 && localY >= 0 && localZ >= 0 &&
                localX < this.size.x() && localY < this.size.y() && localZ < this.size.z();

    }

    public void resize() {
        final SectionRenderDispatcher.RenderSection[] oldRenderSections = this.renderSections;
        final Collection<SectionRenderDispatcher.RenderSection> oldRenderSectionsList = new ObjectArrayList<>(this.allRenderSections);

        this.renderSections = null;
        this.allRenderSections.clear();
        this.dirtyRenderSections.clear();
        this.sable$visibleBlocks.clear();

        final BoundingBox3ic bounds = this.subLevel.getPlot().getBoundingBox();

        if (bounds != null && !bounds.equals(BoundingBox3i.EMPTY) && bounds.volume() > 0.0) {
            final Vector3i minChunkPos = new Vector3i(bounds.minX() >> 4, bounds.minY() >> 4, bounds.minZ() >> 4);
            final Vector3i maxChunkPos = new Vector3i(bounds.maxX() >> 4, bounds.maxY() >> 4, bounds.maxZ() >> 4);

            final Vector3i oldSize = new Vector3i(this.size);
            final Vector3i oldOrigin = new Vector3i(this.chunkOrigin);

            this.size.set(maxChunkPos.x() - minChunkPos.x() + 1, maxChunkPos.y() - minChunkPos.y() + 1, maxChunkPos.z() - minChunkPos.z() + 1);
            this.chunkOrigin.set(minChunkPos);
            this.origin.set(minChunkPos.x() << 4, minChunkPos.y() << 4, minChunkPos.z() << 4);

            this.renderSections = new SectionRenderDispatcher.RenderSection[this.size.x() * this.size.y() * this.size.z()];

            for (int x = minChunkPos.x(); x <= maxChunkPos.x(); x++) {
                for (int y = minChunkPos.y(); y <= maxChunkPos.y(); y++) {
                    for (int z = minChunkPos.z(); z <= maxChunkPos.z(); z++) {
                        final SectionRenderDispatcher.RenderSection oldSection = getSection(oldRenderSections, oldSize, oldOrigin, x, y, z);
                        final SectionRenderDispatcher.RenderSection newSection;

                        if (oldRenderSections != null && oldSection != null) {
                            newSection = oldSection;
                        } else {
                            newSection = this.sectionRenderDispatcher.new RenderSection(-1, SectionPos.asLong(x, y, z));
                            ((RenderSectionExtension) newSection).sable$addDirtyListener(this.dirtyRenderSections::add);
                        }

                        // mc26.3 port: RenderSection no longer carries its own
                        // dirty state (that moved to SectionUpdateTracker in
                        // vanilla). Newly created plot sections are uncompiled
                        // and therefore need a build.
                        if (newSection.getSectionMesh() == CompiledSectionMesh.UNCOMPILED) {
                            this.dirtyRenderSections.add(newSection);
                        }
                        this.renderSections[this.getIndex(x, y, z)] = newSection;
                        this.allRenderSections.add(newSection);
                    }
                }
            }

            // free old chunks
            if (oldRenderSections != null) {
                for (final SectionRenderDispatcher.RenderSection oldSection : oldRenderSectionsList) {
                    // if not in bounds
                    final SectionPos oldSectionPos = SectionPos.of(oldSection.getRenderOrigin());
                    if (oldSectionPos.getX() < minChunkPos.x() || oldSectionPos.getX() > maxChunkPos.x() ||
                            oldSectionPos.getY() < minChunkPos.y() || oldSectionPos.getY() > maxChunkPos.y() ||
                            oldSectionPos.getZ() < minChunkPos.z() || oldSectionPos.getZ() > maxChunkPos.z()) {

                        oldSection.reset();
                    }
                }
            }
        }
    }

    @Override
    public void rebuild() {
        for (final SectionRenderDispatcher.RenderSection renderSection : this.allRenderSections) {
            this.sable$markDirty(renderSection, true);
        }
    }

    /**
     * Marks a plot render section as needing a rebuild.
     *
     * <p>Vanilla's {@code SectionUpdateTracker} only covers the real view area,
     * so plot sections keep their own dirty queue here.
     */
    private void sable$markDirty(final SectionRenderDispatcher.RenderSection renderSection, final boolean playerChanged) {
        if (playerChanged) {
            this.playerChangedSections.add(renderSection.getSectionNode());
        }
        if (!this.dirtyRenderSections.contains(renderSection)) {
            this.dirtyRenderSections.add(renderSection);
        }
    }

    @Override
    public void compileSections(final PrioritizeChunkUpdates chunkUpdates, final RenderRegionCache renderRegionCache, final Camera camera) {
        if (this.dirtyRenderSections.isEmpty()) {
            return;
        }

        final ProfilerFiller profiler = net.minecraft.util.profiling.Profiler.get();
        final Vector3d cameraPos = JOMLConversion.atCenterOf(camera.blockPosition()).sub(8, 8, 8);
        this.subLevel.logicalPose().transformPositionInverse(cameraPos);

        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            this.dirtyRenderSections.clear();
            this.playerChangedSections.clear();
            return;
        }

        for (final SectionRenderDispatcher.RenderSection renderSection : this.dirtyRenderSections) {
            ((RenderSectionExtension) renderSection).sable$setListening(false);

            final boolean playerChanged = this.playerChangedSections.contains(renderSection.getSectionNode());
            boolean buildSync = false;
            if (chunkUpdates == PrioritizeChunkUpdates.NEARBY) {
                final BlockPos origin = renderSection.getRenderOrigin();
                buildSync = cameraPos.distanceSquared(origin.getX(), origin.getY(), origin.getZ()) < 768.0 || playerChanged;
            } else if (chunkUpdates == PrioritizeChunkUpdates.PLAYER_AFFECTED) {
                buildSync = playerChanged;
            }

            // mc26.3 port: RenderSection#rebuildSectionSync/Async and the
            // dispatcher overloads were replaced by the region based
            // compileSync/compileAsync calls (mirrors LevelRenderer#compileSections).
            final RenderSectionRegion region = renderRegionCache.createRegion(level, renderSection.getSectionNode());
            if (buildSync) {
                profiler.push("sublevel_build_near_sync");
                renderSection.compileSync(region);
                profiler.pop();
            } else {
                profiler.push("sublevel_schedule_async_compile");
                renderSection.compileAsync(region);
                profiler.pop();
            }

            ((RenderSectionExtension) renderSection).sable$setListening(true);
        }
        this.dirtyRenderSections.clear();
        this.playerChangedSections.clear();
    }

    @Override
    public int getVisibleSectionCount() {
        return this.allRenderSections.size();
    }

    @Override
    public ClientSubLevel getSubLevel() {
        return this.subLevel;
    }

    @Override
    public boolean isSectionCompiled(final int x, final int y, final int z) {
        if (this.renderSections == null) {
            return false;
        }

        if (!this.inBounds(x, y, z)) {
            return true;
        }

        final int index = this.getIndex(x, y, z);
        return index >= 0 && index < this.renderSections.length && this.renderSections[index].getSectionMesh() != CompiledSectionMesh.UNCOMPILED;
    }

    @Override
    public void setDirty(final int x, final int y, final int z, final boolean playerChanged) {
        if (this.renderSections == null) {
            return;
        }

        if (!this.inBounds(x, y, z)) {
            return;
        }

        final int index = this.getIndex(x, y, z);
        if (index >= 0 && index < this.renderSections.length) {
            this.sable$markDirty(this.renderSections[index], playerChanged);
        }
    }

    /**
     * @return all render sections this renderer stores
     */
    public ObjectList<SectionRenderDispatcher.RenderSection> allRenderSections() {
        return this.allRenderSections;
    }

    @Override
    public void close() {
        for (final SectionRenderDispatcher.RenderSection section : this.allRenderSections) {
            section.reset();
        }
        this.allRenderSections.clear();
        this.renderSections = null;
        this.sable$visibleBlocks.clear();
    }

    /**
     * 返回指定 plot section 的可见方块列表（带缓存）。
     *
     * <p>仅在 section 内容变化（{@code plot.sable$getRenderRevision} 改变）或封闭剔除开关切换时重建，
     * 否则直接复用上一帧的结果。
     */
    public VisibleBlocks sable$getVisibleBlocks(final long sectionNode, final LevelChunkSection section, final int sectionMinY,
                                                final int baseX, final int baseZ, final boolean cullEnclosedEnabled) {
        final int revision = this.subLevel.getPlot().sable$getRenderRevision(sectionNode);
        final VisibleBlocks cached = this.sable$visibleBlocks.get(sectionNode);
        if (cached != null && cached.revision == revision && cached.cullEnclosed == cullEnclosedEnabled) {
            return cached;
        }

        final Level level = this.subLevel.getLevel();
        final List<BlockPos> positions = new ArrayList<>();
        final List<BlockState> states = new ArrayList<>();
        final List<Holder<Biome>> biomes = new ArrayList<>();

        for (int localX = 0; localX < 16; localX++) {
            for (int localY = 0; localY < 16; localY++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    final BlockState blockState = section.getBlockState(localX, localY, localZ);
                    if (blockState.isAir()) {
                        continue;
                    }

                    final BlockPos blockPos = new BlockPos(baseX + localX, sectionMinY + localY, baseZ + localZ);
                    if (cullEnclosedEnabled && blockState.isSolidRender() && sable$isFullyEnclosed(level, blockPos)) {
                        continue;
                    }

                    positions.add(blockPos);
                    states.add(blockState);
                    biomes.add(level.getBiome(blockPos));
                }
            }
        }

        final VisibleBlocks built = new VisibleBlocks(
                revision,
                cullEnclosedEnabled,
                positions.toArray(new BlockPos[0]),
                states.toArray(new BlockState[0]),
                sable$biomeArray(biomes)
        );
        this.sable$visibleBlocks.put(sectionNode, built);
        return built;
    }

    @SuppressWarnings("unchecked")
    private static Holder<Biome>[] sable$biomeArray(final List<Holder<Biome>> biomes) {
        return biomes.toArray(new Holder[0]);
    }

    private static boolean sable$isFullyEnclosed(final Level level, final BlockPos pos) {
        for (final Direction direction : Direction.values()) {
            if (!level.getBlockState(pos.relative(direction)).canOcclude()) {
                return false;
            }
        }
        return true;
    }

    public SectionRenderDispatcher.RenderSection getRenderSection(final SectionPos sectionPos) {
        if (this.renderSections == null) {
            return null;
        }

        final int index = this.getIndex(sectionPos.getX(), sectionPos.getY(), sectionPos.getZ());

        if (index < 0 || index >= this.renderSections.length) {
            return null;
        }

        return this.renderSections[index];
    }
}
