# 后续开发者须知

- 在运行 Gradle 编译或排查游戏启动问题前，先阅读根目录 `BUILDING.md`。它记录 Java/Gradle/NeoForge/Create 版本、依赖镜像、本地 Create 缓存用法和已知维度 JSON 格式错误。
- 项目配置了 `printBuildNotes` 依赖任务；执行 `compileJava`、`jar`、`assemble` 或 `build` 时，控制台会提示阅读 `BUILDING.md`。
- Minecraft 数据文件解析异常优先查看目标实例 `logs/latest.log` 中更早出现的 registry/datapack 根因，再修复 `src/main/resources`。
