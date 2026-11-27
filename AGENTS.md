# Gadgetbridge (local fork workspace)

本文件和 `docs/` 随个人分支提交到 GitHub `personal`,不进入给上游的 PR。

## Goal
- 让 K70 上的复旦大学联名交通卡通过 S4 使用：用户最新决定优先蓝牙代理（S4 NFC主机前端→Classic SPP→手机），A真实迁移暂停。2026-11-26 已在 `feat/xiaomi-voice-inject` 接入完整蓝牙刷卡链路（`local.meta.esenfc` `ApduRelayService` 0.6.0/code7 + Gadgetbridge `XiaomiNfcRelayBridge` 2026.11.19/code274 + S4 `host-relay-session`/`host-relay-pump` 表盘 `362991121` + 「NFC 刷卡」伴侣 RPK 1.1.1/code18），冷启动端到端实测到达 `S4 watch relay status=active`；真机刷卡门禁/闸机射频验收待用户实测。保护原卡、余额、校园绑定和配对，白名单限制交通联合/上海/PPSE/PSE AID 与 Gadgetbridge 证书。
- 在上游 Gadgetbridge(codeberg.org/Freeyourgadget/Gadgetbridge)基础上做个人定制功能.
- 已做: 设置中可指定 app 使用的时区,并同步到手表.
- 已做: 小米设备配对时可用 Root 从同一手机上的小米运动健康导入密钥。
- 已做: 自动导出数据库后通过 Tailscale 自动上传到 ROG(取代 Syncthing)。
- 进行中: 在小米手表 S4 上运行自定义快应用并替换语音助手。任务日志 `~/.agents/xiaomi-watch-s4--custom-apps-voice-assistant/AGENTS.md`,探测快应用项目 `/Users/meta/Developer/S4-Vela-Probe`。

## Current State
- 2026-11-26 用户授权部署：K70已原位更新2026.11.21/code276（Pixel保持清醒），沿用原debug证书cbde4ad4…，首次安装时间2026-11-10、dynamic主题、设备消息偏好保留，S4恢复连接。已包含inject分支v2026.11.19/code274的NFC转发。Dayweave手表0.2.1/code3圆形图标已装S4并收到安装完成通知，GitHub watch-v0.2.1预发布附RPK；真实睡眠/振动/响铃和手表图标肉眼效果仍待验收。
- 分支 `feat/app-timezone`(基于上游 master 8976702fc3)。2026-11-25 已合入 `feat/xiaomi-voice-inject`(手机助手、S4 活动通话设备、Gemini 播报、公交卡研究工具与快应用前台直发)，版本 2026.11.18/code273。合并后 Xiaomi、设置外观和主题迁移回归 418 项、0 失败、2 跳过；APK `app/build/outputs/apk/mainline/debug/app-mainline-debug.apk` 已用原 debug 证书 cbde4ad4… 签名。合并前 K70 运行的是 inject 分支的 2026.11.16/code272；2026-11-26 01:02(K70 时间)已覆盖安装 K70(`adb install -r --user current`，首次安装时间 2026-11-10 保留)，S4 原绑定重新认证、手表通话设备恢复 ACTIVE、手表消息开关仍为 true；GitHub 预发布 v2026.11.18 已附 APK。快应用前台回包尚未实机复测(用户暂时不能测试)。`origin` 是 Codeberg 上游，个人发布仓库为 GitHub `AlexbeatsZ/Gadgetbridge`，只推送 `personal`。
- mainline 名称为「手环」，使用用户提供的新图标及 Google Settings 风格原生设置行。默认动态取色，偏好迁移 73 将旧 system 一次性迁到 dynamic，保留 light/dark 与迁移后手动选择。旧 checkbox 显式布局别名复用新圆角行。设计见 `docs/design/pixel-settings.md`。
- 2026-11-15 已提交并推送 `610827c031`，个人 fork prerelease `v2026.11.5` 已发布。APK 为 `app/build/outputs/apk/mainline/debug/app-mainline-debug.apk`，沿用原 debug 签名，干净提交重建，项目仅保留当前 APK。
- 2026-11-15 主聊天已在 K70 覆盖安装 2026.11.5/code261。后台进程自动启动后只读确认 `pref_key_theme=dynamic`、`shared_preferences_version=73`，旧 system 自动迁移实机成立。安装后立即读取曾仍为 system，迁移在进程启动时执行。
- 自定义时区: 设置 → 日期和时间 → 时区(pref key `datetime_timezone`,空字符串=系统时区).
  - `util/AppTimeZone.java`: `apply()` 用 `TimeZone.setDefault()` 覆盖整个进程的默认时区;`setOverride()` 保存 + 应用 + 重排 DST 闹钟 + `onSetTime()` 同步手表.
  - 应用时机: `GBApplication.onCreate`(prefs 初始化后),`TimeChangeReceiver` 收到 `ACTION_TIMEZONE_CHANGED`,`DeviceActionHandler` 处理 `ACTION_SETTIME` 前.
  - 时区选择对话框抽成 `activities/TimeZonePickerDialog.java`,世界时钟页也改用它.

