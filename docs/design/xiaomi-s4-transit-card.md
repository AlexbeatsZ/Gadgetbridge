# S4 复旦大学联名交通卡：恢复、迁移与 SE 代理研究

2026-11-22后续研究已按用户要求分开：路线A见[xiaomi-tsm-auth.md](xiaomi-tsm-auth.md)，路线B见[xiaomi-s4-nfc-host.md](xiaomi-s4-nfc-host.md)。本文件保留早期证据；最新已验证到系统tsm-auth应用权限拒绝、独立官方QR初始化，以及S4原始CE注册处理器的真实失败回调。尚无已批准TSM会话或射频APDU往返。生产app不改，不发布APK。

最后验证：2026-11-21（America/Los_Angeles）；K70 日志为 2026-11-22（Asia/Shanghai）。设备为 Redmi K70 + Xiaomi Watch S4，S4 固件 3.201.79。任务仍未完成：尚不能通过 S4 使用手机上的真实联名交通卡。

## 范围与验收

优先 A：Gadgetbridge 自己实现发行方允许的原生迁移；其次 B：手表 NFC 前端经蓝牙将 APDU 交给手机 SE。不得把跳转、下载链接、示例画面或探针称为公交卡功能。核心链路验证前不发 APK。真实卡、余额、校园身份绑定和手表配对保持不变。

A 的验收至少包括合法认证、源卡和目标产品资格、可恢复的迁出/迁入状态机、发行方确认、目标 SE 个性化与真实刷卡。B 的验收至少包括 RF SELECT 测试 AID、原生回调、测试响应回到读卡器、蓝牙往返和超时/WTX，再到经授权的手机 SE 后端。当前完成的是管理通道和原生代码执行前提。

## 第一阶段：实际恢复结果

- 错误提交 `2eb2e983deb9d41489a47ff5599146986fa166d4`；回滚提交 `985018fbea9a28fbc8e34dace53447624a7ac9f4` 保留。未 reset、未 force push、未改写历史。
- GitHub 完整 Release 列表（含 draft）无 v2026.11.17，按 Tag 查询和原 APK 下载 URL 均返回 404。远程遗留 Tag 原指向错误提交，本次正常删除该 Tag；本地同名 Tag 已不存在。
- v2026.11.16 保留：Tag `cc7395480eda18d03161473b41c6858cfd93901f`，Release ID 451260019，APK asset ID 767448827，47,019,820 bytes。回滚提交的 `app/` 与该版本源码相同。
- 无线 ADB 实读 K70 为 2026.11.16/code272，实际安装 APK 与原 GitHub 发布 APK 逐字节一致，原签名证书指纹 `cbde4ad49f895d2e7943fc3e66cca51f7efff0a79a71b08dca9425ce6909cda1`。本次不需要再降级，也未安装/卸载/清数据。
- UID 10261，原首次安装时间保留。应用数据库 190 表，DEVICE=1；原 S4 认证 key 仍在，已用原绑定连接。控制中心实际显示 S4 已连接、电量 93%；HFP 为活动设备，通知监听和设备通信服务在运行。
- 第二阶段结束后再次比对：活动样本 16,472、心率样本 217,532、睡眠记录 15、睡眠阶段 223，均与实验前一致。未重新验收用户长按唤醒、实际 Gemini 播报、真实闹钟响铃和自动上传，不能把静态设置及连接正常写成全部功能实机验收。

## A：小米运动健康完整业务链路的已定位部分

静态对象为 com.mi.health 3.61.0/code361000。jadx CLI 输出 398 个方法反编译错误；以下关键直线方法已读，复杂协程/迁移恢复分支尚须用字节码或动态调用补足，不能称为完整迁移已验证。未在官方 App 中启动移卡。

### 上层到设备传输

