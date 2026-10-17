# SubLevel API 参考

本文列出当前移植分支中**所有可用于操作 sub-level 的可调用方法**，按包分组。
阅读前请先看 [`architecture.md`](architecture.md)（概念与坐标系统）与 [`creating-a-physics-sublevel.md`](creating-a-physics-sublevel.md)（上手示例）。

> 所有类都在 `dev.ryanhcode.sable` 包下，下文省略该前缀。
> 标注 **Internal** 的方法为内部实现（`@ApiStatus.Internal`），可能随时变动。

---

## 0. 快速开始

```java
ServerSubLevelContainer c = SubLevelContainer.getContainer(serverLevel);
Pose3d pose = new Pose3d();
pose.position().set(x, y, z);

ServerSubLevel sl = (ServerSubLevel) c.allocateNewSubLevel(pose);
LevelPlot plot = sl.getPlot();
plot.newEmptyChunk(plot.getCenterChunk());                                     // 1. 先建区块
plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), 3); // 2. 写方块
sl.updateLastPose();                                                           // 3. 刷新位姿/包围盒
c.physicsSystem().getPipeline().teleport(sl, new Vector3d(x, y, z), pose.orientation()); // 4. 物理化
```

---

## 1. 入口：获取容器 `api/sublevel/SubLevelContainer`

| 签名 | 说明 |
|---|---|
| `static @Nullable SubLevelContainer getContainer(Level)` | 取维度容器，未接入 Sable 时为 null |
| `static @Nullable ServerSubLevelContainer getContainer(ServerLevel)` | 服务端容器 |
| `static @Nullable ClientSubLevelContainer getContainer(ClientLevel)` | 客户端容器 |

---

## 2. 容器层 `api/sublevel/SubLevelContainer`

### 2.1 创建

| 方法 | 说明 |
|---|---|
| `SubLevel allocateNewSubLevel(Pose3d pose)` | 在第一个空闲 plot 上创建子关卡（UUID 随机）。无空位抛 `IllegalStateException` |
| `SubLevel allocateSubLevel(UUID uuid, int x, int z, Pose3d pose)` | 按指定本地 plot 坐标与 UUID 创建。坐标越界或已占用抛 `IllegalArgumentException` |
| `protected abstract SubLevel createSubLevel(int globalPlotX, int globalPlotZ, Pose3d pose, UUID uuid)` | 工厂方法（子类实现） |

### 2.2 查找

| 方法 | 说明 |
|---|---|
| `@Nullable SubLevel getSubLevel(int x, int z)` | 本地 plot 坐标查询 |
| `@Nullable SubLevel getSubLevel(UUID uuid)` | UUID 查询 |
| `@Nullable LevelPlot getPlot(int chunkX, int chunkZ)` | 全局 chunk 坐标 → plot |
| `@Nullable LevelPlot getPlot(ChunkPos pos)` | 同上 |
| `@Nullable LevelChunk getChunk(ChunkPos pos)` | 全局 chunk 坐标 → 区块 |
| `@Nullable PlotChunkHolder getChunkHolder(ChunkPos pos)` | 全局 chunk 坐标 → 区块持有者 |
| `List<? extends SubLevel> getAllSubLevels()` | 所有已加载子关卡 |
| `int getLoadedCount()` | 已加载数量 |
| `Iterable<SubLevel> queryIntersecting(BoundingBox3dc bounds)` | 包围盒相交查询 |
| `List<ServerPlayer> getPlayersTracking(ChunkPos chunkPos)` | 正在追踪该区块的玩家 |
| `boolean inBounds(ChunkPos / BlockPos / Vector3dc / (int x, int z))` | 是否在 plotgrid 范围内 |
| `int getIndex(int x, int z)` **Internal** | plot 坐标 → 数组下标 |
| `Vector2i getOrigin()` | plotgrid 原点 |
| `int getLogPlotSize()` / `int getLogSideLength()` | plot 边长 / grid 边长的 log2 |
| `Level getLevel()` | 所属维度 |

### 2.3 删除与生命周期

| 方法 | 说明 |
|---|---|
| `void removeSubLevel(SubLevel subLevel, SubLevelRemovalReason reason)` | 移除子关卡 |
| `void removeSubLevel(int x, int z, SubLevelRemovalReason reason)` | 按 plot 坐标移除 |
| `void tick()` | 每 tick 调用：遍历快照 `tick()` + `processSubLevelRemovals()` + 通知 observer |
| `void processSubLevelRemovals()` | 处理排队移除；`MassTracker.isInvalid()`（质量为 0）的会被自动 `destroyAllBlocks()` + `markRemoved()` |
| `void addObserver(SubLevelObserver observer)` | 注册观察者 |
| `void newPopulatedChunk(ChunkPos pos, LevelChunk chunk)` | 向 plot 写入一个已填充区块（重复写抛异常） |
| `BitSet getOccupancy()` **Internal** | plot 占用位图（含已卸载） |

**`SubLevelRemovalReason`**（`sublevel/storage/`）：

- `REMOVED` — 真正删除：清 occupancy、删实体、排队删除持久化数据
- `UNLOADED` — 卸载：保留 occupancy 与票据（用于世界卸载/存档）

