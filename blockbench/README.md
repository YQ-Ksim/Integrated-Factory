# Blockbench 可编辑模型与方块坐标

本目录提供可直接导入 Blockbench 的 Minecraft Java 方块模型 JSON，适用于 Minecraft 1.21.1 的资源模型。导入后可调整元素坐标、面 UV 和贴图引用，再导出为 Minecraft Java Block/Item Model JSON。

## 导入与应用

1. 在 Blockbench 选择 **File → Import → Minecraft Java Block/Item Model**，导入 `models` 内对应 JSON。
2. 核心模型为从 Create 6.0.10 离合器父模型链展开的独立副本；传动轴纹理仍引用 `create:block/axis`。要让纹理预览完整，请在 Blockbench 的贴图面板为引用指定项目资源或 Create 资源包中的 PNG。
3. 修改并导出后，将模型复制回 `src/main/resources/assets/integrated_factory/models/block/` 对应路径。核心模型需复制到 `integration_unit/block.json`；应力端口水平、竖直模型分别放入 `stress_port/block.json` 和 `stress_port/block_vertical.json`。
4. 修改方块状态朝向映射时编辑 `src/main/resources/assets/integrated_factory/blockstates/` 中相应 JSON。Blockbench 导出的单模型不会自动维护这些朝向映射。

这些 JSON 是可编辑源副本，改动不会自动同步至正式资源目录。正式资源目录仍是游戏实际加载的模型。

## 方块模型位置盘点

Minecraft 方块模型坐标范围通常为 0–16；`x` 向东、`y` 向上、`z` 向南。`from` 与 `to` 定义模型盒体的两个角点。模型整体位于 `[0,0,0]` 到 `[16,16,16]` 时会占满一个方块。

| 方块 | 当前模型与元素位置 | 朝向/渲染说明 |
|---|---|---|
| 集成单元 | Create clutch 的 gearshift 几何：主体 `[0,0,0]–[16,16,16]`；齿轮薄层 `[1.95,15,1.95]–[14.05,16,14.05]` 与 `[1.95,0,1.95]–[14.05,1,14.05]`。 | 方块状态旋转后，玩家前/后面为 clutch 贴图、左右动力面为 gearbox 贴图、顶/底为 frame 贴图。Create/Flywheel partial 轴在模型源中沿 Y 轴，由渲染器将 UP 对齐到核心实际左右动力轴，轴从两侧各伸出 0.5 像素并随动力转动。 |
| 简单集成单元 | 复用 Create clutch/gearshift 的核心几何：主体 `[0,0,0]–[16,16,16]`，上下齿轮薄层 `[1.95,0/15,1.95]–[14.05,1/16,14.05]`。 | 前/后分别使用 clutch_front / clutch_back，左右为 gearbox，上下为 clutch。四向 blockstate 选择前后贴图朝向；左右贯穿的动画轴由 Create/Flywheel partial 单独绘制。 |
| 拓展接口 | `[0,0,0]–[16,16,16]` 立方体。 | 六面使用 `extension_interface.png`。 |
| 输入端口 | `[0,0,0]–[16,16,16]` 立方体。 | 六面使用 `input_port.png`。 |
| 输出端口 | `[0,0,0]–[16,16,16]` 立方体。 | 六面使用 `output_port.png`。 |
| 应力端口 | 水平模型 12 个盒体，主要外壳从 `[3,3,0.1]–[13,13,2.1]` 延伸至 `[3,3,12]–[13,13,14]`；竖直模型 11 个盒体，主体从 `[4,2,4]–[12,11,12]`，轴向护柱延至 y=0–15。 | 方块状态按六向 `facing` 选择水平/竖直模型并旋转。动画单向轴由 Create `SHAFT_HALF` partial 单独绘制，JSON 中的轴仅用于静态预览/物品模型。 |
| 基石 | 使用完整立方体 `[0,0,0]–[16,16,16]`。 | `models/block/cornerstone.json` 直接引用 `minecraft:block/smooth_stone`，无独立 PNG；物品模型为 `models/item/cornerstone.json`。 |

### 应力端口元素位置

水平模型：

- 后盖：`[3,3,0.1]–[13,13,2.1]`、`[3,3,12]–[13,13,14]`
- 两层环：`[5,5,9]–[11,11,13]`、`[3.5,3.5,2]–[12.5,12.5,5]`
- 主体：`[4,4,2]–[12,12,11]`
- 四角护柱：x 为 2–4 或 12–14，y 为 2–14，z 为 0–15
- 下部机身：`[1,0,3]–[15,3,10]`
- 面板凸起：`[5,7,4]–[11,13,10]`、`[3,5,4]–[13,11,10]`

竖直模型：

- 四根护柱：x/z 为 2–4 或 12–14，y 为 0–15
- 上/下后盖：`[3,12,3]–[13,14,13]`、`[3,0.1,3]–[13,2.1,13]`
- 上部环：`[5,9,5]–[11,13,11]`
- 下部环：`[3.5,2,3.5]–[12.5,5,12.5]`
- 主体：`[4,2,4]–[12,11,12]`
- 中间凸起：`[3,4,5]–[13,10,11]`、`[5,4,3]–[11,10,13]`

## 可编辑文件清单

- `models/integration_unit.json`
- `models/integration_unit_shaft.json`（动画 partial 的静态编辑源；正式模型为 `assets/integrated_factory/models/block/core_shaft.json`）
- `models/simple_integration_unit.json`
- `models/simple_integration_unit_front_west.json`、`models/simple_integration_unit_front_east.json`（简单单元前后纹理朝向变体）
- `models/extension_interface.json`
- `models/input_port.json`
- `models/output_port.json`
- `models/stress_port.json`
- `models/stress_port_vertical.json`
- `models/create_shaft_reference.json`（核心轴的 Blockbench 参考；运行时轴动画仍由 Create/Flywheel 代码渲染）

集成单元与应力端口贴图正式文件在 `src/main/resources/assets/integrated_factory/textures/block/`。四个方块整块贴图目前直接使用 Minecraft 原版贴图。
