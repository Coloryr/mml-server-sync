# mml-server-sync

MML 服务器实例专用更新器，只读取 MML 生成的 `server.json`，同步 **游戏目录** 里的模组、
配置与基础文件。用于 PCL2、HMCL、MultiMC 等非 MML 启动器启动 MML 服务器实例之前的更新；
不兼容 HMCL `server-manifest.json` 或其他清单格式。

## Java Agent 用法

推荐直接挂到游戏 JVM：

```text
-javaagent:D:\Launcher\mml-server-sync.jar=https://example.com/server.json
```

`Agent.premain` 在游戏 `main` 之前执行同步，完成后再进游戏。下载失败会抛异常中止启动，
不会带着旧文件继续进服；但**清单本身拉不到**（网络故障、404、清单格式错）时只记日志并
跳过本次同步，游戏照常启动 —— 这时一个文件都还没动过。

- 工作目录必须是游戏目录（`.minecraft`）。启动器没有设置工作目录时，额外加：
  `-Dmml.serverSync.gameDir=D:\Instance\.minecraft`
- 状态目录可用 `-Dmml.serverSync.stateDir=...` 或环境变量 `MML_SERVER_SYNC_STATE_DIR` 覆盖。
- 需要忽略 `PackVersion` 强制检查时，加 `-Dmml.serverSync.force=true` 或环境变量
  `MML_SERVER_SYNC_FORCE=1`。
- 需要在 MML 启动器下强制走同步时，设环境变量 `MML_SKIP=true`，跳过 MML 识别。
- CurseForge API Key 读环境变量 `CURSEFORGE_API_KEY`。
- 识别到 MML 启动器会直接跳过，不重复同步。
- 同时下载的文件数默认 16，可用 `-Dmml.serverSync.threads=8` 调整。
- 同步时默认弹一个 Swing 进度窗口，见「进度窗口」；`-Dmml.serverSync.gui=false` 或
  `MML_SERVER_SYNC_NO_GUI=1` 关掉。
- 启动时检查自更新，默认索引 `https://www.coloryr.com/mml-server-sync/index.json`；
  `--no-update` / `-Dmml.serverSync.update=false` / `MML_SERVER_SYNC_NO_UPDATE=1` 关掉。
- 可选模组有更新时会弹窗让你勾选，见「可选模组勾选」；脚本可用
  `-Dmml.serverSync.skip=mods/a.jar,mods/b.jar` 或环境变量 `MML_SERVER_SYNC_SKIP` 跳过指定条目。
- 同步日志写在 `<状态目录>/mml-server-sync.log`，每行以 `[MML-SERVER-SYNC]` 开头，
  日志文件可用 `-Dmml.serverSync.log=...` 覆盖。
- 下载时每秒打一条字节进度，例如 `[download] config.zip 43% (9.2/22.0 MB, 6.3 MB/s)`。
- 拉清单默认带 `Accept-Encoding: gzip`：Gitea 这类支持压缩的托管会把 8 MB 的 `server.json`
  按约 1 MB 传输；服务端不支持压缩时按原样读取。收清单时每秒一条
  `清单接收中 3.4 MB/7.7 MB（44 KB/s）`，慢也只是慢，不会看起来像卡死；
  同一数字停住超过 120 秒（单次读超时）就会报错退出，不会永远挂着。
  解压后的清单字节才是状态文件与 `server.json.sha1` 比对的对象。
- 命令行模式仍然可用，见下文；两种模式共用同一个 jar。

### 怎么识别 MML 启动器

先看环境变量 `MML_SKIP`：为真时整段检查都不做，直接按非 MML 启动器同步。
否则按顺序检查：

1. JVM 属性 `-Dmml.launcher=1`；
2. **MML 自己写的 ARG_ 启动变量**：
   - `ARG_GAME_DIR` 与当前游戏目录一致；
   - `ARG_GAME_BASE_DIR` 与游戏目录的父目录一致；
   - `ARG_GAME_UUID` / `ARG_GAME_NAME` 非空时进一步确认。
3. **MML 的默认用户目录隔离**：
   - `HOME`、`USERPROFILE`、`APPDATA`、`LOCALAPPDATA` 指向实例根目录；
   - `XDG_CONFIG_HOME` / `XDG_DATA_HOME` / `XDG_CACHE_HOME` 位于实例根目录下；
   - agent 用「游戏目录的父目录」和这些环境变量比对。
4. Java 9+ 反射读取父进程命令行，匹配 `mml-gui` / `ModernMinecraftLauncher` /
   `mml_launcher`。

