# mml-server-sync

MML 服务器实例（整合包）的独立更新器：读取 MML 生成的 `server.json`，在启动游戏前把
模组、配置与基础文件同步到 **游戏目录**。供 PCL2 / HMCL / Prism / MultiMC 等非 MML
启动器使用，也支持 FCL / PojavLauncher 等安卓启动器；不兼容 HMCL
`server-manifest.json` 或其他清单格式。

当前版本 **1.7.0**，更新记录见 [CHANGELOG.md](CHANGELOG.md)。

## 特性

- **增量更新**：先比 `server.json.sha1` 清单指纹，再按 SHA-1 / SHA-256 比对，
  只下载缺失或内容变化的文件；指纹变了就按新清单同步。
- **两种入口**：Java Agent 直挂游戏 JVM，在 `main` 之前完成更新；也支持
  `java -jar` 命令行 / 启动器「启动前脚本」。
- **自选模组**：`Optional` 条目勾选才下载/更新，不勾选本次跳过、旧文件保留；
  默认每次启动都能改选，勾「记住我的选择」后只在新增 / 内容变化时弹；支持
  `--skip` / `--skip-file`。
- **同步规则（更新器扩展）**：更新器可读 `server.json.sync.json` 的 `Removed` / `Ignore`
  清理历史废弃文件、保护玩家私有文件；MML 只生成 `server.json`（和 `.sha1`），
  不会生成或读取这个旁文件。
- **配置目录四种同步方式**：压缩包整包 / 压缩包本目录 / 逐文件整目录 / 逐文件本目录，
  按目录选一种；另有「不同步」「删除目录」。配置包与基础包自动解压，空 zip 可作
  「墓碑包」删目录；从清单移除压缩包不会误删已解压目录。
- **安全落盘**：单文件先写 `.part`，哈希校验通过后原子替换；下载中断不覆盖旧版本，
  瞬时错误自动重试。
- **进度与取消**：Swing 进度窗显示阶段、进度与当前文件，可随时暂停、跳过本次更新或
  结束游戏；更新失败时可选择重试 / 跳过 / 关闭游戏（Agent 在图形界面可用时默认开启，
  命令行用 `--ask-on-failure`）。
- **自更新**：读取更新索引（默认 `https://www.coloryr.com/mml-server-sync/index.json`），
  新版本下载到 jar 同目录的 `update/`，下次启动由独立类加载器加载。
- **界面**：中英双语按系统语言切换；FCL / Pojav / Zalith 等移动启动器自动放大界面，
  可用 `--ui-scale=2.5` 覆盖。

## 快速开始

### Java Agent（推荐，Prism / HMCL / PCL2 通用）

把 jar 挂到游戏 JVM：

```text
-javaagent:D:\Launcher\mml-server-sync.jar=https://example.com/server.json
```

- 等号右边是 **`server.json` 的完整地址**（也可以是本地路径），不是发布目录。
- 工作目录必须是游戏目录（`.minecraft`）；启动器没设置时加
  `-Dmml.serverSync.gameDir=D:\Instance\.minecraft`。
- 识别到 MML 启动器时会自动跳过同步；要在 MML 下强制测试，设环境变量
  `MML_SKIP=true`。

### 命令行

```powershell
java -jar mml-server-sync.jar -m https://example.com/server.json -g "D:\Instance\.minecraft"
```

常用参数：

```text
--force          忽略清单指纹，强制同步
--dry-run        只打印计划，不下载、不删除、不解压、不写状态
--skip=mods/a.jar,mods/b.jar
                 指定自选模组本次不下载
--no-gui         不显示进度窗
--no-delete      不删除旧清单里已移除的受管文件
```

放进启动器的「启动前执行命令」时，失败要阻止启动：

```bat
@echo off
java -jar "%~dp0mml-server-sync.jar" -m "https://example.com/server.json" -g "%~dp0.minecraft"
if errorlevel 1 exit /b 1
```

同步日志默认写在
`<game-dir>/.mml-server-sync/mml-server-sync.log`。

## 自选模组

清单里 `Optional: true` 的条目按这次的选择决定是否下载：

- 勾选：本次下载/更新清单里的版本；本地没有文件时按原名安装；
- 不勾选：本次不下载，本地旧文件（启用或 `.disabled`）原样保留。

勾选不会主动改变启用/禁用状态：本地已有 `xxx.jar` 就更新 `xxx.jar`，已有
`xxx.jar.disabled` 就更新那份，不会因为一次更新把它改回启用。

自动弹窗默认**每次启动都出现**，清单指纹命中、包版本未变的启动也一样；勾选
「记住我的选择，以后不再显示」后写入 `optional-prompt.txt`，此后只在自选模组
新增 / 内容变化时弹。进度窗上的「自选模组」按钮可随时管理全部可选模组。

无图形界面、`--no-ask` 或弹窗不可用时：没有记录的条目**不下载**，已有记录按记录
执行（`1` 更新，`0` 跳过）。`--skip` / `--skip-file` 指定本次不下载的路径；
Agent 模式用 `-Dmml.serverSync.skip=...` 或环境变量 `MML_SERVER_SYNC_SKIP`。

选择记录在 `<状态目录>/optional-state.txt`（内容哈希 + 清单路径 + `1`/`0`）；
旧版 `skipped.txt` 会自动迁移成「跳过」后删除。

