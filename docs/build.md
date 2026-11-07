# 构建与发版

## 环境

- 构建：JDK 17+（推荐 21/25），Gradle Wrapper；
- 产物：Java 8 字节码，可在 Java 8–25 运行。

## 构建

Windows：

```powershell
.\gradlew.bat build
```

Linux / macOS：

```bash
chmod +x gradlew
./gradlew build
```

产物：

```text
build/libs/mml-server-sync.jar
```

Wrapper 首次运行会把 Gradle 下到用户目录；已有 Gradle 时也可以直接 `gradle build`。
Gradle 的 `jar` 任务已经写入：

```text
Main-Class:    com.coloryr.mml.serversync.Main
Premain-Class: com.coloryr.mml.serversync.Agent
```

## 生成 index.json

发布包用 Gradle 任务生成，哈希不用手填：

```powershell
.\gradlew releaseIndex
```

默认产物：

```text
build/release/index.json
build/release/mml-server-sync-1.4.3.jar
```

- `version` / `url` 取 `build.gradle` 的版本号，jar 名固定为 `mml-server-sync-<version>.jar`；
- `sha1` / `sha256` 自动计算；
- 会自动校验 `build.gradle` 与 `Main.java` 里的 `VERSION` 一致，不一致直接报错；
- `url` 默认写相对文件名（相对 index.json 所在目录解析），要指向别处用
  `-PreleaseUrl="https://..."`；
- 备注用 `-PreleaseNotes="..."`；
- 输出目录用 `-PreleaseDir=dist`（相对路径按仓库根目录解析）。

把 `index.json` 和版本化 jar 一起传到更新索引所在目录（默认
`https://www.coloryr.com/mml-server-sync/`）即可。

## 版本号

发版时改这几处，保持一致：

- `src/main/java/com/coloryr/mml/serversync/Main.java` 的 `VERSION`；
- `build.gradle` 的 `version`；
- `CHANGELOG.md` 新增对应版本；
- 然后跑一次 `.gradlew releaseIndex` 生成 `index.json` 与版本化 jar。

## 自更新

启动时检查索引（默认 `https://www.coloryr.com/mml-server-sync/index.json`，与 jar 放在
同一个目录）。版本比当前程序新时，把新 jar 下到「当前 jar 同目录 / update /」下：
名为 `mml-server-sync-<版本>.jar`，旁边写 `.sha1` / `.sha256` 旁车文件；
**下一次启动**由独立类加载器加载它，本次运行仍是原版本，不会覆盖正在用的 jar。

索引格式：

```json
{
  "version": "1.4.3",
  "url": "mml-server-sync-1.4.3.jar",
  "sha1": "可选的 SHA1，填了就校验",
  "sha256": "可选的 SHA256，填了就校验",
  "notes": "可选，只写进日志"
}
```

- `url` 可以写相对地址（相对索引自身的地址解析）：index.json 与 jar 放同一个目录，
  整包传到哪个路径都能用。
- 版本只比数字段：`1.4.3 > 1.4.2 > 1.4.1`；`-beta` 这类后缀不参与比较。
- 加载前会校验：jar 结构 / CRC、启动清单与入口类、class 版本、以及下载时记下的哈希；
  不通过就跳过并删掉坏包，回退到当前版本。
- 换索引：`--update-index <URL>`、`-Dmml.serverSync.updateIndex=...`、
  `MML_SERVER_SYNC_INDEX=...`；本地调试可用 `file:///D:/.../index.json`。
- 关掉自更新：`--no-update`、`-Dmml.serverSync.update=false`、`MML_SERVER_SYNC_NO_UPDATE=1`。
- 索引必须是 JSON：地址被指到网页（常见于域名首页）时只记一行
  `更新索引返回的是网页而不是 JSON，跳过自更新`，不影响同步。
- 任何一步失败只记一行日志，不影响同步本身。