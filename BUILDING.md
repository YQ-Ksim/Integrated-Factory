# 编译与常见问题记录

本文件记录 Integrated Factory 在 Minecraft 1.21.1 / NeoForge 环境中的可复用构建步骤、依赖获取问题，以及本项目已经踩过的坑。开始编译或排查启动错误前先读这里。

## 最近一次变更

2.5.1 汇总 2.4.6 以来的机器空间、结构安全、蓝图边界、节点管理与删除恢复改进：补全简单单元四向模型映射和基石实体破坏保护；强化多核心、活塞/Create 动态结构及接口合并拦截；加入 `/factory worlds` 可点击管理、节点所有权/协作与备注；蓝图大炮越界目标在 Create 抽取材料前跳过；删除节点时清理结构旧 UUID，旧绑定核心可作为新核心重新放置，并修复放置失败吞物。已用 Java 21 与 Gradle 8.14 执行 `build -Puse_neoforged_mirror --console=plain` 成功编译打包，尚未进行完整游戏内回归。

## 当前目标环境

- Minecraft：1.21.1
- NeoForge：开发配置为 `21.1.209`；游戏实例使用过 `21.1.264`
- Create：`6.0.10`（`gradle.properties` 中开发 Maven 版本为 `6.0.10-280`）
- Java：21
- ModDevGradle：`2.0.107`
- Gradle：8.14
- 成品：`build/libs/integrated_factory-2.5.1.jar`（当前源码版本）

运行时需要在游戏实例中另外安装 Create 6.0.10 及其依赖。编译成功不代表已经完成完整的游戏内功能验证。

## Windows PowerShell 构建

如果 Java 21 与 Gradle 8.14 已在 PATH 中：

```powershell
$env:JAVA_HOME = 'C:\路径\到\JDK 21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:GRADLE_USER_HOME = "$PWD\.gradle-home"
gradle build -Puse_neoforged_mirror --console=plain
```

本项目没有 Gradle Wrapper。环境中没有 Gradle 时，安装/准备 Gradle 8.14 后再运行上面的命令。不要让 Gradle 误用 Java 8 或 17；Minecraft 1.21.1 的此构建应使用 Java 21。

构建完成后，将对应版本 JAR 复制到目标实例的 `mods` 目录。更新时先移走旧版本 JAR，避免实例仍加载旧版。

## 本项目遇到过的构建环境问题

### Gradle 本地目录与守护进程

- 默认 Gradle 用户目录在受限环境中曾出现 native platform / 文件锁访问失败。设置 `GRADLE_USER_HOME` 到项目内的 `.gradle-home` 可避免使用该默认缓存目录。
- 受限沙箱可能禁止 Gradle 守护进程的本地通信。若构建停在启动守护进程或报 loopback 错误，应在允许本地进程通信的构建终端重试；不要把这类错误误判成 Java 源码错误。
- `.gradle-home/` 已加入 `.gitignore`。它是本机缓存，不要提交 Gradle 缓存或临时文件。

### NeoForge Maven 镜像

本次环境访问 `maven.neoforged.net` 时遇到 TLS/连接失败，但 `https://neoforged.forgecdn.net/releases` 可用。`settings.gradle` 已把该镜像放在插件仓库列表中；传入 `-Puse_neoforged_mirror` 后，`build.gradle` 还会把插件创建的 NeoForge Maven 仓库地址重定向到镜像。

### Create 依赖

本次环境的 `maven.createmod.net` 下载会长时间无响应。为使当前工作区可以重复构建，`build.gradle` 在以下本地文件存在时优先用它编译：

```text
.gradle-home/create-1.21.1-6.0.10.jar
```

该文件从 Create 的 Modrinth 发布 CDN 获取，Modrinth 项目/版本 ID 为 `LNytGWDc` / `UjX6dr61`，SHA-1 为 `0E97E49837BED766E6F28A4C95B04885D6ACC353`。本地文件不存在时，Gradle 会回退到 `maven.createmod.net` 上声明的 Create slim 依赖。如果 Maven 仍不可用，可从发布 CDN 取对应 1.21.1 NeoForge JAR，放在上述本地路径，再重新构建；不要把 Create JAR 嵌入最终的 Integrated Factory 模组。