---

## 3. 容器子类

### 3.1 `api/sublevel/ServerSubLevelContainer`

| 方法 | 说明 |
|---|---|
| `void initialize()` | 初始化（创建 holding chunk map、加载强加载子关卡） |
| `void tick()` | super + `holdingChunkMap.processChanges()` |
| `@NotNull SubLevelPhysicsSystem physicsSystem()` | 物理系统（未注入时断言失败） |
| `@NotNull SubLevelTrackingSystem trackingSystem()` | 追踪/同步系统 |
| `SubLevelHoldingChunkMap getHoldingChunkMap()` | 持久化 chunk map |
| `<T> boolean addForceLoadTicket(ServerSubLevel, SubLevelLoadingTicketType<T>, T key)` | 添加强加载票据（同 key 不可重复） |
| `<T> boolean removeForceLoadTicket(ServerSubLevel, SubLevelLoadingTicketType<T>, T key)` | 移除强加载票据 |
| `Collection<ServerSubLevel> collectForceLoadedSubLevels()` | 收集强加载（含依赖链）的子关卡 |
| `List<ServerSubLevel> getAllSubLevels()` | 强转后的列表 |
| `ServerLevel getLevel()` | 强转后的维度 |
| `void takePhysicsSystem(SubLevelPhysicsSystem)` **Internal** | 注入物理系统 |
| `void takeTrackingSystem(SubLevelTrackingSystem)` **Internal** | 注入追踪系统 |
| `void loadTickets(Object2ObjectMap<UUID, SubLevelTicketInfo>)` **Internal** | 读档票据 |
| `Map<UUID, SubLevelTicketInfo> getAllTickets()` **Internal** | 票据只读视图 |
| `void close()` **Internal** | 释放全部原生资源（世界关闭时） |

### 3.2 `api/sublevel/ClientSubLevelContainer`

| 方法 | 说明 |
|---|---|
| `void tick()` | 插值状态 tick + super |
| `List<ClientSubLevel> getAllSubLevels()` | 强转后的列表 |
| `ClientLevel getLevel()` | 强转后的维度 |
| `ClientSableInterpolationState getInterpolation()` | 快照插值状态 |
| `int getLightingSceneId(ClientSubLevel)` | 分配光照场景 ID（Flywheel 用） |
| `void freeLightingScene(int id)` **Internal** | 释放光照场景 ID |
| `void addDebugInfo(Consumer<String>)` **Internal** | F3 调试信息 |

---

## 4. SubLevel 实例

### 4.1 `sublevel/SubLevel`（抽象基类）

| 方法 | 说明 |
|---|---|
| `Pose3d logicalPose()` | 当前逻辑位姿，可直接改 `position()/orientation()/scale()/rotationPoint()` |
| `Pose3dc lastPose()` | 上一 tick 位姿 |
| `BoundingBox3dc boundingBox()` | 世界包围盒 |
| `LevelPlot getPlot()` | 取 plot |
| `Level getLevel()` | 父维度 |
| `void updateLastPose()` | lastPose ← pose（**写完方块后必须调用**） |
| `void updateBoundingBox()` | 依 plot bounds + pose 重算包围盒 |
| `void onPlotBoundsChanged()` | plot 边界变化回调 |
| `void tick()` | 每 tick 调用（内部 `plot.tick()`） |
| `boolean isRemoved()` / `void markRemoved()` | 查询 / 标记待移除（容器下个 tick 处理） |
| `void onRemove()` **Internal** | 移除流程（plot.onRemove + markRemoved） |
| `UUID getUniqueId()` / `void setUniqueId(UUID)` **Internal** | 唯一 ID（客户端/服务端一致、跨存档） |
| `@Nullable String getName()` / `void setName(String)` | 显示名（null 表示无名） |

### 4.2 `sublevel/ServerSubLevel`（实现 `PhysicsPipelineBody`）

