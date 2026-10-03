package dev.ryanhcode.sable.mixin.sublevel_render;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.sublevel_render.vanilla.RenderSectionExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Notifies sub-level render data when one of its sections is marked dirty, and maps plot
 * sections into their sub-level's coordinate space when translucent geometry is sorted.
 *
 * <p>mc26.3 port: the per-section {@code dirty}/{@code setDirty} state was
 * moved out of {@code SectionRenderDispatcher.RenderSection} into
 * {@code SectionUpdateTracker}, which only tracks the real view area. Plot
 * sections are dirty-tracked by {@code VanillaChunkedSubLevelRenderData}
 * itself, so this mixin only keeps the listener plumbing used by that class.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public class RenderSectionMixin implements RenderSectionExtension {

    @Unique
    private Set<DirtyListener> sable$listeners;
    @Unique
    private boolean sable$listening = true;

    /**
     * A plot section's {@link SectionPos} is a coordinate on the sub-level's own block grid,
     * not on the world grid, so the sort key vanilla derives from
     * {@code camera - sectionOrigin} would be measured from the wrong place once the
     * sub-level is displaced or rotated. Passing the camera through the inverse pose first
     * makes the key identical to what vanilla computes for an unmoved sub-level.
     *
     * <p>This is the single place both {@code CompileTask} and {@code ResortTransparencyTask}
     * build their sorting from, so nothing has to share the dispatcher's camera position.
     *
     * <p>Uses the logical pose rather than the render pose on purpose: these tasks run on the
     * worker threads, and {@code renderPose()} writes the partial-tick it was last evaluated
     * at, which would race with the render thread. The tick of interpolation the logical pose
     * is behind shifts the key by well under a block.
     */
    @Inject(
            method = "createVertexSorting(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/phys/Vec3;)Lcom/mojang/blaze3d/vertex/VertexSorting;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void sable$plotVertexSorting(final SectionPos sectionPos, final Vec3 cameraPos,
                                         final CallbackInfoReturnable<VertexSorting> cir) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        final SubLevel subLevel = Sable.HELPER.getContaining(level, sectionPos);
        if (subLevel == null) {
            return;
        }

        final Vec3 plotCamera = subLevel.logicalPose().transformPositionInverse(cameraPos);

        cir.setReturnValue(VertexSorting.byDistance(
                (float) (plotCamera.x - sectionPos.minBlockX()),
                (float) (plotCamera.y - sectionPos.minBlockY()),
                (float) (plotCamera.z - sectionPos.minBlockZ())
        ));
    }

    @Override
    public void sable$addDirtyListener(final DirtyListener listener) {
        if (this.sable$listeners == null) {
            this.sable$listeners = new ObjectArraySet<>();
        }
        this.sable$listeners.add(listener);
    }

    @Override
    public void sable$setListening(final boolean listening) {
        this.sable$listening = listening;
    }
}