MML 启动游戏时会写入：

```text
ARG_JAVA_LOCAL
ARG_JAVA_ARG
ARG_LAUNCHER_DIR
ARG_GAME_NAME
ARG_GAME_UUID
ARG_GAME_DIR
ARG_GAME_BASE_DIR
```

Agent 用 `ARG_GAME_DIR` / `ARG_GAME_BASE_DIR` 做路径匹配，不依赖某个开关是否被转发。
Java 8 没有 `ProcessHandle`，靠第 1–3 条；只要 MML 开着默认用户目录隔离就能识别。
如果要在 MML 启动器里强行测试更新，把 `MML_SKIP` 设为 `true`。

## 进度窗口

同步时默认弹一个 Swing 窗口：标题「整合包更新」，确认清单后顶行换成包名与版本；
阶段行按流程变化——获取清单指纹 → 下载远程配置（细节行显示已接收 MB 数）→ 检查本地文件
→ 对比改动 → 清理旧文件 → 下载文件 (n/N)（细节行显示当前文件名）→ 安装基础文件 →
解压配置包 → 保存同步状态。同步一结束窗口就关；agent 模式同步完立刻关掉，不拖游戏启动。

底部两个按钮：

- **取消更新**：点击（或关窗）会问一句——跳过本次更新（退出码 0，游戏照常启动，
  已写入的文件下次启动补齐）还是结束游戏（退出码 1，agent / 启动前脚本中止启动）；
  点 Esc 或关掉确认框继续更新。下载与解压循环里每 1 MB、收清单时每 256 KB 检查一次；
  确认取消后窗口最多 2 秒内自行关闭，不等主线程从网络读取里轮询出来。
- **自选模组**：清单下载完成后可点，见「可选模组勾选」；下载计划定稿后按钮收起。
- 关掉窗口：命令行 `--no-gui`，JVM 属性 `-Dmml.serverSync.gui=false`，
  环境变量 `MML_SERVER_SYNC_NO_GUI=1`。
- 无图形环境（纯终端服务器、headless）自动不显示，不用额外参数。

## 界面语言

按系统语言自动选：`zh*` 用中文，其余一律英文；窗口、帮助、日志都跟着变。要强制指定用
`-Dmml.serverSync.lang=zh|en` 或环境变量 `MML_SERVER_SYNC_LANG`。

Prism 等启动器会给游戏 JVM 强制 `-Duser.language=en`，此时按系统编码（中文 Windows 为
GBK 系列）与地区码回退识别，界面仍然显示中文；显式覆盖永远优先。

## 自更新

启动时检查索引（默认 `https://www.coloryr.com/mml-server-sync/index.json`，与更新用的
jar 放在同一个目录）。版本比当前程序新，就把新 jar 下到「当前 jar 同目录/update/」下，
**下一次启动**由独立类加载器加载它；本次运行仍是原版本，不会覆盖正在用的 jar。

索引格式：

```json
{
  "version": "1.4.1",
  "url": "mml-server-sync-1.4.1.jar",
  "sha1": "可选的 SHA1，填了就校验",
  "sha256": "可选的 SHA256，填了就校验",
  "notes": "可选，只写进日志"
}
```

- `url` 可以写相对地址（相对索引自身的地址解析）：index.json 与 jar 放同一个目录，
  整包传到哪个路径都能用。
- 版本只比数字段：`1.2.1` > `1.2.0`；`-beta` 这类后缀不参与比较。
- 加载前会校验：jar 结构 / CRC、启动清单与入口类、class 版本、以及下载时记下的哈希
  （`<jar>.sha1` / `.sha256` 旁车文件）；不通过就跳过并删掉坏包。
- 换索引：`--update-index <URL>`、`-Dmml.serverSync.updateIndex=...`、
  `MML_SERVER_SYNC_INDEX=...`；本地调试可用 `file:///D:/.../index.json`。
- 关掉自更新：`--no-update`、`-Dmml.serverSync.update=false`、`MML_SERVER_SYNC_NO_UPDATE=1`。
- 索引必须是 JSON。地址被指到网页（常见于域名首页）时只记一行
  `更新索引返回的是网页而不是 JSON，跳过自更新`，不影响同步。
- 任何一步失败只记一行日志，不影响同步本身。

## 可选模组

自选模组（清单里带 `Optional`，按 `mods/*自选*` 等规则标记）是**选择制**：内容始终同步，
勾选装成启用（清单路径），不勾装成禁用（`.disabled`）。选择记在状态目录的
`optional-state.txt`，下次同步按它执行；旧版 `skipped.txt` 会自动迁移成「未勾选」。

