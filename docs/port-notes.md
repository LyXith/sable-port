# 26.3 移植说明

本分支把上游 Sable（NeoForge 1.21.1）移植到 **Fabric / Minecraft 26.3**。本文记录移植时的关键决策、踩过的坑、已修复的问题与已知限制。

---

## 1. 构建环境

- **必须使用 JDK 25**（Minecraft 26.3 / Fabric 26.3 的 class file version 为 69）。
- 项目通过 `gradle/gradle-daemon-jvm.properties` 的 `toolchainVersion=25` 自动定位工具链，**不要硬编码 `JAVA_HOME`**。
- 构建：`gradle build`；产物在 `build/libs/sable-fabric-26.3-<version>.jar`。

## 2. Mixin 配置（最关键的坑）

**`compatibilityLevel` 必须为 `JAVA_25`。**

若设为 `JAVA_21`，Mixin 会因为目标 class 版本过高（`Class version 69 required is higher...`）而**静默跳过**大量 Mixin —— 本项目曾一次被跳过约 **179 个**。这些 Mixin 覆盖音效、渲染、实体交互等，表现为“功能莫名失效、逻辑对不上”，非常难排查。

排查手段：查看 `runs/client/logs/debug.log` 中是否有 `Class version 69 required`，以及目标 Mixin 是否出现 `Mixing ... from sable.mixins.json`。

## 3. 被剥离 / 排除的内容

为适配 26.3 的 render-state 重写，`build.gradle` 的 `sourceSets.main.java.exclude` 排除了以下不再有 1:1 API 的文件：

- **Sodium 兼容层**（`fabric/mixin/compatibility/sodiumextras/**`、`sublevel_render/impl/sodium/**`）。当前移植分支**没有 Sodium 专用渲染路径**，`sub_level_renderer` 只有 `VANILLA`。
- **Veil shader 特性**（动态着色 / 天光阴影 / 水面遮挡）。相关客户端配置项仍保留以便旧配置加载，但已不驱动任何逻辑。
- 旧 debug 线框渲染、`loaded_chunk_debug`、`udp`、部分 `block_outline_render`、`stop_lightning`、若干 `plot/container`、以及 26.3 中签名变更的 `EntityRendererMixin` / `BlockMixin` / `BlockAndTintGetterMixin` 等。

## 4. 移植中修复的问题

### 持久化 / 坐标

- `SubLevelHoldingChunkMap.saveAll` 曾用 `new ChunkPos(blockX, blockZ)` 保存，实际应为 **chunk 坐标**（`SectionPos.blockToSectionCoord`）。同样的错误还存在于 `ServerChunkCacheMixin.blockChanged`、`BlockAndTintGetterMixin.getBrightness/getRawBrightness`、`ExecuteCommandMixin`、`VoxelNeighborhoodState.getState`。修复前存档里的子关卡会落到错误的远处区块。
- 在 plot 区块边界放置方块：`mixin/plot/LevelMixin.java` 在 `Level.setBlock(...II)` 前确保目标 plot 区块存在；`EmbeddedPlotLevelAccessor.setBlock` 调用 `plot.ensureChunkFor(globalPos)`。

### 同步

- `SubLevelTrackingSystem` 改为**增量区块同步**：维护 `syncedChunks` 与脏区块集合，每 tick 只重发新增 / 变化的 plot 区块（`PlotChunkHolder.networkDirty`），并加固 `sendFullSync`。修复了“在区块边界放方块后客户端看不见，除非重进”的问题。

### 光照

- `ServerLevelPlot.lightChunk` 改为 `setLightEnabled(pos, true)`（此前误传 `chunk.isLightCorrect()`，会把区块在 plot 光照引擎里禁用）。
- `TerrainParticleMixin` 恢复子关卡地形粒子的光照采样。

> 跨子关卡的光照 / 遮挡未处理（有意为之）。

### 音效

- 新增 `mixin/sublevel_sounds/LevelSoundMixin`：服务端把 **plot 坐标**上的 `playSound(Entity, BlockPos, ...)` 投影到子关卡世界坐标并广播给所有玩家（含放置者）；客户端抑制 plot 坐标的重复播放。修复了放置 / 打火石等音效听不到或双响的问题。

