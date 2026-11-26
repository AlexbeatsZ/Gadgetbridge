# 路线 B：S4 原生 NFC Host APDU 与射频代理

## 范围与当前结论

本路线研究并实现了「S4 NFC Host 蓝牙扩展手机 eSE 刷卡」链路：外部读卡器 ↔ S4 主机前端（`host-relay-session.c` + `host-relay-pump.c`，AID-first 路由交通联合 `A000000632010105` / 上海 `A0000000032005464D53484D4F43` / PPSE `2PAY.SYS.DDF01` 至 DH `0x00`）↔ 伴侣快应用（`com.meta.s4nativeprobe` 1.1.0/code17「NFC 刷卡」）↔ Classic SPP ↔ 手机 Gadgetbridge `XiaomiNfcRelayBridge` ↔ `local.meta.esenfc/.ApduRelayService`（0.6.0/code7，OMAPI `eSE1` 持久会话）。继续复用单表盘 ID `362991121` 与伴侣包名，按需开启并在退出/超时后自动恢复原表盘与原路由。

2026-11-26 蓝牙刷卡接入（经用户明确授权）：
- K70 `local.meta.esenfc` 新增签名+包名证书双校验的 `ApduRelayService`（仅放行同签名及 `nodomain.freeyourgadget.gadgetbridge`），将 `00 A4 04` SELECT AID 映射为 OMAPI `openBasicChannel(aid, p2)` 并复用持久化 `eSE1` 会话，按 `epoch` 隔离开卡状态。
- Gadgetbridge 新增 `XiaomiNfcRelayBridge` 进程内转发（省去 `logcat` + `attach-agent` 开销），收到「NFC 刷卡」的 `relay-open` 后预热 `eSE1`、切换至表盘 `362991121` 并在等待切换确认及 Lua 初始化后拉回伴侣前台；退出/断连自动关闭 `eSE1` 会话并恢复原表盘。
- 手表 `host-relay-session.c` / `host-relay-pump.c` 解除固定测试 APDU 及 `seq<=3` 限制，支持最长 261/260 字节任意 ISO-DEP C-APDU/R-APDU、`5 ms` 轮询与 `relay-stop.txt` 提前退出，退出时自动恢复原两项技术路由并卸载常驻模块。`verify_host_relay.py` 5 项离线 ARM 校验、伴侣 `verify_companion.mjs` 16 项及 Gadgetbridge 单测均通过。真实刷卡器贴卡交易、WTX 与有线交易兼容性待用户实机验收。

用户已改为优先推进蓝牙代理，A的真实迁移暂停。以下保留此前固定测试阶段记录：

2026-11-26 固定诊断伴侣生命周期修复：SPP 断开时撤销就绪状态和待收回复，复连后重新发送就绪。异步连接状态、文件读、重试及发送失败只在发起操作的连接代次生效，同一伴侣实例不重发曾发出的固定请求。已提交的文件写入等待平台完成，旧写入完成后不发送 ACK，复连后的就绪和下一笔固定请求也等到此时。现有手机及原生协议保持原格式，进程重启后重用相同 seq/epoch 的跨窗口情况以及原生发送队列仍需独立验证。14 项伴侣离线测试及 RPK code16 构建通过，未部署或重跑 RF。

2026-11-25 RF10串联实机进展：

