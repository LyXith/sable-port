# 架构

本文描述 Sable 在 **Fabric / Minecraft 26.3** 下的整体结构。上游为 NeoForge 1.21.1，移植时大量依赖 26.3 的“render state / submit node”渲染重写与新的客户端/服务端 API。

---

## 1. 概念模型

- **子关卡（SubLevel）**：一组被抽离主世界、作为整体做刚体模拟的方块。分服务端 `ServerSubLevel` 与客户端 `ClientSubLevel`。
- **Plot**：子关卡内部方块的存储空间。每个子关卡拥有一个 `LevelPlot`，plot 内的方块使用**独立的 plot 坐标**，与主世界完全隔离。
- **位姿（Pose）**：`dev.ryanhcode.sable.companion.math.Pose3d` 描述子关卡在世界中的位置、朝向、旋转中心与缩放。子关卡的方块世界坐标由位姿把 plot 坐标变换得到。
- **容器（Container）**：`SubLevelContainer` 管理一个维度内的全部子关卡；服务端为 `ServerSubLevelContainer`（含物理系统），客户端为 `ClientSubLevelContainer`。

关键 API 入口：

```java
ServerSubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
ServerSubLevel subLevel = (ServerSubLevel) container.allocateNewSubLevel(pose);
LevelPlot plot = subLevel.getPlot();
```

创建流程见 [`creating-a-physics-sublevel.md`](creating-a-physics-sublevel.md)。

## 2. 坐标系统（最容易踩坑的地方）

| 坐标 | 含义 | 量级 |
| --- | --- | --- |
| 世界坐标 | 正常的主世界坐标 | 常规 |
| plot 坐标 | 子关卡内部坐标，从 plot 中心附近起算 | 常为 `2e7` 级 |

判断“某个位置属于哪个子关卡”统一走 `Sable.HELPER.getContaining(...)` / `getContainingClient(...)`；它按 **plot block 坐标 → plot chunk → 子关卡** 查找。因此：

- 射线/碰撞投射到子关卡时，`BlockPos` 往往是 **plot 坐标**（例如方块轮廓、挖掘目标）。
- 实体的世界位置则用世界坐标。
- **不要手工把 block 坐标当 chunk 坐标**：plot chunk = `SectionPos.blockToSectionCoord(blockX/Z)`。移植中多处持久化 / 光照代码曾把 block 坐标直接当 chunk 坐标，导致子关卡存到了错误的远处区块（见 `SubLevelHoldingChunkMap`、`ServerChunkCacheMixin.blockChanged`、`BlockAndTintGetterMixin` 等修复）。

## 3. 物理

- 通过 `sable-companion` 的 **Rapier** 后端做刚体模拟（`SubLevelPhysicsSystem` + `PhysicsPipeline`）。
- 服务端每 tick 推进物理；子关卡作为刚体注册，`teleport(...)` 可把刚体瞬间安置到指定位姿。
- 物理参数（子步数、solver/PGS/稳定化迭代、接触弹簧、min island size 等）可通过 `/sable debug config` 与 `SableServerConfig` 调整。
- 每当物理 tick 前后会派发平台事件（`FabricSablePrePhysicsTickEvent` / `FabricSablePostPhysicsTickEvent`）。

## 4. 网络与同步

- 自建 TCP 通道（`SableTCPPackets` / `SableTCPPacket` / `SablePacketSink`）承载子关卡位姿、快照、rope、punch 等数据包；可选 UDP 通道用于高频位姿更新（`SableClientConfig.ATTEMPT_UDP_NETWORKING`）。
- **位姿快照 + 延迟插值**：服务端按 tick 发送位姿快照，客户端用 `SubLevelSnapshotInterpolator` 在 `SableClientConfig.INTERPOLATION_DELAY`（默认 1.5 tick）之后回放，避免抖动。
- **增量区块同步**：`SubLevelTrackingSystem` 维护 `syncedChunks` 与脏区块集合，每 tick 只重发新增 / 变化的 plot 区块（`PlotChunkHolder.networkDirty`），而不是每次全量重发。
- 数据包列表集中在 `SableTCPPackets.entries()`；Fabric 侧在 `SableFabric` 中通过 `PayloadTypeRegistry` 注册。

## 5. 渲染管线

### 5.1 子关卡方块

客户端每个子关卡都有一个 `ClientSubLevel`（含 `renderPose()` 渲染位姿）。当前移植分支使用 **vanilla 渲染路径**，按 section 的编译状态分成两条，由同一个 `isSectionBatchable(...)` 判定分工，保证既不重复绘制也不漏画：

#### 批量路径（默认，`sub_level_batched_sections`）

