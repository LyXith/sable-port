package dev.ryanhcode.sable.mixin.sublevel_render.impl.vanilla;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.ryanhcode.sable.sublevel.render.SubLevelBatchedTerrainRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the sub-level terrain groups built by {@link SubLevelBatchedTerrainRenderer}.
 *
 * <p>Hanging off {@link ChunkSectionsToRender} rather than off {@code LevelRenderer}'s
 * execute methods means every terrain render path (solid, classic translucency and OIT)
 * picks the plot groups up in the correct pass, with the correct sampler / atlas /
 * wireframe state, and after vanilla's own terrain but before features.
 */
@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin {

    @Inject(
            method = "renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V",
            at = @At("TAIL")
    )
    private void sable$renderSubLevelTerrain(final ChunkSectionLayerGroup layerGroup, final RenderPass renderPass,
                                             final GpuSampler sampler, final GpuTextureView atlasView,
                                             final boolean wireframe, final CallbackInfo ci) {
        SubLevelBatchedTerrainRenderer.renderGroup(layerGroup, renderPass, sampler, atlasView, wireframe);
    }

    @Inject(
            method = "renderOit(Lcom/mojang/renderpearl/api/textures/GpuSampler;Lnet/minecraft/client/renderer/oit/OitStage;Lnet/minecraft/client/renderer/oit/OitRenderPassProvider$Parameters;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V",
            at = @At("TAIL")
    )
    private void sable$renderSubLevelTerrainOit(final GpuSampler sampler, final OitStage stage,
                                                final OitRenderPassProvider.Parameters parameters,
                                                final GpuTextureView colorView, final GpuTextureView depthView,
                                                final CallbackInfo ci) {
        SubLevelBatchedTerrainRenderer.renderOit(sampler, stage, parameters, colorView, depthView);
    }
}
