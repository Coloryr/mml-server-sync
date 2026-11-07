# Java Agent 接入

把 jar 挂到游戏 JVM，`Agent.premain` 会在游戏 `main` 之前完成同步。

```text
-javaagent:D:\Launcher\mml-server-sync.jar=https://example.com/server.json
```

- 等号右边是 **`server.json` 的完整地址**（也可以是本地路径），不是发布目录。
- Prism 等启动器不会展开 `$INST_DIR` 之类的变量；jar 放在实例目录时直接写相对路径，
  工作目录就是 `.minecraft`：

  ```text
  -javaagent:mml-server-sync.jar=https://example.com/server.json
  ```

## 启动时的行为

1. 先判断是不是 MML 启动器在启动；是就跳过同步，不重复执行。
2. 拉清单：网络故障、404、清单格式错时**只记日志并跳过本次同步**，游戏照常启动——
   这时一个文件都还没动过。
3. 同步：文件下载失败、哈希不匹配等会中止启动，不会带着半套文件进服。
4. `PackVersion` 与上次相同（且没给 `--force`）时直接跳过。

进度窗会先尝试创建：安卓启动器（FCL / Zalith / PojavLauncher）的 AWT 由 cacio 的
javaagent 提供，而它排在同步 agent 之后，premain 里初始化 Swing 通常会落到 X11 并失败。
失败时只记一行日志，然后按无界面继续同步，不会再因为弹窗问题中止启动；
自选模组按无界面规则安装（无记录时禁用）。

## 工作目录与状态目录

- 工作目录必须是游戏目录（`.minecraft`）；启动器没设置时显式指定
  `-Dmml.serverSync.gameDir=D:\Instance\.minecraft`。
- 状态目录默认 `<game-dir>/.mml-server-sync`，见 [sync.md](sync.md#状态文件)。
- 基础包默认解压到 `.minecraft` 的父目录（实例根目录）；实例目录结构不同时改用
  命令行入口并显式给 `--base-dir`。

## 配置项

| JVM 属性 | 环境变量 | 说明 |
| --- | --- | --- |
| `mml.serverSync.gameDir` | `MML_SERVER_SYNC_GAME_DIR` | 游戏目录；工作目录不是 `.minecraft` 时用 |
| `mml.serverSync.stateDir` | `MML_SERVER_SYNC_STATE_DIR` | 状态目录，默认 `<game-dir>/.mml-server-sync` |
| `mml.serverSync.log` | — | 日志文件路径，默认 `<状态目录>/mml-server-sync.log` |
| `mml.serverSync.force` | `MML_SERVER_SYNC_FORCE` | 忽略 `PackVersion`，强制同步 |
| `mml.serverSync.threads` | — | 同时下载的文件数，1–64，默认 16 |
| `mml.serverSync.gui` | `MML_SERVER_SYNC_NO_GUI` | 进度窗开关；`gui=false` 或设了 `NO_GUI` 时关闭 |
| `mml.serverSync.noAsk` | `MML_SERVER_SYNC_NO_ASK` | 关闭自选模组弹窗（默认允许弹窗） |
| `mml.serverSync.skip` | `MML_SERVER_SYNC_SKIP` | 逗号分隔的清单路径，按「未勾选」处理 |
| `mml.serverSync.update` | `MML_SERVER_SYNC_NO_UPDATE` | 自更新开关 |
| `mml.serverSync.updateIndex` | `MML_SERVER_SYNC_INDEX` | 自更新索引地址 |
| `mml.serverSync.lang` | `MML_SERVER_SYNC_LANG` | 界面语言：`zh` / `en` |
| — | `MML_SKIP` | 跳过 MML 启动器识别（强制走同步，用于在 MML 下测试） |
| — | `CURSEFORGE_API_KEY` | CurseForge API Key |

属性优先于环境变量；布尔值认 `1 / true / yes / mml`。

## 怎么识别 MML 启动器

先看 `MML_SKIP`：为真时整段检查都不做，直接按非 MML 启动器同步。否则按顺序检查：

1. JVM 属性 `-Dmml.launcher=1`，或环境变量 `MML_LAUNCHER=1`（MML 1.4.0+ 默认写入）；
2. **MML 自己写的 ARG_ 启动变量**：
   - `ARG_GAME_DIR` 与当前游戏目录一致；
   - `ARG_GAME_BASE_DIR` 与游戏目录的父目录一致；
   - `ARG_GAME_UUID` / `ARG_GAME_NAME` 非空时进一步确认。
3. **MML 的默认用户目录隔离**：
   - `HOME`、`USERPROFILE`、`APPDATA`、`LOCALAPPDATA`（以及 `user.home`）
     精确指向实例根目录；
   - agent 用「游戏目录的父目录」和这些环境变量比对。
4. Java 9+ 反射读取父进程命令行，匹配 `mml-gui` / `ModernMinecraftLauncher` /
   `mml_launcher`。

安卓启动器（FCL / PojavLauncher 等）会带 `FCL_*` / `POJAV_*` / `MOD_ANDROID_RUNTIME` /
`DALVIK_JAVAVM` 中的环境变量，这时直接按非 MML 处理；`XDG_*` 不再作为判据——
这些启动器会把 `XDG_DATA_HOME` 放进版本目录，按前缀判断会误判成 MML。

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
要在 MML 启动器里强行测试更新，把 `MML_SKIP` 设为 `true`。
