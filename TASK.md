# TASK：mml-server-sync 支持「更新时挑选可选模组」

目标仓库：`D:/code/mml-server-sync`（独立 Java 更新器，供 PCL2 / HMCL / MultiMC 用）
对齐实现：M²L 仓库的 `mml-core/mml-game/src/serverpack/mod.rs`（`upgrade_serverpack_with`）与
`mml-gui/src-tauri/src/windows/resource/pack.rs`（`changed_optional_mods`）。
本任务只改 mml-server-sync，不改启动器。

## 1. 现状

- `Manifest.parse` 已认 `Files[].Optional`，`Sync.changedFiles` 会把新装的可选模组装成
  `<名字>.disabled`，`enabledIdentities` 让玩家启用过的模组换文件名后仍保持启用。
- 缺的是启动器现在有的那一步：更新时把这次会变的可选模组列出来让用户勾选，
  没勾的这次不更新、保留本地旧版本。
- 启动器侧的判断口径（照搬语义，不必照搬代码）：
  - 只列 `Optional` 且内容有变化的条目（含换了文件名）；附本地是否启用；
  - 跳过的条目不下载、旧文件不删，记下「清单路径 + 内容 SHA1 + 本地保留的旧文件路径」；
  - 发布方换了内容就重新询问；重新勾上时新版本落地，并按记录清掉旧文件；
  - 跳过之后再更新，玩家原来的启用 / 禁用状态照样延续。

## 2. 目标行为（验收口径）

1. 同步前，这次有可选模组会变（新增或内容变化）时弹窗列出它们，默认全勾，可全选 / 单个取消。
2. 没勾的：不下载新版本，本地旧文件原样留着（可能还带 `.disabled` / `.disable`）。
3. 选择要记住：同一 `PackVersion` 再次运行不再询问（本工具本来就按版本短路）；
   发布方发新版本、对应条目内容变了才重新询问。
4. 下次勾上时：新版本落地，上次留下的旧文件被清掉；本来启用着的仍装成启用。
5. 无交互环境（`--no-gui`、Agent 模式、headless）：不弹窗，全部更新，行为与现在一致。
6. 用户在弹窗里点「取消同步」：本次一个文件都不动，日志写一条，返回一句 summary。

## 3. 交互设计

- 新增 Swing 模态对话框 `OptionalModsDialog`，父窗口取 `ProgressWindow` 的 JFrame
  （`ProgressWindow` 需要暴露包内可见的 `frame()`；没有窗口时返回 null）。
- 只列会变的 optional 条目：文件名、清单里的相对路径、本地状态（新增 / 已禁用 / 旧文件名）。
- 顶部全选框与「已选 n / 总数」，底部按钮「取消同步」「更新所选」。
- 弹窗时机：`Sync.run()` 里算完「会变的 optional」之后、任何删除 / 下载之前。
- `--dry-run` 不弹窗，把「本次可选模组」「将跳过哪些」打进计划输出。
- headless（`GraphicsEnvironment.isHeadless()`）或 `gui=false` 时不弹窗，按全更新处理。

## 4. 非交互传入（脚本 / PCL2 / Agent 用）

- 命令行 `--skip <路径>`：可重复，也支持逗号分隔（`--skip=mods/a.jar,mods/b.jar`）；
  路径按清单里的相对路径，`/` 分隔。
- 命令行 `--skip-file <文件>`：UTF-8，每行一个路径，空行与 `#` 开头的行忽略。
- Agent 模式：JVM 属性 `-Dmml.serverSync.skip=...`（逗号分隔）与环境变量 `MML_SERVER_SYNC_SKIP`。
- 这些路径只用来否决「会变的 optional」；与弹窗结果取并集。给了 `--skip` 就不再弹窗（脚本优先）。

## 5. 状态文件

- 现有 `saveState(byte[])` 把远程清单原样写进 `<状态目录>/last-manifest.json`，**不要动它**。
- 新增 `<状态目录>/skipped.txt`（UTF-8，TSV，一行一条）：
  `sha1<TAB>清单路径<TAB>本地保留路径`
  - 路径统一 `/`；当时没有旧文件时第三列留空；
  - 文件不存在 = 没有跳过记录；
  - 同步成功后重写，没有跳过就删掉这个文件；
  - 用 TSV 是因为 `Json.java` 只有解析、没有写出，别为几个字段加一个序列化器。
- `loadOld()` 的 fallback（`<base-dir>/server.json`）可能带启动器写的 `Skipped`，不解析、不依赖。

## 6. 代码改造点

### Manifest.java
- `FileEntry` 加 `boolean hasSameContent(FileEntry other)`：SHA1 优先、退回 SHA256，都缺按不同处理。
- 不需要解析 `Skipped`。

### Options.java
- 加 `List<String> skip` 与 `Path skipFile`。

### Main.java
- 解析 `--skip` / `--skip-file`（`--x=值` 与 `--x 值` 两种写法都要），`printHelp()` 补说明与示例。
- `VERSION` 升到 `1.2.0`。

### Agent.java
- 读 `-Dmml.serverSync.skip` 与 `MML_SERVER_SYNC_SKIP`，填进 `options.skip`。

