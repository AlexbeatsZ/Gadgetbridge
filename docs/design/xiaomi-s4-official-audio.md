# 小米手表 S4 官方 App 音频静态分析

核对日期为 2026-11-16，使用 America/Los_Angeles 本地日期。此前手机日志和任务记录使用了 2026-11-17，引用这些记录时保留其原始时间。

## 分析对象与边界

- 官方 App 为国区小米运动健康 `com.mi.health`，版本 `3.59.2`，versionCode `359002`，APK 的 compile/target SDK 为 35。
- 安装包取自此前使用的小米官方下载链路。下载对象为小米 CDN `cdn.cnbj0.fds.api.mi-img.com` 上的 `default/3.59.2_20261023185032_mihealth.apk`。`aapt2 dump badging` 确认包名和版本。
- 本次研究采用 APK 静态反编译、JNI 反汇编和合成数据解码。设备真实语音解码、手表显示、停止收音和扬声器播放仍待实机验证。
- 用户要求保留当前绑定，只有确有必要才考虑重新绑定。后续优先沿现有 Gadgetbridge 连接验证。
- 分析用 jadx 1.5.6。全包反编译输出 397 个方法错误，以下关键方法均有可读方法体。Opus JNI 的直接调用另以 ARM64 反汇编交叉核对。分析结果为重述的协议与调用关系。

## 收音链路

```text
SPP V2 的 L2 通道 3，opCode 2
  → TaskQueueV2 解密并映射为 TYPE_VOICE 103
  → AivsComponent.DataHandlerImpl
  → BluetoothSpeechRecognizerManager.receiveAudioData
  → AivsCommand.parse
  → START / AUDIO_DATA / AUDIO_END / QUIT
  → BluetoothEventSession + com.banya.opus.OpusDecoder
  → libjni_byopus.so → libopus.so 的 opus_decode
  → BaseEventSession 向 AIVS Engine.postData 提交 PCM
```

### 传输层

`L2Packet.Channel.TRANSPORT_CHANNEL_MASS_VOICE = 3`，`TRANSPORT_OPCODE_WRITE_ENC = 2`。`TaskQueueV2.channelToType` 将通道 3 映射到 103，`dispatchMessage` 按 opCode 调用现有 `CryptoHandlerV2.decryptDataV2`，然后交给业务 handler。

`AivsComponent.DataHandlerImpl.handleData` 在设备声明 `xiaoai` 且已连接时接收类型 103，要求首字节为 0、下一字节为 1–4，再转给蓝牙 AIVS 服务。语音控制命令和压缩数据都经这条通道到达手机。

### AIVS 二进制封装

`AivsCommand.parse` 使用大端 ByteBuffer。每个交付缓冲区先读取一次版本字节 0，其余内容由一个或多个命令组成。常见命令布局如下。

| 字段 | 长度 | 含义 |
| --- | --- | --- |
| version | 1 字节 | 缓冲区开头的 0 |
| type | 1 字节 | 命令类型 |
| length | 2 字节，大端 | 当前命令数据长度 |
| data | length 字节 | 当前命令数据 |

| type | 官方常量 | 手机侧行为 |
| --- | --- | --- |
| 1 | AUDIO_COMMAND_START | 数据长度 1 或 2，首字节是 session ID，第二字节可携带 TTS 开关。建立会话、初始化解码器、准备 ASR，并停止旧 TTS |
| 2 | AUDIO_COMMAND_AUDIO_DATA | 将压缩数据加入当前会话队列 |
| 3 | AUDIO_COMMAND_AUDIO_END | 停止当前输入，向 AIVS 发送 RecognizeStreamFinished |
| 4 | AUDIO_COMMAND_QUIT | ClearSession、标记下次 RenewSession、停止输入并结束会话 |

官方解析器还兼容缓冲区末尾只剩 type 的无负载命令形式。其 L67 旧固件兼容参数会调整循环最小剩余字节要求，S4 应采用当前蓝牙分支的正常格式。未来实现应独立校验长度、截断和命令顺序。

此前日志的 `00 02 01 90` 可精确解释为版本 0、AUDIO_DATA 2、数据长度 0x0190，即 400 字节。

## 音频编码与解码参数

`BluetoothEventSession` 创建 `OpusDecoder(16000, 1, 640, 80)`。反编译把 640 显示为高德 SDK 的 `DEVICE_DISPLAY_DPI_XXHIGH` 常量，已读取该常量定义确认其实际数值为 640。