## 已修复的编译问题

- `BaseEntityBlock` 在 1.21.1 需要 `codec()`，实体方块应返回 `simpleCodec(...)`。
- Create 的 `SmartBlockEntity.saveAdditional(...)` 是 `final`。自定义 NBT 字段应通过覆盖 `write(CompoundTag, HolderLookup.Provider, boolean)` 写入；读取逻辑可放在 `loadAdditional(...)`。
- `BlockEntityTicker` 的泛型目标应明确，避免使用不兼容的方法引用。
- 简单集成单元方块需要导入 `BlockEntity` 类型。

## 已修复的游戏启动 / 新建世界错误

测试实例在“创建新世界”时曾因维度类型注册失败。重要报错出现在实例自己的 `logs/latest.log`，而 crash report 顶层通常只显示 `Failed to load registries due to above errors`。

`data/integrated_factory/dimension_type/factory_room.json` 必须遵守 Minecraft 1.21.1 的维度类型字段格式：

- 字段名是 `ultrawarm`，不是 `ultra_warm`。
- `monster_spawn_light_level` 可用整数，例如 `7`。曾用的 `{ "type": "minecraft:uniform", "value": {...} }` 嵌套结构会解析失败；本项目采用整数值。

如果再次遇到 registry/datapack 加载失败：

1. 打开目标游戏实例的 `logs/latest.log`，搜索 `Registry loading errors`、`Failed to parse` 和 `Caused by`。
2. 按日志指出的数据文件与字段修复 `src/main/resources/data/...`，不要只看 crash report 最外层异常。
3. 重新运行 Gradle 构建，并确认 JAR 中的资源已更新。
4. 用新 JAR 替换实例 `mods` 中的旧文件后再重试。

最近一次已成功执行 `gradle build`。`compileTestJava` 与 `test` 显示 `NO-SOURCE`；这只确认构建/打包成功，不能代替游戏内验证。

### 1.1.0 返回空间修复构建记录

2026-10-21 使用 Java 21 与 Gradle 8.14 执行 `compileJava` 和 `jar`，两项均成功；没有运行测试任务，也没有对新版本进行游戏内回归。产物为 `build/libs/integrated_factory-1.1.0.jar`。

### 1.2.0 界面与节点权限开发记录

2026-10-21 使用 Java 21 与 Gradle 8.14 执行 `compileJava` 和 `jar`，均成功；没有运行测试任务，也没有游戏内回归。产物为 `build/libs/integrated_factory-1.2.0.jar`。

### 1.3.0 端口菜单与应力接口模型开发记录

2026-10-21 使用 Java 21 与 Gradle 8.14 执行 `jar`，成功编译并打包；没有运行测试任务，也没有游戏内回归。产物为 `build/libs/integrated_factory-1.3.0.jar`。本版增加端口专属库存窗口、核心结构统计，并复用 Create 传动轴模型、Flywheel Visual 与 Create 动能方块实体渲染器。

### 1.4.0 应力网络与动力朝向修复记录

2026-10-22 使用 Java 21 与 Gradle 8.14 执行 `jar -Puse_neoforged_mirror`，成功编译并打包为 `build/libs/integrated_factory-1.4.0.jar`。修复应力端口在动力网络尚未就绪时通知 Create 应力容量的空指针崩溃；应力端口改为单向面向输出并采用 Create 创造马达模型与半轴渲染；简单单元与集成核心的动力轴调整为正面左右两侧。未运行测试任务，且尚未进行游戏内回归。

### 1.5.0 应力动力门控与离合器模型记录

2026-10-22 使用 Java 21 与 Gradle 8.14 执行 `jar -Puse_neoforged_mirror`，成功编译并打包为 `build/libs/integrated_factory-1.5.0.jar`。应力端口动力输出现依据主核心已加载时的 Create 理论转速门控；主核心区块卸载时保留理论负载快照。应力端口视觉改用 Create 定向半轴视觉，集成核心模型替换为离合器机身、铁块纹理与匹配正面左右轴向。未运行测试任务，尚未游戏内回归。

