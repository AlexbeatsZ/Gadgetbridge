# Gadgetbridge 手机助手分支

## Goal

- 在小米手表 S4 上使用手机默认助手，并将助手文字与功能卡片反馈到手表。
- 让 K70 上的复旦大学联名交通卡通过 S4 使用：用户最新决定优先蓝牙代理（S4 NFC主机前端→Classic SPP→手机），A真实迁移暂停。先测试AID/APDU，核心链路未验证前不发布APK；保护原卡、余额、校园绑定和配对，不修改生产app。

## Current State

- 现有 worktree `/Users/meta/Developer/Gadgetbridge-inject`，分支 `feat/xiaomi-voice-inject`；只推送个人远程 `personal`。
- 用户要求删除小米运动健康跳转入口并撤回发布。GitHub 的 v2026.11.17 及其 APK 已删除，个人分支通过回退提交恢复 2026.11.16/code272 应用源码。K70 已原地装回 v2026.11.16 的原发布 APK，原安装时间与应用 ID 保持，S4 通过原绑定重新认证。本地 APK 已替换为 v2026.11.16。
- 2026-11-21 复核：远程错误 Tag v2026.11.17 已删除，回滚提交985018fbea保留，v2026.11.16 Release/Tag/APK保留。无线ADB实读K70为272，与原发布APK逐字节一致，本轮没有再安装。研究工具/文档不改变生产应用源码或版本。
- S4 3.201.79 实测通过 type5 C0/C2/C1：SE打开、虚构AID F0010203040506→6A82、CPLC元数据→9000、关闭。连接为Classic SPP V2。另验证Lua shell/file IPC、自建空ET_REL模块加载/卸载，实机存在/dev/nfc_sn100及NFC栈进程。均不等于射频卡模拟或真实移卡已实现。
- 11.10/4af6e89f0c 的 release 已撤为 draft。11.11 的部分回退及完整 ecdbbdbda2 对照均复现无法自动唤醒。
- 长按无法唤醒的根因已确定：Android 对腕表类设备(S4 类 0x280704)的 HFP 重连从不设为活动设备，S4 停在 Connected 但 Active=null，Google VOICE_COMMAND 报无蓝牙耳机。2026.11.15/code271(ca2053c122)由 `XiaomiHeadsetActivator` 自动用 root 恢复，已装 K70 并发布 v2026.11.15，用户确认长按唤醒恢复。
- 2026.11.14/code270 已装 K70，用户确认 S4 播放 Gemini 原声，以及停在助手界面黑屏后重新点亮退出会关闭手机浮窗。334 项测试通过、2 项跳过。提交 e73eb93e48 已推 personal，APK 已发布至 v2026.11.14。K70 已打开 S4 播报开关。
- GlassMic 是用户临时启用排查，现停用；不是原始故障原因，也不是本实现依赖。

## Active Work