## Active Work
- 2026-11-26 已实现 S4「保持清醒」原生设置与每周规则：多时段/跨午夜/振动或寻找手表响铃、连续固件睡眠状态确认、数据过期与佩戴/充电门控；默认关闭。22项新测试、合并后共91项定向回归通过。全量lint仍有21处原有错误，本功能无新错误。设计 `docs/design/xiaomi-wake-guard.md`，2026-11-26用户授权部署并要求Pixel统一主题：列表/编辑已改用共用设置Activity/Fragment、Material对话框与时间选择器，删除旧网页示意；2026.11.21/code276已装K70，真机列表/编辑/时间选择器截图在app/build/reports/wake-guard-*-device.png；新建规则默认星期摘要问题已复现并修复，需最终重装核对。Dayweave圆形图标0.2.1/code3已装S4并收到安装完成。已合入inject分支v2026.11.19/code274的NFC转发功能。
- 手机助手分支 `Gadgetbridge-inject` / `feat/xiaomi-voice-inject` 的 2026.11.14/code270 已安装 K70 并在 personal 预发布。用户已验收 S4 播放 Gemini 原声，以及停留在助手界面黑屏后点亮退出时同步关闭手机 Gemini。K70 已开启播报。两种原生闹钟样卡均未出现，按用户决定保留现有 Markdown；样卡探针已撤下。
- 手机闹钟联动已补充 Xiaomi 原生 type17/subtype16 与 Google 时钟正在响铃通知入口。用户明确只需手机响铃时手表提醒，实机测试因用户上课而暂停。临时测试闹钟已取消并确认无遗留。当时K70为2026.11.13/code269；真实响铃/停止/稍后提醒验收仍未完成。
- S4 天气闹钟与手机语音助手两个分支已合并，2026.11.8/code264 已发布 personal prerelease 并覆盖安装 K70，merge 提交 956f07c668。S4 已用原密钥认证并初始化，启动无致命异常。合并后 295 项回归中 293 通过、2 跳过。用户已要求部署，覆盖此前保留手机语音实验构建的阶段性选择。Google 天气开关默认关闭，K70 已有手机助手交接开关为 true。
- S4 官方 App 音频静态研究已完成。com.mi.health 3.59.2 的蓝牙语音为 channel3/type103，AIVS START/DATA/END/QUIT，80字节 Opus 帧、16kHz 单声道；停止收音和回答走 type14/subtype0，TTS PCM 走 HFP/SCO。真实帧解码与设备应答待验证，详见 `docs/design/xiaomi-s4-official-audio.md`。用户要求先静态分析并保留绑定，仅必要时考虑重绑。
- 2026.11.6: 手表快应用 interconnect 已在 S4 实测双向中的手表→手机方向；手机→手表和广播开关路径未实测。下一步是 AIVS(命令类型 14)语音协议。
- 2026.11.5 的 K70 页面视觉验收仍待完成。14 项主题迁移/启动/设置入口回归通过，覆盖安装及主题偏好迁移已有实机证据。完整 lint 的 12 个现存错误见 2026-11 日志。
- Dayweave 手表快应用(设计 `~/Developer/dayweave/docs/design/watch.md`):前台直发与定向广播已随合并进入主线(e975dc29ae)。code273 已装 K70，待用户能测试时再用 `~/Developer/S4-Vela-Probe` 1.1.0 + `tools/channel_responder.py` 复测回包×10 与下行大小。2026-11-26本次用户已授权部署，K70已更新code276；未来安装仍需注意与NFC实验共用GB进程。
- 自定义时区尚未在真机上验证。
- 真实 S4 已进入 Gadgetbridge 认证页。旧版在 APatch 下报数据库缺失，修复后已在 K70 上复现挂载隔离并验证只读重试返回唯一有效密钥，待验收新版认证页和蓝牙握手。
- 数据外传(2026-11-12 起):K70 上自动导出数据库每小时一次，不设导出位置，导出后上传到 `http://100.106.169.46:8789/gadgetbridge`(ROG webhook 接收端，令牌只在手机 prefs 和 ROG 私有目录里)。Syncthing 已从 ROG 删除，手机上也已没有 Syncthing-Fork。整点自动上传还没观察到。设计见 `docs/design/auto-export-upload.md`,运维记录见 `~/.agents/gadgetbridge--rog-syncthing/AGENTS.md`。