| 参数 | 官方实现 |
| --- | --- |
| 编码 | Opus |
| 输出采样率 | 16000 Hz |
| 声道 | 1 |
| 压缩帧长度 | 固定 80 字节 |
| PCM 帧长度 | 640 字节，16-bit 单声道 |
| 每帧样本数 | 320 |
| 每帧时长 | 20 ms |
| 400 字节负载 | 5 帧，1600 个样本，3200 字节 PCM，100 ms |

`com.banya.opus.OpusDecoder.b` 要求输入长度可被 80 整除，再按 80 字节切帧，逐帧调用 native decode。JNI 的 `Java_com_banya_opus_OpusDecoder_decode` 在地址 `0x9f0`，从 Java byte[] 拷贝压缩帧后于 `0xaa8` 直接调用 `opus_decode`，frame_size 为 320、decode_fec 为 0，并要求返回 320 个样本。

该已追踪的解码调用链在传输层解密后直接消费 Opus 帧。第二轮历史抓包中不规则的帧首字节仍需用真实完整帧验证。此前仅凭边界字节分布排除“五个 80 字节 Opus 帧”的结论应撤回，官方实现已明确采用此切帧方式。

`BaseEventSession.run` 将解码后的 PCM 交给 `BaseAivsService.postData`，再进入小米 AIVS SDK 的 `Engine.postData`。`BluetoothAivsService.buildConfig` 设置 `asr.codec = OPUS` 和 `asr.encoded_by_client = false`，说明蓝牙侧解码完成后由 SDK 接受 PCM 并负责后续编码流程。

## 停止收音、文字显示与播报

### 停止收音

`BluetoothInstructionCapabilityImpl.processSpeechRecognizer` 收到云端 `SpeechRecognizer.StopCapture` 时，同时触发手机输入停止，并向手表下发 AIVS instruction type 3。

`BluetoothSender.sendAivsInstructions` 将指令装进 protobuf 顶层 type 14、subtype 0，`BluetoothAivsService.sendAivsInstruction` 填入当前 START 命令取得的 session ID。实现时需要把语音通道的会话与 protobuf 应答关联起来。

手表也有 AUDIO_END 和 QUIT 上行命令。因此，连续音频日志只能证明当时收到了持续音频，具体持续原因还需结合 START/END/QUIT、反复唤醒与完整时间线确认。

### 文字与卡片

官方 App 把 ASR RecognizeResult、Template.Toast、其他卡片和会话结束等转换为 AIVS instruction 列表，经上述 type 14/subtype 0 下发。已确认的实例包括 Template.Toast 的 instruction type 256 和 SpeechSynthesizer.Speak 文本元数据的 type 264。最终渲染效果取决于当前 S4 固件，仍待验证。

### TTS 声音

`SpeechSynthesizerCapabilityImpl` 接收云端 TTS 的 PCM，把数据加入播放队列。`onPlayStart` 获取 `BluetoothProfile.HEADSET`，其 ServiceListener 对当前表的 BluetoothDevice 调用 `BluetoothHeadset.startVoiceRecognition`，在 SCO 音频连接建立后通过 `AudioTrack` 播放。AudioTrack 使用 voice-call stream、单声道和 PCM16，采样率来自 TTS 的 onPlayStart 参数，初始默认值为 16000。

结束时调用 `stopVoiceRecognition` 并释放播放器。由此可以确认，官方实现同时维护 SPP 数据链路和 HFP/SCO 音频播放链路。自定义后端的回答文字可以沿 protobuf 发送，手表扬声器播报需要实现相应的蓝牙播放路由。

## Gadgetbridge 可复用部分与验证

仓库已有纯 Java 的 `org.concentus.OpusDecoder`，并在 `ZeppOsAssistantService` 中使用。现有 `AudioUtils.opusToPcm` 针对另一种带 8 字节记录头的文件封装，S4 的实时数据需要按其 AIVS 头与 80 字节帧单独拆包，可复用底层 Concentus 解码器和 WAV 头写入逻辑。

本次用本机已有 libopus 1.6.1 生成 440 Hz 合成音，编码为 16 kHz 单声道、32 kbps CBR、20 ms 帧。5 帧均为 80 字节，拼接为带 `00 02 01 90` 头的 404 字节输入。使用当前仓库原有 Concentus 源码独立 javac 编译，成功逐帧解出 1600 个样本、3200 字节 PCM，共 100 ms。验证输入是人工生成数据，验证范围覆盖封装尺寸与已有解码器兼容性，真实手表数据的内容验证仍待进行。

