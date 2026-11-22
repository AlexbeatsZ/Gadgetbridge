# Gadgetbridge 手机助手分支

## Goal

- 在小米手表 S4 上使用手机默认助手，并将助手文字与功能卡片反馈到手表。
- 让 K70 上的复旦大学联名交通卡通过 S4 使用：优先实现真正的原生迁移，再研究手机 SE 蓝牙代理。核心链路未验证前不发布 APK；不改真实卡、余额、校园绑定和配对。

## Current State

- 现有 worktree `/Users/meta/Project/Workspaces/Gadgetbridge-inject`，分支 `feat/xiaomi-voice-inject`；只推送个人远程 `personal`。
- 用户要求删除小米运动健康跳转入口并撤回发布。GitHub 的 v2026.11.17 及其 APK 已删除，个人分支通过回退提交恢复 2026.11.16/code272 应用源码。K70 已原地装回 v2026.11.16 的原发布 APK，原安装时间与应用 ID 保持，S4 通过原绑定重新认证。本地 APK 已替换为 v2026.11.16。
- 2026-11-21 复核：远程错误 Tag v2026.11.17 已删除，回滚提交985018fbea保留，v2026.11.16 Release/Tag/APK保留。无线ADB实读K70为272，与原发布APK逐字节一致，本轮没有再安装。研究工具/文档不改变生产应用源码或版本。
- S4 3.201.79 实测通过 type5 C0/C2/C1：SE打开、虚构AID F0010203040506→6A82、CPLC元数据→9000、关闭。连接为Classic SPP V2。另验证Lua shell/file IPC、自建空ET_REL模块加载/卸载，实机存在/dev/nfc_sn100及NFC栈进程。均不等于射频卡模拟或真实移卡已实现。
- 11.10/4af6e89f0c 的 release 已撤为 draft。11.11 的部分回退及完整 ecdbbdbda2 对照均复现无法自动唤醒。
- 长按无法唤醒的根因已确定：Android 对腕表类设备(S4 类 0x280704)的 HFP 重连从不设为活动设备，S4 停在 Connected 但 Active=null，Google VOICE_COMMAND 报无蓝牙耳机。2026.11.15/code271(ca2053c122)由 `XiaomiHeadsetActivator` 自动用 root 恢复，已装 K70 并发布 v2026.11.15，用户确认长按唤醒恢复。
- 2026.11.14/code270 已装 K70，用户确认 S4 播放 Gemini 原声，以及停在助手界面黑屏后重新点亮退出会关闭手机浮窗。334 项测试通过、2 项跳过。提交 e73eb93e48 已推 personal，APK 已发布至 v2026.11.14。K70 已打开 S4 播报开关。
- GlassMic 是用户临时启用排查，现停用；不是原始故障原因，也不是本实现依赖。

## Active Work

- 公交卡两条独立路线仍未完成；用户本阶段禁止修改生产app或发布APK。A：独立模块`tools/research/xiaomi-tsm-auth`已核对login模式AES/签名，11项测试与AccountSDK Java参考向量通过；K70实际GB UID绑定系统Token服务成功，但tsm-auth返回ERROR_APP_PERMISSION_FORBIDDEN/3，无授权Intent。官方独立QR初始化HTTP200/code0，等待窗口未得到批准响应，未完成认证、未查卡资格。B：`tools/research/xiaomi-nfc-host`已实读驱动/CE动作表和IPC；原始处理器对测试AID真实回调event0x15/status3/handleFFFF，注册失败，eSE flags前后8101。本地配置/注销/ISO-DEP技术槽是空函数，不得把返回1当成功。下一步核对NCI连接回调/Host路由与安全恢复ABI，不能清掉唯一eSE监听项或抢读NFC fd。先读两条独立设计文档，不能因快应用接口缺失推断硬件不支持。
- 2026-11-22研究结束：零导入stock CE拒绝探针已卸载；原9表盘和原活动「昼与夜」回读一致，唯一测试表盘/RPK移除；手机so/DEX与ROG临时编译目录清理。GB始终原PID10882，本阶段无崩溃/重配对。较早研究曾因JNI List/Collection签名错误崩溃，已恢复；新的生命周期工具使用Collection.toArray并检查JNI异常。长按/Gemini/真实闹钟等未重新物理验收。
- 2026.11.16/code272：手表发起的会话中，Gemini 语音会暂缓到 S4 的 SCO 接通再播；手机上直接用 Gemini 不受影响。用户已验收。实际会话中“开口即建路由”尚未在实机触发，只有单元测试覆盖。

