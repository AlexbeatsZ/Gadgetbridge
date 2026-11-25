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
- 手机发消息时默认先发子命令 4 拉起快应用，等子命令 6 后再发 8 和 7。快应用已在前台时，S4 收到 4 不会再发 6，消息会一直排队到下次打开(2026-11-22 实测)。因此快应用最近 30 秒内发过 6 或 9 时，直接发 8 和 7，不再拉起；超过 30 秒或断线后仍走拉起流程。快应用在这 30 秒内退出时，这条消息会丢失，请求-应答协议应由手表端超时重试。
- 子命令 9 总是记日志。只有设备设置里开启了「第三方应用收发手表应用消息」(`third_party_apps_watch_app_messages`,与 Intent API 发送共用)才广播 `nodomain.freeyourgadget.gadgetbridge.WATCH_APP_MESSAGE_RECEIVED`,extras 为 `device`、`appId`、`payload`(UTF-8 字符串)。广播不限接收方，开关就是授权。若手机上与 appId 同名的包声明了接收该 action 的接收器，则只定向发给它(`setPackage`)，这样 Android 8+ 的清单静态接收器在应用未运行时也能收到，其他应用收不到；否则保持隐式广播。
- 包名和签名在 Gadgetbridge 路径下不校验:debug 签名的 rpk 也能互通。

## 已知限制(S4 固件,2026-11)

- 手表→手机单条消息实测 4 KB(4126 字节)可达，8 KB 和 16 KB 未到达 Gadgetbridge；手机→手表上限尚未测得。
- 快应用 `@system.record` 为 null,第三方应用拿不到麦克风。
- 快应用原生 `@system.fetch` 报 "Couldn't connect to server",且不经过命令类型 20;手机侧网络代理通道未实现。联网需求应让快应用经 interconnect 交给手机端代发(AstroBox FetchBridge 采用同样做法)。

## 验收

- 单元测试 `XiaomiRpkServiceTest`:抓包字节、状态请求回 CONNECTED、手表消息不触发回包。
- 单元测试 `XiaomiRpkServiceTest` 另覆盖前台直发、30 秒窗口外重新拉起和断线清空；`XiaomiRpkTargetTest`(Robolectric)覆盖按包名定向广播。
- 实机(2026-11-22,K70 + S4,GB 2026.11.16/code272,探针 1.1.0):手表→手机请求全部到达，Mac 经 adb 应答约 0.1 秒交给 GB；快应用前台时回包因上述问题全部超时，前台直发改动尚未装机实测。
- 实机:K70 + S4,探测快应用 `com.meta.s4probe` 发送 `{"hello":"s4"}`,手表显示 send 成功,GB 日志收到 14 字节。