弹窗有两个入口：

- **自动弹出**：首次同步（没有选择状态），或检测到自选模组新增 / 内容变化时——
  新条目在列表里标注「新增」。同步不中断，继续在后台下载；收尾时暂停，等玩家确认后
  把文件按勾选归位（通常只是改名），然后才写状态、放行游戏启动；
- **「自选模组」按钮**：进度窗上常驻，清单解析完成即可点，随时打开管理窗改选择。

弹窗行为：

- 列出全部自选模组，列为：选择 / 模组 / 介绍 / 状态（新增、需更新、已同步，附当前
  启用状态）；首次同步默认全选；
- **介绍**来自发布端放在清单地址旁的 `server.json.notes.json`（路径 → 简介文字），
  没有该文件时留空；
- 关窗、Esc 与「确定」同义：按当前勾选继续，没有取消出口；要中止整次更新用主窗的
  「取消更新」（跳过本次 = 退出码 0，结束游戏 = 退出码 1）；
- 未勾选的自选模组装成 `.disabled`，玩家在游戏里自己改名启用，或下次在弹窗里勾上。

无交互环境（`--no-gui`、headless、`--no-ask`）不弹窗：没有记录的条目按禁用安装。
`--skip` / `--skip-file` 把指定路径按「未勾选」处理（装成禁用），给了 `--skip` 时
自动弹窗不再出现（按钮仍可用）。命令行写法：

```text
--skip=mods/a.jar,mods/b.jar      # 可重复
--skip-file skip.txt              # UTF-8，一行一个，# 开头忽略
```

Agent 模式用 `-Dmml.serverSync.skip=...` 或环境变量 `MML_SERVER_SYNC_SKIP`；
Agent 默认允许弹窗（Prism 等启动场景窗口前有人），`-Dmml.serverSync.noAsk=true` 或
环境变量 `MML_SERVER_SYNC_NO_ASK=1` 关掉。

## 要求

- 运行：Java 8–25
- 构建：JDK 17+（推荐 21/25；Gradle 用 `--release 8` 产出 Java 8 字节码）
- 只支持 zip 配置包与基础包（MML 生成器输出的 `Archives` / `Base` 都是 zip）
- 需要能访问清单里的下载地址；CurseForge 项目未带 URL 时：

  - 有 API Key：`--curseforge-api-key` 或环境变量 `CURSEFORGE_API_KEY`
  - 没有 API Key：按 fileId 推导 `edge.forgecdn.net` CDN 地址，个别被作者关闭 CDN 的文件会失败

## 构建（Gradle）

需要 JDK 17+（推荐 21/25）和 Gradle Wrapper；jar 本身编译为 Java 8 字节码，可在
Java 8–25 运行。

Windows：

```powershell
cd tools\server-sync
.\gradlew.bat build
```

Linux / macOS：

```bash
cd tools/server-sync
chmod +x gradlew
./gradlew build
```

产物：

```text
tools/server-sync/build/libs/mml-server-sync.jar
```

Wrapper 首次运行会把 Gradle 9.5.0 下载到用户目录；已经有 Gradle 时也可以直接
`gradle build`。

Gradle 的 `jar` 任务已经写入：

```text
Main-Class:    com.coloryr.mml.serversync.Main
Premain-Class: com.coloryr.mml.serversync.Agent
```

## 用法

```text
java -jar mml-server-sync.jar --manifest <URL|文件> --game-dir <目录> [选项]
```

例如：

```powershell
java -jar mml-server-sync.jar ^
  --manifest https://example.com/server.json ^
  --game-dir "D:\Games\MyInstance\.minecraft"
```

MML 基础包要解压到实例根目录（`.minecraft` 的父目录），默认会自动推断；实例目录不是
`.minecraft` 时显式传：

```powershell
java -jar mml-server-sync.jar -m ./server.json -g ./Instance/game --base-dir ./Instance
```

常用参数：

