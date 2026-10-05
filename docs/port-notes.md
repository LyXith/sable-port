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

- **按 section 批量绘制子关卡**（`sub_level_batched_sections`，本次性能优化的主体）：26.3 没有 1.21 那套 per-section 变换矩阵，`ChunkSectionInfo` 只存 section 原点与可见度，所以不能直接沿用旧的「地形管线带子关卡位姿」思路。做法改为给每个子关卡构造一个自带 `TerrainUniform` UBO 的 `ChunkSectionsToRender.DrawSeparate`，并在 `ChunkSectionInfo` 里把 section 原点整体偏移一个整数向量 `B = round(position − rotationPoint)`；UBO 里的矩阵取 `viewRotation · T(position − camera) · R · S · T(camera − rotationPoint − B)`，代入 shader 的 `pos = p − camera` 后正好还原逐方块路径的变换。绘制挂在原版 `renderGroup` / `renderOit` 的 `TAIL`（`ChunkSectionsToRenderMixin`），实心 / 经典半透明 / OIT 三条路径都能自动拾取。原先 140k 方块岛屿每帧要逐方块 `submitMovingBlock`，现在每个子关卡只提交一次。
- **mesh 未编译时的回退**：`isSectionBatchable(...)` 同时被提交阶段与批量构建阶段调用，mesh 就绪就批量绘制，否则退回 `SubmitNodeCollector.submitMovingBlock` 逐方块提交，避免加载时方块缺失或被重复画两次。
- **半透明排序**：plot section 的 `SectionPos` 是子关卡自己的网格坐标，直接拿世界相机算排序键会排错，因此 `RenderSectionMixin` 拦截 `createVertexSorting`，先用位姿逆变换把相机映射进 plot 空间。编译与重排都经此入口，无需共享 dispatcher 的相机位置（避免渲染线程 / worker 线程竞态）。
- **子关卡方块渲染**（回退路径）通过 `SubmitNodeCollector.submitMovingBlock`（原版活塞路径）提交，支持完整位姿 / 旋转 / 非整数位置。
- **面剔除**：vanilla `MovingBlockFeatureRenderer` 把 `cull` 硬编码为 `false`，且 Fabric `fabric-renderer-api-v1` 会把 moving-block 渲染交给 Indigo。因此通过 `AltModelBlockRendererImplMixin` 强制 Indigo 的 moving-block 渲染 `cull = true`，并用 `SubLevelMovingBlockRenderState` 读取真实 plot 邻居。
- **剔除**：`impl.vanilla.LevelRendererMixin` 每帧读取 `SableClientConfig`，实现 section 遮挡 BFS、距离剔除、封闭方块剔除与视锥剔除。
- **进服即断开**（`Updating render data` NPE）：`LevelRenderer` 要到首次 `invalidateCompiledGeometry` 才构造 `SectionRenderDispatcher`，而 `sable:start_tracking_sub_level` 可能更早到达，`createRenderData` 拿到 null 后在 `resize()` 里 NPE，包处理失败并把玩家踢下线；紧接着 `rebuild()` 又因 `getRenderData()` 为 null 二次崩溃。现在 `createRenderData` 在 dispatcher 未就绪时返回 null，`ClientSubLevel.getRenderData()` 惰性重试（已 `isRemoved()` 的不再复活），并给所有 `getRenderData()` 消费点补了判空。
- **破坏裂纹**（`block_decal_render`）：
  1. 这两个 Mixin 曾在 `JAVA_21` 下被跳过；改为 `JAVA_25` 后真正生效。
  2. Mixin 中一个 `@Unique final` 字段的初始化器未被合并，运行到裂纹渲染时空指针崩溃；已改为方法内局部对象。
  3. 关键：`submitBreakingBlockModel(..., boolean)` 的最后一个参数决定裂纹进 `solid` 还是 `breakingOverlay`。普通方块进 `solid` 没问题（地形更早绘制），但子关卡方块也在 `solid` 且移动方块 renderer 排在方块模型 renderer 之后，会把裂纹盖住。已对子关卡方块强制置 `true`，让裂纹进 `breakingOverlay`。修复后挖掘裂纹正常显示。

### Create 兼容（Create-Fly 6.0.9 / 26.3）

Create 把「本维度坐标」当世界坐标用的地方，在子关卡里拿到的都是 **plot 坐标**（~2e7），于是要么被服务端 reach 校验拒绝，要么被画到视锥之外。以下 mixin 全部位于 `dev.ryanhcode.sable.mixin.compatibility.create`，由 `AbstractSableMixinPlugin` 按 `compatibility.<modId>` 包名门控（未装 Create 时整包跳过），注册在 `sable.mixins.json`：