1. `CardListViewModel` 加载支持产品，经 `fbt` 调用 `MiPayService.getSupportedCardList(CLOUD_TRANSIT)`。
2. `fbt` 初始化 `MiPayService` 的 DeviceInfo、Channel、APDU executor 与 `MiOAuthImpl(TokenManager)`；设备身份由手表型号、SN、固件和 CPLC 等构成。
3. `CardWrapper.transferOut` → `SeCard.transferOut`；`CardWrapper.transferIn` → `SeCard.transferIn(transferInOrderToken)`。`CloudTransitCard.transferIn` 要求存在可移入订单。
4. `TransitCard` 在迁移前检查服务可用性；`model.s` 创建迁出订单，拿到 orderId 与 transferOutToken 后交给 `CardInfoManager.transferOut`。`model.mitsm.c/f` 执行服务端返回的 APDU，验证状态字、上传执行结果、继续拉取脚本并完成通知。
5. `ChannelImpl` → `jui` → S4 使用的 `ct4`（非 Huami 路径）。管理命令是 type5/subtype0，protobuf field7=NFC、内层 field1=命令字节。C0=open，C1=close，C2=transceive；各为 32-bit 大端 opcode、16-bit 大端数据长度、APDU。
6. 回复为 32-bit 大端 status、16-bit 大端长度、数据。官方 `NfcResponse` 接受 1 或 224；这是外层管理封装状态，不能与 NXP 底层错误枚举混为一谈。

### TSM 与发行方链路

线上主机 `https://tsmapi.pay.xiaomi.com/`，serviceId=`tsm-auth`。认证路径按登录/OAuth 使用 `api/login/...` 或 `api/oauth/...`。已定位的调用如下，未执行任何真实卡写操作：

| 阶段 | 私有接口（相对路径 `api/%s/`） | 作用及当前验证 |
| --- | --- | --- |
| 资格 | `acl/checkServiceAvailable` | 官方 TRANSFER_OUT 等服务资格检查；尚未以合法账号查询该卡 |
| 云端卡产品 | `spcard/v2/queryShiftInCardProducts` | 需要 CPLC、产品类别、deviceModel、客户端版本、迁移能力等；空的未认证只读请求实得 HTTP 401 |
| 迁出订单 | `transferCard/prepareTransferOut` | 返回 orderId/transferOutToken；涉及真实业务状态，未调用 |
| SE 会话 | `se/createSession` | 绑定 CPLC、设备/账户、固件等；未建立真实会话 |
| SE 操作 | `se/startSeOperation` | 携带操作、AID、订单授权码；未调用 |
| 迁出脚本 | `se/pullBusCardShiftOutData` | 获取服务端迁出脚本；未调用 |
| 逐批执行 | `se/processSeResponse` | 上传 APDU 执行状态，拉取后续步骤；未调用 |
| SE 完成 | `se/persoFinishNotify` | 个性化/操作完成通知；未调用 |
| 迁出确认 | `transferCard/confirmTransferOut` | 订单/源卡状态收敛；未调用 |
| 移入订单 | `transferCard/startTransferIn` | 换取 issue/withdraw 等目标侧令牌；未调用 |
| 迁入脚本 | `se/pullBusCardShiftInData` | 目标设备下载、个性化/激活脚本；未调用 |

`MiOAuthImpl` 向 TokenManager 请求 `serviceToken,security,userId`；SDK 网络层加 cookie、加密/签名。401 只证明空请求未通过认证，不证明接口不可调用，不证明发行方拒绝这张卡，也不证明 S4 产品资格。Gadgetbridge 尚未实现自己的合法 TSM/OAuth 会话及签名客户端。

系统服务实测补充：K70 的 Android AccountManager 中存在1个 `com.xiaomi` 账号，认证器为 `com.xiaomi.account/.authenticator.XmAuthenticationService`（R-24.05.28.12）。但在正在运行的 Gadgetbridge 实际 UID 内调用 `AccountManager.getAccountsByType("com.xiaomi")` 返回0；没有读取账号名、请求令牌或修改可见性。`account-visibility.cpp` 归档此只读检查。已有系统账号不能直接当成 GB 已获授权，下一步须接入用户授权的账号选择/访问或独立合法 OAuth，再获取 tsm-auth 会话。此结果同样不代表该账号无权迁卡。

冻结、移出、目标个性化和激活由订单状态机及发行方脚本驱动，不能把某条 APDU 或一个可访问的 SE 当成可导出卡密钥。源卡不变的约束下，本轮停在只读资格/认证研究，未创建迁移订单。后续先取得合法认证并仅查询源卡 TRANSFER_OUT、目标产品/联名资格；不要试调用 prepare/start/confirm 来“看看是否支持”。

### 手机 SE

K70 已有 `k70-ese-nfc` 项目的 eSE1 适配，已有受项目签名保护的只读 Messenger 接口。该项目记录 Android16 OMAPI 的 ARA 拒绝普通调用，以及现有授权组件/厂商 SE 实现路径。这里没有改动该项目、ARA、路由、余额或原卡。Gadgetbridge 与适配器不同签名，不能假设能直接复用只读接口，更不能把它扩成任意公交交易 APDU 通道绕过访问控制。