| 方法 | 说明 |
|---|---|
| `ServerLevel getLevel()` / `ServerLevelPlot getPlot()` | 强转返回 |
| `Collection<UUID> getTrackingPlayers()` | 追踪该子关卡的玩家 UUID |
| `SablePacketSink playerSink()` | 给追踪玩家发包的 sink |
| `Pose3d lastNetworkedPose()` / `BoundingBox3i lastNetworkedBoundingBox()` | 上次网络同步的位姿/包围盒 |
| `boolean getLastNetworkedStopped()` / `void setLastNetworkedStopped(boolean)` | 网络同步的“已停止”标志 |
| `int getRuntimeId()` | 物理引擎运行时 ID |
| `SubLevelHeatMapManager getHeatMapManager()` | 热力图管理器（拆分判定） |
| `FloatingBlockController getFloatingBlockController()` | 浮动方块控制器 |
| `ReactionWheelManager getReactionWheelManager()` | 反作用轮管理器 |
| `MassData getMassTracker()` | 合并后质量数据 |
| `MassTracker getSelfMassTracker()` | 自身质量追踪 |
| `void buildMassTracker()` | 重建质量追踪 |
| `void updateMergedMassData(float partialPhysicsTick)` | 更新合并质量数据 |
| `void setSplitFrom(ServerSubLevel containingSubLevel, Pose3d originalPose)` | 记录拆分来源 |
| `UUID getSplitFromSubLevel()` / `Pose3d getSplitFromPose()` / `void clearSplitFrom()` | 读取 / 清除拆分来源 |
| `QueuedForceGroup getOrCreateQueuedForceGroup(ForceGroup)` | 取/建排队力组 |
| `void enableIndividualQueuedForcesTracking(boolean)` / `boolean isTrackingIndividualQueuedForces()` | 逐力跟踪开关 |
| `@Nullable Object2ObjectMap<ForceGroup, QueuedForceGroup> getQueuedForceGroups()` | 排压力组视图 |
| `@Nullable CompoundTag getUserDataTag()` / `void setUserDataTag(CompoundTag)` | 自定义 NBT（持久化） |
| `GlobalSavedSubLevelPointer getLastSerializationPointer()` / `void setLastSerializationPointer(...)` | 上次序列化位置指针 |
| `void deleteAllEntities()` | 删除内部所有实体 |
| `void onPlotBoundsChanged()` / `void tick()` | 覆写实现 |
| `void prePhysicsTickBegin()` / `prePhysicsTick(SubLevelPhysicsSystem, RigidBodyHandle, double)` | 物理 tick 回调 |
| `void applyQueuedForces(SubLevelPhysicsSystem, RigidBodyHandle, double)` | 施加排队力 |
| `@ApiStatus.Internal`：`createPlot(...)`、`onPlotBoundsChanged()` | 内部 |

### 4.3 `sublevel/ClientSubLevel`

| 方法 | 说明 |
|---|---|
| `Pose3dc renderPose()` | 当前渲染位姿 |
| `Pose3dc renderPose(float pt)` | 插值后的渲染位姿 |
| `ClientLevel getLevel()` / `ClientLevelPlot getPlot()` | 强转返回 |
| `BoundingBox3dc boundingBox()` | 覆写（含插值） |
| `void tick()` / `void forceUpdateBounds()` | tick / 强制刷新边界 |
| `SubLevelRenderData getRenderData()` / `void updateRenderData()` | 渲染数据 |
| `SubLevelSnapshotInterpolator getInterpolator()` | 快照插值器 |
| `void receiveServerMovementStop()` | 收到服务端“停止移动” |
| `void wasSplitFrom(ClientSableInterpolationState, ClientSubLevel splitFrom, Pose3dc pose)` | 拆分动画起始 |
| `void setInitialPosesFrom(ClientSableInterpolationState)` | 初始化插值位姿 |
| `void setLightingSceneId(int)` / `int getLightingSceneId()` | 光照场景 ID |
| `boolean isFinalized()` / `void setFinalized()` | 是否已完成创建 |
| `int scaleSkyLight(int)` / `int scaleLightColor(int)` / `int getLatestSkyLightScale()` | 光照缩放 |
| `int computeSubLevelSkyLight(Pose3dc)` | 计算天空光 |
| `void onPlotBoundsChanged()` / `void onRemove()` | 覆写实现 |

---

## 5. Plot（方块读写）

### 5.1 `sublevel/plot/LevelPlot`

| 方法 | 说明 |
|---|---|
| `void newEmptyChunk(ChunkPos pos)` | 创建空区块（**写方块前必须**，pos 为 plot 局部 chunk 坐标） |
| `void ensureChunkAt(ChunkPos globalChunk)` | 确保全局 chunk 存在 |
| `void ensureChunkFor(BlockPos globalBlockPos)` | 确保该方块所在 chunk 存在 |
| `void expandIfNecessary(BlockPos blockPos)` | plot 边界自动扩展 |
| `EmbeddedPlotLevelAccessor getEmbeddedLevelAccessor()` | plot 内的 Level 访问器（方块读写入口） |
| `LevelChunk getChunk(ChunkPos local)` | 局部 chunk 坐标 → 区块 |
| `@Nullable PlotChunkHolder getChunkHolder(ChunkPos local)` | 局部 chunk 坐标 → holder |
| `Collection<PlotChunkHolder> getLoadedChunks()` | 已加载区块 |
| `ChunkPos toLocal(ChunkPos global)` / `ChunkPos toGlobal(ChunkPos local)` | 全局 ↔ plot 坐标 |
| `ChunkPos getCenterChunk()` / `BlockPos getCenterBlock()` | plot 中心 |
| `ChunkPos getChunkMin()` / `ChunkPos getChunkMax()` | plot 边界 |
| `boolean contains(double x, double z)` / `contains(Vec3)` / `contains(Vector3dc)` / `contains(ChunkPos)` | 包含判定 |
| `BoundingBox3ic getBoundingBox()` / `void setBoundingBox(BoundingBox3ic)` / `void updateBoundingBox()` | plot 包围盒 |
| `SubLevel getSubLevel()` | 所属子关卡 |
| `abstract LevelLightEngine getLightEngine()` | 光照引擎 |
| `Iterable<BlockEntitySubLevelActor> getBlockEntityActors()` | 动力学方块实体 |
| `Collection<BlockEntitySubLevelReactionWheel> getBlockEntityReactionWheels()` | 反作用轮方块实体 |
| `Set<Map.Entry<BlockPos, BlockEntitySubLevelReactionWheel>> getBlockEntityReactionWheelMap()` | 同上（带位置） |
| `void onBlockChange(BlockPos, BlockState)` | 方块变化回调 |
| `void tick()` / `void onRemove()` | 生命周期 |
| `protected void newChunk(ChunkPos, LevelChunk, boolean initializeLighting)` | 内部建区块 |
| `protected abstract void onRemoveChunkHolder(LevelChunk)` | 区块移除回调 |
| `int sable$getRenderRevision(long sectionNode)` | 渲染版本号 |