## 同步规则与删除

旧清单有、当前清单没有的受管文件会删除：原名和同名的 `.disabled` / `.disable`
副本一起清掉（与 MML 原生删除一致）。这次没勾选的自选模组，旧文件会保留。
`--no-delete` 可只跳过删除步骤。

MML 只生成 `server.json`（以及 `server.json.sha1` 指纹），**不会生成
`server.json.sync.json`**；这个旁文件是本更新器的扩展功能，不是 MML 的设计，
需要发布端手动额外放到清单同目录：

```json
{
  "Removed": ["mods/old.jar"],
  "Ignore": ["options.txt", "shaderpacks/", "mods/*自选*"]
}
```

- `Removed`：历史废弃路径，首次同步与跨版本升级都能清理；仍在清单里、受屏蔽保护、
  归档目录内和越界的路径一律不删。
- `Ignore`：目录前缀 / 通配 / 精确匹配，忽略大小写；命中的文件不下载、不覆盖，
  自选模组也不会更新它，且不被 `Removed` 或旧清单差异删除。
- 旁文件不存在（404 / 本地文件缺失）时按**空规则**处理，保留原来的旧清单差异删除；
  返回网页、JSON 损坏或网络故障等「无法确认」的情况才不执行删除和空包墓碑删除
  （下载照常；配置包的整目录覆盖仍按原规则执行）。
- 旁文件只在真正执行同步时读取；改了旁文件要同时改动 `server.json`（让 `.sha1`
  变化），否则清单指纹命中会跳过整次同步。

这套 `Removed` / `Ignore` 是发布端按「所有配置逐文件同步」使用时的**更新器扩展能力**，
对标 HMCL 服务器包的排除/强制删除；它不是 MML 的清单规范，MML 启动器既不生成也不
读取这个旁文件，详见 [docs/differences.md](docs/differences.md)。

## 状态与日志

默认状态目录为 `<game-dir>/.mml-server-sync`，可用 `--state-dir` 修改：

| 文件 / 目录 | 内容 |
| --- | --- |
| `last-manifest.json` | 上次同步的清单原文，用于指纹比对和读取旧清单 |
| `optional-state.txt` | 自选模组下载选择记录（内容哈希 + 路径 + `1`/`0`） |
| `optional-prompt.txt` | 「记住我的选择，以后不再显示」标记（`suppress=1`） |
| `mml-server-sync.log` | 同步日志 |
| `tmp/` | 配置包 / 基础包的下载中转目录 |

自更新使用的 `update/` 目录在 **jar 同目录**，不在状态目录里。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/agent.md](docs/agent.md) | Java Agent 接入、MML 启动器识别、JVM 属性与环境变量 |
| [docs/cli.md](docs/cli.md) | 命令行参数、退出码、启动器 / 启动前脚本接入 |
| [docs/optional-mods.md](docs/optional-mods.md) | 自选模组下载选择、弹窗、`optional-state.txt`、`--skip` |
| [docs/sync.md](docs/sync.md) | 同步规则、旁文件、状态文件、下载与解压细节、已知边界 |
| [docs/ui.md](docs/ui.md) | 进度窗、取消 / 暂停 / 继续、界面语言与缩放 |
| [docs/build.md](docs/build.md) | 构建与发版、自更新索引格式 |
| [docs/differences.md](docs/differences.md) | 与 MML 启动器原生同步的功能差异 |
| [docs/troubleshooting.md](docs/troubleshooting.md) | 常见问题排查 |
| [CHANGELOG.md](CHANGELOG.md) | 版本更新记录 |

## 要求

- 运行：Java 8–25；构建：JDK 17+（Gradle 用 `--release 8` 产出 Java 8 字节码）。
- 只支持 zip 格式的配置包与基础包。
- 需要能访问清单里的下载地址；CurseForge 条目没带 URL 时用 API Key 或 CDN 规则。
- Android 启动器的 AWT 可能初始化失败，此时自动退回无界面同步，不影响文件更新。

## 已知限制

- 不会安装 Java、加载器或游戏本体，也不执行 MML 的模组依赖检查、在线信息回写、
  实例设置回写。
- 只支持 zip；配置包是 tar.gz / 7z 时需要自行转换或扩展 `Fs.extractZip`。
- CurseForge 条目没带 URL 且没有 API Key 时依赖 CDN 命名规则，个别被作者关闭 CDN
  的文件会失败（见 [docs/troubleshooting.md](docs/troubleshooting.md)）。
- 清单条目没有 `ProjectId`（自定义条目）时，身份按路径计算：发布端换了文件名会被
  当成新条目，跳过 / 选择记录也按路径记，下次会重新询问。
- 旁文件读取失败（不是 404）时，本轮不执行旧文件 / 废弃列表删除和空包墓碑删除；
  需要清理旧文件时先修复旁文件。

## 构建

```powershell
.\gradlew.bat build
```

产物：`build/libs/mml-server-sync.jar`。发布包用 `.\gradlew releaseIndex` 生成
（`index.json` + 版本化 jar）。构建细节、自更新索引格式与发版步骤见
[docs/build.md](docs/build.md)。

## 许可证

Apache-2.0，见 [LICENSE](LICENSE)。
