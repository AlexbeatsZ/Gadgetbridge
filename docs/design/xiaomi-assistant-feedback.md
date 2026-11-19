# 小米手表手机助手反馈

## 范围与入口

- 语音入口沿用 `XiaomiVoiceAssistantBridge` 和 `VoiceMicHub`，Google 进程内的 `VoiceMicHook` 继续将手表 SPP Opus 解码后的 PCM 交给助手。
- 手机助手的执行结果可能只有闹钟、计时器等功能卡片。旧实现只读取 `assistant_robin_text`，没有该控件时会一直等到超时并结束手表会话。
- 卡片转换成可读文字，通过现有 type14/subtype0 的 LLM ToastStream 和 FinishStream 发送。执行状态、确认要求和错误信息沿用卡片原文。

## 响应读取

- Xposed 观察 `assistant_robin_floaty_message_content` 与 `assistant_robin_message_content` 的附着事件。名称来自 K70 当前 Google APK 资源和此前真实天气回答的 UI 树。
- 以 500ms 间隔读取当前响应容器。遍历范围包含没有资源 ID 的后代 TextView，以及自定义 View 通过无障碍节点暴露的文字和描述。布局后的快照支持卡片复用、原地更新和初次绑定时尚未附着的子控件。TextView 重新绑定或无障碍描述更新会标记该容器，以支持相同内容的再次返回。
- 按树顺序合并文字并去重，跳过隐藏子树、反馈工具栏和生成过程的状态控件。输入区位于响应容器之外。
- 页面隐藏期间不发送空快照，避免浮窗与全屏页面切换时清除新页面已经上报的结果。可见容器被清空时取消尚未发送的旧内容。
- 读取最多 512 个 View 和 4000 个码点，手表输出最多 1000 个码点。文字稳定 1.5 秒后发送。
- 仅有绘制内容而没有可读 View 文字或无障碍标签的卡片仍可能无法提取。当前实现没有接入图片识别，也没有遍历虚拟无障碍子节点。

## 回调与失败反馈

- 卡片快照复用原有 `METHOD_ANSWER`、`VoiceMicHub.onAnswer` 和 Bridge 的回答等待流程。仅在内容变化或卡片重新绑定时上报。
- Google 进程的音频注入、录音启停、HFP 抢占保护与回调线程方式，以及 Bridge 的唤醒与收音均与 `ecdbbdbda2` 保持一致。
- 2026.11.10 曾将音频和卡片 IPC 共用串行队列，并增加反馈会话序号。这些超出回答显示范围的改动已撤回，最终版本保持原有音频与 IPC 实现。
- 唤醒故障的完整旧版对照也失败：S4 的 HFP 已连接但系统活动通话设备为空，Google VOICE_COMMAND 因没有蓝牙音频设备直接拒绝。临时将已连接 S4 恢复为活动通话设备后，用户确认自动唤醒恢复，日志确认原版拒绝 SCO 保护与 PCM 注入同时正常。活动设备变空的原因见下节「活动通话设备」，与卡片代码和用户临时启用的 GlassMic 无关。
- GlassMic 不再是依赖；它在成功对照中停用。系统活动通话设备与实际开启 SCO 录音是不同状态，不能通过移除原版 startVoiceRecognition 拦截解决唤醒。
- 没有可读内容时，等待 30 秒后显示“手机助手未返回可显示的内容，请在手机上查看操作结果。”再结束对话。
- Google 进程需要在 APK 更新后重新启动，才能加载更新后的 LSPosed hook。

## 活动通话设备

- 根因：AOSP `ActiveDeviceManager` 在 HFP 连接时跳过 WEARABLE_WRIST_WATCH 类设备，不把手表设为活动设备。S4 的设备类为 `0x280704`（腕表）。走出范围、蓝牙重启、开关通话权限或手机重启都会让 HFP 重连，之后 S4 一直是 Connected 但 Active=null，Google `ACTION_VOICE_COMMAND` 报没有蓝牙耳机并拒绝。2026-11-19 复现时，Telecom 日志显示 17:34 两次断开重连，`activeBluetoothDevice` 均为 null。
- 检测无需特权：只有活动 HFP 设备会进入音频策略的 `TYPE_BLUETOOTH_SCO` 输出。`XiaomiHeadsetActivator.state()` 读 `AudioManager.getDevices(OUTPUTS)`，区分本表活动、其它耳机活动和无活动设备三种状态。
- 恢复需要特权：`BluetoothAdapter.setActiveDevice(device, ACTIVE_DEVICE_PHONE_CALL)` 是系统 API。通过 `su` 用本 APK 作为 CLASSPATH 运行 `app_process`，入口为 `BluetoothActiveDeviceTool`。app_process 中没有 ActivityThread，Android 13+ 须先 `Looper.prepareMainLooper()` 并反射调用 `BluetoothFrameworkInitializer.setBluetoothServiceManager`，否则 `getDefaultAdapter()` 返回 null 或在 Handler 构造时抛 NPE。release 构建的 proguard 规则保留该入口。
- 触发时机（仅在「使用手机语音助手」开启时）：本表 HFP 变为已连接、系统广播活动设备变为空（各延迟 2 秒，等待系统自身的选择）、连接后 10 秒补查一次（应对 GB 重启时 HFP 早已连上），以及每次手表 START 拉起助手之前。已经活动时只读一次音频设备列表，不调用 root。
- 不抢占：其它耳机为活动通话设备时不改动；音频模式不是 MODE_NORMAL（通话中）时不改动，避免用户在通话中把声音切回手机后被拉回手表。没有 root 时只记日志，助手仍照常拉起。
- 2026.11.15 实测：GB 重启 10 秒后检测到 Active=null，0.8 秒内恢复，HeadsetService 与音频策略均出现 S4。用 root 清空活动设备后 2 秒内自动恢复。用户长按 S4 后 Gemini 唤起并回答。

