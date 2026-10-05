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

- **子关卡在水里突然自旋 + 飞天（2026-10-05 定位 + Java 侧防护）**：
  - **现象**：3 块木板这类轻结构放进水里，放/挖一个方块（或干等）后原地暴转（ω 冲到 50~277 rad/s），随后被弹射上天（v_y 单窗口跳到 38.7 m/s），空中纯弹道抛落（减速度 ≈ g≈11）。多次复显一致指向浮力。
  - **根因在原生 rust，Java 完全看不到**：`sable_rapier` 的 `buoyancy.rs::compute_buoyancy` 每游戏 tick（`Rapier3D.tick` 里调用，`Rapier3D.step` 每 substep 积分，力保留到 tick 末）先 `reset_forces`，再经 `algo.rs::find_collision_pairs(..., liquid=true)` 取「本体格 × 世界水格」配对，对每对调 `do_float` 加 `+10.5 × volume × overlap` 向上力（作用点在**具体淹没格**上）、`do_drag` 加 `-1.7 × v × vol` 阻力 —— 全程不经过 Java 的 `ForceTotal`，所以力组类诊断（`big force` / `big impulse` 探针）恒为 0。量级：木板 `#sable:light` mass 0.5、volume 默认 1.0 → 单格浮力 10.5 N vs 单格自重 5.5 N（g≈11）；3 块木板自重 16.5 N、全泡水浮力 31.5 N（**1.9 倍**），绕长轴惯量只有 **0.25 kg·m²**，浮力按格点不对称施加 → 持续泵自旋；净升力 + 高速自旋砸接触面（格点线速度上百 m/s）产生接触冲量 → 弹射上天。日志时序（`[sublevel-diag] runaway`，19:05）：ω 16 → 51 → 113 → 277 → 弹射（fluid=3 时 estBuoyancy 31.5 > weight 16.5）→ 出水后 fluid=0 纯弹道。
  - **上游状况**：`buoyancy.rs` / `algo.rs` 自 2026-06-12（`effec5f3` 拆模块）后零改动，上游没有现成修复（换上游新 native 二进制无用）。上游同类未修 issue：[#262 1x1 Poles in water fly away](https://github.com/ryanhcode/sable/issues/262)（open，约 1/3 复现率）、[#410 Freespinning structure in freespinning water is way too energetic](https://github.com/ryanhcode/sable/issues/410)（维护者：*"We hardly handle regular buoyancy"*）。
  - **修复（sable-port Java 侧，`SubLevelPhysicsSystem.applyBuoyancyJumpGuard`）**：每 substep 在 `updatePose` 末尾重新估算原生浮力（遍历 plot bbox，非空气块变换到**世界坐标**后所在格是流体 → `10.5 × sable:volume`，复刻 rust 判据，上限 65536 格），做**浮力跳变防护**：
    - 单 substep 内有效浮力**最多上涨 `max(超出部分一格浮力10.5 N, 自重 mass×g)`**；超过部分按 `Δv = -excess × dt / mass` 在原生 step 后立刻反向抵消（只抵消上升沿，下降沿不管）；`previousEffectiveBuoyancy` 记录抵消后的值 → 效果是给原生浮力加了个**上升速率限制器**，消灭「一 tick 内浮力暴涨」的弹射源。
    - **豁免**（对应「质量无较大变化」）：质量变化 > 0.1 kg（加/挖方块）当 tick 不抵消、直接重新对齐；位姿与上次采样**逐位相同**（睡眠 / 静止，原生没在积分）直接跳过不扫描 —— 静止陆地结构零开销。
    - 每 10 tick 限流打一条 `[sublevel-diag] buoy-guard: ... buoyancy/allowed/excess/deltaVy/mass/v/w`，用于复现验证。
    - **残留风险（只抵消浮力、不碰速度/角速度的代价）**：稳态下不对称浮力矩仍可能缓慢泵自旋；若复显仍能通过「自旋砸地的接触冲量」飞天，下一步需要把防护扩展到浮力矩（抵消角速度增量）——需用户拍板。
  - **探针状态**：`big force` / `big impulse` / `stats changed` 三处探针已随定位完成清除；`runaway` 探针（`SubLevelPhysicsSystem.updatePose`，限流 ≥10 tick/子关卡）**保留一轮**用于验证防护效果，验证通过后与 `buoy-guard` 日志一并清理。改动前快照：`*.pre-buoyguard.bak`（ServerSubLevel / RapierPhysicsPipeline / SubLevelPhysicsSystem / port-notes）与更早的 `*.pre-runawaydiag.bak`。
  - **19:52 会话复验（新 jar）——浮力归因被削弱，转向穿透弹射**：
    - `buoy-guard` **0 次命中**（原生从无单 substep >16.5 N 的浮力跳变）；开场天上转的 `fffbc88d` 是**上一轮飞天结构的存盘残留**（`sub_level_velocity_retained_on_load=0.9`，加载即 y=260、ω=109 下落），本轮并无新飞天，y 最高 68。
    - 反证：`739a17b2`（3 木板）沉到 y=55.5（**穿进池底地形**）后，带着估算 `estBuoyancy=31.5 > weight=16.5` **在水底静止 7 秒**——原生实际浮力若真有 31.5 N 早该窜出水面 → **Java 估算 ≈ 原生实际的 2 倍**（原生按 AABB overlap 半格计、`find_collision_pairs` node 角格漏配），净升力实际接近中性。
    - 能量账：19:54:02→03 半秒内 |v| 2.4→23、|ω| 4.5→23（峰值 93），动能 ≈700 J ≫ 浮力理论上限 ~150 J，且 Δv_z=21 是**水平**的（浮力只有竖直分量）→ 这一脚来自**穿透后接触求解器弹射**，不是浮力。
  - **onset 逐 substep 探针（本轮埋，`captureOnsetRecords` / `dumpOnsetRecords`）**：每个原生 substep 后记录 `{pos, native v/w, Δv, Δw, mass}` 进 80 substep（2 秒）环形缓冲；当**单 substep** `|Δv|>1.0 m/s`（重力一步仅 0.275）或 `|Δw|>1.5 rad/s`（初版 5.0 抓不到放方块后的缓坡自旋，已下调）或 `|v|>30 m/s` 时整段 dump（每子关卡冷却 100 tick），格式 `[sublevel-diag] onset: sub=... t=tick.substep pos= v= w= dv= dw= mass=`。用途：区分「单步大冲量（接触/穿透）」与「多步渐进（力累积）」——0.5 秒限流的 runaway 探针看不见前者的确切时刻。改动前快照 `SubLevelPhysicsSystem.pre-onsetdiag.bak`。**已清除（见 21:40 会话条目）。****bug 已修（20:36 会话）**：`OnsetRecorder.lastDumpTime` 初值 `Long.MIN_VALUE` 使 `gameTime - lastDumpTime` 发生 long 溢出（结果为负）→ `>= 100` 恒假 → 整个会话 **trigger dump 0 次**、飞天瞬间的逐 substep 数据全部漏掉（只有 mass-change 强制 dump）；已改初值为 `-ONSET_DUMP_COOLDOWN_TICKS`，快照 `*.pre-probefix.bak`。
  - **stats-change 探针（放/打方块 → 突然自旋，`MergedMassTracker.uploadData`）**：用户复现「放方块打掉多次后突然旋转」（`d7032974`：mass 1.5→3.5 的同一瞬间 ω 2.4→22.9，随后水阻尼 ~1.5 s 内回落；增长 2→8→44 分散在多个 substep，onset 初版阈值未触发）。已在质心补偿传送（`teleport(t+R·Δcom)`）+ `onStatsChanged` **前后**各读一次原生 pose/v/w，输出 `[sublevel-diag] stats-change: sub= mass=→ comD= shiftExp= shiftGot= v→ w→`（shiftExp≈shiftGot 验证补偿、w 前后不变=非调用内注入=环境力累积），并强制 dump onset 环形缓冲（reason=`mass-change`）。改动前快照 `*.pre-statsdiag.bak`（MergedMassTracker / SubLevelPhysicsSystem）。**已清除（见 21:40 会话条目）。**排查过程中已确认：方块变化路径（`changeBlock` 只改 voxel/octree、`teleportObject` 保速度、`setMassProperties` local_com=ZERO 只换质量、`uploadData` 有质心补偿且符号正确）**没有任何直接速度写入**。
  - **20:36 会话判读：stats-change 路径彻底洗清 + 探针自身假象修正**。1224 个事件中 `v/w` 前后**逐位不变** → 质量更新调用内部无速度注入。曾出现「`shiftGot==comD`（裸位移）≠ `shiftExp`」的假矛盾——实为探针自污染：JOML `Quaterniond.transform(Vector3d)` **原地改写入参**，teleport 那行执行后 `movement` 已被转成 R·Δ，导致日志 `comD` 实为 R·Δ、`shiftExp`（再次旋转）实为 R²·Δ，而 `shiftGot` 才是真正的 R·Δ——**补偿传送本来就是正确的**（已用三个 joml 版本 1.10.4/8/9 实测 + `javap` 字节码核实）。已修：teleport 前快照 `diagComRaw`、日志加 `q=(qx qy qz qw)`、shiftExp 改用原始副本（修后 shiftExp 应≈shiftGot，可离线复验 R·Δ）。同会话飞天实锤：`d7032974` 20:36:28 从 y=57.9、|v|=1.56、|w|=2.13 半秒内飙到 |v|=62.4、ω=52.8，y 一路 263，出水后 ω 52→125 继续涨（非球体自由翻滚下 |ω| 随姿态变化属正常，角动量守恒，非外力矩）；**事发前该结构正在下沉**（onset 记录 y 60.5→58.4、v_y≈−1.9，贴塘底）→ 仍指向「沉穿地形 → 接触求解器弹射」，待 trigger dump（已修溢出）给出逐 substep 形状（单步冲量 vs 多步渐变）最终定罪。另：`293a594c` 的 mass 34.0↔34.5↔35.5↔35.0 四态确定性循环（comD 四向量和为零、每秒数十次，非人手操作），疑似 Create 活动部件/浮块进出统计，与飞天无关，暂记档不追。
  - **21:16 会话判读：溢出修复生效（trigger 6 次）、补偿传送运行时复验通过、native 帧链核查无错位、但弹射脉冲被 100 tick 冷却挡住 → 加极端旁路探针**：
    - 每条 stats-change `shiftExp ≈ shiftGot`（f32 精度内）✓、`v/w` 前后不变 ✓——**质心补偿传送 `t+R·Δcom` 运行时正确**，20:36 的 R·Δ 修正结论在线复验。
    - `23e3eb2a`（1.5 kg）起飞前 80 条 ring 完整：mass 1.5→1.5→3.5（放方块）后 7 tick 内 dv≈0.14/substep、dw≈0.13/substep **匀加速下沉**（≈ weight−真实浮力 ≈ −17.5 N，正常动力学，非玄学力）；打掉方块（3.5→1.5）后 dw 连跳 1.26→2.59 触发，随后数秒内 |v| 到 67.9、bodyW 到 (328, 44, −552)（|ω|≈650 rad/s），弹射中 y 先砸到 56.6 再上 117。**能量账：½·1.5·62² ≈ 2883 J——浮力/摩擦等常规力给不出，只有穿透恢复冲量或求解器爆炸能到这个量级**；弹射前 v_y −9.1 → +8.1 的反弹形状也符合接触。
    - native 帧链逐段核完无错位：`find_collision_pairs` 的 `R·(plot−min)+t+R·(min−com) = t+R·(plot−com)` ✓；`world_vs_world`/`static_world_vs_collider` 的 manifold 构造（static=com₁ 锚、dynamic=com₂ 锚、局部点回加）✓；AABB=com 锚、octree=min 锚但查询时做了 `local_bounds_min−com` 换算 ✓；结构物理帧≈564（塘所在）与 probe 测流体的帧一致 ✓。**接触检测路径本身没问题，嫌疑集中在「下沉穿底后的深度穿透 → 恢复冲量爆炸」的求解阶段。**
    - **卡点**：trigger 冷却 100 tick 恰好挡住弹射脉冲（ring 止于 t=94334，弹射在其后 2-6 秒的冷却窗内，21:16:53 前无再 dump）。已加**极端旁路探针**（`ONSET_EXTREME_SPEED_TRIGGER=12` / `SPIN=30` / `Δv=4`，独立 10 tick 冷却，dump reason=`extreme`，正常时也顺带刷新主冷却）→ 弹射期间每 0.5 s 一发、ring 2 s 首尾重叠 4 倍，可完整覆盖脉冲逐 substep 形状。快照 `*.pre-extremetrigger.bak`。**已清除（见 21:40 会话条目）。**
  - **21:40 会话：极端探针首秀成功但错过脉冲瞬间 → 用户拍板「不修」（2026-10-05），探针全部清除**：
    - 换新 jar 重启（21:40:43 启动）后用户观察到一次跳变；本日志内 `23e3eb2a` 于 21:41:24 首次出现时**已带 47 m/s、|ω|=658 rad/s**（mass 0→1.5 = 重启后 tracker 首次上传）——弹射瞬间发生在当前日志窗口之外（跨重启带速恢复），onset 没能覆盖脉冲本身。
    - extreme 探针本身工作正常：7 连发（每 10 tick、ring 2 s 重叠 4 倍）覆盖飞行中后段 101 条记录（t=94943..94993，y 274→430）：位置/速度积分自洽、dv≈0.38–0.47/substep（≈重力减速）、ω 658→526 缓衰——**弹射是单次冲量、之后无持续力**，与「深度穿透→恢复冲量爆炸」假设一致。
    - **决定：偶发、影响不大、不修（用户拍板 2026-10-05）**。根因假设记档：结构贴塘底放/打方块 → 净浮力向下（估 −17.5 N）持续下沉穿入地形 → 深度穿透 → 接触求解恢复冲量爆炸（能量账 ½·1.5·62²≈2883 J；弹射前 v_y −9.1→+8.1 触底反弹形状；native 帧链已逐段核查无错位；stats-change 已洗清无速度注入）。若将来重追：从 `*.pre-extremetrigger.bak` 恢复 extreme 探针，且须保证弹射落在当前日志窗口内（重启前旧日志会被覆盖）。
    - **全部临时探针已清除**（`stats-change` / onset ring / trigger / extreme / runaway / `buoy-guard` 日志；改动前快照 `*.pre-probecleanup.bak`，SubLevelPhysicsSystem / MergedMassTracker / ServerSubLevel），浮力跳变防护 `applyBuoyancyJumpGuard` 功能保留（全程 0 触发）。

- 修复 `RandomPosMixin` 导致的线上崩溃。
- `IntegratedServerMixin` 把 Toast 操作包进 `minecraft.execute`，修复跨线程崩溃。
- 删除依赖 Create 方块的 schematic（`vostone_2.nbt`、`vinalilime.nbt`）。
- 物理属性选择器指向**未安装的可选模组**时（如 `create:flywheel`）改为 debug 输出，不再把缺失依赖刷成 ERROR；同命名空间下确有方块却查不到时仍报错。
- 移除 `sable.mixins.json` 中指向 `sable.refmap.json` 的声明：26.3 无混淆、该文件本就不生成，之前每次启动都会警告 `Reference map ... could not be read`。

## 5. 已知限制

- **子关卡偶发弹射/自旋飞天——不修（用户拍板 2026-10-05）**：水中贴塘底放/打方块后偶发结构突然自旋并被弹上天（单次冲量可到 60+ m/s、|ω| 数百 rad/s，之后自由飞行无持续力）。证据链与全部排查结论（stats-change 无注入、补偿传送运行时正确、native 帧链无错位、能量账指向深度穿透恢复冲量）见 §4「21:40 会话」条目；根因大概率在 native 接触求解（上游 issue #262 / #410）。偶发、影响不大，**决定不修**；临时诊断探针已全部清除（快照 `*.pre-probecleanup.bak`，复现手法见 §4 21:16 / 21:40 条目，extreme 探针可用 `*.pre-extremetrigger.bak` 恢复）。
- **原生浮力只做了 Java 侧「跳变防护」，不是根修（2026-10-05）**：rust `sable_rapier::buoyancy` 的浮力/阻力完全在 native 内（10.5 N/淹没格、按格点不对称施加），sable-port 只能事后估算并抵消「单 substep 内超过自重的浮力突增」（见 §4 其它）。19:52 复验后归因已修正：**浮力估算高估约 2 倍、净升力接近中性**；防护保留但全程 0 触发。若最终仍需治浮力本体，可扩展到浮力矩抵消或本地重编 native（cargo/lz4 工具链已确认可用）。上游对应 issue #262 / #410。
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
