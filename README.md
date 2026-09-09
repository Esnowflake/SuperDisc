# Super Disc · 超级唱片

Forge **1.20.1**，Java **17**，独立的新工程。模组 ID：`super_disc`。新增物品 ID：`super_disc:super_disc`。

## 编译与安装

在 PowerShell 中运行：

```powershell
$env:JAVA_HOME = 'D:\Program Files\JDK-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
Set-Location 'E:\MX\Super Disc'
.\gradlew.bat jarJar --no-daemon --console=plain
```

**安装 `build/libs/super-disc-forge-1.20.1-1.0.0-all.jar`**。它内含 JLayer MP3 解码库。请勿把普通 JAR 和 `-all.jar` 同时装入；多人游戏的服务器及每个客户端安装同一个 `-all.jar`。

也可以运行带资源及成品检查的构建脚本：

```powershell
powershell -ExecutionPolicy Bypass -File '.\build.ps1'
```

构建脚本将编译输出保存至项目内 `build-output.log`。它仅在你执行时编译。本次交付进行了源码语法、Forge API 签名及资源结构检查，**尚未编译 Mod，也未完成 Minecraft/双客户端实测**。

IntelliJ IDEA 直接打开本文件夹，选择 Gradle、JDK 17。需要开发启动配置时执行 `./gradlew.bat genIntellijRuns`。Gradle 使用现有全局依赖缓存，无需移动旧 YX 工程。

## 使用

金色外圈、绿色中心的超级唱片在创造模式“工具与实用物品”栏中。也可以 `/give @s super_disc:super_disc`。

```
金锭  金锭    金锭
金锭  绿宝石  金锭
金锭  金锭    金锭
```

输出 1 张。手持超级唱片右键**原版唱片机（jukebox）**绑定并打开界面。这里使用的是能播放唱片的唱片机，并非红石触发单音的音符盒（note_block）。超级唱片作为可重复使用的控制物品，不消耗、不插入原版唱片槽。

已绑定的唱片机，其他玩家也可以右键打开界面；无需再拿超级唱片。原版唱片机内若已有唱片，先取出。界面中“解除绑定”会停止自定义音乐并恢复普通唱片机交互。

1. 选择 `.mp3` 或 `.ogg`（Ogg Vorbis）文件，也可输入绝对路径或拖入一个文件。
2. 选定文件后自动开始校验、上传和同步，不必先点击播放，也不再等待本机整首解码后才上传。接收后各客户端独立解码，以实际 PCM 采样数确认时长；点击“开始播放”后等收听者全部就绪再播放。
3. 暂停后“开始播放”继续当前位置；“重新播放”从头开始。
4. 模式默认为“播完暂停”，点击切换为循环。
5. 重新打开选择窗口后取消/关闭，或直接清空路径，会停止播放、取消同步，并通知服务器和收听者清理未完成的 `.part` 文件。后台解码也会取消并清理临时 PCM。同步期间每秒检查源文件是否仍存在：若在电脑上删除了源文件，同样取消同步。完整且通过校验的缓存保留，Mod 不主动删除用户的源文件。
6. 音量滑块为共享设置，范围 **0–200%**；100% 保持原音量，0% 静音。按整首 PCM 的峰值计算可用增益，最高放大两倍，空间不足时限制实际放大倍数。使用固定线性增益保留波形，避免硬削波和动态压缩；原本接近满幅的音频可能无法继续变响。最终音量仍受游戏主音量及“唱片/音符盒”音量影响，原文件已有的失真无法消除。

## 联机、时钟与权限

- 每个世界维度及唱片机坐标独立存储状态，支持多台同时播放。
- 导入者 UUID 是该曲目的进度条控制者，服务器会验证权限，不取决于谁放置唱片机。其他人仍能播放、暂停、重播、切模式、调音量或更换音乐。更换音乐后控制权转给新导入者。
- 本地绝对路径不传给其他玩家。客户端提交文件名、大小、哈希、时长，服务器接收并校验文件，按受限大小的分块转发。
- 64 格内的玩家提前同步，当前在线导入者也加入同步。首次播放等这些玩家全部 ready 后预约同一服务器时间开始。中途靠近/加入的玩家校验完成后从当前时间加入。
- 每游戏刻读取 OpenAL 实际采样位置，每秒向服务器上报导入者位置；进度条和时间文字使用同一次设备时钟读数。队列不提交空缓冲；实际尾段耗尽后发送结束事件，同一播放事件不会重建尾段音源。循环由结束事件开启下一次播放，不使用推算时间提前回绕。导入者离线或时钟失联时使用服务器估计时间兜底。网络及设备缓冲会带来误差，不能承诺逐采样完全一致。
- 输出经过 Minecraft 的 RECORDS 声音系统及原版线性距离衰减（约 16 格听觉范围，200% 不扩大范围）。音频混为单声道以支持定位，保持原采样率。
- 唱片机播放时每秒产生原版 NOTE 音符粒子。
- 同步通知每秒更新聊天栏，界面开关只影响本人的通知，保存在本地。
- 破坏唱片机后停止播放。服务器重启保留绑定、音乐、权限、音量及进度，默认暂停；不强制加载区块。

