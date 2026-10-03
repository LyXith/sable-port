# 创建物理化子关卡（会模拟的方块）

本文说明如何用 Sable 的 API 在服务端创建一个“物理化”的子关卡（sub-level）——即一组会随刚体模拟移动的方块。

> 只能在**服务端**创建；客户端会自动同步并渲染。
> 上游的 `/sable test` 示例命令在本移植中已删除；可参考游戏内 `/sable spawn block <方块>`（`SableSpawnCommands`）或本文示例。

## 最简示例

```java
public static void spawnPhysicsStone(final ServerLevel level,
                                     final double x, final double y, final double z) {
    // 1. 取得（服务端）子关卡容器
    final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
    if (container == null) {
        return;
    }

    // 2. 创建子关卡，并设置它在世界中的位姿
    final Pose3d pose = new Pose3d();
    pose.position().set(x, y, z); // 世界位置

    final ServerSubLevel subLevel = (ServerSubLevel) container.allocateNewSubLevel(pose);

    // 3. 在子关卡的 plot 里写入方块（plot 坐标从 (0,0,0) 起）
    final LevelPlot plot = subLevel.getPlot();
    plot.newEmptyChunk(plot.getCenterChunk()); // 必须先创建区块
    plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), 3);

    // 4. 刷新位姿并交给物理管线 → 成为刚体
    subLevel.updateLastPose();
    container.physicsSystem()
            .getPipeline()
            .teleport(subLevel, new Vector3d(x, y, z), pose.orientation());
}
```

## 要点

- **`allocateNewSubLevel(pose)`**：分配一个新的子关卡，`pose` 决定它在世界中的位置 / 旋转 / 缩放。
- **plot 坐标**：子关卡内部使用独立的 plot 坐标（与主世界隔离）。写入方块前先 `plot.newEmptyChunk(...)`；`BlockPos.ZERO` 就是子关卡中心。
- **跨区块写入**：如果方块超出当前区块，需要先为对应 chunk 坐标创建区块：
  `plot.newEmptyChunk(new ChunkPos(chunkX, chunkZ))`（注意这里是 **chunk** 坐标）。
- **`updateLastPose()`**：写完方块后调用，刷新内部插值/包围盒。
- **物理化**：`container.physicsSystem().getPipeline().teleport(subLevel, position, orientation)`
  会把该子关卡注册为刚体并传送到指定位姿，之后由物理系统持续模拟。
  `teleport` 的第一个参数是 `PhysicsPipelineBody`，`ServerSubLevel` 已实现该接口。
- **位姿**：`Pose3d#position()/orientation()/scale()` 控制世界位姿；`rotationPoint()` 是 plot 内的旋转中心（可选，默认原点）。

## 相关 API

- `dev.ryanhcode.sable.api.sublevel.SubLevelContainer`（`getContainer(ServerLevel)`）
- `dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer`（`allocateNewSubLevel`、`physicsSystem`）
- `dev.ryanhcode.sable.sublevel.SubLevel` / `ServerSubLevel`（`getPlot`、`updateLastPose`）
- `dev.ryanhcode.sable.sublevel.plot.LevelPlot`（`newEmptyChunk`、`getEmbeddedLevelAccessor`、`getCenterChunk`）
- `dev.ryanhcode.sable.api.physics.PhysicsPipeline`（`teleport`）
- `dev.ryanhcode.sable.companion.math.Pose3d`