- K70外部reader→S4 NFC控制器/原生测试处理→私有信箱→同包快应用→Classic SPP→K70 app_process固定后端→原GB消息/会话builder→SPP→快应用私有响应信箱→原生校验→原NFA action10c→射频→K70，取得**一次精确5334544553549000**，实际外部RTT709,344,947ns。另一轮为TagLostException，不是2/2稳定通过。
- native实读精确SELECT2、request文件写成2、有效手机response1、原NFA入队1、IO错误0，与reader成功1一致。HOST_SPP_BRIDGE没有本地固定应答回退；原生只在手机信箱seq/epoch/固定RAPDU匹配且Host仍活动时构造固定应答消息。未实现任意APDU转发，也没有访问真实SE/卡。
- 手机后端2请求/2watch ACK，JNI分别105,764,219ns/102,768,073ns；watch的Date.now整段1160/559ms包括发送排队与file.writeText完成，不是纯SPP射频时延。手机日志从请求到ACK分别165/184ms。第一失败与迟到结果/去激活相符，但未测到精确FWT/WTX原因；native tick-timeout计数0，不能声称800ms超时触发。
- 第一份collector在后台恢复末段读取到phase4/混合快照，按守卫拒绝卸载；随后后台保存的槽/回调/状态已经复原。重新采样后phase5、全部槽/回调/HAL/eSE/flags-state和完整原表均相等，原队列barrier通过，所有resident卸载。不能因RECOVERY_SEQUENCE_FINISHED文件或旧采样宣称最终清理；首次拒绝快照保留在RF10证据中。
- 用户明确反馈多次等待准备及反复贴近令人烦扰。本轮停止请求更多实体操作；后续先完成全部编译/暂存/自动验证，合并必要实体实验，只有全链真实就绪才提示操作。先做无需用户的耗时拆分、超时/过期拒绝与恢复改进。
- 证据xiaomi-s4-host-spp-rf10-2026-11-25.json；这是固定测试链路的实机进展，不是公交卡功能完成。WTX、稳定性、跨窗口代次和原发送队列入队后跨激活竞态仍需解决。

2026-11-25 rf8决定性实机成功（K70 Asia/Shanghai 11-26）：

- K70→S4 SELECT固定测试AID，S4主机原生HAL测试程序→原NFA发送队列→控制器→K70，连续两次收到精确5334544553549000；RTT92,725,573ns/92,220,156ns。不是SE管理APDU或模拟器结果，固定测试RF往返判据已通过。
- DATA时控制器Host标记1、DMstate5、own-listener0、完整handleFFFF已实读；HAL固定SELECT2/原NFA队列提交2与reader成功2相符。NFA RF6003仍0，不声称已恢复通用NFA CE服务。原eSE表/槽保持，模块按有界后台自动恢复，collector最终全部守卫/原表/队列barrier与卸载通过。
- 本轮只返回手表本地固定响应，没有经过SPP手机后端；WTX数量/链路串联/跨休眠稳定性/真实卡仍未通过。新证据xiaomi-s4-host-rf8-success-2026-11-25.json。原表盘/唯一测试face清理随后完成，唯一诊断RPK保留继续SPP。
- 后续独立SPP实验：现有com.meta.s4nativeprobe诊断RPK versionCode9只加入明确启动的3次固定SELECT请求/固定响应校验；K70临时app_process后端仅解析自己RPK的固定nonce/SELECT，调用临时JNI原message/session builders返回固定响应，规避已定位的旧版前台RPK反复launch问题，不修改生产app或安装APK。该实验不把Mac/ADB放进逐条应答路径，不连接SE，仍与RF串联验证分开记录。

2026-11-25 独立Classic SPP固定后端成功：

- 同一个诊断RPK（最终versionCode9）发3次固定SELECT，由K70的30秒app_process后端识别并经临时JNI调用**已安装GB的原message/session builders**发送固定RAPDU。手表ACK3/3，RTT122/123/146ms；Mac/ADB仅启动和取证，不在逐条应答路径。无APK、无生产app修改、无SE访问。
- 首轮request0/timeout1：start消息处理时发出的首包没有到达GB，后续timeout通知正常到达。连接就绪顺序是依据现象的推断；修复为延后并同步getReadyState有界确认后再发，最终3/3。不把首轮的agent调用/发送提交当成功。
- 当前两段独立实机均通过，但**RF→native→quickapp→SPP→K70→SPP→native→RF尚未串联**。下一核心为无阻塞native/quickapp IPC、带会话/序号的过期拒绝、端到端deadline和WTX观察。研究手机端逐条attach-agent耗时约103–105ms，非生产延迟基线；SPP RTT不可直接与RF RTT相加作为已测总时延。
- 源码S4SppTestBackend.java、watch-ops.cpp固定SPP模式和原companion扩展；证据xiaomi-s4-spp-fixed-backend-2026-11-25.json。只允许固定probe包/nonce/seq1..3/测试APDU，未知数据不应答。

2026-11-25 原生/快应用私有文件IPC实机与串联原型：