### 1.6.0 主网络容量映射记录

2026-10-22 使用 Java 21 与 Gradle 8.14 执行 `jar -Puse_neoforged_mirror`，成功编译并打包为 `build/libs/integrated_factory-1.6.0.jar`。移除应力端口固定百万 SU 容量，按主维度 Create 动力网络实际容量减去其他负载后的余量设定远端容量，并每 20 tick 同步主网变化。未运行测试任务，尚未进行游戏内回归。

### 1.7.0 核心与应力端口物品模型修复记录

修复集成核心方块状态同时声明 `variants` 与 `multipart` 导致机身/轴模型组合不稳定的问题，改为统一 multipart 状态，并直接继承 Create 离合器官方机身模型后应用铁块纹理。应力端口物品栏模型现包含静态 Create 半轴几何，补齐此前仅方块实体动画可见、物品图标无轴的问题。使用 Java 21 与 Gradle 8.14 执行 `jar -Puse_neoforged_mirror --console=plain`，成功编译打包为 `build/libs/integrated_factory-1.7.0.jar`；未运行测试任务，未进行游戏内回归。并检查成品 JAR 内核心方块状态与核心/应力端口模型 JSON 均可解析，且应力端口物品模型包含静态半轴几何。

### 1.8.0 离合器银白材质、核心轴动画与真实 RPM 桥接

源码保留 Create 离合器官方几何，使用 Pillow 将 clutch、gearbox 与漏斗框架纹理重映射为银白金属色并保留像素明暗细节；核心添加 Create/Flywheel 旋转轴渲染；应力端口按主维度核心理论转速输出，不再固定 256 RPM，远端应力容量按实际转速换算。使用 Java 21 与 Gradle 8.14 执行 `jar -Puse_neoforged_mirror --console=plain`，成功打包为 `build/libs/integrated_factory-1.8.0.jar`；检查了 JAR 内资源 JSON 和新增纹理。未运行测试任务，尚未进行游戏内回归。

### 1.9.0 机器空间强加载与绑定节点保留

将新机器空间按区块对齐，使 10×10 房间及其边界完整位于单一区块。新增 `maxForceLoadedChunks` 服务端配置，默认总额度为 8 个 block-ticking 区块，按最近访问的有效节点分配，节点失效或超出额度时撤销票据；旧房间按实际占用区块计入额度。核心与简单单元的掉落物保存节点 UUID 方块实体数据，重新放置后恢复原节点绑定。核心 Flywheel 轴视觉采用 Create 轴向感知路径修正竖直显示。使用 Java 21 与 Gradle 8.14 执行 `jar -Puse_neoforged_mirror --console=plain` 成功编译并打包为 `build/libs/integrated_factory-1.9.0.jar`；未运行测试任务，尚未进行游戏内回归。

### 2.0.0 物品能力兼容、核心传动轴与机器空间优化

端口自动化接口增加 `IItemHandlerModifiable` 兼容视图，并保留方向性写入权限。集成核心模型调整动力轴端面与前后表面的纹理分配；新增向方块边界外延伸的 Create 轴 partial，并用于传统方块实体渲染与 Flywheel visual。机器空间强加载额度默认提高到 256、上限提高到 4096；只有有效启用节点且主核心区块加载时才保留 block-ticking 票据，主核心区块卸载后定期回收远端房间票据。核心/简单单元只在应力影响有变化时更新 Create 网络；机器空间节点查找改为按区块索引，避免每个远端方块实体遍历全部节点。使用 Java 21 与 Gradle 8.14 执行 `jar -Puse_neoforged_mirror --console=plain` 成功编译并打包为 `build/libs/integrated_factory-2.0.0.jar`；未运行测试任务，尚未进行游戏内回归。

### 2.0.1 Create 网络与集成核心模型回退