### 5.2 `sublevel/plot/ServerLevelPlot`

| 方法 | 说明 |
|---|---|
| `void addContraption(KinematicContraption)` / `void removeContraption(...)` / `Collection<KinematicContraption> getContraptions()` | 动力学机构 |
| `void setBiome(ResourceKey<Biome>)` | 设置 plot 生物群系 |
| `void kickAllEntities()` | 把实体踢回主世界 |
| `void destroyAllBlocks()` | 销毁所有方块 |
| `CompoundTag save()` / `void load(CompoundTag)` | 序列化 / 反序列化 |
| `ServerSubLevel getSubLevel()` | 强转返回 |
| `ObjectCollection<BlockSubLevelLiftProvider.LiftProviderContext> getLiftProviders()` | 升力提供者 |
| `void addChunkHolder(ChunkPos, PlotChunkHolder, boolean)` / `void tick()` / `void onBlockChange(...)` | 覆写实现 |

### 5.3 `sublevel/plot/EmbeddedPlotLevelAccessor`

实现 `CommonLevelAccessor, ServerLevelAccessor`。所有坐标都是 **plot 局部坐标**（相对 plot 中心，`BlockPos.ZERO` 即中心），内部自动 `offset(center)`：

`getBlockState`、`setBlock(BlockPos, BlockState, int flags, int maxUpdateDepth)`（另有 3 参 `setBlock(pos, state, flags)` 接口默认重载）、`getBlockEntity`、`getFluidState`、`getEntities(...)`、`players()`、`getChunk(i, j, status, force)`、`hasChunk(i, j)`、`getLightEngine()`、`getWorldBorder()`、`getBlockTicks()`、`getFluidTicks()`、`getLevelData()`、`getCurrentDifficultyAt(BlockPos)`、`getServer()`、`getChunkSource()`、`nextSubTickCount()`、`getRandom()` 等。

---

## 6. 物理

### 6.1 `api/physics/PhysicsPipeline`（经 `container.physicsSystem().getPipeline()`）

| 方法 | 说明 |
|---|---|
| `void init(Vector3dc gravity, double universalDrag)` | 初始化 |
| `void dispose()` | 释放资源 |
| `void prePhysicsTicks()` / `physicsTick(double timeStep)` / `postPhysicsTicks()` | 物理 tick（每游戏 tick 多个子步） |
| `void tick()` | 独立数据 tick（物理暂停时也会执行） |
| `void add(ServerSubLevel, Pose3dc pose)` / `void remove(ServerSubLevel)` | 注册 / 注销刚体 |
| `void add(KinematicContraption)` / `void remove(KinematicContraption)` | 动力学机构 |
| `void teleport(PhysicsPipelineBody body, Vector3dc position, Quaterniondc orientation)` | 传送刚体（也是“物理化”的入口） |
| `void applyImpulse(PhysicsPipelineBody body, Vector3dc position, Vector3dc force)` | 在 plot 位置施加冲量 [N] |
| `void applyLinearAndAngularImpulse(body, Vector3dc force, Vector3dc torque, boolean wakeUp)` | 局部力 + 力矩 |
| `void addLinearAndAngularVelocity(body, Vector3dc linVel, Vector3dc angVel)` | 叠加速度（默认空实现） |
| `void resetVelocity(body)` | 速度归零（默认实现） |
| `Vector3d getLinearVelocity(body, Vector3d dest)` / `Vector3d getAngularVelocity(body, Vector3d dest)` | 取速度 [m/s]、角速度 [rad/s] |
| `void wakeUp(body)` | 唤醒休眠刚体 |
| `Pose3d readPose(ServerSubLevel, Pose3d dest)` | 读取物理引擎中的当前位姿 |
| `@Nullable <T extends PhysicsConstraintHandle> T addConstraint(bodyA, bodyB, PhysicsConstraintConfiguration<T>)` | 添加约束（body 为 null 表示固定到世界） |
| `RopeHandle addRope(RopePhysicsObject)` / `BoxHandle addBox(BoxPhysicsObject)` | 绳 / 盒对象 |
| `void handleChunkSectionAddition(LevelChunkSection, x, y, z, boolean)` / `handleChunkSectionRemoval(x, y, z)` | 区块 section 增删 |
| `void handleBlockChange(SectionPos, LevelChunkSection, x, y, z, oldState, newState)` | 方块变化（仅服务端） |
| `void onStatsChanged(ServerSubLevel)` | 质量/质心/包围盒变化后重传 |
| `void updateConfigFrom(PhysicsConfigData)` | 热更新物理配置 |
| `int getNextRuntimeID()` | 分配运行时 ID |