- 原生同步ET_REL探针读到快应用写入S4IPC-QUICKAPP-1125，原生通过原固件POSIX open/read/write/close写回S4IPC-NATIVE-1125，快应用通过system.file读回，probe phase5/卸载通过。usleep(10000)亦返回0。这是实际ABI/双向文件可见性，未连接NFC/SPP路径。
- 原NSH cmp/cp/usleep调用点与ROM指纹确认：open2c55d839/read2c55d5e1/write2c55d61b/close2c55af95/usleep2c20dc11。读flags1、写创建截断flags0x26来自**此固件cp**；现代NuttX头值不同，不能照搬。代码只用诊断包两条固定私有路径，fd0有效，短读/短写/指纹不符拒绝。5项编译探针离线通过。
- 用户在并行工作中于K70中国时间11-26 01:02:46更新GB至2026.11.18/code273，PID6001→6681，exit-info为PACKAGE UPDATED，code_cache临时库被清掉，attach-agent调用返回并不代表库实际执行。用户确认更新且要求不用管；本研究不回退、不重新配对，重新stage诊断库后原绑定回包恢复。此前RF/SPP结果属于PID6001/旧安装时段，后续IPC属于新PID。临时helper新增库可读性前检和仅自己包的消息过滤，避免混入并行Dayweave包内容。
- 串联原型只在HOST_SPP_BRIDGE构建开启：HAL精确SELECT只登记seq/activation epoch，不做文件IO/等待/本地固定应答；NSH每片1秒的同步模块调用resident pump 50次/20ms，独立读写私有请求/应答文件，再复制固定RAPDU入原NFA action10c。去激活/新激活/STOP/GET清待处理，错序号/过期epoch/非固定响应/原eSE句柄拒绝，40次pump等待上限约800ms加IO，真实时延仍须实测。
- result扩大为360字节，旧296字节前缀不变；ipc[0]=53505049，[1]=bridge enabled，[2]=同步pump指针，[3]=pump在途，[4]=pending stage0/1/2，[5]=当前activation epoch，[6]=seq1..3，[7]=request epoch，[8]=请求写成次数，[9]=有效response次数，[10]=响应入队数，[11]=等待tick，[12]=IO错误，[13]=请求拒绝，[14]=超时。collector需90words、pump在途和pending为0才允许卸载。原NFA发送消息没有module指针。
- 同包诊断RPK11显式rf-start后读固定请求JSON，经原SPP交K70有界后端；回复只有seq1..3/epoch1..65535/固定RAPDU，写S4RF1:seq:epoch:5334544553549000供原生pump。启动时清两个信箱，默认诊断仍短时退出。12项串联状态离线通过；现实并发、RF总时延、WTX、原send enqueue后跨激活竞态仍未验证，后者为真实卡/生产实现前必须解决的条件。

2026-11-25 串联RF9与诊断生命周期：

- 用户更新后的GB发生一次研究JNI崩溃（K70 01:24:38，PID6681，SIGSEGV，栈在libgbwatchops-rfbridge.so）；此前暂存使用cp覆盖已加载库，怀疑截断mapped inode导致崩溃，但没有受控复现，不当作确定因果。改为不可变构建名v4、单独.new后rename、attach前可读性检查；调用原DeviceService.connect()重连原绑定，PID11449实际RFCOMM连接/认证加密/初始化及九原表盘回包通过。未开Connection API、未重配对、未改生产app。两处spp-test分发条件遗漏spp-rf模式亦已修复。
- RF9收到两次K70测试SELECT错误TagLostException，原SPP后端在贴近期间request0。找到快应用轮询误用同步getReadyState()!==1：官方API为getReadyState({success(data)})/data.status；独立SPP原有onopen回退掩盖此误用。现改onopen/异步状态缓存，增加明确bridge-ready，仅自己包文件，不增加表盘ID。8项实际companion方法离线检查覆盖异步API、去重、错seq/epoch/响应、停止和独立模式，非实机。
- 修复RPK13后，在RF9已去激活期间读到旧信箱seq2/epoch9，经K70临时后端收到固定响应，watch ACK177ms；这是原生信箱→SPP→手机→SPP→信箱的真实链路，不是外部RF应答，必须由native epoch/Host守卫拒绝迟到结果。RF9 collector实读HAL精确SELECT2、request文件写成2、有效手机response0、RF入队0、IOerror0；phase5、完整旧表/槽/回调/eSE/barrier和全部卸载通过。
- RF10启动前遇到新版GB的30秒recent-open缓存误判：诊断RPK已自动退出，sendWearAppMessage仍直接发送，实机日志明确is open, sending directly，无launched或ready回包。临时JNI v5仅移除com.meta.s4nativeprobe的lastSeenOpen内存项，再调用原启动/排队流程；不改生产源码或其他包状态。RPK15的ready有250/750ms有界重发（启动初包可能丢失），随后实际收到两条bridge-ready/connected=true才提示用户贴近。RF10后端当时仍运行v4 DEX/回复库，v5仅用于启动；未覆盖正在使用的DEX/so文件。
- K70无线ADB随后丢失，先检查本机daemon/设备清单/mDNS，再确认旧端口拒绝及Tailscale在线；用户重新启用后发现新transport（名称附(2)），PID11449和临时v4库仍在，未重启adb daemon或改代理。恢复复核完成前不启动RF10。

