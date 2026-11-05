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
- 同步日志写在 `<状态目录>/mml-server-sync.log`，每行以 `[MML-SERVER-SYNC]` 开头，
  日志文件可用 `-Dmml.serverSync.log=...` 覆盖。
- 下载时每秒打一条字节进度，例如 `[download] config.zip 43% (9.2/22.0 MB, 6.3 MB/s)`。
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

- **版本**：清单的 `PackVersion` 与上次状态相同则直接跳过；`--force` 可强制。
- **文件**：清单 `Files` / `files` 里的相对路径按 SHA1（没有则 SHA256）比对；
  缺失或哈希不同的才下载。
- **删除**：旧清单有、当前清单没有的受管文件会删除；同一项目编号换了路径时删除旧路径；
  旧配置压缩包被移除且 `Over=true` 时删除对应目录。清单之外的用户文件不动。
- **配置压缩包**：按目录深度从浅到深解压；`Over=true` 先删除旧目录；同名、同目录、
  同哈希且目标目录还在时可跳过。
- **基础包**：只在第一次同步（状态里没有 Base）时下载并解压到 `baseDir`；
  之后即使清单里仍有 Base 也不会重复下载。

## 状态与回退

状态在：

```text
<game-dir>/.mml-server-sync/last-manifest.json
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
