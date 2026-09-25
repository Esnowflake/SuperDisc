# 开发文档

用户安装与使用说明见 [README](../README.md)。本页面向修改源码、验证模组和维护发行版的开发者。

## 工程与工具链

版本统一维护在根 `gradle.properties` 的 `mod_version`，目标定义位于 `scripts/targets.json`。
本地构建入口、GitHub Actions 和安装包校验均读取这份矩阵。

| 目标 | 工程 | JDK | Gradle |
| --- | --- | --- | --- |
| Forge 1.20.1 | 仓库根目录 | 17 | 8.8 |
| Forge 1.21.1 | `forge-1.21.1/` | 21 | 8.8 |
| NeoForge 1.21.1 | `neoforge-1.21.1/` | 21 | 9.5.1 |
| Fabric 1.21.1 | `fabric-1.21.1/` | 21 | 9.5.1 |
| Fabric 26.2 | `fabric/` | 25 | 9.5.1 |

三个 1.21.1 工程共用 `shared-1.21.1/src/main/`，公共构建配置位于 `gradle/common-1.21.1.gradle`。
新增 Minecraft 目标需要实际版本适配及对应测试，不能只修改矩阵或包名。
不要提交缓存、日志、存档、凭据或玩家音频。

## 本地构建

安装完整 JDK 17、21、25，以及 Python 3.11+。Windows 下将仓库放在非 C 盘，在仓库根目录执行：

```powershell
.\build.ps1 -Target forge-1.20.1 -JavaHome $env:JDK17_HOME
.\build.ps1 -Target forge-1.21.1 -JavaHome $env:JDK21_HOME
.\build.ps1 -Target neoforge-1.21.1 -JavaHome $env:JDK21_HOME
.\build.ps1 -Target fabric-1.21.1 -JavaHome $env:JDK21_HOME
.\build.ps1 -Target fabric-26.2 -JavaHome $env:JDK25_HOME
python -m unittest discover -s scripts -p 'test_*.py' -v
```

脚本启用 UTF-8，将 Gradle 缓存和临时文件放在仓库的 `.gradle-home/`、`.tmp/`，并禁用 JDK 自动下载。
需要代理时增加 `-Proxy $env:HTTPS_PROXY`，传入不含凭据的 HTTP 代理地址；脚本同时配置 shell 和 Java 代理，并在结束后恢复环境变量。
直接运行 Wrapper 不会应用该脚本的目录和代理保护。

Linux 下先选择对应 JDK，设置 `GRADLE_USER_HOME` 指向仓库的 `.gradle-home/`，
设置 `JAVA_TOOL_OPTIONS` 中的 `-Djava.io.tmpdir` 指向仓库的 `.tmp/`，再执行目标工程的 Wrapper：

```sh
export GRADLE_USER_HOME="$PWD/.gradle-home"
mkdir -p "$PWD/.tmp"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$PWD/.tmp"
# JDK 21，Fabric 1.21.1 示例：
bash ./fabric-1.21.1/gradlew --project-dir fabric-1.21.1 --no-daemon clean build
```

各工程产物位于各自 `build/libs/`。Forge 的可安装包带 `-all.jar` 后缀，不要分发普通开发 JAR。
Forge 1.20.1 包含 `jarJar` 和重混淆步骤；Forge 1.21.1 使用官方运行名称。
Fabric 1.21.1 执行 `remapJar`；Fabric 26.2 使用不混淆发行环境。
最终产物路径和校验要求以 `scripts/targets.json` 为准。
JLayer 的许可证及源码归档必须保留在安装包内，Fabric API 不内嵌。

## 测试

五个 `build` 都执行真实核心源码的回归检查，以及 `tools/ScreenRenderChecks.java` 的编译后绘制顺序检查。
三个 1.21.1 目标还执行存档和声音接口契约检查，Fabric 26.2 执行自己的对应测试。
`scripts/release.py prepare` 检查安装包元数据、资源、字节码、映射及内嵌依赖。

这些测试不会启动游戏，不等同于 OpenGL 画面、实际 Mixin 注入、音频设备或联机验收。
Fabric 1.21.1 的 GUI 模糊修复已收到用户实测确认；不要据此推断其他目标或完整玩法已验收。
使用独立测试世界，逐目标检查：

- 主界面和多人音量页的清晰度、交互、滚动及来回切换。
- MP3/Ogg 播放、暂停、重播、循环、尾段和极短音频。
- 进度权限、个人与远程音量、音量保护及防失真。
- 上传下载中取消、换曲、超时、断线及缓存复用。
- 专用服务器启动、双客户端同步、中途加入、世界退出和音频重载。
- 服务器重启后的绑定、进度和音量保存；旧存档先备份，不承诺跨 Loader 转换。

## 云端构建与发布

1. 更新根 `mod_version`，在 README 添加唯一的 `## [版本] - YYYY-MM-DD` 用户更新摘要。工程内部细节放在本页或提交说明。
2. 完成本地五目标构建及发布工具测试，将提交推送到有权限的目标仓库 `main`，确认 CI 通过。
3. 创建并推送与版本匹配的新附注标签，如 `v2.1.0-alpha.3`；不要移动已存在的标签。
4. Release 工作流从标签源码重建五个目标，校验、汇总五个 JAR 和 `SHA256SUMS.txt`，生成 Draft Release。
5. 核对附件、依赖说明和实测范围后，由维护者发布草稿；alpha、beta、rc 版本保留 Pre-release 标记。

CI 与 Release 自动配置各目标工具链。Release 要求标签提交属于 `main`，仅发布任务拥有写权限。
自动化不会自行公开草稿。相同版本、同名附件会逐字节核对；内容不同或 Release 已公开时拒绝修改。
失败后优先重跑对应工作流，不能用本地旧包替代标签构建，不使用强制覆盖附件。
文档可以在 `main` 上继续更新；已经构建的标签始终保留当时的源码和文档快照。

```sh
python scripts/release.py validate v2.1.0-alpha.3
python scripts/release.py prepare v2.1.0-alpha.3 --target fabric-1.21.1 --output release/local/fabric-1.21.1
```

对全部矩阵目标分别执行 `prepare` 后，再用 `assemble` 汇总、`verify` 校验。
输出目录必须是新的空目录；每个新版本使用新的目录和标签。
发行摘要从该标签的 README 提取，验收不完整时必须明确标注实验性限制。
