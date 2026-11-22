# 路线 B：S4 原生 NFC Host APDU 与射频代理

## 范围与当前结论

本路线只研究「S4 主机能否处理外部 NFC 读卡器的 ISO-DEP APDU」。手机SE管理、真实公交卡迁移、生产Gadgetbridge功能不属于本阶段实现。代码在 `tools/research/xiaomi-nfc-host`，继续复用已有 `xiaomi-nfc-probe/watch` companion 和单一表盘 ID `362991121`，没有创建多个表盘ID。

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

1. **保留现有栈的Host扩展**：恢复完整Host listen_info/AID分发和发送数据函数，确认NFA DM action编号、NCI连接回调、DH路由增加/撤销的ABI。S4动作表与公开NXP的条目数量/实现不同，不能按公共表下标盲调用 `NFA_SendRawFrame`。最小实验先用合成NCI帧在离线执行中走到指定测试AID回调/固定应答，并证明不会覆盖现有eSE项；不能直接在唯一eSE槽注册或清标志位。
2. **独立用户态NCI服务**：先把TML句柄8对应的文件上下文/所有者查明，明确原reader/writer排他关系、已有路由读取和恢复方法，再评估受控的Host路由/回调实验。未确认前不开第二个/dev/nfc_sn100读者，不直接向设备写NCI包，也不刷固件/加载复杂内核驱动。

只有AID注册成功且可无损注销后，才让另一台手机发 `00 A4 04 00 07 F0 01 02 03 04 05 06 00`，要求S4主机返回固定测试数据及9000；移开重贴后仍能工作。然后走现有SPP固定测试后端，统计单次及P50/P95/P99、超时、WTX与断开恢复。**当前未到该阶段，所以没有虚构这些指标，也未要求用户贴卡。** BLE在SPP完整通过后评估。

## 结束状态

实际回读原9个表盘及原活动「昼与夜」ID120917345464保持；临时表盘/RPK删除，原生模块卸载，手机临时研究DEX/so和安装暂存移除。ROG临时编译目录删除。Gadgetbridge进程始终PID10882，无本轮重启/崩溃或重配对。见 `docs/evidence/xiaomi-s4-research-cleanup-2026-11-22.json`。

## 一手对照源码

- https://github.com/NXPNFCLinux/linux_libnfc-nci/blob/master/src/libnfc-nci/nfa/ce/nfa_ce_api.c
- https://github.com/NXPNFCLinux/linux_libnfc-nci/blob/master/src/libnfc-nci/nfa/ce/nfa_ce_main.c
- https://github.com/NXPNFCLinux/linux_libnfc-nci/blob/master/src/libnfc-nci/nfa/ce/nfa_ce_act.c
- https://github.com/NXPNFCLinux/linux_libnfc-nci/blob/master/src/libnfc-nci/nfa/int/nfa_ce_int.h
- https://github.com/open-vela/nuttx/blob/dev/include/nuttx/fs/fs.h

公开源码仅用于结构/语义对照，S4实际代码和实机数据优先。原生模块只是明确一个失败接口的诊断工具，不是公交卡/射频代理实现。