2026-11-25 rf6实机（K70 Asia/Shanghai 11-26）：

- 用户确认两次贴近。HAL实际收到3个conn0/NCI DATA，长度16，全部精确匹配13字节固定SELECT；自身Host activation/deactivation各4次。射频→控制器→主机HAL已真实通过，RF DATA6003仍0，K70 SELECT阶段3次TagLostException，没有外部固定响应。
- 激活尾部曾实读自身RF callback，但HAL收到DATA时owner再次为stock sink2c34472d；覆盖发生点仍需进一步定位，不能把原NXP相似分支当成已确认的实机调用者。
- 自动恢复后collector确认phase5、原槽40字节、RF/DISC/HAL、eSE8101、flags/state01000100和完整原表均恢复，原队列barrier后模块为空。GB本轮PID6001；这是11-23之后的新进程，不沿用旧PID10882作连续性证明。原表盘已恢复，测试face已删。
- 新研究原型仅在完整固定SELECT、conn0且当前自身slot1/Host/listen-active时，将复制的S4TEST9000消息放入原nfa_sys_sendmsg→action10c，HAL不直接调用RF send/drain、不改RF指针，所有入帧仍原样交原HAL。队列消息无resident指针；原发送处理器在实际执行时拒绝非active状态。RF收到同帧时抑制重复应答。rf7的rf_meta[13]为提交时phase/state/own-active/handle低12位位域，[14]为队列提交数，[15]为待消费的同帧抑制标志；提交不等于RF发送成功。
- 27项原ARM/编译探针离线检查通过，包含stock sink覆盖后新队列路径、原send处理器、同帧去重、分离后拒绝和分配失败/非测试帧/原eSE handle保护。实际新应答路径尚待实机验证。证据分别为xiaomi-s4-host-rf6-2026-11-25.json和xiaomi-s4-host-hal-queue-offline-2026-11-25.json。

2026-11-25 rf7实机与后续原型：

- 新队列应答原型的无贴近dry4实机自动恢复、原表/槽/回调复核及卸载通过。rf7用户两次贴近，HAL精确测试SELECT=2；新诊断在DATA到达时记录phase4/state5/own-active0/handle低12位FFF（旧打包截断，完整FFFF仅为候选），提交数0。不是已发送但丢失，明确为自身listener不活动、非handle1而被保护条件拒绝；RF/DISC回调覆盖的完整调用者仍未确定。
- 下一原型额外识别完整NCI 61/05激活通知中的interface2/protocol4/mode80（不读UID/激活参数），61/06去激活或STOP/GET撤销时清除标记。只有控制器Host Listen-A标记有效、DMstate5、handleFFFF、原eSE槽未改、测试slot1仍归自己时，才允许同一固定SELECT复制应答入原NFA队列；handle0/其他handle、direct-eSE/interface0、Poll、畸形激活、过期会话均拒绝。已有自身listener/handle1路径保留。结构ABI/恢复过程不改；rf8改正诊断打包：rf_meta[13]的bits0..3为phase，4..7为state，bit8为own-active，bit9为控制器Host标记，16..31为完整handle。
- 新33项离线检查全部通过；这项Host通知回退仍须真实SELECT应答验证，不能将允许入队当成RF成功。

