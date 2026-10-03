package dev.ryanhcode.sable.mixin.sublevel_render;

import dev.ryanhcode.sable.mixinterface.sublevel_render.vanilla.RenderSectionExtension;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Set;

/**
 * Notifies sub-level render data when one of its sections is marked dirty.
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