## Build / Run / Test
- 使用现有 `JAVA_HOME=/Users/meta/.local/share/mise/installs/java/zulu-21` 与 `ANDROID_HOME=/Users/meta/Library/Android/sdk`，Gradle toolchain 要求 JDK 21。
  `JAVA_HOME=/Users/meta/.local/share/mise/installs/java/zulu-21 ANDROID_HOME=/Users/meta/Library/Android/sdk ./gradlew <task> -Porg.gradle.java.installations.paths=/Users/meta/.local/share/mise/installs/java/zulu-21 -Porg.gradle.java.installations.auto-detect=false`
- zsh 开启了 noclobber,重定向覆盖已有日志要用 `>|`.
- 编译检查: `./gradlew :app:compileMainlineDebugJavaWithJavac`
- 打包: `./gradlew :app:assembleMainlineDebug`
- 设置外观改动运行 `:app:testMainlineDebugUnitTest --tests nodomain.freeyourgadget.gadgetbridge.activities.SettingsAppearanceTest` 与 `:app:lintMainlineDebug`。沿用已有 debug 签名，不生成新签名。
- 主题升级改动另测 `--tests nodomain.freeyourgadget.gadgetbridge.prefs.ThemePreferenceMigrationTest --tests nodomain.freeyourgadget.gadgetbridge.ThemeUpgradeStartupTest`，覆盖生产启动迁移与真实设置 Activity。
- 提交后发版需先单独运行 `:app:buildGitChangelog --rerun-tasks` 再 `:app:assembleMainlineDebug`。现有 changelog task 仅跟踪 `.git/HEAD`，同分支新提交可能继续判为 UP-TO-DATE。

## Durable Lessons
- mainline 渐变 launcher foreground 不能交给 API 23 原生矢量加载器，欢迎/启动资源在 API 23 复用密度位图，API 24+ 用矢量别名；回归测试覆盖 API 23/35。
- 设备时间同步代码(100+ 处)都通过 `TimeZone.getDefault()` / `Calendar.getInstance()` / `ZoneId.systemDefault()` 取时区,所以覆盖进程默认时区即可让所有设备生效,不要逐个设备改.
- Android 在系统时区变化时会对 app 进程调用 `TimeZone.setDefault(null)` 重置覆盖,因此必须在时区变更广播和每次同步时间前重新 `AppTimeZone.apply()`.