- **无法调节（创造马达转速、速度控制器、过滤器……）**：`AllHandle#onBlockEntityConfiguration` 用 `pos.closerThan(player.blockPosition(), distance)` 做 reach 校验，plot 坐标与世界坐标恒不满足 → `AllHandleMixin` 通过 `@WrapOperation` 换成双方处于同一坐标系的比较（玩家位置经 `transformPositionInverse` 逆变换到 plot 空间）。回调必须是 `private static`（注入目标是 static 方法），且 `@WrapOperation` 实例回调的 `Operation.call` 首参是 receiver。
- **方块侧面的小 UI / 不显示转速**：`ValueBox#submit` 第 82 行 `ms.translate(pos - camera)` 把 plot 坐标当世界坐标 → `ValueBoxMixin` 改为用子关卡 `renderPose` 投影出的世界坐标，并补一个 `mulPose(orientation)`（与 `block_decal_render` 处理破坏裂纹的写法一致）。
- **放置预览虚影不渲染**（齿轮等 ghost）：`GhostBlockRenderer` 的两个实现同样 `translate(pos - camera)` → `GhostBlockRendererMixin` / `TransparentGhostBlockRendererMixin` 同一修法。两个内部类结构一致但**拆成两个单目标 mixin**（sable 里没有多目标 mixin 的先例，不赌 `@Mixin(targets = {A, B})`）。
- **流体储罐液体 / 蓝图加农炮炮体不渲染**：`LevelExtractorMixin` 调 `BlockEntityRenderDispatcher.tryExtractRenderState(be, partialTick, overlay, false)`，第 4 个参数不是「force」而是「**本次只提取哪一类方块实体**」——方法体里有 `if (arg != renderer.shouldRenderOffScreen()) return null;`。原版从可见 section 提取时传 `false`、从 `getGloballyRenderedBlockEntities` 提取时传 `true`。`FluidTankRenderer` / `SchematicannonRenderer`（以及 belt / track / station / pulley / mechanical arm / flap display / chain conveyor / elevator pulley）都把 `shouldRenderOffScreen()` 覆写成 `true`，固定传 `false` 会**一律返回 null**，而原版路径提出来的那份又正好被上面的 `removeIf` 删掉，结果就是整体不渲染。修复：按 renderer 自身的取值传（等价于「两个集合都提取」，sable 本来就是逐个遍历子关卡 BE）。
- **子关卡更新时飞轮旋转角度重置**：sable 只要 plot 区块里**任意一个**方块变了就整块重发（`PlotChunkHolder.networkDirty` → `SubLevelTrackingSystem.sendChunkUpdates` → `ClientboundLevelChunkWithLightPacket`），客户端收到后走 `LevelChunk.replaceWithPacketData`，而它**开头就 `clearAllBlockEntities()`**（javap：offset `13: invokevirtual clearAllBlockEntities:()V`），把区块内所有方块实体销毁重建 —— 于是每个实例上只存在于客户端的私有状态归零。飞轮 `FlywheelBlockEntity.angle` 是**客户端每 tick 自己累加的量**（不进 read/write、`tick()` 里 `if (!level.isClientSide()) return` 使得**服务端那份恒为 0**，所以「把 angle 写进 update tag」这条原设想无效），`visualSpeed` 也是新实例从 0 重新爬升；`MechanicalBearingBlockEntity.angle` 同理，其它动能方块因为旋转是 `renderTime * speed` 的派生量反而不受影响。**这是纯客户端问题。**修复落在 `mixin/plot/LevelChunkMixin`（common 段，**不依赖 Create**）：原版重建回调里本就写了类型一致性检查（`be.getType() == packetType` 才 `loadWithComponents`），所以只要不销毁，原生代码会自动把包里的新数据灌进旧实例 —— `@WrapOperation` 对 plot 区块跳过 `clearAllBlockEntities()`，HEAD 按包内声明剪掉「已不存在/类型已变」的旧 BE，RETURN 把幸存 BE 的 `blockState` 对齐到新状态（原版靠销毁重建保证这一步）。三步合起来后态与原版等价，区别仅在实例身份；顺带 Create 的 `Storage.visuals` 按 BE 对象身份索引，不再 remove+add 抖动。