- 操作节奏（用户2026-11-25纠正）：本轮不再请用户重复贴近。后续先完成全部编译、资源准备和自动恢复预演，再约用户；窗口内只做已准备好的启动与真实ready确认，不要一边让用户等一边继续编译/修复。合并实机实验，优先无需用户操作的分析与测试。
- B优先2026-11-22新实机：已完整捕获GET_ROUTING通知61020c00020003c0e3000003c0e302，两项NFC-A/F→eSE C0、power e3；HAL槽3c2d45cc实读2c34cd85，限量转发器自撤销+原HAL GET barrier后卸载。能力features00063e3a、AID支持、capacity1030、NCI20；原NFA STOP/START状态01000100→0→01000100实测。
- B测试AID路由增删已实机通过：必须AID-first（AOSP/NXP新栈顺序），F0010203040506→DH00/power01，SET41010100、GET三项、重启discovery后仍在，再恢复两原项/再重启读回相等。AID-last先前返回FAILED03且GET空表，已立即完整恢复；失败不能假定无副作用。证据独立保留，不清表恢复，不使用合成旧项。
- B额外RF discovery slot1实读空闲，slot0原eSE；原add2c344755/delete2c341311在停止发现的原NFA任务中同步add/delete实测handle1，全部40字节回原样、RF/主DISC callback保持、模块卸载。delete只清in_use，不清callback，必须恢复空闲槽原字节后才能卸载。
- B剩余主机分发：原activate dispatcher2c3410a1按technology map(DM+11e)选host；原ARM离线证实ISO-DEP DH需选择性协调，direct eSE保持原路。新增host-test-session/control C原型及12项原ARM离线检查（完整NFC_DISC4004激活、测试SELECT→S4TEST9000原send队列、非测试AID交旧sink、去激活与卸载引用、拒绝停止eSE活动），**已实机装入Host session并完成自动超时恢复/原表复核/卸载；外部K70读卡得到7/10/4次Host激活，但测试SELECT仍TagLostException，SPP APDU未做**。原激活尾部重装sink问题已定位并选择性重接，DATA6003仍0；被动HAL计数无贴卡恢复预演已通过，下一步RF计数区分控制器交付/栈丢失。11-23追加原NCI DATA/接收队列/drain离线执行，16项通过（含指纹/释放一次），不代表实际交付；rf6此前模块不变；11-25实机和后续新原型见下。
- B后台实验：NSH `sh <probe私有脚本> > <私有输出> &`实测0秒返回，3秒后完成。长Lua曾使SPP短断连，原绑定自动认证恢复；后续RF等待使用后台而不阻塞表盘。K70 11-25实读无线adb-dcc50feb-cMg7fx._adb-tls-connect._tcp、serial dcc50feb、GB当前PID11449（诊断agent曾在PID6681崩溃，v4不可变库名/原绑定connect恢复，见新证据）（用户并行更新2026.11.18/code273；旧实验PID6001，更新清了code_cache，已重新stage研究库；不回退/重配对）；无线校园网旧地址不可达，不改代理/VPN。临时S4TestReader app_process已实机ReaderMode与贴近（flags385→0）；ISO-DEP connect通过、SELECT阶段TagLostException，无成功响应，不装APK。
- B 2026-11-25 rf6关键实机：用户两次贴近，HAL收到3个精确固定SELECT DATA（16字节conn0），RF6003仍0，DATA时stock sink再次占用，K70三次TagLostException。rf7尝试HAL→原NFA固定应答队列但保护拒绝（own-active0/handle低12位FFF、state5、提交0，旧诊断截断未证完整FFFF）。两轮均完成后台恢复、原表/槽/RF/DISC/HAL/eSE复核、barrier和全部卸载，原表盘恢复/测试face删。
- B当前新原型：识别完整控制器Host ISO-DEP Listen-A激活元数据，精确固定SELECT且state5/handleFFFF/原eSE槽未变/自身测试slot1仍在时，复制固定S4TEST9000入原NFA action10c；不是HAL直接发送，不覆盖未知RF owner，不触及真实SE。完整handle改为rf_meta[13]高16位；旧rf7仅低12位。33项原ARM/编译探针离线检查通过，dry4新队列恢复预演实机通过。rf8实机两次成功：K70收到5334544553549000，RTT92.73/92.22ms；控制器Host标记1/state5/完整handleFFFF/队列2精确匹配reader2。自动恢复、原表/槽/回调/barrier/卸载复核均通过；新证据host-rf8-success-2026-11-25.json。新证据*host-rf6/host-rf7/host-hal-queue*2026-11-25.json。
- 当前RF固定响应已通过，SPP独立固定APDU后端已实机3/3通过（同包诊断RPK9+K70临时DEX/JNI，RTT122/123/146ms，无APK）；原生/quickapp私有文件双向IPC亦实机通过（模块同步卸载），5项IPC离线/12项串联状态离线通过。HOST_SPP_BRIDGE原型使用独立NSH同步worker，不在HAL做IO/等待，无本地RAPDU回退；result360字节/90words，新增ipc[16]及pump在途/待处理卸载守卫。串联dryrun实机恢复通过；RF9两次SELECT均TagLost，native请求文件2/有效手机应答0/入队0，已恢复卸载。快应用误用同步getReadyState已修复，RPK15新增有界ready重发，8项实际companion离线通过；JNI v5只清此诊断包的recent-open缓存再原流程启动；RF10完整串联已有一次外部精确响应709.34ms，另一次TagLost；native手机response1/原队列1与reader成功1相符，手机2请求2ACK不能等同2RF成功。最终phase5/原表/槽/回调/eSE/barrier/模块卸载通过，九原表盘及昼与夜恢复，ReaderMode退出；唯一诊断RPK和研究暂存保留、不自动运行。尚未通过串联稳定性、WTX或真实卡。生产app/版本/代理/配对不改，无APK发布。唯一com.meta.s4nativeprobe RPK、手机so/face/rpk/reader.dex及ROG临时编译目录待研究结束清理；不要重复创建表盘ID。