### Sync.java（核心）
1. `run()` 在 `validate` 之后、`enabledIdentities` 之前准备 `Set<String> skipPaths`：
   命令行 / 属性给的 + 弹窗里没勾的。
2. 新增 `List<Manifest.FileEntry> changedOptional(Manifest current)`：遍历 `current.files`，
   `optional` 且（`existingVariant` 为空 或 `Downloader.matches` 不通过）才收；
   与 `changedFiles` 同一判据，避免弹窗列了却因为哈希一致根本不下载。
3. 弹窗返回 null = 取消同步：直接 `return "已取消同步"`，不动任何文件。
4. `planRemovals(...)` 多一个 `Set<String> skippedIdentities`：身份在里面的旧文件不删
   （否则换文件名时旧文件被删、新的又不下载）。身份用 `FileEntry.identity()`。
5. `enabledIdentities(old)`：清单路径上找不到本地文件时，再看 `skipped.txt` 里该条目的 `kept` 文件。
6. 清理上次 kept：对 `loadSkipped()` 的每条，若本次不再跳过、`kept` 存在、且不等于当前清单里任一
   路径，就 `Fs.deleteFile(kept)` 并记日志（`--no-delete` 时跳过这一步）。
7. `changedFiles(...)`：`skipPaths` 里的条目直接跳过（不下载），但仍要写进 skipped.txt。
8. 记录本次跳过：`kept` 先按旧清单里同身份条目的本地文件（`existingVariant` 的结果，含禁用后缀）取，
   找不到再按新路径取；`sha1` 取新清单条目的 SHA1（空时用 SHA256）。
9. `saveState` 之后原子写 `skipped.txt`（`.tmp` + `Files.move`，与 last-manifest.json 同一做法）。

### ProgressWindow.java
- 暴露包内可见的 `JFrame frame()`，给弹窗当父窗口。

### 新增 OptionalModsDialog.java
- 构造参数：父 `Component`、行数据（路径 / 文件名 / 本地状态 / 旧名）。
- `static Set<String> ask(Component parent, List<Row> rows)`：返回未勾选的路径；取消返回 `null`。

## 7. 边界与坑

- Java 8 兼容：不用 `var`、`List.of`、switch 表达式；按 `javac --release 8` 编译。
- 不引第三方依赖（Swing / JDK 自带）。
- 路径一律 `/`，经 `Fs.resolveSafe` 解析，别自己拼字符串。
- `--dry-run` 只打印：不弹窗、不写 `skipped.txt`、不动文件。
- 首次同步（`old == null`）：所有 optional 都算「会变」，弹窗照常列，`kept` 为空。
- 自定义编号（`pid` 空）身份按路径：换名后会被当成新条目，跳过记录也按路径记；写进 README 的已知限制。
- 这个 jar 同时被 MML 启动器内嵌（`mml-gui/src-tauri/resources/mml-server-sync.jar`），
  改完要重新编译并覆盖那一份。

## 8. 验收

用本地 HTTP 服务或本地文件跑真实同步（场景与 `mml-core/mml-game/tests/server_pack_upgrade.rs` 相同）：

1. v1：两个 optional（a、b）+ 一个必装 → 首次同步 a、b 都是 `.disabled`。
2. 玩家启用 a → v2：两个都换名换内容 → 弹窗列出两条（a 显示已启用 + 旧名 `a-1.jar`，b 显示已禁用）。
3. 取消勾选 b → `a-2.jar` 落地且启用；`b-1.jar.disabled` 留着；`skipped.txt` 记一条（`b-2.jar` / `b-1.jar`）。
4. 再跑一次同版本：直接跳过，不再弹窗。
5. v3 再换内容 → 弹窗重新出现；都勾上 → `a-3.jar`、`b-3.jar.disabled` 落地，
   `a-1` / `b-1` 旧文件被清掉，`skipped.txt` 删除。
6. `--no-gui` 或 Agent 模式：不弹窗，全部更新。
7. `--no-gui --skip=mods/b-3.jar`：b 保持旧版本，a 正常更新。
8. `--dry-run`：只打印计划，不动文件、不写状态。

## 9. 文档与发布

- README：Agent 与命令行两节补「可选模组勾选」「--skip / --skip-file」「skipped.txt」；
  已知限制补自定义编号换名那条边界。
- `Main.printHelp()` 同步补参数与示例。
- `index.json` 的 `version` / `url` 改成新版本（发版时填 `sha1` / `sha256`）。
- 编译（Java 8）+ 自测后，把 jar 覆盖到
  `D:/code/ModernColoryrMinecraftLauncher/mml-gui/src-tauri/resources/mml-server-sync.jar`；
  那边 `include_bytes!` 会跟着重新打包，不需要改 Rust 代码。

## 10. 交付清单

- [ ] Manifest.java：内容比较辅助
- [ ] Options.java / Main.java / Agent.java：`--skip`、`--skip-file`、属性与环境变量、help、版本号
- [ ] Sync.java：会变的 optional 计算、弹窗、跳过、removal 保护、kept 清理、skipped.txt 读写
- [ ] OptionalModsDialog.java（新文件）
- [ ] ProgressWindow.java：暴露父窗口
- [ ] README / index.json 更新
- [ ] 验收 8 条跑过，jar 覆盖回启动器仓库
