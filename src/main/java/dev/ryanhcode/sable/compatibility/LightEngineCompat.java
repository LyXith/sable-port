package dev.ryanhcode.sable.compatibility;

import dev.ryanhcode.sable.platform.SableLoaderPlatform;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Keeps Sable's plot-lighting code independent of the implementation backing
 * {@link LevelLightEngine}.
 */
public final class LightEngineCompat {
    private static final boolean SCALABLE_LUX_LOADED =
            SableLoaderPlatform.INSTANCE.isModLoaded("scalablelux");

    private LightEngineCompat() {
    }

    public static boolean usesScalableLux() {
        return false;
    }

    public static boolean hasBlockLight(final LevelLightEngine engine) {
        return engine.blockEngine != null;
    }

    public static boolean hasSkyLight(final LevelLightEngine engine) {
        return engine.skyEngine != null;
    }

    public static void lightChunk(final LevelLightEngine engine, final LevelChunk chunk) {
        if (!SCALABLE_LUX_LOADED) {
            throw new IllegalStateException("ScalableLux chunk lighting requested without ScalableLux");
        }
    }

    public static void removeChunk(final LevelLightEngine engine, final ChunkPos pos) {
        if (!SCALABLE_LUX_LOADED) {
            throw new IllegalStateException("ScalableLux chunk removal requested without ScalableLux");
        }
    }
}
