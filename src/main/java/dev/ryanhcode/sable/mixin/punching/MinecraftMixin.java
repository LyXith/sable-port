package dev.ryanhcode.sable.mixin.punching;

import dev.ryanhcode.sable.client.rope.RopeManager;
import dev.ryanhcode.sable.index.SableTags;
import dev.ryanhcode.sable.network.client.ClientSubLevelPunchHelper;
import dev.ryanhcode.sable.network.packets.tcp.ServerboundRemoveRopePacket;
import dev.ryanhcode.sable.network.tcp.SablePacketSink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Shadow
    @Nullable
    public LocalPlayer player;

    @Shadow
    @Nullable
    public ClientLevel level;

    @Shadow
    @Nullable
    public abstract ClientPacketListener getConnection();

    // 左键攻击时优先检测绳子：命中则请求服务端移除，并阻止后续攻击。
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void sable$attackRope(final CallbackInfoReturnable<Boolean> cir) {
        if (this.player == null) {
            return;
        }

        final java.util.UUID rope = RopeManager.pick(
                this.player.getEyePosition(),
                this.player.getLookAngle(),
                this.player.blockInteractionRange()
        );

        if (rope != null) {
            RopeManager.remove(rope);
            SablePacketSink.server().sendPacket(new ServerboundRemoveRopePacket(rope));
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "startAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;resetAttackStrengthTicker()V"))
    private void tryPaddling(final CallbackInfoReturnable<Boolean> cir) {
        // PORT-NOTE(mc26.1): ItemCooldowns now keys on ItemStack.
        if (!this.player.getMainHandItem().is(SableTags.PADDLES) ||
                this.player.getCooldowns().isOnCooldown(this.player.getMainHandItem())) {
            return;
        }

        final BlockHitResult hitResult = ItemInvoker.sable$getPlayerPOVHitResult(this.level, this.player, ClipContext.Fluid.ANY);

        if (hitResult.getType() == HitResult.Type.BLOCK) {
            final FluidState state = this.level.getFluidState(hitResult.getBlockPos());
            if (!state.isEmpty()) {
                ClientSubLevelPunchHelper.clientTryPunch(hitResult, this.level, false);
            }
        }
    }
}
