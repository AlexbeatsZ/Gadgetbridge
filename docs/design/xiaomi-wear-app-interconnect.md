# 小米手表快应用 interconnect(手机端)

手表上的 Vela 快应用通过 `@system.interconnect` 与手机端通信。官方 App 在手机侧扮演对端；Gadgetbridge 由 `XiaomiRpkService`(命令类型 20 `THIRDPARTY_APP`)承担。

## 子命令对照

字段名以 AstroBox-NG-Module-Pb 的 `wear_thirdparty_app.proto` 为准，Gadgetbridge 的旧命名保留未改。

| 子命令 | 官方名 | 方向 | Gadgetbridge 名 | 负载 |
|---|---|---|---|---|
| 4 | LAUNCH_APP | 手机→手表 | `CMD_RPK_LAUNCH` | LaunchInfo |
| 6 | REQUEST_PHONE_APP_STATUS | 手表→手机 | `CMD_RPK_LAUNCHED` | BasicInfo(包名+指纹)，GB 读作 `rpkDel` |
| 7 | SYNC_PHONE_APP_STATUS | 手机→手表 | `CMD_RPK_SESSION_END` | PhoneAppStatus,`unknown2=1` 即 CONNECTED |
| 8 | SEND_PHONE_MESSAGE | 手机→手表 | `CMD_RPK_MESSAGE` | MessageContent |
| 9 | SEND_WEAR_MESSAGE | 手表→手机 | `CMD_RPK_WEAR_MESSAGE` | MessageContent,GB 读作 `rpkMessage` |

## 行为

- 手表在快应用打开 interconnect 时发子命令 6。手机必须回子命令 7 CONNECTED,否则快应用 `send` 报 `not connected`(code 1006)。无待发消息时也要回；有待发消息时沿用上游抓包顺序：先发消息，再发状态。
- 子命令 9 总是记日志。只有设备设置里开启了「第三方应用收发手表应用消息」(`third_party_apps_watch_app_messages`,与 Intent API 发送共用)才广播 `nodomain.freeyourgadget.gadgetbridge.WATCH_APP_MESSAGE_RECEIVED`,extras 为 `device`、`appId`、`payload`(UTF-8 字符串)。广播不限接收方，开关就是授权。
- 包名和签名在 Gadgetbridge 路径下不校验:debug 签名的 rpk 也能互通。

## 已知限制(S4 固件,2026-11)

- 快应用 `@system.record` 为 null,第三方应用拿不到麦克风。
- 快应用原生 `@system.fetch` 报 "Couldn't connect to server",且不经过命令类型 20;手机侧网络代理通道未实现。联网需求应让快应用经 interconnect 交给手机端代发(AstroBox FetchBridge 采用同样做法)。

## 验收

- 单元测试 `XiaomiRpkServiceTest`:抓包字节、状态请求回 CONNECTED、手表消息不触发回包。
- 实机:K70 + S4,探测快应用 `com.meta.s4probe` 发送 `{"hello":"s4"}`,手表显示 send 成功,GB 日志收到 14 字节。