### 6.2 `api/physics/PhysicsPipelineBody`（接口，`ServerSubLevel` 已实现）

`int getRuntimeId()`、`MassData getMassTracker()`、`boolean isRemoved()`

### 6.3 `sublevel/system/SubLevelPhysicsSystem`（`container.physicsSystem()`）

| 方法 | 说明 |
|---|---|
| `static SubLevelPhysicsSystem get(Level)` / `static @NotNull require(Level)` | 静态获取 |
| `static SubLevelPhysicsSystem getCurrentlySteppingSystem()` | 当前正在步进的系统（物理线程） |
| `PhysicsPipeline getPipeline()` | 取物理管线 |
| `RigidBodyHandle getPhysicsHandle(ServerSubLevel)` | 取刚体句柄 |
| `boolean getPaused()` / `void setPaused(boolean)` | 暂停 / 恢复 |
| `double getPartialPhysicsTick()` | 部分 tick 进度 |
| `PhysicsConfigData getConfig()` / `void onConfigUpdated()` | 配置读取 / 热更新 |
| `void initialize()` | 初始化 |
| `void onSubLevelAdded(SubLevel)` / `void onSubLevelRemoved(SubLevel, SubLevelRemovalReason)` | 生命周期钩子 |
| `void tick(SubLevelContainer)` | 每 tick |
| `void updatePose(ServerSubLevel)` | 把物理位姿写回 |
| `boolean recoverSubLevel(ServerSubLevel)` | 恢复异常子关卡 |
| `boolean tryPunch(UUID player, int cooldownAttempt)` | 拳击/推动子关卡 |
| `void wakeUpObjectsAt(int x, int y, int z)` | 唤醒某位置的刚体 |
| `void handleBlockChange(SectionPos, LevelChunkSection, lx, ly, lz, oldState, newState)` | 方块变化分发 |
| `void updateMassDataFromBlockChange(SubLevel, BlockPos, oldState, newState, boolean notifyPipeline)` | 局部质量更新 |
| `Iterable<SubLevel> queryIntersecting(BoundingBox3dc)` | 相交查询 |
| `Iterable<ArbitraryPhysicsObject> getArbitraryObjects()` / `addObject(...)` / `removeObject(...)` | 任意物理对象 |
| `int getNextRuntimeID()` | 分配 ID |
| `ServerLevel getLevel()` | 所属维度 |
| `PhysicsChunkTicketManager getTicketManager()` | 区块票据管理 |

### 6.4 `sublevel/system/SubLevelTrackingSystem`（`container.trackingSystem()`）

`onSubLevelAdded(SubLevel)`、`onSubLevelRemoved(SubLevel, reason)`、`tick(SubLevelContainer)`、`serverWidePlayerSink(ServerSubLevel)`、`getInterpolationTick()`、`addTrackingPlugin(SubLevelTrackingPlugin)`

### 6.5 质量 `api/physics/mass/`

`MassData`：`getMass()`、`getInverseMass()`、`getInertiaTensor()`、`getInverseInertiaTensor()`、`getCenterOfMass()`

---

## 7. 查询助手 `Sable.HELPER`（`ActiveSableCompanion`）

全局静态入口：`Sable.HELPER`（`ActiveSableCompanion`）。

**归属查询**

```java
@Nullable SubLevel    getContaining(Level, int chunkX, int chunkZ)
@Nullable SubLevel    getContaining(Level, ChunkPos / SectionPos / Vec3i / Position / Vector3dc / double blockX, double blockZ)
@Nullable SubLevel    getContaining(Entity entity)
@Nullable SubLevel    getContaining(BlockEntity blockEntity)
@Nullable ClientSubLevel getContainingClient(同上的各种参数重载)   // 客户端专用
Iterable<SubLevel> getAllIntersecting(Level, BoundingBox3dc bounds)
boolean isInPlotGrid(Level, int chunkX, int chunkZ)
```

**坐标投影与遍历**

```java
Vector3d projectOutOfSubLevel(Level, Vector3dc pos, Vector3d dest)
Vec3     projectOutOfSubLevel(Level, Vec3 / Position pos)
@Nullable <T, S extends SubLevelAccess> T runIncludingSubLevels(Level, origin, boolean shouldCheckOrigin, S subLevel, BiFunction<S, BlockPos, T> converter)
<S extends SubLevelAccess> boolean findIncludingSubLevels(Level, origin, boolean shouldCheckOrigin, S subLevel, BiFunction<S, BlockPos, Boolean> converter)
```

**距离与速度**

```java
double distanceSquaredWithSubLevels(Level, ...)
double rectilinearDistanceWithSubLevels(Level, ...)
Vector3d getVelocity(Level, Vector3dc pos, Vector3d dest)
Vector3d getVelocity(Level, SubLevelAccess subLevel, Vector3dc pos, Vector3d dest)
Vector3d getVelocityRelativeToAir(Level, Vector3dc pos, Vector3d dest)   // Vec3 / Position 重载同样存在
```

