# mml-server-sync

MML 服务器实例（整合包）的独立更新器：读取 MML 生成的 `server.json`，在启动游戏前把
模组、配置与基础文件同步到 **游戏目录**。供 PCL2 / HMCL / Prism / MultiMC 等非 MML
启动器使用，也支持 FCL / PojavLauncher 等安卓启动器；不兼容 HMCL
`server-manifest.json` 或其他清单格式。

当前版本 **1.9.0**，更新记录见 [CHANGELOG.md](CHANGELOG.md)。

## 与 MML 的关系

- MML 的清单本体只有 `server.json`（发布时另生成 `server.json.sha1` 指纹）；
  可选模组和配置同步方式都在这一份清单里。
- MML 启动器自己启动游戏时，Agent 会识别并跳过同步，改用 MML 原生逻辑，不会双跑。
- `server.json.sync.json` / `server.json.notes.json` 是**本更新器的扩展**，
  MML 不生成也不读取；完整差异见 [docs/differences.md](docs/differences.md)。

## 特性

- **增量更新**：先比 `server.json.sha1` 指纹，再按 SHA-1 / SHA-256 比对，只下载缺失
  或内容变化的文件；指纹变了就按新清单同步。
- **两种入口**：Java Agent 直挂游戏 JVM，在 `main` 之前完成更新；也支持
  `java -jar` 命令行 / 启动器「启动前脚本」。
- **配置目录四种同步方式**：压缩包整包 / 压缩包本目录 / 逐文件整目录 / 逐文件本目录，
  按目录选一种；另有「不同步」「删除目录」。
- **自选模组**：`Optional` 条目勾选才下载/更新，不勾选本次跳过、旧文件保留；
  支持每次启动改选、记住选择、`--skip` / `--skip-file`。
- **更新器扩展**：可用 `server.json.sync.json` 的 `Removed` / `Ignore`
  清理历史废弃文件、保护玩家私有文件；可用 `server.json.notes.json` 显示模组简介。
- **安全落盘**：单文件先写 `.part`，哈希校验通过后原子替换；下载中断不覆盖旧版本，
  瞬时错误自动重试。
- **进度与取消**：Swing 进度窗显示阶段、进度与当前文件，可暂停、跳过本次更新或
  结束游戏；失败时可选择重试 / 跳过 / 关闭游戏。
- **自更新**：读取更新索引（默认 `https://www.coloryr.com/mml-server-sync/index.json`），
  新版本下载到 jar 同目录的 `update/`，下次启动由独立类加载器加载；用 `--update-url`
  （或 `-Dmml.serverSync.updateUrl` / `MML_SERVER_SYNC_UPDATE_URL`）可以换成自己的更新地址。
- **界面**：中英双语按系统语言切换；FCL / Pojav / Zalith 等移动启动器自动放大界面，
  可用 `--ui-scale=2.5` 覆盖。

## 代码结构

| 包 | 内容 |
| --- | --- |
| `com.coloryr.mml.serversync` | 入口与引导：`Main`、`Agent`、`Bootstrap`、`Runner` |
| `com.coloryr.mml.serversync.sync` | 同步核心：清单、下载、落盘、删除、自选模组状态 |
| `com.coloryr.mml.serversync.update` | 自更新：索引、下载、更新包类加载器 |
| `com.coloryr.mml.serversync.ui` | Swing 界面：进度窗、缩放、自选模组与确认框 |
| `com.coloryr.mml.serversync.util` | 日志、文案、JSON、取消状态与异常 |
| `com.coloryr.mml.serversync.host` | 宿主与更新包共享的 `Progress` 接口 |

`Runner` 的类名和 `run` 签名跨版本保持稳定（自更新会反射调用更新包里的这一份）。

## 快速开始

### Java Agent（推荐）

把 jar 挂到游戏 JVM：

```text
-javaagent:D:\Launcher\mml-server-sync.jar=https://example.com/server.json
```

- 等号右边是 **`server.json` 的完整地址**（也可以是本地路径），不是发布目录。
- 工作目录必须是游戏目录（`.minecraft`）；启动器没设置时加
  `-Dmml.serverSync.gameDir=D:\Instance\.minecraft`。
- 识别到 MML 启动器时会自动跳过；要在 MML 下强制测试，设环境变量 `MML_SKIP=true`。

### 命令行

```powershell
java -jar mml-server-sync.jar -m https://example.com/server.json -g "D:\Instance\.minecraft"
```

常用参数：

| 参数 | 说明 |
| --- | --- |
| `--force` | 忽略清单指纹，强制重新读取清单并同步 |
| `--dry-run` | 只打印计划，不下载、不删除、不解压、不写状态 |
| `--skip` | 指定自选模组本次不下载；可重复或逗号分隔 |
| `--no-delete` | 不删除旧清单里已移除的受管文件 |
| `--no-gui` | 不显示进度窗 |
| `--update-url` | 自更新地址（jar 自身）；可填 `index.json` 或它所在的目录 |

完整参数、退出码见 [docs/cli.md](docs/cli.md)。

### 启动前脚本

放进启动器的「启动前执行命令」时，失败要阻止启动：

```bat
@echo off
java -jar "%~dp0mml-server-sync.jar" -m "https://example.com/server.json" -g "%~dp0.minecraft"
if errorlevel 1 exit /b 1
```

同步日志默认写在
`<game-dir>/.mml-server-sync/mml-server-sync.log`。

## 配置目录：四种同步方式

MML 的「配置文件」页按目录选一种：