2026-11-22 后续实机（手机Asia/Shanghai 11-23中午）：

- 常驻Host测试会话、slot1注册、原栈STOP/START、固定测试AID增加、超时恢复、完整旧表复核、原队列barrier及卸载已实机通过。零窗口曾因NSH拒绝`sleep 0`中断脚本，受保护collector拒绝卸载；补完恢复后修复为省略延时命令，后续自动恢复预演通过。不能把`os.execute`返回成功当作后台脚本完成。
- K70临时ReaderMode实读`mTechMask: 1 / mEnableReader: true / mEnableHostRouting: false / ON_UNLOCKED`。用户明确在窗口内贴近，rf2有7次Host ISO-DEP激活，rf3有10次，rf4有4次；元数据protocol4/listenA80/interface2，实际激活进入slot1，并逐次去激活/恢复RF回调。**Host激活实机通过，SELECT/固定响应尚未通过。**
- K70 `IsoDep.connect()`成功，`transceive(test SELECT)`报TagLostException。rf3原激活回调返回后RF owner实读为原sink2c34472d；新增尾部仅在自身Host活动时重接，rf4证明重接到本模块，但仍只有去激活6002、没有DATA6003；不能把回调覆盖修复当成APDU成功。
- 新增HAL被动计数，只计类型/长度/测试SELECT精确命中，不复制未知payload或UID，所有帧原样转发；原STOP或GET响应在owner内自撤销，原HAL查询barrier后才卸载。无贴近dryrun3实机通过（HALactive0、detach reason STOP、原表/槽/回调恢复、模块空）。外部RF HAL计数实验待做。
- 主要脱敏证据`docs/evidence/xiaomi-s4-host-session-2026-11-22.json`；每次现场原始probe记录保留Mac任务临时目录。研究中的唯一测试表盘/RPK与手机/ROG文件仍待最终清理；生产应用PID10882保持，不安装APK，不触及真实卡。

2026-11-22 新实机进展（手机Asia/Shanghai时间为11-23上午）：

- 已完整捕获只读 GET_ROUTING 的通知 `61020c00020003c0e3000003c0e302`：两条技术路由为NFC-A/F→eSE C0，原始power字节均e3。HAL原owner实读为2c34cd85；只捕获路由通知、其他帧原样转发，随后自撤销，原HAL后续查询barrier完成、模块卸载。
- 控制器能力实读features00063e3a，AID routing支持位存在，capacity1030、ctrl payload255、NCI版本20。ISO-DEP discovery map引用实读与镜像对照。能力声明不等于RF成功。
- 原NFA STOP/START实机达到flags/state `01000100 → 00000000 → 01000100`，eSE flags8101保持。
- **测试AID路由增加和撤销真实通过**：`F0010203040506→DH00/power01`必须放在原技术路由前面。SET响应41010100，GET回读三项，原栈重启discovery后仍存在；恢复原两项响应41010100，再次重启后完整旧表相等。没有外部读卡器/APDU/SPP测试。
- 第一次AID放在技术项后返回41010103（通用FAILED，不是INVALID_PARAM09），随后GET为空。立即用已捕获旧表恢复并读回，旧表/发现状态恢复。**SET失败也可能丢表，不能只看状态或以空SET清理。**
- 额外实读两组RF discovery注册槽：slot0用于eSE，slot1为空。在停止发现后，通过GET响应进入原NFA任务调用原add/delete，实际得到handle1，恢复两槽全部40字节，默认RF callback未变；重新发现正常、模块卸载。该实验没有让注册跨越真实激活。
- 已验证NSH脚本后台执行：Lua调用`sh <probe-private-script> > <probe-private-output> &`在0秒返回，独立脚本3秒后写入完成标记。后续RF等待和超时恢复应使用后台步骤，长Lua曾导致SPP短断连、原绑定自动恢复。

2026-11-22 实机：原始 NFA CE 分发器对虚构 AID `F0010203040506` 返回 **REGISTERED事件0x15、status3、handle0xFFFF**，注册失败。当前固件CE动作表中的配置本地卡/注销/ISO-DEP配置槽是同一个空函数；注册处理器仅保留可用的eSE监听分支，当前eSE监听项已占用。