将集成核心和简单单元的应力更新恢复为每 tick 向 Create `KineticNetwork` 提交，撤回 2.0.0 的变化检测短路；核心方块模型、物品模型与轴向视觉回到 2.0.0 前的离合器模型与 `AllPartialModels.SHAFT`。移除延长轴模型及对应注册。保留物品能力兼容、机器空间强加载与区块索引等其他 2.0.0 改进。使用 Java 21 与 Gradle 8.14 执行 `jar -Puse_neoforged_mirror --console=plain` 成功编译打包为 `build/libs/integrated_factory-2.0.1.jar`；未运行测试任务，尚未进行游戏内回归。

### 2.1.0 机器世界管理与应力网络恢复

增加 `/factory worlds`、`enter`、`delete` 和 `note` 管理命令；节点备注、删除节点 ID 与退役房间坐标写入 `SavedData`。删除会撤销该节点的强加载票据并保留房间原有方块，节点删除时在房间内的玩家会返回进入前的位置。核心/简单单元放置者被记录为所有者。应力端口每 20 tick 重新向主维度 Create 网络提交远端应力，并使用理论转速避免实际转速归零时的桥接恢复死锁；应力端口放置朝向改为玩家视线方向。Java 21 与 Gradle 8.14 执行 `jar -Puse_neoforged_mirror --console=plain`，成功编译并打包 `build/libs/integrated_factory-2.1.0.jar`；未运行测试任务，尚未进行游戏内回归。

### 2.1.1 应力变化触发同步

移除集成核心每 tick 与应力端口每 20 tick 的重复全量应力计算。核心与简单单元缓存最近提交的应力影响，只在远端 SU、本地转速或 Create 动力网络实例变化时调用 `updateStressFor`。应力端口读取 Create 6.0.10 缓存的网络总应力/容量，只有检测到数值变化或网络重建时才更新节点负载并触发主网络重算。使用 Java 21 与 Gradle 8.14 执行 `jar -Puse_neoforged_mirror --console=plain` 成功编译并打包 `build/libs/integrated_factory-2.1.1.jar`；未运行测试任务，尚未进行游戏内回归。

### 2.2.0 机器世界聊天交互菜单

`/factory worlds` 现在显示可点击的进入、删除和备注操作；删除先显示二次确认，备注按钮会预填节点命令，不需要手动抄写完整 UUID。使用 Java 21 与 Gradle 8.14 执行 `jar -Puse_neoforged_mirror --console=plain`，成功编译并打包 `build/libs/integrated_factory-2.2.0.jar`；未运行测试任务，尚未进行游戏内回归。

### 2.2.7 集成核心水平传动轴方向修复

修正核心旋转轴 partial 的参考方向：模型几何本身沿 Y 轴，但 Flywheel 视觉此前将其当作 SOUTH 轴，导致动画绕正确轴旋转而几何保持竖直。Flywheel 与传统方块实体渲染均显式将 UP 方向映射到核心的实际动力轴。使用 Java 21 与 Gradle 8.14 执行 `build -Puse_neoforged_mirror --console=plain` 成功，生成 `build/libs/integrated_factory-2.2.7.jar`；未运行测试任务，游戏内视觉仍待验证。

### 2.2.8 简单单元与端口模型贴图

简单集成单元复用核心的 Create 离合器/齿轮箱几何，按四向朝向区分前后面贴图，并沿左右动力轴使用同一 Flywheel/方块实体传动轴动画。拓展接口、输入端口和输出端口模型改为六面使用各自模组贴图；同步 Blockbench 源及物品图标。使用 Java 21 与 Gradle 8.14 执行 `build -Puse_neoforged_mirror --console=plain` 成功，生成 `build/libs/integrated_factory-2.2.8.jar`；检查了相关 JSON 解析，未运行测试任务，未进行游戏内视觉回归。

### 2.2.9 世界重载后应力过载恢复

核心/简单单元在重新加入 Create 网络时提交负载并触发应力与容量联合重算；应力端口在远端网络重建时也执行一次联合重算，且远端快照变化后请求主维度核心网络重算。这会刷新 Create 持久化网络中的过期负载/容量，避免重载后残留过载状态直到玩家手动更新动力源。使用 Java 21 与 Gradle 8.14 执行 `build -Puse_neoforged_mirror --console=plain` 成功，生成 `build/libs/integrated_factory-2.2.9.jar`；未运行测试任务，尚未进行重载后的游戏内回归。

