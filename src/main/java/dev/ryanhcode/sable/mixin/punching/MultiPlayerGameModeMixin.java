package dev.ryanhcode.sable.mixin.punching;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.network.client.ClientSubLevelPunchHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "startDestroyBlock", at = @At("HEAD"))
    private void onBlockBreakStart(final BlockPos blockPos, final Direction direction, final CallbackInfoReturnable<Boolean> cir) {
        assert this.minecraft.player != null;

        if (this.minecraft.hitResult instanceof final BlockHitResult blockHitResult) {
            ClientSubLevelPunchHelper.clientTryPunch(blockHitResult, this.minecraft.level, true);
        }
    }

    /**
     * 在子关卡上使用物品（放方块等）时补上挥手动画。
     *
     * <p>mc26.3 的 {@code Minecraft.startUseItem} 只在结果为
     * {@code InteractionResult.Success(PREDICTED)} 时才挥动手臂。子关卡交互走的是
     * 射线投射到 plot 坐标的路径，客户端本地预测可能返回 {@code FAIL}（原版直接
     * return，不挥手），但服务端仍会放下方块，于是出现"方块放上了、手没挥"。
     *
     * <p>这里只在"目标是子关卡方块、原版不会挥手、且目标确实可放置（含没有实体
     * 遮挡）"时补一次，避免在放不下（被遮挡）时也挥手。
     *
     * <p>放置音效不在这里补：由服务端在子关卡的世界坐标播放（见
     * {@code sublevel_sounds.LevelSoundMixin}）。
     */
    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void sable$swingOnSubLevelUseOn(final LocalPlayer player, final InteractionHand hand, final BlockHitResult hitResult, final CallbackInfoReturnable<InteractionResult> cir) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        if (Sable.HELPER.getContaining(level, hitResult.getBlockPos()) == null) {
            return; // 只对子关卡方块兜底
        }

        final InteractionResult result = cir.getReturnValue();
        final InteractionResult.SwingSource swing = result instanceof final InteractionResult.Success success
                ? success.swingSource()
                : InteractionResult.SwingSource.NONE;

        if (swing != InteractionResult.SwingSource.NONE) {
            return; // PREDICTED: 客户端自己挥手；SERVER_ONLY: 服务端下发挥手包
        }

        final ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof final BlockItem blockItem)) {
            return;
        }

        // 真正能否放置：先看 canPlace，再看目标位置是否被实体遮挡。
        if (!new BlockPlaceContext(player, hand, stack, hitResult).canPlace()) {
            return;
        }

        final BlockPos placedPos = hitResult.getBlockPos().relative(hitResult.getDirection());
        final AABB placedBox = new AABB(
                placedPos.getX(), placedPos.getY(), placedPos.getZ(),
                placedPos.getX() + 1.0, placedPos.getY() + 1.0, placedPos.getZ() + 1.0
        );
        if (!level.getEntities((Entity) null, placedBox).isEmpty()) {
            return; // 目标位置有实体遮挡，放不下，不挥手
        }

        player.swing(hand, stack.getInteractAnimation(), false);
    }

}
