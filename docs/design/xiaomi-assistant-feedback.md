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

## 会话与失败反馈

- 音频停止后继续保留反馈会话。反馈会话使用递增序号，与手表可能一直为 0 的 AIVS session ID 独立。
- Google 进程通过受原有调用者校验保护的 ContentProvider 查询反馈序号，再报告带序号的响应快照。读取 View 在 Google UI 线程执行，IPC 在串行后台队列执行。
- Provider 丢弃已结束或属于旧会话的快照，Bridge 的主线程回调再次核对反馈序号。
- 没有可读内容时，等待 30 秒后显示“手机助手未返回可显示的内容，请在手机上查看操作结果。”再结束对话。
- Google 进程需要在 APK 更新后重新启动，才能加载更新后的 LSPosed hook。

## 验证

- `AssistantResponseReaderTest` 在 API23/35 上覆盖纯卡片、无 ID 后代、叙述与卡片合并、无障碍标签、隐藏卡片、复用更新、失败和确认原文、响应范围。
- `XiaomiVoiceAssistantFeedbackTest` 覆盖音频停止后的卡片、稳定等待、空内容兜底、清空旧卡片、旧会话排除、提前返回、连续重复结果和码点截断。
- `XiaomiVoiceAssistantBridgeTest` 保留 AIVS 字段号与线格式检查，`XiaomiVoiceHandlerTest` 保留 Opus 解码检查。
- S4 实际屏幕显示需要连接手机与手表后验收。