### 2.3.0 重载应力快照与残留负载修复

服务器启动时清空节点保存的运行时远端应力/理论转速快照，防止主维度核心在远端网络恢复前提交旧负载。应力桥接改为使用 Create 当前网络成员计算出的应力与容量，避免读取重载期间的 `unloaded` 缓存残留；网络重建时仍执行联合重算。使用 Java 21 与 Gradle 8.14 执行 `build -Puse_neoforged_mirror --console=plain` 成功，生成 `build/libs/integrated_factory-2.3.0.jar`；未运行测试任务，尚未完成游戏内重载回归。

### 2.3.1 机器空间区块重载应力恢复

应力端口在区块实体重新加载时清除节点运行时应力和理论转速快照，并等待 Create 重新挂接机器空间成员后再发布远端应力；恢复期间按主网当前可用容量重新开放虚拟动力源，避免旧负载和部分恢复值造成假过载。使用 Java 21 与 Gradle 8.14 构建成功，生成 `build/libs/integrated_factory-2.3.1.jar`；未运行测试任务，尚未进行游戏内重载回归。

### 2.3.2 清理未加载成员应力残留

机器空间限定在单区块内；应力端口恢复时清除 Create `KineticNetwork` 的未加载成员应力、容量和成员数量快照，再由当前已加载成员计算网络总量，避免不存在的旧机器造成残余应力。使用 Java 21 与 Gradle 8.14 构建；未运行测试任务，尚未完成游戏内重载回归。

### 2.3.3 主网络核心负载恢复

应力端口恢复窗口第一 tick 会把主世界核心在 Create 网络中残留的旧应力成员值更新为零并重算，再计算可用容量和恢复远端动力，修复需要手动更新动力源才能解除的重载过载。使用 Java 21 与 Gradle 8.14 构建；未运行测试任务，尚未完成游戏内重载回归。

### 2.3.4 核心先入网时的残留应力修复

恢复前保存核心旧远端应力；无论核心还是应力端口先加入 Create 网络，均只从 `unloadedStress` 中扣除该核心对应的残值，再提交零负载并重算，不影响主网络中其他正常未加载设备。使用 Java 21 与 Gradle 8.14 构建；未运行测试任务，尚未完成游戏内重载回归。

### 2.3.5 强制同步 Create 过载状态

网络恢复和负载更新后显式调用 Create `KineticNetwork.sync()`，即使应力/容量数值没有变化，也会刷新从存档恢复的 `overStressed` 标志，避免必须手动更新动力源才能清除显示过载。使用 Java 21 与 Gradle 8.14 构建；未运行测试任务，尚未完成游戏内重载回归。

### 2.3.6 远端网络对象替换恢复

应力端口每次检测到新的远端 `KineticNetwork` 对象都会重新清理该网络的未加载成员快照并强制同步，覆盖机器空间成员延迟加载引起的中途网络重建。使用 Java 21 与 Gradle 8.14 构建；未运行测试任务，尚未完成游戏内重载回归。

### 2.3.7 远端应力仅统计实际加载成员

远端应力桥改为只统计当前 `KineticNetwork.members` 中仍对应实际方块实体的成员，并使用其实际理论转速计算负载，完全排除 Create `unloadedStress` 快照和不存在方块的残留。主世界网络仍使用完整 Create 网络数据。使用 Java 21 与 Gradle 8.14 构建；未运行测试任务，尚未完成游戏内重载回归。

### 2.3.8 主网络重载后负容量修复

应力端口计算主世界可用容量时改为只使用当前加载的动力源和核心以外的实际负载，不再把 Create 重载快照中的 `unloadedStress` / `unloadedCapacity` 作为桥接预算。修复假过载时远端应力表显示为正常负载相反数的问题。使用 Java 21 与 Gradle 8.14 构建；未运行测试任务，尚未完成游戏内重载回归。