## 缓存与文件结构

缓存基于 Forge 的实际游戏工作目录（开启版本隔离时即版本目录），例如：

```
.minecraft/versions/JDen/super_disc/
  client.properties
  audio_cache/
    <SHA-256>_<文件名>.mp3
    <SHA-256>_<文件名>.ogg
    <SHA-256>.mono-v1.pcm
```

同名且内容相同复用缓存；同名但内容不同分别存储。收到的文件会校验大小和 SHA-256。PCM 是本机预解码的中间文件。服务器也在自己的游戏工作目录缓存文件；多人服务器运行时不依赖 `E:` 盘。

单文件上限 32 MiB / 20 分钟，PCM 上限 256 MiB；Ogg 仅支持 Vorbis，不是 Opus。当前最多同时播放 32 台、一个世界最多绑定 256 台。传输超时或任一收听者解码失败会暂停并提示，可再次尝试。

```
src/main/java/dev/superdisc/
  SuperDisc.java           物品注册、创造栏
  Net.java                 双向分包及方向约束
  Track.java               播放状态/时钟/序列化
  Cache.java               命名、哈希、校验、临时文件
  DiscData.java            世界存档数据
  ServerPlayback.java      同步、权限、控制、粒子
  client/
    ClientEvents.java      客户端事件、模型诊断
    ClientPlayback.java    缓存、收发、时钟、通知
    AudioDecoder.java      MP3/Vorbis → 磁盘 PCM
    DiscSound.java         游戏声音系统、实际播放位置
    DiscScreen.java        界面、文件选择、滑块
src/main/resources/
  META-INF/mods.toml
  pack.mcmeta
  assets/super_disc/models/item/super_disc.json
  assets/super_disc/textures/item/super_disc.png
  assets/super_disc/lang/{zh_cn,en_us}.json
  data/super_disc/recipes/super_disc.json
tools/
  RegressionChecks.java   独立时钟、增益、取消与缓存回归检查
  verify-resources.ps1     资源检查（不编译）
  ParseSources.java        Java 17 语法检查（不构建 Mod）
  super_disc-art.png       图标生成原稿
```

诊断请查看游戏目录 `logs/latest.log` 中的 `Super Disc` / `Audio` 日志，以及构建时的 `build-output.log`。不要把本目录 `tools/*.jar` 当成 Mod 安装。

本次修改完成了 Java 17 语法、资源结构和独立核心回归检查，未执行 Gradle 构建或游戏联机测试。核心回归检查可执行 `& 'D:\Program Files\JDK-17\bin\java.exe' tools/RegressionChecks.java`；它只在临时目录编译并运行隔离测试，不构建 Mod。网络协议已升级为 2，客户端与服务器需要一起更新。

队列计时依据 [OpenAL 1.1 规范](https://www.openal.org/documentation/openal-1.1-specification.pdf)：采样偏移相对于仍在队列中的全部缓冲；并已核对本机 Minecraft 1.20.1 的 Channel/ChannelAccess 队列维护顺序。

## 实测清单

见 [TESTING.md](TESTING.md)。旧音响 YX 模组不会被本工程修改；这个新 Mod 不包含旧音响方块或模型。

实现依据：[Forge 1.20.1 开发文档](https://docs.minecraftforge.net/en/1.20.1/gettingstarted/)、[ForgeGradle Jar-in-Jar](https://docs.minecraftforge.net/en/fg-6.x/dependencies/jarinjar/)及本机 Forge 47.2.0 的实际 API。MP3 解码采用 JLayer 1.0.1（LGPL，独立嵌套 JAR）；Ogg Vorbis 使用 Minecraft 自带解码器。
