# 路线 B：S4 原生 NFC Host APDU 与射频代理

## 范围与当前结论

本路线只研究「S4 主机能否处理外部 NFC 读卡器的 ISO-DEP APDU」。手机SE管理、真实公交卡迁移、生产Gadgetbridge功能不属于本阶段实现。代码在 `tools/research/xiaomi-nfc-host`，继续复用已有 `xiaomi-nfc-probe/watch` companion 和单一表盘 ID `362991121`，没有创建多个表盘ID。

用户已改为优先推进蓝牙代理，A的真实迁移暂停。本路线先验证外部读卡器↔S4主机固定APDU，再用现有Classic SPP接固定测试后端；BLE后评估。此选择不代表已经证明能代理真实公交卡或保留校园功能。

2026-11-22 新实机进展：通过原NFA DM/NCI队列发送唯一只读命令 `21 02 00`，控制器响应 **`41 02 01 00`（成功）**。响应回调event0x42/length4/count1；随后callback槽和busy flag清零、命令窗口恢复1、队列为空，诊断模块卸载exit0、lsmod为空，eSE flags前后0x8101。**这只是GET_ROUTING命令状态成功；单独的路由表通知还未捕获，不能当作路由备份或RF APDU成功。**

2026-11-22 实机：原始 NFA CE 分发器对虚构 AID `F0010203040506` 返回 **REGISTERED事件0x15、status3、handle0xFFFF**，注册失败。当前固件CE动作表中的配置本地卡/注销/ISO-DEP配置槽是同一个空函数；注册处理器仅保留可用的eSE监听分支，当前eSE监听项已占用。

这是原生栈入口的具体阻塞证据，**不是整个硬件不支持HCE的结论**。尚未完成测试AID射频SELECT/固定应答、SPP代理、时延/WTX测量，不能称功能完成。没有执行真实卡APDU或支付交易，没有停止现有NFC栈或改路由。

## 精确材料和地址规则

- 实际 S4 固件 `3.201.79`，NuttX `3.6.1 3a1418973b`。
- AP原始镜像 `mijia.watch.o62_v3.201.79_full_f86aa1eb.bin` 中 `vela_ap.bin`，基址 `0x2c1c0000`。下表地址是此版本运行时地址；file offset = address − base，ARM Thumb函数指针bit0=1，反汇编前清除bit0。
- 不能把 `/dev/nfc_sn100`、`board_sn110_initialize`、`sn220tu.cesfwu` 的命名直接当成已实读的芯片型号。
- `firmware.py` 只读离线镜像；拒绝奇数起点，避免把Thumb函数指针当文件指令地址错位解码。

## B1/B2：驱动、fd、任务和IPC

### 驱动操作表（离线定位且实机ROM回读一致）

`0x2c571b48` 附近向驱动注册函数传 `/dev/nfc_sn100`、file_operations=`0x2c9d235c` 和私有结构。实机 `xd 2c9d235c 40` 的表值与镜像一致：

| 槽 | 地址 | 已确定用途 |
| --- | --- | --- |
| open | `0x2c33d341` | 打开钩子 |
| close | `0x2c33d325` | 关闭钩子 |
| read | `0x2c33f045` | I2C消息读，flags=1 |
| write | `0x2c33f12d` | I2C消息写，flags=0x80 |
| seek | 0 | 未实现 |
| ioctl | `0x2c33ef4d` | 对cmd低8位0..4分派；GPIO/IRQ/关闭同步 |
| mmap/truncate/poll等尾槽 | 0 | 此表未提供 |

`ioctl` 的0/1/3分支控制私有结构中offset0x24/0x20/0x28对应的GPIO；2分支arg1/2启停IRQ；4分支进入关闭/解锁日志路径。引脚的确切硬件角色还未全部命名。**没有在此分派器找到AID注册或Host APDU回调命令。** 本轮未打开该设备、调用ioctl或读取NCI流，避免抢走原栈消息。

### 进程和文件描述符：保留已发现的不一致