**实体相关**

```java
@Nullable SubLevel getTrackingSubLevel(Entity)          // 实体当前站立的子关卡
@Nullable SubLevel getLastTrackingSubLevel(Entity)
@Nullable SubLevel getTrackingOrVehicleSubLevel(Entity)
@Nullable SubLevel getVehicleSubLevel(Entity)
@NotNull Vec3 getEyePositionInterpolated(Entity, float partialTicks)
@NotNull Vector3d getFeetPos(Entity, float distanceDown)
Level getClientLevel()
```

---

## 8. 辅助工具类

### 8.1 `api/SubLevelHelper`

| 方法 | 说明 |
|---|---|
| `static void pushEntityLocal(SubLevel, Entity)` / `popEntityLocal(SubLevel, Entity)` | 把实体坐标切入/切出子关卡局部空间 |
| `static void pushEntityLocal(SubLevel, Entity, EntityAnchorArgument.Anchor)` / `popEntityLocal(...)` | 指定锚点版本 |
| `static boolean pushEntityLocalIfNeeded(SubLevel, Entity)` | 需要时才切 |
| `static void popEntityLocalIfNeeded(SubLevel, Entity, boolean projected)` | 需要时才切回 |
| `static Vector3d getVelocityRelativeToAir(Level, Vector3dc pos, Vector3d dest)` | 相对空气速度 |
| `static void registerWindProvider(BiFunction<Vector3dc, Level, Vector3dc>)` | 注册风力提供者 |
| `static Collection<ServerSubLevel> getLoadingDependencyChain(ServerSubLevel)` | 加载依赖链 |
| `static Collection<SubLevel> getConnectedChain(SubLevel)` | 连接的子关卡链 |

### 8.2 `api/SubLevelAssemblyHelper`（主世界方块 ↔ 子关卡）

| 方法 | 说明 |
|---|---|
| `static ServerSubLevel assembleBlocks(ServerLevel, BlockPos anchor, Iterable<BlockPos> blocks, BoundingBox3ic bounds)` | 把主世界方块组装成子关卡 |
| `static GatherResult gatherConnectedBlocks(BlockPos gatherOrigin, ServerLevel, int maximumBlocksToAssemble, @Nullable FrontierPredicate)` | 收集连通方块 |
| `static void kickFromContainingSubLevel(ServerLevel, ...)` | 把实体从子关卡踢出 |
| `static void moveTrackingPoints(ServerLevel, BoundingBox3ic, ServerSubLevel, AssemblyTransform)` | 移动追踪点 |
| `static void moveOtherStuff(ServerLevel, AssemblyTransform, Iterable<BlockPos>, BoundingBox3ic)` | 移动其它数据 |
| `static void moveBlocks(ServerLevel, AssemblyTransform, Iterable<BlockPos>)` | 移动方块 |
| `record AssemblyTransform(BlockPos anchorPos, ...)`：`apply(Vec3/BlockPos/BlockState)`、`getLevel()`、`getRotation()` | 组装变换 |
| `record GatherResult(@Nullable Set<BlockPos> blocks, int checkedBlocks, @Nullable BoundingBox3i boundingBox, ...)` | 收集结果 |

### 8.3 `api/SubLevelEntityScope`（try-with-resources）

```java
static SubLevelEntityScope at(Level level, BlockPos localPos)     // 按局部坐标确定子关卡
static SubLevelEntityScope forSubLevel(@Nullable SubLevel subLevel)
<T extends Entity> T include(T entity)                              // 记录实体进入子关卡
<T extends Entity> Iterable<T> includeAll(Iterable<T> entities)
boolean isActive()
void close()
```

### 8.4 `api/entity/EntitySubLevelUtil`

`setOldPosNoMovement(Entity)`、`kickEntity(SubLevel, Entity)`、`shouldKick(Entity)`、`getCustomEntityOrientation(Entity, float partialTicks)`、`hasCustomEntityOrientation(Entity)`

### 8.5 `api/particle/ParticleSubLevelKickable`

`sable$shouldKickFromTracking()`、`sable$shouldCollideWithTrackingSubLevel()`

---

## 9. 票据与持久化

### 9.1 `api/sublevel/ticket/`

```java
record SubLevelLoadingTicketType<T>(Identifier name, Codec<T> codec)
    static <T> SubLevelLoadingTicketType<T> create(Identifier, Codec<T>)   // 注册自定义票据类型
    static @Nullable SubLevelLoadingTicketType<?> byName(Identifier)
    static final SubLevelLoadingTicketType<Unit> COMMAND_FORCED           // 内置：命令强制加载

final class SubLevelLoadingTicket<T>            // 不可变票据（type + 子关卡 UUID + key）
    SubLevelLoadingTicket(SubLevelLoadingTicketType<T> type, UUID subLevelId, T key)
    SubLevelLoadingTicketType<T> getType() / T getKey() / UUID getSubLevelId()

@ApiStatus.Internal class SubLevelTicketInfo    // 某子关卡的票据集合 + 序列化指针
    SubLevelTicketInfo() / SubLevelTicketInfo(@Nullable GlobalSavedSubLevelPointer, ObjectSet<SubLevelLoadingTicket<?>>)
    @Nullable GlobalSavedSubLevelPointer getPointer() / void setPointer(...)
    ObjectSet<SubLevelLoadingTicket<?>> tickets()
```