| 参数 | 说明 |
| --- | --- |
| `--manifest, -m` | MML `server.json` 的 URL 或本地路径 |
| `--game-dir, -g` | 游戏目录；mods、config 等写到这里 |
| `--base-dir` | MML 基础包解压根目录；默认 `.minecraft` 的父目录，否则同游戏目录 |
| `--state-dir` | 清单状态目录；默认 `<game-dir>/.mml-server-sync` |
| `--force` | `PackVersion` 相同也强制同步 |
| `--dry-run` | 只打印计划，不下载、不删除、不解压 |
| `--no-delete` | 不删除旧清单里已移除的受管文件 / 目录 |
| `--quiet` | 少输出 |
| `--log` | 同步日志文件；默认 `<state-dir>/mml-server-sync.log` |
| `--threads` | 同时下载的文件数，1-64；默认 16 |
| `--skip` | 这次不更新的可选模组，清单相对路径；可重复或逗号分隔 |
| `--skip-file` | 从文件读要跳过的路径，UTF-8，一行一个，`#` 开头忽略 |
| `--no-gui` | 不显示进度窗口 |
| `--no-ask` | 不弹可选模组勾选窗，全部更新 |
| `--no-update` | 不检查自更新 |
| `--update-index` | 换自更新索引地址；默认 `https://www.coloryr.com/mml-server-sync/index.json` |

退出码：`0` 成功或版本未变跳过，`1` 同步失败，`2` 参数错误。

## 启动器接入

把下面命令填到启动器的「启动前执行命令 / 自定义脚本」里；失败时不要让启动器继续启动：

```bat
@echo off
java -jar "%~dp0mml-server-sync.jar" -m "https://example.com/server.json" -g "%~dp0.minecraft"
if errorlevel 1 exit /b 1
```

如果启动器已经进入实例目录，可以用相对路径：

```bat
java -jar "D:\Launcher\mml-server-sync.jar" -m "https://example.com/server.json" -g ".minecraft"
if errorlevel 1 exit /b 1
```

## 同步规则

- **清单指纹**：发布目录里有 `server.json.sha1` 时先取它和本地清单比 SHA1，没变就跳过整份
  下载；没有这个文件、取不到或内容不是 40 位哈希时照旧下载 `server.json`。`--force` 不看指纹。
  指纹只比字节：换过清单内容就要重下一份，这一步默认请求 gzip 压缩，且日志里每秒一条进度。
- **版本**：清单的 `PackVersion` 与上次状态相同则直接跳过；`--force` 可强制。
- **文件**：清单 `Files` / `files` 里的相对路径按 SHA1（没有则 SHA256）比对；
  缺失或哈希不同的才下载。被改名停用的文件（`.disabled` / 旧版 `.disable`，不限 `.jar`）
  同样按内容比对，不会因为文件名变了就重新下一份、把玩家停用的模组或资源包装回启用。
- **可选模组**：清单里带 `Optional` 的条目按玩家的选择安装——勾选装成启用（清单路径），
  未勾选装成 `xxx.jar.disabled`（与 MMC / HMCL 的后缀一致）；内容每次按哈希同步，
  勾选变化只引起改名。选择记录在状态文件里，首次同步与内容变化时弹窗确认。
- **删除**：旧清单有、当前清单没有的受管文件会删除，启用 / 禁用两种文件名一起删；
  同一项目编号换了路径时删除旧路径；
  旧配置压缩包被移除且 `Over=true` 时删除对应目录。清单之外的用户文件不动。
- **配置压缩包**：按目录深度从浅到深解压；`Over=true` 先删除旧目录；同名、同目录、
  同哈希且目标目录还在时可跳过。
- **基础包**：只在第一次同步（状态里没有 Base）时下载并解压到 `baseDir`；
  之后即使清单里仍有 Base 也不会重复下载。

## 状态与回退

状态在：

```text
<game-dir>/.mml-server-sync/last-manifest.json
<game-dir>/.mml-server-sync/optional-state.txt
<game-dir>/.mml-server-sync/mml-server-sync.log
```

第一次运行时如果状态不存在，会尝试用实例根目录的 `server.json` 作为旧清单；之后只读写
状态文件，不会改 MML 的 `server.json`。

MML 的 `server_instance_ids.json`、`server.json`、`game.json`、`icon.png` 等
元数据不会被基础包覆盖；基础包里有这些文件会直接报错。

## 已知边界

- 不会安装 Java、加载器或游戏本体，只更新实例文件。
- 不会执行 MML 的模组依赖检查、在线信息回写、实例设置回写。
- 只支持 zip；如果配置包是 tar.gz/7z，需要自行转换或扩展 `Fs.extractZip`。
- CurseForge 无 URL 且无 API Key 时依赖 CDN 命名规则，可能个别文件失败。
- 自定义条目（清单里没有项目编号）的模组身份按路径算：换了文件名会被当成新条目，
  跳过记录也按路径记，下次会重新询问。