- 手表类蓝牙设备不会被 Android 自动设为活动通话设备，HFP 每次重连都会丢失。用音频输出里的 BT SCO 判断是否活动；root 下 app_process 调 setActiveDevice 前须 prepareMainLooper 并反射 `BluetoothFrameworkInitializer.setBluetoothServiceManager`。
- K70 的 logcat 被 clash 通知每秒刷屏，GB 语音日志几分钟就被冲掉；排查时实时 `adb logcat --pid` 抓取。
- K70 + S4 上 HFP 语音识别模式接通但麦克风上行全零，模拟通话模式被远端断开。此前 SCO 在小爱开始录音后接通还中断过 SPP 收音。手机助手使用 SPP PCM 注入，2026.11.14 已支持停止收音后经 SCO 播报 Gemini 原声。
- 不使用 `uiautomator dump` 抓 K70 界面，它会使已启用的 Switch Access 服务重连并弹出设置向导。UI 验收使用截图或现有 hook。
- 卡片后代经常没有资源 ID，读取响应容器整体才能覆盖只有功能卡片的结果。
- 3.201.79 Lua表盘可执行shell；首次装入ready未就绪会return，不能靠点亮保证重跑；同ID直接覆盖可能code5，先准备companion目录再恢复原表盘/删除旧测试项/重装同ID。快应用私有目录是`/data/quickapp/files/<package>/`。此固件shell不接受`2>&1`。表盘上传完成后的即时列表可能早于安装；必须等type4/subtype5结果并重新查询，code3表示已安装启用，不能当错误码。
- S4 上 `CMD_WATCHFACE_SET` 切表盘不会关闭前台快应用，且 `main.lua` 在切表盘响应前同步执行；从快应用触发 Lua 表盘必须先由快应用 `terminate()`（`yield-for-face`）让出前台，切表盘并等待后台脚本启动后再 `CMD_RPK_LAUNCH` 重开。
- 含异步 NFA 回调的模块（`get-routing-status.elf`、`relay-route-*.elf`）必须 `sleep 1` 后再 `rmmod`，过早卸载会引发回调 UAF 重启；NuttX 系统 tick 为 10 ms，1 秒切片需按 `100 x 10 ms` 计。

## Docs Index
- `docs/design/xiaomi-wake-guard.md`。S4非睡眠时段、实时睡眠确认与振动/响铃所有权。
- `docs/design/xiaomi-assistant-feedback.md`。手机助手回答与功能卡片反馈到 S4。
- `docs/design/xiaomi-s4-nfc-host.md`。公交卡路线 B:S4 NFC 主机前端经蓝牙代理到手机 SE 的研究。
- `docs/design/xiaomi-transit-migration.md`。公交卡路线 A:官方迁移状态机与身份连续性。
- `docs/design/xiaomi-tsm-auth.md`。独立 TSM 授权与只读移卡资格核验。
- `docs/design/xiaomi-s4-transit-card.md`。国区小米运动健康官方开卡与移卡、复旦大学联名卡支持名单、表端 Vela NFC 探测结果。
- `docs/design/xiaomi-s4-daily-features.md`。天气来源、Google Root 缓存字段、城市同步、闹钟与提醒协议和验收状态。
- `docs/design/xiaomi-s4-official-audio.md`。官方音频接收、Opus 参数、AIVS 会话控制、文字应答与 HFP/SCO 播报链路。
- `docs/design/mi-fitness-auth-key.md`。Root 导入、数据库字段、设备匹配和验收条件。
- `docs/design/auto-export-upload.md`。自动导出后上传：设置项、Worker 流程、线协议、ROG 接收端和 INTERNET 权限。
- `docs/design/xiaomi-wear-app-interconnect.md`。手表快应用 interconnect 子命令对照、连接应答、消息广播开关和 S4 限制。
- `docs/design/pixel-settings.md`。手环品牌、动态取色、Google Settings 原生设置布局和资源复用。