用法：`container.addForceLoadTicket(subLevel, MyTicketType.MY_KEY, myKey)` / `removeForceLoadTicket(...)`。

### 9.2 持久化

- 子关卡按维度保存在 `sublevel/storage/holding/SubLevelHoldingChunkMap`，key 为 **chunk 坐标**
- 占用表：`sublevel/storage/SubLevelOccupancySavedData`
- 票据：`sublevel/storage/SubLevelTicketsSavedData`
- 拆分来源：`ServerSubLevel.setSplitFrom(...)`，用户数据：`setUserDataTag(CompoundTag)`

---

## 10. 观察者 / 事件 / 追踪插件

### 10.1 `api/sublevel/SubLevelObserver`（`container.addObserver(...)`）

```java
default void onSubLevelAdded(SubLevel subLevel)
default void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason)
default void tick(SubLevelContainer subLevels)
```

### 10.2 事件 `api/event/`（Fabric 侧注册见 `fabric/event/`）

| 接口 | 回调 |
|---|---|
| `SableSubLevelContainerReadyEvent` | `onSubLevelContainerReady(Level, SubLevelContainer)` —— 容器可用 |
| `SablePrePhysicsTickEvent` | `prePhysicsTick(SubLevelPhysicsSystem, double timeStep)` |
| `SablePostPhysicsTickEvent` | `postPhysicsTick(SubLevelPhysicsSystem, double timeStep)` |

注册方式：`SableEventPlatform.onSubLevelContainerReady(event)` 等，Fabric 实现为 `FabricSableSubLevelContainerReadyEvent.EVENT.register(...)`。
容器默认初始化逻辑见 `Sable.defaultSubLevelContainerInitializer(Level, SubLevelContainer)`。

### 10.3 `api/sublevel/SubLevelTrackingPlugin`

```java
Iterable<UUID> neededPlayers();               // 需要插值信息的玩家
void sendTrackingData(int interpolationTick); // 同步数据时回调
```
注册：`container.trackingSystem().addTrackingPlugin(plugin)`

---

## 11. 方块扩展接口 `api/block/`

| 接口 | 方法 | 说明 |
|---|---|---|
| `BlockSubLevelCollisionShape` | `VoxelShape getSubLevelCollisionShape(BlockGetter, BlockState)` | 自定义碰撞形状 |
| `BlockSubLevelDynamicCollider` | `void buildBoxes(VoxelColliderData data)` | 动态碰撞盒（每 tick 变化） |
| `BlockSubLevelCustomCenterOfMass` | `Vector3dc getCenterOfMass(BlockGetter, BlockState)` | 自定义质心 |
| `BlockSubLevelLiftProvider` | `Direction sable$getNormal(BlockState)` | 升力/风力提供者 |
| `BlockEntitySubLevelActor` | （动力学方块实体标记） | 加入力学计算 |
| `BlockEntitySubLevelReactionWheel` | `void sable$getAngularVelocity(Vector3d)`、`BlockState getBlockState()` | 反作用轮 |
| `propeller/BlockEntityPropeller` | `getBlockDirection()`、`getAirflow()`、`getThrust()`、`isActive()`、`getLevel()`、`getBlockPos()` | 螺旋桨数据 |
| `propeller/BlockEntitySubLevelPropellerActor` | `BlockEntityPropeller getPropeller()` | 螺旋桨推力执行者 |
| `BlockWithSubLevelCollisionCallback` | `BlockSubLevelCollisionCallback sable$getCallback()` | 碰撞回调 |
| `physics/callback/BlockSubLevelCollisionCallback` | `CollisionResult sable$onCollision(BlockPos hit, @Nullable BlockPos otherHit, Vector3d impactPos, double impactVel)` | 碰撞回调实现 |
| `BlockSubLevelAssemblyListener` | `void afterMove(ServerLevel origin, ServerLevel resulting, BlockState newState, BlockPos oldPos, BlockPos newPos)` | 组装后通知 |

`api/sublevel/KinematicContraption`（活塞/矿车等外源运动体）：
`sable$getLocalBounds(BoundingBox3i)`、`sable$blockGetter()`、`sable$getMassTracker()`、`sable$getPosition(double pt)`、`sable$getOrientation(double pt)`、`sable$liftProviders()`、`sable$getFloatingClusterContainer()`、`sable$shouldCollide()`、`sable$isValid()`

> 注意：`api/client/SubLevelBlockEntityRenderRegistry` 在当前 `build.gradle` 中被 `exclude`，不可用。

---

## 12. 命令与参数类型

### 12.1 `/sable` 子命令（`command/`）

| 命令 | 说明 |
|---|---|
| `/sable sub_level name set|clear|get <sub_level>` | 名称操作 |
| `/sable sub_level teleport <sub_levels> <destination> [angle]` | 传送子关卡 |
| `/sable sub_level remove <sub_levels>` | 删除子关卡 |
| `/sable info <sub_level>` | 查看信息 |
| 其它 | `joint add/remove`、`assemble`、`physics`、`storage`、`spawn`、`config` |

