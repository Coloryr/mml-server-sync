# 命令行用法

两种入口共用同一个 jar：Java Agent（见 [agent.md](agent.md)）和命令行。
命令行适合启动器的「启动前执行命令 / 自定义脚本」，也方便排查问题。

## 基本用法

```text
java -jar mml-server-sync.jar --manifest <URL|文件> --game-dir <目录> [选项]
```

```powershell
java -jar mml-server-sync.jar ^
  --manifest https://example.com/server.json ^
  --game-dir "D:\Games\MyInstance\.minecraft"
```

基础包默认解压到 `.minecraft` 的父目录；实例目录结构不同时显式给 `--base-dir`：

```powershell
java -jar mml-server-sync.jar -m ./server.json -g ./Instance/game --base-dir ./Instance
```

`--manifest` 也接受本地文件；相对路径按当前工作目录解析，Windows 盘符路径会转成 `file:`
地址。

## 参数

| 参数 | 说明 |
| --- | --- |
| `--manifest, -m` | MML `server.json` 的 URL 或本地路径 |
| `--game-dir, -g` | 游戏目录；mods、config 等写到这里 |
| `--base-dir` | 基础包解压根目录；默认 `.minecraft` 的父目录，否则同游戏目录 |
| `--state-dir` | 状态目录；默认 `<game-dir>/.mml-server-sync` |
| `--force` | `PackVersion` 相同也强制同步（也不看清单指纹） |
| `--dry-run` | 只打印计划，不下载、不删除、不解压，也不弹窗 |
| `--no-delete` | 不删除旧清单里已移除的受管文件 / 目录 |
| `--quiet` | 少输出 |
| `--log` | 日志文件；默认 `<state-dir>/mml-server-sync.log` |
| `--threads` | 同时下载的文件数，1–64；默认 16 |
| `--skip` | 这次不启用的自选模组，清单相对路径；可重复或逗号分隔 |
| `--skip-file` | 从文件读要跳过的路径，UTF-8，一行一个，`#` 开头忽略 |
| `--no-gui` / `--gui` | 关 / 开进度窗口（默认开，headless 自动关） |
| `--no-ask` | 不弹自选模组勾选窗（无记录时按禁用安装） |
| `--no-update` / `--update` | 关 / 开自更新（默认开） |
| `--update-index` | 自更新索引地址；默认项目主页的 `index.json` |
| `--curseforge-api-key` | CurseForge API Key；缺省读环境变量 `CURSEFORGE_API_KEY` |
| `--version` | 显示版本 |
| `--help, -h` | 显示帮助 |

`--lenient` 是 Agent 内部使用的开关（清单拉不到时只记日志、不中止启动），命令行一般不用。

`--x=值` 与 `--x 值` 两种写法都支持。

## 退出码

| 退出码 | 含义 |
| --- | --- |
| `0` | 成功；或版本未变 / 清单指纹命中而跳过；或取消时选了「跳过更新」 |
| `1` | 同步失败；或取消时选了「结束游戏」 |
| `2` | 参数错误 |

## 启动器接入

填到启动器的「启动前执行命令 / 自定义脚本」，失败时不要让启动器继续启动：

```bat
@echo off
java -jar "%~dp0mml-server-sync.jar" -m "https://example.com/server.json" -g "%~dp0.minecraft"
if errorlevel 1 exit /b 1
```

启动器已经进入实例目录时可以用相对路径：

```bat
java -jar "D:\Launcher\mml-server-sync.jar" -m "https://example.com/server.json" -g ".minecraft"
if errorlevel 1 exit /b 1
```

Linux / macOS：

```bash
java -jar mml-server-sync.jar -m "https://example.com/server.json" -g ".minecraft" || exit 1
```

## 跳过部分自选模组

```text
--skip=mods/a.jar,mods/b.jar      # 可重复
--skip-file skip.txt              # UTF-8，一行一个，# 开头忽略
```

给了 `--skip` 时自动弹窗不再出现，但进度窗的「自选模组」按钮仍然可用。
未列出的自选模组按状态文件里的选择处理。详见 [optional-mods.md](optional-mods.md)。

## 先看计划再动手

```powershell
java -jar mml-server-sync.jar -m https://example.com/server.json -g .minecraft --dry-run --no-gui
```

`--dry-run` 会打印：清单名 / 版本、将删除的文件与目录、需要检查的文件数、本次新增或
内容变化的可选模组及其启用 / 禁用计划。它不会下载、删除、解压，也不会写状态文件。