- `SubLevelRenderer` 选择 `SelectedRenderer.VANILLA`，创建 `VanillaSubLevelRenderDispatcher`。它复用 `LevelRenderer.sectionRenderDispatcher()`，因此子关卡 section 与原版地形共用同一套 section mesh 与 uber vertex / index buffer，编译结果由 `CompileTask` 正常入列。
- `mixin/sublevel_render/impl/vanilla/LevelRendererMixin` 在 `compileSections` 的 `TAIL` 触发各子关卡的 section 编译。
- `prepareChunkRenders` / `prepareChunkRendersIndirect` 的 `RETURN` 处，`SubLevelBatchedTerrainRenderer.build(...)` 为每个可见子关卡构造一个自带 `TerrainUniform` UBO 的 `ChunkSectionsToRender.DrawSeparate`。
- `mixin/sublevel_render/impl/vanilla/ChunkSectionsToRenderMixin` 在原版 `renderGroup` / `renderOit` 的 `TAIL` 把这些 group 画出来（带重入保护）。这样实心、经典半透明与 OIT 三条路径都会自动拾取子关卡，且画在原版地形之后、特征之前，无需各自适配。
- **UBO 必须用自己的 `DynamicGpuData` 实例**：`RenderSystem.getDynamicUniforms()` 是与原版共享的，原版在构造绘制组时把 `writeChunkSections` 返回的 slice 存进 `DrawIndirect` 并在渲染阶段绑到 slot 1，我们若再写一次会触发 ring buffer 重分配、把原版 slice 所属的旧 buffer 挪进待关闭列表，导致原版崩溃（`Vertex buffer at slot 1 has been closed!`）。`SubLevelBatchedTerrainRenderer` 因此持有私有的 `DynamicGpuData`，每帧 `build()` 开头 `reset()` 回收上一帧的空间，扩容只会波及自己。

**变换如何进入 GPU**：`terrain.vsh` 算的是 `pos = Position + (ChunkPosition − CameraBlockPos) + CameraOffset`，而 `CameraBlockPos = floor(camera)`、`CameraOffset = CameraBlockPos − camera`（见 `GlobalSettingsUniform.update`），所以 `pos = 世界坐标 − 相机`；原版只需在 UBO 里给一个纯视图旋转矩阵。子关卡改写为 `viewRotation · T(position − camera) · R · S · T(camera − rotationPoint − B)`，并把每个 section 原点按整数向量 `B = round(position − rotationPoint)` 偏移（写进 `ChunkSectionInfo`）。代入后恰好得到 `position + R·S·(p − rotationPoint) − camera`，与逐方块路径完全一致，同时让 `pos` 保持小量级 —— 浮点精度和雾效距离因此都正确。子关卡位姿为恒等时整个式子退化为原版行为。

#### 回退路径（逐方块）

- mesh 尚未编译 / 上传的 section（刚加载或正在重建）由 `submitTransientBlocks` 的 `TAIL` 逐方块提交，避免方块短暂缺失：
  `SubmitNodeCollector.submitMovingBlock(poseStack, state, 0)`（原版活塞移动方块的路径）。
  这条路经支持完整 `PoseStack`，因此旋转 / 缩放 / 非整数位置都能正确处理。
- 提交时的 pose 为：`T(position - camera) · R · S · T(blockPos - rotationPoint)`，与方块轮廓 (`submitBlockOutline`) 的写法一致。
- 方块状态通过 `SubLevelMovingBlockRenderState` 提供，其 `getBlockState` 读取 **真实 plot 邻居**，用于面剔除 / AO。

> **面剔除**：vanilla 的 `MovingBlockFeatureRenderer` 把 `cull` 硬编码为 `false`，且 Fabric `fabric-renderer-api-v1` 会把 moving-block 渲染交给 Indigo 的 `AltModelBlockRendererImpl`。因此这里通过 `mixin/sublevel_render/AltModelBlockRendererImplMixin` 强制 Indigo 的 moving-block 渲染 `cull = true`，配合 `SubLevelMovingBlockRenderState` 读取真实邻居来实现面剔除。批量路径的面剔除由 mesh 编译器天然完成。

**半透明排序**：plot section 的 `SectionPos` 落在子关卡自己的网格上而非世界网格，所以 `mixin/sublevel_render/RenderSectionMixin` 拦截 `createVertexSorting`，先用子关卡位姿的逆变换把相机映射进 plot 坐标空间再算排序键。编译与 `resortTransparency` 都走这一个入口，因此无需去共享 dispatcher 的相机位置（那会在渲染线程与 worker 线程之间产生竞态）。含半透明几何的可见 section 每 500 ms 请求一次重排（原版是每帧；这里做节流以免把开销加回这条路径）。

### 5.2 剔除（`SableClientConfig`）

每帧从配置读取开关（因此改动即时生效）：

- **区块遮挡剔除**（`sub_level_occlusion_culling`）：从相机所在 section 出发做 BFS，只保留能通过“非全不透明面”到达的 section。
- **封闭方块剔除**（`sub_level_cull_enclosed_blocks`）：跳过被不透明邻居完全包住的方块（仅回退的逐方块路径用得上；批量路径的 mesh 编译器本身就按可见面生成几何）。
- **距离剔除**（`sub_level_render_distance`，`-1` 关闭）。
- **视锥剔除**：用相机 frustum 剔除 section。