后续实现入口已明确。

1. 为 Channel.Voice 注册正式的 AIVS 二进制命令处理器，处理 START、数据、END、QUIT 和 session ID。
2. 每个会话维护 16 kHz、单声道 Concentus 解码器，把合法 80 字节帧转为 PCM，向用户自己的 ASR 后端交付。
3. 实现 protobuf type 14/subtype 0 的停止收音、识别结果和回答卡片，并关联 session ID。
4. 在现有绑定下验证收音停止和文字显示，再验证 HFP/SCO 的手表 TTS 播放。

## 实机解码验证

2026-11-17(手机时间)在 K70 + S4 上，经现有 Gadgetbridge 连接、不重新绑定，用 `XiaomiVoiceHandler` 实时解码真实语音通道数据。日志只记录每包解码帧数和电平，不保存 PCM。

- 17 秒内 171 个 AUDIO_DATA 包,855 个 80 字节帧全部由 Concentus 解码成功(每帧 320 样本),0 失败。
- 每包电平在 -77 到 -43 dBFS 之间随环境起伏，符合真实环境音。
- 此前第二轮帧首字节不规则的现象属于正常的 Opus 帧内容变化，不影响按 80 字节切帧解码。
- 本次及此前三轮唤醒都没有观察到 START、AUDIO_END、QUIT 或 protobuf type 14。连 Gadgetbridge 时手表直接推 AUDIO_DATA,处理器在无 START 时按会话 -1 解码。START 和停止收音何时出现仍待确认。

## 证据定位与复现入口

以下行号对应本次 jadx 1.5.6 的输出，输出根目录为 `/tmp/.agents/s4-official-audio/decompiled/sources`。缓存清理后可从上述 APK 重新生成。

| 源文件 | 关键行号 | 核对内容 |
| --- | --- | --- |
| com/xiaomi/wearable/transport/layerl2/L2Packet.java | 27–40、87–90 | 通道 3 与加密 opcode |
| com/xiaomi/wearable/transport/queue/TaskQueueV2.java | 112–166 | 通道映射与传输解密 |
| com/xiaomi/fitness/aivs/init/AivsComponent.java | 106–141 | 类型 103 的语音入口 |
| com/xiaomi/fitness/aivs/bluetooth/AivsCommand.java | 15–65 | 二进制命令头与命令列表 |
| com/xiaomi/fitness/aivs/bluetooth/BluetoothSpeechRecognizerManager.java | 63–116、151–158 | START/DATA/END/QUIT 和 session ID |
| com/xiaomi/fitness/aivs/bluetooth/BluetoothEventSession.java | 25–31、43–68 | Opus 参数与解码调用 |
| com/autonavi/amap/mapcore/tools/GlMapUtil.java | 33 | 常量实际值 640 |
| com/banya/opus/OpusDecoder.java | 17–58 | 固定 80 字节分帧与 PCM 长度 |
| com/xiaomi/fitness/aivs/base/BaseEventSession.java | 139–191、220–237 | 数据队列与 PCM 提交 |
| com/xiaomi/fitness/aivs/bluetooth/BluetoothAivsService.java | 42–49、126–152 | AIVS 编码配置与应答 session ID |
| com/xiaomi/fitness/aivs/bluetooth/BluetoothSender.java | 21–30 | type 14/subtype 0 |
| com/xiaomi/fitness/aivs/bluetooth/BluetoothInstructionCapabilityImpl.java | 941–954、1028–1045、1362–1380 | StopCapture 与文字指令 |
| com/xiaomi/fitness/aivs/bluetooth/SpeechSynthesizerCapabilityImpl.java | 144–171、201–220、305–340 | AudioTrack、PCM 队列与 SCO 状态 |
| com/xiaomi/fitness/aivs/bluetooth/SpeechSynthesizerCapabilityImpl$blueHeadsetListener$1.java | 34–43、46–81 | HEADSET 和 startVoiceRecognition |

临时分析目录为 `/tmp/.agents/s4-official-audio`，含官方 APK、jadx、反编译输出、选取的 JNI 库和合成输入验证脚本。天气与闹钟适配对话复用该目录。
