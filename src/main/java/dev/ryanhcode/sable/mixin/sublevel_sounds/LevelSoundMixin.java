package dev.ryanhcode.sable.mixin.sublevel_sounds;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端在 plot 坐标播放的音效（方块放置/破坏等）传不到任何玩家，因为玩家都在
 * 子关卡的世界坐标附近，而音效位置是 plot 坐标。
 *
 * <p>这里在服务端把子关卡方块的音效位置投影到该子关卡的世界坐标再播放，这样
 * 附近的玩家就能收到（客户端侧的动态移动由 {@code sublevel_sounds} 的其它 mixin
 * 负责）。
 */
@Mixin(Level.class)
public class LevelSoundMixin {

    @Inject(method = "playSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V", at = @At("HEAD"), cancellable = true)
    private void sable$projectSubLevelSound(final Entity except, final BlockPos pos, final SoundEvent sound,
                                            final SoundSource source, final float volume, final float pitch, final CallbackInfo ci) {
        final Level level = (Level) (Object) this;

        final SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
        if (subLevel == null || !subLevel.getPlot().contains(pos.getX(), pos.getZ())) {
            return; // 只处理在 plot 坐标播放的音效；世界坐标（脚步等）不受影响
        }

        if (level.isClientSide()) {
            // 客户端本地预测在 plot 坐标播放的这一声，会被服务端在子关卡世界坐标
            // 下发的音效取代，丢弃以避免双声（对打火石等所有物品一视同仁）。
            ci.cancel();
            return;
        }

        // 传 null 而不是 except：客户端预测失败时本地不会有音效，若这里再排除放置者，
        // 放置者就完全听不到。
        final Vec3 world = subLevel.logicalPose().transformPosition(Vec3.atCenterOf(pos));
        level.playSound(null, world.x, world.y, world.z, sound, source, volume, pitch);
        ci.cancel();
    }
}