实机NFC任务组47成员为 `47 54 58 59 60`，对应已有的 `nfc_stack_bridge/nfc_task/nfc_readerTask/nfc_writerTask/client task`。`fdinfo`用法是 `fdinfo [pid]`，不是 `-p`；第一次 `fdinfo -p 47`失败，改为 `fdinfo 47`后成功。

组47/54/58/59的实际fd视图相同：fd6=`nfc_msg_down`，fd7=`nfc_msg_up`，fd8也显示`nfc_msg_up`。同时TML上下文全局指针 `*(0x3c2d3708)=0x3c4ee090`，其offset0x38保存值8。不能把这个8直接等同于上述proc fd8就是NFC设备；驱动open包装还通过另一个文件上下文间接访问fd表。该索引/上下文关系未完全恢复，不能用猜测的fd读取控制器。

### TML与IPC调用链

- TML初始化 `0x2c34b3f4` 调open包装`0x2c55d838`，参数是驱动路径和0x403，结果写入ctx+0x38。初始化还调用配置/IRQ分支，不可用重新初始化来做只读探针。
- Reader入口`0x2c34acf9`、Writer入口`0x2c34b05d`由原栈创建，prio180、stack0x1c00；使用ctx+0x44/0x48信号量、+0x1c读buffer、+0x30写buffer、+0x34长度、+0x38传输句柄。没有读取这些真实buffer内容。
- Reader按3字节NCI头解析payload长度并读剩余数据；Writer发送NCI包并用已有client task消息/定时器回调完成。这是芯片传输层，不是支付APDU回调。
- `/var/mqueue` 实读有 `nfc_msg_down/nfc_msg_up/nfc_task_msg/client_task_msg` 等。`nfc_stack_bridge` 从down队列读12字节结构：首字节type，offset4为payload指针；分派1..32。
- wiredSE通道的down消息2/3/4分别对应WIREDENABLE/WIREDTRANSCEIVE/WIREDENABLE_EX（已有type5管理协议通过此链）；RF_MSG是状态通知，不能据此判为ISO-DEP APDU交付。
- `nfa_sys_sendmsg`候选`0x2c34de2d`通过原GKI task2消息队列；`nfc_task_msg`是NFA事件处理所在路径。核心分发器确实存在，见下节。

## B3/B5：CE动作表与原始处理器

实机读取 `*(0x3c203dac)=0x2c8b4590`，该子系统注册结构的handler=`0x2c340eb5`，动作表在`0x2c8b4578`。handler按消息首字节（event低8位）选择六个动作，随后可能调用`nfa_ce_cb+0xd0`的可选vendor handler。

| event | 官方语义 | S4实际槽值 |
| --- | --- | --- |
| 0x400 | 配置本地NDEF tag | `0x2c340ead`，仅return1 |
| 0x401 | 注册监听 | `0x2c341eed`，特化的注册处理器 |
| 0x402 | 注销监听 | `0x2c340ead`，仅return1 |
| 0x403 | 配置ISO-DEP监听技术 | `0x2c340ead`，仅return1 |
| 0x404 | 激活通知 | `0x2c3418f9` |
| 0x405 | 去激活通知 | `0x2c341e69` |

空函数机器码实机回读为 `80 b5 01 20 00 af 80 bd`（保存/恢复寄存器并返回1）。这个1代表分发器可释放消息，**不是注册/配置成功**。

注册结构与公开NXP版本匹配：BT_HDR8字节；callback@8；listen_type@12；AID@24；aid_len@40；ee_handle@42；tech_mask@44；总48字节。eSE配置的官方调用在S4被内联成event0x401、type4、handle0x4c0、techmask7。Host类型为1。

S4特化注册处理器只在type4分支设置eSE字段，当前的单个监听项flags=`0x8101`。Host type1请求会先因为in-use位被拒绝；没有进入公共NXP实现里的 `CE_T4tRegisterAID` / 多listen_info分支。即使清掉占用位，剩余代码也不含等价Host注册完整实现；**没有在实机清过该位**，因为会影响现有eSE监听且不能产生完整HCE功能。