### 2.3.9 主网络残留过载标记修复

恢复窗口内只有在主网络所有待恢复成员均已重新挂接时，才清除其未加载快照并强制同步过载状态；仍有普通 Create 设备未加载时保留原有快照。使用 Java 21 与 Gradle 8.14 构建；未运行测试任务，尚未完成游戏内重载回归。

### 2.4.0 合成配方

新增六个模组方块配方，使用 Minecraft 1.21.1 的 shaped/shapeless 配方格式；Create 材料使用 `create:shaft` 与 `create:stressometer`。使用 Java 21 与 Gradle 8.14 构建；未运行测试任务。

### 2.4.1 Mod 信息与图标

将项目根目录 `icon.png` 复制到资源根目录并注册为 NeoForge Mod 图标；完善 `neoforge.mods.toml` 中的中英文简介、作者、致谢、许可证和依赖元数据。使用 Java 21 与 Gradle 8.14 构建；未运行游戏内测试。

### 2.4.2 PCL2 元数据兼容与官方传动杆

同时提供 NeoForge 与传统 Forge 元数据文件，兼容 PCL2 的本地 Mod 扫描；图标仍以 `icon.png` 为主，并提供同内容 `logo.png` 别名。集成核心和简单集成单元动态传动杆改用 Create 官方 `AllPartialModels.SHAFT`。使用 Java 21 与 Gradle 8.14 构建；未运行游戏内测试。

### 2.4.3 半像素延伸传动杆

恢复集成核心与简单集成单元自定义传动杆 partial，使轴体两端延伸 0.5 像素避免和机身边界重叠；模型仍引用 Create 官方 `axis` 与 `axis_top` 贴图。使用 Java 21 与 Gradle 8.14 构建；未运行游戏内测试。

### 2.4.4 传动杆截面贴图修正

### 2.4.5 基石与简单单元水平朝向

新增 `integrated_factory:cornerstone` 方块，使用 `BlockBehaviour.Properties.ofFullCopy(Blocks.BEDROCK)` 复制原版基岩的完整属性，并在资源模型中改用原版平滑石头贴图。机器空间新生成的地面改用基石，避免玩家破坏房间地面。简单集成单元明确保持水平 `HORIZONTAL_FACING` 状态，支持北/东/南/西四向绕竖直轴旋转，不允许沿 X/Z 轴翻转。版本升级为 2.4.5。使用 Java 21 与 Gradle 8.14 执行 `build -Puse_neoforged_mirror --console=plain` 成功；未运行游戏内回归。

### 2.4.6 简单单元四向输入输出与凋灵免疫

修正简单集成单元南/西朝向的 blockstate 旋转映射，使输入面可正确朝向东、南、西、北四个水平面，输出面始终为其相反面，左右动力轴不受影响。将基石加入原版 `minecraft:wither_immune` 方块标签，使凋灵本体和蓝色凋灵头爆炸均不能破坏机器空间地面。使用 Java 21 与 Gradle 8.14 执行 `build -Puse_neoforged_mirror --console=plain`；未运行游戏内回归。

### 2.4.7 西向输入面映射修正

重新按 Minecraft 方块模型 Y 轴旋转规则核对四个 blockstate：`front_west` 的北/东朝向分别使用 90/180°，`front_east` 的南/西朝向分别使用 90/180°。修正西向原先误用 270°导致模型回到北向的问题；能力判定继续直接使用同一 `FACING` 状态，因此自动化输入和输出面与可见模型保持一致。使用 Java 21 与 Gradle 8.14 执行 `build -Puse_neoforged_mirror --console=plain`；未运行游戏内回归。

### 2.4.8 基石代码级实体破坏保护

新增 `IFCornerstoneBlock`，继续使用 `Blocks.BEDROCK` 的完整方块属性，同时覆盖 `canEntityDestroy` 并拒绝实体破坏请求。这样凋灵本体、蓝色凋灵头和末影龙均无法破坏基石，即使整合包的数据标签合并顺序异常也不会失效。保留 `minecraft:wither_immune` 标签作为数据层兼容保护。使用 Java 21 与 Gradle 8.14 执行 `build -Puse_neoforged_mirror --console=plain`；未运行游戏内回归。

