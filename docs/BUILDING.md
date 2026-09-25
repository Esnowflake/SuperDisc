# 构建

## 工具链

| 目标 | JDK | Wrapper | 构建插件 |
| --- | --- | --- | --- |
| Forge 1.20.1 | 17 | Gradle 8.8 | ForgeGradle 6.0.54 |
| Forge 1.21.1 | 21 | Gradle 8.8 | ForgeGradle 6.0.54 |
| NeoForge 1.21.1 | 21 | Gradle 9.5.1 | ModDevGradle 2.0.147 |
| Fabric 1.21.1 | 21 | Gradle 9.5.1 | Fabric Loom Remap 1.17.21 |
| Fabric 26.2 | 25 | Gradle 9.5.1 | Fabric Loom 1.17.21 |

权威模组版本位于根 `gradle.properties` 的 `mod_version`。
平台定义位于 `scripts/targets.json`，CI 与 Release 读取同一矩阵。
云端固定使用 Ubuntu 24.04，Actions 使用完整提交 SHA 固定的 Node 24 版本。
Fabric 26.2 使用不混淆的游戏发行包，因此安装产物来自 Loom 的 `jar`，不套用旧版 Fabric 的 remap 流程。
Forge 1.20.1 的 `build` 包含 `jarJar` 和 `reobfJarJar`。
Forge 1.21.1 使用官方名称运行环境，不执行旧 SRG 重混淆；NeoForge 1.21.1 也使用官方名称。
Fabric 1.21.1 执行 `remapJar`，Mixin 选择器由现代 Loom 直接重映射，不生成旧式 refmap。

## Windows

仓库必须放在非 C 盘。安装完整的 JDK 17、21、25；Python 3.11+ 用于发布工具。
以下命令在仓库根目录执行，JDK 路径按实际安装目录传入：

```powershell
.\build.ps1 -Target forge-1.20.1 -JavaHome $env:JDK17_HOME
.\build.ps1 -Target forge-1.21.1 -JavaHome $env:JDK21_HOME
.\build.ps1 -Target neoforge-1.21.1 -JavaHome $env:JDK21_HOME
.\build.ps1 -Target fabric-1.21.1 -JavaHome $env:JDK21_HOME
.\build.ps1 -Target fabric-26.2 -JavaHome $env:JDK25_HOME
python -m unittest discover -s scripts -p 'test_*.py' -v
```

需要代理时，使用 `-Proxy $env:HTTPS_PROXY`，传入无凭据的 HTTP 代理 URL。
脚本显式设置 shell 代理和 Java 的 HTTP/HTTPS 代理属性，不在代码中保存个人代理地址。
脚本启用 UTF-8，缓存位于 `.gradle-home/`，临时文件位于 `.tmp/`；结束后恢复它修改的环境变量。
JDK 自动下载关闭，不会隐式向用户目录下载 JDK。
直接执行 Wrapper 不会自动应用这些 Windows 目录及代理设置，受缓存位置限制的环境应使用 `build.ps1`。

## Linux / GitHub Actions

先为相应目标选择正确 JDK：

```sh
export GRADLE_USER_HOME="$PWD/.gradle-home"
mkdir -p "$PWD/.tmp"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$PWD/.tmp"
bash ./gradlew --no-daemon clean build
# 使用 JDK 21：
bash ./forge-1.21.1/gradlew --project-dir forge-1.21.1 --no-daemon clean build
bash ./neoforge-1.21.1/gradlew --project-dir neoforge-1.21.1 --no-daemon clean build
bash ./fabric-1.21.1/gradlew --project-dir fabric-1.21.1 --no-daemon clean build
# 使用 JDK 25：
bash ./fabric/gradlew --project-dir fabric --no-daemon clean build
python -m unittest discover -s scripts -p 'test_*.py' -v
```

Shell 的 `HTTPS_PROXY` 不等于 Java 代理设置；需要代理的 Java 进程还需配置 `http.proxyHost/http.proxyPort` 和 `https.proxyHost/https.proxyPort`。

## 产物

当前版本 `2.1.0-alpha.2`：

- Forge 1.20.1：`build/libs/super-disc-forge-1.20.1-2.1.0-alpha.2-all.jar`
- Forge 1.21.1：`forge-1.21.1/build/libs/super-disc-forge-1.21.1-2.1.0-alpha.2-all.jar`
- NeoForge 1.21.1：`neoforge-1.21.1/build/libs/super-disc-neoforge-1.21.1-2.1.0-alpha.2.jar`
- Fabric 1.21.1：`fabric-1.21.1/build/libs/super-disc-fabric-1.21.1-2.1.0-alpha.2.jar`
- Fabric 26.2：`fabric/build/libs/super-disc-fabric-26.2-2.1.0-alpha.2.jar`

不要安装 Forge 不带 `-all` 的开发输出。
五个安装包均包含 JLayer、对应 LGPL 许可证和源码归档；不需要额外安装 JLayer。
Fabric API 不内嵌，必须由玩家单独安装。

## 验证层级

五个 `build` 都运行真实核心源码的 `RegressionChecks`，包括路径、缓存、取消、PCM 循环、增益和播放时钟。
Fabric 26.2 额外执行 `FabricChecks`，三个 1.21.1 目标执行 `Minecraft121Checks`，检查存档往返和实际游戏类中的声音接口签名。
这些检查不等同于启动游戏、实际应用 Mixin、OpenAL 播放或双客户端联机。
`scripts/release.py prepare` 检查最终 JAR 元数据、资源、入口、Java 字节码、内嵌库、许可证及 Forge 重混淆标识。
运行时验收请执行 `TESTING.md`；Forge 旧存档与 Fabric 新存档不承诺互相转换。

## 后续复用

同一组目标发布新版本时，只需修改根 `mod_version`、整理 changelog、运行验证，再推送对应附注标签。
新增目标时，先实现独立工程或共享版本适配，再向 `scripts/targets.json` 添加实际工具链、输出、依赖和元数据约束。
Windows 构建脚本、CI、Release 和汇总程序读取这份矩阵；测试自动按矩阵生成各目标的校验案例。
如新版本改变资源、元数据或 Mixin 映射格式，需要扩展安装包校验器并补测试，不能仅修改矩阵宣称适配。

官方工具参考：

- https://github.com/FabricMC/fabric-example-mod/tree/26.2
- https://docs.fabricmc.net/develop/
- https://docs.minecraftforge.net/en/fg-6.x/