### 本轮原始ARM代码离线执行（非实机RF）

`verify_stock_ce.py` 用Unicorn执行镜像中原始分发器，合成48字节消息和RAM，flags0x8101：

- Host AID event0x401产生callback(event0x15,status3,handle0xFFFF)。
- 0x402/0x403均返回1，没有回调，合成CE控制结构不变。
- 版本指纹、动作表不匹配即拒绝验证。

### 本轮原始处理器实机验证

`stock-ce-rejection.c` 为388字节text/rodata、52字节data的零导入ET_REL诊断模块。加载前由代码检查六个ROM表槽、空函数指纹、CE系统注册指针、`flags==0x8101`及vendor handler为空。只在这些条件全满足时调用**已验证无状态写入的拒绝分支**，结果写入模块自身48字节数据。

2026-11-22 16:10:31 Asia/Shanghai实读模块输出：

```text
magic=0x53444345 guard_result=2 flags_before=0x8101
callback_count=1 event=0x15 status=3 handle=0xFFFF
dispatch_return=1 flags_after=0x8101
deregister_return=1 config_isodep_return=1 flags_final=0x8101
```

`insmod`和`rmmod`均exit0；lsmod加载后出现`gb_ce_reject`，卸载后为空。没有向控制器发测试/真实APDU，没有挂钩或替换固件函数，没有遗留回调。

证据分别为 `xiaomi-s4-nfc-host-abi-2026-11-22.json`（只读ABI/IPC采样）与 `xiaomi-s4-stock-ce-rejection-2026-11-22.json`（实际注册失败回调）。离线测试和实际调用分开，二者均不冒充RF SELECT测试。

## 后续路径与最小验证实验

直接复用现有标准CE注册/注销API已遇到明确实现裁剪，重复调用不能前进。继续研究以下两条底层路径，不能只依据无公开API结束：

1. **保留现有栈的Host扩展**：原始RF接收、raw send及NCI路由构造已经定位并离线执行，见下节。仍须恢复测试AID分发、DH路由增加/撤销及完整路由备份。不能直接在唯一eSE槽注册或清标志位；公开动作表的下标也不能直接用于此固件。
2. **独立用户态NCI服务**：先把TML句柄8对应的文件上下文/所有者查明，明确原reader/writer排他关系、已有路由读取和恢复方法，再评估受控的Host路由/回调实验。未确认前不开第二个/dev/nfc_sn100读者，不直接向设备写NCI包，也不刷固件/加载复杂内核驱动。

只有AID注册成功且可无损注销后，才让另一台手机发 `00 A4 04 00 07 F0 01 02 03 04 05 06 00`，要求S4主机返回固定测试数据及9000；移开重贴后仍能工作。然后走现有SPP固定测试后端，统计单次及P50/P95/P99、超时、WTX与断开恢复。**当前未到该阶段，所以没有虚构这些指标，也未要求用户贴卡。** BLE在SPP完整通过后评估。

## 保留的RF数据链与回调所有权（2026-11-22新增）

表中“实读指针”是S4运行时元数据；功能语义另由原镜像反汇编/合成执行确认。两类证据不能互相替代。

| 地址/槽 | 已确认语义与证据 |
| --- | --- |
| DM `0x3c2d46fc`，action表 `0x2c8b459c` | 实机28槽与镜像一致；raw send在index12/event0x10c，handler=`0x2c3420d1` |
| DM+0x24 | 实读应用连接callback=`0x2c336741`；event9分支复制到既有同步请求的buffer，并非自带Host APDU服务 |
| `0x2c34319f` | RF receive callback，R0=conn id/R1=event/R2=event data；0x6003读取status与BT_HDR，将event9/status/payload/len同步交给DM应用callback，再释放原buffer一次 |
| NFC `0x3c2d4b8c` +4 | 实读static RF callback=`0x2c34472d`；该函数收到0x6003直接释放buffer，0x6004通知状态机，不处理Host APDU |
| `0x2c34dff1` | 安装static RF callback后立即调用pending-data drain；不能当作纯指针赋值，也不能卸载仍被引用的模块 |
| NFC+0xa4 | 实读discovery callback=`0x2c344699`，区别于RF数据callback及NFC+0xa8的响应callback |
| `0x2c34e6d5` | NFC_SendData，原raw send在LISTEN_ACTIVE(5)走此函数；RF connection id0经`0x2c34e5c5`查连接，再进`0x2c34e0d5`队列 |