### 12.2 `api/command/SubLevelArgumentType`

```java
static Collection<ServerSubLevel> getSubLevels(CommandContext<CommandSourceStack>, String name)
static ServerSubLevel getSingleSubLevel(CommandContext<CommandSourceStack>, String name)
static SubLevelArgumentType singleSubLevel()      // 单个
static SubLevelArgumentType subLevels()           // 多个（支持选择器）
static SubLevelArgumentType subLevelsOrLevel()    // 多个或 "static_world"
static void setSuggestions(StringReader, String... / List<String> / List<Pair<String, Component>>)
```

### 12.3 `api/command/SableCommandHelper`

`requireSubLevelContainer(CommandContext|CommandSourceStack)`、`requireSubLevelPhysicsSystem(...)`、`requireSubLevelPhysicsPipeline(ctx)`、`requireNotNull(value, exception)`、`sendSuccessDescribingSubLevels(...)`、`sendSuccessDescribingSubLevelsAtIndex(...)`、`getResultComponentForSublevelCollection(...)`

---

## 13. 数学类型（sable-companion 依赖）

`dev.ryanhcode.sable.companion.math`：

```java
class Pose3d implements Pose3dc
    Pose3d() / Pose3d(Vector3d pos, Quaterniond ori, Vector3d rotationPoint, Vector3d scale) / Pose3d(Pose3dc)
    Pose3d set(Pose3dc) / Pose3d lerp(Pose3dc, double frac)
    Vector3d position() / Quaterniond orientation() / Vector3d rotationPoint() / Vector3d scale()

BoundingBox3d / BoundingBox3i（及对应的 BoundingBox3dc / BoundingBox3ic 接口）
JOMLConversion.toJOML(...)/toMojang(...)
```

`companion.SubLevelAccess` / `ClientSubLevelAccess`：`getUniqueId()`、`getName()`、`logicalPose()`、`lastPose()`、`boundingBox()` 等子关卡通用访问接口。

---

## 14. 常见流程

### 创建并物理化

见 §0。

### 移动

```java
// 方式一：直接传送
pipeline.teleport(subLevel, new Vector3d(x, y, z), orientation);
// 方式二：改逻辑位姿后刷新
subLevel.logicalPose().position().set(x, y, z);
subLevel.updateLastPose();
pipeline.teleport(subLevel, subLevel.logicalPose().position(), subLevel.logicalPose().orientation());
```

### 施力 / 冲量

```java
SubLevelPhysicsSystem phys = container.physicsSystem();
pipeline.applyImpulse(subLevel, new Vector3d(plotX, plotY, plotZ), new Vector3d(0, 10, 0));
pipeline.applyLinearAndAngularImpulse(subLevel, force, torque, true);
pipeline.wakeUp(subLevel);
```

### 删除

```java
subLevel.markRemoved();                                             // 容器下个 tick 自动移除（REMOVED）
container.removeSubLevel(subLevel, SubLevelRemovalReason.UNLOADED); // 卸载但保留占位与票据
```

### 从主世界“抓取”成子关卡

```java
ServerSubLevel sl = SubLevelAssemblyHelper.assembleBlocks(level, anchor, blocks, bounds);
```

### 观察子关卡增删

```java
container.addObserver(new SubLevelObserver() {
    @Override public void onSubLevelAdded(SubLevel sl) { ... }
    @Override public void onSubLevelRemoved(SubLevel sl, SubLevelRemovalReason reason) { ... }
});
```

---

## 15. 注意事项（易踩坑）

1. **坐标系统**：`BlockPos` 常为 plot 坐标（量级 ~2e7），实体位置为世界坐标；plot chunk = `SectionPos.blockToSectionCoord(blockX/Z)`，不要把 block 坐标当 chunk 坐标。
2. **写方块前必须** `plot.newEmptyChunk(...)` / `ensureChunkAt(...)`，否则方块无处可写；plot 会 `expandIfNecessary` 自动扩展。
3. **写完方块调用 `updateLastPose()`**，并走 `pipeline.teleport(...)` 才会注册为刚体。
4. **创建子关卡只能在服务端**（客户端由网络同步自动创建）；`getContainer` 返回 null 说明该 Level 未接入 Sable。
5. `removeSubLevel(..., UNLOADED)` 不清 occupancy，plot 位不会被复用；彻底删除必须用 `REMOVED`。
6. **质量为 0 的子关卡会被自动销毁**（`processSubLevelRemovals()` 中 `MassTracker.isInvalid()` → `destroyAllBlocks()` + `markRemoved()`）。
7. `physicsSystem()` / `trackingSystem()` 在容器未 `initialize()` 时断言失败；请在 `SableSubLevelContainerReadyEvent` 之后使用。
8. 一个游戏 tick 内会执行多个物理子步，需要影响物理世界的逻辑请挂在 `SablePre/PostPhysicsTickEvent` 而非游戏 tick 事件。
9. `@ApiStatus.Internal` 方法请勿依赖，随时可能变化。
