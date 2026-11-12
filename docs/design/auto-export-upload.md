# 自动导出数据库后发送到服务器

个人 fork 功能：自动导出数据库(`DatabaseExportWorker`)完成后，把快照上传到 ROG 的 webhook 接收端，替代原先的 Syncthing 外部同步。

## App 端
- 设置：设置 → 自动化 → 自动导出数据库 → "发送到服务器"分类(`res/xml/auto_export_upload_settings.xml`,只由 `AutoExportDbSettingsFragment` 追加，ZIP/GPX/FIT 导出不受影响)。
  - `auto_export_upload_enabled`,`auto_export_upload_url`(默认 `http://100.106.169.46:8789/gadgetbridge`),`auto_export_upload_token`。
  - 状态写入 `auto_export_upload_last_success` / `auto_export_upload_last_error`(`<毫秒>|<消息>`)。
- 启用上传后导出位置变为可选：先 `GBDatabaseManager.exportDB(File)` 导出到 `cacheDir/auto_export_upload.db`,如设置了 SAF 位置再复制过去，然后上传，最后删除快照。不要从 SAF 文件读回(只有写权限)。
- 上传失败：`runAttemptCount < 4` 时返回 `Result.retry()`(WorkManager 退避重试，会重新导出),之后返回 failure 并弹导出失败通知。
- mainline 主 manifest 用 `tools:node="remove"` 去掉了 INTERNET;`app/src/mainline/AndroidManifest.xml` 重新加回(flavor manifest 优先级更高)。改动 manifest 后用 `aapt2 dump permissions` 确认 APK 里有 INTERNET。
- 明文 HTTP:`network_security_config.xml` 上游本就允许 cleartext。tailnet 由 WireGuard 加密，因此默认地址用 http + tailnet IP。

## 线协议
`POST <url>`,`Authorization: Bearer <token>`,`Content-Type: application/x-sqlite3`,`Content-Encoding: gzip`,`X-Gadgetbridge-Db-Bytes: <解压后字节数>`,body 为 gzip 后的 SQLite 文件(chunked)。服务端在原子替换文件后返回 2xx `{"ok":true,"bytes":N,"received_at":...}`;App 只把 `ok:true` 当成功。

## 服务端(ROG,不在本仓库)
- `C:\Users\Meta\Project\Scripts\JavaScript\webhooks`:`gadgetbridge.mjs` + `receiver.mjs` 的 `/gadgetbridge` 路由，令牌 `%LOCALAPPDATA%\obsidian-webhook-receiver\gadgetbridge-secret.txt`。
- 写入 `GADGETBRIDGE_EXPORT_FILE`(run-receiver.ps1 设为 `work-review\data\gadgetbridge\export\Gadgetbridge.db`),临时文件 + fsync + rename,Windows 上文件被占用时会重试。下游 `GadgetbridgeArchive` 不需要改。
- tailnet 入口：`tailscale serve --bg --tcp 8789 tcp://127.0.0.1:8788`(仅 tailnet,不经 Funnel)。