BT_HDR为8字节，data在header+8+offset；发送要求offset≥4。raw handler接受POLL_ACTIVE(4)/LISTEN_ACTIVE(5)，在listen下设置RAW_FRAME flag并关闭reassembly，再调用SendData。原dispatcher返回0表示消息所有权已交给下层，失败时下层可能已经释放，**不是让调用者再free一次，也不等于射频已发送成功**。未激活状态返回1，调用者仍持有消息。

`verify_rf_data.py`执行原始ARM函数、合成RAM与测试SELECT/固定`S4TEST9000`响应，14项通过：有效接收、实际callback目标丢弃、空buffer、去激活清callback、安装/恢复callback、listen发送、队列失败只释放一次、offset不足、RF连接缺失、5种非激活状态。应用callback、GKI free、pending drain和发送队列是明确模拟边界；每例合成eSE控制结构均未变。没有外部读卡器、真实RF或SPP测量。

## 路由命令、只读查询及剩余通知缺口

### 已验证的原栈命令路径

- DM index26/event0x11a=`0x2c343181`，即保留的NXP raw NCI action。合成布局：callback@8、cmd len@13、payload@20，action把BT_HDR.offset设12/len设命令长度、event0x4000/layer_specific2，交`0x2c34e385`队列。
- 队列在NFC+0x140保存临时callback，+0xbc标raw command，+0x218存待匹配头；按原HAL通道发送、释放消息、维护命令窗口与超时。**不需要第二个/dev/nfc_sn100 reader。**
- 原raw response处理器`0x2c34e561`给callback传event=`OID|0x40`、长度和完整NCI响应，返回后清callback/+0xbc并更新窗口。本轮真实GET_ROUTING走通此路径。
- `get-routing-status.c`为380字节text、64字节结果的小型零导入模块；只允许常量`21 02 00`，没有路由写或任意APDU输入。加载时检查版本指纹、eSE flags8101、discovery状态1、原callback为空、busy=0、window=1、queue count=0，不满足即拒绝发送。
- Lua在1秒后读取模块自身64字节及状态；仅当结果不再pending且原callback/busy均已清除才卸载。成功结果为module state3、callback_count1、event0x42、len4、status0、packet41020100；与旧CE拒绝探针的guard_result2不能混淆。

实机时间：2026-11-23 08:55:48 Asia/Shanghai，等于客户端2026-11-22 16:55:48 America/Los_Angeles。证据`xiaomi-s4-get-routing-status-2026-11-22.json`保存实际输出与清理验证。

### 不能省略的路由备份

`0x2c34e291`保留SET_LISTEN_MODE_ROUTING构造器，R0=more、R1=numTLV、R2=TLV字节数、R3=TLVs。原代码构造`21 01 [len] [more] [count] [TLVs]`再交命令队列；空TLV构造`21 01 02 00 00`，会结束/替换路由配置，**不是只读查询或“清理测试项”的无害调用**。未在实机发送SET_ROUTING。

`verify_nci_routing.py`的8例执行原始ARM：不透明的合成旧项+测试AID、合成恢复、more分段、空表的真实命令格式、分配失败、GET原dispatcher→原queue、原response后清指针、普通GET_ROUTING事件忽略。`verify_getroute_probe.py`另有7例执行实际编译探针：正常状态/清理和6个拒绝分支。总29例离线通过，明确不证明实卡路由或控制器支持Host AID。