### 交互

- **挥手**：`punching/MultiPlayerGameModeMixin.useItemOn` 在“目标是子关卡、原版不挥手、且确实能放置（`BlockPlaceContext.canPlace()` 且目标 AABB 无实体）”时补一次挥手。
- **挖掘裂纹**：见下文。
- **左键移除绳子**：`punching/MinecraftMixin.startAttack` 命中绳子时移除并阻止后续攻击。

### 渲染

- **子关卡方块渲染**改为通过 `SubmitNodeCollector.submitMovingBlock`（原版活塞路径）提交，支持完整位姿 / 旋转 / 非整数位置。
- **面剔除**：vanilla `MovingBlockFeatureRenderer` 把 `cull` 硬编码为 `false`，且 Fabric `fabric-renderer-api-v1` 会把 moving-block 渲染交给 Indigo。因此通过 `AltModelBlockRendererImplMixin` 强制 Indigo 的 moving-block 渲染 `cull = true`，并用 `SubLevelMovingBlockRenderState` 读取真实 plot 邻居。
- **剔除**：`impl.vanilla.LevelRendererMixin` 每帧读取 `SableClientConfig`，实现 section 遮挡 BFS、距离剔除、封闭方块剔除与视锥剔除。
- **破坏裂纹**（`block_decal_render`）：
  1. 这两个 Mixin 曾在 `JAVA_21` 下被跳过；改为 `JAVA_25` 后真正生效。
  2. Mixin 中一个 `@Unique final` 字段的初始化器未被合并，运行到裂纹渲染时空指针崩溃；已改为方法内局部对象。
  3. 关键：`submitBreakingBlockModel(..., boolean)` 的最后一个参数决定裂纹进 `solid` 还是 `breakingOverlay`。普通方块进 `solid` 没问题（地形更早绘制），但子关卡方块也在 `solid` 且移动方块 renderer 排在方块模型 renderer 之后，会把裂纹盖住。已对子关卡方块强制置 `true`，让裂纹进 `breakingOverlay`。修复后挖掘裂纹正常显示。

### 界面

- 通过 Mod Menu 的 `ConfigScreenFactoryRegistry` 注册配置界面；补齐 `sable.configuration.*` 的英文 / 中文翻译。

### 其它

- 修复 `RandomPosMixin` 导致的线上崩溃。
- `IntegratedServerMixin` 把 Toast 操作包进 `minecraft.execute`，修复跨线程崩溃。
- 删除 `/sable test` 命令；删除依赖 Create 方块的 schematic（`vostone_2.nbt`、`vinalilime.nbt`）。

## 5. 已知限制

- **无 Sodium 兼容层**：安装 Sodium 时子关卡渲染不会走 Sodium 路径（Sodium 仍可用于原版地形）。
- **部分渲染特性失效**：动态着色 / 天光阴影 / 水面遮挡开关为历史遗留，暂不生效。
- **旧存档迁移**：在“chunk 坐标修复”之前保存的子关卡可能位于错误的远处区块（只会在那个错误区块被加载时才出现，不会自动恢复），需要一次性迁移脚本。
- **跨子关卡光照 / 遮挡**未实现。
- 启动时可能看到 `Failed to apply tag physics properties. Unknown block: create:flywheel` —— 这是数据包引用了未安装的 Create 方块标签，与本移植无关。
- `Requested post effect does not exist: minecraft:end_of_frame` 是 26.3 的已知无害告警。

## 6. 调试建议

- **Mixin 没生效？** 先确认 `sable.mixins.json` / `sable-fabric.mixins.json` 的 `compatibilityLevel` 为 `JAVA_25`，再看 `logs/debug.log` 是否打印了对应 `Mixing ...`。
- **子关卡落错位置 / 存错区块？** 检查相关代码是否把 block 坐标当 chunk 坐标（用 `SectionPos.blockToSectionCoord`）。
- **渲染相关开关不生效？** 客户端渲染 Mixin 每帧读取 `SableClientConfig`，确认选项确实被 Mod Menu / `sable-client.toml` 改动。