这是原生栈入口的具体阻塞证据，**不是整个硬件不支持HCE的结论**。尚未完成测试AID射频SELECT/固定应答、SPP代理、时延/WTX测量，不能称功能完成。没有执行真实卡APDU或支付交易，没有停止NFC任务/原reader或操作真实卡；上述后续实验仅暂停/恢复RF discovery并测试可恢复的虚构AID路由。

2026-11-23 续接：rf5因用户明确错过窗口而没有贴近，按未完成操作记录，不算技术失败；后台仍自行恢复，续接后最终旧表/槽/回调/eSE/HAL与卸载复核通过。rf6五分钟资源已暂存但尚未启动，等待用户准备。

补充原数据入口离线验证：从client-task的DATA分支2c351018执行原RF连接查找、GKI enqueue/dequeue和data-event drain，NCI头00000d+固定SELECT得到len13/offset7、测试处理器一次命中、固定响应提交、原输入只释放一次和空接收队列。临界区、内存池检查与任务循环是合成边界；16项检查通过不代表控制器实际交付或外部读卡器收包。新证据`xiaomi-s4-host-ingress-offline-2026-11-23.json`。

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

GET_ROUTING状态响应与`61 02`通知是两个包；此前普通NFC分发裁剪了后者。现已通过实读的HAL槽3c2d45cc→trampoline2c340d35→original2c34cd85链捕获完整通知；见`get-routing-capture.c/.lua.in`。最多4包/1040字节，仅保留6102，其他包只计数/原样转发。最终通知或第二个只读查询响应触发callback owner内自撤销；确认active0、HAL恢复后，再经过原HAL查询barrier才卸载。原始实机只观测到对应RSP/NTF两包，不声称所有复位/恢复并发场景已验证。

`routing_table.py`严格校验通知序列、TLV计数和完整长度，保存原字节及相对顺序；未知项可备份，但拒绝生成未经理解的写计划。当前单包写计划拒绝大于253字节TLV，不能猜分段。AID-first规划来自较新AOSP/NXP路由构造器（nfa_ee_lmrt_to_nfcc按AID→protocol→technology）；S4两次实机对照保持其他字节不变后证实顺序差异会决定接受/拒绝。

实机成功命令及回读：

```text
add:     210117000302090001f00102030405060003c0e3000003c0e302
response:41010100
routing: 610217000302090001f00102030405060003c0e3000003c0e302
restore: 21010c00020003c0e3000003c0e302
response:41010100
routing: 61020c00020003c0e3000003c0e302
```

失败对照为AID-last `21011700030003c0e3000003c0e30202090001f0010203040506`，状态03后通知6102020000；已恢复，证据独立保存，不覆盖失败历史。恢复原表只移除拥有的测试项，不清空eSE表。

## Host分发剩余条件

原RF discovery API：add=2c344755，delete=2c341311，两个entry从DM+58起、步长20。add写callback@4、host_id@10、disc_mask@12、selected mask@16；delete只清in_use，**不会清callback指针**。因此探针必须在停止发现的原NFA任务中注册/删除，并恢复原本空闲entry的全部20字节；不能仅删in_use后就不检查地卸载。现已实机验证同步add/delete（新handle1），尚未验证长时间驻留及真实激活的所有权。

原activate dispatcher=2c3410a1，参数activate.protocol@1、tech_mode@2、interface@27。原ARM离线执行证明：ISO-DEP/interface2/listenA80时，旧technology host映射DM+11e=C0仍选原eSE listener；在合成RAM中临时DH映射00且selected mask已由模拟discovery阶段设40000时才选spare Host listener；interface0的direct eSE路径仍到原listener。**这只是离线分发证据，不能直接在实机永久改映射。**下一步须验证选择性Host激活分发、原RF callback安装/去激活恢复、队列/在途撤销，且仅处理固定测试AID/APDU。

下一项实机实验先建立自动超时/恢复的短后台步骤，读卡端可用K70独立临时app_process工具（只发送测试SELECT，无APK/生产app改动，ReaderMode结束必须恢复）。只有选择性回调与自动恢复路径离线和实机预演通过后，才让用户贴近手机与手表。成功判据仍为外部reader实际收到S4主机固定RAPDU；controller路由增加成功本身不够。

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

## 本轮证据与状态（最新）

