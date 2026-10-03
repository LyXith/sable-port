# 配置

Sable 使用 [Forge Config API Port](https://github.com/Fuzss/forgeconfigapiport)（已内嵌），配置文件位于游戏目录的 `config/`：

| 文件 | 类型 | 注册处 |
| --- | --- | --- |
| `config/sable-common.toml` | `COMMON` | `SableFabric` |
| `config/sable-client.toml` | `CLIENT` | `SableFabricClient` |

安装 **Mod Menu** 后，可在其模组列表中直接打开 Sable 的配置界面（`ConfigurationScreen`），改动即时生效（客户端每帧读取）。

> 另有 `SableServerConfig`（内含 `sub_level_substeps_per_tick`），但当前移植分支未注册该配置；物理子步数等可在游戏内用 `/sable debug config substeps <n>` 调整。

---

## 公共配置（`sable-common.toml`）

定义于 `dev.ryanhcode.sable.SableConfig`。

### 子关卡生命周期

| 键 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `sub_level_splitting` | bool | `true` | 子关卡在被分离时是否允许分裂成多个子关卡。 |
| `sub_level_splitting_heatmap_steps` | int | `200` | 每 tick 执行的子关卡分裂 heatmap 步数（1 ~ `Integer.MAX_VALUE`）。 |
| `sub_level_tracking_range` | double | `320.0` | 向玩家网络同步子关卡的距离（格）。 |
| `sub_levels_with_players_cannot_unload` | bool | `true` | 有玩家与子关卡相交时禁止卸载它。 |
| `sub_level_remove_min` | double | `-10000` | 子关卡可存在的最低 Y 坐标，超出即移除。 |
| `sub_level_remove_max` | double | `100000` | 子关卡可存在的最高 Y 坐标。 |
| `sub_level_velocity_retained_on_load` | double | `0.9` | 子关卡重新加载时保留的动量比例（0.0 ~ 1.0）。 |

### 击打（Punch）

| 键 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `sub_level_punch_strength_multiplier` | double | `2.1` | 击打冲量的强度倍率。 |
| `sub_level_punch_downward_strength_multiplier` | double | `0.175` | 向下击打冲量竖直分量的倍率（防止站在轻物上向下击打把自己弹起来）。 |
| `sub_level_punch_cooldown_ticks` | int | `3` | 两次击打之间的冷却（tick）。 |

### 网络

| 键 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `disable_udp_pipeline` | bool | `false` | 完全禁用 UDP 网络管线。可提升与 Replay Mod 等模组 / 特殊网络环境的兼容性，但子关卡同步的延迟与性能会变差。 |
| `attempt_udp_networking` | bool | `true` | 是否尝试与客户端建立 UDP 连接以发送子关卡运动数据。 |

### 日志 / 调试

| 键 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `sub_level_saving_log_message` | bool | `true` | 保存某维度子关卡时是否打印日志。 |
| `verbose_serialization_logging` | bool | `false` | 序列化系统与 holding chunk-map 的冗长日志（仅调试用，不推荐）。 |

---

## 客户端配置（`sable-client.toml`）

定义于 `dev.ryanhcode.sable.SableClientConfig`。

### 渲染

| 键 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `sub_level_renderer` | enum | `VANILLA` | 子关卡渲染器。当前仅 `VANILLA` 受支持（Sodium 兼容层在本次移植中被剥离）。 |
| `sub_level_occlusion_culling` | bool | `true` | 区块级遮挡剔除：从相机所在 section 出发做 BFS，只保留能通过“非全不透明面”到达的 section。 |
| `sub_level_cull_enclosed_blocks` | bool | `true` | 跳过被不透明邻居完全包住的方块（实心结构内部，从外面看不到）。 |
| `sub_level_render_distance` | double | `-1.0` | 子关卡的最大渲染距离（格）；`-1` 表示不按距离剔除。 |
| `sub_level_dynamic_shading` | bool | `true` | 动态方块着色（**已失效**：Veil shader 特性被剥离，保留以兼容旧配置）。 |
| `sub_level_water_occlusion` | bool | `true` | 子关卡是否遮挡水面（**已失效**，同上）。 |
| `sub_level_skylight_shadows` | bool | `false` | 子关卡是否向世界投射阴影（**已失效**，同上）。 |
| `debug_draw_loaded_chunks` | bool | `false` | 区块调试渲染器是否绘制已加载区块。 |

### 同步 / 其它

| 键 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `sub_level_snapshot_interpolation_delay_ticks` | double | `1.5` | 位姿快照插值回放的延迟（tick）。越大越平滑、越滞后。 |
| `sub_level_zoom_sensitivity` | double | `0.2` | 子关卡相机类型的缩放灵敏度。 |
| `attempt_udp_networking` | bool | `true` | 是否尝试与服务端建立 UDP 连接以接收子关卡运动数据。 |

---

## 命令式物理参数

以下服务端物理参数不通过配置文件，而是用命令调整（需权限等级 2）：

```
/sable debug config substeps <n>
/sable debug config solver_iterations <n>
/sable debug config pgs_iterations <n>
/sable debug config stabilization_iterations <n>
/sable debug config contact_spring_natural_frequency <f>
/sable debug config contact_spring_damping_ratio <r>
/sable debug config min_island_size <n>
```
