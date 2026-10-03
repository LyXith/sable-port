package dev.ryanhcode.sable.mixin.sublevel_render;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 打开 Fabric Renderer API（Indigo）移动方块渲染器的邻接面剔除。
 *
 * <p>原版 {@code MovingBlockFeatureRenderer} 被 Fabric Renderer API 接管后，面生成委托给
 * Indigo 的 {@code AltModelBlockRendererImpl}；Fabric 调用
 * {@code Renderer.altModelBlockRenderer(ambientOcclusion, false, blockColors)} 时把
 * {@code cull} 传成 {@code false}（活塞不需要）。Sable 的子关卡方块也走这条路，于是内部面
 * 全画。
 *
 * <p>这里强制 {@code cull = true}：
 * <ul>
 *     <li>对活塞：其 {@code MovingBlockRenderState.getBlockState} 对邻居返回空气，剔除判断
 *     结果为“照画”，行为不变；</li>
 *     <li>对子关卡：使用 {@code SubLevelMovingBlockRenderState} 从 plot 读取真实邻居，
 *     正确剔除内部面并计算环境光遮蔽。</li>
 * </ul>
 *
 * <p>用 {@code targets} 字符串引用 Indigo 的实现类，避免编译期强依赖。
 */
@Mixin(targets = "net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl")
public class AltModelBlockRendererImplMixin {

    // 构造器在 super() 之前注入，handler 必须是 static。
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, index = 2)
    private static boolean sable$forceFaceCulling(final boolean cull) {
        return true;
    }
}