- **已放置的强力胶区域平时不显示面纹理（按需求改动，非移植 bug）**：`SuperGlueSelectionHandler#tick` 里 `faceTex = h ? AllSpecialTextures.GLUE : null`，只有准星正命中那一帧才把 GLUE 传给 `withFaceTextures(texture, highlightTexture)`，其余时间传 `null`，于是放好的胶只剩 1/64 的细线框；同一段代码里的框选选区却恒传 `GLUE`，两处不一致。改为恒传 `AllSpecialTextures.GLUE`，高亮状态仍只由 `colored(...)` 与 `lineWidth(...)` 区分。此行在 Create-Fly `Initial release` 里就与上游一致，属于按使用者要求改掉的上游行为；改动前备份 `SuperGlueSelectionHandler.pre-gluefacetex.bak`（Create 侧自包含，不 import sable）。

**待修（Create 兼容）**：

- 强力胶 / 锁链传动轮挂锁链触发的 JVM 崩溃（Vulkan）：`PonderRenderTypes.OUTLINE_SOLID` / `OUTLINE_TRANSLUCENT` 标了 `OutlineProperty.IS_OUTLINE`（进无 depth 的 outline pass）却用了带 `depthStencilState` 的管线，`VulkanRenderPipeline.compile` 遇到 `depthStencilState != null` 时不编译 `withoutDepthPipeline`，于是 `vkCmdBindPipeline(cmd, 0, 0)` 空句柄崩溃。计划用 mixin 修在 sable 侧。
- 胶的 AABB / 簇轮廓与锁链的 `showLine` 同样是 `pos - camera`，崩溃修好后它们会「不崩但画到 2e7 外」，需要用同样的 renderPose 投影补上。
- 齿轮放置预览虚影会让**它身后世界里的水面变透明 / 开天窗**：确认为 Create（`GhostBlockRenderer` 走 translucent moving block，原版在 `executeTranslucent` 里先于 `renderGroup(TRANSLUCENT)` 画）自身顺序问题，**决定不修**。与上面「子关卡半透明被世界水挡住」是同一根源的两个方向，将来若做列表归并可以一并覆盖。
- 方案 A（服务端把 `networkDirty` 整块重发改成增量：`ClientboundBlockUpdatePacket` + `ClientboundBlockEntityDataPacket` + `ClientboundLightUpdatePacket`）尚未落地；上面的飞轮修复已经让整块重发不再丢客户端状态，A 属于「根治 + 省流量」。

### 界面

- 通过 Mod Menu 的 `ConfigScreenFactoryRegistry` 注册配置界面；补齐 `sable.configuration.*` 的英文 / 中文翻译。

### 其它

- **子关卡分裂（heatmap split）两处修复（2026-10-05）**：
  - `SubLevelContainer.tick()` / `processSubLevelRemovals()` 改为**快照遍历**：fastutil `ReferenceArrayList.forEach` 沿用旧底层数组却每轮重读 `size`，而 `SubLevel::tick → heatMapManager.split → assembleBlocks → allocateNewSubLevel → allSubLevels.add` 会在遍历中途插入新元素 → `Index N out of bounds for length N`（登录后 1–2 秒必崩）。`processSubLevelRemovals` 另加「槽位仍是它才移除」的守卫，避免重复移除。
  - **分裂出来的新子关卡立刻消失**：`ServerSubLevel.tick()` 是 `super.tick → updateBoundingBox → heatMapManager.tick`，新子关卡的 `globalBounds` 在创建当 tick 还是全 0；同一 tick 稍后 `SubLevelContainer.tick` 的 `observers` 会跑 `physicsSystem.tick → ticketManager.update`，按 bbox 算出的 chunk 是 `(0,0)`，`isChunkLoadedEnough(0,0)` 为 false → `holdingChunkMap.moveToUnloaded()` 把它序列化进 holding chunk 并 `removeSubLevel(UNLOADED)`，世界里那半边方块当场消失（其实被存盘了，玩家看不到）。修复：`SubLevelAssemblyHelper.assembleBlocks` 在 `return` 前补一次 `subLevel.updateBoundingBox()`（与下一 tick 会做的是同一件事）。

- 修复 `RandomPosMixin` 导致的线上崩溃。
- `IntegratedServerMixin` 把 Toast 操作包进 `minecraft.execute`，修复跨线程崩溃。
- 删除依赖 Create 方块的 schematic（`vostone_2.nbt`、`vinalilime.nbt`）。
- 物理属性选择器指向**未安装的可选模组**时（如 `create:flywheel`）改为 debug 输出，不再把缺失依赖刷成 ERROR；同命名空间下确有方块却查不到时仍报错。
- 移除 `sable.mixins.json` 中指向 `sable.refmap.json` 的声明：26.3 无混淆、该文件本就不生成，之前每次启动都会警告 `Reference map ... could not be read`。