可复用：已验证的手表管理传输、SE 元数据读取、订单/会话及 APDU 批次的协议结构与错误恢复设计。仍须建立或确认：合法账号服务会话、应用身份/授权、源卡迁出与目标卡产品资格、发行方/TSM 的脚本与密钥域授权、联名校园身份是否随目标产品保留。尚没有证据可判定这些项全都获准或全都不可实现。

## B：原生 NFC 前端和手机 SE 代理

### 已完成的实机链路

现有 2026.11.16 debug APK 通过临时、固定命令 allowlist 的 JNI agent，复用原已认证 SPP V2 连接。没有安装实验版 Gadgetbridge。源码与 runner 在 `tools/research/xiaomi-nfc-probe/`。

| 操作 | 实际响应 |
| --- | --- |
| type5/subtype10 能力查询 | field10=1 |
| C0 打开 | status224，空数据 |
| C2 SELECT `F0010203040506` | status224，APDU `6A82`（测试应用不存在） |
| C2 CPLC GET DATA | 外层224、TLV `9F7F2A`、SW9000；只保留厂商/芯片型别479009DC |
| C1 关闭 | status224，空数据 |

这些结果多轮复现，最后在清理恢复连接后由修正并加固的 runner 再次通过。证据 `docs/evidence/xiaomi-s4-nfc-se-2026-11-21.json`。本轮不选择真实公交 AID，不读取/导出密钥，不执行真实卡交易。该通道是手机→手表 SE 的 wired 管理访问；不是读卡器→手表主机→手机 SE 的射频代理。

Lua 快应用未暴露 NFC/HCE，不能作为底层不支持的结论。进一步实验已证明：

- 自建 Lua 表盘在 S4 执行 `os.execute`、`io.open`，快应用用文件 IPC + interconnect 回传；实际目录为 `/data/quickapp/files/com.meta.s4nativeprobe/`。
- 实机 `/dev/nfc_sn100` 存在，`ps` 中有 `nfc_stack_bridge`、`nfc_task`、`nfc_readerTask`、`nfc_writerTask`。
- 自建 import-free ARM Thumb ET_REL 空模块由 `insmod` 加载，实际 `lsmod` 出现 `gb_readonly_probe`；`rmmod` 后实际列表为空。不是只依据 shell 成功码。
- `/proc/version` 返回 `NuttX version 3.6.1 3a1418973b Jul 3 2026 19:24:21`。它是此固件字符串，不按上游 NuttX 版本号推导所有接口。
- 未刷固件、未运行社区分区修改脚本、未打开/重置 NFC 设备。空模块没有导入、固件函数调用、路由修改或自动启动。

### 原固件静态证据

下载与实机相同 3.201.79 的官方 OTA 内容归档镜像，仅离线解包/反汇编。AP 基址通过多个字符串引用核对为文件 offset+`0x2c1c0000`。以下是文件偏移，不是可以直接调用的公开 ABI：

| 项目 | 已核对事实 |
| --- | --- |
| NFC 驱动 | AP 中有 `/dev/nfc_sn100`、`board_sn110_initialize`；vendor 包有 `nfc/sn220tu.cesfwu`。不能单凭这些字符串断言精确在用芯片型号 |
| SE wired 命令 | type5 的 C2 走 `SeApi_WiredTransceive_upper_layer` 并投递内部消息，区别于 RF 主机回调 |
| `nfc_card_emulation_cb` | 函数约0x2d367c，属于工厂售后界面/状态回调；名称本身不代表 APDU 回调 |
| `nfc_msg_up_cb` | 约0x2cc2bc，已查看分支处理 RF 结果和读卡记录；未在此函数确认通用 APDU 请求/响应代理 |
| `nfceeActionEventCallback` | 约0x17a99c，解析 SE 事务/AID 事件；不等于主机 CE 数据事件 |
| NXP CE 封装 | eSE listen 配置的分配大小48、事件0x401、listen_type偏移0x0c、ee_handle0x2a、tech_mask0x2c，与公开 NXP NFA CE 结构吻合；尚未据此向真实 NFC 栈发送自造事件 |
| 分配/消息入口候选 | 0x182e84 / 0x18de2c 出现在已核对 eSE listen 配置链，分别符合分配及投递；仍为静态候选，没有做固件函数调用实验 |