### 2.4.9 多核心结构与动态移动保护

集成核心放置事件现在拒绝非玩家来源，阻止 Create 蓝图大炮等直接打印核心；玩家放置核心若紧邻已有核心，或紧邻一个已经包含核心的成型机器结构，也会被拒绝；孤立的输入端口、输出端口和拓展接口不会阻止核心放置。核心只有在真实玩家放置事件取得许可后才会创建新节点，未取得许可的无绑定核心会直接掉落为普通物品。多核心组件在服务端扫描时先选择已绑定且与节点主机坐标一致的原核心，其他核心会被移除并掉落，绑定核心保留节点 UUID、重绑定令牌和附魔标记，未绑定核心则掉落普通物品；不会把端口归属或原节点改写到重复核心。绑定核心复制到非原主机位置时，即使由玩家放置，只要没有合法重绑定令牌或原主机仍存在，也会自动退回为保留绑定的掉落物；同一节点最多保留一个主机核心。原版活塞事件和 Create `BlockMovementChecks` 均禁止集成核心与简单集成单元进入移动结构，覆盖机械活塞、轴承、龙门架、底盘等动态路径。使用 Java 21 与 Gradle 8.14 执行 `build -Puse_neoforged_mirror --console=plain` 成功；未运行游戏内回归。

后续放置时序修正：核心邻接限制与多结构拓展接口合并限制前移到方块 `canSurvive` 检查，放置失败时不会先扣除手中物品；连接两个既有结构的拓展接口会被拒绝。简单集成单元不参与多方块核心冲突。强制连接仍会保留未授权新核心的独立节点，并在真正形成多核心冲突时优先保护原有核心。使用 Java 21 与 Gradle 8.14 再次构建成功；未运行游戏内回归。

节点交互补充：拓展接口合并被拒绝时向玩家发送提示；潜行右键集成单元或简单集成单元会在原有简要信息/库存界面之外追加对应的 `/factory worlds` 可点击节点行，包含节点名称、备注以及进入、删除、备注三个操作。使用 Java 21 与 Gradle 8.14 构建成功；未运行游戏内回归。

孤立结构放置修正：核心邻接检查不再把任意端口或拓展接口当作已成型结构，只有相邻核心或已绑定到有效节点的结构成员才会阻止核心放置；孤立输入端口、输出端口和拓展接口旁边可以正常放置集成核心。使用 Java 21 与 Gradle 8.14 构建成功；未运行游戏内回归。

扳手旋转修正：集成核心的邻接/机器空间限制移出 `canSurvive`，改在玩家放置状态生成阶段执行，避免 Create 扳手旋转时将已存在的核心误判为不可生存；核心现在可以正常通过扳手改变四向水平朝向。使用 Java 21 与 Gradle 8.14 构建成功；未运行游戏内回归。

核心误放置与掉落时序修正：放置前增加服务器交互拦截，沿端口/拓展接口的连通组件查找真实集成核心；连接已有核心的放置请求在扣除物品前直接失败，避免核心先出现、随后消失且掉落延迟到重新进入世界才可见。孤立端口和拓展接口仍可放置核心。使用 Java 21 与 Gradle 8.14 构建成功；未运行游戏内回归。

接口合并限制扩展：输入端口和输出端口与拓展接口统一禁止放置在两个含集成核心的机器结构之间，放置前提示“端口或拓展接口不能连接两个已有的机器结构”，并保留物品；限制仅作用于新放置，不会因邻居更新移除已经存在的接口。使用 Java 21 与 Gradle 8.14 构建成功；未运行游戏内回归。

修正自定义传动杆 partial 的面映射：轴体四个侧面使用 Create 官方 `axis` 贴图，真正的上下轴端使用 `axis_top` 截面贴图；保留两端各延伸 0.5 像素的几何。使用 Java 21 与 Gradle 8.14 构建；未运行游戏内测试。

