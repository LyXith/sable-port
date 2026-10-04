# Sable（Fabric / Minecraft 26.3 移植版）Warning 本项目大量使用AI

Sable 是一个为方块世界加入**可交互的物理化移动结构**的模组：把一片世界区域“组装”成一个独立的子关卡（sub-level），交给刚体物理持续模拟，它可以移动、旋转、被撞击、被踩踏、被挖掘，而其中的方块依旧是真正的方块。

本仓库是上游 Sable（NeoForge 1.21.1）到 **Fabric / Minecraft 26.3** 的移植分支。

- 版本：`3.0.0`
- Minecraft：`26.3`
- 加载器：Fabric Loader `>= 0.19.5`
- Fabric API：`0.161.0+26.3`
- Java：`25`
- 许可：PolyForm Shield License 1.0.0

---

## 目录

| 文档 | 内容 |
| --- | --- |
| [`docs/architecture.md`](docs/architecture.md) | 整体架构：子关卡、plot、物理、网络、渲染管线与 Mixin 布局 |
| [`docs/configuration.md`](docs/configuration.md) | 客户端 / 服务端 / 公共配置项速查 |
| [`docs/port-notes.md`](docs/port-notes.md) | 26.3 移植说明、已知限制与常见坑 |
| [`docs/creating-a-physics-sublevel.md`](docs/creating-a-physics-sublevel.md) | 用 API 在服务端创建物理化子关卡 |

---

## 依赖

**必需**

- Fabric Loader `>= 0.19.5`
- Fabric API `0.161.0+26.3`
- [sable-companion](https://github.com/ryanhcode/sable-companion) `1.6.0`（仅编译）

**可选 / 兼容**

- **Mod Menu**：在游戏内提供 Sable 配置界面。
- **Forge Config API Port**：已随模组内嵌（`include`），用于配置文件与逐项配置界面。
- **Sodium**：`compileOnly` 兼容目标；当前移植分支**未包含** Sodium 专用渲染兼容层（见 `docs/port-notes.md`）。
- **ScalableLux**：光照兼容目标。

模组在 `fabric.mod.json` 中声明了若干 `breaks` 约束（Sodium、sable-companion、ScalableLux 的最低版本），加载器会据此提示不兼容版本。

---

## 构建

```bash
# 需要 JDK 25（Gradle toolchain 会在 gradle/gradle-daemon-jvm.properties
# 里通过 toolchainVersion=25 自动定位，无需手工设置 JAVA_HOME）
gradle build
```

产物：

```
build/libs/sable-fabric-26.3-3.0.0.jar        # 发布用
build/libs/sable-fabric-26.3-3.0.0-sources.jar
```

## 运行（开发环境）

```bash
gradle runClient   # 客户端，工作目录 runs/client
gradle runServer   # 服务端，工作目录 runs/server
```

## 安装

把 `sable-fabric-26.3-3.0.0.jar` 放进 `.minecraft/mods/`，并安装上表中的必需依赖即可。

---

## 功能概览

- **物理化子关卡**：把世界里的方块组装为独立 plot，作为刚体参与模拟，支持完整位姿（位置 / 旋转 / 缩放 / 非整数位置）。
- **组装 / 碎裂**：`/sable assemble`、`/sable shatter` 支持连通区域、球体、立方体、矩形区域等。
- **交互**：可放置 / 破坏子关卡上的方块，可击打（punch）施加冲量，绳子（rope）系统等。
- **移动结构上的实体**：实体可站在 / 骑乘 / 被移动结构推动。
- **同步与插值**：子关卡位姿快照通过网络同步，客户端做延迟插值；支持增量区块同步。
- **渲染性能**：子关卡按 section 批量走原版地形管线（`sub_level_batched_sections`，默认开启）。实测 **20 万方块**子关卡稳定 **75+ FPS**（帧时间约 13ms），半透明渲染正确；关闭该开关则回退为逐方块提交。
- **配置**：客户端 / 服务端 / 公共三类配置，可在 Mod Menu 中编辑。

## 命令

`/sable` 需要 **GameMaster（权限等级 2）**。顶层分类：

| 分类 | 命令 |
| --- | --- |
| `spawn` | `block` `sphere` `platform` `grid` `jenga` `schematic` `clone` `rope_test` `slope_test` `joint_test` |
| `assemble` / `shatter` | `area` `connected` `sphere` `cube` `sub_level` |
| `physics` | `add` `set` `impulse`（`linear` / `angular`，`global` / `local`） |
| `sub_level` | `get` `set` `name` `teleport` `remove` `clear` |
| `joint` | `add` `rotary` |
| `storage` | `find` `find_all_sub_levels` |
| `debug config` | `substeps` `solver_iterations` `pgs_iterations` `stabilization_iterations` `contact_spring_natural_frequency` `contact_spring_damping_ratio` `min_island_size` |

---

## 目录结构

```
src/main/java/dev/ryanhcode/sable/
├── api/            # 公开 API（sublevel / physics / math 等）
├── command/        # /sable 命令树
├── sublevel/       # 子关卡、plot、物理系统、存储、网络同步
├── mixin/          # 通用 Mixin（服务端 + 客户端）
├── mixinhelpers/   # Mixin 辅助类
├── render/         # 渲染辅助
├── network/        # TCP/UDP 数据包
└── fabric/         # Fabric 平台入口与仅 Fabric 的 Mixin/Platform 实现
```

更详细的分层说明见 [`docs/architecture.md`](docs/architecture.md)。