## 取消会话

- 手表的 `AUDIO_END` 仅结束音频输入，继续等待手机答复。活跃对话收到 `QUIT` 则关闭手表音频管道并清除待发送的回答。`AUDIO_END` 已使解码器丢弃会话 ID 时，无 ID 的 `QUIT` 仍可取消正在等待结果的对话。
- 手机浮窗使用独立的请求编号。Gemini 浮窗创建或接到新 Intent 时认领最近一次尚未打开的手表请求，绑定持续到浮窗销毁、被其它请求复用、手表断开或下一轮唤醒。手表文字发完、语音播完均不解除手机浮窗的取消绑定。
- 取消通过签名权限广播和 provider 中的取消状态传递。已绑定浮窗只处理编号一致的退出。浮窗打开得较慢时，只在发起唤醒后的 12 秒内允许认领且取消后最长等待 5 秒。已取消的浮窗打开后会检查状态并随即关闭。新请求生成的新编号使旧取消失效。
- 范围为 Google Gemini 浮窗及手表通过 AIVS 发出的 QUIT。如果固件退出时未发送 QUIT，手机端没有该退出事件。
- 2026.11.14 用户确认手表停在助手界面黑屏，重新点亮后退出，也能关闭手机 Gemini。

## 手表播报

- S4 在 Android 10 及以上提供「用手表播放助手回答」开关，默认关闭，依赖「使用手机语音助手」及 Google 作用域的 LSPosed 模块。
- 录音停止并通过原有 1.5 秒去抖后，发送 STOP_CAPTURE。再等 450ms，通过 HFP 的 `startVoiceRecognition` 建立 S4 SCO。沿用原版的手表收音期间 SCO 保护。保持 Android 音频模式，已有通话或 SCO 时跳过。
- K70 的 Android 音频策略在 SCO 接通后把 Google 助手语音 AudioTrack 路由到 S4。直接播放 Gemini 原声。Google 钩子通过受调用方校验的旧 provider 报告 `USAGE_ASSISTANT` / `CONTENT_TYPE_SPEECH` 音轨启停；这些事件使用独立队列。
- 语音播放结束后预留 800ms 等待下一条音轨。没有语音的等待限于 10 秒，整段最多 90 秒。取消、下一次手表唤醒或设备断开时关闭本次打开的 SCO。文字及卡片发送仍独立完成。通道短暂开放期间，系统也可能把其它声音路由到手表。
- K70 + S4 真机在 2026.11.14/code270 验证用户问时间后，Google 原声音轨实际路由到 S4，用户确认从手表听到回答，音轨停止后 SCO 自动关闭。长回答、通话期间与其他手机 ROM 未实测。

## 卡片与 Markdown 实机对照

- Redmi K70 + S4 上，在完整语音会话中发送只读闹钟样例。分别尝试 `Template.General2`（258，instruction 字段 5）和 `Template.General`（257，字段 4），沿用最终识别结果和 STOP_CAPTURE，跳过 LLM 加载卡。用户两轮均未看到对应的时间或样卡。此结果适用于本次指令和会话顺序。
- 现有 `LLM.ToastStream`（292）的 Markdown 样卡包含标题、粗体、斜体、无序与有序列表、代码和表格。用户只确认了 `-` 和 `1.` 列表样式，其他样式未确认。
- 保留 2026.11.14 现有 Markdown 卡片和纯文字回传。样卡探针已撤下。闹钟样例只展示，不发送会设置闹钟的 `Alerts.SetAlert`（263）。

## 验证

- `AssistantResponseReaderTest` 在 API23/35 上覆盖纯卡片、无 ID 后代、叙述与卡片合并、无障碍标签、隐藏卡片、复用更新、失败和确认原文、响应范围。
- `XiaomiVoiceAssistantFeedbackTest` 覆盖音频停止后的卡片、稳定等待、空内容兜底、清空旧卡片、提前返回、连续重复结果和码点截断。
- `XiaomiVoiceAssistantBridgeTest` 保留 AIVS 字段号与线格式检查，`XiaomiVoiceHandlerTest` 保留 Opus 解码检查。
- 2026.11.13/code269 在 K70 + S4 上实测长按自动打开 Gemini，手表退出时手机随即关闭浮窗并停止录音，再次长按可开启新会话。用户确认手表显示了实际闹钟时间或执行结果。