遮挡 BFS 要扫遍已加载 section 并对每个方向取一整面 16×16 判定，因此**每帧只算一次**，由提交阶段算、批量构建阶段复用（`SubLevelBatchedTerrainRenderer.beginSubmitFrame()` 开帧，`FRAME_VISIBLE_SECTIONS` 缓存）。共享结果同时保证两个阶段对「哪些 section 可见」的答案一致 —— 这一点是硬要求：提交阶段跳过的正是批量阶段要画的那些 section。

### 5.3 方块轮廓 / 裂纹 / 音效

- **方块轮廓**：`submitBlockOutline` 被接管，按子关卡位姿绘制（`sable$submitSubLevelBlockOutline`）。
- **破坏裂纹**（`mixin/block_decal_render/`）：
  - `LevelExtractorMixin` 重写 `BlockPos.distToCenterSqr`，让 plot 坐标的破坏状态不被距离剔除。
  - `LevelRendererMixin` 在 `submitBlockDestroyAnimation` 中把裂纹的 pose 从 plot 坐标搬到子关卡世界坐标。
  - 关键点：`submitBreakingBlockModel(..., boolean)` 的最后一个参数决定裂纹进 `solid` 还是 `breakingOverlay` 阶段。普通方块进 `solid` 没问题，但子关卡方块同样在 `solid`（移动方块 renderer 排在方块模型 renderer 之后），会把裂纹盖住，因此对子关卡方块强制置 `true` 让它进 `breakingOverlay`。
  - `ServerLevelMixin` 放宽 `destroyBlockProgress` 的 1024 距离，使破坏进度包能发给远处（plot 坐标）的玩家。
- **音效**（`mixin/sublevel_sounds/`）：服务端把 plot 坐标上的方块音效投影到子关卡世界坐标并广播；客户端抑制 plot 坐标的重复播放。

### 5.4 渲染相关配置项

`SableClientConfig` 中还保留了 `sub_level_dynamic_shading`、`sub_level_water_occlusion`、`sub_level_skylight_shadows` 等旧开关以兼容既有配置文件，但当前移植分支中这些 Veil shader 特性已被剥离，仅 `sub_level_renderer` 等少数开关真正生效。

## 6. Mixin 布局

- 配置位于 `src/main/resources/sable.mixins.json` 与 `sable-fabric.mixins.json`。
- `AbstractSableMixinPlugin` 按环境选择要加载的渲染 Mixin（启动日志会打印 `Using Vanilla renderer mixins`）。
- **`compatibilityLevel` 必须为 `JAVA_25`**。若为 `JAVA_21`，Mixin 会静默跳过大量 class file version 69 的目标（曾一次跳过约 179 个 Mixin，造成音效、渲染等“莫名失效”）。
- `sable.classtweaker` 提供访问拓宽。

因 26.3 的 render-state 重写被剥离的文件（见 `build.gradle` 的 `exclude`）：Sodium 兼容层、旧 debug 线框渲染、Veil/UDP 相关等。

## 7. 持久化

- 子关卡按维度保存在 `SubLevelHoldingChunkMap` 中，key 必须是 **chunk 坐标**。
  > 早期移植版本这里误用 block 坐标当 chunk 坐标，导致旧存档里的子关卡落到错误的远处区块；修复后新数据正确，但**修复前保存的子关卡需要手动迁移**（见 `docs/port-notes.md`）。
- 保存 / 加载会打印 `Saving sub-levels for level '...'` 日志。

## 8. 客户端入口

- `SableFabricClient`：
  - 注册配置与配置界面（`ModMenu` 通过 `ConfigScreenFactoryRegistry` 列出）。
  - 注册客户端数据包、事件、资源重载监听。
- `SableFabric`：注册命令、属性、数据包、公共配置（`SableConfig.SPEC`）。

---

## 常见改造点速查

| 想改的东西 | 去看 |
| --- | --- |
| 子关卡方块渲染 / 剔除 | `mixin/sublevel_render/impl/vanilla/LevelRendererMixin.java`、`AltModelBlockRendererImplMixin.java`、`SableClientConfig.java` |
| 方块轮廓 | `sublevel_render/impl/vanilla/LevelRendererMixin.java#sable$submitSubLevelBlockOutline` |
| 破坏裂纹 | `mixin/block_decal_render/` |
| 挖掘 / 放置 / 挥手 / punch | `mixin/punching/`、`mixin/plot/LevelMixin.java`、`mixin/block_placement/` |
| 音效 | `mixin/sublevel_sounds/` |
| 同步 / 快照 / 插值 | `sublevel/system/SubLevelTrackingSystem.java`、`network/` |
| 持久化 / chunk 坐标 | `sublevel/storage/holding/SubLevelHoldingChunkMap.java` |
| 物理 | `sublevel/system/SubLevelPhysicsSystem.java`、`physics/impl/rapier/` |
