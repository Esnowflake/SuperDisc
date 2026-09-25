# Super Disc · 超级唱片

通过原版唱片机播放自选 MP3 / Ogg Vorbis 音乐，支持多人同步、独立音量和播放控制。

![超级唱片](src/main/resources/assets/super_disc/textures/item/super_disc.png)

## 支持范围

| Minecraft | 平台 | Java | 状态 |
| --- | --- | --- | --- |
| 1.20.1 | Forge 47.2.0+（47.x） | 17 | 原有平台；本分支完整构建及核心回归通过 |
| 1.21.1 | Forge 52.1.16+（52.x） | 21 | 实验性移植；构建通过，游戏验收待完成 |
| 1.21.1 | NeoForge 21.1.251+（21.1.x） | 21 | 实验性移植；构建通过，游戏验收待完成 |
| 1.21.1 | Fabric Loader 0.19.5+、Fabric API 0.116.17+1.21.1 | 21 | 实验性移植；GUI 模糊修复已反馈通过，完整游戏验收待完成 |
| 26.2 | Fabric Loader 0.19.5+、Fabric API 0.161.0+26.2 | 25 | 实验性移植；构建及静态检查通过，游戏内验收待完成 |

当前测试版本：`2.1.0-alpha.3`。客户端与服务器必须安装相同平台、Minecraft 版本及模组版本。
这些目标不是跨版本或跨 Loader 联机桥梁，不承诺互相连接。音频设备、专用服务端、双客户端同步及旧存档迁移仍需完整验收；GUI 修复确认不代表这些项目均已通过。

## 安装

从对应 Release 选择一个安装包，放入游戏或服务器的 `mods` 目录：

- Forge 1.20.1：`super-disc-2.1.0-alpha.3+mc1.20.1-forge.jar`
- Forge 1.21.1：`super-disc-2.1.0-alpha.3+mc1.21.1-forge.jar`
- NeoForge 1.21.1：`super-disc-2.1.0-alpha.3+mc1.21.1-neoforge.jar`
- Fabric 1.21.1：`super-disc-2.1.0-alpha.3+mc1.21.1-fabric.jar`，另装匹配的 Fabric API。
- Fabric 26.2：`super-disc-2.1.0-alpha.3+mc26.2-fabric.jar`，另装匹配的 Fabric API。

JLayer MP3 解码库已内嵌，不需额外安装。不要同时装入多个平台包，也不要安装 sources 或开发中间包。
本地构建的 Forge 安装包带有 `-all.jar` 后缀，不要安装不带该后缀的开发输出。
发布包附带 `SHA256SUMS.txt`，可用 `Get-FileHash` 或 `sha256sum` 校验。

## 使用

用八个金锭围绕一个绿宝石合成超级唱片；也可从创造模式工具栏获取，
或使用 `/give @s super_disc:super_disc`。

1. 手持超级唱片右键原版唱片机，绑定并打开控制界面；先取出其中的原版唱片。
2. 选择或拖入 `.mp3` / `.ogg` 文件，也可输入完整文件路径。文件会自动校验并同步给收听者。
3. 使用开始、暂停、重新播放和循环控制。取消文件选择不会停止现有音乐。
4. 已绑定的唱片机无需手持物品即可打开；“解除绑定”会停止自定义播放并恢复原版交互。

超级唱片是可重复使用的控制物品，不插入原版唱片槽，也不会因绑定而消耗。
本地绝对路径不向其他玩家发送；同步内容包含音频文件、文件名及必要播放元数据。

## 联机与音量

- 音频导入者可调整进度；本次播放发起者可管理多人音量。
- 每位玩家、每台唱片机独立设置音量，范围为 0–400%。
- “禁止他人调音量”由本人控制，服务器验证每次管理操作。
- “防失真”默认开启，仅作用于本机，以 PCM 峰值限制增益；它与多人音量保护是不同设置。
- 关闭防失真后，高增益可能削波破音；保护不能修复原音频或设备失真。
- 收听者同步完成后预约播放；中途靠近或加入会补同步。网络和音频设备仍可能造成时间误差。
- 使用游戏唱片声音分类和位置衰减，约 16 格听觉范围；提高音量不会扩大范围。
- 破坏唱片机会停止播放；服务器重启保留绑定和进度，默认暂停。

## 文件与限制

缓存位于实际游戏工作目录的 `super_disc/` 下，支持启动器版本隔离。
客户端偏好保存于 `super_disc/client.properties`，音频和 PCM 缓存位于 `super_disc/audio_cache/`。
模组不会删除导入的源文件；主动清空路径会取消同步，完整缓存仍保留。
服务器及收听客户端会保存共享音频，请只分享有权分发的内容。