- A身份/迁移研究2026-11-22：新增`xiaomi-transit-migration`公共资产检查及离线状态机（不接真实后台）。上海交通卡公共配置明确复旦联名产品SPTC.MOT.SH.FDU/partner06000047；H5由faceNo规范化cardNo后查联名信息，不能等同于小米基础产品SPTC_MOT或证明校园唯一映射键。迁入使用目标CPLC/deviceId+cloud transferOrder，卡号/联名映射连续性仍未知；虽用户允许尝试迁出，因其“未知则不要迁移”条件仍未满足，本阶段未创建订单/SE会话/执行preTransferOut。后者会启动CHECK_SHIFT_OUT并可能执行APDU，绝非只读。已定位原响应缓存补报/3007续拉/未完成业务，28项离线测试通过；正式源SE写适配、发行方联名会话和真实恢复语义仍需验证。完整方案与最小实验见`docs/design/xiaomi-transit-migration.md`，新证据`xiaomi-campus-migration-2026-11-22.json`。这是此前A阶段记录；其后已转向B，新增结果见上。
- 公交卡两条路线仍未完成；禁止修改生产app或发布APK。A新进展2026-11-22：用户扫码批准官方tsm-auth QR，STS成功签发独立会话；源K70/vermeer/SPTC_MOT的已发行记录与批准账号、源CPLC匹配（尾8213），TRANSFER_OUT成功；S4官方alias=o62，实际目标CPLC的TRANSFER_IN和同产品目录成功；最终五项HTTP200/errCode200，云端列表为空。只读服务资格通过，真实迁移与复旦校园身份保留未验证，禁止为造云端卡而先迁出。新CLI保持会话、device_plan补齐真实元数据、四个精确只读路径；凭证仅内存，QR/完整CPLC临时计划已删除。见xiaomi-tsm-auth设计和xiaomi-tsm-eligibility-2026-11-22.json。此前系统Binder应用拒绝已由合法独立QR路径取得进展，不再当作认证未完成。
- B此前标准CE实验：`tools/research/xiaomi-nfc-host`已实读驱动/CE动作表和IPC；原始处理器对测试AID真实回调event0x15/status3/handleFFFF，注册失败，eSE flags前后8101。本地配置/注销/ISO-DEP技术槽是空函数，不得把返回1当成功。下一步核对NCI连接回调/Host路由与安全恢复ABI，不能清掉唯一eSE监听项或抢读NFC fd。此次A扫码阶段无新RF实验，无需用户贴卡；不能因快应用接口缺失推断硬件不支持。
- 2026-11-22较早研究结束：零导入stock CE拒绝探针已卸载；原9表盘和原活动「昼与夜」回读一致，唯一测试表盘/RPK移除；手机so/DEX与ROG临时编译目录清理。GB始终原PID10882，本阶段无崩溃/重配对。较早研究曾因JNI List/Collection签名错误崩溃，已恢复；新的生命周期工具使用Collection.toArray并检查JNI异常。长按/Gemini/真实闹钟等未重新物理验收。
- 2026.11.16/code272：手表发起的会话中，Gemini 语音会暂缓到 S4 的 SCO 接通再播；手机上直接用 Gemini 不受影响。用户已验收。实际会话中“开口即建路由”尚未在实机触发，只有单元测试覆盖。

- 研究 S4 播放路线。2026-11-17 再验证 K70 + S4：HFP 语音识别模式约 5 秒录得 78720 个样本，实际路由为 S4 SCO，全部为零。手机麦克风对照 31040 个样本中 27273 个非零。模拟通话两轮均反复短暂连上后断开，蓝牙栈明确记录远端断开。测试后回到 MODE_NORMAL，S4 保持 HFP 活动，SCO 已关闭，手机诊断文件已删除。
- S4 播放已验证收音后顺序。8 秒探针和 2026.11.14 的 Gemini 原声均确认从手表扬声器播放。测试后回到 MODE_NORMAL，S4 保持 HFP 活动，SCO 已关闭。
- 小爱录音中途建立 SCO 的那轮只有一包 SPP 音频在 SCO 接通附近到达，随后到播放结束无新语音包；用户听到声音但无法区分声源。空闲态单独播放时 Android 路由为 S4，但用户两边没听到。尚不能据此确定两种状态差异的固件条件。手机临时探针已删除，记录位于 /tmp/.agents/gadgetbridge-card-feedback/hfp-output。
- 2026-11-17 原生卡实验已结束。S4 的 General2（258）和 General（257）均未显示只读闹钟样卡；ToastStream（292）仅确认无序、有序列表样式。按用户要求保留 2026.11.14 的 Markdown 回答卡，探针代码及设备触发标记均已移除。
- Dayweave 手表快应用通道(设计 `~/Developer/dayweave/docs/design/watch.md`):2026-11-25 已提交快应用前台时直发消息(30 秒窗口)和按包名定向广播，14 项相关单测通过，未改版本、未打包、未装机。用户要求先不部署；下次发版需升版本并用探针 `~/Developer/S4-Vela-Probe` 1.1.0 加 `tools/channel_responder.py` 复测回包与下行大小。K70 上 S4 的「第三方应用收发手表应用消息」已为测试打开。
- 主工作区 docs/log/2026-11.md 有其他任务未提交内容。

