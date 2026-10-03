package dev.ryanhcode.sable.mixin.sublevel_render;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    // mc26.3: LevelRenderer no longer has a "level" field and #allChanged was
    // replaced by #invalidateCompiledGeometry(ClientLevel, Options, Camera, BlockColors).
    @Inject(method = "invalidateCompiledGeometry", at = @At("TAIL"))
    private void sable$allChanged(final CallbackInfo ci) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        SubLevelRenderDispatcher.get().rebuild(((ClientSubLevelContainer) ((SubLevelContainerHolder) level).sable$getPlotContainer()).getAllSubLevels());
    }

    // PORT-NOTE(mc26.1): the single-block batch pass (renderAfterSections) was
    // removed with the ShaderInstance pipeline — see VanillaSubLevelRenderDispatcher.
}
