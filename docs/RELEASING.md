# 草稿发布

## 发布准备

1. 在功能分支修改根 `gradle.properties` 的 `mod_version`。不复用已公开版本。
2. 将 `CHANGELOG.md` 的 Unreleased 内容归档至唯一的 `## [版本] - YYYY-MM-DD`。
3. 完成本地五个目标的构建、发布工具测试及安装包验证。
4. 通过 PR 合入 `main`，确认 CI 成功。运行 `TESTING.md` 的游戏验收；未验收目标必须标注实验性。
5. 创建与版本一致的附注标签，再将标签推送到指定仓库。不要向未经授权的仓库推送。

标签支持 `vX.Y.Z`、`vX.Y.Z-alpha.N`、`vX.Y.Z-beta.N`、`vX.Y.Z-rc.N`，N 从 1 开始。
Release 工作流要求标签提交是 `main` 的祖先。
所有版本只生成 Draft Release；预发布后缀还会设置 prerelease。工作流不会公开草稿。

## 本地打包

在干净的输出目录中执行：

```sh
python -m unittest discover -s scripts -p 'test_*.py' -v
python scripts/release.py validate v2.1.0-alpha.2
python scripts/release.py prepare v2.1.0-alpha.2 --target forge-1.20.1 --output release/local/forge-1.20.1
python scripts/release.py prepare v2.1.0-alpha.2 --target fabric-26.2 --output release/local/fabric-26.2
python scripts/release.py prepare v2.1.0-alpha.2 --target forge-1.21.1 --output release/local/forge-1.21.1
python scripts/release.py prepare v2.1.0-alpha.2 --target neoforge-1.21.1 --output release/local/neoforge-1.21.1
python scripts/release.py prepare v2.1.0-alpha.2 --target fabric-1.21.1 --output release/local/fabric-1.21.1
python scripts/release.py assemble v2.1.0-alpha.2 --inputs release/local --output release/assembled
python scripts/release.py verify v2.1.0-alpha.2 --inputs release/assembled
```

输出目录存在时拒绝覆盖；重试使用新的目录，不混入历史包。
模组版本改变时命令中的标签也要同步改变。

## 云端流程

`release.yml` 从标签源码重新构建矩阵中的五个目标，上传各自经过验证的 bundle。
汇总任务要求目标集合恰好齐全，复核哈希和元数据，再上传唯一的完整发布工件。
只有最后的 publish 任务拥有 `contents: write`，使用内置 `GITHUB_TOKEN`。
发行说明变更部分仅取自对应 changelog 章节；附带目标、Java、依赖、实验性限制和校验方式。
上传附件为五个独立安装 JAR 和 `SHA256SUMS.txt`。

## 失败与重跑

- 构建失败：修复后使用新版本标签；不移动已公开标签。
- 发布任务中断：优先重跑失败的 publish 任务，复用同一次运行的已验证附件。
- 已存在草稿：核对标题、正文、标签和 prerelease，仅补齐缺失附件。
- 已存在同名附件：下载并逐字节比较；相同跳过，不同则停止，绝不 `--clobber`。
- 已公开 Release：拒绝修改，不自动删除、替换或转回草稿。
- API 权限、网络和分页异常：停止，不能当作 Release 不存在。
- GitHub 上传不是事务操作，中断可能留下不完整草稿，公开前必须确认所有任务成功。

本地的 `scripts/publish.py` 与 Actions 使用同一实现，但本地不应上传开发机旧包替代标签构建。
若需要人工恢复，应下载对应运行的 `release-verified` 工件，核对标签提交后再操作。

## 维护者设置

对 `main` 启用 PR 和 CI 必过规则，限制绕过者；保护 `v*` 标签的创建、更新和删除。
私有仓库的规则可用性受 GitHub 账户套餐影响，需要维护者在设置页确认。
脚本标签格式校验不能代替发布者授权和仓库规则。
公开草稿前，检查安装、客户端启动、专用服务端启动及双客户端联机。