- 路由通知、控制器能力、discovery stop/start、AID-last失败、AID-first成功/恢复、RF slots读数/同步add-delete、NSH后台分别保存在`docs/evidence/xiaomi-s4-{routing-notification,controller-capabilities,discovery-roundtrip,aid-last-rejected,test-aid-routing,discovery-slots,discovery-slot-roundtrip,background-execution}-2026-11-22.json`。
- 本轮新离线定向检查：capture14、discovery12、constant writers12、routing parser6、discovery slots9均通过。现有GET状态7例仍通过。所有离线数据明确标模拟边界。
- 最近实读：原路由恢复、flags/state01000100、eSE8101、HAL2c34cd85、lsmod空；原表盘已多次切回昼与夜。研究仍在进行，唯一测试face/RPK及手机/ROG暂存未最终撤下，不能沿用此前“已清理”作为本轮结束结论。实际最终清理另记。

## 生产蓝牙刷卡转发架构（2026-11-26）

在固定测试链路验证完成后，正式接入端到端按需蓝牙 APDU 转发：

1. **三端组件**：
   - **K70 eSE 适配器**（`local.meta.esenfc` `0.6.0/code7`）：`ApduRelayService` + `EseApduRelay` 提供绑定 `Messenger` 会话，通过 `EseCardReader.SE_LOCK` 串行复用 `eSE1` OMAPI `Session`，将 `00 A4 04` SELECT AID 映射为 `openBasicChannel(aid, p2)` 并按 `epoch` 复位通道；严格校验调用方为 Gadgetbridge 签名证书且仅放行交通联合 `A000000632010105`、上海 `A0000000032005464D53484D4F43`、PPSE `2PAY.SYS.DDF01`、PSE `1PAY.SYS.DDF01`。
   - **Gadgetbridge 进程内转发桥**（`2026.11.19/code274`）：`XiaomiRpkService` 直接截获 `com.meta.s4nativeprobe` 的 `relay=s4` 消息交由 `XiaomiNfcRelayBridge` 处理，不经过 `logcat`/`attach-agent`。
   - **Watch S4 转发端**：表盘 `362991121`（`relay-session.lua.in` + `host-relay-session.c` + `host-relay-pump.c` + `relay-route-write.c`）与快应用「NFC 刷卡」（`com.meta.s4nativeprobe` `1.1.1/code18`）。
2. **启动与生命周期时序**：
   - S4 上 `CMD_WATCHFACE_SET` 不会关闭前台快应用，因此 `XiaomiNfcRelayBridge` 先实时刷新活动表盘（`refreshAndGetActiveWatchface`），需要触发 `362991121` 时先发 `yield-for-face` 让快应用 `terminate()`（不写 `relay-stop.txt` 也不发 `relay-close`），等待 600 ms 后切至 `362991121`（若已停留在 `362991121` 且状态非 `active`/`bg-launched` 则先回弹到其他表盘再切回），等 `CMD_WATCHFACE_SET` ACK 及 6.5 s 后台路由/Hook 就绪后再 `CMD_RPK_LAUNCH` 重开快应用并下发 `relay-open-ack`。
   - 原生模块在 NSH 后台以 10 ms × 100（精准 1 s）切片运行 180 s，写 `relay-stop.txt` 或窗口到期后退出；所有含异步 NFA 回调的模块（`gb_route_append`、`gb_host_hal_barrier`、`gb_route_restore`、`gb_host_barrier2`、`gb_host_test`）均在 `sleep 1` 与原队列屏障完成后再 `rmmod`，避免回调 UAF。
   - 退出「NFC 刷卡」时写 `relay-stop.txt` 并发送 `relay-close`，手机端关闭 `eSE1` 会话并恢复原活动表盘。
3. **验证边界**：
   - 已通过：K70 `local.meta.esenfc` 22 项单测与 `ApduRelayService` 实机建连；GB 定向单测、`verify_companion.mjs` 17 项、`verify_host_relay.py` 5 项 Unicorn 离线执行；K70 + S4 真机冷启动至 `S4 watch relay status=active`。
   - 待用户实机验收：真实地铁/公交/校园读卡器贴近时的整段射频时延、WTX 行为以及 `80 50`/`80 54` 在 OMAPI 有线通道的兼容性。
