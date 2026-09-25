# 贡献

日常开发使用独立分支，通过 PR 合入 `main`。不要提交缓存、日志、游戏存档、凭据或玩家音频。
先执行 `git status`，保留他人的未提交改动；同步主分支使用 `git pull --ff-only`，不要强推共享分支。

构建见 [docs/BUILDING.md](docs/BUILDING.md)，发布见 [docs/RELEASING.md](docs/RELEASING.md)。
版本只有根 `gradle.properties` 一个来源，目标定义只维护 `scripts/targets.json`。
新增平台必须有实际移植和对应验证，不能仅改 JAR 文件名或模组元数据。

Forge 1.20.1 位于 `src/main/`，Fabric 26.2 位于 `fabric/src/main/`。
三个 1.21.1 工程分别位于对应目标目录，共用 `shared-1.21.1/src/main/` 的游戏版本适配。
`gradle/common-1.21.1.gradle` 统一其版本、源码、资源和测试。
跨 Minecraft 版本的纯 Java 辅助类目前保留副本；修改时保持相同行为，并分别运行核心回归测试。
修复共享逻辑时应同时检查另一平台，避免仅修改一个副本。
不把“编译成功”描述成“游戏内稳定”。变更先记入 `CHANGELOG.md` 的 Unreleased。

原仓库声明 All Rights Reserved。本分支保留该声明，不替上游重新授权。
第三方 JLayer 的 LGPL 许可证、声明与源码归档必须随包保留。