- 单个文件最多 32 MiB、20 分钟；PCM 缓存最多 256 MiB。
- `.ogg` 仅支持 Vorbis，不支持 Opus。
- 每个世界最多绑定 256 台唱片机，最多同时播放 32 台。
- 失败或超时会暂停并提示；首次同步、解码速度受网络和设备影响。
- Fabric 使用独立的版本适配，未承诺读取 Forge 的历史存档数据。

## 构建与验证

版本统一维护在 `gradle.properties` 的 `mod_version`，五个目标由 `scripts/targets.json` 定义。
Forge 1.20.1 使用 JDK 17，三个 1.21.1 目标使用 JDK 21，Fabric 26.2 使用 JDK 25。
安装完整 JDK 和 Python 3.11+，在仓库根目录执行：

```powershell
.\build.ps1 -Target forge-1.20.1 -JavaHome $env:JDK17_HOME
.\build.ps1 -Target forge-1.21.1 -JavaHome $env:JDK21_HOME
.\build.ps1 -Target neoforge-1.21.1 -JavaHome $env:JDK21_HOME
.\build.ps1 -Target fabric-1.21.1 -JavaHome $env:JDK21_HOME
.\build.ps1 -Target fabric-26.2 -JavaHome $env:JDK25_HOME
python -m unittest discover -s scripts -p 'test_*.py' -v
```

Windows 下将仓库放在非 C 盘。脚本使用 UTF-8，将 Gradle 缓存和临时文件放在仓库的 `.gradle-home/`、`.tmp/`，禁用 JDK 自动下载。
需要代理时增加 `-Proxy $env:HTTPS_PROXY`，传入 HTTP 代理地址；脚本同时配置 shell 和 Java 代理，不保存个人地址。
Linux 可在选择对应 JDK 后运行各工程的 Wrapper；CI 和 Release 已自动配置工具链、矩阵及缓存目录。

五个 `build` 均执行核心回归与编译后 GUI 绘制顺序检查；1.21.1 和 Fabric 26.2 另检查存档及声音接口。
最终安装包还会验证元数据、资源、Java 字节码、映射及内嵌依赖。这些检查不替代游戏实测。
复测时使用独立测试世界，检查主界面和音量管理页、MP3/Ogg 播放与循环、进度和音量权限、取消同步、重连、服务器重启及双客户端同步。
不要将缓存、日志、存档、凭据或玩家音频提交到仓库。

## 发布

1. 更新根 `mod_version`，在 README 中添加唯一的 `## [版本] - YYYY-MM-DD` 变更摘要。
2. 完成五个目标的本地构建与测试，合入 `main` 并确认 CI 通过。
3. 推送与版本匹配的新附注标签，如 `v2.1.0-alpha.3`；不要移动旧标签。
4. GitHub Actions 从标签源码重建全部目标，校验并汇总五个 JAR 与 `SHA256SUMS.txt`，生成 Draft Release。
5. 检查附件及测试范围后，由维护者发布草稿；带 alpha、beta 或 rc 后缀的版本保留 Pre-release 标记。

工作流要求标签提交属于 `main`。上传重跑只补齐缺失附件，相同附件逐字节核对；不同内容或已发布的 Release 会被拒绝修改。
自动化始终先生成草稿，不会自行公开。后续同一矩阵的版本可复用此流程；新增 Minecraft 目标需要实际移植和测试，不能仅修改包名。
本地可用 `python scripts/release.py validate v2.1.0-alpha.3` 检查版本与发行摘要。

## 反馈与许可

反馈时附上 Minecraft、Loader、Java 和模组版本，以及已去除个人信息的相关日志。
项目保留原有 All Rights Reserved 声明；JLayer 的 LGPL 许可证及源码归档随安装包提供。

## [2.1.0-alpha.3] - 2026-09-25

- 修复 1.21.1 Fabric、Forge、NeoForge 的主界面和多人音量页重复绘制背景，避免模糊覆盖文字。
- 移除 Fabric 26.2 的重复背景调用，使用不模糊的游戏内背景并保留字幕处理。
- Forge 1.20.1 未发现该问题，保持现有渲染行为；五个构建目标均增加编译后绘制顺序回归检查。
- Fabric 1.21.1 的 GUI 修复已收到用户复测通过反馈；其余平台画面和完整音频、服务端、联机验收不据此宣称通过。
- 文档合并至 README，保留可复用的五目标云端构建、草稿审核与发布流程，以及必要许可证和第三方声明。