GET_ROUTING状态响应和包含路由表的`61 02 ...`通知是两个包。当前S4普通响应callback`0x2c343bd1`对event0x500b直接return；NFC任务的RF通知OID2也指向丢弃路径。本轮只保留了前者的原始状态响应，没有挂钩后者，所以**不能声称已经读取路由表**。

继续调查找到HAL data callback的精确链：HAL open=`0x2c34b1f5`将R1存入`0x3c2d45cc`；trampoline=`0x2c340d35`从该槽调用，签名为`(u16 length,u8 *packet)`；初始化传入`0x2c34cd85`，它将NCI帧复制进BT_HDR并排入原NFC任务。此链当前只有镜像证据，**尚未实读该槽当前值，尚未安装拦截器**。

下一项最小实验是：先只读确认HAL槽，再离线验证一个仅捕获本次查询`61 02`通知的短期转发器（其他帧原样转交且不记录）。必须覆盖通知分片、长度错误、没有通知、超时及撤销时的在途callback；回调引用清除和实际执行退出都可确认后才能在实机安装。取得完整原表及不可截断的备份后，才考虑追加测试AID→DH、查询回读和恢复。若原栈会自动重写路由，还需与其队列/状态机协调。此处不以清空eSE项绕过缺口。

## 结束状态

实际回读原9个表盘及原活动「昼与夜」ID120917345464保持；临时表盘/RPK删除，原生模块卸载，手机临时研究DEX/so和安装暂存移除。ROG临时编译目录删除。Gadgetbridge进程始终PID10882，无本轮重启/崩溃或重配对。见 `docs/evidence/xiaomi-s4-research-cleanup-2026-11-22.json`。

新增GET_ROUTING实验后再次比较原9表盘完整列表相等、原活动表盘保持；com.meta.s4nativeprobe已从RPK列表消失，原com.meta.s4probe/com.meta.s4nfccapability保留。手机本轮so/face/rpk暂存和ROG两个编译目录删除；GB仍PID10882。只确认RPK卸载，未另读卸载后的sandbox是否消失；长按助手、闹钟、校园和交通刷卡本轮未做物理复验。新清理证据在GET_ROUTING结果内。

Lua表盘本轮观察到首次加载时ready目录未就绪会直接return，点亮不会保证初始化重跑；同ID直接覆盖又返回type4/sub4 code5。有效顺序是先启动companion创建目录，再恢复原表盘/删除旧测试项/重装同ID；不能将前两次无输出解释为GET_ROUTING不支持。

## 一手对照源码

- https://github.com/NXPNFCLinux/linux_libnfc-nci/blob/master/src/libnfc-nci/nfa/ce/nfa_ce_api.c
- https://github.com/NXPNFCLinux/linux_libnfc-nci/blob/master/src/libnfc-nci/nfa/ce/nfa_ce_main.c
- https://github.com/NXPNFCLinux/linux_libnfc-nci/blob/master/src/libnfc-nci/nfa/ce/nfa_ce_act.c
- https://github.com/NXPNFCLinux/linux_libnfc-nci/blob/master/src/libnfc-nci/nfa/int/nfa_ce_int.h
- https://github.com/open-vela/nuttx/blob/dev/include/nuttx/fs/fs.h
- https://github.com/NXPNFCLinux/linux_libnfc-nci/blob/master/src/libnfc-nci/nfa/dm/nfa_dm_act.c
- https://github.com/NXPNFCLinux/linux_libnfc-nci/blob/master/src/libnfc-nci/nfc/nci/nci_hmsgs.c
- https://github.com/NXPNFCLinux/linux_libnfc-nci/blob/master/src/libnfc-nci/nfc/nfc/nfc_main.c

公开源码仅用于结构/语义对照，S4实际代码和实机数据优先。原生模块分别验证标准CE拒绝与只读NCI查询，不是公交卡/射频代理实现。