成熟 NXP 实现使用 `NFA_CeRegisterAidOnDH`、`NFA_CeSetIsoDepListenTech`、`NFA_CE_DATA_EVT` 和 raw-frame 响应链。固件未找到相应函数名字不能证明其实现不存在；可能被裁剪、内联或去掉日志。当前只确认已有 eSE listen 与管理层，尚未验证 S4 的 host CE 动作表、数据事件处理、线程上下文和发送函数。因此不贸然使用别机型地址、猜测 ABI 或独占重启控制器。

### 下一步最小实验

1. 先完成只读的固件 CE 动作表/注册结构与 NXP 源码匹配，核对宿主数据处理和发送函数是否在此固件中保留。原生模块加载条件已成立，不需再把快应用接口缺失当障碍。
2. 只注册测试 AID `F0010203040506`，保留 SE/default 路由，使用独立读卡器或 K70 reader mode 实际 SELECT；模块回固定测试响应，确认 RF 双向，随后撤销测试 AID。需要物理射频贴靠；本轮未声称做过。
3. RF 通过后，再测 SPP 测试 APDU 往返、断连/超时/WTX，接手机的测试后端；此阶段也不用真实公交卡。
4. 真实 SE 交易必须由发行方允许且通过 SE 访问控制，之后才评估此联名产品是否可代理。无需复制密钥不等于自动获得交易授权。

## 实验清理与恢复

- 原活动表盘 ID 已先记录。测试面362991121全部删除，实际回读保留原9面，原「昼与夜」仍活动。临时快应用 `com.meta.s4nativeprobe` 在实际应用列表中已不存在。临时 Lua 清除了自己的私有目录。
- 临时清理 JNI 因 Java 返回签名写成 List 而实际为 Collection，导致一次 Gadgetbridge native crash。已修正调用并完成删除；应用自动重启后按原绑定主动连接，认证和管理命令成功，HFP 活动恢复。没有重装、清数据或重新配对。
- 协议 runner 另补全 JNI 查找检查、目标地址匹配、无应答失败和 finally 关闭/文件清理。已用修正后的版本重新通过实际 SE 实验。错误的清理 agent 不归档成可复用工具。
- 归档源码不含 APK、表盘二进制、OTA、反编译源码、完整 CPLC、key、token、真实卡号和健康数据库。大型离线研究材料只在 Mac `/tmp/.agents/gadgetbridge-transit-research`，不进入 Git。

## 一手实现与参考

- [小米 Vela 文件 API](https://iot.mi.com/vela/quickapp/en/features/data/file.html)；[应用上下文及退出/动态库边界](https://iot.mi.com/vela/quickapp/en/features/basic/app.html)。
- [Apache NuttX 模块命令](https://nuttx.apache.org/docs/latest/applications/nsh/commands.html#insmod-install-an-os-module)；[insmod 实现及符号表要求](https://github.com/apache/nuttx/blob/master/sched/module/mod_insmod.c)。
- [NXP NFA CE API](https://github.com/NXPNFCLinux/linux_libnfc-nci/blob/master/src/libnfc-nci/nfa/ce/nfa_ce_api.c)、[内部结构](https://github.com/NXPNFCLinux/linux_libnfc-nci/blob/master/src/libnfc-nci/nfa/int/nfa_ce_int.h)、[事件动作](https://github.com/NXPNFCLinux/linux_libnfc-nci/blob/master/src/libnfc-nci/nfa/ce/nfa_ce_act.c)。
- [AstroBox 原始 wear_nfc.proto](https://github.com/AstralSightStudios/AstroBox-NG-Module-Pb/blob/main/protos/xiaomi/wear_nfc.proto)、[watchface 安装回执定义](https://github.com/AstralSightStudios/AstroBox-NG-Module-Pb/blob/main/protos/xiaomi/wear_watch_face.proto)。
- [LuaDevTemplate 原作者实现](https://github.com/FangAiden/LuaDevTemplate)、[Lua 文件 IPC 原作者实现](https://github.com/FangAiden/Vela-Shell-Bridge)、[MiWatchNativeApps 原作者实现](https://github.com/m0tral/MiWatchNativeApps)。社区不同机型的代码只作为路径参考，S4 的执行能力由本轮实机结果成立。
- [3.201.79 OTA 归档镜像](https://github.com/AstralSightStudios/MiWearFirmwareArchives/releases/tag/firmware-mijia.watch.o62-v3.201.79-88e9c536)。