## Build / Run / Test

- 使用已有 `JAVA_HOME=/Users/meta/.local/share/mise/installs/java/zulu-21` 与 `ANDROID_HOME=/Users/meta/Library/Android/sdk`，仓库 `./gradlew`。
- Gradle 参数为 `-Porg.gradle.java.installations.paths=/Users/meta/.local/share/mise/installs/java/zulu-21 -Porg.gradle.java.installations.auto-detect=false`。
- 定向测试为 `AssistantResponseReaderTest`、`XiaomiVoiceAssistantFeedbackTest`、`XiaomiVoiceAssistantBridgeTest`、`XiaomiVoiceHandlerTest`，扩大回归包括 Xiaomi、手机闹钟、Google 天气、设置及主题迁移。
- 提交后先单独运行 `:app:buildGitChangelog --rerun-tasks`，随后 `:app:assembleMainlineDebug`。沿用现有 debug 签名。
- K70 序列号为 `dcc50feb`，当前无线 transport 每次从 `adb devices -l` 核对，安装使用 `--user 0 -r`。APK 更新后重新启动 Google 应用，加载更新后的 LSPosed hook。
- NFC研究：原SE协议6项测试见`tools/research/xiaomi-nfc-probe`；A的11项测试/Java参考验证/B原始ARM三组29例与实机CE拒绝、GET_ROUTING只读探针见各研究目录README。使用既有NDK28.2.13676358；依赖仅uv隔离缓存，无需全局安装或重建APK。

## Durable Lessons

- 手表类蓝牙设备不会被 Android 自动设为活动通话设备，HFP 每次重连都会丢失。用音频输出里的 BT SCO 判断是否活动；root 下 app_process 调 setActiveDevice 前须 prepareMainLooper 并反射 `BluetoothFrameworkInitializer.setBluetoothServiceManager`。
- K70 的 logcat 被 clash 通知每秒刷屏，GB 语音日志几分钟就被冲掉；排查时实时 `adb logcat --pid` 抓取。

- K70 + S4 上 HFP 语音识别模式接通但麦克风上行全零，模拟通话模式被远端断开。此前 SCO 在小爱开始录音后接通还中断过 SPP 收音。手机助手使用 SPP PCM 注入，2026.11.14 已支持停止收音后经 SCO 播报 Gemini 原声。
- 不使用 `uiautomator dump` 抓 K70 界面，它会使已启用的 Switch Access 服务重连并弹出设置向导。UI 验收使用截图或现有 hook。
- 卡片后代经常没有资源 ID，读取响应容器整体才能覆盖只有功能卡片的结果。
- 3.201.79 Lua表盘可执行shell；首次装入ready未就绪会return，不能靠点亮保证重跑；同ID直接覆盖可能code5，先准备companion目录再恢复原表盘/删除旧测试项/重装同ID。快应用私有目录是`/data/quickapp/files/<package>/`。此固件shell不接受`2>&1`。表盘上传完成后的即时列表可能早于安装；必须等type4/subtype5结果并重新查询，code3表示已安装启用，不能当错误码。

## Docs Index

- `docs/design/xiaomi-transit-migration.md`：A的校园联名身份、完整迁移/恢复主干、独立模块设计与未验证条件；模拟不等于实卡迁移。
- `docs/design/xiaomi-s4-transit-card.md` 保留此前恢复、迁移链与wired SE证据。
- `docs/design/xiaomi-tsm-auth.md`：路线A，账号/TSM认证、签名、Binder拒绝、官方QR和只读资格判据。
- `docs/design/xiaomi-s4-nfc-host.md`：路线B，驱动/IPC/CE ABI、真实注册失败证据及后续Host扩展路径。
- `docs/design/xiaomi-assistant-feedback.md` 记录响应容器、会话与空内容反馈。
- `docs/design/xiaomi-s4-official-audio.md` 记录官方音频与 AIVS 协议。
- 其他模块沿主工作区 `AGENTS.md` 和已有设计文档继续。
