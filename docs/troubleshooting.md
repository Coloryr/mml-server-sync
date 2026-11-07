# 常见问题

## 「获取清单」一直停着

先看日志 `<状态目录>/mml-server-sync.log`：

- 有 `清单接收中 3.4 MB/7.7 MB（44 KB/s）` 每秒一条 → 是网络慢，不是卡死；换个网络或
  让发布端确认支持 gzip（8 MB 清单压缩后约 1 MB）。
- 同一数字停住超过 120 秒 → 单次读超时，会报错退出，不会永远挂着。
- 有 `HTTP 404` / `HTTP 403` → 清单地址不对或没有权限。

`--manifest` 必须是 **`server.json` 的完整地址**，不是发布目录；本地文件也可以。

## 每次启动都重新下载整份清单

说明本地 `last-manifest.json` 与远端 `server.json.sha1` 的 SHA-1 不一致：
可能上次没同步成功，或发布端刚换过清单。成功同步一次后就会命中指纹，只拉 40 字节的
`.sha1`。`--force` 会让每次都重下。

## 「更新索引不可用」/「更新索引返回的是网页」

自更新索引地址（默认 `https://www.coloryr.com/mml-server-sync/index.json`）返回的不是
JSON。常见原因是域名首页或 404 页面。修好索引，或者用 `--no-update` /
`-Dmml.serverSync.update=false` / `MML_SERVER_SYNC_NO_UPDATE=1` 关掉自更新。
这不会影响同步本身。

## 自更新没有生效

- 新版本是**下次启动**才生效：本次运行仍是旧版本。
- 只有以 jar 方式运行才会自更新；`--version` / `--help` 不会联网。
- 检查 `update/` 目录里是否有 `mml-server-sync-<版本>.jar`；索引里的 `version` 必须比当前大，
  `url` 要能下载到，填了哈希就必须匹配。
- 校验不通过的新包会被删掉并回退到当前版本，日志里会有一行原因。

## 中文系统显示英文

Prism 等启动器会给游戏 JVM 强制 `-Duser.language=en`。程序会按系统编码（中文 Windows
为 GBK 系列）回退识别；如果仍显示英文，显式指定：

```text
-Dmml.serverSync.lang=zh
```

或环境变量 `MML_SERVER_SYNC_LANG=zh`。

## 哈希校验失败 / zip CRC 错误

- 先重试一次：可能是代理或 CDN 缓存了旧文件；
- 删掉目标同目录的 `<文件名>.part` 后重试；
- 持续失败说明发布端的清单哈希与文件不一致，需要发布端修复。

## 点了「取消更新」之后

- 点下去**立即暂停**下载，然后才弹确认框；选「继续更新」会从暂停处继续。
- 选「跳过更新」或「结束游戏」后，进度条定格，窗口最多 2 秒关闭。
- 正在下载的单个文件不会写坏：先写 `.part`，校验通过才原子替换；取消时目标文件保持旧版本。
- 配置压缩包 / 基础包是解压到目标目录的，若在解压途中中断，可能留下部分目录，
  下次启动会重新下载并重新解压。

## 在 MML 启动器里没有同步

Agent 识别到 MML 启动器会主动跳过，避免重复同步。要在 MML 下强制测试，设环境变量
`MML_SKIP=true`。

## 找不到游戏目录 / 状态写到了奇怪的地方

Agent 要求工作目录是游戏目录（`.minecraft`）。启动器没有设置时加：

```text
-Dmml.serverSync.gameDir=D:\Instance\.minecraft
```

## 启动报 UnsupportedClassVersionError

jar 是 Java 8 字节码，需要 Java 8 及以上；报这个错说明用的是更老的 JRE。