## 5. 已知限制

- **无 Sodium 兼容层**：安装 Sodium 时子关卡渲染不会走 Sodium 路径（Sodium 仍可用于原版地形）。
- **子关卡旋转时雾效略有偏差**：批量路径把 `pos` 组织成「未旋转位置 + 整数偏移 B」，雾按这个 `pos` 算距离。恒等位姿下完全准确；子关卡被旋转或缩放时，雾距离与真实渲染位置相差 `(I − R·S)·(p − rotationPoint)`，也就是离旋转中心越远误差越大（未旋转时为 0）。
- **半透明排序刷新为 500 ms 一次**（原版每帧）：快速绕着子关卡走时，水面 / 玻璃的排序最多滞后半秒才追上视角。
- **子关卡的半透明方块沉在世界水里时完全看不见（未修，2026-10-05 记录）**：
  - **现象**：结构里的粘液块 / 蜂蜜块放进**世界自带的水**后，从任何角度、任何距离都稳定看不见（选中框正常，说明方块确实在世界里）；同一个位置放普通石头正常；把粘液块放到岸上正常。粘液 / 蜂蜜 / 玻璃在 26.3 里都由 `FaceBakery.computeMaterialTransparency` 判成 `ChunkSectionLayer.TRANSLUCENT`，和水同一层，所以「实心看得见、半透明看不见」不是分层错误。
  - **根因是绘制**批次**的先后，不是排序键**：经典透明（`improvedTransparency:false`）下原版 `executeClassicTransparency` 的顺序是 `preparedFrame.executeTranslucent`（特征）→ `renderGroup(TRANSLUCENT)`（地形）→ `executeTranslucentAfterTerrain`，而 sable 的子关卡组挂在 `ChunkSectionsToRender.renderGroup` / `renderOit` 的 **TAIL**（`ChunkSectionsToRenderMixin`），于是**整个世界的半透明地形都排在子关卡半透明地形之前**。世界水面先画并写深度，位于其后的子关卡半透明片元全部深度测试失败 → 看不见；实心层在更早的 `executeSolid` 里画，所以水下石头、以及选中框都不受影响；岸上没有世界水挡着，所以正常。
  - **已做、但对本例无效的两处修复**（它们只决定**子关卡组内部**的先后，跨不过「世界组 vs 子关卡组」这道边界）：`SubLevelBatchedTerrainRenderer.sable$sectionDrawOrder`（组内按 plot 相机距离近→远，再交给原版 `reverseTranslucent` 反转成远→近）、`RenderSectionMixin.createVertexSorting` + `CompileTaskMixin` / `ResortTransparencyTaskMixin`（排序键与重排判定统一到 plot 空间）。这几处保留 —— 它们修的是真问题，只是本例的遮挡来自组外。
  - **待选修法**：正解是把 plot section 的半透明 draw **按距离并进原版 `ChunkSectionsToRender` 的半透明列表**（两个已按距离排好序的序列做归并，而不是追加在后面）；退一步可以改成子关卡半透明**先于**原版画，能修好本例，但会反过来让「子关卡玻璃在前、世界水面在后」的场景被深度剔掉（表现为玻璃后面的世界水开天窗），所以不作为默认方案。
- **部分渲染特性失效**：动态着色 / 天光阴影 / 水面遮挡开关为历史遗留，暂不生效。
- **旧存档迁移**：在“chunk 坐标修复”之前保存的子关卡可能位于错误的远处区块（只会在那个错误区块被加载时才出现，不会自动恢复），需要一次性迁移脚本。
- **跨子关卡光照 / 遮挡**未实现。
- 启动时可能看到 `Failed to apply tag physics properties. Unknown block: create:flywheel` —— 这是数据包引用了未安装的 Create 方块标签，与本移植无关。
- `Requested post effect does not exist: minecraft:end_of_frame` 是 26.3 的已知无害告警。

## 6. 调试建议

- **Mixin 没生效？** 先确认 `sable.mixins.json` / `sable-fabric.mixins.json` 的 `compatibilityLevel` 为 `JAVA_25`，再看 `logs/debug.log` 是否打印了对应 `Mixing ...`。
- **子关卡落错位置 / 存错区块？** 检查相关代码是否把 block 坐标当 chunk 坐标（用 `SectionPos.blockToSectionCoord`）。
- **渲染相关开关不生效？** 客户端渲染 Mixin 每帧读取 `SableClientConfig`，确认选项确实被 Mod Menu / `sable-client.toml` 改动。