- 研究 S4 播放路线。2026-11-17 再验证 K70 + S4：HFP 语音识别模式约 5 秒录得 78720 个样本，实际路由为 S4 SCO，全部为零。手机麦克风对照 31040 个样本中 27273 个非零。模拟通话两轮均反复短暂连上后断开，蓝牙栈明确记录远端断开。测试后回到 MODE_NORMAL，S4 保持 HFP 活动，SCO 已关闭，手机诊断文件已删除。
- S4 播放已验证收音后顺序。8 秒探针和 2026.11.14 的 Gemini 原声均确认从手表扬声器播放。测试后回到 MODE_NORMAL，S4 保持 HFP 活动，SCO 已关闭。
- 小爱录音中途建立 SCO 的那轮只有一包 SPP 音频在 SCO 接通附近到达，随后到播放结束无新语音包；用户听到声音但无法区分声源。空闲态单独播放时 Android 路由为 S4，但用户两边没听到。尚不能据此确定两种状态差异的固件条件。手机临时探针已删除，记录位于 /tmp/.agents/gadgetbridge-card-feedback/hfp-output。
- 2026-11-17 原生卡实验已结束。S4 的 General2（258）和 General（257）均未显示只读闹钟样卡；ToastStream（292）仅确认无序、有序列表样式。按用户要求保留 2026.11.14 的 Markdown 回答卡，探针代码及设备触发标记均已移除。
- 主工作区 docs/log/2026-11.md 有其他任务未提交内容。

## Build / Run / Test

- 使用已有 `JAVA_HOME=/Users/meta/.local/share/mise/installs/java/zulu-21` 与 `ANDROID_HOME=/Users/meta/Library/Android/sdk`，仓库 `./gradlew`。
- Gradle 参数为 `-Porg.gradle.java.installations.paths=/Users/meta/.local/share/mise/installs/java/zulu-21 -Porg.gradle.java.installations.auto-detect=false`。
- 定向测试为 `AssistantResponseReaderTest`、`XiaomiVoiceAssistantFeedbackTest`、`XiaomiVoiceAssistantBridgeTest`、`XiaomiVoiceHandlerTest`，扩大回归包括 Xiaomi、手机闹钟、Google 天气、设置及主题迁移。
- 提交后先单独运行 `:app:buildGitChangelog --rerun-tasks`，随后 `:app:assembleMainlineDebug`。沿用现有 debug 签名。
- K70 序列号为 `dcc50feb`，当前无线 transport 每次从 `adb devices -l` 核对，安装使用 `--user 0 -r`。APK 更新后重新启动 Google 应用，加载更新后的 LSPosed hook。
- NFC研究：原SE协议6项测试见`tools/research/xiaomi-nfc-probe`；A的11项测试/Java参考验证/B原始ARM离线与实机拒绝探针见各研究目录README。使用既有NDK28.2.13676358；依赖仅uv隔离缓存，无需全局安装或重建APK。

## Durable Lessons

- 手表类蓝牙设备不会被 Android 自动设为活动通话设备，HFP 每次重连都会丢失。用音频输出里的 BT SCO 判断是否活动；root 下 app_process 调 setActiveDevice 前须 prepareMainLooper 并反射 `BluetoothFrameworkInitializer.setBluetoothServiceManager`。
- K70 的 logcat 被 clash 通知每秒刷屏，GB 语音日志几分钟就被冲掉；排查时实时 `adb logcat --pid` 抓取。

- K70 + S4 上 HFP 语音识别模式接通但麦克风上行全零，模拟通话模式被远端断开。此前 SCO 在小爱开始录音后接通还中断过 SPP 收音。手机助手使用 SPP PCM 注入，2026.11.14 已支持停止收音后经 SCO 播报 Gemini 原声。
- 不使用 `uiautomator dump` 抓 K70 界面，它会使已启用的 Switch Access 服务重连并弹出设置向导。UI 验收使用截图或现有 hook。
- 卡片后代经常没有资源 ID，读取响应容器整体才能覆盖只有功能卡片的结果。
- 3.201.79 Lua表盘可执行shell；快应用私有目录是`/data/quickapp/files/<package>/`。此固件shell不接受`2>&1`。表盘上传完成后的即时列表可能早于安装；必须等type4/subtype5结果并重新查询，code3表示已安装启用，不能当错误码。

## Docs Index

- `docs/design/xiaomi-s4-transit-card.md` 保留此前恢复、迁移链与wired SE证据。
- `docs/design/xiaomi-tsm-auth.md`：路线A，账号/TSM认证、签名、Binder拒绝、官方QR和只读资格判据。
- `docs/design/xiaomi-s4-nfc-host.md`：路线B，驱动/IPC/CE ABI、真实注册失败证据及后续Host扩展路径。
- `docs/design/xiaomi-assistant-feedback.md` 记录响应容器、会话与空内容反馈。
- `docs/design/xiaomi-s4-official-audio.md` 记录官方音频与 AIVS 协议。
- 其他模块沿主工作区 `AGENTS.md` 和已有设计文档继续。