|  | 整棵子树 | 仅当前层 |
| --- | --- | --- |
| **压缩包** | 压缩包整包同步：整包解压前先删旧目录 | 压缩包本目录同步：只覆盖当前层，不删客户端目录 |
| **逐文件** | 逐文件整目录同步：子树里的文件逐个进 `Files` | 逐文件本目录同步：只有当前层的文件逐个进 `Files` |

另外还有两个辅助状态：

- **不同步**：这个目录不参与同步，父目录整包时也会被排除；
- **删除目录**：生成空 zip + `Over`，客户端据此删除该目录。

选择原则：

- 目录完全由发布端托管、不需要保留玩家私有文件 → **压缩包整包同步**；
- 目录里还有玩家私有文件/子目录 → **压缩包本目录同步** 或 **逐文件本目录同步**；
- 需要逐文件增量、逐文件可选、保留清单外文件 → **逐文件整目录 / 逐文件本目录**；
- 玩家完全私有的目录 → **不同步**；
- 需要清理旧目录 → **删除目录**，不要靠删 `Archives` 条目。

本更新器支持这四种方式；压缩包部分只支持 zip，MML 原生还支持 tar / tar.gz / tar.xz。

## 自选模组

清单里 `Optional: true` 的条目按这次的选择决定是否下载：

- 勾选：本次下载/更新清单里的版本；本地没有文件时按原名安装；
- 不勾选：本次不下载，本地旧文件（启用或 `.disabled`）原样保留。

勾选不会主动改变启用/禁用状态：本地已有 `xxx.jar` 就更新 `xxx.jar`，已有
`xxx.jar.disabled` 就更新那份，不会因为一次更新把它改回启用。

自动弹窗默认**每次启动都出现**，清单指纹命中、版本未变的启动也一样；勾选
「记住我的选择，以后不再显示」后写入 `optional-prompt.txt`，此后只在自选模组
新增 / 内容变化时弹。弹窗确认后才开始下载，没勾的模组不会被后台提前下载。

无图形界面、`--no-ask` 或弹窗不可用时：没有记录的条目**不下载**，已有记录按记录
执行（`1` 更新，`0` 跳过）。`--skip` / `--skip-file` 指定本次不下载的路径；
Agent 模式还可用 `-Dmml.serverSync.skip=...` 或环境变量 `MML_SERVER_SYNC_SKIP`。

选择记录在 `<状态目录>/optional-state.txt`（内容哈希 + 清单路径 + `1`/`0`）；
旧版 `skipped.txt` 会自动迁移成「跳过」后删除。

## 更新器扩展

这两份文件不是 MML 的设计，MML 不生成也不读取，需要发布端手动额外放置：

### `server.json.sync.json`

放在 `server.json` 同目录，更新器按 `<清单 URL>.sync.json` 读取：

```json
{
  "Removed": ["mods/old.jar"],
  "Ignore": ["options.txt", "shaderpacks/", "mods/*自选*"]
}
```

- `Removed`：历史废弃路径，首次同步与跨版本升级都能清理；仍在清单里、受保护、
  归档目录内和越界的路径不删。
- `Ignore`：目录前缀 / 通配 / 精确匹配，忽略大小写；命中的文件不下载、不覆盖、
  自选模组也不更新它，且不被 `Removed` 或旧清单差异删除。
- 旁文件不存在（404 / 本地文件缺失）时按空规则处理，旧清单删除照常；只有网络故障、
  返回网页或 JSON 损坏等无法确认的情况才停删除。
- 改了旁文件要同时改动 `server.json`（让 `.sha1` 变化），否则指纹命中会跳过整次同步。

### `server.json.notes.json`

可选的模组简介，JSON 对象，键是清单里的相对路径，值是简介文字；没有时留空。

> 同一份包同时给 MML 和本更新器用时，不要把关键删除/保护逻辑只放在扩展里：
> MML 用户不会执行 `Removed` / `Ignore`。

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
| [docs/differences.md](docs/differences.md) | 与 MML 原生同步的协议边界、四种配置方式和差异 |
| [docs/ui.md](docs/ui.md) | 进度窗、取消 / 暂停 / 继续、界面语言与缩放 |
| [docs/build.md](docs/build.md) | 构建与发版、自更新索引格式 |
| [docs/troubleshooting.md](docs/troubleshooting.md) | 常见问题排查 |
| [CHANGELOG.md](CHANGELOG.md) | 版本更新记录 |

## 要求

- 运行：Java 8–25；构建：JDK 17+（Gradle 用 `--release 8` 产出 Java 8 字节码）。
- 只支持 zip 格式的配置包与基础包。
- 需要能访问清单里的下载地址；CurseForge 条目没带 URL 时用 API Key 或 CDN 规则。
- Android 启动器的 AWT 可能初始化失败，此时自动退回无界面同步，不影响文件更新。

## 已知限制

- 不会安装 Java、加载器或游戏本体，也不执行 MML 的模组依赖检查、在线信息回写。
- 不回写实例的游戏版本/加载器，也不维护 `mml-link`；MML 导出的 MMC/Prism 包里的
  `mmc-pack.json` / `instance.cfg` 是静态的。
- 更新器压缩包只支持 zip；MML 原生支持更多格式。
- `server.json.sync.json` / `server.json.notes.json` 只对使用本更新器的启动器生效。
- 清单条目没有 `ProjectId`（自定义条目）时，身份按路径计算：发布端换了文件名会被
  当成新条目，选择记录也按路径记，下次会重新询问。

## 构建

```powershell
.\gradlew.bat build
```

产物：`build/libs/mml-server-sync.jar`。发布包用 `.\gradlew releaseIndex` 生成
（`index.json` + 版本化 jar）。构建细节、自更新索引格式与发版步骤见
[docs/build.md](docs/build.md)。

## 许可证

Apache-2.0，见 [LICENSE](LICENSE)。
